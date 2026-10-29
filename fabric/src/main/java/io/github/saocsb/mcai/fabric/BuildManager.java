package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs {@link BuildJob}s on the server thread, a slice per tick. Jobs wait in one FIFO queue; each player's jobs
 * run strictly in order (an undo queued before a rebuild finishes first), while the head jobs of different players
 * share the per-tick budget round-robin. The budget is {@code blocksPerTick} entries and {@code maxTickMillis}
 * milliseconds per tick, whichever runs out first.
 */
public final class BuildManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("MC-AI");
	private static final int MIN_SLICE = 64;
	private static final int PROGRESS_INTERVAL_TICKS = 20;

	/** Job events, delivered on the server thread. */
	public interface Listener {
		/** Every 20 ticks while a (non-silent) job runs. */
		void progress(BuildJob job);

		/** A job completed ({@code cancelled == false}) or was cancelled after it had started. */
		void finished(BuildJob job, boolean cancelled);
	}

	private final List<BuildJob> queue = new ArrayList<>();
	private final Supplier<ModConfig.Build> settings;
	private final Listener listener;

	public BuildManager(Supplier<ModConfig.Build> settings, Listener listener) {
		this.settings = settings;
		this.listener = listener;
	}

	/**
	 * Queues a job.
	 *
	 * @return the number of jobs of the same owner queued ahead of it
	 */
	public int enqueue(BuildJob job) {
		int ahead = 0;
		for (BuildJob queued : queue) {
			if (Objects.equals(queued.owner(), job.owner())) {
				ahead++;
			}
		}
		queue.add(job);
		return ahead;
	}

	/** Advances the head job of every owner within this tick's budget. */
	public void tick() {
		if (queue.isEmpty()) {
			return;
		}
		ModConfig.Build cfg = settings.get();
		List<BuildJob> active = heads();
		int budget = Math.max(1, cfg.blocksPerTick);
		long maxNanos = Math.max(1, cfg.maxTickMillis) * 1_000_000L;
		long start = System.nanoTime();
		int slice = Math.max(MIN_SLICE, budget / active.size());
		List<BuildJob> failed = new ArrayList<>();
		boolean progressed = true;
		outer:
		while (budget > 0 && progressed) {
			progressed = false;
			for (BuildJob job : active) {
				if (job.isDone() || failed.contains(job)) {
					continue;
				}
				long left = maxNanos - (System.nanoTime() - start);
				if (left <= 0) {
					break outer;
				}
				try {
					int n = job.step(Math.min(slice, budget), left, cfg.protectBlockEntities, cfg.updateShapes);
					budget -= n;
					progressed |= n > 0;
				} catch (RuntimeException e) {
					LOGGER.error("Build job '{}' failed", job.name(), e);
					failed.add(job);
				}
				if (budget <= 0) {
					break outer;
				}
			}
		}
		for (BuildJob job : active) {
			if (cfg.particles) {
				particles(job);
			}
			if (failed.contains(job)) {
				queue.remove(job);
				notifyFinished(job, true);
			} else if (job.isDone()) {
				queue.remove(job);
				notifyFinished(job, false);
			} else if (job.nextTick() % PROGRESS_INTERVAL_TICKS == 0 && !job.silent()) {
				try {
					listener.progress(job);
				} catch (RuntimeException e) {
					LOGGER.error("Build progress listener failed", e);
				}
			}
		}
	}

	/**
	 * Removes every job of {@code owner}. Blocks already placed stay. Builds that had started and all undo jobs
	 * are reported through {@link Listener#finished} with {@code cancelled = true}, so a partial build can still be
	 * undone and an interrupted (or not yet started) undo is not lost.
	 *
	 * @return the number of jobs removed
	 */
	public int cancel(UUID owner) {
		List<BuildJob> notify = new ArrayList<>();
		int removed = 0;
		Iterator<BuildJob> it = queue.iterator();
		while (it.hasNext()) {
			BuildJob job = it.next();
			if (Objects.equals(job.owner(), owner)) {
				it.remove();
				removed++;
				if (job.hasStarted() || job.kind() == BuildJob.Kind.UNDO) {
					notify.add(job);
				}
			}
		}
		for (BuildJob job : notify) {
			notifyFinished(job, true);
		}
		return removed;
	}

	/**
	 * Removes the BUILD job with {@code id}, if it is queued or running. A build that had started is reported through
	 * {@link Listener#finished} with {@code cancelled = true}, so its partial record reaches the undo history.
	 *
	 * @return whether such a job was found
	 */
	public boolean cancelBuild(long id) {
		Iterator<BuildJob> it = queue.iterator();
		while (it.hasNext()) {
			BuildJob job = it.next();
			if (job.kind() == BuildJob.Kind.BUILD && job.id() == id) {
				it.remove();
				if (job.hasStarted()) {
					notifyFinished(job, true);
				}
				return true;
			}
		}
		return false;
	}

	/** Drops all jobs without notifications (server shutdown). */
	public void clear() {
		queue.clear();
	}

	/** Number of queued and running jobs. */
	public int size() {
		return queue.size();
	}

	public boolean hasJobs(UUID owner) {
		for (BuildJob job : queue) {
			if (Objects.equals(job.owner(), owner)) {
				return true;
			}
		}
		return false;
	}

	private List<BuildJob> heads() {
		List<BuildJob> heads = new ArrayList<>();
		Set<UUID> owners = new HashSet<>();
		for (BuildJob job : queue) {
			if (owners.add(job.owner())) {
				heads.add(job);
			}
		}
		return heads;
	}

	private void notifyFinished(BuildJob job, boolean cancelled) {
		try {
			listener.finished(job, cancelled);
		} catch (RuntimeException e) {
			LOGGER.error("Build completion listener failed for '{}'", job.name(), e);
		}
	}

	private static void particles(BuildJob job) {
		SimpleParticleType type = job.kind() == BuildJob.Kind.BUILD ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.CLOUD;
		for (BlockPos pos : job.recentPositions()) {
			job.level().sendParticles(type, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.0);
		}
	}
}
