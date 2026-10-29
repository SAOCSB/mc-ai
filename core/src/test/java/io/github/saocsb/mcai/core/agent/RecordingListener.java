package io.github.saocsb.mcai.core.agent;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every {@link AgentListener} callback in order. */
final class RecordingListener implements AgentListener {
	/** A chat (status null) or status (text null) callback. */
	record Event(AgentStatus status, List<Object> args, String text) {
		@Override
		public String toString() {
			return status == null ? "chat(" + text + ")" : status.name() + args;
		}
	}

	final List<Event> events = new CopyOnWriteArrayList<>();

	@Override
	public void onChat(String text) {
		events.add(new Event(null, List.of(), text));
	}

	@Override
	public void onStatus(AgentStatus status, Object... args) {
		events.add(new Event(status, Arrays.asList(args), null));
	}

	List<String> chats() {
		return events.stream().filter(e -> e.status() == null).map(Event::text).toList();
	}

	List<Event> statuses() {
		return events.stream().filter(e -> e.status() != null).toList();
	}

	List<Event> statuses(AgentStatus status) {
		return events.stream().filter(e -> e.status() == status).toList();
	}

	/** Statuses rendered like "THINKING[0]" for compact assertions. */
	List<String> statusStrings() {
		return statuses().stream().map(Event::toString).toList();
	}
}
