package io.github.saocsb.mcai.core.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.saocsb.mcai.core.llm.FakeServer.data;
import static io.github.saocsb.mcai.core.llm.FakeServer.json;
import static io.github.saocsb.mcai.core.llm.FakeServer.sse;
import static io.github.saocsb.mcai.core.llm.FakeServer.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLlmClientTest {
	private static final LlmRequest REQUEST = new LlmRequest("sys", List.of(ChatMessage.user("hi")));
	private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

	private FakeServer server;

	@BeforeEach
	void start() throws Exception {
		server = new FakeServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private String host() {
		return "127.0.0.1:" + server.port();
	}

	private static LlmException failure(CompletableFuture<LlmResponse> future) {
		ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(15, TimeUnit.SECONDS));
		return assertInstanceOf(LlmException.class, e.getCause());
	}

	@Test
	void retriesOn429ThenSucceeds() throws Exception {
		server.enqueue(json(429, "{\"error\":{\"message\":\"Rate limit reached\"}}", "retry-after-ms", "10"));
		server.enqueue(json(200, OK));
		LlmResponse r = Cfg.openai(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		assertEquals("ok", r.text());
		assertEquals(2, server.requests.size());
	}

	@Test
	void noRetryOn401AndKeyIsRedacted() throws Exception {
		server.enqueue(json(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + Cfg.KEY + "\",\"type\":\"invalid_request_error\"}}"));
		LlmException e = failure(Cfg.openai(server.baseUrl()).client().execute(REQUEST, null));
		assertEquals("HTTP 401 from " + host() + ": Incorrect API key provided: ***", e.getMessage());
		assertEquals(401, e.statusCode());
		assertFalse(e.retryable());
		assertFalse(e.getMessage().contains(Cfg.KEY));
		server.take();
		assertNull(server.requests.poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	void giveUpAfterTwoRetries() throws Exception {
		for (int i = 0; i < 4; i++) {
			server.enqueue(json(503, "{\"error\":{\"message\":\"busy\"}}"));
		}
		LlmException e = failure(Cfg.openai(server.baseUrl()).client().execute(REQUEST, null));
		assertEquals("HTTP 503 from " + host() + ": busy", e.getMessage());
		assertTrue(e.retryable());
		assertEquals(3, server.requests.size());
	}

	@Test
	void shouldRetryHeaderOverridesStatus() throws Exception {
		server.enqueue(json(500, "{\"error\":{\"message\":\"no\"}}", "x-should-retry", "false"));
		LlmException e = failure(Cfg.openai(server.baseUrl()).client().execute(REQUEST, null));
		assertFalse(e.retryable());
		assertEquals(1, server.requests.size());

		server.requests.clear();
		server.enqueue(json(400, "{\"error\":{\"message\":\"try again\"}}", "x-should-retry", "true"));
		server.enqueue(json(200, OK));
		assertEquals("ok", Cfg.openai(server.baseUrl()).stream(false).client().execute(REQUEST, null).get(10, TimeUnit.SECONDS).text());
		assertEquals(2, server.requests.size());
	}

	@Test
	void retryAfterIsHonouredAndScaled() throws Exception {
		server.enqueue(json(429, "{\"error\":{\"message\":\"slow down\"}}", "retry-after", "3"));
		server.enqueue(json(200, OK));
		Cfg.openai(server.baseUrl()).stream(false).client(0.1).execute(REQUEST, null).get(10, TimeUnit.SECONDS);
		FakeServer.Recorded first = server.take();
		FakeServer.Recorded second = server.take();
		long gapMillis = TimeUnit.NANOSECONDS.toMillis(second.nanoTime() - first.nanoTime());
		// 3 s scaled by 0.1; the default first backoff would be at most 50 ms
		assertTrue(gapMillis >= 280 && gapMillis < 2500, "gap " + gapMillis);
	}

	@Test
	void retryAfterAboveCapIsNotWaitedFor() throws Exception {
		server.enqueue(json(429, "{\"error\":{\"message\":\"quota\"}}", "retry-after", "60"));
		LlmException e = failure(Cfg.openai(server.baseUrl()).client().execute(REQUEST, null));
		assertEquals("HTTP 429 from " + host() + ": quota (retry after 60 s)", e.getMessage());
		assertTrue(e.retryable());
		assertEquals(1, server.requests.size());
	}

	@Test
	void connectionRefusedIsRetriedThenReported() throws Exception {
		String base = server.baseUrl();
		String host = host();
		server.close();
		LlmException e = failure(Cfg.openai(base).client().execute(REQUEST, null));
		assertEquals(-1, e.statusCode());
		assertTrue(e.retryable());
		assertTrue(e.getMessage().contains(host), e.getMessage());
	}

	@Test
	void rawAndHtmlErrorBodies() throws Exception {
		server.enqueue(text(400, "text/html", "<html><head><title>400 Bad Request</title></head><body><center><h1>400 Bad Request</h1></center></body></html>"));
		server.enqueue(text(404, "text/plain", "x".repeat(1000)));
		server.enqueue(json(422, "[{\"error\":{\"code\":422,\"message\":\"Gemini says no\",\"status\":\"INVALID_ARGUMENT\"}}]"));
		server.enqueue(json(400, "{\"detail\":\"Model not loaded\"}"));
		server.enqueue(json(403, ""));
		HttpLlmClient client = Cfg.openai(server.baseUrl()).client(0);
		LlmException html = failure(client.execute(REQUEST, null));
		assertEquals("HTTP 400 from " + host() + ": 400 Bad Request 400 Bad Request", html.getMessage());
		LlmException raw = failure(client.execute(REQUEST, null));
		assertEquals("HTTP 404 from " + host() + ": " + "x".repeat(300) + "...", raw.getMessage());
		assertEquals("HTTP 422 from " + host() + ": Gemini says no", failure(client.execute(REQUEST, null)).getMessage());
		assertEquals("HTTP 400 from " + host() + ": Model not loaded", failure(client.execute(REQUEST, null)).getMessage());
		assertEquals("HTTP 403 from " + host() + ": (empty response)", failure(client.execute(REQUEST, null)).getMessage());
	}

	@Test
	void errorExtraction() {
		assertEquals("bad", HttpLlmClient.extractErrorMessage("{\"error\":{\"message\":\"bad\",\"type\":\"x\",\"code\":null}}"));
		assertEquals("overloaded_error", HttpLlmClient.extractErrorMessage("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"));
		assertEquals("plain", HttpLlmClient.extractErrorMessage("{\"error\":\"plain\"}"));
		assertEquals("a b", HttpLlmClient.extractErrorMessage("  a \n\t b "));
		assertEquals("(empty response)", HttpLlmClient.extractErrorMessage(null));
	}

	@Test
	void redactsKeyInExtraAuthHeadersToo() throws Exception {
		server.enqueue(json(400, "{\"error\":{\"message\":\"bad token tok-abcdef123 and key " + Cfg.KEY + "\"}}"));
		Cfg cfg = Cfg.openai(server.baseUrl());
		cfg.extraHeaders = FakeServer.headers("X-Auth-Token", "Bearer tok-abcdef123");
		LlmException e = failure(cfg.client().execute(REQUEST, null));
		assertEquals("HTTP 400 from " + host() + ": bad token *** and key ***", e.getMessage());
		assertEquals("Bearer tok-abcdef123", server.take().header("X-Auth-Token"));
	}

	@Test
	void errorInsideSuccessfulResponse() throws Exception {
		server.enqueue(json(200, "{\"error\":{\"message\":\"upstream failed\",\"code\":502}}"));
		server.enqueue(sse(data("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"),
				data("{\"error\":{\"message\":\"stream broke\",\"type\":\"server_error\"}}")));
		server.enqueue(sse("event: error\ndata: {\"error\":{\"message\":\"event error\"}}\n\n"));
		HttpLlmClient client = Cfg.openai(server.baseUrl()).client();
		LlmException embedded = failure(client.execute(REQUEST, null));
		assertEquals("Error from " + host() + ": upstream failed", embedded.getMessage());
		assertEquals(502, embedded.statusCode());
		assertTrue(embedded.retryable());
		LlmException stream = failure(client.execute(REQUEST, null));
		assertEquals("Error from " + host() + ": stream broke", stream.getMessage());
		assertTrue(stream.retryable());
		assertEquals("Error from " + host() + ": event error", failure(client.execute(REQUEST, null)).getMessage());
		assertEquals(3, server.requests.size());
	}

	@Test
	void cancellationAbortsTheExchange() throws Exception {
		CountDownLatch firstChunk = new CountDownLatch(1);
		CountDownLatch disconnected = new CountDownLatch(1);
		server.enqueue(exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			OutputStream out = exchange.getResponseBody();
			try {
				for (int i = 0; i < 400; i++) {
					out.write(data("{\"choices\":[{\"delta\":{\"content\":\"tick \"}}]}").getBytes(StandardCharsets.UTF_8));
					out.flush();
					Thread.sleep(25);
				}
			} catch (java.io.IOException e) {
				disconnected.countDown();
			}
		});
		AtomicInteger progressCalls = new AtomicInteger();
		CompletableFuture<LlmResponse> future = Cfg.openai(server.baseUrl()).client().execute(REQUEST, (t, r, p) -> {
			progressCalls.incrementAndGet();
			firstChunk.countDown();
		});
		assertTrue(firstChunk.await(10, TimeUnit.SECONDS));
		assertTrue(future.cancel(true));
		assertThrows(CancellationException.class, future::join);
		assertTrue(disconnected.await(5, TimeUnit.SECONDS), "server did not see the disconnect");
		int calls = progressCalls.get();
		Thread.sleep(200);
		assertTrue(progressCalls.get() <= calls + 1, "progress continued after cancel");
	}

	@Test
	void cancellationBeforeHeadersAbortsTheRequest() throws Exception {
		CountDownLatch received = new CountDownLatch(1);
		CountDownLatch closed = new CountDownLatch(1);
		AtomicInteger connections = new AtomicInteger();
		try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			Thread acceptor = new Thread(() -> {
				while (!socket.isClosed()) {
					try (Socket s = socket.accept()) {
						connections.incrementAndGet();
						received.countDown();
						// never answer; read until the client closes the connection
						while (s.getInputStream().read() != -1) {
							// discard the request
						}
						closed.countDown();
					} catch (IOException e) {
						return;
					}
				}
			});
			acceptor.setDaemon(true);
			acceptor.start();
			CompletableFuture<LlmResponse> future = Cfg.openai("http://127.0.0.1:" + socket.getLocalPort()).client().execute(REQUEST, null);
			assertTrue(received.await(10, TimeUnit.SECONDS));
			Thread.sleep(100);
			assertTrue(future.cancel(true));
			assertTrue(closed.await(5, TimeUnit.SECONDS), "connection was not closed after cancel");
			Thread.sleep(200);
			assertEquals(1, connections.get(), "request was retried after cancel");
		}
	}

	@Test
	void idleStreamTimesOut() throws Exception {
		server.enqueue(exchange -> {
			sse(data("{\"choices\":[{\"delta\":{\"content\":\"start\"}}]}")).respond(exchange);
			Thread.sleep(8000);
		});
		Cfg cfg = Cfg.openai(server.baseUrl());
		cfg.timeoutSeconds = 1;
		long start = System.nanoTime();
		LlmException e = failure(cfg.client().execute(REQUEST, null));
		long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertTrue(e.getMessage().contains("timed out"), e.getMessage());
		assertEquals(-1, e.statusCode());
		assertTrue(e.retryable());
		assertTrue(elapsed >= 900 && elapsed < 5000, "elapsed " + elapsed);
		assertEquals(1, server.requests.size());
	}

	@Test
	void headerTimeoutIsReportedWithoutRetry() throws Exception {
		server.enqueue(exchange -> Thread.sleep(8000));
		Cfg cfg = Cfg.openai(server.baseUrl());
		cfg.timeoutSeconds = 1;
		LlmException e = failure(cfg.client().execute(REQUEST, null));
		assertEquals("Request to " + host() + " timed out after 1 s", e.getMessage());
		assertTrue(e.retryable());
		assertEquals(1, server.requests.size());
	}

	@Test
	void connectionDropMidStreamFailsWithoutRetry() throws Exception {
		AtomicInteger connections = new AtomicInteger();
		try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			Thread acceptor = new Thread(() -> {
				while (!socket.isClosed()) {
					try (Socket s = socket.accept()) {
						connections.incrementAndGet();
						BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
						String line;
						while ((line = in.readLine()) != null && !line.isEmpty()) {
							// skip request headers
						}
						String chunk = data("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}");
						s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
								+ Integer.toHexString(chunk.length()) + "\r\n" + chunk + "\r\n").getBytes(StandardCharsets.UTF_8));
						s.getOutputStream().flush();
						Thread.sleep(100);
					} catch (Exception e) {
						return;
					}
				}
			});
			acceptor.setDaemon(true);
			acceptor.start();
			LlmException e = failure(Cfg.openai("http://127.0.0.1:" + socket.getLocalPort()).client().execute(REQUEST, null));
			assertEquals(-1, e.statusCode());
			assertTrue(e.getMessage().contains("lost"), e.getMessage());
			Thread.sleep(200);
			assertEquals(1, connections.get());
		}
	}

	@Test
	void misconfigurationFailsFast() {
		LlmException noUrl = failure(Cfg.openai(" ").client().execute(REQUEST, null));
		assertEquals("No base URL configured for provider 'test'", noUrl.getMessage());
		assertFalse(noUrl.retryable());
		assertEquals("Invalid base URL for provider 'test' (expected http(s)://host/...)",
				failure(Cfg.openai("ftp://x").client().execute(REQUEST, null)).getMessage());
		Cfg noModel = Cfg.openai("http://127.0.0.1:1");
		noModel.model = "";
		assertEquals("No model configured for provider 'test'", failure(noModel.client().execute(REQUEST, null)).getMessage());
		Cfg badHeader = Cfg.openai("http://127.0.0.1:1");
		badHeader.extraHeaders = Map.of("Host", "x");
		assertTrue(failure(badHeader.client().execute(REQUEST, null)).getMessage().startsWith("Invalid request settings"));
	}

	@Test
	void retryAfterParsing() {
		assertEquals(1500, HttpLlmClient.retryAfterMillis(headers("retry-after-ms", "1500")));
		assertEquals(2000, HttpLlmClient.retryAfterMillis(headers("retry-after", "2")));
		assertEquals(500, HttpLlmClient.retryAfterMillis(headers("Retry-After", "0.5")));
		assertEquals(-1, HttpLlmClient.retryAfterMillis(headers("x", "y")));
		String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30));
		long fromDate = HttpLlmClient.retryAfterMillis(headers("retry-after", date));
		assertTrue(fromDate > 25_000 && fromDate <= 30_000, "from date " + fromDate);
	}

	@Test
	void backoffDelays() {
		HttpLlmClient client = Cfg.openai("http://127.0.0.1:1").client(1.0);
		for (int i = 0; i < 20; i++) {
			long first = client.retryDelayMillis(0, -1);
			assertTrue(first >= 375 && first <= 500, "first " + first);
			long later = client.retryDelayMillis(10, -1);
			assertTrue(later >= 6000 && later <= 8000, "later " + later);
		}
		assertEquals(1234, client.retryDelayMillis(0, 1234));
		assertEquals(12, Cfg.openai("http://127.0.0.1:1").client(0.01).retryDelayMillis(1, 1200));
	}

	private static HttpHeaders headers(String name, String value) {
		return HttpHeaders.of(Map.of(name, List.of(value)), (a, b) -> true);
	}
}
