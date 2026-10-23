# Eta-custom · patches（3.3.3 基线）

[Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的自定义改造，以补丁形式存放，便于对照上游与选择性应用。

- 上游基线：`5e9d3a7b`（v3.3.3）
- 配套分支：`eta-custom` = 该基线上的完整定制版（本目录的补丁就是它与基线的全部差异）
- 旧版补丁（3.3.0 基线的 `01`–`26`、3.3.1 基线的 7 个）保留在 `patches` / `patches-3.3.1` 分支，仅作历史

## 与 3.3.1 基线的主要差异

**中途插话改用上游实现**。上游 3.3.3 的 `0dd2919a`（「支持消息排队发送与插话发送」）自带排队发送与插话：
`AgentMessageQueueReducer` + `AgentChatQueueStrip` + 输入框的停止/发送分离 + `steering` 通道。
本地自研的插话链路已整体丢弃，包括：

- 协议层：`MSG_SUPPLEMENT` / `MSG_SUPPLEMENT_RESPONSE`、`supplementBundle` 系列
- 运行时：`AgentRunController.SteeringSupplement`（带图插话）、`AgentRuntimeSession.steer(text, images)`、`AgentRuntimeClient.interjectRun`、`AgentRuntimeService.requestMidRunInterjection`
- UI 层：`AgentAppState.submitMidRunInterjection` / `restoreInterjectionDraft`、`AgentInterjectionAttachments.kt`、设置里的「中途插话」开关、`Prefs.AGENT_MID_RUN_INTERJECTION`
- 文案：`chat_interjection_*`、`settings_mid_run_interjection*`

**能力差异（已知，未自行补回）**：上游插话只接受纯文本，不支持我们原先的「带图片 / 带附件插话」。
需要的话得在上游 steering 通道上另行扩展。

## 补丁

按列出的顺序 `git apply`，七个补丁的文件互不重叠，全部应用后与定制版的树完全一致。

| 补丁 | 覆盖 | 文件数 |
| --- | --- | --- |
| `01-virtual-screen.patch` | 虚拟屏（`agent/display`、虚拟屏路由策略与工具目录、悬浮小窗）、悬浮胶囊与 overlay、虚拟屏设置页 | 33 |
| `02-ask-user.patch` | 不确定时向我提问：挂起请求状态机、`ask_user` Schema、提问卡片 | 3 |
| `03-chat-organization.patch` | 会话组织：文件夹 / 置顶 / 归档的列与迁移（并入 23→24）、管理聊天页 | 4 |
| `04-memory-subsystem.patch` | 记忆子系统：画像 / 工作记忆 / 条目池与 EI、检索与归档、空闲整理、档案分卷与按节读、记忆页与工具、备份、内置 `memory-keeper` | 19 |
| `05-agent-runtime.patch` | 运行时：工具体系与能力快照、提示词、模型客户端、多会话、Linux 运行通知、Root PTY、`Prefs` | 24 |
| `06-ui-and-shell.patch` | 界面：聊天气体与输入栏、工作过程节点、侧栏、顶栏标题、设置页、路由 | 26 |
| `07-build-resources-tests.patch` | 构建与资源：`build.gradle.kts`、`proguard-rules.pro`、Manifest、字符串资源、单元测试 | 25 |

## 应用方式

```bash
git clone https://github.com/Mangi-11/Eta
cd Eta
git checkout 5e9d3a7b            # v3.3.3
for p in 01-virtual-screen 02-ask-user 03-chat-organization 04-memory-subsystem \
         05-agent-runtime 06-ui-and-shell 07-build-resources-tests; do
  git apply --index "/path/to/patches/$p.patch"
done
```

- 补丁以 `git diff --binary 5e9d3a7b patches-3.3.3` 生成，按整文件切分，**必须按顺序应用**。
- 数据库：3.3.3 上游已占用 `22→23`（`model_providers.auth_mode`），会话组织的列与 `conversation_folders` 表并入 `23→24`，DB 版本 24。
- 验证：应用后 `git write-tree` 应得到 `759502e8f415cd40cf441b74855b7d5e43557337`（与 `patches-3.3.3` 的源码树相同，不含 `patches/` 与 `README.md`）。

## 改造内容

1. **虚拟屏** —— GUI 工具在独立 display 上执行；含悬浮小窗、主屏回退授权、空闲回收、应用冲突三选项。
2. **提问卡片** —— `ask_user` 挂起请求与卡片式选项。
3. **会话组织** —— 文件夹、置顶、归档、管理聊天页。
4. **记忆子系统** —— 画像 / 工作记忆 / 条目池与 EI、档案检索与归档、空闲整理、档案分卷与按节读、备份。
5. **多会话运行** —— 替换 / 排队 / 并发三选一。
6. **Linux 运行通知** —— 通知栏显示 rootfs 进程数并支持一键退出。
7. **界面与外壳** —— 聊天气体、工作过程节点、侧栏文件夹、顶栏两行标题。
