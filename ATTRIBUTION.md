# 第三方资源署名（Attribution）

本仓库的**代码**以 MIT 许可发布（见 [`LICENSE`](LICENSE)）。
以下是随仓库分发的**第三方数据与代码**，它们各自保留原有许可，本项目在此署名。

---

## 1. ECDICT — 词库（词形 / 音标 / 释义 / 词频）

- **用于**：`app/src/main/assets/vocab/vocab.base`、`vocab.forms`
- **来源**：https://github.com/skywind3000/ECDICT
- **许可**：MIT License
- **版权**：Copyright (c) skywind3000 及 ECDICT 贡献者

> 本项目对原始数据做了格式转换（制表符分隔的定长字段、去重、按词频排序），
> 未改变释义内容。

## 2. Tatoeba — 例句与译文

- **用于**：`app/src/main/assets/vocab/examples.base`
- **来源**：https://tatoeba.org （英文原句 + 中文译文对）
- **许可**：CC BY 2.0 FR（https://creativecommons.org/licenses/by/2.0/fr/）
- **署名要求**：使用时须注明来源于 Tatoeba 并保留本署名

> 本项目按「词形 → 例句」做了索引化与截断（每条词最多保留少量例句），
> 未修改句子内容。

## 3. py-fsrs — FSRS 算法参考实现

- **用于**：`tools/fsrs-reference/`；`app/src/main/java/.../word/Fsrs.kt` 是它的 Kotlin 移植
- **来源**：https://github.com/open-spaced-repetition/py-fsrs
- **许可**：MIT License
- **版权**：Copyright (c) Open Spaced Repetition

> 移植版与原实现的一致性由 `FsrsTest` 的向量对照保证（见该测试的注释）。

## 4. 播客内容（**不在本仓库内**）

音频、封面、字幕原文均由使用者在自己的服务器上抓取，**不随仓库分发**。
版权归各内容源（NPR / BBC / CBC / WSJ / Al Jazeera / TED 等）所有。
本项目的 `server/` 只是抓取与转写工具 —— 对外提供服务属于再分发内容，
需自行确认各源条款。

---

如你是上述任一资源的权利人，认为本项目的使用方式不当，请开 issue 联系。
