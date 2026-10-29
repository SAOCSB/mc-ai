package io.github.saocsb.mcai.core.i18n;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The mod's own user-facing strings in Simplified Chinese ("zh_cn") and English ("en_us").
 * (Server-side mod: vanilla clients have no language files for it, so strings live here.)
 * Templates use {@link java.text.MessageFormat}-style {0}, {1} placeholders.
 * Every key exists in both languages (enforced by tests).
 *
 * <p>Number arguments are written without grouping separators (integers as plain digits, other numbers with at
 * most one decimal), so templates only use plain {@code {n}} placeholders. Apostrophes in templates are doubled,
 * as {@link MessageFormat} requires.
 */
public final class Messages {
	public static final String ZH_CN = "zh_cn";
	public static final String EN_US = "en_us";

	private static final Map<String, String> ZH = zh();
	private static final Map<String, String> EN = en();

	private Messages() {
	}

	/** Formats a message; unknown language -> en_us; unknown key -> the key itself. */
	public static String tr(String lang, String key, Object... args) {
		String template = table(lang).get(key);
		if (template == null) {
			return key;
		}
		Object[] values = args == null ? new Object[0] : new Object[args.length];
		for (int i = 0; i < values.length; i++) {
			values[i] = formatArg(args[i]);
		}
		return new MessageFormat(template, Locale.ROOT).format(values);
	}

	/**
	 * Picks "zh_cn" or "en_us": a configured "zh_cn"/"en_us" wins; with "auto", client languages starting
	 * with "zh" give "zh_cn", everything else "en_us".
	 */
	public static String resolveLanguage(String configured, String clientLanguage) {
		String c = normalize(configured);
		if (c.equals(ZH_CN) || c.equals(EN_US)) {
			return c;
		}
		return normalize(clientLanguage).startsWith("zh") ? ZH_CN : EN_US;
	}

	/** All keys (for tests). */
	public static Set<String> keys(String lang) {
		return table(lang).keySet();
	}

	private static Map<String, String> table(String lang) {
		return ZH_CN.equals(normalize(lang)) ? ZH : EN;
	}

	private static String normalize(String lang) {
		return lang == null ? "" : lang.strip().toLowerCase(Locale.ROOT).replace('-', '_');
	}

	private static Object formatArg(Object arg) {
		if (arg instanceof Integer || arg instanceof Long || arg instanceof Short || arg instanceof Byte
				|| arg instanceof BigInteger || arg instanceof AtomicInteger || arg instanceof AtomicLong) {
			return arg.toString();
		}
		if (arg instanceof Double || arg instanceof Float || arg instanceof BigDecimal) {
			double d = ((Number) arg).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				return String.valueOf(d);
			}
			BigDecimal rounded = BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros();
			return rounded.signum() == 0 ? "0" : rounded.toPlainString();
		}
		return arg;
	}

	private static Map<String, String> zh() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("status.thinking", "{0} 正在思考……");
		m.put("status.thinking_chars", "{0} 正在思考……（已收到 {1} 字）");
		m.put("status.writing", "{0} 正在编写建造脚本……（{1} 行）");
		m.put("status.running", "正在运行建造脚本……");
		m.put("status.repairing", "{0} 正在修正脚本（第 {1}/{2} 次尝试）：{3}");
		m.put("build.start", "开始建造「{0}」，共 {1} 个方块，预计需要 {2} 秒。");
		m.put("build.progress", "「{0}」建造中…… {1}%（{2}/{3}）");
		m.put("build.done", "「{0}」建造完成！放置了 {1} 个方块，用时 {2} 秒。输入 /ai undo 可撤销。");
		m.put("build.skipped", "有 {0} 个位置被跳过（受保护的方块、出生点保护区、世界边界之外或超出了建筑高度限制）。");
		m.put("build.replace_missing", "撤销记录里找不到要替换的上一个建筑（可能已被撤销），所以这次没有先移除旧建筑。");
		m.put("build.warning", "提示：{0}");
		m.put("build.confirm", "「{0}」共有 {1} 个方块，规模较大。输入 /ai confirm 开始建造，或输入 /ai stop 取消。");
		m.put("build.confirm_none", "目前没有等待确认的建造。");
		m.put("build.queued", "服务器上还有其他建造在进行，你的建造已排队，前面还有 {0} 个任务。");
		m.put("build.stopped", "已停止。");
		m.put("build.nothing_to_stop", "当前没有可以停止的任务。");
		m.put("build.default_name", "建筑");
		m.put("undo.start", "正在撤销「{0}」（{1} 个方块）……");
		m.put("undo.done", "已撤销「{0}」。");
		m.put("undo.nothing", "没有可以撤销的建造。");
		m.put("undo.skipped", "有 {0} 个位置在建造之后被改动过或放有物品，撤销时保留了原样。");
		m.put("error.busy", "你的上一个请求还在处理中，请稍等一下（输入 /ai stop 可以取消）。");
		m.put("error.too_far", "这个建造离你太远了（{0} 格），服务器最多允许 {1} 格。请走近一些再试。");
		m.put("error.cooldown", "说得太快啦，请 {0} 秒后再试。");
		m.put("error.server_busy", "{0} 现在有点忙，请稍后再试。");
		m.put("error.no_api_key", "服务商「{0}」还没有配置 API 密钥。请管理员使用 /ai config key <密钥> 进行设置。");
		m.put("error.llm", "AI 服务出错了：{0}");
		m.put("error.llm_auth", "AI 服务拒绝了请求（HTTP {0}），请检查 API 密钥是否正确。");
		m.put("error.llm_rate", "AI 服务的请求太频繁或额度已用完，请稍后再试。");
		m.put("error.llm_timeout", "AI 服务响应超时，请稍后再试。");
		m.put("error.script_failed", "建造脚本没能成功运行：{0}");
		m.put("error.refused", "AI 拒绝了这个请求。");
		m.put("error.truncated", "AI 的回复太长，被截断了。请试试更简单一些的要求。");
		m.put("error.no_permission", "你没有权限执行这个操作。");
		m.put("error.internal", "发生了内部错误：{0}");
		m.put("error.empty_message", "请在 {0} 后面写上你想说的话，例如：{0} 帮我建一座埃菲尔铁塔");
		m.put("cmd.cleared", "已清空你和 {0} 的对话记录。");
		m.put("cmd.reloaded", "配置已重新加载。服务商：{0}，模型：{1}");
		m.put("cmd.reload_failed", "重新加载配置失败：{0}");
		m.put("cmd.config_set", "已将 {0} 设置为 {1}。");
		m.put("cmd.unknown_provider", "未知的服务商「{0}」。可用的服务商有：{1}");
		m.put("cmd.config_unavailable", "配置文件加载失败，暂时不能修改设置。请修正 mcai.json 后执行 /ai reload。");
		m.put("cmd.config_usage", "用法：/ai config provider|model|key|baseurl <值>（provider、key 和 baseurl 需要服务器所有者权限）");
		m.put("cmd.status", "服务商：{0}\n模型：{1}\nAPI 密钥：{2}\n进行中的请求：{3}\n排队中的建造：{4}");
		m.put("cmd.help", String.join("\n",
				"MC-AI 使用帮助：",
				"/ai <内容> — 和 AI 对话，它会陪你聊天，也能帮你建造",
				"@ai <内容> — 直接在聊天栏里说，例如：@ai 帮我建一座小木屋",
				"/ai stop — 停止当前的请求或建造",
				"/ai undo — 撤销上一次建造",
				"/ai confirm — 确认建造规模较大的建筑",
				"/ai clear — 清空你的对话记录",
				"/ai status — 查看服务商、模型和队列状态",
				"/ai help — 显示本帮助",
				"管理员：/ai reload 重新加载配置；/ai config provider|model|key|baseurl <值> 修改设置"));
		m.put("actions.summary", "附加操作已执行：成功 {0} 个，失败 {1} 个。");
		m.put("actions.blocked", "以下命令不在服务器允许的范围内，已跳过：{0}");
		m.put("actions.disabled", "服务器已禁用附加操作，跳过了 {0} 个操作。");
		m.put("key.set", "已设置");
		m.put("key.unset", "未设置");
		return Collections.unmodifiableMap(m);
	}

	private static Map<String, String> en() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("status.thinking", "{0} is thinking...");
		m.put("status.thinking_chars", "{0} is thinking... ({1} chars)");
		m.put("status.writing", "{0} is writing the build script... ({1} lines)");
		m.put("status.running", "Running the build script...");
		m.put("status.repairing", "{0} is fixing the script (attempt {1}/{2}): {3}");
		m.put("build.start", "Building \"{0}\": {1} blocks, about {2} s.");
		m.put("build.progress", "Building \"{0}\"... {1}% ({2}/{3})");
		m.put("build.done", "\"{0}\" is done! Placed {1} blocks in {2} s. Type /ai undo to undo it.");
		m.put("build.skipped", "Skipped {0} positions (protected blocks, spawn protection, outside the world border or the build height).");
		m.put("build.replace_missing", "The previous build is no longer in your undo history (it may have been undone), so nothing was removed first.");
		m.put("build.warning", "Note: {0}");
		m.put("build.confirm", "\"{0}\" has {1} blocks, which is a big build. Type /ai confirm to start it, or /ai stop to cancel.");
		m.put("build.confirm_none", "There is no build waiting for confirmation.");
		m.put("build.queued", "Other builds are running on the server; yours is queued with {0} job(s) ahead of it.");
		m.put("build.stopped", "Stopped.");
		m.put("build.nothing_to_stop", "There is nothing to stop.");
		m.put("build.default_name", "Structure");
		m.put("undo.start", "Undoing \"{0}\" ({1} blocks)...");
		m.put("undo.done", "\"{0}\" has been undone.");
		m.put("undo.nothing", "There is nothing to undo.");
		m.put("undo.skipped", "{0} positions were changed after the build or hold items, so they were left as they are.");
		m.put("error.busy", "Your previous request is still being processed. Please wait, or type /ai stop to cancel it.");
		m.put("error.too_far", "That build would be {0} blocks away from you; this server allows at most {1}. Move closer and try again.");
		m.put("error.cooldown", "You''re going too fast! Try again in {0} s.");
		m.put("error.server_busy", "{0} is busy right now. Please try again in a moment.");
		m.put("error.no_api_key", "No API key is configured for \"{0}\". An admin can set one with /ai config key <key>.");
		m.put("error.llm", "The AI service returned an error: {0}");
		m.put("error.llm_auth", "The AI service rejected the request (HTTP {0}). Please check the API key.");
		m.put("error.llm_rate", "The AI service is rate-limited or out of quota. Please try again later.");
		m.put("error.llm_timeout", "The AI service took too long to respond. Please try again.");
		m.put("error.script_failed", "The build script could not be made to work: {0}");
		m.put("error.refused", "The AI declined this request.");
		m.put("error.truncated", "The AI''s reply was too long and got cut off. Try a simpler request.");
		m.put("error.no_permission", "You don''t have permission to do that.");
		m.put("error.internal", "Internal error: {0}");
		m.put("error.empty_message", "Please write something after {0}, for example: {0} build me an Eiffel Tower");
		m.put("cmd.cleared", "Your conversation with {0} has been cleared.");
		m.put("cmd.reloaded", "Config reloaded. Provider: {0}, model: {1}");
		m.put("cmd.reload_failed", "Failed to reload the config: {0}");
		m.put("cmd.config_set", "Set {0} to {1}.");
		m.put("cmd.unknown_provider", "Unknown provider \"{0}\". Available providers: {1}");
		m.put("cmd.config_unavailable", "The config file failed to load, so settings can''t be changed now. Fix mcai.json and run /ai reload.");
		m.put("cmd.config_usage", "Usage: /ai config provider|model|key|baseurl <value> (provider, key and baseurl need server owner permission)");
		m.put("cmd.status", "Provider: {0}\nModel: {1}\nAPI key: {2}\nActive requests: {3}\nQueued builds: {4}");
		m.put("cmd.help", String.join("\n",
				"MC-AI help:",
				"/ai <message> - talk to the AI; it chats and can build for you",
				"@ai <message> - say it in normal chat, e.g. @ai build a small oak cabin",
				"/ai stop - stop your current request or build",
				"/ai undo - undo your last build",
				"/ai confirm - confirm a big build",
				"/ai clear - forget your conversation",
				"/ai status - show the provider, model and queue",
				"/ai help - show this help",
				"Admins: /ai reload reloads the config; /ai config provider|model|key|baseurl <value> changes settings"));
		m.put("actions.summary", "World actions: {0} succeeded, {1} failed.");
		m.put("actions.blocked", "These commands are not allowed on this server and were skipped: {0}");
		m.put("actions.disabled", "World actions are disabled on this server; {0} were skipped.");
		m.put("key.set", "set");
		m.put("key.unset", "not set");
		return Collections.unmodifiableMap(m);
	}
}
