# Back on Track — 实施计划（Android 优先）

## Context

用户长期受注意力分散困扰，同时有主业 + 多个副业 + AI 创业/自媒体，零散信息（待办、灵感、选题、观点）无处安放。PRD（`Back on Track/Back-on-Track-PRD.md`）以「语音快速记录」为唯一入口，AI 自动归类分发到 6 个模块：AI To-do、Content Creator、Idea Memo、饮食记录、成就记录、人格记录。形态为移动端 + Chrome 插件双端协同。

**已确认的前提**（决定了下面所有选型）：

- 用户 iPhone 和 Android 都在日常使用，设备不构成约束
- **只考虑美国设备**（Pixel / 美版 Samsung，完整 GMS，无国产 ROM 的后台管控问题）
- **纯自用，暂不发布**，后期有人想要才考虑发布，概率小
- 「想到了记不下来」和「记下来了但不做」两个痛点**一样痛**

当前状态：项目目录只有 PRD，尚无代码。本机 Node 24、Command Line Tools，未装 Xcode（Android 路线不需要）。

---

## 平台决策：先做 Android

在「美版设备 + 纯自用 + 不发布」这三个前提下，Android 明确占优。

| | Android | iOS |
|---|---|---|
| 成本 | **$0**，自签名**永不过期**，`adb install` 秒装 | **$99/年**（免费签名 7 天过期，对日常工具是硬伤；TestFlight 也要这个会员且 build 90 天过期） |
| 全局录音入口 | **悬浮球**（`SYSTEM_ALERT_WINDOW`）：浮在小红书/TikTok 之上，按住即说，**不切走当前 App** | 系统禁止第三方在其他 App 之上绘制。最接近的是 Action Button，但会把你切走 |
| 提醒可靠性 | 需处理 Doze + 电池优化白名单 —— **一次性工程量**，自用场景设置一次即可 | 系统级本地通知，不用跟系统斗 |
| 迭代循环 | 改完直接装 | 重签名/重装摩擦 |

**关键判断**：iOS 的代价是**持续的**（年费 + 每次迭代摩擦），Android 的代价是**一次性的**（Doze 处理）。而悬浮球是 iOS 永远拿不到的牌，它直接解决用户两个痛点中的前一半——"回过神的那一秒不用退出当前 App"。

**两条被推翻的旧论据**（记录在此以免反复）：
1. "国产 ROM 杀提醒" —— 只考虑美版设备，不成立。
2. "iOS 端上实时转录更好" —— 用户是中英混说，而 `SFSpeechRecognizer` 和 Android `SpeechRecognizer` 都按单一 locale 工作，混说两边都不灵。真正扛混说的是服务端 multilingual 模型（Whisper 系）。此条对两个平台同等不利，应从对比中剔除。

**小组件不能直接录音（两个平台都是）**：Android 的 `RemoteViews` 和 iOS 的 WidgetKit 都拿不到麦克风，点击只能启动 Activity/Intent。Android 的解法是绕过小组件走悬浮球，iOS 没有这个绕法。

**iOS 什么时候做**：M2 自用两周、产品形态稳定之后再评估。服务端逻辑届时已沉淀好，成本是重写一套 SwiftUI，不是重写一切。**现在不上 KMP** —— 一个人做一端时抽象成本大于收益。

---

## 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 移动端 | **Android 原生**（Kotlin + Jetpack Compose，minSdk 31 / targetSdk 36） | 只适配自己的机器，可以直接用最新 API，不背历史包袱 |
| 全局入口 | **悬浮球**：`SYSTEM_ALERT_WINDOW` overlay + `ForegroundService(type=microphone)` | 核心入口，产品成败所系 |
| 辅助入口 | **Glance 小组件**（待办清单）+ **QuickSettings Tile**（下拉即录）+ Assistant 快捷 | PRD 功能一要求待办显示在小组件中 |
| 桌面端 | **Chrome MV3 扩展**（React + Vite + CRXJS），Side Panel + 全局快捷键 + Offscreen Document 录音 | PRD 明确桌面场景在 Chrome |
| 后端 | **Supabase**（Postgres + Auth + Storage + RLS + Edge Functions） | 音频存储、双端同步、鉴权一站式；Edge Function 兼作 AI 网关，密钥不落客户端 |
| ASR | **服务端 multilingual 模型**（Whisper 系 / gpt-4o-transcribe / ElevenLabs Scribe，M1 横评定选型） | 中英混说是硬需求，端上 locale 方案不可用 |
| LLM | **Claude `claude-opus-5`** + Structured Outputs（`output_config.format`） | 纠错 + 归类 + 字段提取一次调用返回 JSON；分类档用 `effort: "low"` 压成本 |
| 提醒 | `AlarmManager.setExactAndAllowWhileIdle()` + 引导加电池优化白名单 | 递进提醒是本地定时，不依赖服务器在线 |

**关于"实时转录"的降级方案**：端上草稿这一层在中英混说下不可靠，M1 先不做。改为松手立刻显示波形 + "转写中"占位，服务端往返控制在 2s 内。M4 之后如果确有必要，再评估端上模型。

---

## Android 特有的工程清单（容易漏，先列出来）

- `SYSTEM_ALERT_WINDOW` —— 必须跳系统设置页手动授予，写一次性引导
- `ForegroundService` 在 Android 14+ 必须声明 `foregroundServiceType="microphone"`，且要有常驻通知
- `POST_NOTIFICATIONS` —— Android 13+ 运行时权限
- `SCHEDULE_EXACT_ALARM` —— Android 12+；自用可申请 `USE_EXACT_ALARM`（免用户授权但发布时需理由）
- **电池优化白名单** —— `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，这是提醒不漏的关键，务必在 onboarding 里引导
- 开机重启后重建所有 alarm（`BOOT_COMPLETED` 广播）
- 自建 keystore 签名，APK 永不过期

---

## 数据模型（Postgres，全表 `user_id` + RLS）

```
captures        id, user_id, audio_path, duration_ms, draft_text, final_text,
                asr_status, kind, kind_confidence, created_at
                -- kind ∈ task|content|idea|note|project|meal|inbox

tasks           id, capture_id, title, due_at, due_is_fuzzy, priority,
                status, snooze_count, completed_at
reminders       id, task_id, fire_at, tier, delivered_at

ideas           id, capture_id, raw_text, organized jsonb, analysis jsonb,
                last_touched_at, dormant_notified_at
content_pieces  id, capture_id, clean_text, polished_text, script_text,
                titles text[], hooks text[], polish_level
notes           id, capture_id, body, tags text[]
projects        id, name, status
meals           id, capture_id, items jsonb, kcal_est, photo_path, logged_on
weights         id, logged_on, kg
persona_doc     id, markdown, version, excluded_topics text[]   -- 端上优先
```

要点：
- `captures` 是唯一入口表，**原始语音与转录永不删除**；下游表回指 `capture_id`，随时能回到 Raw（PRD 3.2 硬要求）。
- 一段语音可产生多条 task → 一对多，不要做成一对一。
- 虽是自用，从第一天带 `user_id`，将来发布不必重构。

---

## AI 管线

```
音频 ──► Storage ──► Edge Function ──► ASR ──► 定稿文本
                                        │
                                        ▼
                  Claude Opus 5（一次调用，structured output）
                  ├ 纠错后的文本
                  ├ kind + confidence（低置信度一律进 inbox，绝不硬猜）
                  ├ tasks[]  (title, due_at, priority, needs_reminder)
                  └ 各类型的结构化字段
```

- **纠错 + 分类 + 提取一次调用完成**，不拆成三次串行（延迟与成本都会翻倍）。
- 深度分析（Idea 可行性 / MVP / 技术栈）、月报、人格文档更新是独立异步调用，显式触发或定时跑，走 Batch API 省一半。
- 成本估算：每天 20 条语音、每条 ~1k in / 500 out，Opus 5 约 **$0.35/天（~$10/月）**；分类档降到 `effort: "low"` 还能再砍。ASR 约 $0.006/分钟，可忽略。

---

## 里程碑

| 阶段 | 目标 | 产出 | 预估 |
|---|---|---|---|
| **M0** 地基 | 环境齐活（**$0**） | Android Studio、Supabase 项目、Anthropic + ASR key、Compose 工程骨架 + keystore | 半天 |
| **M1** 打通链路 | 端到端跑通，**不做 AI 归类** | App 内按住录音 → 上传 → 服务端转录 → 会话列表（可复播 + 看文本）。同期用真实中英混说语音横评 3 家 ASR 定选型 | 3–5 天 |
| **M2** 核心闭环 ★ | 入口 + 整理两个痛点一起解 | **悬浮球**、AI 归类（含手动改类 + Inbox 兜底）、多待办提取、优先级、递进式提醒（含 Doze/白名单/重启恢复）、Glance 小组件、QS Tile | 2–3 周 |
| **M3** 桌面端 | Chrome 扩展 + 双端同步 | 快捷键呼出录音、Side Panel 看清单、Realtime 同步 | 4–6 天 |
| **M4** 创作模块 | Idea Memo + Content Creator | Raw / Organized 双视图、深度分析、口语清洗三档、标题与钩子生成。**注意：日常用的 gpt-4o-transcribe 会自动删掉口语重复，「轻度清洗」这档需要拿原始音频用 whisper-1 重转一份忠实文本，否则这档名不副实** | 1–2 周 |
| **M5** 长尾 | 饮食 / 成就 / 人格 | 定时提醒、拍照识别、月度 Recap、人格 Markdown + 隐私开关 | 1–2 周 |

★ **M2 结束就应该能日常使用。** 到那一步先自己用两周，再决定 M4/M5 怎么改 —— 大概率会改。

**MVP 收敛**：PRD 的 6 个模块并行开工，最可能的结局是六个半成品。MVP 只做 **Capture + To-do + Idea Memo**，M2 之前不碰饮食 / 成就 / 人格。

---

## 风险清单

| 风险 | 应对 |
|---|---|
| AI 归类不准，失去信任 | 置信度低进 Inbox；每条一键改类；改类记录喂回 prompt 做少样本 |
| 提醒被 Doze 延迟或漏掉 | `setExactAndAllowWhileIdle` + onboarding 引导加白名单 + `BOOT_COMPLETED` 重建 alarm；M2 必须做定时回归验证 |
| 悬浮球常驻通知烦人 / 耗电 | 常驻通知设最低优先级；空闲时不持麦，只在按下瞬间开 recorder |
| 中英混说转录准确率 | M1 用真实语音横评 3 家 ASR 再定，别先写代码后测 |
| 通知太吵被关掉 | 递进提醒设每日上限；「推迟多次」的追问一周最多一次 |
| 隐私（功能六人格记录） | 人格文档端上优先，默认不上传；服务端无状态推理不留存原文；一键清空；分模块开关 |

---

## 验收方式

- **M1**：真机按住说一句中英混说的话，松手立刻见波形 + "转写中"，2 秒内出定稿文本；杀掉 App 重开记录还在，音频能复播。
- **M2**：
  - 打开任意第三方 App（如小红书），悬浮球在最上层，按住直接说，**全程不离开该 App**
  - 说「下周之前把供应商的事情处理掉，另外提醒我周五交房租」→ 得到 2 条待办，deadline 分别正确解析
  - 把系统时间调到 deadline 前一周，验证 4 档递进提醒依次到达
  - **杀掉 App + 重启手机**后提醒仍准时触发（这条最容易翻车，必须单独验）
- **M3**：Chrome 里用快捷键录一条，手机上 5 秒内出现。
- **成本**：跑满一周后查 Anthropic Console 用量，确认在 $10/月量级。

---

## 第一步

1. 装 Android Studio（$0，无阻塞项）
2. 建 Supabase 项目 + 备好 Anthropic API Key
3. 选一款 ASR 服务开通
4. 开 M1
