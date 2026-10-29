package io.github.saocsb.mcai.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static io.github.saocsb.mcai.core.llm.FakeServer.event;
import static io.github.saocsb.mcai.core.llm.FakeServer.json;
import static io.github.saocsb.mcai.core.llm.FakeServer.sse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicClientTest {
	private static final LlmRequest REQUEST = new LlmRequest("You build things.",
			List.of(ChatMessage.user("hi"), ChatMessage.assistant("hello"), ChatMessage.user("a"), ChatMessage.user("b")));

	private FakeServer server;

	@BeforeEach
	void start() throws Exception {
		server = new FakeServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private static String message(String contentJson, String stopReason) {
		return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-served\",\"content\":"
				+ contentJson + ",\"stop_reason\":\"" + stopReason + "\",\"usage\":{\"input_tokens\":10,"
				+ "\"cache_creation_input_tokens\":5,\"cache_read_input_tokens\":100,\"output_tokens\":42}}";
	}

	@Test
	void requestShapeAndHeadersForCompatibleHost() throws Exception {
		server.enqueue(json(200, message("[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn")));
		Cfg cfg = Cfg.anthropic(server.baseUrl() + "/anthropic/").stream(false);
		cfg.temperature = 0.5;
		cfg.effort = "high";
		cfg.maxTokens = 0;
		cfg.client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);

		FakeServer.Recorded r = server.take();
		assertEquals("/anthropic/v1/messages", r.path());
		assertEquals(Cfg.KEY, r.header("x-api-key"));
		assertNull(r.header("Authorization"));
		assertEquals("2023-06-01", r.header("anthropic-version"));
		assertNull(r.header("anthropic-beta"));
		assertEquals("application/json", r.header("Content-Type"));

		JsonObject body = r.json();
		assertEquals("test-model", body.get("model").getAsString());
		assertEquals(16000, body.get("max_tokens").getAsInt());
		assertFalse(body.has("temperature"));
		assertFalse(body.has("top_p"));
		assertFalse(body.has("top_k"));
		assertFalse(body.has("fallbacks"));
		assertFalse(body.has("stream"));
		assertEquals("high", body.getAsJsonObject("output_config").get("effort").getAsString());
		JsonArray system = body.getAsJsonArray("system");
		assertEquals(1, system.size());
		JsonObject block = system.get(0).getAsJsonObject();
		assertEquals("text", block.get("type").getAsString());
		assertEquals("You build things.", block.get("text").getAsString());
		assertEquals("ephemeral", block.getAsJsonObject("cache_control").get("type").getAsString());
		JsonArray messages = body.getAsJsonArray("messages");
		assertEquals(3, messages.size());
		assertEquals("user", messages.get(2).getAsJsonObject().get("role").getAsString());
		assertEquals("a\n\nb", messages.get(2).getAsJsonObject().get("content").getAsString());
	}

	@Test
	void streamingDefaultMaxTokensAndNoEffort() throws Exception {
		server.enqueue(sse(event("message_stop", "{\"type\":\"message_stop\"}")));
		Cfg cfg = Cfg.anthropic(server.baseUrl());
		cfg.maxTokens = 0;
		cfg.client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		FakeServer.Recorded r = server.take();
		assertEquals("/v1/messages", r.path());
		JsonObject body = r.json();
		assertEquals(32000, body.get("max_tokens").getAsInt());
		assertTrue(body.get("stream").getAsBoolean());
		assertFalse(body.has("output_config"));
		assertEquals("text/event-stream", r.header("Accept"));
	}

	@Test
	void officialHostDetection() {
		assertTrue(AnthropicClient.isOfficialHost("https://api.anthropic.com"));
		assertTrue(AnthropicClient.isOfficialHost("https://API.Anthropic.com/"));
		assertFalse(AnthropicClient.isOfficialHost("https://api.anthropic.com.evil.example"));
		assertFalse(AnthropicClient.isOfficialHost("https://api.deepseek.com/anthropic"));
		assertFalse(AnthropicClient.isOfficialHost("http://127.0.0.1:8080"));
		assertFalse(AnthropicClient.isOfficialHost("not a url"));
		assertFalse(AnthropicClient.isOfficialHost(null));
	}

	@Test
	void officialHostGetsCurrentVersionAndFallbacks() {
		Cfg cfg = Cfg.anthropic("https://api.anthropic.com");
		cfg.temperature = 1.0;
		cfg.extraHeaders = FakeServer.headers("anthropic-beta", "some-beta-2026-01-01");
		HttpLlmClient client = cfg.client();
		HttpRequest request = client.buildHttpRequest(REQUEST);
		assertEquals("https://api.anthropic.com/v1/messages", request.uri().toString());
		assertEquals(AnthropicClient.API_VERSION, request.headers().firstValue("anthropic-version").orElseThrow());
		assertEquals("some-beta-2026-01-01," + AnthropicClient.FALLBACK_BETA,
				request.headers().firstValue("anthropic-beta").orElseThrow());
		assertEquals(Cfg.KEY, request.headers().firstValue("x-api-key").orElseThrow());

		JsonObject body = JsonParser.parseString(bodyOf(request)).getAsJsonObject();
		assertEquals("default", body.get("fallbacks").getAsString());
		assertFalse(body.has("temperature"));
		assertEquals(1000, body.get("max_tokens").getAsInt());

		cfg.extraHeaders = FakeServer.headers();
		assertEquals(List.of(AnthropicClient.FALLBACK_BETA), cfg.client().buildHttpRequest(REQUEST).headers().allValues("anthropic-beta"));
	}

	@Test
	void endpointBuilding() {
		assertEquals("https://api.anthropic.com/v1/messages", AnthropicClient.endpoint("https://api.anthropic.com/"));
		assertEquals("https://api.anthropic.com/v1/messages", AnthropicClient.endpoint("https://api.anthropic.com/v1"));
		assertEquals("https://api.anthropic.com/v1/messages", AnthropicClient.endpoint("https://api.anthropic.com/v1/messages"));
		assertEquals("https://api.deepseek.com/anthropic/v1/messages", AnthropicClient.endpoint("https://api.deepseek.com/anthropic"));
		assertEquals("https://gw.example.com/anthropic/v1/messages?key=1",
				AnthropicClient.endpoint("https://gw.example.com/anthropic/?key=1"));
		assertEquals("https://gw.example.com/v1/messages?a=b&c=d", AnthropicClient.endpoint("https://gw.example.com/v1?a=b&c=d"));
		assertEquals("https://gw.example.com/v1/messages?a", AnthropicClient.endpoint("https://gw.example.com/v1/messages?a"));
		assertTrue(AnthropicClient.isOfficialHost("https://api.anthropic.com?beta=1"));
	}

	@Test
	void requestKeepsTheBaseUrlQueryAfterThePath() throws Exception {
		server.enqueue(sse(event("message_stop", "{\"type\":\"message_stop\"}")));
		Cfg.anthropic(server.baseUrl() + "/proxy?tenant=a%20b").client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		FakeServer.Recorded req = server.take();
		assertEquals("/proxy/v1/messages", req.path());
		assertEquals("tenant=a%20b", req.query());
	}

	@Test
	void streamEndingWithoutMessageStopOrStopReasonIsIncomplete() throws Exception {
		server.enqueue(sse(
				event("message_start", "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-served\"}}"),
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Buil\"}}")));
		server.enqueue(sse(
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Done\"}}"),
				event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")));
		HttpLlmClient client = Cfg.anthropic(server.baseUrl()).client();
		LlmResponse cut = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("Buil", cut.text());
		assertEquals(LlmResponse.INCOMPLETE_STREAM, cut.stopReason());
		assertTrue(cut.truncated());
		assertFalse(cut.refused());
		assertEquals("claude-served", cut.model());
		// a stop_reason without message_stop is enough
		LlmResponse ended = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("Done", ended.text());
		assertEquals("end_turn", ended.stopReason());
		assertFalse(ended.truncated());
	}

	@Test
	void refusalNeverIncludesTheDeclinedPartialOutput() throws Exception {
		server.enqueue(sse(
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Sure, here is how to\"}}"),
				event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}"),
				event("message_stop", "{\"type\":\"message_stop\"}")));
		server.enqueue(json(200, "{\"content\":[{\"type\":\"text\",\"text\":\"Sure, here is how to\"}],\"stop_reason\":\"refusal\"}"));
		LlmResponse streamed = Cfg.anthropic(server.baseUrl()).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(streamed.refused());
		assertEquals("", streamed.text());
		LlmResponse plain = Cfg.anthropic(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(plain.refused());
		assertEquals("", plain.text());
	}

	@Test
	void nonStreamingIgnoresNonTextBlocks() throws Exception {
		server.enqueue(json(200, message("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"},"
				+ "{\"type\":\"redacted_thinking\",\"data\":\"x\"},"
				+ "{\"type\":\"fallback\",\"from\":{\"model\":\"a\"},\"to\":{\"model\":\"b\"}},"
				+ "{\"type\":\"text\",\"text\":\"Hello \"},{\"type\":\"text\",\"text\":\"there\"}]", "end_turn")));
		LlmResponse r = Cfg.anthropic(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("Hello there", r.text());
		assertEquals("end_turn", r.stopReason());
		assertEquals("claude-served", r.model());
		assertEquals(115, r.inputTokens());
		assertEquals(42, r.outputTokens());
		assertFalse(r.truncated());
		assertFalse(r.refused());
	}

	@Test
	void stopReasonMapping() throws Exception {
		server.enqueue(json(200, message("[{\"type\":\"text\",\"text\":\"cut\"}]", "max_tokens")));
		server.enqueue(json(200, message("[]", "model_context_window_exceeded")));
		server.enqueue(json(200, "{\"content\":[{\"type\":\"text\",\"text\":\"partial\"}],\"stop_reason\":\"refusal\","
				+ "\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"Declined.\"}}"));
		server.enqueue(json(200, "{\"content\":[],\"stop_reason\":\"refusal\",\"stop_details\":null}"));
		HttpLlmClient client = Cfg.anthropic(server.baseUrl()).stream(false).client();
		assertTrue(client.execute(REQUEST, null).get(10, TimeUnit.SECONDS).truncated());
		assertTrue(client.execute(REQUEST, null).get(10, TimeUnit.SECONDS).truncated());
		LlmResponse refused = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(refused.refused());
		assertEquals("Declined.", refused.text());
		LlmResponse bare = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(bare.refused());
		assertEquals("", bare.text());
		assertEquals("test-model", bare.model());
	}

	@Test
	void streamingTextThinkingAndUsage() throws Exception {
		server.enqueue(sse(
				event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"model\":\"claude-served\","
						+ "\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":3,\"output_tokens\":1}}}"),
				event("ping", "{\"type\":\"ping\"}"),
				event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"),
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Planning walls\"}}"),
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc\"}}"),
				event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
				event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Building \"}}"),
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"a hut.\"}}"),
				event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"),
				event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":57}}"),
				event("message_stop", "{\"type\":\"message_stop\"}")));
		List<int[]> progress = Collections.synchronizedList(new ArrayList<>());
		LlmResponse r = Cfg.anthropic(server.baseUrl()).client().execute(REQUEST, (t, rc, p) -> progress.add(new int[] {t, rc}))
				.get(10, TimeUnit.SECONDS);
		assertEquals("Building a hut.", r.text());
		assertEquals("end_turn", r.stopReason());
		assertEquals("claude-served", r.model());
		assertEquals(15, r.inputTokens());
		assertEquals(57, r.outputTokens());
		assertEquals(3, progress.size());
		assertEquals(0, progress.getFirst()[0]);
		assertEquals("Planning walls".length(), progress.getFirst()[1]);
		assertEquals("Building a hut.".length(), progress.getLast()[0]);
	}

	@Test
	void streamingRefusalAndTruncation() throws Exception {
		server.enqueue(sse(
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Par\"}}"),
				event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"explanation\":\"Not allowed.\"}}}"),
				event("message_stop", "{\"type\":\"message_stop\"}")));
		server.enqueue(sse(
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"long\"}}"),
				event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}"),
				event("message_stop", "{\"type\":\"message_stop\"}")));
		HttpLlmClient client = Cfg.anthropic(server.baseUrl()).client();
		LlmResponse refused = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(refused.refused());
		assertEquals("Not allowed.", refused.text());
		LlmResponse truncated = client.execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertTrue(truncated.truncated());
		assertEquals("long", truncated.text());
	}

	@Test
	void midStreamErrorFailsWithoutRetry() throws Exception {
		server.enqueue(sse(
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}"),
				event("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")));
		server.enqueue(json(200, message("[{\"type\":\"text\",\"text\":\"should not be requested\"}]", "end_turn")));
		CompletableFuture<LlmResponse> future = Cfg.anthropic(server.baseUrl()).client().execute(REQUEST, null);
		ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
		LlmException cause = assertInstanceOf(LlmException.class, e.getCause());
		assertTrue(cause.retryable());
		assertEquals("Error from 127.0.0.1:" + server.port() + ": Overloaded", cause.getMessage());
		server.take();
		assertNull(server.requests.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	void httpErrorBodyIsSurfaced() throws Exception {
		server.enqueue(json(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
				+ "\"message\":\"temperature: Extra inputs are not permitted\"},\"request_id\":\"req_1\"}"));
		CompletableFuture<LlmResponse> future = Cfg.anthropic(server.baseUrl()).client().execute(REQUEST, null);
		ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
		LlmException cause = assertInstanceOf(LlmException.class, e.getCause());
		assertEquals("HTTP 400 from 127.0.0.1:" + server.port() + ": temperature: Extra inputs are not permitted", cause.getMessage());
		assertEquals(400, cause.statusCode());
		assertFalse(cause.retryable());
	}

	@Test
	void retriesOverloaded529() throws Exception {
		server.enqueue(json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
		server.enqueue(json(200, message("[{\"type\":\"text\",\"text\":\"ok\"}]", "end_turn")));
		LlmResponse r = Cfg.anthropic(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("ok", r.text());
		assertEquals(2, server.requests.size());
	}

	static String bodyOf(HttpRequest request) {
		HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
		HttpResponse.BodySubscriber<String> collector = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
		publisher.subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription s) {
				collector.onSubscribe(s);
			}

			@Override
			public void onNext(ByteBuffer item) {
				collector.onNext(List.of(item));
			}

			@Override
			public void onError(Throwable t) {
				collector.onError(t);
			}

			@Override
			public void onComplete() {
				collector.onComplete();
			}
		});
		return collector.getBody().toCompletableFuture().join();
	}
}
