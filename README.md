# 知微QQ机器人

把 QQ 官方机器人接到你自己的模型上。人设自己写、有脾气，回复分句发，菜单带按钮，图片、视频、语音、任何文件和整个目录都能发。记住聊过的人，群里私聊都认得。
只走 QQ 开放平台官方接口，不碰账号密码，配置与记忆全部存在本机。

## 中文

**它是什么**：一台手机就是一个机器人。应用以 `specialUse` 前台服务常驻，通过 WebSocket 与 QQ 网关保持长连接，收到消息后走「指令 → 人设 + 模型 → 分句 → 发送」这条链。

**能做什么**

- 人设：名字、身份、性格、说话风格、禁止项、开场白、补充设定，全部拼进系统提示词，可一键预览。默认是「知微」——会撒娇、会反驳、会生气的全能可爱助手。
- 长期记忆：按人存，群聊和私聊共用一份资料；同一个人换群后照样认得（按 `union_openid` 归并）。除了自动记名字与喜好，还能把每句对话原话都留档，回复前回放最近与最相关的几条。
- 全量对话留存：开关默认开启，你说的每句、它回的每句都按人落盘，一两句聊天也有上下文；记忆库页可按人展开、逐条删除。
- 文件速查与快发：`q` 查目录、`w` 扫安装包、`e` 发 apk、`s` 发压缩包；也可以直接说「发一下 /sdcard/Download/报告.pdf」。
- 分句发送：按标点切句，段与段之间随机间隔 + 按字数算的打字延迟。
- 菜单按钮：消息底下挂按钮，点一下开子菜单、跑 `/指令`、或当一句话交给 AI。
- 语音与表情：模型写 `[表情:无语]`、`[语音]要说的话` 即触发；表情自己加，TTS 自己配。
- 发任意文件：管理员 `/文件 路径`，或公网链接 `/文件 https://…/a.zip`，或模型输出 `[文件:/sdcard/xxx.pdf]`。图片、视频、silk 语音自动按类型发，其余（pdf / zip / apk / docx / 日志…）按普通文件发。
- 发整个目录：模型输出 `[目录:/sdcard/Download/资料]`，或管理员 `/文件 /sdcard/Download/资料`，目录里的文件逐个发，并汇报成功与失败数量。
- 状态查询：`/状态` 给出确切信息——运行平台、机型、系统、电量、内存、存储、运行时长、线路、收发计数与记忆统计。
- 保活引导：通知、电池白名单、自启动、后台弹出、访问所有文件，逐项检测 + 一键跳转。
- 抗 Doze 三重保险：CPU 锁只防 CPU 睡、防不住息屏掐 WiFi，所以同时持有 `WIFI_MODE_FULL_HIGH_PERF` 的 WiFi 锁；进程被冻结时协程 `delay` 会一起冻结，改用 `AlarmManager.setAndAllowWhileIdle` 每 15 分钟穿透 Doze 唤醒自检；网络恢复时通过 `ConnectivityManager` 回调立刻重连，不再干等 30 秒退避；在最近任务划掉卡片会在 `onTaskRemoved` 里自动复活。
- 双进程保活：主服务之外的 `:guard` 独立进程，读主服务写的心跳文件 `bot.alive` 判断主服务还在不在（不用 `getRunningServices`，它在 Android 8+ 只返回自己进程的服务，跨进程恒判死亡会造成无限重启），真死了才拉起。
- 快捷指令自定义：触发词、说明、动作、参数全部可在应用内增删改查与启停，出厂带 `q`/`w`/`e`/`s`/`目录`/`备份`/`状态`；指令表同时拼进系统提示词，模型知道有哪些指令、能干什么，你说「用 q 列一下目录」它不会再当普通聊天。
- 模型按用途分配：普通对话、识图、语音稿、画图四栏可各填 BaseURL / Key / 模型名，留空自动回落到主模型——「普通模型能顶替就不用填」。别人发来图片时自动切到识图栏，把图以 `image_url` 混排进请求；四栏都能单独测通，画图栏点自测会真画一张出来验证。
- 画图与语音稿：模型写 `[画图:描述]` 或发 `/画图 描述` 即现画一张发出来；`[语音]文本` 会先经语音栏模型改写成纯口语稿（去掉 Markdown 与表情符号），再交 TTS 合成 silk，念出来不再带 `**` 和 `~`。
- 备份与恢复：配置、记忆库、全部对话、表情原图打成一个 zip 存到 `/sdcard/ZhiweiQBot`，换机或重装时一键覆盖还原，也可从任意路径导入。
- 看目录不再发文件：说「看/列一下某目录」只回紧凑文件清单（名字、大小、时间），不再把文件本身推给你；「内部存储」「下载」这类口语路径直接映射到真实目录。

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
| `/状态` | 运行平台、设备状态、线路、收发、记忆统计 | 所有人 |
| `/记忆` | 看机器人记得你的哪些事 | 所有人 |
| `/记住 内容` | 记一条（最多 3 条） | 所有人 |
| `/忘记` | 删掉机器人对你的全部记忆 | 所有人 |
| `/记忆库` | 记忆库概况 | 管理员 |
| `/表情 名字` | 发指定表情 | 所有人 |
| `/分句开` `/分句关` | 临时切换发送方式 | 所有人 |
| `/清除` | 清掉当前会话记忆（长期记忆不受影响） | 所有人 |
| `/语音 文本` | 让机器人说一句 | 管理员 |
| `/文件 路径或目录或链接` | 发本机文件 / 整个目录 / 公网文件 | 管理员 |
| `q [目录]` | 查目录里有什么，带大小 | 所有人 |
| `w [关键词]` | 扫下载与应用目录里的安装包 | 所有人 |
| `e 路径或文件名` | 直接把 apk 发出来 | 所有人 |
| `s 路径` 或 `s 目录 文件名` | 发压缩包 / 发指定文件 | 所有人 |
| `/重连` | 手动重建连接（连接不对劲时用） | 管理员 |

管理员在「接入与连接 → 管理员 openid」里填，逗号分隔；留空等于所有人可用。

**长期记忆**

- 按人存，不按会话存：群聊与私聊共用同一份资料，私聊里聊熟的人，在群里被 @ 时照样被认出来。
- 自动记：聊天中出现「我叫 / 我喜欢 / 我讨厌」这类句式会自动落库；模型想记事会单独写 `[记忆:内容]`，写进回复前会被剥掉、落库后再发出。
- 手动记：`/记住 你喜欢猫，讨厌加班`（最多 3 条）；`/记忆` 看它记得你的全部；`/忘记` 删掉你的资料。
- 注入提示词：每次回复会带上「当前说话的人」的昵称、聊过几句，以及按关键词 + 新旧选出的最相关几条记忆，在「设置 → 人设与回复」里能一键开关。
- 隐私：不记密码、手机号、身份证这类信息；记忆全部存在本机 `filesDir/memory.json`，崩溃/退后台自动落盘，应用内「记忆库」页可逐条查看与删除。

**发文件**

- 本机：`/文件 /sdcard/Download/报告.pdf`。
- 整个目录：`/文件 /sdcard/Download/资料`——目录里的普通文件逐个发，并汇报成功与失败数量。
- 公网：`/文件 https://example.com/a.zip`——交给平台去拉，大文件走这条，不占手机内存。
- 让 AI 自己判断：提示词里写 `[文件:/sdcard/xxx.pdf]`，路径必须真实存在，一次最多一个；要发整个目录写 `[目录:/sdcard/Download/资料]`。
- 类型分流：图片 `file_type=1`（压到 1280px，gif/webp 保留动画）、视频 `2`、silk 语音 `3`、其它一律 `4` 带文件名；apk 按普通文件走 `file_type=4`。
- 设置里有三个开关：允许发普通文件、非管理员也能发、单文件上限（默认 10MB）。
- 普通文件能不能发取决于开放平台给你的机器人开没开这项能力；被拒时日志里是平台原始错误码，不会假装发出去。

**稳定性**

- 连接：WebSocket 客户端不设协议层 ping（QQ 网关不回标准 pong，设了会被 OkHttp 主动掐断，表现就是周期性无故掉线），保活完全交给协议层心跳；心跳按服务端间隔发送，op11 与任何下行帧都算 ACK；独立看门狗 20 秒巡检，长时间没数据直接重连。
- 重连：指数退避 1/2/4/8/16/30 秒 + 抖动，旧 socket 的回调不会踩掉新连接；连续失败切备用网关；`op9` 自动刷 token。
- 接口：401/403 或鉴权类错误码自动刷新 AccessToken 重试一次；网络抖动与 5xx 退避重试；发送带最小间隔，避免被限流。
- 处理：同一会话串行，全局最多 4 个模型请求；模型失败重试 3 次，全失败才回一句人话。
- 兜底：前台服务守护协程 45 秒巡检，网关死了就拉；通知栏有「重连」「停止」；崩溃前先落盘配置并写日志；配置 500ms 防抖 + 退到后台立即落盘；日志留 300 条。

**构建**：`./gradlew assembleDebug` / `assembleRelease`。签名走环境变量 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，**找不到正式密钥时构建直接失败**——换签名的包会导致旧版无法覆盖安装、用户必须卸载重装并丢数据，所以绝不允许回退 debug 签名。包名固定 `com.zhiwei.qbot`，不得更改。推 `v*` 标签由 GitHub Actions 构建并发布。JDK 17 / compileSdk 35 / minSdk 24。

**边界**：同一条消息被动回复最多 5 次，超过后自动改发主动消息继续（不再中断、不再虚报失败）；群聊要 @ 机器人；按钮消息必须走 markdown，且自定义按钮是开放平台「内邀」能力、模板按钮需申请，没开通时自动退回纯文字菜单；不是外挂，不做账号登录，不绕过平台限制；模型说的话由模型负责，先自己测几句。

**许可**：MIT。QQ 接口的使用需遵守《QQ 开放平台开发者服务协议》。

## English

Bring your own model to QQ's official bot API. Your persona — with real emotions — split replies, button menus, stickers, voice, any file and whole directories. It remembers the people it has talked to, in groups and in DMs alike. Official endpoints only: no account password, no third‑party gateway, everything stays on the device.

**What it is**: one phone is one bot. A `specialUse` foreground service keeps a WebSocket to the QQ gateway; every message goes through command → persona + model → splitting → sending.

**Features**

- Persona: name, identity, traits, speaking style, bans, greeting, extra notes — all injected into the system prompt, with a one‑tap preview. The default is "Zhiwei": a capable, cute assistant who knows when to play coy, argues back, and gets genuinely annoyed.
- Long‑term memory: stored per person, shared by groups and DMs — someone you have chatted with in private is still recognized when @‑mentioned in a group. Names and preferences are learned automatically; `/remember` adds entries manually.
- Human‑like sending: split by punctuation, random gaps, typing delay scaled by length.
- Button menus: buttons under the message; each opens a submenu, runs a `/command`, or sends text to the model.
- Stickers and voice: the model writes `[sticker:name]` or `[voice]text`; you add stickers and a TTS endpoint.
- Any file: `/file <path>` for admins, `/file https://…/a.zip` for public links, or `[file:/sdcard/x.pdf]` from the model. Images, video and silk voice go out as their own types; everything else (pdf, zip, apk, docx, logs…) goes out as a plain file.
- Whole directories: `[dir:/sdcard/Download/stuff]` from the model, or `/file /sdcard/Download/stuff` for admins — files go out one by one with a summary of what succeeded.
- Status: `/status` reports exact info — platform, model, OS, battery, memory, storage, uptime, route, counters and memory stats.
- Keep‑alive help: notifications, battery whitelist, autostart, background pop‑up, all‑files access — status check plus a jump to the right settings page.
- Two‑process keep‑alive: an independent `:guard` process reads a `bot.alive` heartbeat file written by the main service instead of calling `getRunningServices` (which on Android 8+ only reports the caller's own process — cross‑process it always looks dead and would restart the bot in an endless loop), and only revives it when it is really gone. The main service's own watchdog dies with it, so a second process is the only thing that can bring it back.
- Custom shortcuts: trigger word, description, action and argument are all editable in‑app, with enable/disable. Ships with `q`/`w`/`e`/`s`/`目录`/`备份`/`状态`; the same table is injected into the system prompt, so the model knows the shortcuts exist and won't treat "run q for me" as small talk.
- Per‑purpose models: plain chat, vision, voice script, and image generation each take their own BaseURL / key / model. Leave a slot empty and it falls back to the main model — fill only what you actually need. When someone sends a picture, the request switches to the vision slot and the image is mixed into the payload as an `image_url` part; every slot has its own connectivity test, and the image slot actually draws one picture to prove it works.
- Drawing and speech: `[draw:description]` in a reply, or `/draw description`, generates and sends a picture on the spot; `[voice]text` is first rewritten into plain spoken language by the voice‑script model (Markdown and emoji stripped) before TTS turns it into silk, so it no longer reads out asterisks and tildes.
- Backup & restore: config, memory, full conversation history and original sticker files packed into one zip under `/sdcard/ZhiweiQBot`; restore with one tap on a new device, or import from any path.
- Listing is not sending: asking to "look at / list a folder" returns a compact listing (name, size, time) instead of pushing the files themselves; colloquial paths like "internal storage" or "downloads" map to the real directories.

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
| `/菜单` | show the button menu | everyone |
| `/状态` | platform, device state, route, counters, memory stats | everyone |
| `/记忆` | show what the bot remembers about you | everyone |
| `/记住 文本` | store one entry (up to 3) | everyone |
| `/忘记` | wipe everything the bot has stored about you | everyone |
| `/记忆库` | memory overview | admins |
| `/表情 名字` | send that sticker | everyone |
| `/分句开` `/分句关` | toggle split sending | everyone |
| `/清除` | clear this conversation's memory (long‑term memory untouched) | everyone |
| `/语音 文本` | speak a line | admins |
| `/文件 路径、目录或链接` | send a local file, a whole directory, or a public file | admins |
| `/重连` | rebuild the connection | admins |

Admins go in “Connection → admin openid”, comma separated; empty means everyone.

**Long-term memory**

- Stored per person, not per conversation: groups and DMs share one profile, so someone you have chatted with in private is still recognized when @‑mentioned in a group.
- Automatic: phrases like "I am called / I like / I hate" are stored on the fly; when the model wants to remember something it writes a standalone `[memory:text]`, which is stripped from the reply and stored before sending.
- Manual: `/remember you like cats, hate overtime` (up to 3 entries); `/memory` lists everything it has stored about you; `/forget` wipes your profile.
- Injection: every reply gets the speaker's name, how many messages you have exchanged, and the most relevant memories ranked by keyword match plus recency. The toggle sits in "Settings → Persona".
- Privacy: no passwords, phone numbers or ID numbers are ever stored. Memory lives in local `filesDir/memory.json`, persisted on crash and on background; the in-app Memory page lets you browse and delete entries.

**Files in practice**

- Local: `/file /sdcard/Download/report.pdf`.
- Whole directory: `/file /sdcard/Download/stuff` — ordinary files inside go out one by one, with a summary of what succeeded.
- Public: `/file https://example.com/a.zip` — the platform fetches it, so big files don't touch phone memory.
- Let the model decide: `[file:/sdcard/x.pdf]`, path must exist, one file per reply; for a whole directory use `[dir:/sdcard/Download/stuff]`.
- Routing: image `file_type=1` (scaled to 1280px, gif/webp keep animation), video `2`, silk voice `3`, everything else `4` with a file name; apk goes out as a plain file via `file_type=4`.
- Three settings: allow plain files, allow non‑admins, per‑file size limit (10 MB default).
- Whether `file_type=4` is accepted depends on the capability being enabled for your bot; a rejection is logged with the platform's own error code instead of pretending it was sent.

**Stability**

- Connection: heartbeat follows the server interval; op11 and any downstream frame count as an ACK; a watchdog checks every 20s and reconnects when data stops.
- Reconnects: exponential backoff 1/2/4/8/16/30s with jitter; callbacks from an old socket can't kill the new one; backup gateway after repeated failures; `op9` refreshes the token.
- API calls: 401/403 and auth error codes trigger a token refresh and one retry; network errors and 5xx are retried with backoff; sends keep a minimum gap to avoid rate limits.
- Handling: one conversation at a time, at most 4 model calls at once; a failed model call is retried 3 times before a short apology.
- Safety nets: the foreground service re‑arms the gateway every 45s, the notification has “Reconnect” and “Stop”, crashes persist config and log the reason first, config is debounced 500ms and flushed on background, logs capped at 300 lines.
- Surviving Doze: a CPU wake lock alone keeps the CPU awake but not the Wi‑Fi, so a `WIFI_MODE_FULL_HIGH_PERF` Wi‑Fi lock is held alongside it; coroutine `delay` freezes with the process, so `AlarmManager.setAndAllowWhileIdle` wakes it every 15 minutes to self‑check; a `ConnectivityManager` callback reconnects the instant the network returns instead of waiting out the 30s backoff; `onTaskRemoved` revives the service when the card is swiped away. The `:guard` process reads a `bot.alive` heartbeat file instead of `getRunningServices`, which on Android 8+ only reports the caller's own process and would otherwise restart the bot in an endless loop.

**Build**: `./gradlew assembleDebug` or `assembleRelease`. Signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; **the build fails outright when no release keystore is present** — a differently signed package can't be installed over the old one, forcing users to uninstall and lose data, so falling back to the debug key is never allowed. The package name is fixed at `com.zhiwei.qbot`. Pushing a `v*` tag builds and publishes via GitHub Actions. JDK 17 / compileSdk 35 / minSdk 24.

**Limits**: at most 5 passive replies per message — beyond that sends switch to active messages automatically (no more truncation or phantom failures); groups need an @mention; button messages must be markdown, custom buttons are an invite‑only capability and template buttons need approval — without them the menu falls back to plain text; this is not a plugin hack, it does no account login and bypasses nothing; whatever the model says is on the model — test it first.

**License**: MIT. Using the QQ API means following the QQ Open Platform developer agreement.

始源创想科技IT · https://github.com/mingxiozsg-star/zhiwei-qbot