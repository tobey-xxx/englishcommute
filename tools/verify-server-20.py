#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
客户端 2.0：内容改为「服务器目录 + 服务器字幕」—— 这一轮的契约锁。

## 这一轮改了什么

    1.0：App 自己拉 11 个 RSS → 下音频 → 端侧转写（sherpa-onnx，622 MB）→ 端侧翻译（108 MB）
    2.0：App 拉服务器 index.json → 下音频（仍直连原 CDN）→ 从服务器取 SRT + 中文
         服务器拿不到时才落回端侧转写/翻译（1.0 的那条路一行没删）

## 每条断言守的都是「不报错但会错」的那类

| # | 静默错法 | 后果 |
|---|---|---|
| 1 | 客户端改用服务端自己的 `id` | 与 1.0 落盘的 id 不一致 → 同一期内容在素材库出现两条，收藏/进度断 |
| 2 | 目录拉不到时不清空也不退回，直接返回空 | 服务器一挂，素材库「突然空了」 |
| 3 | 取回的中文不校验句数 | 长度对不上会被 App 整份丢弃，但**没人告诉用户** |
| 4 | 先跑端侧转写再考虑服务器 | 630 MB 模型照样加载 —— 2.0 最大的收益直接没了 |
| 5 | 模型门禁没带上「有服务器」这个条件 | 新用户仍旧要先下完 630 MB 才能用 |
| 6 | SRT 非原子写 | 被杀留下半截字幕，解析**不报错**，只是后半段没了 |

所以下面除了「存在性」，还有一组**反向对照**：关键判据各配一个坏样本，
要求断言必须在坏样本上失败（否则断言没有分辨力）。

用法：
    python3 tools/verify-server-20.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/com/example/englishcommute"

PASS = 0
FAIL = 0
FAILURES = []


def check_true(name: str, ok: bool, why: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✓ {name}")
    else:
        FAIL += 1
        FAILURES.append(name + (f"  ← {why}" if why else ""))
        print(f"  ✗ {name}" + (f"\n      ↳ {why}" if why else ""))


def section(title: str) -> None:
    print(f"\n{'=' * 74}\n{title}\n{'=' * 74}")


def read(rel: str) -> str:
    p = JAVA / rel
    return p.read_text(encoding="utf-8") if p.is_file() else ""


def strip_comments(src: str) -> str:
    """去掉注释与字符串字面量，避免断言被注释里的示例代码骗过。"""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j))
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            out.append('""')
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


CONFIG = read("net/ServerConfig.kt")
CATALOG = read("net/ServerCatalog.kt")
CATALOG_C = strip_comments(CATALOG)
REPO = read("content/ContentRepository.kt")
REPO_C = strip_comments(REPO)
SYNC = read("sync/SyncEngine.kt")
SYNC_C = strip_comments(SYNC)
EPISODE = read("content/Episode.kt")
EPISODE_C = strip_comments(EPISODE)
STORE = read("content/AudioStore.kt")
STORE_C = strip_comments(STORE)
# ⚠️ 2.1.0 删掉了五个源变量（TranscribePipeline / AudioDecoder / AsrEngine /
#    TranslateManager / ModelProvisioner）—— 那些文件整块拆了。
#    本节剩下的断言全部围绕「服务器那一路」与「失败文案不许撒谎」，
#    而这两件事与端侧有没有识别器无关。
OUTCOME = read("sync/SubtitleOutcome.kt")
OUTCOME_C = strip_comments(OUTCOME)
CLEANUP = read("setup/OnDeviceModelCleanup.kt")
CLEANUP_C = strip_comments(CLEANUP)
APP = read("EnglishCommuteApp.kt")
APP_C = strip_comments(APP)
WORKER = read("sync/SubtitleWorker.kt")
WORKER_C = strip_comments(WORKER)
LIB = read("ui/LibraryScreen.kt")
LIB_C = strip_comments(LIB)
LIB_VM = read("ui/LibraryViewModel.kt")
LIB_VM_C = strip_comments(LIB_VM)
PLAYER_VM = read("ui/PlayerViewModel.kt")
PLAYER_VM_C = strip_comments(PLAYER_VM)

# res/ 与 manifest 不在 JAVA 目录下，用项目根拼
NSC_FILE = ROOT / "app/src/main/res/xml/network_security_config.xml"
NSC = NSC_FILE.read_text(encoding="utf-8") if NSC_FILE.is_file() else ""
MANIFEST_FILE = ROOT / "app/src/main/AndroidManifest.xml"
MANIFEST = MANIFEST_FILE.read_text(encoding="utf-8") if MANIFEST_FILE.is_file() else ""


# ===========================================================================
# 一、服务端地址只定义一处 + 有一条能「回到 1.0」的开关
# ===========================================================================
section("一、服务端地址与总开关")

check_true("ServerConfig 存在且给出了服务器地址",
           'BASE_URL: String = "http' in CONFIG,
           "地址写死一处，少一个能填错的框")
check_true("  · 地址**只有一处**定义（不在别处再写一遍）",
           sum(1 for f in (CATALOG, REPO, SYNC) if "YOUR_SERVER_IP" in f) == 0,
           "散落的 IP 是「改了这里忘了那里」的经典来源")
check_true("  · 有 ENABLED 总开关（关掉 = 完全退回 1.0）",
           "ENABLED: Boolean" in CONFIG,
           "排查「是服务器还是客户端的问题」要靠它一刀切开")
check_true("  · ⚠️ 开关必须是 `val`，**不能是 `const val`**",
           "const val ENABLED" not in CONFIG,
           "const val 会常量折叠：`&& !ENABLED` 恒假 → 1.0 的兜底路径被当死代码整个删掉。"
           "而构建只会报「dex 锚点缺失」，看起来像断言过期 —— 实际是代码真没了（本轮踩过）")


# ===========================================================================
# 二、id 必须由客户端算（与 1.0 对齐），不能用服务端那套
# ===========================================================================
section("二、id 对齐（升级不出现重复条目）")

check_true("从服务器读的是**原始 guid**，不是它的 id",
           'optString("guid")' in CATALOG,      # 含字面量 → 必须用原始源码
           "服务端的 id 是 sha1(guid)[:16]+尾，与 1.0 的 id 不是一回事")
check_true("  · 客户端用 RssParser.stableId 自己算 id",
           "RssParser.stableId(" in CATALOG_C,
           "算法只保留一处；两边各写一份实现 = 两边可能不一致")
check_true("  · 没有 guid 时也用同一套算法（退回音频地址，而不是服务端 id）",
           "noGuid++" in CATALOG_C and "val id = RssParser.stableId(guid, audioUrl)" in CATALOG_C,
           "2.0.3：退回服务端 id 会让同一期内容分裂成两条 —— "
           "一条有音频没字幕、一条有字幕没音频，而后者没下载过、点下去毫无反应")
check_true("Episode 的新字段落盘（remoteSrt / remoteZh）",
           'put("remoteSrt"' in EPISODE and 'put("remoteZh"' in EPISODE,
           "不落盘的话，冷启后就忘了去哪儿取字幕，那期永远停在「有音频没字幕」")
check_true("  · 读回时也有（老索引没有 → null，不能崩）",
           "optStringOrNull(\"remoteSrt\")" in EPISODE, "")


# ===========================================================================
# 三、目录拿不到时必须退回 RSS，而不是把素材库清空
# ===========================================================================
section("三、服务器不可达 → 退回 1.0 的 RSS 路径")

check_true("目录失败返回 null（不是空列表）",
           "return@withContext null" in CATALOG_C,
           "空列表是「服务器在但没内容」，null 是「服务器不可用」——两者必须分开")
check_true("  · 拉不到目录时走 RSS（`?:` 后面的那段并发拉取）",
           re.search(r"serverEps\?\.let .*?\} \?: FeedSources\.ALL\.map", REPO_C, re.S) is not None,
           "少了这条退路，服务器一挂素材库就「突然空了」")
check_true("  · 合并时 remoteSrt/remoteZh 单调保留（不被 RSS 的 null 抹掉）",
           "remoteSrt = item.remoteSrt ?: old.remoteSrt" in REPO_C,
           "从服务器模式掉回 RSS 模式会忘掉「去哪儿取字幕」，而它本来还能取")


# ===========================================================================
# 四、字幕只有一个来源：服务器（2.1.0）
# ===========================================================================
#
# 这一节原来验的是「**先**问服务器，拿不到才落回端侧」——四级优先级、
# FromServer 变体、以及「取服务器字幕不扣端侧额度」。
#
# 端侧那条路拆掉之后，优先级这个概念本身消失了：**只有一条路**。
# 于是这一节改判「那条路真的只剩一条」，因为留一个假的兜底分支
# （比如「取不到就本地生成」的注释或空实现）会让下一个人以为还有 Plan B。
section("四、字幕只有一个来源")

check_true("取字幕只有一处实现", SYNC_C.count("fetchSubtitlesFor(") >= 1)
check_true("  · 拿不到时**不会**去别处生成（没有第二来源）",
           "asrEngine" not in SYNC_C and "pipeline" not in SYNC_C,
           "留着任何一个，就意味着「字幕可能来自本机」这件事在代码里仍然成立")
check_true("  · 结果类型里没有「本机生成」这一支（只有从服务器拿与拿不到）",
           "FromServer" not in OUTCOME_C and "Got(" in OUTCOME_C,
           "两套来源的名字都在，下一个人就会去实现第二条路")

check_true("中文取回后**校验句数**再落盘",
           "zh.size == lines.size" in CATALOG_C,
           "长度对不上会被 App 整份丢弃 —— 不校验的话还以为写成功了。"
           "⚠️ 2.1.0 起这条校验收紧了：以前句数对不上还能退回端侧翻译，"
           "现在它是唯一一道防错线")
check_true("  · SRT 解析不出句段时弃用（不写半截文件）",
           "取回的字幕解析不出句段" in CATALOG,
           "写下去就会被当成有效字幕，且不报错")

# —— 2.1.0 新增：有英文、没中文时**必须再取一次** ——
#
# 服务器的 zh 比 srt 晚一轮发布，而客户端原来是「有 srt 就不再取」。
# 于是「在服务器翻译完成之前点开过这一期的用户，永远只有英文」——
# 2.0 时这条空洞被端侧翻译兜住，拆掉端侧之后就没有兜底了。
check_true("有英文、没中文时也算「待补」",
           "episode.remoteZh != null && !audioStore.hasTranslation(episode)" in REPO_C,
           "少了它，中文对照会「时有时无」，而现象上看不出任何规律")
check_true("  · 取字幕的入口对这种情况是幂等的（会再取一次）",
           "fetchSubtitles(episode)" in SYNC_C,
           "两条路径（只取中文 / 取两个）迟早会有一条被漏掉")


# ===========================================================================
# 五、首次启动不再被任何东西挡住（2.1.0：门禁整块删掉）
# ===========================================================================
#
# 2.0 那一版的门禁是「**先问服务器字幕，取不到才看模型**」——
# 也就是「模型不再是能用 App 的前提，但仍是没有服务器字幕时的前提」。
#
# 现在连那半句也不成立了：没有任何模型。于是四处门禁
# （SyncEngine.run / Worker / 插队 / 回前台）**全部删掉**，
# 而这一节改判「它们真的都删干净了」—— 留一处，就留了一条
# 「关掉服务器开关后行为回到 1.0」的假象，而 1.0 已经没有可回的东西。
section("五、没有任何「首次准备」门禁了")

for name, src in (("SyncEngine.run", SYNC_C), ("后台 Worker", WORKER_C),
                  ("Application 的三个入口", APP_C)):
    check_true(f"{name}：没有模型/部署门禁",
               "modelReady" not in src and "provisioner" not in src
               and "DeployPending" not in src,
               "这三处是同一个口径的四个副本，改一处别忘了别处；"
               "现在正确的做法是**一处都没有**")
SVC_C = strip_comments(read("sync/ContentSyncService.kt"))
check_true("  · 也没有残留的「准备中」通知文案",
           "正在准备语音功能" not in APP_C
           and "正在准备语音功能" not in SVC_C,
           "留下的文案会指向一个不存在的过程")


# ===========================================================================
# 六、字幕落盘必须原子（半截字幕不报错，只是后半段没了）
# ===========================================================================
section("六、字幕原子写")

check_true("writeSrt 先写 .tmp 再改名",
           'f.name + ".tmp"' in STORE and "renameTo(f)" in STORE_C,
           "裸 writeText 被杀会留半截 SRT，而 SrtTrack.parse 对截断是宽容的 —— 不报错")
check_true("  · 清理时连 .tmp 一起删",
           'srtDir, srtFile(episode).name + ".tmp"' in STORE,
           "否则留下永远不会被读到的孤儿")


# ===========================================================================
# 七、坏样本对照：断言必须有分辨力
# ===========================================================================
# 七、能连上服务器：明文策略只对本机服务器放行
#
#   2.0.0 就是栽在这里。targetSdk 28 起 Android 默认禁明文 HTTP，
#   而 manifest 里写着 usesCleartextTraffic="false"、服务器又是 http:// ——
#   OkHttp 每次都直接抛 UnknownServiceException，**一个字节都没发出去**。
#   表现是「素材库照常更新、永远没有服务器字幕」，而日志里只有一行 W。
# ===========================================================================
section("七、能连上服务器：明文只对本机服务器放行")

check_true("有 network_security_config.xml",
           bool(NSC),
           "策略写在这里；写在 manifest 的 usesCleartextTraffic 里就是全局开关，"
           "为一个 IP 把 NPR/TED 的音频请求也一起降级")
check_true("  · manifest 引用了它",
           'android:networkSecurityConfig="@xml/network_security_config"' in MANIFEST, "")

_app_tag = MANIFEST.split("<application", 1)[1].split(">", 1)[0] if "<application" in MANIFEST else ""
check_true("  · ⚠️ manifest 里**不再**出现 usesCleartextTraffic（单一真相源）",
           "usesCleartextTraffic" not in _app_tag,
           "两处真相源就会有人只看一处。2.0.0 就是这么错的：manifest 说全局禁明文，"
           "服务器却是 http://，两边都「看起来没错」")
check_true("  · 默认仍然禁明文（base-config）",
           'cleartextTrafficPermitted="false"' in NSC, "")
check_true("  · 只对本机服务器放行（domain-config）",
           'cleartextTrafficPermitted="true"' in NSC, "")

m_url = re.search(r'BASE_URL[^"\n]*"http[s]?://([^"/]+)', CONFIG)
base_host = m_url.group(1) if m_url else ""
nsc_hosts = [d.strip() for d in re.findall(r"<domain[^>]*>([^<]+)</domain>", NSC)]

# ── 3.1.0：这条断言从「相等」改成了「形状」 ────────────────────────────────
#
# 2.1.x 时它守的是「xml 放行的域 == BASE_URL 的 host」—— 那时两者是同一台机器的
# 同一个形态（都是那个 IP），相等就够了。
#
# 3.1.0 起 BASE_URL 是 `https://YOUR_DOMAIN`，而 xml 里放行的仍是**那个 IP** ——
# 两者**故意不相等**（理由见 network_security_config.xml 的文件头：
# 它是应急更新通道，不是内容通道）。原来的断言会在这里误报。
#
# ⚠️ 但**不能因此把它删掉** —— 删掉就等于这个方向再没有人守。
#    改成守「形状」，四条一起，任何一条破掉都说明有人只改了一半：
#      ① BASE_URL 是 https
#      ② 明文例外恰好一条（多了说明有人随手放行了别的）
#      ③ 那条例外是**裸 IP**、不是域名（放行域名 = 给未来的自己留了个
#         走明文回退的诱惑，而域名已经有 TLS，没有放行的理由）
#      ④ 那个 IP 与服务端 `FALLBACK_BASE_URL` **逐字一致** ——
#         它是同一条通道的两端（这边写「允许明文去哪」，那边写「回退到哪」）
check_true("  · BASE_URL 是 https（内容不再走明文）",
           base_host != "" and CONFIG.count('BASE_URL: String = "https://') == 1,
           f"实际 host = {base_host!r}")
check_true("  · 明文例外恰好一条", len(nsc_hosts) == 1, f"实际 {nsc_hosts!r}")
check_true("  · 那条例外是裸 IP，不是域名",
           len(nsc_hosts) == 1
           and re.fullmatch(r"\d{1,3}(?:\.\d{1,3}){3}", nsc_hosts[0]) is not None,
           f"实际 {nsc_hosts!r} —— 放行域名等于留下一个明文回退的口子")

_pub_path = ROOT / "server/bin/publish_update.py"
_pub_src = _pub_path.read_text(encoding="utf-8") if _pub_path.is_file() else ""
_fb = re.search(r'FALLBACK_BASE_URL = "http[s]?://([^"/]+)"', _pub_src)
fallback_host = _fb.group(1) if _fb else ""
check_true(f"  · 例外里的 IP == 服务端 FALLBACK_BASE_URL（{fallback_host or '未解析到'}）",
           fallback_host != "" and nsc_hosts == [fallback_host],
           f"xml 放行 {nsc_hosts!r}，服务端回退到 {fallback_host!r}。"
           f"不一致 = 应急回退产出的地址被客户端直接拒绝，"
           f"而症状是「按文档切回去了还是下载失败」—— 看起来像命令没生效")
check_true("  · 而且那段回退示例命令里写的就是这个地址",
           fallback_host != "" and f"--base-url http://{fallback_host}" in _pub_src,
           "注释里的示例命令与常量分叉之后，照着文档操作会失败，"
           "而失败的样子是「我明明按文档做了」")

try:
    import xml.etree.ElementTree as ET
    ET.fromstring(NSC)
    ET.fromstring(MANIFEST)
    _xml_ok = True
except Exception:
    _xml_ok = False
check_true("  · 两份 XML 都合法（开始标签的属性之间不许写注释）", _xml_ok,
           "XML 不允许在属性之间写注释 —— 本工程为此踩过两次，第二次就是这一轮")


# ===========================================================================
# 八、失败原因不许撒谎：模型缺失 ≠ 这条音频读不出来
#
#   2.0.0 真机：音频完好、能正常播放，界面却叫用户「换一条试试」。
#   原因是 onChunk（业务回调）里 AsrEngine 抛的「模型文件缺失」被 AudioDecoder
#   的 catch-all 包成了 DecodeException(「解码失败」)，上层再映射成那句话。
#   用户唯一能做的动作，恰恰是解决不了问题的那个。
# ===========================================================================
section("八、失败原因不许撒谎（模型缺失 ≠ 音频坏）")

# ⚠️★ 2.1.0：这一节原来是**整个脚本里最有价值的一段**，而它验的东西现在
# 一件都不存在了 —— 我把它整段删掉之前先把结论留在这里：
#
#   2.0.0 真机：音频完好、能正常播放，界面却叫用户「换一条试试」。
#   根因是 onChunk（业务回调）里 AsrEngine 抛的「模型文件缺失」被 AudioDecoder
#   的 catch-all 包成了 DecodeException(「解码失败」)，上层再映射成那句话。
#   用户唯一能做的动作，恰恰是解决不了问题的那个。
#   修法两层：AudioDecoder 的 CallbackFailure（谁抛的比抛的是什么重要）
#             + AsrEngine.ModelMissingException（类型化，不靠字符串前缀）
#
# 现在没有 AudioDecoder、没有 AsrEngine、没有 pipeline，
# 也就没有「回调异常被基础设施层包住」这条路径了 ——
# **但那条教训活着**，它以三种形式留着：
#   · `Agent 记忆` 的「错误文案必须指向真实原因」那一条
#   · `verify-crashfix.py` ① 节（ListeningClock 的同类问题：基础设施层吞异常）
#   · 本节这段说明
#
# 2.1.0 重新表述同一件事（下面这两条仍然有效，而且**更重要了**）：
# 现在唯一的失败路径是「字幕取不到」，而它的文案必须说清是**哪一种取不到**。
check_true("两种「取不到字幕」在类型层面就是分开的",
           "data object NotYet" in OUTCOME_C and "data object ServerDown" in OUTCOME_C,
           "合成一个枚举值，调用方就只能靠 message 猜；而这两种的下一步完全相反")
check_true("  · 而「音频坏了」这个说法**没有**被用在字幕问题上",
           "这条音频读不出来" not in SYNC_C and "换一条试试" not in SYNC_C,
           "音频永远是好的（它就是那个 mp3），字幕没到与它无关")


# ===========================================================================
# 九、2.0.1：「没有服务器字幕」的两种原因不许混成一句
#
#   真机上报：刚发布的 NPR 整点新闻显示「字幕暂缺 · 装离线识别后自动补上」，
#   而它的真实原因是「服务器那一轮还没跑」（服务器 15 分钟一轮，
#   而这条 21:13 发布、服务器 21:02 刚跑完）。用户根本不需要装模型。
#
#   根因是 fetchCatalog() 的调用方只拿到 null，而 null 同时代表
#   「目录里没有这一条」与「根本拿不到目录」—— 在调用方猜就一定说错一种。
# ===========================================================================
section("九、两种「没有服务器字幕」不许混成一句")

check_true("ServerCatalog 记录了「本次目录到底拿没拿到」",
           "var lastCatalogOk: Boolean" in CATALOG_C,
           "调用方拿到的只有 null，无从分辨原因；只有发请求的那一层知道")
check_true("  · 它是只读的（private set）",
           "private set" in CATALOG_C, "")
check_true("  · 网络失败路径置 false",
           CATALOG_C.count("lastCatalogOk = false") == 2,
           f"实际 {CATALOG_C.count('lastCatalogOk = false')} 处（网络失败 + 解析失败）")
check_true("  · 成功路径置 true，且在所有提前返回**之后**",
           CATALOG_C.count("lastCatalogOk = true") == 1,
           "只有一个成功出口")
# ⚠️ 范围必须限定在 fetchCatalog 里：文件后面还有 fetchSubtitles，
#    它也有 `return@withContext null`。用整份源码 rfind 会找到那一个，
#    于是断言恒假 —— 这是本轮第二次「假失败」，原因都是「没限定范围」。
_cat_fn = CATALOG[
    CATALOG.find("suspend fun fetchCatalog"):CATALOG.find("private fun parse(")
]
_i_true = _cat_fn.find("lastCatalogOk = true")
_i_ret = _cat_fn.rfind("return@withContext null")
check_true("  · 且 true 确实在 fetchCatalog 最后一个 null 出口之后",
           -1 < _i_ret < _i_true,
           "放在前面的话，失败路径也会把它设成 true —— 那就完全反了")

check_true("两种原因各有各的文案，且只有「服务器连不上」进红色错误位",
           "SERVER_DOWN_HINT" in SYNC_C, "")
check_true("  · 门禁处按 lastCatalogOk 分流",
           "repository.serverCatalog.lastCatalogOk" in SYNC,
           "写死一种文案，就一定会把其中一半场景说错")
# ⚠️ 2.1.0：这三条断言的**判据对象从「内联的 if/else」改成了 `markPending`**。
#    2.0.3 那版是「在 NoModel 分支里就地写一段状态与文案的取舍」，
#    2.1.0 把那段收成了一个函数（两个调用点：还没轮到 / 服务器不可达）。
#    收成一处的理由和「判据只能有一份」是同一条 —— 但那意味着
#    断言不能再去找那行字面量，得去找那个函数里的取舍。
check_true("  · 「稍后自愈」那一种不再占用 error（它是正常流程，不是错误）",
           "error = if (serverDown) SERVER_DOWN_HINT else null" in SYNC_C,
           "给正常流程标红，用户会以为坏了 —— 这件事由卡片上的「字幕待补」胶囊说，中性色")
check_true("  · 「稍后自动补」那句确实能自愈（不依赖任何用户动作）",
           "fun pendingSubtitleCount(): Int = _episodes.value.count { needsSubtitles(it) }" in REPO_C
           and "if (!audioStore.hasSrt(episode)) return true" in REPO_C,
           "待补队列的判据是「有音频、字幕没齐」，与 status 无关 —— "
           "所以下一轮它会自动被捡起来重试")
check_true("  · 两条日志都在（排查时能分辨是哪种）",
           "服务器还没处理这一期" in SYNC and "服务器不可达" in SYNC, "")


# ===========================================================================
# 九之二、2.0.3：「点不动」—— 静默吞掉点击 + 错标 Failed 的两个根
#
#   真机上报（diag(6).log）：最新一期（NPR 9AM，服务器还没转写到它）
#   显示「字幕暂缺」，**点下去完全没反应** —— 不进播放页、不弹提示、
#   日志里也没有任何记录。用户的原话是「这个音频我也无法播放」。
#
#   两个根：
#     ① `transcribeOne` 在「服务器还没处理这一期 + 本机没装模型」时把状态
#        标成 EpisodeStatus.Failed —— 本意是「这条的字幕欠着」，但
#        `Failed.canPlay == false`，于是它成了「不能播」。
#     ② `LibraryScreen` 的点击判据是 `if (canPlay && !selfBusy) onPlay()`：
#        两个条件都会**静默**吞掉点击，没有任何出口。
#
#   这一节的断言守的就是这两条，外加「用户点了就必须真的开始下」。
# ===========================================================================
section("九之二、「点不动」：不能播的行点击不许静默丢弃")

check_true("「字幕欠着」不再标成 Failed（音频在盘上就是能听）",
           "status = if (repository.audioStore.hasAudio(episode))" in SYNC_C,
           "2.1.0：这条判据收进了 `markPending` 一处（原来是两处各写一遍）——"
           "所以只要它在那一个函数里，所有调用它的路径都已经是正确行为。"
           "判据本身仍然是**文件系统**而不是状态位：音频在盘上就是能听")
check_true("  · 播放判据收到 Episode.isPlayable 一处",
           "val isPlayable: Boolean get() = audioFileName != null" in EPISODE,
           "判据散在多处时，改一处就会漏另一处")
check_true("  · 危险的 status.canPlay 已从枚举里删除",
           "val canPlay" not in EPISODE_C,
           "留一个同名属性在那里，下一个人会本能地用它 —— 而它答的是另一个问题"
           "（注释里提到它不算，所以断言看的是去注释的源码）")
check_true("  · 列表与播放队列都用新判据",
           "episode.isPlayable" in LIB_VM_C and "it.isPlayable" in PLAYER_VM_C,
           "改了判据却漏掉其中一个调用点，会出现「能点开但队列里没有」")
check_true("  · 不能播时点击必须去下载，而不是什么都不做",
           "onNeedDownload()" in LIB_C,
           "真机上用户看到的是「这个音频点不出来」，而真相只是「还没下载」")
check_true("  · 被吞掉的动作**留痕**（日志里能查到）",
           "点了一条还没下好音频的内容" in LIB,
           "这一整轮排查里最贵的一条：现象无法复现，因为日志里一行都没有")
check_true("  · selfBusy 不再参与点击判定",
           "if (canPlay && !selfBusy) onPlay()" not in LIB_C,
           "「正在转写」说明音频早在盘上、完全能播，过去也被一起挡住了")
check_true("  · 下载插队要越过「更新窗口」",
           "pendingDownload" in SYNC_C and "val (forced, rest)" in SYNC_C,
           "被窗口跳过的那条**永远**下不下来（下轮年龄只会更大）→ 一行永久死条目")
check_true("  · 插队下载真的会把服务拉起来（窗口内也要下）",
           "container.syncEngine.requestDownload(episodeId)" in APP_C
           and "ContentSyncService.start(this)" in APP_C,
           "只登记不起服务 = 用户点了还是没反应（而且要等下一次回到前台）")
check_true("  · 服务端没给 guid 时客户端不退回服务端 id",
           "o.optString(\"id\")" not in CATALOG_C,
           "两套 id 算法并存 → 同一期两条：一条有音频没字幕、一条有字幕没音频")
check_true("  · 合并时按音频地址兜底识别「同一期」",
           "existing[item.id] ?: byAudioUrl[item.audioUrl]" in REPO_C,
           "服务端丢了 guid 时两个 id 必然对不上，没有这层兜底就会分裂成两条")
check_true("  · 按音频地址认出后**沿用本地 id**（收藏/进度/已下音频都挂在它上面）",
           "val keepId = old.id" in REPO_C and "id = keepId," in REPO_C,
           "换 id 等于把用户积累的收藏、断点、已下载文件一起丢掉")
check_true("  · 升级上来时把上一版错标的 Failed 迁移回可播",
           "migrateStaleSubtitleFailure()" in REPO,
           "状态位是落盘的：不迁移，那条会误导排查的红字就一直在（本轮差点被它骗过）")
check_true("  · 迁移只碰「Failed + 音频在盘上」，音频真没下来的保持 Failed",
           "it.status == EpisodeStatus.Failed && it.audioFileName != null" in REPO_C,
           "把「音频没下下来」也一起放过，等于把真失败藏起来")


# ===========================================================================
section("十、坏样本对照（每条都必须被拦下）")

# A. 去掉 RSS 退路
bad_a = REPO_C.replace("} ?: FeedSources.ALL.map", "} ?: listOf()", 1)
check_true(
    "坏样本 A（目录失败不退回 RSS）被拦下",
    bad_a != REPO_C
    and re.search(r"serverEps\?\.let .*?\} \?: FeedSources\.ALL\.map", bad_a, re.S) is None,
    "断言三必须能识别「退路被去掉」",
)

# B. 先端侧后服务器
# ⚠️ 2.1.0：坏样本 B 换代了。
#
#   旧 B：「顺序反了：先端侧再服务器」—— 端侧那条路没了，顺序无从谈起。
#   新 B：「拿不到服务器字幕时**去别处生成**」—— 这是同一类错误在新架构下的形态：
#         为了让「字幕永远有」，悄悄接一条第二来源。它比没有兜底更糟，
#         因为那条来源的质量、时序、句段切分都与服务器不同，
#         于是同一个 App 里会出现两种字幕格式。
bad_b = OUTCOME_C.replace(
    "data object NotYet : SubtitleOutcome",
    "data object RegenerateLocally : SubtitleOutcome",
    1,
)
check_true(
    "坏样本 B（结果类型里出现「本机生成」这一支）被拦下",
    bad_b != OUTCOME_C and "RegenerateLocally" in bad_b,
    "两套来源的名字都在，下一个人就会去实现第二条路",
)

# C. 去掉译文长度校验
bad_c = CATALOG_C.replace("zh.size == lines.size", "true", 1)
check_true(
    "坏样本 C（不校验译文句数）被拦下",
    bad_c != CATALOG_C and "zh.size == lines.size" not in bad_c,
    "去掉校验后错位的译文会被静默写盘",
)

# D. 门禁回来了（2.1.0 的正确做法是「一处都没有」）
bad_d = SYNC_C.replace(
    "        if (!busy.compareAndSet(false, true)) {",
    "        if (!modelReady()) return\n        if (!busy.compareAndSet(false, true)) {",
    1,
)
check_true(
    "坏样本 D（「首次准备」门禁被加回来）被拦下",
    bad_d != SYNC_C and "modelReady" in bad_d,
    "加回来的门禁会让「装完 App 能不能用」重新取决于一个不存在的东西 —— "
    "而它的表现是更新按钮永远灰着，且**不报任何错**",
)

# F. 开关写成 const val（常量折叠会把 1.0 兜底路径删掉）
bad_f = CONFIG.replace("val ENABLED: Boolean", "const val ENABLED: Boolean", 1)
check_true(
    "坏样本 F（开关写成 const val）被拦下",
    bad_f != CONFIG and "const val ENABLED" in bad_f,
    "这就是「注释说兜底在、其实包里没有」",
)

# E. 客户端改用服务端 id
bad_e = CATALOG_C.replace("RssParser.stableId(guid, audioUrl)", 'o.optString("id")', 1)
check_true(
    "坏样本 E（客户端直接用服务端 id）被拦下",
    "RssParser.stableId(" not in bad_e,
    "id 对不上 → 素材库出现重复条目，且收藏与进度断掉",
)


# ⚠️★ 2.1.0：坏样本 G / H / I 三个**一起删了** —— 它们分别模拟
#   「onChunk 不隔离」「不再专门认模型缺失」「事前门禁被短路」，
#   而这三个对象（AudioDecoder / AsrEngine / TranscribePipeline / 门禁）
#   全部不存在了。
#
#   它们防的是**同一个东西**：「一层基础设施把一个业务异常包成了另一件事，
#   于是用户被指向错误的补救动作」。那条教训现在由两种形式守着：
#     · 坏样本 D（门禁加回来 → 更新按钮永远灰着且不报错）
#     · 坏样本 B（悄悄接一条第二来源）
#   —— 两者都是「静默地改变用户处境」这一类。

# J. 明文例外里的 IP 与服务端回退地址不一致
# ⚠️ 必须替换**完整标签**：裸 IP 的第一次出现是在注释里（那段解释明文策略的文字里
#    就写着 http://YOUR_SERVER_IP），换错了位置会让坏样本「看起来生效了其实没有」。
bad_j = NSC.replace('>YOUR_SERVER_IP</domain>', '>10.0.0.1</domain>', 1)
_bad_j_hosts = [d.strip() for d in re.findall(r"<domain[^>]*>([^<]+)</domain>", bad_j)]
check_true(
    "坏样本 J（明文例外里的 IP ≠ 服务端 FALLBACK_BASE_URL）被拦下",
    _bad_j_hosts != [fallback_host],
    "这是同一条应急通道的两端。不一致时「按文档切回 IP」会产出一个"
    "客户端直接拒绝的地址，症状是「切回去了还是下载失败」",
)

# J2. 明文例外放行的是**域名**（3.1.0 之后等于给自己留了个明文回退的口子）
bad_j2 = NSC.replace('>YOUR_SERVER_IP</domain>', '>YOUR_DOMAIN</domain>', 1)
_bad_j2_hosts = [d.strip() for d in re.findall(r"<domain[^>]*>([^<]+)</domain>", bad_j2)]
check_true(
    "坏样本 J2（明文例外放行域名而非 IP）被拦下",
    not (len(_bad_j2_hosts) == 1
         and re.fullmatch(r"\d{1,3}(?:\.\d{1,3}){3}", _bad_j2_hosts[0]) is not None),
    "域名已经有 TLS 了，放行它的明文没有任何理由 —— 只会让「顺手退回 http」"
    "变成一个不改配置就能做的选择",
)

# J3. 明文例外多了一条
bad_j3 = NSC.replace(
    '<domain includeSubdomains="false">YOUR_SERVER_IP</domain>',
    '<domain includeSubdomains="false">YOUR_SERVER_IP</domain>\n'
    '        <domain includeSubdomains="false">1.2.3.4</domain>', 1)
_bad_j3_hosts = [d.strip() for d in re.findall(r"<domain[^>]*>([^<]+)</domain>", bad_j3)]
check_true(
    "坏样本 J3（明文例外多了一条）被拦下",
    len(_bad_j3_hosts) != 1,
    "每多一条例外，就多一个没人记得为什么在那儿的明文口子",
)

# K. manifest 又写回 usesCleartextTraffic
bad_k = MANIFEST.replace(
    'android:networkSecurityConfig="@xml/network_security_config"',
    'android:usesCleartextTraffic="false"\n        '
    'android:networkSecurityConfig="@xml/network_security_config"', 1)
_bad_k_app = bad_k.split("<application", 1)[1].split(">", 1)[0]
check_true(
    "坏样本 K（manifest 又出现 usesCleartextTraffic）被拦下",
    "usesCleartextTraffic" in _bad_k_app,
    "两处真相源是 2.0.0 那个 bug 的成因",
)


# L. lastCatalogOk 的 true 挪到失败路径之前（于是失败也变成「稍后自动补」）
bad_l = CATALOG.replace(
    "    var lastCatalogOk: Boolean = false",
    "    var lastCatalogOk: Boolean = true", 1)
check_true(
    "坏样本 L（lastCatalogOk 默认成 true）被拦下",
    "_i_true > _i_ret" and bad_l != CATALOG,
    "默认 true 会让「服务器不可达」也被说成「稍后自动补」—— 用户会一直等一个不会来的字幕",
)


# M. 「字幕欠着」又被标回 Failed（＝ 那一行又会整行点不动）
#    ⚠️ 两处都要换：只换一处的话，另一处仍然满足「字符串还在」这个判据，
#       坏样本就变成了假通过（本轮踩过一次）。
bad_m = SYNC_C.replace(
    "status = if (repository.audioStore.hasAudio(episode)) {",
    "status = if (false) {")
check_true(
    "坏样本 M（把「字幕欠着」标回 Failed）被拦下",
    "status = if (repository.audioStore.hasAudio(episode))" not in bad_m
    and "status = if (false) {" in bad_m,
    "这一条正是真机上「点不动」的根：Failed 不在能播的集合里",
)

# N. 点击判定又变成静默吞掉
#
#    ⚠️★ 2.2.0：这里的**锚点换过一次**，而原因本身值得记下来。
#
#    旧写法是「取 `if (canPlay) {` 之后到**第一个** `}` 的文本，看里面有没有
#    onNeedDownload()」。2.2.0 在 `if (canPlay)` 里面**又嵌了一层**
#    `if (needsAudio) { … onNeedDownload() … }`（边听边下时要顺手落盘）——
#    于是「第一个 `}`」变成了**内层**的结尾，判据失去分辨力：
#    好代码也会被判成「点击被吞掉」。这正是纪律说的**判据落在代理量上**
#    （第 N 个花括号是结构代理，不是「用户点下去有没有下文」这个语义本身）。
#
#    现在改成两支各自判：能播那支要有 onPlay，不能播那支要有 onNeedDownload。
def _click_body(src: str) -> str:
    """`onClick = { … }` 这一块（到 `onLongClick` 之前）"""
    i = src.find("onClick = {")
    if i < 0:
        return ""
    j = src.find("onLongClick", i)
    return src[i:j] if j > i else src[i:]


def _play_branch(src: str) -> str:
    """`if (canPlay) { … }` 这一支（到 `} else` 之前）

    ⚠️ 单独取这一支是必须的：**两支里都有 onNeedDownload()**，
       只看整块的话「能播那支不再落盘」这个坏样本根本区分不出来。
    """
    b = _click_body(src)
    i = b.find("if (canPlay)")
    if i < 0:
        return ""
    j = b.find("} else", i)
    return b[i:j] if j > i else b[i:]


_click = _click_body(LIB_C)
check_true(
    "  · ★2.2.0 点击的两支都有下文（不存在静默吞掉的分支）",
    "onPlay()" in _click and _click.count("onNeedDownload()") >= 2,
    "能播的那支要 onPlay（本地没落盘时还要顺手落盘）；不能播的那支要 "
    "onNeedDownload —— 少任何一支都会退回 2.0.3 的「整行点下去什么都不发生」",
)
check_true(
    "  · 能播那支里，本地没落盘时要顺手落盘（边听边下的另一半）",
    "onNeedDownload()" in _play_branch(LIB_C),
    "只播不落盘 = 用户点过的那条永远不落本地 → 下次在地铁里点开还是转圈",
)

# 坏样本 N：把 onClick 整个换成空操作
# （用**取出来的那一块本身**当锚点 —— 不猜缩进、不猜第几个花括号）
bad_n = LIB_C.replace(_click, "onClick = { }\n                    ", 1)
check_true(
    "坏样本 N（点击整个变成空操作）被拦下",
    bad_n != LIB_C
    and "onNeedDownload()" not in _click_body(bad_n)
    and "onPlay()" not in _click_body(bad_n),
    "这就是 2.0.3 真机上的原样：整行点下去，不进播放页、不弹提示、日志里一行都没有",
)

# 坏样本 N2：能播时不再顺手落盘（= 懒加载变成「点了也不给下」）
bad_n2 = re.sub(
    r"if \(needsAudio\) \{[\s\S]*?onNeedDownload\(\)[\s\S]*?\}",
    "", LIB_C, count=1)
check_true(
    "坏样本 N2（能播时不再顺手落盘）被拦下",
    bad_n2 != LIB_C and "onNeedDownload()" not in _play_branch(bad_n2),
    "边听边下之后，用户点过的那条**一定**要落到本地 —— 否则下次在地铁里"
    "点开它还是转圈，而「懒加载」就成了「点了也不给下」",
)

# O. 去掉按音频地址的兜底合并
bad_o = REPO_C.replace(
    "existing[item.id] ?: byAudioUrl[item.audioUrl]", "existing[item.id]", 1)
check_true(
    "坏样本 O（去掉按音频地址合并的兜底）被拦下",
    "existing[item.id] ?: byAudioUrl[item.audioUrl]" not in bad_o,
    "服务端丢了 guid 时两个 id 必然对不上，同一期会分裂成两条",
)


# ===========================================================================
print(f"\n{'=' * 74}")
print(f"通过 {PASS} 项，失败 {FAIL} 项")
if FAILURES:
    print("\n失败明细：")
    for f in FAILURES:
        print(f"  - {f}")
print("结论：" + ("全部通过 ✅" if FAIL == 0 else "有断言未通过 ❌"))
print("=" * 74)
sys.exit(1 if FAIL else 0)
