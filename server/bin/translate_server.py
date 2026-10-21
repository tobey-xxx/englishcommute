#!/usr/bin/env python3
"""服务端英译中：opus-mt-en-zh（MarianMT）ONNX + onnxruntime。

这是 App 端 `translate/TranslateEngine.kt` + `translate/Tokenizer.kt` 的 **1:1 移植**。
默认用 **fp32**（`--set fp32`）：实测它比 App 那份 int8 **又快又准**
（int8 因为 decoder 的 QDQ 崩溃被迫用 BASIC_OPT，而且会丢掉否定语义）。
`--set int8` 可切回 App 那份（译文与端侧一致），留作内存吃紧时的兜底。

## 三个「不报错但会错」的坑（从 Kotlin 带过来）

1. **KV cache 接线**：merged decoder 的 `with_past` 分支输出的 **encoder KV 是占位垃圾**
   （(1,8,L,64) 退化成 (0,8,1,64)）。所以 **encoder 的 KV 只取第一轮**，`.decoder.` 取上一轮。
2. **`use_cache_branch` 第一轮必须 False**，传反了会拿空 past 算 cross-attention。
3. **int8 的 decoder 只能用 BASIC 优化级别**：它顶层只有一个 `If`，计算在 subgraph 里，
   ORT 的 QDQ 优化器（EXTENDED/ALL 才启用）处理 subgraph 内的 `MatMulNBits` 会崩。
   fp32 没有这个问题 → 可以用 ALL，快一倍以上。

用法：
    python translate_server.py --srt <file.srt> --n 14
    python translate_server.py --set fp32 --opt all --srt <file.srt> --n 14
"""

import argparse
import re
import struct
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

PAD_ID = 65000          # config 里 bad_words_ids=[[65000]]，pad 永远不该被生成
EOS_ID = 0              # Marian 约定
UNK_ID = 1
MAX_SRC_TOKENS = 500    # 位置编码上限 512，留出 eos 余量
MAX_NEW_TOKENS = 220
NEG_INF = -1e30
UNK_SCORE = -100.0

# 两套模型。int8 = App 端那份（译文与端侧一致）；fp32 = 服务端专用（更好也更快）
# 三套模型的实测对照（同一台机器、同样 5 句，2026-09-21）
#
#   模型   大小    优化    速度       峰值内存    质量
#   int8   108MB   BASIC   321 ms/句  556 MB      否定丢失（"做了…希望他做的事"，意思反了）
#   fp32   425MB   ALL     217 ms/句  1030 MB     正确 ✅
#   fp16   212MB   ALL     629 ms/句  1320 MB     正确（但最慢最占内存）
#
# → **默认 fp32**：又快又对。
#   ⚠️ fp16 在 CPU 上是「两头最差」：onnxruntime 的 CPU EP 没有 fp16 算子，
#      只能现场 Cast 成 fp32 再算 —— 权重之外还要多留一份 fp32 副本，
#      于是内存反而最高、速度最慢。**别以为 fp16 一定更省。**
#   ⚠️ int8 之所以慢，不是量化本身，而是它的 decoder 只能加载 BASIC 优化级别
#      （ALL/EXTENDED 会让 QDQ 优化器崩在 subgraph 的 MatMulNBits 上）。
#   int8 保留作为**内存兜底**：万一以后内存吃紧，切回去能省近一半内存。
MODEL_SETS = {
    "fp32": {"dir": "/opt/ec/models/translate-fp32",
             "encoder": "encoder_fp32.onnx", "decoder": "decoder_merged_fp32.onnx",
             "vocab": "/opt/ec/models/translate/vocab.bin", "opt": "all"},
    "int8": {"dir": "/opt/ec/models/translate",
             "encoder": "encoder_int8.onnx", "decoder": "decoder_merged_int8.onnx",
             "vocab": "vocab.bin", "opt": "basic"},
}
DEFAULT_SET = "fp32"

# 模型根目录。`--models-root` 可覆盖 —— 用于两件事：
#   ① 在本地用 App assets 里那份 int8 验管线（不必等服务器）；
#   ② 把整套模型搬到别的机器。
# 覆盖规则：`dir` 换根、`vocab` 若已是绝对路径则「父目录名 + 文件名」跟着换根，
# 这样 fp32 复用 int8 目录里的那份 vocab.bin 也能跟着走。
DEFAULT_MODELS_ROOT = "/opt/ec/models"


def resolve_set(model_set, root=None):
    m = dict(MODEL_SETS[model_set])
    if not root:
        return m
    root = Path(root)
    m["dir"] = str(root / Path(m["dir"]).name)
    v = Path(m["vocab"])
    if v.is_absolute():
        m["vocab"] = str(root / v.parent.name / v.name)
    return m

OPT_LEVELS = {
    "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
    "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
    "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
    "no": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
}


# ---------------------------------------------------------------- 词表 + 分词
class Vocab:
    """格式（由桌面脚本从 tokenizer.json 压出）：
        magic "MTV1" | count uint32 LE | 之后 count 条按 id 升序：score f32, len u16, bytes
    ⚠️ 权威分词表就是它。仓库里的 source.spm/target.spm 是另一套 32000 词的表，
       用了不报错，只是译文全变成高频垃圾。
    """

    def __init__(self, path):
        b = Path(path).read_bytes()
        if b[:4] != b"MTV1":
            raise ValueError("词表魔数不对，文件可能不完整")
        count = struct.unpack_from("<I", b, 4)[0]
        off = 8
        pieces, scores = [], []
        for _ in range(count):
            scores.append(struct.unpack_from("<f", b, off)[0]); off += 4
            ln = struct.unpack_from("<H", b, off)[0]; off += 2
            pieces.append(b[off:off + ln].decode("utf-8")); off += ln
        self.pieces, self.scores, self.size = pieces, scores, count


class UnigramTokenizer:
    def __init__(self, vocab: Vocab):
        self.vocab = vocab
        self.piece_to_id = {p: i for i, p in enumerate(vocab.pieces)}
        self.max_chars = max((len(p) for p in vocab.pieces), default=1)

    def encode(self, text, with_eos=True):
        out = []
        for word in re.split(r"\s+", text):
            if not word:
                continue
            self._viterbi("▁" + word, out)
        if with_eos:
            out.append(EOS_ID)
        return out

    def _viterbi(self, word, out):
        n = len(word)
        best = [NEG_INF] * (n + 1)
        back = [0] * (n + 1)
        piece_at = [None] * (n + 1)
        best[0] = 0.0
        for i in range(1, n + 1):
            lo = max(0, i - self.max_chars)
            for j in range(lo, i):
                if best[j] == NEG_INF:
                    continue
                pid = self.piece_to_id.get(word[j:i])
                if pid is None or pid in (EOS_ID, UNK_ID):
                    continue    # 特殊 token 不参与匹配，否则字面量 "</s>" 会被切出来
                s = best[j] + self.vocab.scores[pid]
                if s > best[i]:
                    best[i], back[i], piece_at[i] = s, j, word[j:i]
            # unk 兜底：允许单字符硬闯。少了这步，一个表外字符会让整个词变 <unk>
            if best[i - 1] + UNK_SCORE > best[i]:
                best[i], back[i], piece_at[i] = best[i - 1] + UNK_SCORE, i - 1, None
        ids, pieces = [], []
        i = n
        while i > 0:
            ids.append(0); pieces.append(piece_at[i]); i = back[i]
        for k in range(len(ids) - 1, -1, -1):
            p = pieces[k]
            if p is None:
                if out and out[-1] == UNK_ID:
                    continue
                out.append(UNK_ID)
            else:
                out.append(self.piece_to_id.get(p, UNK_ID))

    def decode(self, ids):
        sb = [self.vocab.pieces[i] for i in ids
              if i not in (EOS_ID, UNK_ID) and 0 <= i < self.vocab.size]
        return "".join(sb).replace("▁", " ").strip()


# ---------------------------------------------------------------- 推理引擎
class Translator:
    def __init__(self, model_set="int8", threads=2, opt=None, models_root=None,
                 beams=4):
        m = resolve_set(model_set, models_root)
        want = opt or m["opt"]
        enc_p, dec_p = Path(m["dir"]) / m["encoder"], Path(m["dir"]) / m["decoder"]
        vk = Path(m["vocab"])
        vocab_p = vk if vk.is_absolute() else Path(m["dir"]) / vk

        # 优化级别阶梯：先试想要的，不行往下退。
        # int8 的 decoder 在 ALL/EXTENDED 上会崩（QDQ + subgraph MatMulNBits）
        ladder = ["all", "extended", "basic", "no"]
        ladder = ladder[ladder.index(want):] if want in ladder else ladder

        dec = enc = None
        self.opt_used = None
        last_err = None
        for lvl in ladder:
            so = ort.SessionOptions()
            so.intra_op_num_threads = threads
            so.inter_op_num_threads = 1
            so.graph_optimization_level = OPT_LEVELS[lvl]
            try:
                t0 = time.monotonic()
                dec = ort.InferenceSession(str(dec_p), so,
                                           providers=["CPUExecutionProvider"])
                so2 = ort.SessionOptions()
                so2.intra_op_num_threads = threads
                so2.inter_op_num_threads = 1
                so2.graph_optimization_level = OPT_LEVELS[lvl]
                enc = ort.InferenceSession(str(enc_p), so2,
                                           providers=["CPUExecutionProvider"])
                self.opt_used = lvl
                break
            except Exception as e:
                last_err = e
                dec = enc = None
        if dec is None:
            raise RuntimeError(f"模型加载失败（试过 {ladder}）：{last_err}")
        self.load_sec = time.monotonic() - t0
        self.decoder, self.encoder = dec, enc
        self.tok = UnigramTokenizer(Vocab(vocab_p))
        self.set_name = model_set
        # 模型 generation_config 里就是 num_beams=4。给 1 可退回贪心（用于对照）
        self.beams = max(1, int(beams))

        self.past_names = [i.name for i in dec.get_inputs()
                           if i.name.startswith("past_key_values.")]
        if not self.past_names:
            raise RuntimeError("解码器缺少 KV cache 输入，模型文件可能不对")
        self.present_names = sorted(o.name for o in dec.get_outputs() if o.name != "logits")
        shape = next(i.shape for i in dec.get_inputs() if i.name == self.past_names[0])
        self.heads = shape[1] if isinstance(shape[1], int) and shape[1] > 0 else 8
        self.head_dim = shape[3] if isinstance(shape[3], int) and shape[3] > 0 else 64
        ls = next(o.shape for o in dec.get_outputs() if o.name == "logits")
        self.vocab_size = ls[-1] if isinstance(ls[-1], int) else 65001

    @staticmethod
    def _present_of(past_name):
        return past_name.replace("past_key_values.", "present.", 1)

    def translate(self, text, max_new=MAX_NEW_TOKENS):
        """按 [self.beams] 选择解码方式。beams>1 时走 beam search（模型的原设计）。"""
        if self.beams > 1:
            return self.translate_beam(text, self.beams, max_new)
        return self.translate_greedy(text, max_new)

    def translate_greedy(self, text, max_new=MAX_NEW_TOKENS):
        ids = self.tok.encode(text)
        if not ids:
            return "", 0
        if len(ids) > MAX_SRC_TOKENS:
            ids = ids[:MAX_SRC_TOKENS]
        ids[-1] = EOS_ID
        attn = np.ones((1, len(ids)), dtype=np.int64)
        src = np.array([ids], dtype=np.int64)

        hidden = self.encoder.run(["last_hidden_state"],
                                  {"input_ids": src, "attention_mask": attn})[0]
        out, input_id, use_cache = [], PAD_ID, False
        holder, prev = {}, {}
        empty = {n: np.zeros((1, self.heads, 0, self.head_dim), dtype=np.float32)
                 for n in self.past_names}
        n_round = 0
        while len(out) < max_new:
            feed = {
                "input_ids": np.array([[input_id]], dtype=np.int64),
                "encoder_attention_mask": attn,
                "use_cache_branch": np.array([use_cache], dtype=bool),
                "encoder_hidden_states": hidden,
            }
            for name in self.past_names:
                if use_cache:
                    src_map = holder if ".encoder." in name else prev
                    feed[name] = src_map.get(self._present_of(name), empty[name])
                else:
                    feed[name] = empty[name]
            vals = self.decoder.run(["logits"] + self.present_names, feed)
            present = dict(zip(["logits"] + self.present_names, vals))
            if n_round == 0:
                holder = {k: v for k, v in present.items() if k != "logits"}
            prev = {k: v for k, v in present.items() if k != "logits"}
            n_round += 1
            logits = present["logits"][0, -1, :]
            logits[PAD_ID] = -np.inf          # bad_words_ids = [[65000]]
            nxt = int(np.argmax(logits))
            if nxt == EOS_ID:
                break
            out.append(nxt)
            input_id = nxt
            use_cache = True
        return self.tok.decode(out), n_round

    def translate_beam(self, text, num_beams=4, max_new=MAX_NEW_TOKENS,
                       length_penalty=1.0):
        """束搜索解码（`num_beams=4`）——**这才是这个模型的原设计**。

        ## 为什么必须补这一步

        `Xenova/opus-mt-en-zh` 的 `generation_config.json` 写着
        `num_beams: 4` / `renormalize_logits: true`，即官方就是按 4 束搜出来的。
        而端侧（以及本文件最初的移植版）用的是**贪心 argmax**，那是 beam=1 的特例。

        后果不是「差一点」，而是**静默丢内容**。实测（fp32、同一台机器）：

            输入(116 字符) The war with Iran … when President Trump addresses the UN General Assembly.
            贪心 → 「与伊朗的战争预计将是本周主要焦点,」   ← 后半句整段没了，也不报错
            同一模型换 4 束 → 见实测输出

        这类「输出比输入少一半却读起来通顺」的失败最危险：用户不会发现。

        ## 实现要点（都是会静默出错的坑）

        1. **所有束共享同一份 encoder_hidden_states**（复制到 batch 维），
           但**自注意力的 KV 必须逐束独立** —— 它随各束选出的 token 而分化。
        2. **第一轮 `use_cache_branch=False`**（与贪心同理），此后恒为 True。
        3. **已结束的束不参与扩展**，但要**留在池子里冻结** —— 否则 batch 维会缩水，
           而 KV 是按 batch 维对齐的。它们继续喂 PAD，输出直接丢弃。
        4. **长度归一化**：分数用 `Σlogp / len^penalty`。不归一化会系统性偏短 ——
           而「偏短」正是要修的毛病，等于把 bug 修成另一个 bug。
        """
        V = self.vocab_size
        ids = self.tok.encode(text)
        if not ids:
            return "", 0
        if len(ids) > MAX_SRC_TOKENS:
            ids = ids[:MAX_SRC_TOKENS]
        ids[-1] = EOS_ID
        attn = np.ones((1, len(ids)), dtype=np.int64)
        hidden = self.encoder.run(
            ["last_hidden_state"],
            {"input_ids": np.array([ids], dtype=np.int64), "attention_mask": attn})[0]

        B = num_beams
        hid_b = np.repeat(hidden, B, axis=0)          # 坑 1：cross-attn 要每束一份
        attn_b = np.repeat(attn, B, axis=0)
        empty = {n: np.zeros((B, self.heads, 0, self.head_dim), dtype=np.float32)
                 for n in self.past_names}

        tok_buf = [[] for _ in range(B)]
        scores = np.full(B, -1e30, dtype=np.float32)
        scores[0] = 0.0                                # 只有第一束起步，其余靠 logp 竞争
        done = np.zeros(B, dtype=bool)
        cur = np.full((B, 1), PAD_ID, dtype=np.int64)  # 本步要喂的 token
        past, enc_past = None, {}
        n_round = 0

        for _step in range(max_new):
            feed = {
                "input_ids": cur[:, -1:],
                "encoder_attention_mask": attn_b,
                # ⚠️ `use_cache_branch` 是 merged decoder 里那个 `If` 节点的**标量条件**，
                # 形状固定为 [1]（javap 式核对过 ONNX 输入形状），**不能按 batch 传**。
                # 传 [True]*B 会直接 `Got: 4 Expected: 1` 报错。
                # 语义上也对：所有束同一时刻处于同一阶段。
                "use_cache_branch": np.array([past is not None], dtype=bool),
                "encoder_hidden_states": hid_b,
            }
            for name in self.past_names:
                key = self._present_of(name)
                if past is None:
                    feed[name] = empty[name]
                else:
                    feed[name] = (enc_past if ".encoder." in name else past)[key]
            vals = self.decoder.run(["logits"] + self.present_names, feed)
            out = dict(zip(["logits"] + self.present_names, vals))
            n_round += 1
            if past is None:
                enc_past = {k: v for k, v in out.items() if k != "logits"}
            past = {k: v for k, v in out.items() if k != "logits"}

            logits = out["logits"][:, -1, :].astype(np.float32)   # (B, V)
            logits[:, PAD_ID] = -np.inf                          # bad_words_ids
            mx = logits.max(axis=-1, keepdims=True)
            logz = np.log(np.exp(logits - mx).sum(axis=-1, keepdims=True))
            logp = logits - mx - logz                            # renormalize_logits

            cand = logp + scores[:, None]                        # (B, V)
            cand[done, :] = -np.inf                              # 坑 3：冻结的不扩展
            flat = cand.reshape(-1)
            k = min(2 * B, flat.size)
            top = np.argpartition(-flat, k - 1)[:k]
            top = top[np.argsort(-flat[top], kind="stable")]

            keep = [i for i in range(B) if done[i]]
            slots = B - len(keep)
            picked = []
            for idx in top:
                b, tok = divmod(int(idx), V)
                if done[b]:
                    continue
                picked.append((b, tok, float(flat[idx])))
                if len(picked) >= slots:
                    break
            if not picked:                                       # 全部结束
                break

            order = keep + [b for b, _t, _s in picked]
            new_scores = np.empty(B, dtype=np.float32)
            new_done = np.zeros(B, dtype=bool)
            new_cur = np.empty((B, 1), dtype=np.int64)
            # ⚠️ 必须**读旧表、写新表**。就地改 `tok_buf` 会出错：
            #    同一个父束的两个候选（如 picked = [(0,5),(0,7)]）里，
            #    第二个候选读 `tok_buf[0]` 时，它已被第一个候选写成「加了 5」的新值，
            #    于是历史被污染 —— 现象是**输出里整段重复**
            #    （实测「我是戴夫Dave Ma廷利廷利」「会面开会开会」）。
            #    这个 bug 不报错，只是译文变得又长又重复。
            new_tok = [None] * B
            for i, b in enumerate(keep):                         # 冻结的照抄
                new_scores[i] = scores[b]
                new_done[i] = True
                new_cur[i, 0] = PAD_ID                           # 喂什么都行，输出丢弃
                new_tok[i] = tok_buf[b]
            for j, (b, tok, sc) in enumerate(picked):
                i = len(keep) + j
                new_scores[i] = sc
                new_cur[i, 0] = tok
                if tok == EOS_ID:
                    new_done[i] = True
                    new_tok[i] = tok_buf[b]                      # EOS 不进历史
                else:
                    new_tok[i] = tok_buf[b] + [tok]
            tok_buf = new_tok
            # KV 跟着束重排。
            # ⚠️ 只能重排**真的按 batch 排过的**那些 —— merged decoder 在 `with_past`
            #    分支输出的 encoder KV 是**占位垃圾**，形状退化成 (0,8,1,64)，
            #    batch 维是 0。贪心路径从不索引它所以看不见；这里照着重排会直接
            #    `index 0 is out of bounds for axis 0 with size 0`。
            #    （语义上那些也不该用：encoder KV 只取第一轮，见类注释第 1 条。）
            past = {n: (v[order] if v.shape[0] == B else v) for n, v in past.items()}
            enc_past = {n: (v[order] if v.shape[0] == B else v)
                        for n, v in enc_past.items()}
            scores, done, cur = new_scores, new_done, new_cur
            if done.all():
                break

        def norm(i):
            n = max(1, len(tok_buf[i]))
            return scores[i] / (n ** length_penalty)

        best = max(range(B), key=norm)
        return self.tok.decode(tok_buf[best]), n_round


# ---------------------------------------------------------------- 评测
def parse_srt(path):
    txt = Path(path).read_text(encoding="utf-8")
    out = []
    for blk in re.split(r"\n\s*\n", txt.strip()):
        lines = [l for l in blk.split("\n") if l.strip()]
        if len(lines) >= 3:
            t = " ".join(lines[2:]).strip()
            if t:
                out.append(t)
    return out


SAMPLES = [
    "President Trump's hand-picked Fed Chair did the opposite of what Trump wanted him to do.",
    "For five months now, people's paychecks have been losing their buying power.",
    "Walsh hopes the stable prices will help families keep their heads above water.",
    "As Steve Castenbaum reports, Trump and the mayor are scheduled to get together today.",
    "in the state of Saxony, the party has ended up with its worst-ever results since World War II",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default=DEFAULT_SET, choices=list(MODEL_SETS))
    ap.add_argument("--opt", default=None, choices=list(OPT_LEVELS))
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--beams", type=int, default=4,
                    help="束数。模型原设计是 4；传 1 = 贪心（用于对照）")
    ap.add_argument("--models-root", default=None,
                    help=f"模型根目录，默认 {DEFAULT_MODELS_ROOT}")
    ap.add_argument("--srt", help="从这份 SRT 里等距抽句子")
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--only-fixed", action="store_true", help="只跑固定样例（便于对照）")
    args = ap.parse_args()

    t = Translator(args.set, args.threads, args.opt, args.models_root, args.beams)
    print(f"模型集 {t.set_name} | 优化级别 {t.opt_used.upper()} | 束 {t.beams} | "
          f"加载 {t.load_sec:.1f}s | "
          f"heads={t.heads} head_dim={t.head_dim} vocab={t.vocab_size} "
          f"KV={len(t.past_names)}", flush=True)

    probe = "The Fed also has no say on tariff policy."
    rt = t.tok.decode(t.tok.encode(probe, with_eos=False))
    print(f"分词往返自检: {'✅ 一致' if rt.strip() == probe else '❌ ' + rt}\n", flush=True)

    if args.only_fixed or not args.srt:
        items = SAMPLES
    else:
        s = parse_srt(args.srt)
        step = max(1, len(s) // args.n)
        items = s[::step][:args.n]

    total = 0.0
    for i, en in enumerate(items, 1):
        t0 = time.monotonic()
        zh, rounds = t.translate(en)
        dt = time.monotonic() - t0
        total += dt
        print(f"{i:2d}. EN  {en}")
        print(f"    ZH  {zh}")
        print(f"        （{dt * 1000:.0f} ms / {rounds} 轮）\n", flush=True)
    print(f"==> {t.set_name}/{t.opt_used}: 平均 {total / len(items) * 1000:.0f} ms/句")


if __name__ == "__main__":
    main()
