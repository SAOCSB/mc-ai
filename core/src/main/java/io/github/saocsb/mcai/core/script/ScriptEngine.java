package io.github.saocsb.mcai.core.script;

import io.github.saocsb.mcai.core.blueprint.BlueprintLimitException;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.GeneratedClassLoader;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.ScriptStackElement;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.WrappedException;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.Debugger;
import org.mozilla.javascript.lc.type.TypeInfo;
import org.mozilla.javascript.regexp.RegExpImpl;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs untrusted, LLM-written JavaScript build scripts in a Rhino sandbox and collects the result.
 *
 * <p>Sandbox: interpreted mode, safe standard objects only (no {@code Packages}, {@code java},
 * {@code importPackage}, {@code load}...), a ClassShutter that denies every Java class, a
 * wall-clock budget enforced via the instruction observer, a call-depth limit, and checks for
 * thread interruption (so a cancelled task stops). {@code Math.random} is replaced by the seeded
 * generator so runs are deterministic for a given seed.
 *
 * <p>Hardening beyond that: a WrapFactory that refuses to wrap Java objects; a Debugger hook that
 * checks the budget on JavaScript function entries (the observer only runs on loop back-jumps, so
 * callbacks invoked by built-ins would otherwise go unchecked); length caps on built-ins that loop
 * or allocate in one native call ({@link NativeGuards}); a per-run allocation budget; no Proxy or
 * typed arrays. Budget, limit and cancellation errors are {@link Error}s, which JavaScript
 * {@code catch}/{@code finally} cannot intercept. Residual risk: a concatenated string that is
 * flattened implicitly (e.g. compared with {@code ===}) can still allocate up to 2 GB at once.
 *
 * <p>The script is wrapped in a function, so top-level {@code const}/{@code let}/{@code function}
 * declarations may shadow API names (e.g. {@code const text = ...}); line numbers in errors still
 * refer to the original script. The global API is documented in {@code mcai/prompt/system.md}
 * (resource) and must match {@link #API_FUNCTIONS}.
 *
 * <p>Before compiling, the source is adjusted for gaps in Rhino 1.9 (see {@link SourceRewriter}):
 * {@code const} is treated as {@code let}, and calls with spread arguments on plain or dotted names
 * become {@code apply} calls. Classes, {@code async} functions and per-iteration {@code let}
 * bindings in {@code for} loops are not supported by Rhino.
 *
 * <p>Thread-safe: every {@link #run} uses its own Context and scope.
 */
public final class ScriptEngine implements ScriptRunner {
	/**
	 * Every global function the script API defines ({@link ScriptApi#FUNCTIONS}). Tests keep the prompt in sync with
	 * it, and the response parser uses it to recognise untagged code blocks that are scripts.
	 */
	public static final List<String> API_FUNCTIONS = ScriptApi.FUNCTIONS;

	/** Source name given to the compiled script; stack frames with this name belong to the user's script. */
	static final String SOURCE_NAME = "script";
	static final String CANCELLED_MESSAGE = "CancelledError: the script run was cancelled";
	/** Interpreter instructions between deadline/interrupt checks. */
	private static final int OBSERVER_THRESHOLD = 10_000;
	private static final String PREFIX = "(function(){";
	private static final String SUFFIX = "\n})();";
	/** Globals of the safe standard objects that scripts have no use for. */
	private static final List<String> REMOVED_GLOBALS = List.of("Continuation", "Script", "With", "Call",
			"JavaException", "isXMLName", "Packages", "java", "javax", "org", "com", "edu", "net", "getClass",
			"JavaAdapter", "JavaImporter", "importPackage", "importClass", "load", "loadClass", "Proxy",
			"ArrayBuffer", "SharedArrayBuffer", "DataView", "Atomics", "Int8Array", "Uint8Array", "Uint8ClampedArray",
			"Int16Array", "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
			"BigInt64Array", "BigUint64Array");

	private static final String STACK_DEPTH_MESSAGE = "Exceeded maximum stack depth";

	private static final SandboxFactory FACTORY;
	private static final ErrorReporter REPORTER = new SandboxErrorReporter();
	/** Runs the session's budget check on every JavaScript function entry, including callbacks from built-ins. */
	private static final DebugFrame TICK_FRAME = new DebugFrame() {
		@Override
		public void onEnter(Context cx, Scriptable activation, Scriptable thisObj, Object[] args) {
			if (cx instanceof SandboxContext sandbox) {
				sandbox.session.tick();
			}
		}
	};
	private static final Debugger TICK_DEBUGGER = (cx, fnOrScript) -> TICK_FRAME;

	static {
		// Context's static initializer finds the RegExp implementation with ServiceLoader and the thread
		// context class loader; under Fabric's class loader that lookup can miss Rhino's service file.
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		try {
			thread.setContextClassLoader(ScriptEngine.class.getClassLoader());
			Class.forName("org.mozilla.javascript.Context", true, ScriptEngine.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new ExceptionInInitializerError(e);
		} finally {
			thread.setContextClassLoader(previous);
		}
		FACTORY = new SandboxFactory();
	}

	public ScriptEngine() {
	}

	/**
	 * Runs a script.
	 *
	 * @param source the JavaScript source (one script; may contain comments)
	 * @param seed   seed for random()/randInt()/pick()/noise()/Math.random
	 * @throws ScriptException on syntax/runtime errors, timeouts, limit violations or cancellation
	 */
	@Override
	public ScriptResult run(String source, ScriptLimits limits, long seed) throws ScriptException {
		return run(source, limits, seed, null);
	}

	/** {@link #run(String, ScriptLimits, long)} with an extra step on the scope before the script runs (tests). */
	ScriptResult run(String source, ScriptLimits limits, long seed, Consumer<ScriptableObject> setup)
			throws ScriptException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(limits, "limits");
		long start = System.nanoTime();
		if (Thread.currentThread().isInterrupted()) {
			throw new ScriptException(ScriptException.Kind.CANCELLED, CANCELLED_MESSAGE, -1);
		}
		ScriptSession session = new ScriptSession(source, limits, seed, start);
		SandboxContext cx = new SandboxContext(FACTORY, session);
		if (FACTORY.enterContext(cx) != cx) {
			Context.exit();
			throw new IllegalStateException("ScriptEngine.run must not be called while a Rhino Context is active");
		}
		try {
			cx.setInterpretedMode(true);
			cx.setLanguageVersion(Context.VERSION_ES6);
			cx.setInstructionObserverThreshold(OBSERVER_THRESHOLD);
			cx.setMaximumInterpreterStackDepth(Math.max(1, Math.min(limits.maxStackDepth(), Short.MAX_VALUE - 1)));
			cx.setClassShutter(className -> false);
			cx.setWrapFactory(new DenyWrapFactory());
			cx.setErrorReporter(REPORTER);
			// Debug info must stay on: without it `return f()` becomes a tail call that neither grows the
			// stack nor reaches the instruction observer, so `function f() { return f(); }` never stops.
			cx.setGeneratingDebug(true);
			cx.setDebugger(TICK_DEBUGGER, null);
			if (ScriptRuntime.getRegExpProxy(cx) == null) {
				ScriptRuntime.setRegExpProxy(cx, new RegExpImpl());
			}

			ScriptableObject scope = cx.initSafeStandardObjects(null, false);
			for (String global : REMOVED_GLOBALS) {
				scope.delete(global);
			}
			session.install(scope);
			if (setup != null) {
				setup.accept(scope);
			}

			Script script = compile(cx, source, session);
			script.exec(cx, scope, scope);
			long elapsed = (System.nanoTime() - start) / 1_000_000L;
			return new ScriptResult(session.blueprint(), session.placement(), session.name(), session.actions(),
					session.logs(), elapsed);
		} catch (ScriptAbort e) {
			throw failure(session, e.kind(), e.getMessage(), e.line());
		} catch (StackOverflowError e) {
			throw failure(session, ScriptException.Kind.LIMIT, recursionMessage(limits), -1);
		} catch (RhinoException e) {
			throw translate(session, limits, e);
		} catch (BlueprintLimitException e) {
			throw failure(session, ScriptException.Kind.LIMIT, ScriptSession.limitMessage(e.getMessage()), -1);
		} catch (OutOfMemoryError e) {
			throw failure(session, ScriptException.Kind.LIMIT, "LimitError: the script used too much memory", -1);
		} catch (RuntimeException e) {
			throw failure(session, ScriptException.Kind.RUNTIME, "InternalError: " + describe(e), -1);
		} finally {
			Context.exit();
		}
	}

	private static Script compile(Context cx, String source, ScriptSession session) throws ScriptException {
		String rewritten;
		try {
			rewritten = SourceRewriter.rewrite(source);
		} catch (RuntimeException e) {
			rewritten = source;
		}
		try {
			return cx.compileString(PREFIX + rewritten + SUFFIX, SOURCE_NAME, 1, null);
		} catch (EvaluatorException e) {
			if (!rewritten.equals(source)) {
				try {
					return cx.compileString(PREFIX + source + SUFFIX, SOURCE_NAME, 1, null);
				} catch (EvaluatorException ignored) {
					// report the error of the rewritten source: same line numbers, fewer Rhino quirks
				}
			}
			int line = e.lineNumber() > 0 ? e.lineNumber() : -1;
			String message = "SyntaxError: " + e.details() + syntaxHint(e.details(), session.sourceLine(line));
			throw failure(session, ScriptException.Kind.SYNTAX, message, line);
		}
	}

	private static String syntaxHint(String details, String sourceLine) {
		if (details.contains("reserved word: class")) {
			return " (classes are not supported; use functions and plain objects)";
		}
		if (sourceLine.matches(".*\\basync\\b.*") || sourceLine.matches(".*\\bawait\\b.*")) {
			return " (async functions are not supported)";
		}
		if (sourceLine.contains("...") && details.startsWith("Invalid assignment left-hand side")) {
			return " (rest elements in array destructuring are not supported; use slice() instead)";
		}
		if (sourceLine.contains("...") && details.equals("syntax error")) {
			return " (spread arguments only work in calls like f(...xs) or a.b(...xs); use fn.apply(null, array))";
		}
		return "";
	}

	private static ScriptException translate(ScriptSession session, ScriptLimits limits, RhinoException e) {
		int line = lineOf(e);
		if (e instanceof EcmaError err) {
			return failure(session, ScriptException.Kind.RUNTIME, err.getName() + ": " + err.getErrorMessage(), line);
		}
		if (e instanceof JavaScriptException jse) {
			return failure(session, ScriptException.Kind.RUNTIME, describeThrown(session, jse), line);
		}
		if (e instanceof WrappedException we) {
			Throwable inner = we.getWrappedException();
			if (inner instanceof BlueprintLimitException) {
				return failure(session, ScriptException.Kind.LIMIT, ScriptSession.limitMessage(inner.getMessage()), line);
			}
			return failure(session, ScriptException.Kind.RUNTIME, "InternalError: " + describe(inner), line);
		}
		if (e instanceof EvaluatorException && STACK_DEPTH_MESSAGE.equals(e.details())) {
			return failure(session, ScriptException.Kind.LIMIT, recursionMessage(limits), line);
		}
		return failure(session, ScriptException.Kind.RUNTIME, "Error: " + e.details(), line);
	}

	private static String recursionMessage(ScriptLimits limits) {
		return "LimitError: recursion too deep (max call depth " + limits.maxStackDepth() + ")";
	}

	/** "Name: message" for a value thrown with {@code throw}. Runs inside the Context (may call getters). */
	private static String describeThrown(ScriptSession session, JavaScriptException e) {
		Object value = e.getValue();
		try {
			if (value instanceof Scriptable s && "Error".equals(s.getClassName())) {
				Object name = ScriptableObject.getProperty(s, "name");
				Object message = ScriptableObject.getProperty(s, "message");
				String n = name instanceof CharSequence ? name.toString() : "Error";
				String m = message == Scriptable.NOT_FOUND || Undefined.isUndefined(message) ? ""
						: ScriptRuntime.toString(message);
				return m.isEmpty() ? n : n + ": " + m;
			}
			return "Error: " + session.format(value);
		} catch (RuntimeException | ScriptAbort ex) {
			return "Error: " + e.details();
		}
	}

	/** 1-based line in the user's script: the error's own line, or the innermost script frame (eval code). */
	private static int lineOf(RhinoException e) {
		if (SOURCE_NAME.equals(e.sourceName()) && e.lineNumber() > 0) {
			return e.lineNumber();
		}
		for (ScriptStackElement el : e.getScriptStack()) {
			if (SOURCE_NAME.equals(el.fileName) && el.lineNumber > 0) {
				return el.lineNumber;
			}
		}
		return -1;
	}

	private static ScriptException failure(ScriptSession session, ScriptException.Kind kind, String message, int line) {
		return new ScriptException(kind, session.withLine(message, line), session.clampLine(line), session.logs());
	}

	private static String describe(Throwable t) {
		String m = t.getMessage();
		return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
	}

	/**
	 * Reports syntax errors like Rhino's default reporter, but turns the interpreter's call-depth error
	 * into a {@link ScriptAbort} so that scripts cannot catch it.
	 */
	private static final class SandboxErrorReporter implements ErrorReporter {
		@Override
		public void warning(String message, String sourceName, int line, String lineSource, int lineOffset) {
		}

		@Override
		public void error(String message, String sourceName, int line, String lineSource, int lineOffset) {
			throw new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
		}

		@Override
		public EvaluatorException runtimeError(String message, String sourceName, int line, String lineSource,
				int lineOffset) {
			if (STACK_DEPTH_MESSAGE.equals(message) && Context.getCurrentContext() instanceof SandboxContext sandbox) {
				int scriptLine = SOURCE_NAME.equals(sourceName) && line > 0 ? line : ScriptSession.currentLine();
				throw new ScriptAbort(ScriptException.Kind.LIMIT, recursionMessage(sandbox.session.limits()), scriptLine);
			}
			return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
		}
	}

	/** Refuses to turn Java objects into script objects; JavaScript values pass through unchanged. */
	private static final class DenyWrapFactory extends WrapFactory {
		DenyWrapFactory() {
			setJavaPrimitiveWrap(false);
		}

		@Override
		public Object wrap(Context cx, Scriptable scope, Object obj, TypeInfo staticType) {
			if (obj == null || obj == Undefined.instance || obj instanceof Scriptable || obj instanceof CharSequence
					|| obj instanceof Number || obj instanceof Boolean) {
				return obj;
			}
			if (obj instanceof Character c) {
				return String.valueOf(c.charValue());
			}
			throw Context.reportRuntimeError("Java objects are not accessible to scripts");
		}

		@Override
		public Scriptable wrapNewObject(Context cx, Scriptable scope, Object obj) {
			if (obj instanceof Scriptable s) {
				return s;
			}
			throw Context.reportRuntimeError("Java objects are not accessible to scripts");
		}

		@Override
		public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, TypeInfo staticType) {
			throw Context.reportRuntimeError("Java objects are not accessible to scripts");
		}

		@Override
		public Scriptable wrapJavaClass(Context cx, Scriptable scope, Class<?> javaClass) {
			throw Context.reportRuntimeError("Java classes are not accessible to scripts");
		}
	}

	/** A Context that knows its run, so the shared factory can check that run's deadline. */
	private static final class SandboxContext extends Context {
		final ScriptSession session;

		SandboxContext(ContextFactory factory, ScriptSession session) {
			super(factory);
			this.session = session;
		}
	}

	/** Shared, stateless factory: disables Java-facing features and enforces deadlines and cancellation. */
	private static final class SandboxFactory extends ContextFactory {
		@Override
		protected boolean hasFeature(Context cx, int featureIndex) {
			return switch (featureIndex) {
				case Context.FEATURE_E4X, Context.FEATURE_ENHANCED_JAVA_ACCESS, Context.FEATURE_ENABLE_JAVA_MAP_ACCESS,
						Context.FEATURE_JAVA_SERIALIZATION, Context.FEATURE_DYNAMIC_SCOPE -> false;
				case Context.FEATURE_LOCATION_INFORMATION_IN_ERROR -> true;
				default -> super.hasFeature(cx, featureIndex);
			};
		}

		@Override
		protected void observeInstructionCount(Context cx, int instructionCount) {
			if (cx instanceof SandboxContext sandbox) {
				sandbox.session.checkAbort();
			}
		}

		@Override
		protected GeneratedClassLoader createClassLoader(ClassLoader parent) {
			throw new SecurityException("class generation is disabled in the script sandbox");
		}
	}
}
