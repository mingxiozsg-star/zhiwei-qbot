# 知微QQ机器人

把 QQ 官方机器人接到你自己的模型上。人设自己写，回复分句发，菜单带按钮，语音表情都能发。
只走 QQ 开放平台官方接口，不碰账号密码，配置全部存在本机。

## 中文

**它是什么**：一台手机就是一个机器人。应用以 `specialUse` 前台服务常驻，通过 WebSocket 与 QQ 网关保持长连接，收到消息后走「指令 → 人设 + 模型 → 分句 → 发送」这条链。

**能做什么**

- 人设：名字、身份、性格、说话风格、禁止项、开场白、补充设定，全部拼进系统提示词，可一键预览。
- 分句发送：按标点切句，段与段之间随机间隔 + 按字数算的打字延迟。
- 菜单按钮：消息底下挂按钮，点一下开子菜单、跑 `/指令`、或当一句话交给 AI。
- 语音与表情：模型写 `[表情:无语]`、`[语音]要说的话` 即触发；表情自己加，TTS 自己配。
- 发文件：管理员 `/文件 /sdcard/xxx.jpg`，或模型输出 `[文件:路径]`。
- 保活引导：通知、电池白名单、自启动、后台弹出、访问所有文件，逐项检测 + 一键跳转。

**快速开始**

1. 在 [QQ 开放平台](https://q.qq.com) 建机器人，拿 `AppID` / `AppSecret`。
2. 应用 → 接入与连接 → 填进去 → 「测试凭证」。
3. 模型接口页填 BaseURL / Key / 模型名（任何 OpenAI 兼容接口）→「测试一句」。
4. 权限与保活 → 通知、电池白名单必开。
5. 主页点「启动机器人」，状态变「在线」。
6. 群里 `@机器人 /菜单`，或私聊发一句。

**指令**

| 指令 | 作用 | 权限 |
| --- | --- | --- |
| `/菜单` | 弹出按钮菜单 | 所有人 |
| `/状态` | 在线时长、收发计数、当前线路 | 所有人 |
| `/表情 名字` | 发指定表情 | 所有人 |
| `/分句开` `/分句关` | 临时切换发送方式 | 所有人 |
| `/清除` | 清掉当前会话记忆 | 所有人 |
| `/语音 文本` | 让机器人说一句 | 管理员 |
| `/文件 路径` | 发送本机文件 | 管理员 |

管理员在「接入与连接 → 管理员 openid」里填，逗号分隔；留空等于所有人可用。

**语音为什么麻烦**：QQ 只收 silk。TTS 支持 base64 / 音频链接 / 直接音频三种取法，格式可选 silk、wav、mp3——非 silk 必须填转码服务，否则发送时明确报错，不假装发出去。请求体里 `{{text}}`、`{{format}}` 会被替换，响应按点路径取值（`data.audio`、`result.0.url`）。

**稳定性与占用**：单例 OkHttp（HTTP/2 优先、连接池复用、30s ping）；心跳按服务端间隔发送，超时重连；断线指数退避 1/2/4/8/16/30 秒 + 抖动，连续失败切备用网关，`op9` 自动刷 token；配置内存 StateFlow + 500ms 防抖落盘；日志留 300 条。

**构建**：`./gradlew assembleDebug` / `assembleRelease`。签名走环境变量 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，没有 keystore 时回退 debug 签名。推 `v*` 标签由 GitHub Actions 构建并发布。JDK 17 / compileSdk 35 / minSdk 24。

**边界**：同一条消息被动回复最多 5 次，分句段数加媒体别超；群聊要 @ 机器人；按钮消息必须走 markdown，且自定义按钮是开放平台「内邀」能力、模板按钮需申请，没开通时本工具自动退回纯文字菜单；`file_type=4` 普通文件官方尚未开放，只能发图片/语音/视频；不是外挂，不做账号登录，不绕过平台限制；模型说的话由模型负责，先自己测几句。

**许可**：MIT。QQ 接口的使用需遵守《QQ 开放平台开发者服务协议》。

---

## English

Bring your own model to QQ's official bot API. Your persona, split replies, button menus, stickers, voice, files. Official endpoints only — no account password, no third‑party gateway, everything stays on the device.

**What it is**: one phone is one bot. A `specialUse` foreground service keeps a WebSocket to the QQ gateway, then each message goes through command → persona + model → splitting → sending.

**Features**

- Persona: name, identity, traits, speaking style, bans, greeting, extra notes — all injected into the system prompt, with a one‑tap preview.
- Human‑like sending: split by punctuation, random gaps, typing delay scaled by length.
- Button menus: buttons under the message; each one opens a submenu, runs a `/command`, or sends text to the model.
- Stickers and voice: the model writes `[sticker:name]` or `[voice]text`; you add your own stickers and TTS endpoint.
- Files: `/file /sdcard/xxx.jpg` for admins, or `[file:path]` from the model.
- Keep‑alive help: notifications, battery whitelist, autostart, background pop‑up, all‑files access — status check plus a jump to the right settings page.

**Quick start**

1. Create a bot at [QQ Open Platform](https://q.qq.com) and copy `AppID` / `AppSecret`.
2. App → Connection → paste and tap “Test credentials”.
3. Model page → BaseURL / key / model name (any OpenAI‑compatible endpoint) → “Test one line”.
4. Permissions → notifications and battery whitelist are required.
5. Tap “Start bot” on the home screen; status turns online.
6. In a group: `@bot /menu`; or just DM it.

**Commands**

| Command | Effect | Who |
| --- | --- | --- |
| `/menu` | show the button menu | everyone |
| `/status` | uptime, counters, current route | everyone |
| `/sticker name` | send that sticker | everyone |
| `/split on` `/split off` | toggle split sending | everyone |
| `/forget` | clear this conversation's memory | everyone |
| `/voice text` | speak a line | admins |
| `/file path` | send a local file | admins |

Admins go in “Connection → admin openid”, comma separated; empty means everyone.

**Why voice is annoying**: QQ only accepts silk. TTS can return base64, an audio URL, or raw audio, in silk / wav / mp3 — non‑silk requires a converter URL, otherwise sending fails loudly instead of pretending. `{{text}}` and `{{format}}` are substituted in the body; responses are read by dot path (`data.audio`, `result.0.url`).

**Stability and footprint**: one shared OkHttp client (HTTP/2 preferred, pooled connections, 30s ping); heartbeat follows the server interval and reconnects on timeout; exponential backoff 1/2/4/8/16/30s with jitter, backup gateway after repeated failures, `op9` refreshes the token; config lives in memory with a 500ms debounce write; logs capped at 300 lines.

**Build**: `./gradlew assembleDebug` or `assembleRelease`. Signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; without a keystore it falls back to the debug key. Pushing a `v*` tag builds and publishes via GitHub Actions. JDK 17 / compileSdk 35 / minSdk 24.

**Limits**: at most 5 passive replies per message, so keep split segments plus media under that; groups need an @mention; this is not a plugin hack, it does no account login and bypasses nothing; whatever the model says is on the model — test it first.

**License**: MIT. Using the QQ API means following the QQ Open Platform developer agreement.

始源创想科技IT · https://github.com/mingxiozsg-star