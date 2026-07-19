# ❄ YukiCli

> 一个用 Java 17 构建的类 Claude Code 终端 AI Agent，分阶段演进实现。

YukiCli 把"一个 LLM 驱动的智能体"做成可在终端直接使用的产品：读文件、写文件、跑命令、创建项目。核心是一个 **ReAct 循环**（思考 → 行动 → 观察），在此之上叠加 **Plan-and-Execute**（先规划后执行，DAG 任务依赖）等更复杂的执行模式。

## 项目目的

对标 Claude Code，从零开始构建一个商业级 Agent CLI 产品。项目采用**分期演进**策略，每一期聚焦一个核心能力，逐步从最基础的 ReAct 闘环成长为功能完整的智能体终端。

## 技术栈

| 类别 | 选型 | 用途 |
|---|---|---|
| 语言/构建 | Java 17 / Maven | 主语言与依赖管理 |
| JSON | Jackson | 手拼 LLM 请求体、解析响应 |
| HTTP | OkHttp | 发送 LLM API 请求 |
| 配置 | 手写 .env 读取 + JSON 配置 | 多 provider 环境变量与持久化配置 |

## 分期路线图

| 期 | 主题 | 一句话交付 | 状态 |
|---|---|---|---|
| 1 | 基础 ReAct + Tool Call | ReAct 循环 + OpenAI 兼容 LLM 集成 + 5 个基础工具 | ✅ 已完成 |
| 2 | Plan-and-Execute | 先规划后执行，任务 DAG + 并行批次 + 重规划 | ✅ 已完成 |
| 3 | Memory 系统 | 短期/长期记忆 + 上下文压缩 + Token 预算 | ✅ 已完成 |
| 4 | RAG 检索 | 代码向量化 + 向量库 + 语义检索 | 规划中 |
| 5 | Multi-Agent | 规划者/执行者/检查者三角色协作 | ✅ 已完成 |
| 6 | HITL + 审批流 | 危险操作审批 + PathGuard/CommandGuard/AuditLog | 规划中 |
| 7 | 异步 + 并行工具 | 同轮多 tool_calls 并行 + 批次超时 | 规划中 |
| 8 | 多模型适配 | LlmClient 抽象 + 模板基类 + 运行时切换 | 规划中 |
| 9+ | 联网 / MCP / TUI / ... | 持续演进 | 规划中 |

## 第一期：基础 ReAct + Tool Call

### 核心设计

**ReAct 循环**（[Agent.java](src/main/java/com/yukicli/agent/Agent.java)）：

```
用户输入
   │
   ▼
┌──────────────────────────┐
│  Reasoning: 调用 LLM     │ ◄── 对话历史 + 工具列表
│  返回文本和/或工具调用     │
└──────────┬───────────────┘
           │
     有工具调用？
     ├── 是 → Acting: 执行工具 → Observation: 结果回灌历史 → 继续循环
     └── 否 → 返回最终回复，循环结束
```

**工具注册表**（[ToolRegistry.java](src/main/java/com/yukicli/tool/ToolRegistry.java)）：
所有工具实现统一的 `Tool` 接口，通过 `ToolRegistry` 注册、查找和执行。每个工具声明自己的名称、描述和参数 JSON Schema（OpenAI function calling 格式）。

### 内置工具（6 个）

| 工具 | 说明 |
|---|---|
| `read_file` | 读取指定路径的文件内容 |
| `write_file` | 将内容写入文件，自动创建父目录 |
| `list_dir` | 列出目录下的文件和子目录 |
| `execute_command` | 执行 Shell 命令（Windows: cmd /c，其他: sh -c） |
| `create_project` | 创建标准项目目录骨架 |
| `save_memory` | LLM 主动保存长期事实记忆（第三期新增） |

## 第二期：Plan-and-Execute + DAG

### 核心设计

**Plan-and-Execute 模式**：处理复杂多步任务，先用 LLM 规划出 DAG 任务图，再按依赖顺序执行。

```
用户输入（/plan 模式）
   │
   ▼
┌──────────────────────────┐
│  Planner: LLM 生成计划    │ → JSON { summary, tasks[] }
│  解析为 ExecutionPlan     │ → DAG 节点 + 依赖关系
└──────────┬───────────────┘
           │
     计划审查（可补充/取消）
           │
           ▼
┌──────────────────────────┐
│  按 DAG 批次执行          │
│  同批次任务并行（≤4线程）  │
│  每任务内部 mini ReAct    │
└──────────┬───────────────┘
           │
     有任务失败且进度 < 50%？
     ├── 是 → 重新规划（replan）
     └── 否 → 汇总结果返回
```

### 模块说明

| 模块 | 文件 | 职责 |
|---|---|---|
| Task | [Task.java](src/main/java/com/yukicli/plan/Task.java) | 任务节点：id/描述/类型/状态/依赖/被依赖 |
| ExecutionPlan | [ExecutionPlan.java](src/main/java/com/yukicli/plan/ExecutionPlan.java) | DAG 管理：拓扑排序、环检测、并行批次、进度追踪 |
| Planner | [Planner.java](src/main/java/com/yukicli/plan/Planner.java) | 用 LLM 生成 JSON 计划并解析为 ExecutionPlan |
| PlanExecuteAgent | [PlanExecuteAgent.java](src/main/java/com/yukicli/agent/PlanExecuteAgent.java) | 编排执行：审查→批次执行→重规划→汇总 |

### DAG 依赖与并行执行

- **拓扑排序**：DFS + visited/visiting 双集合检测环
- **并行批次**：同层无依赖任务划分为一个批次，最多 4 线程并行
- **重规划**：任务失败且进度 < 50% 时，把原目标 + 失败原因 + 已完成任务交给 LLM 重新规划
- **计划审查**：支持 EXECUTE / SUPPLEMENT（补充要求重新规划）/ CANCEL 三种决策

### 任务类型

| 类型 | 说明 |
|---|---|
| `FILE_READ` | 读取文件 |
| `FILE_WRITE` | 写入文件 |
| `COMMAND` | 执行命令 |
| `ANALYSIS` | 分析结果 |
| `VERIFICATION` | 验证结果 |

## 第三期：Memory 系统

### 核心设计

**双层记忆**：短期对话记忆（`ConversationMemory`）+ 长期事实记忆（`LongTermMemory`），加上 Token 预算管理与 Map-Reduce 上下文压缩。

```
用户输入
   │
   ▼
┌──────────────────────────────┐
│ MemoryManager（门面）        │
│  ├─ ConversationMemory       │ ◄── 短期：LinkedHashMap，超预算 evict
│  ├─ LongTermMemory           │ ◄── 长期：JSON 持久化到 ~/.yukicli/memory/
│  ├─ MemoryRetriever          │ ◄── 相关度检索 + 时间衰减
│  ├─ ContextCompressor        │ ◄── Map-Reduce 摘要压缩
│  ├─ ConversationHistoryCompactor │ ◄── Agent 主循环历史压缩
│  └─ TokenBudget              │ ◄── Token 预算计算
└──────────────┬───────────────┘
               │
   构建检索上下文 → 注入 system prompt
               │
   工具执行后回灌短期记忆
   长期记忆由 LLM 通过 save_memory 工具主动保存
```

### 模块说明

| 模块 | 文件 | 职责 |
|---|---|---|
| Memory | [Memory.java](src/main/java/com/yukicli/memory/Memory.java) | 统一接口（store/retrieve/search/getAll/delete/clear） |
| MemoryEntry | [MemoryEntry.java](src/main/java/com/yukicli/memory/MemoryEntry.java) | 条目数据类 + MemoryType 枚举 + token 估算 |
| ConversationMemory | [ConversationMemory.java](src/main/java/com/yukicli/memory/ConversationMemory.java) | 短期记忆，LinkedHashMap + 自动 evict |
| LongTermMemory | [LongTermMemory.java](src/main/java/com/yukicli/memory/LongTermMemory.java) | 长期记忆，project/global 作用域 + JSON 落盘 |
| MemoryRetriever | [MemoryRetriever.java](src/main/java/com/yukicli/memory/MemoryRetriever.java) | 相关度检索，精确匹配 1.0，关键词 + 时间衰减 |
| TokenBudget | [TokenBudget.java](src/main/java/com/yukicli/memory/TokenBudget.java) | 预算计算（context - system - tools - response） |
| ContextCompressor | [ContextCompressor.java](src/main/java/com/yukicli/memory/ContextCompressor.java) | Map-Reduce 压缩短期记忆，保留最近 3 轮 |
| ConversationHistoryCompactor | [ConversationHistoryCompactor.java](src/main/java/com/yukicli/memory/ConversationHistoryCompactor.java) | 压缩 Agent 主循环 List<LlmMessage>，按 user 边界切割 |
| MemoryManager | [MemoryManager.java](src/main/java/com/yukicli/memory/MemoryManager.java) | 门面，组合所有子系统，提供统一 API |
| MemoryQueryTokenizer | [MemoryQueryTokenizer.java](src/main/java/com/yukicli/memory/MemoryQueryTokenizer.java) | 简化分词器（中文按字、英文按边界） |

### 关键设计点

- **Token 预算**：默认 `contextWindow=32000`，扣除 `system(500) + tools(800) + response(2000)` 后剩余分配给对话
- **短期预算**：默认 14000 token，超出自动 evictOldest
- **压缩触发**：当历史 token ≥ `预算 × 0.9` 时自动触发 Map-Reduce 压缩
- **切割保护**：`ConversationHistoryCompactor` 强制在 user message 边界切割，避免切断 `tool_call/tool_result` 协议对
- **作用域隔离**：长期记忆分 `project`（仅当前项目可见）和 `global`（所有项目可见）
- **检索加权**：长期记忆 × 1.2 加权，时间衰减（24h 半衰期）
- **save_memory 工具**：LLM 可主动调用，参数 `fact`（必填）+ `scope`（可选 project/global）
- **持久化路径**：`~/.yukicli/memory/long_term_memory.json`

### 默认参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `contextWindow` | 32000 | 模型上下文窗口大小 |
| `shortTermBudget` | 14000 | 短期记忆 token 预算 |
| `memoryContextTokens` | 1000 | 注入 system prompt 的检索记忆上限 |
| `compressionTriggerRatio` | 0.9 | 历史占用达预算 90% 时触发压缩 |

## 第五期：Multi-Agent 协作

### 核心设计

**三角色主从架构**：Planner（规划者）+ Worker（执行者，池化）+ Reviewer（检查者，每步独立实例）。Planner 拆解任务为有序步骤，多步骤时并行执行，每步由 Reviewer 审查，最多 2 次重试。

```
用户任务
   │
   ▼
┌──────────────────────────┐
│ Planner: LLM 规划步骤     │ → JSON { steps: [{id, description}] }
│ 强制重编号 step_1...step_n│
└──────────┬───────────────┘
           │
   解析为 step 列表
           │
   ┌───────┴────────┐
   │ 单步 → 串行执行 │
   │ 多步 → 并行批次 │
   └───────┬────────┘
           │
   每个步骤：
   ┌──────────────────────────┐
   │ Worker.execute(task)     │ → ReAct 循环 + 工具调用
   │         ↓                │
   │ Reviewer.review(result)  │ → APPROVAL/REJECTION + 反馈
   │         ↓                │
   │ 最多 2 次重试             │
   └──────────┬───────────────┘
              │
   按步骤顺序汇总结果
```

### 模块说明

| 模块 | 文件 | 职责 |
|---|---|---|
| AgentRole | [AgentRole.java](src/main/java/com/yukicli/agent/AgentRole.java) | 角色枚举 PLANNER/WORKER/REVIEWER |
| AgentMessage | [AgentMessage.java](src/main/java/com/yukicli/agent/AgentMessage.java) | Agent 间消息 record，6 种 Type |
| AgentBudget | [AgentBudget.java](src/main/java/com/yukicli/agent/AgentBudget.java) | 三道保险阀：token/stagnation/hard-iter |
| SubAgent | [SubAgent.java](src/main/java/com/yukicli/agent/SubAgent.java) | 轻量 Agent，独立 conversationHistory + 历史压缩 |
| AgentOrchestrator | [AgentOrchestrator.java](src/main/java/com/yukicli/agent/AgentOrchestrator.java) | 主控，1 planner + 2 workers + 1 reviewer |

### 关键设计点

- **固定四实例**：1 个 Planner + 2 个 Worker（BlockingQueue 池化）+ 1 个 Reviewer（每步新建独立实例）
- **并行执行**：多步骤时 Worker 池化并行，ByteArrayOutputStream 缓冲输出，按 step_id 顺序 flush
- **独立历史**：每个 SubAgent 维护独立的 conversationHistory，避免互相污染
- **共享资源**：所有 SubAgent 共享 LlmClient / ToolRegistry / MemoryManager
- **三道保险阀**（AgentBudget）：
  - `tokenBudget`：单 Agent token 上限（默认无限制，可由 `yukicli.react.token.budget` 配置）
  - `stagnationWindow=3`：滑动窗口检测连续无新工具调用的停滞
  - `hardMaxIterations=50`：硬上限兜底
- **Reviewer 保守策略**：解析审查结果失败时默认拒绝，触发重试
- **重编号**：Planner 输出的 step id 强制重写为 `step_1, step_2, ...`，避免 LLM 编号混乱
- **SubAgent 角色差异**：仅 WORKER 使用工具，PLANNER/REVIEWER 纯文本推理

### SubAgent Prompt 模板

| 角色 | Prompt 重点 |
|---|---|
| PLANNER | 拆解任务为可执行步骤，输出 JSON `{steps: [{id, description}]}` |
| WORKER | 执行单个步骤，可用工具，完成后输出结果摘要 |
| REVIEWER | 审查 Worker 输出，输出 APPROVAL/REJECTION + 反馈 |

## 快速开始

```bash
# 1. 配置环境变量
cp .env.example .env
# 编辑 .env，填入任一 provider 的 API Key

# 2. 编译打包
mvn clean package

# 3. 运行
java -jar target/yukicli-1.0-SNAPSHOT.jar
```

启动后会显示雪花图标 ❄，进入交互式对话。

### 支持的 LLM

兼容所有 OpenAI Chat Completions API 格式的模型，通过 provider 专属环境变量配置：

| Provider | 环境变量 | 默认 Base URL | 默认模型 |
|---|---|---|---|
| GLM（智谱） | `GLM_API_KEY` | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| DeepSeek | `DEEPSEEK_API_KEY` | `https://api.deepseek.com/v1` | `deepseek-chat` |
| Kimi（月之暗面） | `KIMI_API_KEY` | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| OpenAI | `OPENAI_API_KEY` | `https://api.openai.com/v1` | `gpt-4o` |

只需配置其中一个 provider 的 API Key 即可。也可通过 `~/.yukicli/config.json` 持久化配置。

### 交互命令

| 命令 | 说明 |
|---|---|
| `/plan <任务>` | 使用 Plan-and-Execute 模式执行复杂任务 |
| `/team <任务>` | 使用 Multi-Agent 协作模式（Planner + Worker + Reviewer） |
| `/react <消息>` | 使用 ReAct 模式（默认） |
| `/memory` | 查看记忆系统状态 |
| `/memory list` | 列出所有长期记忆 |
| `/memory search <关键词>` | 搜索长期记忆 |
| `/memory delete <id>` | 删除指定长期记忆 |
| `/memory clear` | 清空长期记忆 |
| `/save <事实>` | 保存项目级长期记忆 |
| `/save --global <事实>` | 保存全局长期记忆 |
| `/compact` | 手动触发对话历史压缩 |
| `/clear` | 清空对话历史 |
| `/exit` `/quit` | 退出程序 |

## 目录结构

```
src/main/java/com/yukicli/
├── Main.java              入口类，UTF-8 设置 + 雪花图标 + 交互循环
├── agent/
│   ├── Agent.java              ReAct 循环核心
│   ├── PlanExecuteAgent.java   Plan-and-Execute 编排
│   ├── AgentRole.java          Multi-Agent 角色枚举（第五期）
│   ├── AgentMessage.java       Agent 间消息（第五期）
│   ├── AgentBudget.java        Agent 预算/保险阀（第五期）
│   ├── SubAgent.java           轻量 Agent（第五期）
│   └── AgentOrchestrator.java  Multi-Agent 主控（第五期）
├── plan/
│   ├── Task.java               任务节点（DAG 节点）
│   ├── ExecutionPlan.java      执行计划（DAG + 拓扑排序 + 并行批次）
│   └── Planner.java            规划器（LLM 生成计划）
├── memory/                     第三期：记忆系统
│   ├── Memory.java             统一接口
│   ├── MemoryEntry.java        条目数据类 + 类型枚举
│   ├── ConversationMemory.java 短期记忆（LinkedHashMap）
│   ├── LongTermMemory.java     长期记忆（JSON 持久化 + 作用域）
│   ├── MemoryRetriever.java    相关度检索 + 时间衰减
│   ├── MemoryQueryTokenizer.java 简化分词器
│   ├── TokenBudget.java        Token 预算计算
│   ├── ContextCompressor.java  Map-Reduce 短期记忆压缩
│   ├── ConversationHistoryCompactor.java Agent 主循环历史压缩
│   └── MemoryManager.java      门面，组合所有子系统
├── config/
│   └── YukiCliConfig.java      配置管理（JSON + .env）
├── llm/
│   ├── LlmClient.java          LLM 客户端接口
│   ├── OpenAiCompatibleClient.java  OpenAI 兼容协议实现
│   ├── LlmClientFactory.java   工厂，多 provider 轮询
│   ├── LlmMessage.java         对话消息
│   ├── LlmResponse.java        LLM 响应
│   └── ToolCall.java           工具调用请求
├── tool/
│   ├── Tool.java               工具接口
│   ├── AbstractTool.java       工具基类
│   ├── ToolRegistry.java       工具注册表
│   └── tools/
│       ├── ReadFileTool.java
│       ├── WriteFileTool.java
│       ├── ListDirTool.java
│       ├── ExecuteCommandTool.java
│       ├── CreateProjectTool.java
│       └── SaveMemoryTool.java  LLM 主动保存长期记忆（第三期）
└── render/
    ├── Renderer.java           渲染器接口
    └── PlainRenderer.java      纯文本渲染器
```

## License

MIT
