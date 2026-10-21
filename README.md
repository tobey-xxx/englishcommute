# EnglishCommute · 通勤英语

一个**为通勤场景**做的英语听力 App：自动抓取十余家播客源的当日节目，在服务器端转写成字幕并翻译，
手机上离线听、逐句看、生词入本、按 FSRS 复习，每天打卡。

Android（Kotlin + Jetpack Compose + Media3） + 自托管服务端（FastAPI + SQLite + faster-whisper）。

> ⚠️ 这是个人项目的开源化版本，**不含任何真实部署信息**：域名、服务器地址、账号、密码全部是占位符，
> 服务器地址从构建参数读取。详见 [配置自己的服务器](#配置自己的服务器)。

---

## 它解决什么问题

通勤 20 分钟想练英语，遇到的都是同一个循环：找音频 → 没字幕 → 听不懂的词查完就忘。
这个 App 把这条链收紧成三步：**打开就有今天的内容** → **边听边看字幕** → **生词自动进复习队列**。

## 功能

- **自动内容源**：16 个英文播客源（NPR 系、BBC、CBC、WSJ、Al Jazeera、TED 等），服务端每 30 分钟抓一轮
- **字幕与译文**：服务端用 faster-whisper 转写 + 机器翻译，客户端只负责显示（端侧不跑模型，APK 18 MB）
- **逐句播放**：字幕点一句播一句，可循环、可跳到下一句
- **生词本**：长按字幕选词 → 自动查词形、加语境句 → FSRS-6 间隔复习（正面认词，背面例句 + 读音 + 原声）
- **每日提醒**：本地闹钟每晚 23:30 提醒，今天已达标就不打扰（不依赖推送服务）
- **打卡与连续天数**：按墙钟累计收听时长，日历 / 连续天数 / 月度海报
- **多设备同步（可选）**：账号登录后打卡与生词本跨设备同步；不登录完整可用
- **零广告、零埋点**：没有统计 SDK、没有第三方网络请求（除内容源本身）

## 架构一图

```
播客 RSS (16 源)
      │  服务端每 30 分钟一轮（cycle.py）
      ▼
┌─────────────────────────────────────────┐
│ server/  FastAPI + SQLite                │
│  · 抓 RSS → 下载音频 → 转写 → 翻译         │
│  · 音频 / 字幕 / 封面 全部**自托管**       │
│  · 发布 index.json（客户端唯一内容入口）   │
└─────────────────────────────────────────┘
      │  HTTPS：index.json / audio / subs / art
      ▼
┌─────────────────────────────────────────┐
│ app/  Kotlin + Compose + Media3          │
│  · 内容层：目录 → 本地索引 → 懒加载下载     │
│  · 播放层：Media3 + 逐句字幕同步           │
│  · 数据层：生词本(FSRS) / 打卡 / 同步      │
└─────────────────────────────────────────┘
```

设计取舍、踩过的坑与工程约定见 [`docs/`](docs/)：

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层、内容流水线、同步模型、内容策略 |
| [docs/界面约定.md](docs/界面约定.md) | 视觉语言：色板、字号、间距、动效 |
| [docs/工程约定-构建与验证.md](docs/工程约定-构建与验证.md) | 断言怎么设计、为什么「永远为真的断言比没有更糟」 |
| [docs/工程约定-数据与后台.md](docs/工程约定-数据与后台.md) | 下载策略、保留期、通知、并发 |
| [docs/推送与每日提醒-技术调研.md](docs/推送与每日提醒-技术调研.md) | 小米/OPPO/vivo/荣耀推送的接口事实，以及为什么最后用本地闹钟 |

## 快速开始

### 只跑 App（不接服务器）

App 在拿不到服务器目录时会**退回本机解析 RSS**，所以不部署服务端也能用（只是没有字幕与中文）：

```bash
./gradlew :app:assembleDebug
```

### 配置自己的服务器

打包时把服务器地址传进去（**不要**改源码里的字符串）：

```bash
# gradle.properties 里写一行，或命令行传：
./gradlew :app:assembleDebug -PserverBaseUrl=https://your-domain.example
```

服务端（Debian/Ubuntu，2 核 2G 起步）：

```bash
# 依赖：python3.11+ / ffmpeg / nginx / (可选) certbot
cp -r server /opt/ec
python3 -m venv /opt/ec/venv && /opt/ec/venv/bin/pip install -r server/api/requirements.txt
/opt/ec/venv/bin/python server/api/init_db.py          # 建表
/opt/ec/venv/bin/python server/bin/cycle.py --dry-run  # 先看会抓什么
```

完整部署步骤（nginx、systemd、证书、备份）见 [`server/README.md`](server/README.md)。
必需的环境变量：

| 变量 | 说明 |
| --- | --- |
| `EC_PUBLIC_BASE` | 对外地址，如 `https://your-domain.example`（音频/封面/字幕的绝对地址由它拼） |
| `EC_FALLBACK_BASE` | 可选的 IP 应急更新通道；留空即关闭 |
| `EC_DB` | SQLite 路径，默认 `/opt/ec/data/ec.db` |

> 两个必须知道的约束（都写在 `server/README.md` 里）：
> ① **`--workers 1`**：登录限速是进程内状态，多开会变成「上限 × N」且不报错；
> ② 若保留了 IP 应急通道，它必须与 `publish_update.py` 的 `FALLBACK_BASE_URL` **一致**。

## 仓库结构

```
app/                 Android 客户端（Kotlin / Compose / Media3）
  src/main/java/…    content（内容与索引）/ player（播放与字幕）/ word（生词本+FSRS）
                     checkin（打卡与目标时间轴）/ sync（账号与多设备同步）/ notify（每日提醒）
server/              自托管服务端（FastAPI + SQLite + 转写/翻译流水线）
  api/               HTTP 接口（账号、同步、清单）
  bin/               定时任务：cycle.py（抓取+转写+发布）、发布脚本、管理命令
  nginx/             反代与静态资源（模板）
tools/               构建期断言与验证脚本（见 docs/工程约定-构建与验证.md）
docs/                设计与工程约定
```

## 数据与隐私

- 收听记录、生词、打卡**全部存在本机**（`SharedPreferences` / 文件），不登录不上传任何东西。
- 账号是**可选增强**：登录只影响「打卡与生词本跨设备同步」。
- 服务端不采集设备标识；同步只上传用户自己产生的打卡秒数与生词卡。
- 没有广告、没有分析 SDK、没有第三方埋点。

## 已知取舍

- 多设备同时听同一条内容时，打卡时长会各记各的（服务端按设备求和）——不会丢，但会重复计。
- 生词本的例句在两端可能不同：语境并集**保留本机顺序**，避免重排把用户看惯的那句换掉。
- 服务端是单点：它挂掉时 App 退回本机 RSS，能听但没有字幕/译文。

## License

[MIT](LICENSE)。第三方内容（播客音频、封面、字幕原文）版权归各来源所有，**仅供个人学习**；
部署给别人用之前请自行确认各源的条款。
