#!/usr/bin/env python3
"""一次性迁移：给 state.json 里已有的条目补上 2.0 客户端需要的两个字段。

- `guid`：客户端算 id 的依据（必须与 1.0 已落盘的 id 一致，否则升级后同一期
  内容会出现两条、收藏与播放进度也会断）。
- `artworkUrl`：封面。1.0 客户端自己解析 RSS 拿这个，2.0 客户端不再解析 RSS，
  所以必须由服务端转达；缺了它素材库与播放页的封面全变占位色块。

guid 不可逆推（state 里只有 `sha1(guid)` 前 16 位 + 末 24 个字母数字），
所以做法是**把 11 个源重新拉一遍**，对每条算 `ep_id(guid)` 反查回填。

⚠️ 一个已知的不可恢复情形：**只保留少量条目的源**（NPR 整点新闻只留 4 条），
   条目滚出去之后 guid 就找不回来了。那种条目客户端只能退回用服务端 id，
   代价是可能与 1.0 的同一条并存几天（等保留期清掉）。
   —— 这条是「事后补字段」的固有代价；从这版起 guid 在**入库时**就落盘，不会再发生。
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import transcribe as T          # noqa: E402
from cycle import ep_id, load_json, read_feeds, save_json, STATE   # noqa: E402


def main():
    feeds = read_feeds()
    print(f"拉取 {len(feeds)} 个源，建立 ep_id → (guid, 封面) 索引 …")
    index, feed_art = {}, {}
    for fid, url in feeds:
        try:
            # ⚠️ 原样把 http_get 的返回交给 parse_feed —— 它内部自己解码。
            #    先 decode 成 str 再传会报 `'str' object has no attribute 'decode'`。
            items = T.parse_feed(T.http_get(url), fid)
        except Exception as e:
            print(f"  ⚠️ {fid:<22} 拉取失败：{type(e).__name__}: {e}")
            continue
        for it in items:
            g = it.get("guid")
            if g:
                index[ep_id(g)] = it
        # 频道封面按 feedId 兜底（条目自带的极少，多数是频道级）
        art = next((it.get("artworkUrl") for it in items
                    if it.get("feedId") == fid and it.get("artworkUrl")), None)
        if art:
            feed_art[fid] = art
        print(f"  {fid:<22} 条目 {len(items):4d}  频道封面 {'有' if art else '无'}")

    print(f"\n索引 {len(index)} 条 · 有封面的源 {len(feed_art)} 个")

    state = load_json(STATE, {"episodes": {}})
    eps = state.get("episodes", {})
    g_ok = g_miss = a_ok = a_miss = 0
    for eid, e in eps.items():
        hit = index.get(eid)
        if not e.get("guid"):
            if hit:
                e["guid"] = hit["guid"]
                g_ok += 1
            else:
                g_miss += 1
                print(f"  ❌ 找不到 guid：{eid}  {str(e.get('title'))[:46]}")
        if not e.get("artworkUrl"):
            art = (hit or {}).get("artworkUrl") or feed_art.get(e.get("feedId") or "")
            if art:
                e["artworkUrl"] = art
                a_ok += 1
            else:
                a_miss += 1

    if g_ok or a_ok:
        save_json(STATE, state)
    print(f"\nguid：补上 {g_ok} 条，仍缺 {g_miss} 条")
    print(f"封面：补上 {a_ok} 条，仍缺 {a_miss} 条")
    print("已写回 state.json" if (g_ok or a_ok) else "无需写回")
    return 0


if __name__ == "__main__":
    sys.exit(main())
