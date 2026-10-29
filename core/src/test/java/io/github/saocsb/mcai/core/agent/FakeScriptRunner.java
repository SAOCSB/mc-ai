package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.placement.PlacementSpec;
import io.github.saocsb.mcai.core.script.ScriptException;
import io.github.saocsb.mcai.core.script.ScriptLimits;
import io.github.saocsb.mcai.core.script.ScriptResult;
import io.github.saocsb.mcai.core.script.ScriptRunner;
import io.github.saocsb.mcai.core.script.WorldAction;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Scripted {@link ScriptRunner}. Results carry no real blueprint (null); their "palette" lives in a side table read
 * through {@link #inspector()}, and an empty palette means an empty blueprint.
 */
final class FakeScriptRunner implements ScriptRunner {
	@FunctionalInterface
	interface Step {
		ScriptResult run(String source) throws ScriptException;
	}

	final List<String> sources = new CopyOnWriteArrayList<>();
	final List<ScriptLimits> limits = new CopyOnWriteArrayList<>();
	final List<Long> seeds = new CopyOnWriteArrayList<>();
	final CountDownLatch blockingStarted = new CountDownLatch(1);
	final CountDownLatch interrupted = new CountDownLatch(1);
	private final ConcurrentLinkedDeque<Step> steps = new ConcurrentLinkedDeque<>();
	private final Map<ScriptResult, List<String>> palettes = Collections.synchronizedMap(new IdentityHashMap<>());

	FakeScriptRunner then(Step step) {
		steps.add(step);
		return this;
	}

	FakeScriptRunner ok(String... palette) {
		return okWithActions(List.of(), palette);
	}

	FakeScriptRunner okWithActions(List<WorldAction> actions, String... palette) {
		return then(source -> result(actions, palette));
	}

	FakeScriptRunner error(String message) {
		return then(source -> {
			throw new ScriptException(ScriptException.Kind.RUNTIME, message, 1);
		});
	}

	/** Blocks until the running thread is interrupted, then fails like the engine does. */
	FakeScriptRunner blockUntilInterrupted() {
		return then(source -> {
			blockingStarted.countDown();
			try {
				Thread.sleep(60_000);
			} catch (InterruptedException e) {
				interrupted.countDown();
				throw new ScriptException(ScriptException.Kind.CANCELLED, "script cancelled", -1);
			}
			throw new AssertionError("script was never interrupted");
		});
	}

	ScriptResult result(List<WorldAction> actions, String... palette) {
		ScriptResult r = new ScriptResult(null, PlacementSpec.defaults(), "Tower", actions, List.of(), 3);
		palettes.put(r, List.of(palette));
		return r;
	}

	@Override
	public ScriptResult run(String source, ScriptLimits limits, long seed) throws ScriptException {
		sources.add(source);
		this.limits.add(limits);
		seeds.add(seed);
		Step step = steps.poll();
		if (step == null) {
			throw new AssertionError("unexpected script run #" + sources.size());
		}
		return step.run(source);
	}

	AiAgent.BlueprintInspector inspector() {
		return new AiAgent.BlueprintInspector() {
			@Override
			public boolean isEmpty(ScriptResult result) {
				return palette(result).isEmpty();
			}

			@Override
			public List<String> palette(ScriptResult result) {
				return palettes.getOrDefault(result, List.of());
			}
		};
	}
}
