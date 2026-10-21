# EnglishCommute 2.0 · 服务端

端侧转云端的 CS 架构。App 仍负责播放、字幕显示、打卡；**转写与翻译搬到服务器**，
产出静态字幕由 nginx 直发。评估见上一级的 `2.0-端侧转云端-技术复杂度与成本评估.html`。

服务器：`ssh YOUR_HOST`（腾讯云轻量 · 中国香港 · Debian 12 · 2核2G · AMD EPYC 9754）
实测证据在上一级的 `2.0-服务器实测/`。

## 这一份目录是什么

**服务器上真正跑的那套代码的版本化镜像**，外加一键部署脚本。
`bin/` 里的内容与 `/opt/ec/bin/` 一致（`./deploy.sh` 保证这一点）。

> ⚠️ 改代码请改**这里**，然后 `./deploy.sh`。
> 直接在服务器上改会让两边分叉，以后查不出「为什么线上行为不一样」。

## 管线

```
bin/cycle.py             编排：RSS → 选新条目 → 下载 → 转写 → 翻译 → 发布 → 删音频
bin/chunked_transcribe.py  ASR（静音对齐切块）※ 家在 skill 里，deploy 时取过来
bin/translate_batch.py    中文字幕：按句合并 → 束搜索翻译 → 逐行对齐的 JSON
bin/translate_server.py   opus-mt-en-zh 的 ONNX 推理引擎（含 4 束搜索）
bin/transcribe.py         RSS 解析 / 下载 / 时间码（复用已实测的实现）
bin/feeds.txt             11 个源，与 App 的 FeedSource.kt 逐个对齐

bin/verify_zh.py          核验：译文与字幕逐条对齐（含坏样本自检）
bin/verify_publish.py     核验：发布清单与磁盘文件互相一致（含坏样本自检）
bin/eval_translate.py     译文质量对照（不同模型集 / 不同束数并排看）
bin/backfill_meta.py     一次性迁移：给已有条目补 guid / 封面
                         （2.0 客户端要 guid 才能算出与 1.0 一致的 id；
                          封面是这一轮才开始发布的）
bin/bench_beams.py        束搜索 vs 贪心的收益与代价

/opt/ec/
  models/translate         int8（App 那份，内存兜底）
  models/translate-fp32    服务端用这份 —— **又快又准**
  www/                     nginx 站点根：index.json + subs/*.{srt,words.json,zh.json}
  data/state/state.json    幂等记账（按 guid）
  data/audio/              音频临时落点 —— **下完即转即删**，正常情况下是空的
  logs/cycle.log           自限容 2 MB
```

## 三条组织上的纪律

1. **内存峰值靠「进程」而不是「释放」**。ASR（~830 MB）与翻译（~1050 MB）
   各自是独立子进程，**且串行** —— 同时在场会顶到 1.9 GB，而机器只有 1.93 GB。
   `onnxruntime` / `CTranslate2` 的内存池不会可靠归还，「进程退出」才是唯一彻底的归还方式。
2. **每个阶段都要能单独重跑，且幂等**。转写看 `.srt` 在不在，翻译看 `.zh.json`
   在不在且没有 `null`；`--translate-only` 可以只补翻译不动其它。
   `state.json` 只是账本，**判断依据始终是文件本身**。
3. **发布前必须核对文件真的存在**。`index.json` 广告了却 404，
   客户端会整集不可用 —— `verify_publish.py` 拦这一类。

## 常用命令

```bash
./deploy.sh                      # 同步 + 语法检查 + 自检 + 核验
ssh YOUR_HOST '/opt/ec/venv/bin/python /opt/ec/bin/cycle.py --translate-only'   # 只补/重翻中文
ssh YOUR_HOST '/opt/ec/venv/bin/python /opt/ec/bin/cycle.py --hours 24 --per-feed 1'
ssh YOUR_HOST 'systemctl start --no-block ec-cycle.service'   # ⚠️ 必须 --no-block，oneshot 会阻塞到跑完
ssh YOUR_HOST 'journalctl -u ec-cycle.service -n 30 --no-pager'
ssh YOUR_HOST 'systemctl list-timers ec-cycle.timer'
```

## 定时任务

`ec-cycle.timer` 每小时一轮（`RandomizedDelaySec=5min`，避免整点同时轰 11 个 CDN）。
`ec-cycle.service` 是 oneshot，带 **`MemoryHigh=1200M` / `MemoryMax=1500M`**：
超限只 kill 这一轮，不会把整机拖垮。

## 客户端的契约（写客户端时必须遵守）

`index.json` 每条里的 `zh` 指向 `subs/<id>.zh.json`，格式是
**长度严格等于 SRT 句段数**的 JSON 数组：

| 值 | 含义 | UI 应该怎么显示 |
|---|---|---|
| 中文字符串 | 这一行的译文 | 正常显示 |
| `""` | 这行不需要译文 | **不占位**（不留空行） |
| `null` | 翻过了但失败 | 显示占位下划线，将来可补 |

⚠️ **句子跨多行时，中文只挂在句子的第一行**，续行是 `""`。
理由是实测数据：13 份真实字幕里 65.3% 的句子只占 1 行，跨行的 34.7% ——
每行都挂的话那三分之一会在屏幕上把同一段中文重复 2–3 遍。
读起来是「英文前半 / 中文整句 / 英文后半」，干净且语义正确。

---

## 客户端契约（2.1.1 从 MEMORY.md 搬来，那边只留指针）

### 不变式：服务端的「窗口 / 粒度」必须 ⊇ 客户端的

三对，**改任何一边都要同时看另一边**：

| 服务端 | 客户端 | 破了会怎样 |
|---|---|---|
| 处理窗口 = `48h ∪ 每源最新一条` | 下载窗口 = 「最近 2 天 + **每源保底最新一条**」 | 「保底」无上界（周更源 7 天、停更源几个月），服务端只处理 48h → **手机上有音频、服务器永远不给字幕**。2.1.0 之前这条被端侧识别兜着，拆掉之后是**不可补救**的 |
| 字幕粒度 = 译文粒度（一句一条） | —— | 译文只挂句首，跨行的句子后半截**只有英文**。见 skill `podcast-transcribe-srt` |
| 内容变了就抬 `srtVer` | `remoteSrtVer > srtVerOnDisk` 才重下 | 客户端的判据是「本地有字幕就不再取」→ **重切分过的内容在手机上永远不更新**，表现是「中文全没了」 |

### 「没有服务器字幕」的两种原因必须分开说

判据是 `ServerCatalog.lastCatalogOk`（由**发请求那一层**记录 —— 调用方拿到的 `null`
同时代表「目录里没这条」与「拿不到目录」）。

- **目录可用、只是没轮到这一期** → 正常流程，不写红色错误位
- **服务器连不上** → 用户需要知道（且暂时无解）

### 中文比英文晚一轮

服务端先发布 `srt`、**下一轮**才发布 `zh`。客户端原来「有 srt 就不再取」→
**在中文发布前点开过的那一期永远只有英文**。
现在 `needsSubtitles` 把「有英文没中文」也算待办；
但 `remoteZh == null` 时**不算**缺 —— 否则「待补数」永远归不了零。

### 域名 + TLS（3.1.0）：`https://YOUR_DOMAIN`

服务器上了域名（Let's Encrypt 证书；香港免备案），客户端的 `ServerConfig.BASE_URL`
跟着换。**从这一版起内容全走 HTTPS。**

#### ★★ 但 80 端口那条 IP 通道不能关（一个先有鸡还是先有蛋的死结）

App 内更新的清单（`/apk/update.json`）在服务器上，而**已经装在手机里的 3.0.5
只会去 `http://YOUR_SERVER_IP` 取它**：

    域名生效 → 新版本才存在 → 但新版本要靠老版本去 IP 上把它拉下来

80 一关，所有老客户端**永远升不了级**，且没有补救手段（只能数据线装包）。
所以 nginx 的结构是**加法**而不是替换：

| 入口 | `server_name` | 服务谁 |
|---|---|---|
| `:80` default_server | `_` | **老客户端**（内容 + 更新清单）。原样不动 |
| `:80` | `YOUR_DOMAIN` | ACME 续期质询 + 301 跳 https |
| `:443` ssl | `YOUR_DOMAIN` | 新客户端。与 80 **共用同一份** location |

三处共用的 location 在 `nginx/ec-routes.conf` —— 抽出来就为了不让它们分叉。
分叉的症状是「某些资源 80 上拿得到、443 上 404」，而排查方向会完全指错
（第一反应是证书或防火墙，而不是「少写了一个 location」）。

#### 上域名必须**分两步**，不能一次 deploy 完

`bin/cycle.py` 的 `PUBLIC_BASE` 决定 `index.json` 里 `audioUrl` 的**绝对地址**。
它一生效，下一轮发布（**≤30 分钟**，定时器自己会跑）就把所有 audioUrl 换成域名。
那一刻如果 443 还没起来，表现是：

> **有字幕、有封面、点下去没声音** —— 而且不会自己恢复
> （index.json 一直那么写着，直到 443 起来）。

所以：

```bash
./deploy.sh --nginx-only            # ① 只装配置。此时证书还没签 → 域名入口自动不启用
ssh YOUR_HOST /opt/ec/bin/setup-tls.sh     # ② 签证书（ACME 走 80 default_server，无需 443）
./deploy.sh                         # ③ 证书在位 → 域名入口装上，bin/ 一起同步
```

⚠️ `deploy.sh` 看到证书不在时会**主动摘掉**域名那两个 server 块，让站点停在
「纯 IP 完全可用」的自洽状态，而不是「80 跳 443 但 443 起不来」的半个坏状态。

#### 回退到 IP

域名出问题时（解析异常、证书续期失败、换机器），把清单里的下载地址切回 IP：

```bash
ssh YOUR_HOST '/opt/ec/venv/bin/python /opt/ec/bin/publish_update.py \
    --retag-only --base-url http://YOUR_SERVER_IP'
```

`--retag-only` 只重写 `update.json` 的 url 前缀、**不重新发布 APK**（秒级，
不用再跨境传一次 18 MB）。它会从发布区那个 APK 现算一遍 sha256，而不是抄旧值。

⚠️ 这条通道能成立，靠的正是客户端 `network_security_config.xml` 里那条 IP 例外。
它是**应急更新通道**而不是内容通道，正常路径一次都不会走到 ——
但**不要删**：删掉之后域名一旦失效，3.1.0 用户就收不到任何更新了。
`tools/verify-server-20.py` 有断言守着它的形状（恰好一条、是裸 IP、
且与服务端 `FALLBACK_BASE_URL` 逐字一致）。

#### 顺带收口了 `/vocab/`

上域名之后，这个服务从「一个 IP 上的私人小站」变成了「公网可发现的主机名」。
`/vocab/` 下除 `add.json` 外还有 9 个文件（`vocab.base` / `examples.base` /
`*.published` 等，约 3.4 MB 服务端中间产物），它们原来落到 `try_files` 兜底上
—— **可被任何人直接下载**，只是一直没人去找。

现在 `location ^~ /vocab/ { return 404; }`：只放行设计上要公开的那一个。
（用 `return 404` 而不是 `deny all` —— 后者回 403，等于告诉扫描的人
「这里确实有东西」。）

#### 验证

```bash
python3 tools/verify-tls-30.py --compare testdata/tls-30-baseline.json   # 80 有没有被弄坏
python3 tools/verify-tls-30.py                                           # 全套（DNS/证书/443/收口）
```

`--compare` 拿的是**改造前采集的 80 端口基线**（19 条路径的状态码 + 三个响应头，
逐条对照），而且是**双向**的：写进预期变更表的东西没发生，也算失败
（否则「我以为改生效了」就是一句没人验证的话）。

### 自建服务用 `http://IP` 时 Android 会拒绝明文（2.0.0 的历史，3.1.0 已不再需要）

`targetSdk 28+` 默认禁止明文 → OkHttp 抛 `UnknownServiceException`、
**一个字节都发不出去**，而代码看着完全正常。
当时的正解：`res/xml/network_security_config.xml` 只对那一个 IP 放行 +
manifest 里**删掉** `usesCleartextTraffic`。

3.1.0 换了域名之后，内容路径不再需要这条例外（TLS + 系统 CA，
Let's Encrypt 的证书被直接信任），但**例外本身留着**——理由见上面「回退到 IP」。
⚠️ **IP 与放行规则必须同步改**（不一致时报错一模一样）。

### ★★ 前提被推翻：同一个音频 URL **不保证**是同一份音频

2026-09-22 用户报「News Outlets Protest Trump's Media Bans 这期从 **0:56** 起字幕与音频对不上」。
查明**不是**转写、也不是重切分的问题 —— 是这条前提本身不成立：

> 上一版这里写着「服务端转写的音频」= 「客户端播放的音频」。**这是错的。**

NPR / Simplecast 走 **AdsWizz 动态广告插入**（URL 里的 `awCollectionId` / `awEpisodeId` / `t=podcast`
就是签名）。同一 URL，**不同客户端特征 / 不同请求时刻 / 不同出口 IP 会拿到不同的拼接**：

| 请求方 | 大小 | 时长 |
|---|---|---|
| `curl/8.x`（服务端用的） | 12,638,283 B | **789.89 s** |
| `okhttp/4.12.0` | 12,637,865 B | — |
| `Dalvik/2.1.0 (Android 14; Pixel 8)` | **14,414,188 B** | **900.88 s** |

**同一集最多差 1.78 MB / 111 秒**；两条音频在 **第 56.0 秒**（第一个广告位）开始分叉，
相似度从 1.0000 掉到 −0.07，且**在任何时间偏移上都对不回来**（各广告位长度不同，误差累积）。
⚠️ **时间也是变量**：服务端 18:03 下到的那版有 Lester Holt 广告，同日 19:22 用同一个 curl UA
下到的版本 56.7 s 已直接是新闻正文 —— **固定 UA 也救不了**。

**推论（三条，都要记住）**：

1. **字幕错位这类 bug，先去量「两份音频是不是同一份」，别先怀疑转写。** 判据是
   `总时长` 与 `前 55 秒的逐窗互相关`，两者一比就定性（见 `音频托管-流量与带宽评估.md` 的复现命令）。
2. **想让字幕与音频永远一致，只能让它们是同一个文件** —— 即音频也由本服务托管/代理
   （同时也是「音频 URL 过没过期」这个老问题的解法）。评估与流量模型见
   **`../音频托管-流量与带宽评估.md`**。
3. `index.json` 的 `audioHosted` 目前是 `false`（客户端去 NPR 取）。**它是这个一致性断言的开关**：
   只要还是 `false`，就不能假设字幕与音频对齐。

### 成本与失败模式

- **成本与用户数几乎无关**：每集只转一次、全体共用；音频不上服务器
  （⚠️ 想让音频与字幕强一致就得改这一条，见上一节）
- **RSS 全量拉取是当前最大的流量项**：11 个源合计 **50 MB/轮**，定时器**每 15 分钟**一轮
  → **141 GB/月**（占套餐流量的绝大部分，且纯属浪费）。
  实测：simplecast 源的 `ETag` 可用（返回 `304 / 0 字节`），NPR 源 `cache-control: no-store` 不可用；
  simplecast 支持 `Range`（前 128 KB 已含 20 条 item）。**优先把轮询降到 60 分钟**
- 香港 2核2G 实测够（转写峰值 795 MB / 翻译 1030 MB / swap 0）；**免 ICP**；
  ⚠️ 不保障内地↔香港跨境质量
- **比手机端更难的三件事**：长任务队列与断点、音频 URL 带签名会过期
  （**抓到即下、下完即转**）、**服务器单点故障**（1.0 完全没有的失败模式）
- 版权：只分发字幕**仍属演绎** → 只给熟人 / 不公开 / 不收费

---

# 账号与打卡同步接口（4.0.0）

> **这是这个项目的第一个应用层。** 在它之前，服务端只有两样东西：
> nginx 送静态文件、每 30 分钟跑一轮 `cycle.py` —— 没有数据库、没有接口、没有鉴权。

## 拓扑

```
App ──https──> nginx(443) ──/api/──> uvicorn 127.0.0.1:8081 ──> SQLite /opt/ec/data/ec.db
                 └── /audio /subs /index.json /apk …：仍然由 nginx 直接送（一个字没改）
```

| 事实 | 为什么 |
| --- | --- |
| **`/api/` 只在 443 上** | 明文 IP 通道是老客户端的应急升级通道，**不让凭证从那里过**（两条 80 入口都显式 `return 404`） |
| 服务跑在 `ecapi`（非 root）下 | 它是对公网解析不可信输入的第一个进程；它只需要读代码 + 读写 `/opt/ec/data` |
| `--workers 1` **是硬要求** | 登录限速是**进程内**状态。多开一个 worker，5 次上限就变成 5×N 次，而且不报错 |
| 日志只进 journald | uvicorn 的 access log 只记「方法 / 路径 / 状态码」⇒ **密码与令牌进不了日志** |
| **管理面没有 HTTP 端点** | 审批 / 发码 / 踢令牌都在 `bin/ec-admin`（SSH + 直连库）。少一个端点就少一处鉴权面 |

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/healthz` | 匿名。**不返回任何用户数据**（连用户数都不报） |
| POST | `/api/auth/register` | `{name,password}` → 202 `pending`（**不批准不能登录**） |
| POST | `/api/auth/login` | `{name,password,device}` → `{token,…}`。错密码与账号不存在返回**同一个** 401 |
| POST | `/api/auth/forgot` | `{name}` → 202（**只是申请**，码要管理员发） |
| POST | `/api/auth/reset` | `{name,code,password}` → 成功后**踢掉该账号所有令牌** |
| POST | `/api/auth/password` | `{oldPassword,newPassword}`（Bearer）→ 成功**不踢**其它设备 |
| POST | `/api/auth/logout` | 只撤销**当前**令牌 |
| GET | `/api/me` | 当前账号 |
| POST | `/api/sync/checkin` | `{device,days,goals,since}` → `{days(全设备合计),goals,devices,serverDay}` |
| POST | `/api/sync/wipe` | 清空该账号在**所有设备**上的打卡记录 |

## ★ 多设备同步的不变式（改这个功能前先读这一段）

```
daily(用户, 天, 设备) → 秒数          ← 每个格子只有一台设备会写
合并： 秒数 = MAX(旧值, 新值)          ← 幂等：重试 / 乱序 / 断网补传都安全
「今天合计」 = Σ 所有设备
```

客户端侧对应的是：**本机那份（mine，只增、负责上传）** +
**其它设备的合计（others，= 总量 − 本机）**，界面显示两者相加。

- ★★ **那一步减法是全部要害**：直接把总量当 others 会把本机算两遍，
  症状是「数字莫名其妙变大」—— 看起来像「终于同步上了」。
  守门人：`tools/verify-account-sync.py` + 单测 `SyncMergeTest`（13 项）。
- 两台设备**同时**听会算两次：求和模型的必然，可接受（算多了比丢时长安全）。
- 跨时区按**设备本地日**算（后果只是多一行，不丢数据）。刻意不做服务端统一时区。
- **首次登录会认领本机的 `sec:` 数据**：所以 4.0.0 之前的老记录一个字都不用迁移。

## 日常管理

```bash
ssh YOUR_HOST 'ec-admin list'                    # 待审批的注册 + 待处理的重置申请（默认命令）
ssh YOUR_HOST 'ec-admin users'                   # 全部账号 + 状态 + 最近活跃
ssh YOUR_HOST 'ec-admin approve <账号>'           # 通过注册
ssh YOUR_HOST 'ec-admin reject <账号> [原因]'
ssh YOUR_HOST 'ec-admin reset <账号>'             # 生成一次性重置码（8 位 / 30 分钟 / 用一次即废）
ssh YOUR_HOST 'ec-admin revoke <账号>'            # 丢手机时：踢掉该账号所有设备
ssh YOUR_HOST 'ec-admin sqlite'                  # 只读 SQL 会话（排查用）
```

## 运维

| 事 | 怎么做 |
| --- | --- |
| 装 / 更新接口 | `./deploy-api.sh`（幂等；**不碰 nginx** —— 那一层走 `install-nginx.sh`） |
| 回滚 / 停掉接口 | `./deploy-api.sh --stop`（静态内容不受影响；此时 `/api/` 会 502，那是预期） |
| 看日志 | `ssh YOUR_HOST 'journalctl -u ec-api -n 100 --no-pager'` |
| 看状态 | `ssh YOUR_HOST 'systemctl status ec-api; systemctl list-timers --all | grep ec-backup'` |
| 备份 | 每天 04:40 自动（`ec-backup.timer`，`Persistent=true`），保留 14 份，**备份完验完整性** |
| 手动备份 | `ssh YOUR_HOST 'systemctl start ec-backup.service; journalctl -u ec-backup -n 5'` |
| 恢复 | 停服务 → `cp ec-YYYYMMDD-HHMM.db /opt/ec/data/ec.db && chown ecapi:ecapi …` → 起服务 |
| 装依赖 | `ssh YOUR_HOST '/opt/ec/venv/bin/pip install fastapi uvicorn[standard]'`（只有第一次要打网络） |

## 安全水位（写清楚，免得以后误判）

- 暴露面只有 `/api/`；管理动作一律走 SSH。
- 密码：scrypt（stdlib）+ 每人独立 salt；**明文既不落盘也不进日志**（有断言守着）。
- 令牌：随机 32 字节，库里只存 sha256；**滑动续期**（用着就一直有效，不用不会主动登出）；
  走 `Authorization: Bearer` 请求头 —— **绝不放进查询串**（那会被写进 access log）。
- 限速：按「账号 + IP」两条线；只按 IP 会被换网绕过，只按账号能把人锁死。
- 最坏情况：令牌泄露 ⇒ 别人能看到你的收听分钟数；一条命令即可撤销。
