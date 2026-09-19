# Back on Track

语音进，结构化出。想到什么直接说，AI 负责归类、提取、提醒。

- 需求：[Back-on-Track-PRD.md](./Back-on-Track-PRD.md)
- 计划：[PLAN.md](./PLAN.md) —— 平台决策、里程碑、验收标准

**当前状态**：M0。服务端已就绪，客户端待 Android Studio 安装后开工。

## 目录

```
supabase/
  migrations/0001_init.sql          数据库 schema + RLS
  functions/
    process-capture/index.ts        入口：音频 → ASR → Claude → 落库 → 排提醒
    _shared/analysis.ts             一次调用完成纠错+归类+提取（prompt 在这）
    _shared/asr.ts                  可切换的 ASR provider，M1 横评用
    _shared/reminders.ts            递进式提醒档位计算
```

## 启动

第一次搭环境看 **[SETUP.md](./SETUP.md)** —— 每一步是干嘛的、坑在哪，都写清楚了。

已经搭好、只是想重来一遍的快速版：

```bash
brew install --cask android-studio && brew install supabase/tap/supabase
```

建表：把 `supabase/migrations/0001_init.sql` 粘到 Supabase 网页后台的 SQL Editor 里跑一遍。
建桶：Storage 里建一个叫 `captures` 的 **private** bucket。

```bash
supabase login && supabase link --project-ref <project-ref>
```

```bash
supabase secrets set ANTHROPIC_API_KEY=sk-ant-xxx ASR_PROVIDER=openai ASR_API_KEY=sk-xxx
```

```bash
supabase functions deploy process-capture
```

`ASR_PROVIDER` 可选 `openai` / `elevenlabs` / `deepgram`——M1 要拿真实的中英混说语音把三家跑一遍再定。

## 设计上不能动的几条

- **`captures` 表的原始音频和转录永不删除。** 所有下游表回指 `capture_id`，随时能回到 Raw（PRD 3.2）。
- **归类置信度 < 0.6 一律进 inbox。** 阈值卡在代码里而不是 prompt 里——分类错了比不分类更伤信任。
- **`tasks` 与 `kind` 解耦。** 一条 idea 里也可能顺带提到要做的事，两者不是一回事。
- **纠错不改写措辞。** 只去识别错误和口癖，观点和说法是用户自己的。
