package io.github.saocsb.mcai.core.agent;

import io.github.saocsb.mcai.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.saocsb.mcai.core.llm.ChatMessage.assistant;
import static io.github.saocsb.mcai.core.llm.ChatMessage.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTest {
	private static Conversation of(ChatMessage... messages) {
		Conversation c = new Conversation();
		for (ChatMessage m : messages) {
			c.add(m);
		}
		return c;
	}

	@Test
	void addSizeMessagesClear() {
		Conversation c = of(user("a"), assistant("b"));
		assertEquals(2, c.size());
		List<ChatMessage> copy = c.messages();
		assertEquals(List.of(user("a"), assistant("b")), copy);
		assertThrows(UnsupportedOperationException.class, () -> copy.add(user("x")));
		c.add(user("c"));
		assertEquals(2, copy.size());
		c.clear();
		assertEquals(0, c.size());
		assertEquals(List.of(), c.messages());
		assertThrows(NullPointerException.class, () -> c.add(null));
	}

	@Test
	void viewTakesTheLastMessagesStartingWithAUserMessage() {
		Conversation c = of(user("u1"), assistant("a1"), user("u2"), assistant("a2"), user("u3"), assistant("a3"));
		assertEquals(6, c.view(100).size());
		assertEquals(user("u1"), c.view(100).get(0));
		assertEquals(List.of(user("u2"), assistant("a2"), user("u3"), assistant("a3")), c.view(4));
		assertEquals(List.of(user("u3"), assistant("a3")), c.view(3));
		assertEquals(6, c.size());
		assertEquals(List.of(), c.view(1));
		assertEquals(2, c.size());
	}

	@Test
	void viewOfEmptyOrAssistantOnlyHistory() {
		assertEquals(List.of(), new Conversation().view(10));
		assertEquals(List.of(), of(assistant("a")).view(10));
	}

	@Test
	void zeroMaxGivesEmptyViewAndClearsHistory() {
		Conversation c = of(user("u1"), assistant("a1"));
		assertEquals(List.of(), c.view(0));
		assertEquals(0, c.size());
		c.add(user("u2"));
		assertEquals(List.of(), c.view(-3));
		assertEquals(0, c.size());
	}

	@Test
	void storedHistoryIsCappedAtTwiceTheView() {
		Conversation c = new Conversation();
		for (int i = 0; i < 10; i++) {
			c.add(user("u" + i));
			c.add(assistant("a" + i));
		}
		assertEquals(List.of(user("u9"), assistant("a9")), c.view(2));
		assertEquals(4, c.size());
		assertEquals(List.of(user("u8"), assistant("a8"), user("u9"), assistant("a9")), c.messages());
	}

	@Test
	void olderScriptsAreElided() {
		ChatMessage first = assistant("Here!\n```js\nhut();\n```\nEnjoy");
		ChatMessage second = assistant("Two blocks:\n```js\na();\n```\nand\n~~~javascript\nb();\n~~~");
		ChatMessage latest = assistant("Updated!\n```js\ndoor();\n```");
		ChatMessage chatOnly = assistant("You're welcome! Use `fill()` next time.");
		Conversation c = of(user("Steve: hut"), first, user("Steve: more"), second, user("Steve: door"), latest,
				user("Steve: thanks"), chatOnly);

		List<ChatMessage> view = c.view(8);

		assertEquals(assistant("Here!\n" + Conversation.ELIDED_CODE + "\nEnjoy"), view.get(1));
		assertEquals(assistant("Two blocks:\n" + Conversation.ELIDED_CODE + "\nand\n" + Conversation.ELIDED_CODE), view.get(3));
		assertSame(latest, view.get(5));
		assertSame(chatOnly, view.get(7));
		assertEquals("```js\n// (earlier script omitted)\n```", Conversation.ELIDED_CODE);
		List<ChatMessage> stored = c.messages();
		assertSame(first, stored.get(1));
		assertEquals("Here!\n```js\nhut();\n```\nEnjoy", stored.get(1).content());
		assertSame(second, stored.get(3));
	}

	@Test
	void elisionOnlyConsidersTheView() {
		Conversation c = of(user("u1"), assistant("```js\nold();\n```"), user("u2"), assistant("```js\nnew();\n```"),
				user("u3"), assistant("plain"));
		assertEquals(assistant("```js\nnew();\n```"), c.view(4).get(1));
		assertEquals(assistant(Conversation.ELIDED_CODE), c.view(6).get(1));
	}

	@Test
	void userMessagesAreNeverElided() {
		Conversation c = of(user("my code:\n```js\nx();\n```"), assistant("```js\na();\n```"), user("again"),
				assistant("```js\nb();\n```"));
		List<ChatMessage> view = c.view(4);
		assertEquals(user("my code:\n```js\nx();\n```"), view.get(0));
		assertEquals(assistant(Conversation.ELIDED_CODE), view.get(1));
		assertEquals(assistant("```js\nb();\n```"), view.get(3));
	}

	@Test
	void unterminatedBlockIsElidedToTheEnd() {
		Conversation c = of(user("u1"), assistant("Cut:\n```js\nfill(0,0"), user("u2"), assistant("```js\nok();\n```"));
		assertEquals(assistant("Cut:\n" + Conversation.ELIDED_CODE), c.view(4).get(1));
	}

	@Test
	void concurrentAddsAreSafe() throws Exception {
		Conversation c = new Conversation();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Runnable> tasks = new ArrayList<>();
			for (int t = 0; t < 4; t++) {
				tasks.add(() -> {
					for (int i = 0; i < 500; i++) {
						c.add(user("u"));
						c.view(10_000);
					}
				});
			}
			tasks.forEach(pool::execute);
		} finally {
			pool.shutdown();
			assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
		}
		assertEquals(2000, c.size());
	}
}
