# 知微QQ机器人

把 QQ 官方机器人接到你自己的模型上。人设自己写，回复分句发，菜单带按钮，图片、视频、语音、任何文件都能发。
只走 QQ 开放平台官方接口，不碰账号密码，配置全部存在本机。

## 中文

**它是什么**：一台手机就是一个机器人。应用以 `specialUse` 前台服务常驻，通过 WebSocket 与 QQ 网关保持长连接，收到消息后走「指令 → 人设 + 模型 → 分句 → 发送」这条链。

**能做什么**

- 人设：名字、身份、性格、说话风格、禁止项、开场白、补充设定，全部拼进系统提示词，可一键预览。
- 分句发送：按标点切句，段与段之间随机间隔 + 按字数算的打字延迟。
- 菜单按钮：消息底下挂按钮，点一下开子菜单、跑 `/指令`、或当一句话交给 AI。
- 语音与表情：模型写 `[表情:无语]`、`[语音]要说的话` 即触发；表情自己加，TTS 自己配。
- 发任意文件：管理员 `/文件 路径`，或公网链接 `/文件 https://…/a.zip`，或模型输出 `[文件:/sdcard/xxx.pdf]`。图片、视频、silk 语音自动按类型发，其余（pdf / zip / apk / docx / 日志…）按普通文件发。
- 保活引导：通知、电池白名单、自启动、后台弹出、访问所有文件，逐项检测 + 一键跳转。

**快速开始**

1. 在 [QQ 开放平台](https://q.qq.com) 建机器人，拿 `AppID` / `AppSecret`。
2. 应用 → 接入与连接 → 填进去 → 「测试凭证」。
3. 模型接口页填 BaseURL / Key / 模型名（任何 OpenAI 兼容接口）→「测试一句」。
4. 权限与保活 → 通知、电池白名单必开；要发本机文件再开「访问所有文件」。
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
| `/文件 路径或链接` | 发本机文件 / 公网文件 | 管理员 |
| `/重连` | 手动重建连接（连接不对劲时用） | 管理员 |

管理员在「接入与连接 → 管理员 openid」里填，逗号分隔；留空等于所有人可用。

**发文件**

- 本机：`/文件 /sdcard/Download/报告.pdf`。
- 公网：`/文件 https://example.com/a.zip`——交给平台去拉，大文件走这条，不占手机内存。
- 让 AI 自己判断：提示词里写 `[文件:/sdcard/xxx.pdf]`，路径必须真实存在，一次最多一个。
- 类型分流：图片 `file_type=1`（压到 1280px，gif/webp 保留动画）、视频 `2`、silk 语音 `3`、其它一律 `4` 带文件名。
- 设置里有三个开关：允许发普通文件、非管理员也能发、单文件上限（默认 10MB）。
- 普通文件能不能发取决于开放平台给你的机器人开没开这项能力；被拒时日志里是平台原始错误码，不会假装发出去。

**稳定性**

- 连接：心跳按服务端间隔发送，op11 与任何下行帧都算 ACK；独立看门狗 20 秒巡检，长时间没数据直接重连。
- 重连：指数退避 1/2/4/8/16/30 秒 + 抖动，旧 socket 的回调不会踩掉新连接；连续失败切备用网关；`op9` 自动刷 token。
- 接口：401/403 或鉴权类错误码自动刷新 AccessToken 重试一次；网络抖动与 5xx 退避重试；发送带最小间隔，避免被限流。
- 处理：同一会话串行，全局最多 4 个模型请求；模型失败重试 3 次，全失败才回一句人话。
- 兜底：前台服务守护协程 45 秒巡检，网关死了就拉；通知栏有「重连」「停止」；崩溃前先落盘配置并写日志；配置 500ms 防抖 + 退到后台立即落盘；日志留 300 条。

**构建**：`./gradlew assembleDebug` / `assembleRelease`。签名走环境变量 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，没有 keystore 时回退 debug 签名。推 `v*` 标签由 GitHub Actions 构建并发布。JDK 17 / compileSdk 35 / minSdk 24。

**边界**：同一条消息被动回复最多 5 次，分句段数加媒体别超；群聊要 @ 机器人；按钮消息必须走 markdown，且自定义按钮是开放平台「内邀」能力、模板按钮需申请，没开通时自动退回纯文字菜单；不是外挂，不做账号登录，不绕过平台限制；模型说的话由模型负责，先自己测几句。

**许可**：MIT。QQ 接口的使用需遵守《QQ 开放平台开发者服务协议》。

## English

Bring your own model to QQ's official bot API. Your persona, split replies, button menus, stickers, voice — and any file you want to send. Official endpoints only: no account password, no third‑party gateway, everything stays on the device.

**What it is**: one phone is one bot. A `specialUse` foreground service keeps a WebSocket to the QQ gateway; every message goes through command → persona + model → splitting → sending.

**Features**

- Persona: name, identity, traits, speaking style, bans, greeting, extra notes — all injected into the system prompt, with a one‑tap preview.
- Human‑like sending: split by punctuation, random gaps, typing delay scaled by length.
- Button menus: buttons under the message; each opens a submenu, runs a `/command`, or sends text to the model.
- Stickers and voice: the model writes `[sticker:name]` or `[voice]text`; you add stickers and a TTS endpoint.
- Any file: `/file <path>` for admins, `/file https://…/a.zip` for public links, or `[file:/sdcard/x.pdf]` from the model. Images, video and silk voice go out as their own types; everything else (pdf, zip, apk, docx, logs…) goes out as a plain file.
- Keep‑alive help: notifications, battery whitelist, autostart, background pop‑up, all‑files access — status check plus a jump to the right settings page.

**Quick start**

1. Create a bot at [QQ Open Platform](https://q.qq.com) and copy `AppID` / `AppSecret`.
2. App → Connection → paste and tap “Test credentials”.
3. Model page → BaseURL / key / model name (any OpenAI‑compatible endpoint) → “Test one line”.
4. Permissions → notifications and battery whitelist are required; all‑files access is needed for local files.
5. Tap “Start bot”; status turns online.
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
| `/file path or url` | send a local or public file | admins |
| `/reconnect` | rebuild the connection | admins |

Admins go in “Connection → admin openid”, comma separated; empty means everyone.

**Files in practice**

- Local: `/file /sdcard/Download/report.pdf`.
- Public: `/file https://example.com/a.zip` — the platform fetches it, so big files don't touch phone memory.
- Let the model decide: `[file:/sdcard/x.pdf]`, path must exist, one file per reply.
- Routing: image `file_type=1` (scaled to 1280px, gif/webp keep animation), video `2`, silk voice `3`, everything else `4` with a file name.
- Three settings: allow plain files, allow non‑admins, per‑file size limit (10 MB default).
- Whether `file_type=4` is accepted depends on the capability being enabled for your bot; a rejection is logged with the platform's own error code instead of pretending it was sent.

**Stability**

- Connection: heartbeat follows the server interval; op11 and any downstream frame count as an ACK; a watchdog checks every 20s and reconnects when data stops.
- Reconnects: exponential backoff 1/2/4/8/16/30s with jitter; callbacks from an old socket can't kill the new one; backup gateway after repeated failures; `op9` refreshes the token.
- API calls: 401/403 and auth error codes trigger a token refresh and one retry; network errors and 5xx are retried with backoff; sends keep a minimum gap to avoid rate limits.
- Handling: one conversation at a time, at most 4 model calls at once; a failed model call is retried 3 times before a short apology.
- Safety nets: the foreground service re‑arms the gateway every 45s, the notification has “Reconnect” and “Stop”, crashes persist config and log the reason first, config is debounced 500ms and flushed on background, logs capped at 300 lines.

**Build**: `./gradlew assembleDebug` or `assembleRelease`. Signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; without a keystore it falls back to the debug key. Pushing a `v*` tag builds and publishes via GitHub Actions. JDK 17 / compileSdk 35 / minSdk 24.

**Limits**: at most 5 passive replies per message, so keep split segments plus media under that; groups need an @mention; button messages must be markdown, custom buttons are an invite‑only capability and template buttons need approval — without them the menu falls back to plain text; this is not a plugin hack, it does no account login and bypasses nothing; whatever the model says is on the model — test it first.

**License**: MIT. Using the QQ API means following the QQ Open Platform developer agreement.

始源创想科技IT · https://github.com/mingxiozsg-star/zhiwei-qbot