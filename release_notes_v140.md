## v1.4.0

### 中文

这一版只做两件事：把「识图」接对，把「挂后台掉线」修掉。

**识图模型（原来的错误槽位已纠正）**
- 模型分配四栏正式定为：普通对话 / 识图 / 语音稿 / 画图。旧版把第二栏当成「复杂推理」，是错的，现在改名为识图，旧的配置键自动兼容迁移，不用重填。
- 别人在群里或私聊发图片，自动切到识图栏，图片按 OpenAI 规范的 `image_url` 混排进最后一条消息，最多取 4 张。

**画图与语音**
- 回复里写 `[画图:描述]`，或直接发 `/画图 描述`，当场画一张发出来。支持 b64_json 与 url 两种返回。
- `[语音]文本` 先经语音栏模型改写为纯口语稿（去掉 Markdown、表情符号、星号波浪号），再交 TTS 合成 silk，念出来不再像在读代码。
- 模型分配页四栏都能单独测通，画图栏点自测会真画一张验证端点是否可用。

**后台掉线：四个代码级真因，不是权限问题**
- 跨进程探活失效：守护进程原先用 `getRunningServices()` 判断主服务在不在，Android 8+ 它只返回自己进程的服务，跨进程恒判死亡，于是每 20 秒无脑重启主进程。现在改为读主服务写的 `bot.alive` 心跳文件，超过 2 分钟才算死。
- 息屏掐网络：原先只申请 `PARTIAL_WAKE_LOCK`，只防 CPU 休眠，管不住 WiFi 进省电模式。现在同时持有 `WIFI_MODE_FULL_HIGH_PERF` 的 WiFi 锁。
- 协程被冻结：Doze 下 `delay` 会一起冻结，看门狗形同虚设。现在用 `AlarmManager.setAndAllowWhileIdle` 每 15 分钟穿透 Doze 唤醒自检。
- 网络恢复盲等：断线退避最多 30 秒，Doze 下可能拖到几分钟。现在注册 `ConnectivityManager` 回调，网络一恢复立刻绕过退避重连。
- 顺带：最近任务划掉卡片会在 `onTaskRemoved` 里自动复活；通知栏「重连」改为原地踢连接，不再重建网关丢会话。

**下载**
- 正式包由 GitHub Actions 构建，固定签名，可直接覆盖安装 1.3.0。

**官方群**
- QQ 群 1131325287

### English

Two things only: getting vision right, and killing the background disconnects.

**Vision**
- The four model slots are now plain chat / vision / voice script / image generation. The second slot used to be mislabelled "complex reasoning" — that was wrong. It is vision now, and old config keys migrate automatically.
- Pictures sent in groups or DMs switch the request to the vision slot; up to 4 images are mixed into the last message as `image_url` parts, per the OpenAI spec.

**Drawing and speech**
- `[draw:description]` in a reply, or `/draw description`, generates and sends an image immediately. Both `b64_json` and URL responses are supported.
- `[voice]text` is first rewritten into plain spoken language by the voice-script model (Markdown, emoji, asterisks and tildes stripped) before TTS renders it to silk, so it no longer sounds like it is reading code aloud.
- Each slot has its own connectivity test; the image slot actually draws one picture to prove the endpoint works.

**Background disconnects: four real bugs, not a permissions problem**
- Cross-process liveness was broken: the guard process used `getRunningServices()`, which since Android 8 only reports the caller's own process — across processes the bot always looked dead, so it restarted the main process every 20 seconds. It now reads a `bot.alive` heartbeat file and only treats the service as dead after 2 minutes.
- Screen-off killed the network: only `PARTIAL_WAKE_LOCK` was held, which keeps the CPU awake but not the Wi-Fi. A `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi lock is now held alongside it.
- Coroutines froze: under Doze, `delay` freezes with the process and the watchdog silently stops. `AlarmManager.setAndAllowWhileIdle` now wakes the process every 15 minutes to self-check.
- Blind waiting after outages: reconnect backoff reached 30 seconds, and Doze could stretch it to minutes. A `ConnectivityManager` callback now reconnects the instant the network returns, bypassing backoff.
- Also: swiping the app card away revives the service via `onTaskRemoved`, and the notification "Reconnect" now resets the connection in place instead of rebuilding the gateway and losing the session.

**Download**
- Release builds come from GitHub Actions with a fixed signing key, so this installs straight over 1.3.0.

**Community**
- QQ group 1131325287
