package io.github.saocsb.mcai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTailTest {
	@Test
	void noLogsGiveNoDetail() {
		assertNull(AiAgent.logTail(null));
		assertNull(AiAgent.logTail(List.of()));
	}

	@Test
	void shortLogsAreIncludedWhole() {
		assertEquals("Script log before the error:\na\nb", AiAgent.logTail(List.of("a", "b")));
	}

	@Test
	void longLogsKeepTheLastTwentyLines() {
		List<String> logs = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			logs.add("line " + i);
		}
		String tail = AiAgent.logTail(logs);
		assertTrue(tail.startsWith("Script log before the error (last 20 lines):\nline 30\n"), tail);
		assertTrue(tail.endsWith("\nline 49"), tail);
	}
}
