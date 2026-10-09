---
name: memory-keeper
description: 维护 Eta 的持久记忆：工作记忆 MEMORY.md 的条目池（`- (重要度 1-5) 事实`，每轮按有效重要度 EI 排序注入）与用户画像 USER.md 的垃圾回收，档案 memory/files/*.md 的拆分与合并，以及分级召回过去的事实（热记忆 → 档案 → 会话历史 → 设备数据）。当用户要求"清理记忆"、"整理记忆"、"跑记忆 GC"，询问"你还记得…吗"、"我们上次…是什么时候"、"之前关于 X 做过什么决定"，或在一次会话写入大量记忆之后使用。
---

# Memory Keeper

| 文件 | 内容 | 预算 |
|------|------|------|
| `memory/USER.md` | 用户画像：稳定偏好、关系、长期事实 | 硬上限 5 KB |
| `memory/MEMORY.md` | 工作记忆条目池：一条一行 `- (重要度 1-5) 事实 [YYYY-MM-DD]`，每轮按 EI 排序注入 | 软 10 KB / 硬 64 KB，单条 ≤ 500 字符 |
| `memory/files/<名字>.md` | 主题档案：具体细节，按需读取 | 单档 ≤ 10 MiB |
| `memory/archive/<名字>.md` | 冷档案归档：一档一文件，可在记忆页还原 | 不限 |
| `memory/working.json` | 条目统计（引用次数、写入时间），EI 用 | — |
| `memory/usage.json` | 档案统计（引用次数、写入时间、冷标记） | — |

**有效重要度 EI** = 基础权重(重要度 1–5) × 访问因子 max(1, ln(1+引用次数)) × 30 天半衰期衰减（× 连接因子，当前无关系边）。
每轮注入按 EI 降序取到预算为止；未注入的条目与档案仍可用 `memory_get query=` 检索。

## 工具速查

| 目的 | 调用 |
|------|------|
| 追加一条条目 | `memory_write target=entry content="一句话事实" importance=3` |
| 改写一条条目 | `memory_write target=entry match="旧片段" content="新内容" importance=4` |
| 删除一条条目 | `memory_write target=entry match="片段" content=""` |
| 覆盖用户画像 | `memory_write target=profile content="<完整画像>"` |
| 写档案 | `memory_write archive=<名字> mode=append content="<正文>"` |
| 读单个档案 | `memory_get archive=<名字>` |
| 检索条目 + 档案 | `memory_get query=<关键词>` |
| 看全貌 | `memory_get`（画像 + 全部条目与 EI + 档案清单） |

条目写法：`- (重要度 1-5) 一句话事实 [YYYY-MM-DD]`，一条只放一件事；`importance` 参数省略时取正文里的 `(N)`，都没有则按 3 算。
传整行（`- (3) xxx`、`(3) xxx`、甚至重复标记）也不会写重标记，写入端会自动剥前缀。

## GC 流程

触发条件：用户要求整理记忆；条目重复或过时；工作记忆超过 ~10 KB；一次会话写入了大量条目。

1. `memory_get`（不带参数）读取画像、全部条目（含 EI 与引用次数）与档案清单。
2. **合并重复条目**：同一件事的多条合成一条；细节从条目挪进对应档案。
3. **调整重要度**：关键决策 4–5、稳定事实 3、临时信息 1–2。
4. **删除过时条目**：`memory_write target=entry match=<片段> content=""`；拿不准就保留。
5. **档案层**：重复档案合并（记忆页多选合并，或读全文后写回）；长期不用的档案归档；画像级稳定偏好写 `target=profile`。
6. **汇报**：合并、删除、调权、归档了什么，以及容量前后对比。不静默删除整个档案或整条结论。

**永不删除**：用户明确要求记住的内容、硬性行为约束、关系类条目（人名及关系）。

## 分级召回

| 层级 | 来源 | 工具 |
|------|------|------|
| L0 | 每轮注入的 USER.md 与按 EI 排序的活跃条目 | — |
| L1 | 工作记忆条目 + 主题档案 | `memory_get query=<关键词>`，或已知名字时 `memory_get archive=<名字>` |
| L2 | 历史会话 | `conversation_history query=...` |
| L3 | 设备领域数据 | `search_calendar_events` / `search_notes` / `search_system_memories` / `search_bills` 等 |

置信度 ≥ 0.7 即停；每升一层重估，提升 < 0.1 就停。回答附证据（层级 + 日期）；没有匹配就明说。细节见 `references/recall-tiers.md`。

## 捕获格式约定

- 条目：`- (重要度 1-5) 一句话事实 [YYYY-MM-DD]`，一条只放一件事。
- 长内容进档案，条目里只留一句索引式结论。
- 分类、复查阈值与驱逐顺序见 `references/decay-rules.md`。
