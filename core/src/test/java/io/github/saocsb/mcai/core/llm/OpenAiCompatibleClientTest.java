package io.github.saocsb.mcai.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.saocsb.mcai.core.llm.FakeServer.data;
import static io.github.saocsb.mcai.core.llm.FakeServer.json;
import static io.github.saocsb.mcai.core.llm.FakeServer.sse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {
	private static final LlmRequest REQUEST = new LlmRequest("You build things.",
			List.of(ChatMessage.user("hi"), ChatMessage.assistant("hello"), ChatMessage.user("build a hut")));

	private FakeServer server;

	@BeforeEach
	void start() throws Exception {
		server = new FakeServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private static String completion(String content, String finish) {
		return "{\"id\":\"c1\",\"model\":\"served-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
				+ "\"content\":" + quote(content) + ",\"refusal\":null},"
				+ "\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";
	}

	private static String quote(String s) {
		JsonObject o = new JsonObject();
		o.addProperty("s", s);
		return o.get("s").toString();
	}

	private static String chunk(String deltaJson, String finish) {
		return data("{\"model\":\"served-model\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson
				+ ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}");
	}

	private static String content(String text) {
		return chunk("{\"content\":" + quote(text) + "}", null);
	}

	@Test
	void requestShapeAndHeaders() throws Exception {
		server.enqueue(json(200, completion("ok", "stop")));
		Cfg cfg = Cfg.openai(server.baseUrl() + "/v1/").stream(false);
		cfg.temperature = 0.3;
		cfg.effort = "low";
		cfg.maxTokensField = "max_completion_tokens";
		LlmResponse response = cfg.client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("ok", response.text());

		FakeServer.Recorded r = server.take();
		assertEquals("POST", r.method());
		assertEquals("/v1/chat/completions", r.path());
		assertEquals("Bearer " + Cfg.KEY, r.header("Authorization"));
		assertEquals("application/json", r.header("Content-Type"));
		assertEquals("application/json", r.header("Accept"));
		assertTrue(r.header("User-Agent").startsWith("MC-AI/"), r.header("User-Agent"));

		JsonObject body = r.json();
		assertEquals("test-model", body.get("model").getAsString());
		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals(4, messages.size());
		assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("You build things.", messages.get(0).getAsJsonObject().get("content").getAsString());
		assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
		assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
		assertFalse(messages.get(2).getAsJsonObject().has("reasoning_content"));
		assertEquals(1000, body.get("max_completion_tokens").getAsInt());
		assertFalse(body.has("max_tokens"));
		assertEquals(0.3, body.get("temperature").getAsDouble());
		assertEquals("low", body.get("reasoning_effort").getAsString());
		assertFalse(body.has("stream"));
	}

	@Test
	void omitsOptionalFieldsAndBlankKey() throws Exception {
		server.enqueue(json(200, completion("ok", "stop")));
		Cfg cfg = Cfg.openai(server.baseUrl() + "/v1/chat/completions").stream(false);
		cfg.apiKey = " ";
		cfg.maxTokens = 0;
		cfg.client().execute(new LlmRequest(null, List.of(ChatMessage.user("x"))), null).get(10, TimeUnit.SECONDS);

		FakeServer.Recorded r = server.take();
		assertEquals("/v1/chat/completions", r.path());
		assertNull(r.header("Authorization"));
		JsonObject body = r.json();
		assertFalse(body.has("max_tokens"));
		assertFalse(body.has("temperature"));
		assertFalse(body.has("reasoning_effort"));
		assertEquals(1, body.getAsJsonArray("messages").size());
		assertEquals("user", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
	}

	@Test
	void mergesConsecutiveSameRoleMessagesAndDropsBlankOnes() throws Exception {
		server.enqueue(json(200, completion("ok", "stop")));
		LlmRequest request = new LlmRequest("sys", List.of(ChatMessage.user("a"), ChatMessage.user("b"),
				ChatMessage.assistant("  "), ChatMessage.user("c"), ChatMessage.assistant("d"), ChatMessage.assistant("e")));
		Cfg.openai(server.baseUrl()).stream(false).client().execute(request, null).get(10, TimeUnit.SECONDS);

		JsonArray messages = server.take().json().getAsJsonArray("messages");
		assertEquals(3, messages.size());
		assertEquals("a\n\nb\n\nc", messages.get(1).getAsJsonObject().get("content").getAsString());
		assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
		assertEquals("d\n\ne", messages.get(2).getAsJsonObject().get("content").getAsString());
	}

	@Test
	void extraBodyAndHeadersAreMergedLast() throws Exception {
		server.enqueue(json(200, completion("ok", "stop")));
		Cfg cfg = Cfg.openai(server.baseUrl()).stream(false);
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("thinking", Map.of("type", "disabled"));
		extra.put("thinking_budget", 1024.0);
		extra.put("stop", List.of("END", 2.5));
		extra.put("model", "override-model");
		extra.put("max_tokens", null);
		extra.put("enable_thinking", false);
		cfg.extraBody = Collections.unmodifiableMap(extra);
		cfg.extraHeaders = FakeServer.headers("X-Title", "MC-AI", "User-Agent", "custom-agent");
		cfg.client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);

		FakeServer.Recorded r = server.take();
		assertEquals("MC-AI", r.header("X-Title"));
		assertEquals("custom-agent", r.header("User-Agent"));
		JsonObject body = r.json();
		assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
		assertTrue(r.body().contains("\"thinking_budget\":1024,"), r.body());
		assertEquals("[\"END\",2.5]", body.get("stop").toString());
		assertEquals("override-model", body.get("model").getAsString());
		assertFalse(body.has("max_tokens"));
		assertFalse(body.get("enable_thinking").getAsBoolean());
	}

	@Test
	void echoReasoningContentOnAssistantMessages() throws Exception {
		server.enqueue(json(200, completion("ok", "stop")));
		Cfg cfg = Cfg.openai(server.baseUrl()).stream(false);
		cfg.echoReasoningContent = true;
		cfg.client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);

		JsonArray messages = server.take().json().getAsJsonArray("messages");
		assertEquals(" ", messages.get(2).getAsJsonObject().get("reasoning_content").getAsString());
		assertFalse(messages.get(1).getAsJsonObject().has("reasoning_content"));
		assertFalse(messages.get(3).getAsJsonObject().has("reasoning_content"));
	}

	@Test
	void nonStreamingResponseIsParsed() throws Exception {
		server.enqueue(json(200, completion("<think>hmm</think>\n\nHere is the hut.", "stop")));
		LlmResponse r = Cfg.openai(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("Here is the hut.", r.text());
		assertEquals("stop", r.stopReason());
		assertEquals("served-model", r.model());
		assertEquals(11, r.inputTokens());
		assertEquals(7, r.outputTokens());
		assertFalse(r.truncated());
		assertFalse(r.refused());
	}

	@Test
	void nonStreamingReasoningContentIsNotText() throws Exception {
		server.enqueue(json(200, "{\"choices\":[{\"message\":{\"content\":\"answer\",\"reasoning_content\":\"secret thoughts\"},"
				+ "\"finish_reason\":\"stop\"}]}"));
		LlmResponse r = Cfg.openai(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("answer", r.text());
		assertEquals("test-model", r.model());
		assertEquals(0, r.inputTokens());
	}

	@Test
	void truncatedAndRefusedMapping() throws Exception {
		server.enqueue(json(200, "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"long\"},\"finish_reason\":\"length\"}]}"));
		server.enqueue(json(200, completion("partial", "content_filter")));
		server.enqueue(json(200, "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"I can't help with that.\"},\"finish_reason\":\"stop\"}]}"));
		HttpLlmClient client = Cfg.openai(server.baseUrl()).stream(false).client();

		LlmResponse empty = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(empty.truncated());
		assertEquals("", empty.text());

		LlmResponse filtered = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(filtered.refused());
		assertFalse(filtered.truncated());

		LlmResponse refusal = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(refusal.refused());
		assertEquals("I can't help with that.", refusal.text());
	}

	@Test
	void streamingAccumulatesTextAndReportsProgress() throws Exception {
		server.enqueue(sse(
				": OPENROUTER PROCESSING\n\n",
				chunk("{\"role\":\"assistant\",\"content\":\"\"}", null),
				chunk("{\"reasoning_content\":\"Let me think\"}", null),
				chunk("{\"reasoning\":\"more\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"more\"}]}", null),
				chunk("{\"reasoning_details\":[{\"text\":\"abc\"}]}", null),
				content("Sure! "),
				content("```js\nfill(0,0,0,4,3,4,'oak_planks');\n```"),
				chunk("{}", "stop"),
				data("{\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30}}"),
				"data: [DONE]\n\n"));
		List<int[]> progress = Collections.synchronizedList(new ArrayList<>());
		List<String> partials = Collections.synchronizedList(new ArrayList<>());
		LlmResponse r = Cfg.openai(server.baseUrl()).client().execute(REQUEST, (t, rc, p) -> {
			progress.add(new int[] {t, rc});
			partials.add(p);
		}).get(10, TimeUnit.SECONDS);

		String expected = "Sure! ```js\nfill(0,0,0,4,3,4,'oak_planks');\n```";
		assertEquals(expected, r.text());
		assertEquals("stop", r.stopReason());
		assertEquals("served-model", r.model());
		assertEquals(20, r.inputTokens());
		assertEquals(30, r.outputTokens());
		assertEquals(5, progress.size());
		int[] last = progress.getLast();
		assertEquals(expected.length(), last[0]);
		assertEquals("Let me think".length() + "more".length() + "abc".length(), last[1]);
		assertEquals(expected, partials.getLast());
		assertEquals("", partials.getFirst());
		for (int i = 1; i < progress.size(); i++) {
			assertTrue(progress.get(i)[0] >= progress.get(i - 1)[0]);
			assertTrue(progress.get(i)[1] >= progress.get(i - 1)[1]);
		}
		FakeServer.Recorded req = server.take();
		assertEquals("text/event-stream", req.header("Accept"));
		assertTrue(req.json().get("stream").getAsBoolean());
	}

	@Test
	void streamingStripsThinkSplitAcrossChunks() throws Exception {
		server.enqueue(sse(content("<thi"), content("nk>pondering the "), content("roof</thi"), content("nk>\n\nHello"),
				content(" world"), chunk("{}", "stop"), "data: [DONE]\n\n"));
		List<String> partials = Collections.synchronizedList(new ArrayList<>());
		List<Integer> reasoning = Collections.synchronizedList(new ArrayList<>());
		LlmResponse r = Cfg.openai(server.baseUrl()).client().execute(REQUEST, (t, rc, p) -> {
			partials.add(p);
			reasoning.add(rc);
		}).get(10, TimeUnit.SECONDS);
		assertEquals("Hello world", r.text());
		partials.forEach(p -> assertFalse(p.contains("think") || p.contains("ponder"), p));
		assertEquals("pondering the roof".length(), reasoning.getLast());
	}

	@Test
	void streamingTruncatedAndRefused() throws Exception {
		server.enqueue(sse(chunk("{\"reasoning_content\":\"thinking...\"}", null), chunk("{}", "length"), "data: [DONE]\n\n"));
		server.enqueue(sse(chunk("{\"refusal\":\"No.\"}", null), chunk("{}", "stop"), "data: [DONE]\n\n"));
		HttpLlmClient client = Cfg.openai(server.baseUrl()).client();
		LlmResponse truncated = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(truncated.truncated());
		assertEquals("", truncated.text());
		LlmResponse refused = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(refused.refused());
		assertEquals("No.", refused.text());
	}

	@Test
	void doneCompletesWithoutWaitingForStreamEnd() throws Exception {
		server.enqueue(exchange -> {
			sse(content("quick"), "data: [DONE]\n\n").respond(exchange);
			Thread.sleep(3000);
		});
		long start = System.nanoTime();
		LlmResponse r = Cfg.openai(server.baseUrl()).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("quick", r.text());
		assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(2500), "waited for the stream to close");
	}

	@Test
	void streamEndingWithoutDoneIsTolerated() throws Exception {
		server.enqueue(sse(content("a"), "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"},\"finish_reason\":\"stop\"}]}"));
		LlmResponse r = Cfg.openai(server.baseUrl()).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("ab", r.text());
		assertEquals("stop", r.stopReason());
		assertFalse(r.truncated());
		assertEquals(0, r.inputTokens());
	}

	@Test
	void streamEndingWithoutDoneOrFinishReasonIsIncomplete() throws Exception {
		server.enqueue(sse(content("Here:\n```js\nfill(0,0,0,"), content("4,4")));
		server.enqueue(sse(content("ok"), "data: [DONE]\n\n"));
		HttpLlmClient client = Cfg.openai(server.baseUrl()).client();
		LlmResponse r = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("Here:\n```js\nfill(0,0,0,4,4", r.text());
		assertEquals(LlmResponse.INCOMPLETE_STREAM, r.stopReason());
		assertEquals("incomplete_stream", r.stopReason());
		assertTrue(r.truncated());
		assertFalse(r.refused());
		// [DONE] alone ends the stream properly
		LlmResponse done = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("ok", done.text());
		assertFalse(done.truncated());
		assertNull(done.stopReason());
	}

	@Test
	void requestKeepsTheBaseUrlQueryAfterThePath() throws Exception {
		server.enqueue(json(200, completion("ok", "stop")));
		LlmResponse r = Cfg.openai(server.baseUrl() + "/v1/?api-version=2024-10-21&x=1").stream(false).client()
				.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("ok", r.text());
		FakeServer.Recorded req = server.take();
		assertEquals("/v1/chat/completions", req.path());
		assertEquals("api-version=2024-10-21&x=1", req.query());
	}

	@Test
	void dataLinesWithoutBlankSeparatorsAreStillParsed() throws Exception {
		server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n",
				"data: {\"choices\":[{\"delta\":{\"content\":\"y\"}}]}\n", "data: [DONE]\n"));
		LlmResponse r = Cfg.openai(server.baseUrl()).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("xy", r.text());
	}

	@Test
	void jsonBodyWhenStreamingWasRequested() throws Exception {
		server.enqueue(json(200, completion("not streamed", "stop")));
		LlmResponse r = Cfg.openai(server.baseUrl()).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("not streamed", r.text());
	}

	@Test
	void sseBodyWhenStreamingWasNotRequested() throws Exception {
		server.enqueue(sse(content("streamed anyway"), "data: [DONE]\n\n"));
		LlmResponse r = Cfg.openai(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("streamed anyway", r.text());
	}

	@Test
	void contentAsArrayOfParts() throws Exception {
		server.enqueue(json(200, "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"t\"},"
				+ "{\"type\":\"text\",\"text\":\"part1 \"},{\"type\":\"text\",\"text\":\"part2\"}]},\"finish_reason\":\"stop\"}]}"));
		LlmResponse r = Cfg.openai(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("part1 part2", r.text());
	}

	@Test
	void endpointBuilding() {
		assertEquals("https://api.deepseek.com/v1/chat/completions", OpenAiCompatibleClient.endpoint("https://api.deepseek.com/v1"));
		assertEquals("https://api.deepseek.com/v1/chat/completions", OpenAiCompatibleClient.endpoint(" https://api.deepseek.com/v1// "));
		assertEquals("http://x/v1/chat/completions", OpenAiCompatibleClient.endpoint("http://x/v1/chat/completions/"));
		assertEquals("", OpenAiCompatibleClient.endpoint(null));
		assertEquals("https://gw.example.com/openai/v1/chat/completions?api-version=2024-10-21",
				OpenAiCompatibleClient.endpoint("https://gw.example.com/openai/v1?api-version=2024-10-21"));
		assertEquals("https://x/v1/chat/completions?a=1&b=/c/", OpenAiCompatibleClient.endpoint(" https://x/v1//?a=1&b=/c/ "));
		assertEquals("https://x/v1/chat/completions?a=1", OpenAiCompatibleClient.endpoint("https://x/v1/chat/completions/?a=1"));
		assertEquals("", OpenAiCompatibleClient.endpoint("?a=1"));
	}
}
