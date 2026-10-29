package io.github.saocsb.mcai.fabric;

import io.github.saocsb.mcai.core.agent.AgentTask;
import io.github.saocsb.mcai.core.agent.Conversation;
import io.github.saocsb.mcai.core.placement.Transform;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Per-player state kept by {@link AiService}. Server thread only (the conversation itself is thread-safe). */
final class PlayerSession {
	/** A finished plan waiting for {@code /ai confirm}. */
	record PendingBuild(BuildJob job, boolean replacePrevious) {
	}

	final UUID playerId;
	final Conversation conversation = new Conversation();
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
	Transform lastTransform;
	ResourceKey<Level> lastDimension;

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

	/** The previous build's transform if it was in {@code dimension}, else null. */
	Transform previousIn(ResourceKey<Level> dimension) {
		return lastTransform != null && dimension.equals(lastDimension) ? lastTransform : null;
	}
}
