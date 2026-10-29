package io.github.saocsb.mcai.core.script;

import io.github.saocsb.mcai.core.blueprint.BlockIds;
import io.github.saocsb.mcai.core.blueprint.BlockSink;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.BlueprintLimitException;
import io.github.saocsb.mcai.core.blueprint.Noise;
import io.github.saocsb.mcai.core.blueprint.PixelFont;
import io.github.saocsb.mcai.core.blueprint.Shapes;
import io.github.saocsb.mcai.core.placement.GroundMode;
import io.github.saocsb.mcai.core.placement.PlacementMode;
import io.github.saocsb.mcai.core.placement.PlacementSpec;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.ScriptStackElement;
import org.mozilla.javascript.Undefined;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

/**
 * State and API functions of one script run: the blueprint, world actions, logs, placement, name and
 * the seeded random generator. Every {@link ScriptEngine#run} creates its own session.
 */
final class ScriptSession {
	static final String AIR = "minecraft:air";
	/** Writes between deadline/interrupt checks inside one shape call. */
	private static final int CHECK_INTERVAL = 1024;
	private static final int ID_CACHE_MAX = 4096;
	private static final int MAX_POLYGON_POINTS = 4096;
	private static final int MAX_LOG_LINE_CHARS = 1000;
	private static final int MAX_NAME_LENGTH = 64;
	private static final int MAX_COMMAND_LENGTH = 32_000;
	/** Longest pause {@code wait()} accepts, in seconds. */
	static final double MAX_WAIT_SECONDS = 600;
	/** Longest block id, NBT or other string argument accepted (checked before a concatenated string is flattened). */
	private static final int MAX_ARGUMENT_LENGTH = 65_536;
	/** Host calls and guarded built-in calls between deadline checks. */
	private static final int TICK_INTERVAL = 64;
	private static final int MAX_DISTANCE = 64;
	private static final int WORLD_COORD_LIMIT = 30_000_000;
	private static final double SAFE_INTEGER = 9_007_199_254_740_992d;
	private static final Pattern RESOURCE_ID = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

	private static final String[] P_BLOCK = {"x", "y", "z", "id"};
	private static final String[] P_BOX = {"x1", "y1", "z1", "x2", "y2", "z2", "id"};
	private static final String[] P_CLEAR = {"x1", "y1", "z1", "x2", "y2", "z2"};
	private static final String[] P_SPHERE = {"cx", "cy", "cz", "r", "id"};
	private static final String[] P_ELLIPSOID = {"cx", "cy", "cz", "rx", "ry", "rz", "id"};
	private static final String[] P_CYLINDER = {"cx", "y", "cz", "r", "height", "id"};
	private static final String[] P_PYRAMID = {"cx", "y", "cz", "half", "id"};
	private static final String[] P_TORUS = {"cx", "cy", "cz", "R", "r", "id"};
	private static final String[] P_POLYGON = {"points", "y", "height", "id"};
	private static final String[] P_PATH = {"points", "id"};
	private static final String[] P_TEXT = {"str", "x", "y", "z", "id"};
	private static final String[] P_REPLACE = {"x1", "y1", "z1", "x2", "y2", "z2", "from", "to"};
	private static final String[] P_GET = {"x", "y", "z"};
	private static final String[] P_RANDINT = {"min", "max"};
	private static final String[] P_PICK = {"array"};
	private static final String[] P_NOISE = {"x", "y"};
	private static final String[] P_PLACEMENT = {"options"};
	private static final String[] P_NAME = {"name"};
	private static final String[] P_SUMMON = {"entityId", "x", "y", "z"};
	private static final String[] P_GIVE = {"itemId"};
	private static final String[] P_TIME = {"value"};
	private static final String[] P_EFFECT = {"effectId"};
	private static final String[] P_COMMAND = {"cmd"};
	private static final String[] P_WAIT = {"seconds"};

	@FunctionalInterface
	private interface Impl {
		Object call(Object[] args);
	}

	@FunctionalInterface
	private interface BoxShape {
		void draw(BlockSink sink, int x1, int y1, int z1, int x2, int y2, int z2, String id);
	}

	@FunctionalInterface
	private interface RoundShape {
		void draw(BlockSink sink, int cx, int cy, int cz, double r, String id, boolean hollow);
	}

	private final ScriptLimits limits;
	private final long seed;
	private final long deadlineNanos;
	private final String[] sourceLines;
	private final Blueprint blueprint;
	private final SplittableRandom random;
	private final long allocationStart;
	private final long allocationBudget;
	private Noise noise;
	private final List<WorldAction> actions = new ArrayList<>();
	private final List<String> logs = new ArrayList<>();
	private boolean logTruncated;
	private PlacementSpec placement = PlacementSpec.defaults();
	private String name;
	private final Map<String, String> idCache = new HashMap<>();
	/** Palette id -> what get() returns for it (bounded by the blueprint's palette limit). */
	private final Map<String, String> shortIds = new HashMap<>();
	private ScriptableObject scope;
	private int ticks;

	ScriptSession(String source, ScriptLimits limits, long seed, long startNanos) {
		this.limits = limits;
		this.seed = seed;
		this.deadlineNanos = startNanos + Math.max(0, limits.timeoutMillis()) * 1_000_000L;
		this.sourceLines = source.split("\r\n|[\n\r  ]", -1);
		this.blueprint = new Blueprint(limits.maxBlocks(), limits.maxSpan());
		this.random = new SplittableRandom(seed);
		this.allocationStart = AllocationMeter.currentThreadAllocatedBytes();
		this.allocationBudget = allocationBudget();
	}

	ScriptLimits limits() {
		return limits;
	}

	Blueprint blueprint() {
		return blueprint;
	}

	PlacementSpec placement() {
		return placement;
	}

	String name() {
		return name;
	}

	List<WorldAction> actions() {
		return List.copyOf(actions);
	}

	List<String> logs() {
		return List.copyOf(logs);
	}

	// ---- abort handling -------------------------------------------------------------------

	/** Throws a {@link ScriptAbort} when the thread was interrupted or the time budget is used up. */
	void checkAbort() {
		if (Thread.currentThread().isInterrupted()) {
			throw new ScriptAbort(ScriptException.Kind.CANCELLED, ScriptEngine.CANCELLED_MESSAGE, -1);
		}
		if (System.nanoTime() - deadlineNanos > 0) {
			throw new ScriptAbort(ScriptException.Kind.TIMEOUT,
					"TimeoutError: script ran longer than " + limits.timeoutMillis() + " ms", currentLine());
		}
		if (allocationStart >= 0 && AllocationMeter.currentThreadAllocatedBytes() - allocationStart > allocationBudget) {
			throw limit("the script allocated more than " + (allocationBudget >> 20) + " MB of memory");
		}
	}

	/**
	 * Bytes a run may allocate (garbage included): half the maximum heap, between 256 MiB and 4 GiB.
	 * Typical 300,000-block scripts allocate 75 to 350 MB.
	 */
	static long allocationBudget() {
		return Math.max(256L << 20, Math.min(4L << 30, Runtime.getRuntime().maxMemory() / 2));
	}

	/** Cheap per-call hook: runs {@link #checkAbort()} every {@value #TICK_INTERVAL} calls. */
	void tick() {
		if (++ticks >= TICK_INTERVAL) {
			ticks = 0;
			checkAbort();
		}
	}

	ScriptAbort limit(String message) {
		return new ScriptAbort(ScriptException.Kind.LIMIT, limitMessage(message), currentLine());
	}

	/** Prefixes "LimitError: " unless the message already has it (Blueprint's messages do). */
	static String limitMessage(String message) {
		return message != null && message.startsWith("LimitError:") ? message : "LimitError: " + message;
	}

	/** Line of the innermost script frame that is running, or -1. */
	static int currentLine() {
		try {
			for (ScriptStackElement e : new EvaluatorException("").getScriptStack()) {
				if (ScriptEngine.SOURCE_NAME.equals(e.fileName) && e.lineNumber > 0) {
					return e.lineNumber;
				}
			}
		} catch (RuntimeException ignored) {
			// no stack information available
		}
		return -1;
	}

	/** Appends " (line N: source)" to an error message; the source line is trimmed to 120 characters. */
	String withLine(String message, int line) {
		int n = clampLine(line);
		if (n < 1) {
			return message;
		}
		String text = sourceLines[n - 1].strip();
		if (text.length() > 120) {
			text = text.substring(0, 117) + "...";
		}
		return message + " (line " + n + ": " + text + ")";
	}

	/**
	 * A line number limited to the script's last non-blank line (errors at the end of the input are
	 * reported on the wrapper's closing line), or -1 when unknown.
	 */
	int clampLine(int line) {
		return line < 1 ? -1 : Math.min(line, lastLine());
	}

	private int lastLine() {
		int n = sourceLines.length;
		while (n > 1 && sourceLines[n - 1].isBlank()) {
			n--;
		}
		return n;
	}

	/** Source line {@code line} (1-based), or "" when out of range. */
	String sourceLine(int line) {
		return line >= 1 && line <= sourceLines.length ? sourceLines[line - 1] : "";
	}

	// ---- installation ---------------------------------------------------------------------

	/** Defines the API functions, the console object and the seeded Math.random on the scope. */
	void install(ScriptableObject scope) {
		this.scope = scope;
		define(scope, "block", 4, this::block);
		define(scope, "fill", 7, a -> box(a, "fill", Shapes::fill));
		define(scope, "hollow", 7, a -> box(a, "hollow", Shapes::hollow));
		define(scope, "walls", 7, a -> box(a, "walls", Shapes::walls));
		define(scope, "frame", 7, a -> box(a, "frame", Shapes::frame));
		define(scope, "clear", 6, this::clear);
		define(scope, "line", 7, this::line);
		define(scope, "sphere", 5, a -> round(a, "sphere", Shapes::sphere));
		define(scope, "dome", 5, a -> round(a, "dome", Shapes::dome));
		define(scope, "ellipsoid", 7, this::ellipsoid);
		define(scope, "cylinder", 6, a -> cylinder(a, "cylinder"));
		define(scope, "cone", 6, a -> cylinder(a, "cone"));
		define(scope, "pyramid", 5, this::pyramid);
		define(scope, "torus", 6, this::torus);
		define(scope, "polygon", 4, this::polygon);
		define(scope, "path", 2, this::path);
		define(scope, "text", 5, this::text);
		define(scope, "replace", 8, this::replace);
		define(scope, "get", 3, this::get);
		LambdaFunction randomFn = define(scope, "random", 0, a -> random.nextDouble());
		define(scope, "randInt", 2, this::randInt);
		define(scope, "pick", 1, this::pick);
		define(scope, "noise", 2, this::noise);
		define(scope, "placement", 1, this::placement);
		define(scope, "setName", 1, this::setName);
		define(scope, "summon", 4, this::summon);
		define(scope, "give", 1, this::give);
		define(scope, "time", 1, this::time);
		define(scope, "weather", 1, this::weather);
		define(scope, "effect", 1, this::effect);
		define(scope, "command", 1, this::command);
		define(scope, "wait", 1, this::waitAction);
		define(scope, "log", 0, this::log);
		define(scope, "print", 0, this::log);

		ScriptableObject console = (ScriptableObject) Context.getCurrentContext().newObject(scope);
		for (String method : new String[] {"log", "info", "warn", "error", "debug"}) {
			define(console, scope, method, 0, this::log);
		}
		ScriptableObject.defineProperty(scope, "console", console, ScriptableObject.DONTENUM);

		NativeGuards.install(scope, this);

		Object math = ScriptableObject.getProperty(scope, "Math");
		if (math instanceof ScriptableObject m) {
			m.defineProperty("random", randomFn, ScriptableObject.DONTENUM);
		}
	}

	private LambdaFunction define(ScriptableObject scope, String fn, int arity, Impl impl) {
		return define(scope, scope, fn, arity, impl);
	}

	private LambdaFunction define(ScriptableObject target, Scriptable scope, String fn, int arity, Impl impl) {
		LambdaFunction f = new LambdaFunction(scope, fn, arity, (cx, s, thisObj, args) -> invoke(fn, impl, args), false);
		target.defineProperty(fn, f, ScriptableObject.DONTENUM);
		return f;
	}

	private Object invoke(String fn, Impl impl, Object[] args) {
		tick();
		try {
			return impl.call(args);
		} catch (BlueprintLimitException e) {
			throw limit(e.getMessage());
		} catch (IllegalArgumentException e) {
			String m = String.valueOf(e.getMessage());
			throw ScriptRuntime.typeError(m.startsWith(fn + "(") ? m : fn + "(): " + m);
		}
	}

	// ---- blocks ---------------------------------------------------------------------------

	private Object block(Object[] a) {
		need(a, "block", P_BLOCK);
		int x = coord(a, 0, "block", "x");
		int y = coord(a, 1, "block", "y");
		int z = coord(a, 2, "block", "z");
		blueprint.set(x, y, z, blockId(a, 3, "block"));
		return Undefined.instance;
	}

	private Object box(Object[] a, String fn, BoxShape shape) {
		need(a, fn, P_BOX);
		int x1 = coord(a, 0, fn, "x1");
		int y1 = coord(a, 1, fn, "y1");
		int z1 = coord(a, 2, fn, "z1");
		int x2 = coord(a, 3, fn, "x2");
		int y2 = coord(a, 4, fn, "y2");
		int z2 = coord(a, 5, fn, "z2");
		String id = blockId(a, 6, fn);
		CountingSink sink = new CountingSink(false);
		shape.draw(sink, x1, y1, z1, x2, y2, z2, id);
		return sink.count();
	}

	private Object clear(Object[] a) {
		need(a, "clear", P_CLEAR);
		CountingSink sink = new CountingSink(false);
		Shapes.fill(sink, coord(a, 0, "clear", "x1"), coord(a, 1, "clear", "y1"), coord(a, 2, "clear", "z1"),
				coord(a, 3, "clear", "x2"), coord(a, 4, "clear", "y2"), coord(a, 5, "clear", "z2"), AIR);
		return sink.count();
	}

	private Object line(Object[] a) {
		need(a, "line", P_BOX);
		int x1 = coord(a, 0, "line", "x1");
		int y1 = coord(a, 1, "line", "y1");
		int z1 = coord(a, 2, "line", "z1");
		int x2 = coord(a, 3, "line", "x2");
		int y2 = coord(a, 4, "line", "y2");
		int z2 = coord(a, 5, "line", "z2");
		String id = blockId(a, 6, "line");
		double radius = optNumber(a, 7, 0, "line", "radius");
		checkRadius("line", "radius", radius);
		CountingSink sink = new CountingSink(radius > 0);
		Shapes.line(sink, x1, y1, z1, x2, y2, z2, id, radius);
		return sink.count();
	}

	private Object round(Object[] a, String fn, RoundShape shape) {
		need(a, fn, P_SPHERE);
		int cx = coord(a, 0, fn, "cx");
		int cy = coord(a, 1, fn, "cy");
		int cz = coord(a, 2, fn, "cz");
		double r = number(a, 3, fn, "r");
		String id = blockId(a, 4, fn);
		boolean hollow = flag(a, 5);
		checkRadius(fn, "r", r);
		CountingSink sink = new CountingSink(false);
		shape.draw(sink, cx, cy, cz, r, id, hollow);
		return sink.count();
	}

	private Object ellipsoid(Object[] a) {
		need(a, "ellipsoid", P_ELLIPSOID);
		int cx = coord(a, 0, "ellipsoid", "cx");
		int cy = coord(a, 1, "ellipsoid", "cy");
		int cz = coord(a, 2, "ellipsoid", "cz");
		double rx = number(a, 3, "ellipsoid", "rx");
		double ry = number(a, 4, "ellipsoid", "ry");
		double rz = number(a, 5, "ellipsoid", "rz");
		String id = blockId(a, 6, "ellipsoid");
		boolean hollow = flag(a, 7);
		checkRadius("ellipsoid", "rx", rx);
		checkRadius("ellipsoid", "ry", ry);
		checkRadius("ellipsoid", "rz", rz);
		CountingSink sink = new CountingSink(false);
		Shapes.ellipsoid(sink, cx, cy, cz, rx, ry, rz, id, hollow);
		return sink.count();
	}

	private Object cylinder(Object[] a, String fn) {
		need(a, fn, P_CYLINDER);
		int cx = coord(a, 0, fn, "cx");
		int y = coord(a, 1, fn, "y");
		int cz = coord(a, 2, fn, "cz");
		double r = number(a, 3, fn, "r");
		int height = size(a, 4, fn, "height");
		String id = blockId(a, 5, fn);
		boolean hollow = flag(a, 6);
		checkRadius(fn, "r", r);
		if (height > limits.maxSpan()) {
			throw limit(fn + "(): height " + height + " exceeds the maximum span of " + limits.maxSpan() + " blocks");
		}
		CountingSink sink = new CountingSink(false);
		if (fn.equals("cone")) {
			Shapes.cone(sink, cx, y, cz, r, height, id, hollow);
		} else {
			Shapes.cylinder(sink, cx, y, cz, r, height, id, hollow);
		}
		return sink.count();
	}

	private Object pyramid(Object[] a) {
		need(a, "pyramid", P_PYRAMID);
		int cx = coord(a, 0, "pyramid", "cx");
		int y = coord(a, 1, "pyramid", "y");
		int cz = coord(a, 2, "pyramid", "cz");
		int half = size(a, 3, "pyramid", "half");
		String id = blockId(a, 4, "pyramid");
		boolean hollow = flag(a, 5);
		if (2L * half + 1 > limits.maxSpan()) {
			throw limit("pyramid(): half " + half + " makes the base " + (2L * half + 1)
					+ " blocks wide, more than the maximum span of " + limits.maxSpan());
		}
		CountingSink sink = new CountingSink(false);
		Shapes.pyramid(sink, cx, y, cz, half, id, hollow);
		return sink.count();
	}

	private Object torus(Object[] a) {
		need(a, "torus", P_TORUS);
		int cx = coord(a, 0, "torus", "cx");
		int cy = coord(a, 1, "torus", "cy");
		int cz = coord(a, 2, "torus", "cz");
		double major = number(a, 3, "torus", "R");
		double minor = number(a, 4, "torus", "r");
		String id = blockId(a, 5, "torus");
		if (2 * Math.floor(Math.max(major, 0) + Math.max(minor, 0) + 0.5) + 1 > limits.maxSpan()) {
			throw limit("torus(): R + r = " + fmt(Math.max(major, 0) + Math.max(minor, 0))
					+ " makes the ring wider than the maximum span of " + limits.maxSpan() + " blocks");
		}
		CountingSink sink = new CountingSink(false);
		Shapes.torus(sink, cx, cy, cz, major, minor, id);
		return sink.count();
	}

	private Object polygon(Object[] a) {
		need(a, "polygon", P_POLYGON);
		List<int[]> points = points(a[0]);
		int y = coord(a, 1, "polygon", "y");
		int height = size(a, 2, "polygon", "height");
		String id = blockId(a, 3, "polygon");
		boolean hollow = flag(a, 4);
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (int[] p : points) {
			minX = Math.min(minX, p[0]);
			maxX = Math.max(maxX, p[0]);
			minZ = Math.min(minZ, p[1]);
			maxZ = Math.max(maxZ, p[1]);
		}
		long span = Math.max((long) maxX - minX, (long) maxZ - minZ) + 1;
		if (span > limits.maxSpan()) {
			throw limit("polygon(): the footprint is " + span + " blocks wide, more than the maximum span of "
					+ limits.maxSpan());
		}
		if (height > limits.maxSpan()) {
			throw limit("polygon(): height " + height + " exceeds the maximum span of " + limits.maxSpan() + " blocks");
		}
		CountingSink sink = new CountingSink(false);
		Shapes.polygon(sink, points, y, height, id, hollow);
		return sink.count();
	}

	/** path(points, id, radius=0): consecutive [x, y, z] points (or {x, y, z} objects) joined by line(). */
	private Object path(Object[] a) {
		need(a, "path", P_PATH);
		if (!(a[0] instanceof NativeArray array)) {
			throw ScriptRuntime.typeError("path(): points must be an array of [x, y, z] triples or {x, y, z} objects");
		}
		long length = array.getLength();
		if (length < 2) {
			throw ScriptRuntime.typeError("path(): expected at least 2 points, got " + length);
		}
		if (length > MAX_POLYGON_POINTS) {
			throw limit("path(): too many points (" + length + ", max " + MAX_POLYGON_POINTS + ")");
		}
		String id = blockId(a, 1, "path");
		double radius = optNumber(a, 2, 0, "path", "radius");
		checkRadius("path", "radius", radius);
		int[] previous = null;
		CountingSink sink = new CountingSink(true);
		for (int i = 0; i < length; i++) {
			Object p = ScriptableObject.getProperty(array, i);
			Object x;
			Object y;
			Object z;
			if (p instanceof NativeArray triple) {
				if (triple.getLength() < 3) {
					throw ScriptRuntime.typeError("path(): points[" + i + "] must be [x, y, z] or {x, y, z}");
				}
				x = ScriptableObject.getProperty(triple, 0);
				y = ScriptableObject.getProperty(triple, 1);
				z = ScriptableObject.getProperty(triple, 2);
			} else if (p instanceof Scriptable obj && !(p instanceof Function)) {
				x = ScriptableObject.getProperty(obj, "x");
				y = ScriptableObject.getProperty(obj, "y");
				z = ScriptableObject.getProperty(obj, "z");
			} else {
				throw ScriptRuntime.typeError("path(): points[" + i + "] must be [x, y, z] or {x, y, z}");
			}
			int[] point = {coordValue(x, "path", "points[" + i + "].x"), coordValue(y, "path", "points[" + i + "].y"),
					coordValue(z, "path", "points[" + i + "].z")};
			if (previous != null) {
				Shapes.line(sink, previous[0], previous[1], previous[2], point[0], point[1], point[2], id, radius);
			}
			previous = point;
		}
		return sink.count();
	}

	private List<int[]> points(Object value) {
		if (!(value instanceof NativeArray array)) {
			throw ScriptRuntime.typeError("polygon(): points must be an array of [x, z] pairs or {x, z} objects");
		}
		long length = array.getLength();
		if (length < 3) {
			throw ScriptRuntime.typeError("polygon(): expected at least 3 points, got " + length);
		}
		if (length > MAX_POLYGON_POINTS) {
			throw limit("polygon(): too many points (" + length + ", max " + MAX_POLYGON_POINTS + ")");
		}
		List<int[]> out = new ArrayList<>((int) length);
		for (int i = 0; i < length; i++) {
			Object p = ScriptableObject.getProperty(array, i);
			Object x;
			Object z;
			if (p instanceof NativeArray pair && pair.getLength() >= 2) {
				x = ScriptableObject.getProperty(pair, 0);
				z = ScriptableObject.getProperty(pair, 1);
			} else if (p instanceof Scriptable obj && !(p instanceof Function)) {
				x = ScriptableObject.getProperty(obj, "x");
				z = ScriptableObject.getProperty(obj, "z");
			} else {
				throw ScriptRuntime.typeError("polygon(): points[" + i + "] must be [x, z] or {x, z}");
			}
			out.add(new int[] {coordValue(x, "polygon", "points[" + i + "].x"),
					coordValue(z, "polygon", "points[" + i + "].z")});
		}
		return out;
	}

	private Object text(Object[] a) {
		need(a, "text", P_TEXT);
		Object s = a[0];
		if (!(s instanceof CharSequence) && !(s instanceof Number)) {
			throw ScriptRuntime.typeError("text(): str must be a string, got " + describe(s));
		}
		String str = s instanceof CharSequence ? argument(s, "text", "str") : ScriptRuntime.toString(s);
		int x = coord(a, 1, "text", "x");
		int y = coord(a, 2, "text", "y");
		int z = coord(a, 3, "text", "z");
		String id = blockId(a, 4, "text");
		double scaleValue = optNumber(a, 5, 1, "text", "scale");
		long scale = Math.round(scaleValue);
		if (scale < 1) {
			throw ScriptRuntime.typeError("text(): scale must be at least 1, got " + fmt(scaleValue));
		}
		if (scale > limits.maxSpan()) {
			throw limit("text(): scale " + scale + " is too large for the maximum span of " + limits.maxSpan()
					+ " blocks");
		}
		CountingSink sink = new CountingSink(false);
		return PixelFont.draw(sink, str, x, y, z, id, (int) scale);
	}

	private Object replace(Object[] a) {
		need(a, "replace", P_REPLACE);
		long x1 = Math.round(number(a, 0, "replace", "x1"));
		long y1 = Math.round(number(a, 1, "replace", "y1"));
		long z1 = Math.round(number(a, 2, "replace", "z1"));
		long x2 = Math.round(number(a, 3, "replace", "x2"));
		long y2 = Math.round(number(a, 4, "replace", "y2"));
		long z2 = Math.round(number(a, 5, "replace", "z2"));
		boolean any = a[6] instanceof CharSequence cs && cs.length() < 16 && cs.toString().strip().equals("*");
		String from = any ? null : blockId(a, 6, "replace");
		boolean air = from != null && BlockIds.isAir(from);
		String to = blockId(a, 7, "replace");
		long minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
		long minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
		long minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
		int[][] found = {new int[48]};
		int[] n = {0};
		blueprint.forEach((x, y, z, id) -> {
			if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ
					&& (any ? !BlockIds.isAir(id) : air ? BlockIds.isAir(id) : id.equals(from))) {
				if (n[0] + 3 > found[0].length) {
					found[0] = Arrays.copyOf(found[0], found[0].length * 2);
				}
				found[0][n[0]++] = x;
				found[0][n[0]++] = y;
				found[0][n[0]++] = z;
			}
		});
		int[] pos = found[0];
		for (int i = 0; i < n[0]; i += 3) {
			if ((i & (CHECK_INTERVAL - 1)) == 0) {
				checkAbort();
			}
			blueprint.set(pos[i], pos[i + 1], pos[i + 2], to);
		}
		return n[0] / 3;
	}

	private Object get(Object[] a) {
		need(a, "get", P_GET);
		long x = Math.round(number(a, 0, "get", "x"));
		long y = Math.round(number(a, 1, "get", "y"));
		long z = Math.round(number(a, 2, "get", "z"));
		long limit = Blueprint.COORD_LIMIT;
		if (Math.abs(x) > limit || Math.abs(y) > limit || Math.abs(z) > limit) {
			return null;
		}
		String id = blueprint.get((int) x, (int) y, (int) z);
		return id == null ? null : shortIds.computeIfAbsent(id, ScriptSession::shortId);
	}

	/**
	 * An id as get() reports it: {@code "air"} for every kind of air, otherwise without the {@code minecraft:}
	 * namespace (states and NBT kept). Other namespaces stay.
	 */
	static String shortId(String normalized) {
		if (BlockIds.isAir(normalized)) {
			return "air";
		}
		return normalized.startsWith("minecraft:") ? normalized.substring("minecraft:".length()) : normalized;
	}

	// ---- randomness -----------------------------------------------------------------------

	private Object randInt(Object[] a) {
		need(a, "randInt", P_RANDINT);
		double min = number(a, 0, "randInt", "min");
		double max = number(a, 1, "randInt", "max");
		if (Math.abs(min) > SAFE_INTEGER || Math.abs(max) > SAFE_INTEGER) {
			throw ScriptRuntime.typeError("randInt(): min and max must be between -2^53 and 2^53");
		}
		long lo = Math.round(min);
		long hi = Math.round(max);
		if (lo > hi) {
			throw ScriptRuntime.typeError("randInt(): min (" + lo + ") must not be greater than max (" + hi + ")");
		}
		return (double) (lo == hi ? lo : random.nextLong(lo, hi + 1));
	}

	private Object pick(Object[] a) {
		need(a, "pick", P_PICK);
		if (!(a[0] instanceof NativeArray array)) {
			throw ScriptRuntime.typeError("pick(): expected an array, got " + describe(a[0]));
		}
		long length = array.getLength();
		if (length == 0) {
			throw ScriptRuntime.typeError("pick(): the array is empty");
		}
		int index = random.nextInt((int) Math.min(length, Integer.MAX_VALUE));
		Object v = ScriptableObject.getProperty(array, index);
		return v == Scriptable.NOT_FOUND ? Undefined.instance : v;
	}

	private Object noise(Object[] a) {
		need(a, "noise", P_NOISE);
		double x = number(a, 0, "noise", "x");
		double y = number(a, 1, "noise", "y");
		if (noise == null) {
			noise = new Noise(seed);
		}
		if (a.length > 2 && !Undefined.isUndefined(a[2])) {
			return noise.noise3(x, y, number(a, 2, "noise", "z"));
		}
		return noise.noise2(x, y);
	}

	// ---- placement and naming -------------------------------------------------------------

	private Object placement(Object[] a) {
		need(a, "placement", P_PLACEMENT);
		Object options = a[0];
		if (options instanceof CharSequence s) {
			placement = new PlacementSpec(parseMode(s), PlacementSpec.DEFAULT_DISTANCE, GroundMode.PLAYER, false,
					null, null, null);
			return Undefined.instance;
		}
		if (!(options instanceof Scriptable o) || options instanceof NativeArray || options instanceof Function) {
			throw ScriptRuntime.typeError("placement(): expected an options object such as {mode: 'center'}, got "
					+ describe(options));
		}
		PlacementMode mode = PlacementMode.FRONT;
		int distance = PlacementSpec.DEFAULT_DISTANCE;
		GroundMode ground = GroundMode.PLAYER;
		boolean replacePrevious = false;
		Integer x = null;
		Integer y = null;
		Integer z = null;
		for (Object id : o.getIds()) {
			String key = String.valueOf(id);
			Object v = id instanceof Integer i ? ScriptableObject.getProperty(o, i) : ScriptableObject.getProperty(o, key);
			boolean given = !Undefined.isUndefined(v);
			switch (key) {
				case "mode" -> mode = given ? parseMode(v) : mode;
				case "distance" -> {
					if (given) {
						double d = numberValue(v, "placement", "distance");
						if (d < 0 || d > MAX_DISTANCE) {
							throw ScriptRuntime.typeError("placement(): distance must be between 0 and " + MAX_DISTANCE
									+ ", got " + fmt(d));
						}
						distance = (int) Math.round(d);
					}
				}
				case "ground" -> {
					if (given) {
						GroundMode g = v instanceof CharSequence s && s.length() < 64 ? GroundMode.parse(s.toString()) : null;
						if (g == null) {
							throw ScriptRuntime.typeError("placement(): unknown ground " + quote(v)
									+ " (valid: 'player', 'surface')");
						}
						ground = g;
					}
				}
				case "replace" -> replacePrevious = ScriptRuntime.toBoolean(v);
				case "x" -> x = worldCoord(v, "x");
				case "y" -> y = worldCoord(v, "y");
				case "z" -> z = worldCoord(v, "z");
				default -> addLog("placement(): ignoring unknown option '" + key + "'");
			}
		}
		if (mode != PlacementMode.ABSOLUTE && (x != null || y != null || z != null)) {
			addLog("placement(): x, y and z are only used with mode 'absolute'");
			x = null;
			y = null;
			z = null;
		}
		placement = new PlacementSpec(mode, distance, ground, replacePrevious, x, y, z);
		return Undefined.instance;
	}

	private static PlacementMode parseMode(Object v) {
		PlacementMode mode = v instanceof CharSequence s && s.length() < 64 ? PlacementMode.parse(s.toString()) : null;
		if (mode == null) {
			throw ScriptRuntime.typeError("placement(): unknown mode " + quote(v)
					+ " (valid: 'front', 'center', 'origin', 'previous', 'absolute')");
		}
		return mode;
	}

	private static Integer worldCoord(Object v, String key) {
		if (v == null || Undefined.isUndefined(v)) {
			return null;
		}
		double d = numberValue(v, "placement", key);
		if (Math.abs(d) > WORLD_COORD_LIMIT) {
			throw ScriptRuntime.typeError("placement(): " + key + " must be between -" + WORLD_COORD_LIMIT + " and "
					+ WORLD_COORD_LIMIT);
		}
		return (int) Math.round(d);
	}

	private Object setName(Object[] a) {
		need(a, "setName", P_NAME);
		Object v = a[0];
		if (v == null || Undefined.isUndefined(v)) {
			name = null;
			return Undefined.instance;
		}
		if (!(v instanceof CharSequence) && !(v instanceof Number)) {
			throw ScriptRuntime.typeError("setName(): name must be a string, got " + describe(v));
		}
		if (v instanceof CharSequence cs && cs.length() > MAX_ARGUMENT_LENGTH) {
			throw ScriptRuntime.typeError("setName(): the name is too long (" + cs.length() + " characters)");
		}
		StringBuilder sb = new StringBuilder();
		ScriptRuntime.toString(v).codePoints()
				.forEach(cp -> sb.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
		String s = sb.toString().strip();
		if (s.codePointCount(0, s.length()) > MAX_NAME_LENGTH) {
			s = s.substring(0, s.offsetByCodePoints(0, MAX_NAME_LENGTH)).strip();
		}
		name = s.isEmpty() ? null : s;
		return Undefined.instance;
	}

	// ---- world actions --------------------------------------------------------------------

	private void addAction(String fn, WorldAction action) {
		if (actions.size() >= limits.maxActions()) {
			throw limit(fn + "(): too many world actions (max " + limits.maxActions() + ")");
		}
		actions.add(action);
	}

	private Object summon(Object[] a) {
		need(a, "summon", P_SUMMON);
		String id = resourceId(a[0], "summon", "entity id");
		int x = coord(a, 1, "summon", "x");
		int y = coord(a, 2, "summon", "y");
		int z = coord(a, 3, "summon", "z");
		String nbt = null;
		Object raw = a.length > 4 ? a[4] : null;
		if (raw != null && !Undefined.isUndefined(raw)) {
			if (!(raw instanceof CharSequence)) {
				throw ScriptRuntime.typeError("summon(): nbt must be an SNBT string such as '{NoAI:1b}', got "
						+ describe(raw));
			}
			nbt = argument(raw, "summon", "nbt").strip();
			if (nbt.isEmpty()) {
				nbt = null;
			} else if (!nbt.startsWith("{") || !nbt.endsWith("}") || hasLineBreak(nbt)) {
				throw ScriptRuntime.typeError("summon(): nbt must be an SNBT compound such as '{NoAI:1b}', got '"
						+ clip(nbt, 60) + "'");
			}
			String legacy = legacyEntityKey(nbt);
			if (legacy != null) {
				throw ScriptRuntime.typeError("summon(): '" + legacy + "' is pre-1.21.5 NBT that Minecraft 26.3 ignores; use "
						+ "equipment:{head:{id:\"minecraft:iron_helmet\",count:1},mainhand:{...}} (lowercase count)");
			}
		}
		addAction("summon", new WorldAction.Summon(id, x, y, z, nbt));
		return Undefined.instance;
	}

	private static final java.util.regex.Pattern LEGACY_ENTITY_KEY =
			java.util.regex.Pattern.compile("\\b(ArmorItems|HandItems|ArmorDropChances|HandDropChances|Count)\\s*:");

	/** The first legacy (pre-1.21.5) entity/item NBT key in {@code nbt}, or null. */
	static String legacyEntityKey(String nbt) {
		java.util.regex.Matcher m = LEGACY_ENTITY_KEY.matcher(nbt);
		return m.find() ? m.group(1) : null;
	}

	private Object give(Object[] a) {
		need(a, "give", P_GIVE);
		Object raw = a[0];
		if (!(raw instanceof CharSequence)) {
			throw ScriptRuntime.typeError("give(): item id must be a string, got " + describe(raw));
		}
		String s = argument(raw, "give", "item id").strip();
		int bracket = s.indexOf('[');
		String id;
		if (bracket < 0) {
			id = resourceId(s, "give", "item id");
		} else {
			String components = s.substring(bracket);
			if (!components.endsWith("]") || hasLineBreak(components)) {
				throw ScriptRuntime.typeError("give(): invalid item id '" + clip(s, 60) + "'");
			}
			id = resourceId(s.substring(0, bracket), "give", "item id") + components;
		}
		int count = intInRange(a, 1, 1, "give", "count", 1, 6400);
		addAction("give", new WorldAction.Give(id, count));
		return Undefined.instance;
	}

	private Object time(Object[] a) {
		need(a, "time", P_TIME);
		Object v = a[0];
		String value = null;
		if (v instanceof CharSequence s && s.length() < 64) {
			String k = s.toString().strip().toLowerCase(Locale.ROOT);
			if (k.equals("day") || k.equals("noon") || k.equals("night") || k.equals("midnight")) {
				value = k;
			}
		} else if (v instanceof Number n && !(v instanceof BigInteger) && Double.isFinite(n.doubleValue())) {
			long ticks = Math.round(n.doubleValue());
			if (ticks >= 0 && ticks <= 24_000) {
				value = Long.toString(ticks);
			}
		}
		if (value == null) {
			throw ScriptRuntime.typeError("time(): expected 'day', 'noon', 'night', 'midnight' or ticks 0..24000, got "
					+ quote(v));
		}
		addAction("time", new WorldAction.SetTime(value));
		return Undefined.instance;
	}

	private Object weather(Object[] a) {
		need(a, "weather", P_TIME);
		Object v = a[0];
		String k = v instanceof CharSequence s && s.length() < 64 ? s.toString().strip().toLowerCase(Locale.ROOT) : "";
		if (!k.equals("clear") && !k.equals("rain") && !k.equals("thunder")) {
			throw ScriptRuntime.typeError("weather(): expected 'clear', 'rain' or 'thunder', got " + quote(v));
		}
		addAction("weather", new WorldAction.SetWeather(k));
		return Undefined.instance;
	}

	private Object effect(Object[] a) {
		need(a, "effect", P_EFFECT);
		String id = resourceId(a[0], "effect", "effect id");
		int seconds = intInRange(a, 1, 30, "effect", "seconds", 1, 1_000_000);
		int amplifier = intInRange(a, 2, 0, "effect", "amplifier", 0, 255);
		addAction("effect", new WorldAction.Effect(id, seconds, amplifier));
		return Undefined.instance;
	}

	private Object command(Object[] a) {
		need(a, "command", P_COMMAND);
		Object v = a[0];
		if (!(v instanceof CharSequence)) {
			throw ScriptRuntime.typeError("command(): cmd must be a string, got " + describe(v));
		}
		if (((CharSequence) v).length() > MAX_COMMAND_LENGTH) {
			throw ScriptRuntime.typeError("command(): the command is longer than " + MAX_COMMAND_LENGTH + " characters");
		}
		String cmd = v.toString().strip();
		if (cmd.startsWith("/")) {
			cmd = cmd.substring(1).strip();
		}
		if (cmd.isEmpty()) {
			throw ScriptRuntime.typeError("command(): the command is empty");
		}
		if (cmd.chars().anyMatch(Character::isISOControl)) {
			throw ScriptRuntime.typeError("command(): the command must be a single line without control characters");
		}
		if (cmd.length() > MAX_COMMAND_LENGTH) {
			throw ScriptRuntime.typeError("command(): the command is longer than " + MAX_COMMAND_LENGTH + " characters");
		}
		addAction("command", new WorldAction.Command(cmd));
		return Undefined.instance;
	}

	private Object waitAction(Object[] a) {
		need(a, "wait", P_WAIT);
		double seconds = number(a, 0, "wait", "seconds");
		if (!(seconds > 0) || seconds > MAX_WAIT_SECONDS) {
			throw ScriptRuntime.typeError("wait(): seconds must be greater than 0 and at most " + fmt(MAX_WAIT_SECONDS)
					+ ", got " + fmt(seconds));
		}
		addAction("wait", new WorldAction.Wait(seconds));
		return Undefined.instance;
	}

	// ---- logging --------------------------------------------------------------------------

	private Object log(Object[] a) {
		if (logTruncated) {
			return Undefined.instance;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < a.length; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(format(a[i]));
		}
		addLog(sb.toString());
		return Undefined.instance;
	}

	void addLog(String message) {
		for (String line : message.split("\r\n|[\n\r]", -1)) {
			if (logTruncated) {
				return;
			}
			if (logs.size() >= limits.maxLogLines()) {
				logs.add("... (log truncated)");
				logTruncated = true;
				return;
			}
			logs.add(line.length() > MAX_LOG_LINE_CHARS
					? line.substring(0, MAX_LOG_LINE_CHARS) + "... (" + (line.length() - MAX_LOG_LINE_CHARS)
							+ " more characters)"
					: line);
		}
	}

	/** Formats a value like console.log: strings as-is, numbers in JS notation, objects as JSON when possible. */
	String format(Object v) {
		if (v instanceof CharSequence cs) {
			return cs.length() > NativeGuards.MAX_STRING_LENGTH ? "<string of " + cs.length() + " characters>" : cs.toString();
		}
		if (v == null) {
			return "null";
		}
		if (Undefined.isUndefined(v)) {
			return "undefined";
		}
		if (v instanceof Number || v instanceof Boolean) {
			return ScriptRuntime.toString(v);
		}
		if (v instanceof Function f) {
			String fname = f instanceof BaseFunction b ? b.getFunctionName() : "";
			return fname == null || fname.isEmpty() ? "[function]" : "[function " + fname + "]";
		}
		if (v instanceof Scriptable s && !"Error".equals(s.getClassName())) {
			try {
				String json = NativeGuards.stringify(this, scope, Context.getCurrentContext(), s);
				if (json != null) {
					return json;
				}
			} catch (RhinoException e) {
				// cyclic or otherwise not serializable: fall back to toString()
			}
		}
		try {
			return ScriptRuntime.toString(v);
		} catch (RhinoException e) {
			return String.valueOf(v);
		}
	}

	// ---- argument helpers -----------------------------------------------------------------

	private static void need(Object[] a, String fn, String[] names) {
		if (a.length < names.length) {
			throw ScriptRuntime.typeError(fn + "(): expected " + names.length + " arguments (" + String.join(", ", names)
					+ "), got " + a.length);
		}
	}

	private static double number(Object[] a, int i, String fn, String param) {
		return numberValue(i < a.length ? a[i] : Undefined.instance, fn, param);
	}

	private static double numberValue(Object v, String fn, String param) {
		if (v instanceof Number n && !(v instanceof BigInteger)) {
			double d = n.doubleValue();
			if (Double.isFinite(d)) {
				return d;
			}
		}
		throw ScriptRuntime.typeError(fn + "(): " + param + " must be a finite number");
	}

	private static double optNumber(Object[] a, int i, double def, String fn, String param) {
		return i >= a.length || Undefined.isUndefined(a[i]) ? def : number(a, i, fn, param);
	}

	private static boolean flag(Object[] a, int i) {
		return i < a.length && ScriptRuntime.toBoolean(a[i]);
	}

	private int coord(Object[] a, int i, String fn, String param) {
		return coordValue(i < a.length ? a[i] : Undefined.instance, fn, param);
	}

	private int coordValue(Object v, String fn, String param) {
		double d = numberValue(v, fn, param);
		long r = Math.round(d);
		if (r < -Blueprint.COORD_LIMIT || r > Blueprint.COORD_LIMIT) {
			throw limit(fn + "(): " + param + " = " + fmt(d) + " is outside the coordinate range -"
					+ Blueprint.COORD_LIMIT + ".." + Blueprint.COORD_LIMIT);
		}
		return (int) r;
	}

	/** A non-negative integer size (rounded); sizes beyond the coordinate range are limit errors. */
	private int size(Object[] a, int i, String fn, String param) {
		double d = number(a, i, fn, param);
		long r = Math.round(d);
		if (r < 0) {
			throw ScriptRuntime.typeError(fn + "(): " + param + " must not be negative, got " + fmt(d));
		}
		if (r > 2L * Blueprint.COORD_LIMIT) {
			throw limit(fn + "(): " + param + " " + fmt(d) + " exceeds the maximum span of " + limits.maxSpan()
					+ " blocks");
		}
		return (int) r;
	}

	private static int intInRange(Object[] a, int i, int def, String fn, String param, int min, int max) {
		if (i >= a.length || Undefined.isUndefined(a[i])) {
			return def;
		}
		double d = number(a, i, fn, param);
		long r = Math.round(d);
		if (r < min || r > max) {
			throw ScriptRuntime.typeError(fn + "(): " + param + " must be between " + min + " and " + max + ", got "
					+ fmt(d));
		}
		return (int) r;
	}

	/**
	 * Rejects radii whose shape is wider than the span limit before drawing it. Shapes reach
	 * floor(r + 0.5) blocks from the centre, so the check is exact; it gives a clearer message and
	 * avoids iterating huge, mostly empty bounding boxes.
	 */
	private void checkRadius(String fn, String param, double r) {
		if (2 * Math.floor(Math.max(r, 0) + 0.5) + 1 > limits.maxSpan()) {
			throw limit(fn + "(): " + param + " " + fmt(r) + " makes the shape wider than the maximum span of "
					+ limits.maxSpan() + " blocks");
		}
	}

	private String blockId(Object[] a, int i, String fn) {
		Object v = i < a.length ? a[i] : Undefined.instance;
		if (v instanceof CharSequence cs) {
			if (cs.length() > BlockIds.MAX_LENGTH) {
				throw ScriptRuntime.typeError(fn + "(): invalid block id (" + cs.length() + " characters long)");
			}
			String raw = cs.toString();
			String id = idCache.get(raw);
			if (id != null) {
				return id;
			}
			try {
				id = BlockIds.normalize(raw);
			} catch (IllegalArgumentException e) {
				throw ScriptRuntime.typeError(fn + "(): invalid block id '" + clip(raw, 80) + "'");
			}
			if (idCache.size() < ID_CACHE_MAX) {
				idCache.put(raw, id);
			}
			return id;
		}
		throw ScriptRuntime.typeError(fn + "(): invalid block id '" + clip(format(v), 80) + "'");
	}

	private String resourceId(Object v, String fn, String what) {
		if (!(v instanceof CharSequence)) {
			throw ScriptRuntime.typeError(fn + "(): " + what + " must be a string, got " + describe(v));
		}
		String s = argument(v, fn, what).strip().toLowerCase(Locale.ROOT);
		if (!RESOURCE_ID.matcher(s).matches()) {
			throw ScriptRuntime.typeError(fn + "(): invalid " + what + " '" + preview(v, 60) + "'");
		}
		return s;
	}

	/** A string argument as a Java string; longer than {@link #MAX_ARGUMENT_LENGTH} is a TypeError. */
	private static String argument(Object v, String fn, String what) {
		CharSequence cs = (CharSequence) v;
		if (cs.length() > MAX_ARGUMENT_LENGTH) {
			throw ScriptRuntime.typeError(fn + "(): " + what + " is too long (" + cs.length() + " characters)");
		}
		return cs.toString();
	}

	/** Start of a string for messages, without flattening huge concatenated strings. */
	private static String preview(Object v, int max) {
		CharSequence cs = (CharSequence) v;
		return cs.length() > MAX_ARGUMENT_LENGTH ? "<" + cs.length() + " characters>" : clip(cs.toString(), max);
	}

	private static boolean hasLineBreak(String s) {
		return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
	}

	private static String clip(String s, int max) {
		return s.length() > max ? s.substring(0, max) + "..." : s;
	}

	/** Short description of a value's type for error messages. */
	private static String describe(Object v) {
		if (v == null) {
			return "null";
		}
		if (Undefined.isUndefined(v)) {
			return "undefined";
		}
		if (v instanceof CharSequence) {
			return "string '" + preview(v, 40) + "'";
		}
		if (v instanceof Number || v instanceof Boolean) {
			return ScriptRuntime.typeof(v) + " " + ScriptRuntime.toString(v);
		}
		if (v instanceof NativeArray) {
			return "array";
		}
		if (v instanceof Function) {
			return "function";
		}
		return "object";
	}

	/** A value quoted for error messages: strings in single quotes, other values in JS notation. */
	private static String quote(Object v) {
		if (v instanceof CharSequence) {
			return "'" + preview(v, 40) + "'";
		}
		return v instanceof Number || v instanceof Boolean || v == null || Undefined.isUndefined(v)
				? String.valueOf(v == null ? "null" : ScriptRuntime.toString(v)) : describe(v);
	}

	/** A number in JavaScript notation (integers without ".0"). */
	static String fmt(double d) {
		return ScriptRuntime.numberToString(d, 10);
	}

	/**
	 * Forwards writes to the blueprint, counts distinct positions and checks the deadline regularly,
	 * so long-running shapes stay interruptible.
	 */
	private final class CountingSink implements BlockSink {
		private long[] table;
		private int size;
		private int writes;

		/**
		 * @param dedupe count each position once even if the shape writes it repeatedly (thick lines;
		 *               other shapes write every position once per call)
		 */
		CountingSink(boolean dedupe) {
			if (dedupe) {
				table = new long[64];
				Arrays.fill(table, -1L);
			}
		}

		@Override
		public void set(int x, int y, int z, String blockId) {
			if ((++writes & (CHECK_INTERVAL - 1)) == 0) {
				checkAbort();
			}
			blueprint.set(x, y, z, blockId);
			if (table != null) {
				add(((x + 32768L) << 32) | ((y + 32768L) << 16) | (z + 32768L));
			}
		}

		int count() {
			return table != null ? size : writes;
		}

		private void add(long key) {
			int mask = table.length - 1;
			long h = key * 0x9E3779B97F4A7C15L;
			int i = (int) (h ^ (h >>> 32)) & mask;
			while (table[i] != -1L) {
				if (table[i] == key) {
					return;
				}
				i = (i + 1) & mask;
			}
			table[i] = key;
			if (++size * 2 > table.length) {
				long[] old = table;
				table = new long[old.length * 2];
				Arrays.fill(table, -1L);
				size = 0;
				for (long k : old) {
					if (k != -1L) {
						add(k);
					}
				}
			}
		}
	}
}
