package io.github.saocsb.mcai.core.llm;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientsTest {
	@Test
	void createsClientPerStyle() {
		HttpClient http = LlmClients.newHttpClient(null);
		assertInstanceOf(OpenAiCompatibleClient.class, LlmClients.create(Cfg.openai("http://x").build(), http));
		assertInstanceOf(AnthropicClient.class, LlmClients.create(Cfg.anthropic("http://x").build(), http));
	}

	@Test
	void httpClientSettings() {
		HttpClient http = LlmClients.newHttpClient(null);
		assertEquals(HttpClient.Version.HTTP_1_1, http.version());
		assertEquals(Duration.ofSeconds(10), http.connectTimeout().orElseThrow());
		assertEquals(HttpClient.Redirect.NORMAL, http.followRedirects());
		assertTrue(http.proxy().isEmpty());
		ProxySelector selector = ProxySelector.of(new InetSocketAddress("127.0.0.1", 9));
		assertEquals(selector, LlmClients.newHttpClient(selector).proxy().orElseThrow());
	}

	@Test
	void proxySelectorParsing() {
		assertNull(LlmClients.proxySelector(" "));
		assertNull(LlmClients.proxySelector(null));
		List<Proxy> proxies = LlmClients.proxySelector("127.0.0.1:7890").select(URI.create("https://api.deepseek.com"));
		InetSocketAddress address = (InetSocketAddress) proxies.getFirst().address();
		assertEquals("127.0.0.1", address.getHostString());
		assertEquals(7890, address.getPort());
		assertEquals("::1", ((InetSocketAddress) LlmClients.proxySelector("[::1]:8080")
				.select(URI.create("http://x")).getFirst().address()).getHostString());
		assertThrows(IllegalArgumentException.class, () -> LlmClients.proxySelector("localhost"));
		assertThrows(IllegalArgumentException.class, () -> LlmClients.proxySelector("localhost:abc"));
		assertThrows(IllegalArgumentException.class, () -> LlmClients.proxySelector("localhost:70000"));
	}

	@Test
	void requestsGoThroughTheProxy() throws Exception {
		try (FakeServer proxy = new FakeServer()) {
			proxy.enqueue(FakeServer.json(200, "{\"choices\":[{\"message\":{\"content\":\"via proxy\"}}]}"));
			HttpClient http = LlmClients.newHttpClient(LlmClients.proxySelector("127.0.0.1:" + proxy.port()));
			Cfg cfg = Cfg.openai("http://llm.example.invalid/v1").stream(false);
			LlmResponse r = new OpenAiCompatibleClient(cfg.build(), http).complete(
					new LlmRequest("s", List.of(ChatMessage.user("u"))), LlmProgressListener.NONE).get(10, TimeUnit.SECONDS);
			assertEquals("via proxy", r.text());
			FakeServer.Recorded recorded = proxy.take();
			assertEquals("/v1/chat/completions", recorded.path());
			assertEquals("llm.example.invalid", recorded.header("Host"));
		}
	}

	@Test
	void userAgent() {
		assertTrue(LlmClients.userAgent().startsWith("MC-AI/"));
		try {
			LlmClients.setClientVersion("1.2.3");
			assertEquals("MC-AI/1.2.3", LlmClients.userAgent());
		} finally {
			LlmClients.setClientVersion(null);
		}
		assertEquals("MC-AI/dev", LlmClients.userAgent());
	}
}
