# 环境搭建（M0）

三件事：装开发工具、开后端、开语音转文字服务。全程大概 1–2 小时，大头是等下载。

**先说钱**：这一步全部免费。跑起来之后每月大约 $12–15（Claude 约 $10，语音转录约 $2–3），Supabase 自用规模用免费额度就够。

---

## 一、Android Studio

### 这是什么

写 Android App 用的软件，相当于写文章用的 Word。它不只是个编辑器，还捆绑了三样你必须有的东西：

- **JDK**：Android App 用 Kotlin 写，Kotlin 要靠 Java 环境才能跑。（你现在这台机器上没有，装完就有了）
- **Android SDK**：安卓系统提供的一整套功能库——录音、通知、悬浮窗这些都在里面
- **模拟器**：电脑上假装成一台安卓手机，方便调试

### 怎么装

```bash
brew install --cask android-studio
```

没有 brew 就去 https://developer.android.com/studio 下载。

装完**第一次打开**会弹一个 Setup Wizard，选 **Standard** 一路下一步。它会再下载几个 GB 的 SDK，慢慢等。

### 装完还要做一步：让电脑能连你的手机

我们是自用，最终 App 要装到你自己手机上，所以要打开手机的调试开关：

1. 手机 **设置 → 关于手机**
2. 找到 **版本号**（Build number），**连续点 7 次**，会提示"你已成为开发者"
3. 回到 **设置 → 系统 → 开发者选项**，打开 **USB 调试**
4. 用数据线连电脑，手机上会弹一个"允许 USB 调试吗"，勾上"一律允许"

验证一下（先在 Android Studio 里打开过一次项目，`adb` 才会在路径里）：

```bash
~/Library/Android/sdk/platform-tools/adb devices
```

看到你的手机型号或一串编号就成了。

---

## 二、Supabase

### 这是什么

**一个现成的后端。**

你的 App 需要几样东西：一个地方存待办和灵感、一个地方存音频文件、一套"知道你是谁"的登录系统、还要能跑一小段服务器代码（就是我们写的 `process-capture`）。自己搭这套要租服务器、装数据库、配运维，是个大工程。Supabase 把这些打包好了，注册个账号点几下就有。

它由几部分组成，用大白话说：

| Supabase 里的叫法 | 大白话 |
|---|---|
| Database | 一堆能互相关联、能被程序查询的表格（可以想成高级版 Excel） |
| Storage | 网盘，我们用来放录音文件 |
| Auth | 登录系统，管"你是谁" |
| Edge Functions | 你自己写的一小段代码，跑在云上而不是手机上 |
| RLS | 每行数据贴一个"这是谁的"标签，保证你只能看到自己的东西 |

### 怎么开

**1. 注册建项目**

去 https://supabase.com，用 GitHub 登录最快。点 **New project**：

- Name：随便，比如 `back-on-track`
- **Database Password：设一个并且存到密码管理器里。** 后面要用，丢了很麻烦
- Region：选 **West US** 或离你近的

点创建，等 2 分钟。

**2. 记下三个值**

在项目页面 **Settings → API** 里：

- **Project URL**，长这样 `https://abcdefgh.supabase.co`
- **anon public key**，一长串以 `eyJ` 开头的字符
- **project ref**，就是 URL 里 `https://` 和 `.supabase.co` 中间那串（上例是 `abcdefgh`）

**3. 建表**

最省事的办法是**不用命令行**，直接在网页上跑：

1. 左边栏 **SQL Editor** → **New query**
2. 把 `supabase/migrations/0001_init.sql` 整个文件的内容复制粘贴进去
3. 点 **Run**

看到 "Success. No rows returned" 就是成了。左边栏 **Table Editor** 里应该能看到 `captures`、`tasks` 这些表。

**4. 建放音频的地方**

左边栏 **Storage** → **New bucket**：

- 名字必须叫 **`captures`**（代码里写死的）
- **不要勾 Public bucket** —— 你的录音不该谁都能听

**5. 装命令行工具**

这一步只有部署 Edge Function 才需要（网页上做不了）。

```bash
brew install supabase/tap/supabase
```

然后依次跑（`<project-ref>` 换成第 2 步记下的那串）：

```bash
supabase login
```

```bash
supabase link --project-ref <project-ref>
```

会问你数据库密码，就是第 1 步设的那个。

> 如果 `deploy` 那步报 Docker 相关的错，告诉我——有绕过的办法（Supabase 网页后台也能直接粘贴函数代码）。

---

## 三、语音转文字（ASR）

### 这是什么

ASR = Automatic Speech Recognition，自动语音识别。**你把音频文件发给它，它把文字发回来**，按分钟收费。

我们的 App 里，你按住说的那段话就是发给它转成文字，然后才轮到 Claude 去理解和归类。

### 开哪家

M1 阶段要拿你的真实语音（中英混说）横评几家再定，所以**先开两家**：

**OpenAI**（https://platform.openai.com）
- 如果你已经有账号最省事
- ⚠️ **注意：API 账号和 ChatGPT Plus 订阅是两回事。** 订了 Plus 不等于有 API 额度，要单独在 Billing 里充值（充 $5 够跑很久）
- API keys → Create new secret key，拿到 `sk-...` 开头的串

**Deepgram**（https://deepgram.com）
- 主打多语言，中英混说这块值得试
- 注册就送免费额度，够 M1 横评用
- Dashboard 里创建 API key

### 顺便：Anthropic API Key

Claude 那部分也要 key，去 https://console.anthropic.com。

⚠️ **同样的坑：Claude Pro 订阅 ≠ API 额度**，要在 Billing 里单独充。

---

## 四、把 key 交给 Supabase

三个 key 都拿到后，一条命令交给后端（**换成你自己的值**）：

```bash
supabase secrets set ANTHROPIC_API_KEY=sk-ant-xxx ASR_PROVIDER=openai ASR_API_KEY=sk-xxx
```

然后部署：

```bash
supabase functions deploy process-capture
```

### 关于 key 的安全

这些 key 等于你账号的密码，**谁拿到谁就能花你的钱**。三条规矩：

1. **只放在 Supabase secrets 里**，绝不写进 App 代码
2. **绝不提交进 Git 仓库**（这是最常见的泄露方式，GitHub 上有机器人专门扫）
3. 不小心贴到聊天记录或截图里了，就去后台把那个 key 删掉重建，成本很低

---

## 做完了告诉我

三件事齐了就跟我说，我来验证连通性，然后开 M1：录音 → 上传 → 转文字 → 列表。
