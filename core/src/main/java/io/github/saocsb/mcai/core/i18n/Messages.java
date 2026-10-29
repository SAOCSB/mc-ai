package io.github.saocsb.mcai.core.i18n;

/**
 * The mod's own user-facing strings in Simplified Chinese ("zh_cn") and English ("en_us").
 * (Server-side mod: vanilla clients have no language files for it, so strings live here.)
 * Templates use {@link java.text.MessageFormat}-style {0}, {1} placeholders.
 * Every key exists in both languages (enforced by tests).
 */
public final class Messages {
	private Messages() {
	}

	/** Formats a message; unknown language -> en_us; unknown key -> the key itself. */
	public static String tr(String lang, String key, Object... args) {
		throw new UnsupportedOperationException("TODO");
	}

	/**
	 * Picks "zh_cn" or "en_us": a configured "zh_cn"/"en_us" wins; with "auto", client languages starting
	 * with "zh" give "zh_cn", everything else "en_us".
	 */
	public static String resolveLanguage(String configured, String clientLanguage) {
		throw new UnsupportedOperationException("TODO");
	}

	/** All keys (for tests). */
	public static java.util.Set<String> keys(String lang) {
		throw new UnsupportedOperationException("TODO");
	}
}
