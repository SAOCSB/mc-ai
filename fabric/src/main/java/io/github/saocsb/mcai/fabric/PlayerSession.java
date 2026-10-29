package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.agent.AgentTask;
import io.github.saocsb.mcai.core.agent.Conversation;
import io.github.saocsb.mcai.core.placement.Transform;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.UUID;

/** Per-player state kept by {@link AiService}. Server thread only (the conversation itself is thread-safe). */
final class PlayerSession {
	/**
	 * A finished plan waiting for {@code /ai confirm}.
	 *
	 * @param replace       whether the script asked to replace the previous build
	 * @param replaceTarget id of the build to undo first (0: none was found when planning)
	 */
	record PendingBuild(BuildJob job, boolean replace, long replaceTarget) {
	}

	final UUID playerId;
	/** Replaced (not cleared) by /ai clear, so a request still running keeps writing to the old one. */
	Conversation conversation = new Conversation();
	final Deque<UndoRecord> undo = new ArrayDeque<>();
	/** The running agent request, or null. */
	AgentTask task;
	/** Incremented per request so late callbacks of an older request are ignored. */
	long requestId;
	long lastRequestMillis;
	PendingBuild pending;
	/** The last action-bar status of the running request, re-sent periodically because the action bar fades. */
	Component status;
	int statusTick;
	/** Transform, dimension and id of the newest build: queued, placing, waiting for /ai confirm or finished. */
	Transform lastTransform;
	ResourceKey<Level> lastDimension;
	long lastBuildId;
	/**
	 * Id of a build dropped before it placed anything (a cleared confirmation), and the build it would have replaced
	 * (0: none). Replacing the dropped build replaces that one instead.
	 */
	long discardedBuildId;
	long discardedReplaceTarget;
	/** When the player was first seen offline (0 while online); used to evict idle sessions. */
	long offlineSinceMillis;

	PlayerSession(UUID playerId) {
		this.playerId = playerId;
	}

	/** True until the request's outcome has been handled on the server thread (or it was cancelled). */
	boolean busy() {
		return task != null;
	}

	/** Pushes a non-empty record, dropping the oldest beyond {@code limit}. */
	void pushUndo(UndoRecord record, int limit) {
		if (record == null || record.isEmpty() || limit <= 0) {
			return;
		}
		undo.push(record);
		while (undo.size() > limit) {
			undo.removeLast();
		}
	}

	/** Removes and returns the undo record of build {@code id}, or null if it is not in the history. */
	UndoRecord removeUndo(long id) {
		Iterator<UndoRecord> it = undo.iterator();
		while (it.hasNext()) {
			UndoRecord record = it.next();
			if (record.id() == id) {
				it.remove();
				return record;
			}
		}
		return null;
	}

	/** Makes {@code job} the build that 'previous' placement and replace refer to. */
	void rememberBuild(BuildJob job) {
		lastTransform = job.transform();
		lastDimension = job.level().dimension();
		lastBuildId = job.id();
	}

	/** Drops the build waiting for confirmation (it never placed anything). */
	void discardPending() {
		if (pending != null) {
			long target = pending.replace() ? replaceTarget(pending.replaceTarget()) : 0;
			discardedBuildId = pending.job().id();
			discardedReplaceTarget = target;
			pending = null;
		}
	}

	/**
	 * The build a replace aimed at {@code target} must undo: {@code target} itself, or for a dropped build the one it
	 * would have replaced (0 when that is none, i.e. nothing to undo).
	 */
	long replaceTarget(long target) {
		return target != 0 && target == discardedBuildId ? discardedReplaceTarget : target;
	}

	/** The previous build's transform if it was in {@code dimension}, else null. */
	Transform previousIn(ResourceKey<Level> dimension) {
		return lastTransform != null && dimension.equals(lastDimension) ? lastTransform : null;
	}
}
