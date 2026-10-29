# MC-AI：让 AI 在 Minecraft 里帮你造任何东西

MC-AI 是一个 **Fabric 服务端模组**（Minecraft Java 版 **26.3**）。它把大语言模型（LLM）接进游戏：你在聊天栏和 AI 对话，让它“建一座埃菲尔铁塔”“在我周围造个玻璃穹顶”“出个迷宫挑战我”，它就会写出建造脚本，在你面前一层一层把东西造出来。

[English](#english) · [设计文档](docs/DESIGN.md) · [脚本 API](docs/SCRIPT_API.md)

## 功能

- **用自然语言建造**：埃菲尔铁塔、城堡、金字塔、像素字、雕像、迷宫、跑酷赛道……AI 先写一段 JavaScript 建造脚本，模组在沙箱里运行，然后逐 tick 放置方块，不会卡服。
- **会改、能撤销**：“再高一点”“换成石英”会在原地重建；`/ai undo` 撤销最近的建造。
- **陪你玩**：AI 能出谜语、主持小游戏，也能召唤生物、给物品、改时间和天气（受服务器白名单限制）。
- **自动修错**：脚本报错或用了不存在的方块时，会把错误发回给 AI，让它自己改，默认最多重试 2 次。
- **支持多家模型**：DeepSeek、通义千问、Kimi、智谱 GLM、硅基流动、火山方舟（豆包）、OpenAI、Claude、Gemini、OpenRouter，以及 Ollama / LM Studio 本地模型。任何兼容 OpenAI 接口的服务都能接。
- **只装在服务端**：玩家用原版客户端就能进服。单人游戏把它装进客户端的 mods 文件夹即可（单人游戏同样跑在内置服务器上）。
- **安全默认值**：
  - 不覆盖箱子等方块实体；
  - 脚本跑在没有 Java 访问权限的沙箱里，有时间、方块数量和体积上限；
  - AI 只能执行白名单里的指令；
  - 超大建筑要先确认。

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（Minecraft 26.3）和 [Fabric API](https://modrinth.com/mod/fabric-api)。需要 Java 25。
2. 把 `mcai-<版本>.jar` 放进 `mods` 文件夹。
3. 启动一次游戏或服务器，会生成配置文件 `config/mcai.json`。
4. 填入 API Key（二选一）：
   - 编辑 `config/mcai.json`，在当前服务商（`provider`）对应的条目里填写 `apiKey`；
   - 进游戏后（需要 OP 权限）执行 `/ai config key <你的密钥>`。

也可以用环境变量提供密钥，比如 `DEEPSEEK_API_KEY`，或者通用的 `MCAI_API_KEY`。

### 切换模型

```
/ai config provider qwen          切换服务商（输入时按 Tab 有补全）
/ai config model qwen3-max        换模型
/ai config baseurl <url>          自定义接口地址（比如中转服务）
/ai reload                        修改配置文件后重新加载
```

内置服务商：`deepseek`（默认）、`qwen`、`qwen_intl`、`moonshot`、`zhipu`、`siliconflow`、`volcengine`、`openai`、`anthropic`、`gemini`、`openrouter`、`ollama`、`lmstudio`、`custom`。

> 建议选代码能力强的模型，建出来的东西会明显更好。

## 怎么玩

在聊天栏用 `@ai` 开头，或者用 `/ai` 指令：

```
@ai 在我面前建一座 100 格高的埃菲尔铁塔
@ai 给我造一个带花园的中式小院
@ai 在我周围造一个玻璃穹顶
@ai 把刚才那座塔改成石英的，再高一点
@ai 出一个 15x15 的树篱迷宫，终点放一箱钻石
@ai 用金块写 HELLO
@ai 我们来玩猜谜语吧
```

| 指令 | 说明 |
|---|---|
| `/ai <内容>` | 和 AI 说话（和 `@ai <内容>` 一样） |
| `/ai stop` | 取消正在进行的请求或建造 |
| `/ai undo` | 撤销你最近的一次建造 |
| `/ai confirm` | 确认开始一个超大的建造 |
| `/ai clear` | 清空 AI 对你的对话记忆 |
| `/ai status` | 查看当前服务商、模型、队列状态 |
| `/ai help` | 帮助 |
| `/ai reload`、`/ai config ...` | 管理员：重新加载配置、切换服务商、模型、密钥 |

建筑默认造在你面前（正面朝向你），以你脚下那一层为地面。如果要“围绕我”“在原地修改”或“建在指定坐标”，直接跟 AI 说就行，它会自己选择放置方式。

## 配置说明（`config/mcai.json`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `provider` | `deepseek` | 当前使用的服务商（`providers` 里的键） |
| `providers.<id>` | 内置预设 | `apiStyle`（`openai`/`anthropic`）、`baseUrl`、`apiKey`、`apiKeyEnv`、`model`、`maxTokens`、`temperature`、`effort`、`stream`、`timeoutSeconds`、`extraHeaders`、`extraBody` |
| `proxy` | `""` | 访问模型用的 HTTP 代理，如 `127.0.0.1:7890` |
| `language` | `auto` | 提示语言：`auto` 跟随玩家客户端语言，或固定为 `zh_cn` / `en_us` |
| `aiName` | `AI` | AI 在聊天栏里显示的名字 |
| `chatPrefixes` | `["@ai"]` | 以这些前缀开头的聊天消息会发给 AI |
| `replyVisibility` | `all` | `all`：所有人都能看到 AI 的回复；`requester`：只有提问的人能看到 |
| `maxHistoryMessages` | 12 | 每个玩家保留的对话条数 |
| `maxRepairAttempts` | 2 | 脚本出错时让 AI 修正的次数 |
| `cooldownSeconds` / `maxConcurrentRequests` | 3 / 4 | 每个玩家的冷却时间 / 全服同时处理的请求数 |
| `extraInstructions` | `""` | 追加给 AI 的额外规则或服务器设定 |
| `build.maxBlocks` | 300000 | 单次建造的方块上限 |
| `build.blocksPerTick` | 2000 | 每 tick 放置的方块数，服务器卡顿时调小 |
| `build.confirmAboveBlocks` | 100000 | 超过这个数量要先 `/ai confirm`，0 表示不需要确认 |
| `build.protectBlockEntities` | true | 不覆盖箱子、熔炉、告示牌等 |
| `build.undoHistory` | 5 | 每个玩家可撤销的建造次数 |
| `commands.enabled` | true | 是否允许 AI 执行指令（召唤、给予、时间、天气……） |
| `commands.whitelist` | 见文件 | 允许 AI 使用的指令 |
| `commands.allowSelectors` | false | 是否允许 `@a`/`@e`/`@r`/`@n` 选择器 |
| `permissions.useLevel` / `adminLevel` | 0 / 2 | 使用 AI / 管理配置所需的权限等级 |

## 从源码构建

需要 JDK 25。

```
./gradlew build
```

构建会顺带运行核心单元测试和服务端 GameTest。模组 jar 输出在 `fabric/build/libs/`。

如果你的网络访问不了 Fabric / Mojang 的服务器，可以只构建和测试与 Minecraft 无关的核心模块，用 JDK 21 以上即可：

```
./gradlew -PcoreOnly :core:test
```

项目结构：

- `core/`：纯 Java 部分，包括 LLM 客户端、Rhino 沙箱、几何生成、放置坐标变换、提示词、对话代理；
- `fabric/`：Fabric 适配层，包括指令、聊天触发、逐 tick 放置、撤销、白名单指令、GameTest。

---

## English

MC-AI is a server-side **Fabric mod for Minecraft Java 26.3** that lets players chat with a large language model in-game. Ask it to *"build a 100-block Eiffel Tower in front of me"* or *"make a hedge maze with a chest of diamonds at the end"*. It writes a small JavaScript build script, which runs in a sandbox and gets placed gradually in front of you. It can also chat, host little games, summon mobs, give items and change the time or weather, within a server-side command whitelist.

**How it works**

- **Providers:** DeepSeek, Qwen, Kimi, GLM, SiliconFlow, Volcengine Ark, OpenAI, Anthropic Claude, Gemini, OpenRouter, Ollama, LM Studio, and anything OpenAI-compatible.
- **Server-only:** vanilla clients can join, and singleplayer works too.
- **Self-repair:** failing scripts and unknown blocks go back to the model for a fix.
- **Safety:** `/ai undo`, block entities are protected, and large builds need confirmation.

**Setup**

1. Install Fabric Loader, Fabric API (Java 25) and the jar.
2. Start once.
3. Put your API key in `config/mcai.json`, or run `/ai config key <key>` as an operator. You can also use an environment variable such as `OPENAI_API_KEY` or `MCAI_API_KEY`.
4. Talk with `@ai <message>` or `/ai <message>`.

The other commands are `/ai stop`, `undo`, `confirm`, `clear`, `status` and `help`. Operators also get `/ai reload` and `/ai config provider|model|key|baseurl <value>`.

**Build:** `./gradlew build` with JDK 25. Use `./gradlew -PcoreOnly :core:test` for the Minecraft-independent core only.

The architecture is described in [docs/DESIGN.md](docs/DESIGN.md) and the script API the model uses in [docs/SCRIPT_API.md](docs/SCRIPT_API.md).

License: MIT.
