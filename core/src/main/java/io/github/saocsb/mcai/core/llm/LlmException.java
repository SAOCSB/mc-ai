package io.github.saocsb.mcai.core.llm;

/**
 * A provider call failed. The message is short and human-readable, e.g.
 * {@code "HTTP 401 from api.deepseek.com: Authentication Fails (invalid api key)"}; it never
 * contains the API key.
 */
public class LlmException extends RuntimeException {
	private final int statusCode;
	private final boolean retryable;

	public LlmException(String message, int statusCode, boolean retryable, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	public LlmException(String message, int statusCode, boolean retryable) {
		this(message, statusCode, retryable, null);
	}

	/** HTTP status, or -1 for network/timeout/parse failures. */
	public int statusCode() {
		return statusCode;
	}

	/** True for 408/409/429/5xx/529 and network errors. */
	public boolean retryable() {
		return retryable;
	}
}
