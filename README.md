# Eta-custom · patches（3.3.1 基线）

[Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的自定义改造，以补丁形式存放，便于对照上游与选择性应用。

- 上游基线：`213e8ed6`（v3.3.1）
- 配套分支：`eta-custom` = 该基线上的完整定制版（本目录的补丁就是它与基线的全部差异）
- 旧版补丁（3.3.0 基线的 `01`–`26`）保留在 `patches` 分支，仅作历史

## 补丁

按列出的顺序 `git apply`，六个补丁的文件互不重叠，全部应用后与 `eta-custom` 的树完全一致。

| 补丁 | 覆盖 | 文件数 |
| --- | --- | --- |
| `01-virtual-screen.patch` | 虚拟屏（`agent/display`、虚拟屏路由策略与工具目录、悬浮小窗）、悬浮胶囊与 overlay、虚拟屏设置页 | 33 |
| `02-ask-user.patch` | 不确定时向我提问：挂起请求状态机、`ask_user` Schema、提问卡片 | 3 |
| `03-memory-subsystem.patch` | 记忆子系统：画像 / 工作记忆 / 条目池与 EI、检索与归档、空闲整理、记忆页、记忆工具、备份、内置 `memory-keeper` 技能 | 22 |
| `04-agent-runtime.patch` | 运行时：工具体系与能力快照、提示词、模型客户端、中途插话、多会话、Linux 运行通知、Root PTY、设置项（`Prefs`） | 25 |
| `05-ui-and-shell.patch` | 界面：聊天气体与输入栏、工作过程节点、侧栏文件夹与置顶、管理聊天、顶栏标题、设置页、路由 | 28 |
| `06-build-resources-tests.patch` | 构建与资源：`build.gradle.kts`、`proguard-rules.pro`、Manifest、字符串资源、单元测试 | 25 |

## 应用方式

```bash
git clone https://github.com/Mangi-11/Eta
cd Eta
git checkout 213e8ed6            # v3.3.1
for p in 01-virtual-screen 02-ask-user 03-memory-subsystem 04-agent-runtime 05-ui-and-shell 06-build-resources-tests; do
  git apply --index "/path/to/patches/$p.patch"
done
```

- 补丁以 `git diff --binary 213e8ed6 eta-custom` 生成，按整文件切分，**必须按顺序应用**（后一个补丁假设前一个的内容已在）。
- `06` 里的 `build.gradle.kts` 改动含“本机无 NDK 时不做 `ndkVersion` 校验”的适配；用官方 NDK 构建时可以只应用其余部分。
- 验证：应用后 `git write-tree` 应得到 `cd55d252646315b6b1b910ff60d976876703a10f`（与 `eta-custom^{tree}` 相同）。

## 改造内容

1. **中途插话** —— 运行期间发送的消息排队进入当前 run 的下一轮（`设置 → 对话 → 中途插话`）。
2. **流体云** —— 门槛从 Android 16 QPR1（36.1）放宽到 36.0；提问挂起时显示「待回答」与题干。
3. **Linux 运行通知** —— 用 Root 探测 rootfs 内进程数，通知栏显示数量并支持一键退出。
4. **多会话运行** —— 替换 / 排队 / 并发三选一（`设置 → 对话 → 多会话运行`）。
5. **虚拟屏（移植上游 PR #142 的 Root 虚拟屏）** —— GUI 工具在独立 display 上执行；含悬浮小窗、主屏回退授权、空闲回收、应用冲突三选项。
6. **不确定时向我提问** —— 运行中弹出带选项的卡片：单选 / 多选、推荐标记、自定义文字、跳过、多题翻页，结果以 JSON 回传模型；卡片渲染在输入栏的位置。
7. **侧栏文件夹 / 置顶 / 管理聊天** —— 新建对话与管理聊天入口、文件夹 chips、置顶与移动；数据库 `conversations` 增加 `folder_id` / `is_pinned`，新增 `conversation_folders`。
8. **聊天顶栏标题与滚动** —— 顶栏显示会话标题（超 12 字截断），双击回到第一条；打开会话定位到最新消息。
9. **记忆检索与归档** —— `memory_get` 分词打分检索并排序、命中行附上下文、`mode=sections` 章节摘要、用量 sidecar 与冷章节折叠、一档一文件的归档。
10. **画像 / 工作记忆拆分** —— `memory/USER.md`（≤5 KB）与 `memory/MEMORY.md` 每轮注入，旧 `CORE.md` 自动拆分迁移。
11. **工作记忆条目池 + EI** —— 条目 `- (重要度 1-5) 一句话事实 [日期]`，按 EI 排序注入，`memory_write target=entry` 支持追加 / 改写 / 删除。
12. **聊天工作过程节点** —— 技能读取、记忆整理、提问、MCP 调用合并为时间线节点，不再是普通工具行。
13. **悬浮胶囊常驻 + 任务完成通知** —— 常驻胶囊列出会话并可跳回；任务收尾走独立高优先级通道发完成通知。

> 上游 3.3.1 自带的能力（语音朗读、模型选择器重做、角色界面重构、PTY 控制台、`terminal_job` / `terminal_daemon` 等）由基线提供，不在补丁内。
