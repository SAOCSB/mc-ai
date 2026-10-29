package io.github.saocsb.mcai.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");

	/** Every key other tracks use, with its number of arguments. */
	private static final Map<String, Integer> EXPECTED = new LinkedHashMap<>();

	static {
		put("status.thinking", 1);
		put("status.thinking_chars", 2);
		put("status.writing", 2);
		put("status.running", 0);
		put("status.repairing", 4);
		put("build.start", 3);
		put("build.progress", 4);
		put("build.done", 3);
		put("build.skipped", 1);
		put("build.confirm", 2);
		put("build.confirm_none", 0);
		put("build.queued", 1);
		put("build.stopped", 0);
		put("build.nothing_to_stop", 0);
		put("build.default_name", 0);
		put("undo.start", 2);
		put("undo.done", 1);
		put("undo.nothing", 0);
		put("error.busy", 0);
		put("error.cooldown", 1);
		put("error.server_busy", 1);
		put("error.no_api_key", 1);
		put("error.llm", 1);
		put("error.llm_auth", 1);
		put("error.llm_rate", 0);
		put("error.llm_timeout", 0);
		put("error.script_failed", 1);
		put("error.refused", 0);
		put("error.truncated", 0);
		put("error.no_permission", 0);
		put("error.internal", 1);
		put("error.empty_message", 1);
		put("cmd.cleared", 1);
		put("cmd.reloaded", 2);
		put("cmd.reload_failed", 1);
		put("cmd.config_set", 2);
		put("cmd.unknown_provider", 2);
		put("cmd.status", 5);
		put("cmd.help", 0);
		put("actions.summary", 2);
		put("actions.blocked", 1);
		put("actions.disabled", 1);
		put("key.set", 0);
		put("key.unset", 0);
	}

	private static void put(String key, int args) {
		EXPECTED.put(key, args);
	}

	private static String template(String lang, String key) {
		// tr() with no arguments leaves {n} in place; un-doubles apostrophes.
		return Messages.tr(lang, key);
	}

	private static Set<Integer> indices(String text) {
		Set<Integer> result = new TreeSet<>();
		Matcher m = PLACEHOLDER.matcher(text);
		while (m.find()) {
			result.add(Integer.parseInt(m.group(1)));
		}
		return result;
	}

	@Test
	void bothLanguagesHaveExactlyTheExpectedKeys() {
		assertEquals(EXPECTED.keySet(), Messages.keys(Messages.ZH_CN));
		assertEquals(EXPECTED.keySet(), Messages.keys(Messages.EN_US));
		assertEquals(Messages.keys("zh_cn"), Messages.keys("ZH-CN"));
	}

	@Test
	void placeholderIndicesMatchTheSpecInBothLanguages() {
		for (Map.Entry<String, Integer> e : EXPECTED.entrySet()) {
			Set<Integer> expected = new TreeSet<>();
			for (int i = 0; i < e.getValue(); i++) {
				expected.add(i);
			}
			assertEquals(expected, indices(template(Messages.ZH_CN, e.getKey())), "zh_cn " + e.getKey());
			assertEquals(expected, indices(template(Messages.EN_US, e.getKey())), "en_us " + e.getKey());
		}
	}

	@Test
	void everyKeyFormatsCleanly() {
		for (String lang : new String[] {Messages.ZH_CN, Messages.EN_US}) {
			for (Map.Entry<String, Integer> e : EXPECTED.entrySet()) {
				Object[] args = new Object[e.getValue()];
				for (int i = 0; i < args.length; i++) {
					args[i] = i % 2 == 0 ? "arg" + i : Integer.valueOf(12345 + i);
				}
				String text = Messages.tr(lang, e.getKey(), args);
				String where = lang + " " + e.getKey() + ": " + text;
				assertFalse(text.isBlank(), where);
				assertFalse(text.contains("{") || text.contains("}"), where);
				assertFalse(text.contains("''"), where);
				for (int i = 0; i < args.length; i++) {
					assertTrue(text.contains(String.valueOf(args[i])), where);
				}
				assertFalse(text.contains("12,34"), where);
			}
		}
	}

	@Test
	void englishApostrophesSurviveFormatting() {
		assertEquals("You don't have permission to do that.", Messages.tr("en_us", "error.no_permission"));
		assertEquals("You're going too fast! Try again in 3 s.", Messages.tr("en_us", "error.cooldown", 3));
		for (String key : EXPECTED.keySet()) {
			for (String lang : new String[] {"zh_cn", "en_us"}) {
				String text = Messages.tr(lang, key, "a", "b", "c", "d", "e");
				assertFalse(text.contains("{"), lang + " " + key + " lost a placeholder to quoting: " + text);
			}
		}
	}

	@Test
	void documentedChineseStrings() {
		assertEquals("「城堡」建造完成！放置了 1234 个方块，用时 12 秒。输入 /ai undo 可撤销。",
				Messages.tr("zh_cn", "build.done", "城堡", 1234, 12));
		assertEquals("请在 @ai 后面写上你想说的话，例如：@ai 帮我建一座埃菲尔铁塔",
				Messages.tr("zh_cn", "error.empty_message", "@ai"));
	}

	@Test
	void numbersHaveNoGroupingSeparators() {
		assertEquals("\"Castle\" is done! Placed 300000 blocks in 12.5 s. Type /ai undo to undo it.",
				Messages.tr("en_us", "build.done", "Castle", 300000, 12.5));
		assertEquals("Building \"Castle\"... 42% (126000/300000)",
				Messages.tr("en_us", "build.progress", "Castle", 42, 126000L, 300000L));
		assertTrue(Messages.tr("en_us", "build.start", "X", 5, 3.0).endsWith("about 3 s."));
		assertTrue(Messages.tr("en_us", "build.start", "X", 5, 2.04f).endsWith("about 2 s."));
		assertTrue(Messages.tr("en_us", "build.start", "X", 5, 2.25).endsWith("about 2.3 s."));
	}

	@Test
	void unknownLanguageAndKeyFallBack() {
		assertEquals(Messages.tr("en_us", "error.refused"), Messages.tr("fr_fr", "error.refused"));
		assertEquals(Messages.tr("en_us", "error.refused"), Messages.tr(null, "error.refused"));
		assertEquals("no.such.key", Messages.tr("zh_cn", "no.such.key", 1, 2));
		assertEquals(Messages.tr("zh_cn", "error.refused"), Messages.tr("ZH_CN", "error.refused"));
		assertEquals("Internal error: null", Messages.tr("en_us", "error.internal", (Object) null));
		assertEquals("Stopped.", Messages.tr("en_us", "build.stopped", (Object[]) null));
	}

	@Test
	void resolveLanguage() {
		assertEquals("zh_cn", Messages.resolveLanguage("zh_cn", "en_us"));
		assertEquals("en_us", Messages.resolveLanguage("en_us", "zh_cn"));
		assertEquals("zh_cn", Messages.resolveLanguage(" ZH_CN ", null));
		assertEquals("zh_cn", Messages.resolveLanguage("auto", "zh_tw"));
		assertEquals("zh_cn", Messages.resolveLanguage("auto", "zh_cn"));
		assertEquals("en_us", Messages.resolveLanguage("auto", "de_de"));
		assertEquals("en_us", Messages.resolveLanguage("auto", null));
		assertEquals("zh_cn", Messages.resolveLanguage(null, "zh_hk"));
		assertEquals("en_us", Messages.resolveLanguage("klingon", "en_gb"));
	}

	@Test
	void helpListsEveryCommand() {
		for (String lang : new String[] {"zh_cn", "en_us"}) {
			String help = Messages.tr(lang, "cmd.help");
			assertTrue(help.lines().count() >= 9, help);
			for (String part : new String[] {"/ai <", "@ai ", "/ai stop", "/ai undo", "/ai confirm", "/ai clear",
					"/ai status", "/ai help", "/ai reload", "/ai config provider|model|key|baseurl"}) {
				assertTrue(help.contains(part), lang + " help lacks " + part);
			}
		}
	}

	@Test
	void statusUsesKeyStateStrings() {
		String status = Messages.tr("zh_cn", "cmd.status", "deepseek", "deepseek-chat", Messages.tr("zh_cn", "key.set"), 1, 0);
		assertEquals("服务商：deepseek\n模型：deepseek-chat\nAPI 密钥：已设置\n进行中的请求：1\n排队中的建造：0", status);
	}
}
