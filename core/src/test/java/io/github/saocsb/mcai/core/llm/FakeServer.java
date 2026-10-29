package io.github.saocsb.mcai.core.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A scripted fake LLM provider on 127.0.0.1 for client tests. */
final class FakeServer implements AutoCloseable {
	/** One request as the server saw it. */
	record Recorded(String method, String path, Headers headers, String body, long nanoTime) {
		JsonObject json() {
			return JsonParser.parseString(body).getAsJsonObject();
		}

		String header(String name) {
			return headers.getFirst(name);
		}
	}

	@FunctionalInterface
	interface Responder {
		void respond(HttpExchange exchange) throws Exception;
	}

	final BlockingQueue<Recorded> requests = new LinkedBlockingQueue<>();
	private final ConcurrentLinkedDeque<Responder> responders = new ConcurrentLinkedDeque<>();
	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool();

	FakeServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", this::handle);
		server.setExecutor(executor);
		server.start();
	}

	int port() {
		return server.getAddress().getPort();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + port();
	}

	FakeServer enqueue(Responder responder) {
		responders.add(responder);
		return this;
	}

	Recorded take() throws InterruptedException {
		Recorded r = requests.poll(10, TimeUnit.SECONDS);
		if (r == null) {
			throw new AssertionError("no request received");
		}
		return r;
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders(), body, System.nanoTime()));
			Responder responder = responders.poll();
			if (responder == null) {
				responder = json(500, "{\"error\":{\"message\":\"nothing scripted\"}}");
			}
			responder.respond(exchange);
		} catch (IOException e) {
			// client went away
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

	static Responder json(int status, String body, String... headerPairs) {
		return exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			for (int i = 0; i + 1 < headerPairs.length; i += 2) {
				exchange.getResponseHeaders().set(headerPairs[i], headerPairs[i + 1]);
			}
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				exchange.getResponseBody().write(bytes);
			}
		};
	}

	static Responder text(int status, String contentType, String body) {
		return exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		};
	}

	/** Streams the chunks as text/event-stream, flushing each one. */
	static Responder sse(String... chunks) {
		return sse(0, chunks);
	}

	/** Streams the chunks with a pause before each. */
	static Responder sse(long pauseMillis, String... chunks) {
		return exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			OutputStream out = exchange.getResponseBody();
			for (String chunk : chunks) {
				if (pauseMillis > 0) {
					Thread.sleep(pauseMillis);
				}
				out.write(chunk.getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		};
	}

	/** An OpenAI-style SSE frame. */
	static String data(String json) {
		return "data: " + json + "\n\n";
	}

	/** An Anthropic-style SSE frame with an event name. */
	static String event(String name, String json) {
		return "event: " + name + "\ndata: " + json + "\n\n";
	}

	static Map<String, String> headers(String... pairs) {
		Map<String, String> map = new java.util.LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}
}
