#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
词库**独立更新**的契约（3.3.0 新建）。

## 背景：这一轮之前「更新词库」这件事用户做不到

词表原来只有两条更新路径，而两条都绑在别的东西上：

    assets/vocab/ 下那三个文件    2.2 MB    ← 只能跟着 **App 版本**走
    vocab/add.json                十几 KB   ← 跟着**素材同步**走（每 30 分钟一轮）

中间那一大块没有独立通道。用户的原话是「把词库更新功能单独做出来，
不用跟着 app 版本更新」—— 这个脚本守的就是那条新通道。

## 四处「不报错」的错法

### ① 路径不带版本号 + 长缓存 = 永远升不上来

nginx 给静态文件设 `immutable` 是因为客户端每次都要重下 2.4 MB。
而 `vocab.base` 这个**文件名是固定的、内容却每天在变** ——
路径里不带内容版本的话，已经缓存过旧文件的客户端**永远拿不到新词表**，
而且它自己不知道（版本号变了、它去下载，下回来的是缓存里那份）。

这一条是本工程的**老纪律**（`/pron/` 那段注释里写着它的正面版本）。

### ② 清单说「装好了」但文件不全

下载完写清单、然后用户清了数据目录、或者写到一个一半被杀 ——
只要判据是「清单在不在」，词表就会去加载一个不存在的目录，
而症状是「查不到词」，看起来像词表内容的问题。
所以**判据必须落在三个文件上**。

### ③ 下完不切换（或切换不是原子的）

逐个文件 rename 的话，中间会有一个「目录在、文件缺」的窗口，
那一瞬间的读取会拿到半份词表。整目录 rename 才是原子的。

### ④ 更新完不让内存里那份失效

`WordTableLoader` 把解析结果**常驻**在内存里（那是它的设计）。
更新完不 `invalidate()` 的话，这一次更新在本进程里**什么都不会发生** ——
界面已经说「已更新」，用户再查一次那个词依然查不到，**症状和没更新一模一样**，
而且不会自愈（要等进程重启）。

## 这个脚本守什么

一、服务端：产物、版本号、清理、挂进 cycle
二、分发：nginx 三块 location（且收口没被打开）
三、客户端：三条判据落在正确的量上
四、接线：容器顺序、设置页、更新后的失效
五、文案（「词表」是黑名单词）
六、坏样本
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__)) + "/.."
KT = os.path.join(ROOT, "app/src/main/java/com/example/englishcommute")
BIN = os.path.join(ROOT, "server/bin")
NGINX = os.path.join(ROOT, "server/nginx")

PASS, FAIL = 0, 0
FAILURES = []


def check(name, ok, why=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  ✅ %s" % name)
    else:
        FAIL += 1
        FAILURES.append(name)
        print("  ❌ %s" % name)
        if why:
            print("       %s" % why)


def section(t):
    print()
    print("=" * 68)
    print("  " + t)
    print("=" * 68)


def read(p):
    return io.open(p, encoding="utf-8").read()


def code_only(src):
    """去掉注释（字符串字面量保留）。

    ⚠️ 本文件的 KDoc 原样引用了要被反向断言的字符串（`vocab.base`、
       `immutable`、`invalidate()`），不去注释就会命中自己的说明 —— 假失败。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"' or src[j] == "\n":
                        j += 1
                        break
                    j += 1
            out.append(src[i:j])
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def block(src, marker):
    """Kotlin：从 marker 起取**一整个函数体**（按大括号配平）。

    ⚠️ 不能用「取到第一个顶格 `}` 之前」这种写法：Kotlin 里函数自己的收尾括号
       是**缩进**的，于是那个规则取到的是**类**的收尾 —— 「函数体」变成
       「从那个函数到文件末尾」，把后面的函数全框进来。
       本工程的 `verify-checkin-goal.py` 在这一版之前正是这么写的，
       结果一条坏样本断言**假通过**（它去看的是别的函数）。
       **判据取多了和取少了都不行**：取多了假绿，取少了假红。
    """
    i = src.find(marker)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth, k = 0, j
    while k < len(src):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
        k += 1
    return src[i:]


def pyblock(src, marker):
    """Python：从 marker 起到下一个**顶格**语句之前（缩进即块的边界）"""
    if marker not in src:
        return ""
    out = []
    for line in src.split(marker, 1)[1].split("\n")[1:]:
        if line and not line[0].isspace() and not line.startswith("#"):
            break
        out.append(line)
    return "\n".join(out)


publish_raw = read(os.path.join(BIN, "publish_vocab.py"))
cycle_raw = read(os.path.join(BIN, "cycle.py"))
sync_raw = read(os.path.join(KT, "word/VocabTableSync.kt"))
loader_raw = read(os.path.join(KT, "word/WordTableLoader.kt"))
app_raw = read(os.path.join(KT, "EnglishCommuteApp.kt"))
settings_raw = read(os.path.join(KT, "ui/SettingsScreen.kt"))

sync = code_only(sync_raw)
loader = code_only(loader_raw)
settings = code_only(settings_raw)

conf = "\n".join(
    read(os.path.join(NGINX, f)) for f in sorted(os.listdir(NGINX)) if f.endswith(".conf")
)

#: `/vocab/` 收口那一段的**精确**文本（连缩进一起）。
#: ⚠️ 不写成「找 `return 404` 这个词」：那段注释里就引用了它
#:    （「用 return 404 而不是 deny all」），于是坏样本把代码改坏之后，
#:    注释里的那一处还在 ⇒ 断言以为「没被改」⇒ 坏样本假通过。
NGINX_404 = "location ^~ /vocab/ {\n    return 404;\n}"

# ===========================================================================
section("一、服务端：产物与版本号")
# ===========================================================================

check("publish_vocab.py 存在且可独立跑",
      os.path.isfile(os.path.join(BIN, "publish_vocab.py")))

check("版本号由**三个文件的内容哈希**算出来",
      "def compute_version(" in publish_raw
      and re.search(r"for name, _ in FILES:\s*\n\s*h\.update\(digests\[name\]", publish_raw) is not None,
      "只取某一个文件的哈希的话，「另两个变了」不会改版本号，"
      "客户端会因为「版本没变」跳过下载，拿到一份**混版**词表")

check("  · 三个文件按固定顺序参与（不是集合那种无序结构）",
      "FILES = (" in publish_raw and "vocab.base" in publish_raw
      and "vocab.forms" in publish_raw and "examples.base" in publish_raw)

check("清单里每条 path 都**带版本号**",
      '"%s/table/%s/%s" % (WWW_VOCAB, plan["version"], f["name"])' in publish_raw,
      "★ 路径不带版本号 + nginx 设 immutable ⇒ 缓存过旧词表的客户端永远升不上来，"
      "而且不会自愈（版本号变了、下回来的还是缓存里那份）")

check("  · 清单是 tmp + rename 写的（半截 JSON 会一直挡在中间）",
      "os.replace(tmp, MANIFEST)" in publish_raw,
      "客户端每次同步都会重新拉它，坏清单不会自己好")

check("  · 整包也是「先写 staging 目录、再整体改名」",
      "staging" in publish_raw and "os.replace(staging, target_dir)" in publish_raw,
      "逐个文件改名会留下「目录在、文件缺」的窗口，那一瞬的读取拿到半份词表")

check("  · 内容没变时不动清单（不刷新 releasedAt）",
      "已是最新，无需重发" in publish_raw,
      "releasedAt 回答的是「这份词表是什么时候发的」，不该为了「跑过一次」而改")

check("★ 清理时豁免当前版本",
      "v != keep" in publish_raw,
      "★ 只按 mtime 排序的话，当前版本恰好是最旧那一个时会被删掉 ——"
      "客户端立刻 404，而清单还好好地指着它")

check("  · 保留窗口是具名的",
      "KEEP_VERSIONS = 2" in publish_raw)

check("有 --dry-run 与 --selftest",
      "--dry-run" in publish_raw and "--selftest" in publish_raw,
      "不可逆操作要先能空跑一遍；判断要先能在本地跑一遍")

# 自检真的跑一遍（它只用标准库）
r = subprocess.run([sys.executable, os.path.join(BIN, "publish_vocab.py"), "--selftest"],
                   capture_output=True, text=True)
check("  · selftest 退出码 0", r.returncode == 0, (r.stdout or r.stderr).strip()[-160:])
check("  · selftest 会真的断言「任一个文件变了版本号就要变」",
      "单独变化 → 版本号必须变" in (r.stdout or ""))

# ===========================================================================
section("二、挂进 cycle：位置与失败语义")
# ===========================================================================

rv = pyblock(cycle_raw, "def refresh_vocab():")

check("refresh_vocab 里会发布整包",
      "publish_vocab_pkg()" in rv)

check("★ 排在两个 build 之后", 
      rv.find('for script in ("build_vocab.py", "build_vocab_add.py")') >= 0
      and rv.find("publish_vocab_pkg()") > rv.rfind("for script in"),
      "★ 它发布的就是那两个脚本刚写出来的三个文件。放前面会发布**上一轮**的产物，"
      "而且看不出来（版本号照算、清单照写）")

check("失败只记一行、不往上抛",
      "词表包发布失败（发布本身不受影响）" in cycle_raw,
      "词库是支线，素材发布是主线 —— 拿主线赔支线是本工程记过账的错法")

check("  · 超时也兜住了",
      "词表包发布超时（发布本身不受影响）" in cycle_raw)

check("  · cycle.log 里只留结论那一行",
      's.startswith("✅")' in cycle_raw,
      "逐文件明细属于脚本自己的输出，粘进每轮日志只会把它撑长")

# ===========================================================================
section("三、分发：nginx 三块 location")
# ===========================================================================

check("`/vocab/table.json` 设了 no-store",
      "location = /vocab/table.json" in conf and "no-store" in conf,
      "客户端每次点「更新词库」都先来问它；缓存住就永远看不到新版本号")

check("`^~ /vocab/table/` 设了长缓存 + immutable",
      "location ^~ /vocab/table/" in conf and "immutable" in conf,
      "路径里带版本号，内容变了就是换了一个 URL ⇒ 可以放心 immutable")

check("  · 而且带 CORS 头",
      "Access-Control-Allow-Origin" in conf)

check("★ 收口没被打开：`^~ /vocab/` 仍然 return 404",
      NGINX_404 in conf,
      "★ 放行整包时最容易顺手写一条宽松的前缀规则，那会把 3.1.0 收掉的"
      "服务端中间产物（发布账本、抽样文件）重新暴露出去")

check("  · 且只用前缀 + 精确匹配，没有把 /vocab/ 改成放行",
      "location ^~ /vocab/ {" in conf and "try_files" not in conf.split("location ^~ /vocab/ {")[1].split("}")[0],
      "nginx 取**最长前缀**：/vocab/table/ 比 /vocab/ 长，所以前者优先 ——"
      "不需要、也不该把后者放开")

# ===========================================================================
section("四、客户端：三条判据落在正确的量上")
# ===========================================================================

td = block(sync, "fun tableDir():")
check("「装好了没有」判据落在**三个文件**上",
      "TABLE_FILES.all" in td and "isFile" in td and "length() > 0L" in td,
      "★ 判据落在「清单在不在」上的话，一次「写完清单又被清目录」就会让词表"
      "去加载一个不存在的目录，症状是「查不到词」—— 方向完全指错")

check("  · 文件不全时退回内置（不是抛）",
      "退回内置" in sync and "return null" in td,
      "词库坏了不该把查词整个打掉；内置那份永远在")

check("「下完了没有」判据落在 **sha256** 上",
      "actual != expectSha" in sync and "MessageDigest" in sync,
      "2.4 MB 的跨境下载被截断是常态；按长度判会被长度恰好对上的截断骗过")

check("  · 校验发生在**改名之前**",
      sync.find("actual != expectSha") < sync.find("staging.renameTo(target)"),
      "改完名再校验的话，盘上就已经有一份坏词表了，而它看起来是好的")

check("切换是**整目录 rename**（原子的）",
      "staging.renameTo(target)" in sync and ".staging" in sync,
      "逐个文件改名会留下「目录在、文件缺」的窗口")

check("  · rename 失败有兜底（复制完再删）",
      "copyRecursively" in sync)

check("清单读不出来时退回内置，而不是崩",
      "词表清单读不出来" in sync and "return null" in block(sync, "private fun readActive("))

check("★ 更新成功后返回 Updated，让调用方去失效缓存",
      "VocabTableResult.Updated(version, manifest.optInt(\"baseCount\"))" in sync,
      "★ 这里必须把「要失效」这件事交给调用方 —— 本类不知道谁在缓存解析结果")

check("  · 反向：本类不去碰 WordTableLoader（职责不越界）",
      "WordTableLoader" not in sync and "invalidate" not in sync,
      "词表包只管「把文件放到该在的地方」；谁来重新解析是调用方的事。"
      "越界的话，以后换加载方式要改两个地方")

# ===========================================================================
section("五、接线：加载器优先级、容器顺序、设置页")
# ===========================================================================

check("加载器**优先**用整包、没有才用内置",
      "if (dir != null) {" in loader and "File(dir, \"vocab.base\")" in loader
      and "assets.open(FILE_BASE)" in loader,
      "写成「两个都查」的话，等于把 4 MB 的表解析两遍还合并一次")

check("  · 判据是「目录存在」，来自 sourceDir() 一处",
      "private fun sourceDir()" in loader,
      "「读哪个目录」和「日志说读的是哪个」分两处各算一次就会分叉，"
      "而那种日志比没有日志更坏")

check("  · 日志报出**来源**（内置 / 整包）",
      "词表已加载（$source）" in loader,
      "3.3.0 起同一份词表有两个出处，而「查不到某个词」的下一步完全取决于"
      "现在是哪一份在生效 —— 只报数量的话两句日志一模一样")

check("  · 整包是**注入**的（不在加载器里拼路径）",
      "private val vocabTable: VocabTableSync? = null" in loader
      and "File(context.filesDir" not in loader)

check("容器里 vocabTableSync 声明在 wordTable **之前**",
      app_raw.find("val vocabTableSync") >= 0
      and app_raw.find("val vocabTableSync") < app_raw.find("val wordTable"),
      "Kotlin 属性按声明顺序初始化，反过来写会直接编译失败 —— 这是好事")

check("设置页有独立的「词库」块",
      'Block("词库")' in settings_raw or 'Block("词库")' in settings)

check("★ 更新成功后让词表缓存失效",
      "app.wordTable.invalidate()" in settings,
      "★ 不失效的话，这一次更新在**本进程里什么都不会发生**：界面说已更新，"
      "用户再查那个词依然查不到 —— 症状和没更新一模一样，且不自愈")

check("  · 失效发生在读取新版本号**之前/同时**",
      settings.find("invalidate()") < settings.find("version = app.vocabTableSync.localVersion()"),
      "先读版本再失效，会有一瞬间「界面说新版、内存里是旧版」")

check("界面报出词数（否则更新成功看不出来）",
      '"已更新 · $words 词' in settings_raw,
      "词库更新没有别的可见效果（它只影响「查得到查不到某个词」，是概率性的）——"
      "词数变多就是这次更新的产出")

check("失败时给的是**下一步**，不是后端那句话",
      "等网络好一点再试" in settings_raw and "服务器上还没准备好" in settings_raw,
      "本工程的文案纪律：每种失败都要能给出下一步")

# ===========================================================================
section("六、文案：不许出现黑名单词")
# ===========================================================================
# 借用 verify-copy-audit.py 的口径。这里只查**这一个**功能的用户可见字符串，
# 因为它最容易写成构建脚本那一层的说法。
BLACKLIST_HERE = ("词表", "缓存", "assets", "断点续传", "校验和", "哈希")

hits = []
for m in re.finditer(r'"([^"\n]{2,})"', settings):
    body = m.group(1)
    for w in BLACKLIST_HERE:
        if w in body:
            hits.append((w, body))
            break
check("设置页里这一块没有开发者话术（词表 / 缓存 / assets …）",
      not hits,
      "命中：%s —— 一律说「词库」，那是用户脑子里的东西" % (hits[:3],))

check("  · 用户可见文案里说的是「词库」",
      "词库" in settings_raw)

# ===========================================================================
section("七、坏样本")
# ===========================================================================

bad_path = publish_raw.replace(
    '"path": "%s/table/%s/%s" % (WWW_VOCAB, plan["version"], f["name"]),',
    '"path": "%s/%s" % (WWW_VOCAB, f["name"]),',
    1,
)
check("坏样本 1（清单路径不带版本号）被拦下",
      "/table/%s/%s" in publish_raw and "/table/%s/%s" not in bad_path,
      "带 immutable 缓存的话，客户端会永远拿到旧词表且不自愈")

bad_keep = publish_raw.replace(
    "    doomed = [v for v in versions if v != keep][: max(0, len(versions) - KEEP_VERSIONS)]",
    "    doomed = versions[: max(0, len(versions) - KEEP_VERSIONS)]",
    1,
)
check("坏样本 2（清理不豁免当前版本）被拦下",
      bad_keep != publish_raw
      and "v != keep" in pyblock(publish_raw, "def prune(")
      and "v != keep" not in pyblock(bad_keep, "def prune("),
      "会把正在服务的那一份删掉：客户端 404，而清单还指着它")

bad_dir = sync_raw.replace("val ok = TABLE_FILES.all", "val ok = localVersion() != null", 1)
check("坏样本 3（「装好了」只看清单在不在）被拦下",
      bad_dir != sync_raw
      and "TABLE_FILES.all" in td
      and "TABLE_FILES.all" not in block(code_only(bad_dir), "fun tableDir():"),
      "一次「写完清单又被清目录」就会让词表去加载不存在的目录")

bad_invalidate = settings_raw.replace("app.wordTable.invalidate()", "", 1)
check("坏样本 4（更新完不失效缓存）被拦下",
      "app.wordTable.invalidate()" in settings
      and "app.wordTable.invalidate()" not in code_only(bad_invalidate),
      "★ 这一次更新在本进程里什么都不会发生，而界面已经说「已更新」")

bad_open = conf.replace(NGINX_404, "location ^~ /vocab/ {\n    try_files $uri =404;\n}", 1)
check("坏样本 5（把 /vocab/ 收口放开）被拦下",
      bad_open != conf and NGINX_404 not in bad_open,
      "放行整包时顺手写宽松规则，会把 3.1.0 收掉的中间产物重新暴露")

# ===========================================================================
print()
print("=" * 68)
print("  通过 %d 项，失败 %d 项" % (PASS, FAIL))
print("=" * 68)
if FAIL:
    print()
    print("  ❌ 未通过：")
    for f in FAILURES:
        print("     - " + f)
print()
sys.exit(1 if FAIL else 0)
