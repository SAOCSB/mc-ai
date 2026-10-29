package io.github.saocsb.mcai.core.preview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.saocsb.mcai.core.agent.ParsedResponse;
import io.github.saocsb.mcai.core.agent.ResponseParser;
import io.github.saocsb.mcai.core.blueprint.Blueprint;
import io.github.saocsb.mcai.core.blueprint.Bounds;
import io.github.saocsb.mcai.core.script.ScriptEngine;
import io.github.saocsb.mcai.core.script.ScriptException;
import io.github.saocsb.mcai.core.script.ScriptLimits;
import io.github.saocsb.mcai.core.script.ScriptResult;
import io.github.saocsb.mcai.core.script.WorldAction;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Developer CLI: runs a build script (or the script inside an LLM reply) and renders a preview sheet.
 *
 * <pre>
 * PreviewMain &lt;script.js | reply.md&gt; &lt;out.png&gt; [seed]
 * PreviewMain --demo &lt;out.png&gt;            (renders the built-in test scene, no script engine needed)
 * ./gradlew --offline -PcoreOnly :core:preview -Pargs="path/to/build.js /tmp/out.png"
 * </pre>
 *
 * Prints a JSON summary to stdout. Exit code 0 on success, 2 when the script fails (or a reply has no
 * script), 1 for usage, I/O or internal errors.
 */
public final class PreviewMain {
	static final int EXIT_OK = 0;
	static final int EXIT_INTERNAL = 1;
	static final int EXIT_SCRIPT = 2;

	private static final Set<String> SCRIPT_TAGS = Set.of("", "js", "javascript", "mcai", "build");
	private static final Pattern OPEN_FENCE = Pattern.compile("^(`{3,}|~{3,})\\s*([A-Za-z0-9_+.-]*)");
	private static final Pattern THINK = Pattern.compile("(?is)<(think|thinking)>.*?</\\1>|^\\s*<(think|thinking)>.*\\z");

	private PreviewMain() {
	}

	public static void main(String[] args) {
		System.setProperty("java.awt.headless", "true");
		int code = run(args, System.out, System.err);
		System.out.flush();
		System.exit(code);
	}

	/** Runs the CLI and returns the exit code (testable without System.exit). */
	static int run(String[] args, PrintStream out, PrintStream err) {
		if (args.length < 2) {
			err.println("usage: PreviewMain <script.js | reply.md> <out.png> [seed]");
			err.println("       PreviewMain --demo <out.png>");
			err.println("gradle: ./gradlew --offline -PcoreOnly :core:preview -Pargs=\"build.js /tmp/out.png [seed]\"");
			return EXIT_INTERNAL;
		}
		Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
		Path png = Path.of(args[1]);
		JsonObject summary = new JsonObject();
		if (args[0].equals("--demo")) {
			return demo(png, summary, gson, out);
		}
		Path input = Path.of(args[0]);
		long seed;
		try {
			seed = args.length > 2 ? Long.parseLong(args[2].trim()) : 0L;
		} catch (NumberFormatException e) {
			err.println("seed must be an integer: " + args[2]);
			return EXIT_INTERNAL;
		}
		summary.addProperty("ok", false);
		summary.add("error", null);
		summary.addProperty("file", input.toString());
		summary.addProperty("seed", seed);
		// Stable shape: every key is present even when the script fails (filled in on success).
		summary.add("name", null);
		summary.addProperty("blocks", 0);
		summary.add("bounds", null);
		summary.add("palette", new JsonArray());
		summary.add("actions", new JsonArray());
		summary.add("logs", new JsonArray());
		summary.add("elapsedMillis", null);
		summary.add("placement", null);

		String text;
		try {
			text = Files.readString(input, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return fail(summary, gson, out, "cannot read " + input + ": " + e, EXIT_INTERNAL);
		}

		String source = text;
		if (looksLikeReply(input, text)) {
			Extracted ex = extract(text);
			summary.addProperty("parser", ex.parser);
			summary.addProperty("chat", ex.chat);
			summary.addProperty("unterminatedCode", ex.unterminated);
			if (ex.script == null || ex.script.isBlank()) {
				return fail(summary, gson, out, "no build script (```js block) found in the reply", EXIT_SCRIPT);
			}
			source = ex.script;
		}
		summary.addProperty("scriptLines", source.split("\n", -1).length);

		ScriptResult result;
		try {
			result = new ScriptEngine().run(source, ScriptLimits.defaults(), seed);
		} catch (ScriptException e) {
			summary.addProperty("errorKind", e.kind().name());
			summary.addProperty("errorLine", e.line());
			return fail(summary, gson, out, e.getMessage(), EXIT_SCRIPT);
		} catch (UnsupportedOperationException | LinkageError e) {
			return fail(summary, gson, out, "script engine not available in this build (" + e + ")", EXIT_INTERNAL);
		} catch (RuntimeException e) {
			return fail(summary, gson, out, "internal error while running the script: " + e, EXIT_INTERNAL);
		}

		Blueprint bp = result.blueprint();
		summary.addProperty("ok", true);
		summary.addProperty("name", result.name());
		describeBlueprint(summary, VoxelSource.of(bp), bp.palette(), bp.size());
		JsonArray actions = new JsonArray();
		for (WorldAction a : result.actions()) {
			JsonObject o = new JsonObject();
			o.addProperty("type", a.getClass().getSimpleName());
			JsonElement fields = gson.toJsonTree(a);
			if (fields.isJsonObject()) {
				fields.getAsJsonObject().entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
			}
			actions.add(o);
		}
		summary.add("actions", actions);
		summary.add("logs", gson.toJsonTree(result.logs()));
		summary.addProperty("elapsedMillis", result.elapsedMillis());
		summary.add("placement", result.placement() == null ? null : gson.toJsonTree(result.placement()));

		String title = result.name() != null && !result.name().isBlank() ? result.name()
				: input.getFileName().toString();
		String subtitle = String.format(Locale.ROOT, "%s  ·  seed %d  ·  script %d ms",
				input.getFileName(), seed, result.elapsedMillis());
		return render(VoxelSource.of(bp), title, subtitle, png, summary, gson, out);
	}

	private static int demo(Path png, JsonObject summary, Gson gson, PrintStream out) {
		VoxelMap scene = DemoScene.build();
		summary.addProperty("ok", true);
		summary.add("error", null);
		summary.addProperty("name", "Demo scene");
		describeBlueprint(summary, scene, null, scene.size());
		return render(scene, "Demo scene: house, tower, sphere", "built-in test scene", png, summary, gson, out);
	}

	private static int render(VoxelSource src, String title, String subtitle, Path png, JsonObject summary, Gson gson,
			PrintStream out) {
		long t0 = System.nanoTime();
		try {
			BufferedImage img = BlueprintRenderer.renderSheet(src, title, subtitle);
			BlueprintRenderer.writePng(img, png);
		} catch (IOException | RuntimeException e) {
			summary.addProperty("ok", false);
			summary.addProperty("error", "rendering failed: " + e);
			out.println(gson.toJson(summary));
			return EXIT_INTERNAL;
		}
		summary.addProperty("image", png.toAbsolutePath().toString());
		summary.addProperty("renderMillis", (System.nanoTime() - t0) / 1_000_000);
		out.println(gson.toJson(summary));
		return EXIT_OK;
	}

	private static int fail(JsonObject summary, Gson gson, PrintStream out, String message, int code) {
		summary.addProperty("ok", false);
		summary.addProperty("error", message);
		out.println(gson.toJson(summary));
		return code;
	}

	/** blocks (non-air), entries, bounds, palette and per-id counts. */
	private static void describeBlueprint(JsonObject summary, VoxelSource src, List<String> palette, int entries) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		int[] air = {0};
		src.forEach((x, y, z, id) -> {
			if (BlockStyle.isAir(id)) {
				air[0]++;
			} else {
				counts.merge(id, 1, Integer::sum);
			}
		});
		summary.addProperty("blocks", entries - air[0]);
		summary.addProperty("entries", entries);
		summary.addProperty("air", air[0]);
		Bounds b = src.bounds();
		if (b == null) {
			summary.add("bounds", null);
		} else {
			JsonObject bo = new JsonObject();
			bo.add("min", ints(b.minX(), b.minY(), b.minZ()));
			bo.add("max", ints(b.maxX(), b.maxY(), b.maxZ()));
			bo.add("size", ints(b.sizeX(), b.sizeY(), b.sizeZ()));
			summary.add("bounds", bo);
		}
		JsonArray pal = new JsonArray();
		for (String id : palette != null ? palette : new ArrayList<>(counts.keySet())) {
			pal.add(id);
		}
		summary.add("palette", pal);
		JsonObject co = new JsonObject();
		counts.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue())
				.forEach(e -> co.addProperty(e.getKey(), e.getValue()));
		summary.add("counts", co);
	}

	private static JsonArray ints(int... v) {
		JsonArray a = new JsonArray();
		for (int i : v) {
			a.add(i);
		}
		return a;
	}

	/** A .js file is a script; anything else containing a code fence is treated as an LLM reply. */
	static boolean looksLikeReply(Path file, String text) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".js") || name.endsWith(".mjs")) {
			return false;
		}
		return name.endsWith(".md") || name.endsWith(".markdown") || text.contains("```") || text.contains("~~~");
	}

	record Extracted(String script, String chat, boolean unterminated, String parser) {
	}

	/** Uses {@link ResponseParser} when implemented, otherwise a simple fence extractor. */
	static Extracted extract(String reply) {
		try {
			ParsedResponse p = ResponseParser.parse(reply);
			return new Extracted(p.script(), p.chatText(), p.unterminatedCode(), "ResponseParser");
		} catch (UnsupportedOperationException | LinkageError e) {
			return extractFallback(reply);
		}
	}

	/** Joins ``` / ~~~ blocks tagged js, javascript, mcai, build or untagged; drops think sections. */
	static Extracted extractFallback(String reply) {
		String text = THINK.matcher(reply).replaceAll("");
		List<String> blocks = new ArrayList<>();
		StringBuilder chat = new StringBuilder();
		StringBuilder cur = null;
		String fence = null;
		boolean keep = false;
		for (String line : text.split("\n", -1)) {
			String t = line.strip();
			if (cur == null) {
				Matcher mm = OPEN_FENCE.matcher(t);
				if (mm.find()) {
					fence = mm.group(1);
					keep = SCRIPT_TAGS.contains(mm.group(2).toLowerCase(Locale.ROOT));
					cur = new StringBuilder();
				} else {
					chat.append(line).append('\n');
				}
			} else if (isClosingFence(t, fence)) {
				if (keep) {
					blocks.add(cur.toString());
				}
				cur = null;
			} else {
				cur.append(line).append('\n');
			}
		}
		boolean unterminated = cur != null;
		if (unterminated && keep) {
			blocks.add(cur.toString());
		}
		String script = blocks.isEmpty() ? null : String.join("\n", blocks);
		String chatText = chat.toString().strip().replaceAll("\n{3,}", "\n\n");
		return new Extracted(script, chatText, unterminated, "fallback");
	}

	private static boolean isClosingFence(String line, String fence) {
		if (line.length() < fence.length()) {
			return false;
		}
		char c = fence.charAt(0);
		for (int i = 0; i < line.length(); i++) {
			if (line.charAt(i) != c) {
				return false;
			}
		}
		return true;
	}
}
