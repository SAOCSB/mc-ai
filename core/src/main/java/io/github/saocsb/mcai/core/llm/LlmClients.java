package io.github.saocsb.mcai.core.llm;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Creates clients for a {@link ProviderConfig}.
 *
 * <p>The {@link HttpClient} is supplied by the caller and can be shared by all clients; build it with
 * {@link #newHttpClient(ProxySelector)}, which forces HTTP/1.1 (the default HTTP/2 tries an h2c upgrade on
 * {@code http://} URLs, which local servers such as Ollama and LM Studio mishandle).
 */
public final class LlmClients {
	private static volatile String clientVersion = defaultVersion();

	private LlmClients() {
	}

	/**
	 * A client for the config's {@link ApiStyle}. Misconfiguration (blank base URL or model) does not throw
	 * here; each call then fails with an {@link LlmException} explaining it.
	 */
	public static LlmClient create(ProviderConfig config, HttpClient http) {
		return switch (config.apiStyle()) {
			case OPENAI -> new OpenAiCompatibleClient(config, http);
			case ANTHROPIC -> new AnthropicClient(config, http);
		};
	}

	/**
	 * An HTTP client suited to LLM calls: HTTP/1.1, 10 s connect timeout, normal redirects, and the given proxy
	 * selector (null = JVM default proxy settings).
	 */
	public static HttpClient newHttpClient(ProxySelector proxyOrNull) {
		HttpClient.Builder builder = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL);
		if (proxyOrNull != null) {
			builder.proxy(proxyOrNull);
		}
		return builder.build();
	}

	/**
	 * Parses a proxy setting such as {@code "127.0.0.1:7890"} or {@code "[::1]:8080"}.
	 *
	 * @return a selector that always uses that HTTP proxy, or null when the setting is blank
	 * @throws IllegalArgumentException if it is not {@code host:port}
	 */
	public static ProxySelector proxySelector(String hostPort) {
		if (hostPort == null || hostPort.isBlank()) {
			return null;
		}
		String value = hostPort.trim();
		int colon = value.lastIndexOf(':');
		if (colon <= 0 || colon == value.length() - 1) {
			throw new IllegalArgumentException("Proxy must be host:port, got '" + value + "'");
		}
		String host = value.substring(0, colon);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		int port;
		try {
			port = Integer.parseInt(value.substring(colon + 1));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Proxy port is not a number in '" + value + "'");
		}
		if (port < 1 || port > 65535 || host.isEmpty()) {
			throw new IllegalArgumentException("Proxy must be host:port, got '" + value + "'");
		}
		return ProxySelector.of(InetSocketAddress.createUnresolved(host, port));
	}

	/** Sets the version sent in the {@code User-Agent} header ("MC-AI/<version>"); defaults to the jar's version or "dev". */
	public static void setClientVersion(String version) {
		clientVersion = version == null || version.isBlank() ? defaultVersion() : version.trim();
	}

	/** The {@code User-Agent} header value, e.g. "MC-AI/0.1.0". */
	public static String userAgent() {
		return "MC-AI/" + clientVersion;
	}

	private static String defaultVersion() {
		String version = LlmClients.class.getPackage().getImplementationVersion();
		return version == null || version.isBlank() ? "dev" : version;
	}
}
