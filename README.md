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
| HTTP | OkHttp | 发送 LLM API 请求 + 网页抓取 |
| HTML 解析 | jsoup | 网页正文提取（第九期联网模块） |
| 配置 | 手写 .env 读取 + JSON 配置 | 多 provider 环境变量与持久化配置 |

## 分期路线图

| 期 | 主题 | 一句话交付 | 状态 |
|---|---|---|---|
| 1 | 基础 ReAct + Tool Call | ReAct 循环 + OpenAI 兼容 LLM 集成 + 5 个基础工具 | ✅ 已完成 |
| 2 | Plan-and-Execute | 先规划后执行，任务 DAG + 并行批次 + 重规划 | ✅ 已完成 |
| 3 | Memory 系统 | 短期/长期记忆 + 上下文压缩 + Token 预算 | ✅ 已完成 |
| 4 | RAG 检索 | 代码向量化 + 向量库 + 语义检索 | ✅ 已完成 |
| 5 | Multi-Agent | 规划者/执行者/检查者三角色协作 | ✅ 已完成 |
| 6 | HITL + 审批流 | 危险操作审批 + PathGuard/CommandGuard/AuditLog | ✅ 已完成 |
| 7 | 异步 + 并行工具 | 同轮多 tool_calls 并行 + 批次超时 | ✅ 已完成 |
| 8 | 多模型适配 | LlmClient 抽象 + 模板基类 + 4 个 provider 专属客户端 + 运行时切换 | ✅ 已完成 |
| 9 | 联网模块 | web_search / web_fetch + SSRF 防护 + 限流 + HTML→Markdown | ✅ 已完成 |
| 10+ | MCP / TUI / ... | 持续演进 | 规划中 |

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

### 内置工具（9 个）

| 工具 | 说明 | 危险等级 |
|---|---|---|
| `read_file` | 读取指定路径的文件内容 | 🟢 安全 |
| `write_file` | 将内容写入文件，自动创建父目录 | 🟡 中危（HITL 审批） |
| `list_dir` | 列出目录下的文件和子目录 | 🟢 安全 |
| `execute_command` | 执行 Shell 命令（Windows: cmd /c，其他: sh -c） | 🔴 高危（HITL 审批） |
| `create_project` | 创建标准项目目录骨架 | 🟡 中危（HITL 审批） |
| `save_memory` | LLM 主动保存长期事实记忆（第三期新增） | 🟢 安全 |
| `search_code` | RAG 代码语义检索（第四期新增） | 🟢 安全 |
| `web_search` | 联网搜索（第九期新增，zhipu/serpapi/searxng 三 provider） | 🟢 安全 |
| `web_fetch` | 抓取网页正文转 Markdown（第九期新增，含 SSRF 防护） | 🟢 安全 |

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

## 第四期：RAG 检索

### 核心设计

**简化版 RAG**：用 JSON 文件持久化向量（替代 SQLite）+ 正则分块（替代 javaparser AST），保留语义检索 + 关键词检索 + 混合融合三模式，无外部依赖。

```
/index 命令
   │
   ▼
CodeIndex
   ├─ Files.walk 遍历项目（跳过 target/node_modules/.git 等）
   ├─ CodeChunker 分块（Java 用正则识别 class/method + 大括号配对）
   ├─ CodeAnalyzer 抽关系（imports / extends / implements）
   ├─ EmbeddingClient 调 OpenAI 兼容 embedding API
   └─ VectorStore 持久化到 ~/.yukicli/rag/{hash}.json
       ├─ chunks: [{ filePath, chunkType, name, content, embedding }]
       └─ relations: [{ fromFile, fromName, toName, relationType }]

用户输入
   │
   ▼
CodeRetriever.hybridSearch
   ├─ 语义检索：embedding 余弦相似度 topK*2
   ├─ 关键词检索：MemoryQueryTokenizer 分词后逐 token 查
   └─ 融合：双重命中 +0.1，name 命中 +0.3，同文件最多 2 条
   │
   ▼
SearchResultFormatter.formatForPrompt → 注入 system prompt
```

### 模块说明

| 模块 | 文件 | 职责 |
|---|---|---|
| CodeChunk | [CodeChunk.java](src/main/java/com/yukicli/rag/CodeChunk.java) | 块 record（filePath / chunkType / name / content / 行号） |
| CodeChunker | [CodeChunker.java](src/main/java/com/yukicli/rag/CodeChunker.java) | 分块器（Java 正则 + 大括号配对；非 Java 按字符分段） |
| CodeRelation | [CodeRelation.java](src/main/java/com/yukicli/rag/CodeRelation.java) | 关系 record |
| CodeAnalyzer | [CodeAnalyzer.java](src/main/java/com/yukicli/rag/CodeAnalyzer.java) | 关系抽取（import / extends / implements） |
| EmbeddingClient | [EmbeddingClient.java](src/main/java/com/yukicli/rag/EmbeddingClient.java) | OpenAI 兼容 embedding API（默认 GLM embedding-3） |
| VectorStore | [VectorStore.java](src/main/java/com/yukicli/rag/VectorStore.java) | JSON 持久化 + 余弦相似度检索 + 关键词检索 |
| CodeRetriever | [CodeRetriever.java](src/main/java/com/yukicli/rag/CodeRetriever.java) | 检索入口（semantic / keyword / hybrid） |
| CodeIndex | [CodeIndex.java](src/main/java/com/yukicli/rag/CodeIndex.java) | 索引管理器（遍历 + 分块 + embedding + 写入） |
| SearchResultFormatter | [SearchResultFormatter.java](src/main/java/com/yukicli/rag/SearchResultFormatter.java) | 检索结果格式化（注入 prompt / 终端显示） |

### 关键设计点

- **持久化路径**：`~/.yukicli/rag/{SHA-256 前 8 位}.json`，按项目隔离
- **混合检索算法**：
  - 语义检索取 topK*2
  - 关键词命中位置加分：name +0.3，file +0.1，content +0.1
  - 类型加分：method +0.15，class +0.10
  - 双重命中 +0.1（只给一次）
  - 同文件最多保留 2 条，总数限 topK
- **Embedding 配置**：默认 GLM embedding-3，与 LLM 共用 GLM_API_KEY（可单独配置 EMBEDDING_API_KEY）
- **支持扩展名**：.java/.py/.js/.ts/.go/.rs/.c/.cpp/.h/.md/.xml/.yaml/.json/.sh/.kt 等 17 种
- **跳过目录**：node_modules / target / build / .git / .idea / .vscode / dist / out / vendor 等
- **search_code 工具**：LLM 可调用，参数 query（必填）+ top_k（可选，默认 5，上限 30）

## 第六期：HITL + Policy 模块

### 核心设计

**双层防护**：策略层（PathGuard/CommandGuard/AuditLog）+ 审批层（HitlHandler）。策略层先于审批层拦截，审批层负责交互式确认。

```
LLM 调用 write_file / execute_command / create_project
   │
   ▼
HitlToolRegistry.execute(name, args)
   ├─ HITL 关闭 / 工具非危险 / 已 approveAll → 走父类 execute
   └─ 走 HitlHandler.requestApproval
       │
       ├─ APPROVED → 父类 execute(effectiveArgs)
       ├─ APPROVED_ALL → 加入 approvedAll 集合，父类 execute
       ├─ MODIFIED → 父类 execute(modifiedArgs)
       ├─ REJECTED → 写 denyByHitl audit，返回拒绝消息
       └─ SKIPPED → 写 denyByHitl audit，返回跳过消息
   │
   ▼
ToolRegistry.execute(name, args)（父类）
   ├─ Tool.execute(args)
   │   ├─ ReadFileTool/WriteFileTool/ListDirTool/CreateProjectTool
   │   │   └─ pathGuard.resolveSafe() 越界抛 PolicyException
   │   └─ ExecuteCommandTool
   │       └─ CommandGuard.check() 命中黑名单抛 PolicyException
   │
   └─ catch PolicyException → 写 denyByPolicy audit，返回策略拒绝消息
   └─ catch Exception → 写 error audit
   └─ 成功 + 危险工具 → 写 allow audit
```

### 模块说明

**策略层（com.yukicli.policy）**：

| 模块 | 文件 | 职责 |
|---|---|---|
| PolicyException | [PolicyException.java](src/main/java/com/yukicli/policy/PolicyException.java) | 策略异常 |
| PathGuard | [PathGuard.java](src/main/java/com/yukicli/policy/PathGuard.java) | 路径围栏（绝对路径/.. 穿越/符号链接三类越界） |
| CommandGuard | [CommandGuard.java](src/main/java/com/yukicli/policy/CommandGuard.java) | 命令黑名单（sudo/rm -rf //mkfs/dd/fork bomb 等 9 条） |
| AuditLog | [AuditLog.java](src/main/java/com/yukicli/policy/AuditLog.java) | JSONL 审计日志，按天分文件，自动脱敏 |

**审批层（com.yukicli.hitl）**：

| 模块 | 文件 | 职责 |
|---|---|---|
| ApprovalRequest | [ApprovalRequest.java](src/main/java/com/yukicli/hitl/ApprovalRequest.java) | 审批请求 record |
| ApprovalResult | [ApprovalResult.java](src/main/java/com/yukicli/hitl/ApprovalResult.java) | 审批结果 record（5 种 Decision） |
| ApprovalPolicy | [ApprovalPolicy.java](src/main/java/com/yukicli/hitl/ApprovalPolicy.java) | 静态判断哪些工具需要审批 |
| HitlHandler | [HitlHandler.java](src/main/java/com/yukicli/hitl/HitlHandler.java) | 审批处理器接口 |
| TerminalHitlHandler | [TerminalHitlHandler.java](src/main/java/com/yukicli/hitl/TerminalHitlHandler.java) | 终端交互式实现（synchronized 线程安全） |
| HitlToolRegistry | [HitlToolRegistry.java](src/main/java/com/yukicli/hitl/HitlToolRegistry.java) | 装饰器，继承 ToolRegistry 覆写 execute |

### 关键设计点

- **危险等级**：execute_command 🔴 高危 / write_file + create_project 🟡 中危 / 其他 🟢 安全
- **PathGuard 三类越界**：绝对路径逃出项目根、`..` 穿越、符号链接逃逸（用 toRealPath 解析）
- **CommandGuard 9 条规则**：sudo / rm -rf / / mkfs / dd of=/dev/ / fork bomb / curl|sh / find / / chmod 777 / / shutdown
- **AuditLog 落盘**：`~/.yukicli/audit/audit-YYYY-MM-DD.jsonl`，自动脱敏 Bearer / token= / password= / api_key=
- **HitlToolRegistry 装饰器模式**：继承 ToolRegistry 覆写 execute，Agent 无需感知 HITL 存在
- **TerminalHitlHandler 线程安全**：requestApproval 整体 synchronized，避免 Multi-Agent 并行场景下多 Worker 同时弹审批框
- **5 种审批选项**：y 批准 / a 全部放行 / n 拒绝（可填原因）/ s 跳过 / m 修改参数
- **失败安全**：5 次无效输入后默认拒绝
- **SubAgent 默认不启用 HITL**：避免 Multi-Agent 并行时多个 Worker 同时弹审批框阻塞

## 第七期：异步 + 并行工具

### 核心设计

**同轮多 tool_calls 并行执行**：ToolRegistry.executeAllParallel 用固定线程池 + invokeAll 超时，结果按原顺序返回。

```
LLM 返回多个 tool_calls
   │
   ▼
Agent 主循环判断
   ├─ 单工具 → 快速路径，直接 execute（不走线程池）
   └─ 多工具 → executeAllParallel
       │
       ├─ 并行关闭 → 退化为串行
       └─ 并行开启
           ├─ ExecutorService.newFixedThreadPool(min(N, 4))
           ├─ invokeAll(tasks, 90s) 超时控制
           ├─ 单工具超时不影响其他已完成工具
           └─ 结果按原 tool_call 顺序回灌 conversationHistory
   │
   ▼
按顺序回灌 conversationHistory（保证 LLM 看到的 tool_result 顺序与 tool_call 一致）
```

### 模块说明

无新包，改造现有：

| 模块 | 文件 | 改造内容 |
|---|---|---|
| ToolExecutionResult | [ToolExecutionResult.java](src/main/java/com/yukicli/tool/ToolExecutionResult.java) | 新增 record，封装结果 + 耗时 + 超时标记 |
| ToolRegistry | [ToolRegistry.java](src/main/java/com/yukicli/tool/ToolRegistry.java) | 新增 executeAllParallel + 并行开关 + 超时配置 |
| Agent | [Agent.java](src/main/java/com/yukicli/agent/Agent.java) | 主循环改造：单工具快速路径 / 多工具并行 |

### 关键设计点

- **MAX_PARALLEL_TOOLS = 4**：同时执行的工具调用上限
- **DEFAULT_BATCH_TIMEOUT_SECONDS = 90**：单批次超时
- **快速路径**：单工具调用不走线程池，避免线程切换开销
- **顺序保证**：invokeAll 返回的 Future 列表与输入顺序一致，最终回灌按原 tool_call 顺序，符合 OpenAI function calling 协议
- **超时隔离**：单个工具超时不影响其他已完成工具；超时工具返回 `[error] 工具执行超时（90秒），已取消`
- **daemon 线程**：所有 worker 线程设为 daemon，JVM 退出时自动结束
- **HITL 兼容**：HitlToolRegistry 覆写 execute，并行内部调用 execute 时 HITL 审批自动生效；TerminalHitlHandler.requestApproval synchronized 保证不串扰
- **两级并行**：外层 SubAgent 并行执行不同 step + 内层单 SubAgent 同轮多 tool_calls 并行，线程数上限 4 × 2 = 8

## 第八期：多模型适配

### 核心设计

**模板方法模式 + provider 专属客户端**：把 OpenAI 兼容协议的通用逻辑（请求组装、响应解析、tool_calls 解析）抽到 `AbstractOpenAiCompatibleClient` 模板基类，每个 provider 只需实现 4 个抽象方法（`getApiUrl` / `getModel` / `getApiKey` / `getProviderName`），即可适配 GLM / DeepSeek / Kimi / OpenAI 等接口。

```
LlmClient（接口，含能力描述方法）
   │
   ▼
AbstractOpenAiCompatibleClient（模板基类）
   ├─ chat()：组装请求 → 发送 → 解析响应
   ├─ buildRequestBody()：messages + tools JSON 组装
   ├─ parseResponse()：choices[0].message + tool_calls 解析
   ├─ customizeRequestBody() / customizeRequest() / httpClient() 扩展点
   └─ SHARED_HTTP_CLIENT：超时可通过系统属性覆盖
       │
       ├── GLMClient        默认 glm-4-flash，128K 上下文（glm-4-long=1M）
       ├── DeepSeekClient   默认 deepseek-chat，64K 上下文
       ├── KimiClient       默认 moonshot-v1-8k，按后缀解析 8k/32k/128k
       └── OpenAIClient     默认 gpt-4o，128K 上下文，支持视觉输入
```

### 模块说明

| 模块 | 文件 | 职责 |
|---|---|---|
| LlmClient | [LlmClient.java](src/main/java/com/yukicli/llm/LlmClient.java) | 接口，新增 getModelName / getProviderName / maxContextWindow / supportsTools / supportsImageInput / supportsPromptCaching |
| AbstractOpenAiCompatibleClient | [AbstractOpenAiCompatibleClient.java](src/main/java/com/yukicli/llm/AbstractOpenAiCompatibleClient.java) | 模板基类，通用请求/响应逻辑 + 扩展点 |
| GLMClient | [GLMClient.java](src/main/java/com/yukicli/llm/GLMClient.java) | 智谱 GLM 客户端 |
| DeepSeekClient | [DeepSeekClient.java](src/main/java/com/yukicli/llm/DeepSeekClient.java) | DeepSeek 客户端 |
| KimiClient | [KimiClient.java](src/main/java/com/yukicli/llm/KimiClient.java) | Kimi（月之暗面）客户端 |
| OpenAIClient | [OpenAIClient.java](src/main/java/com/yukicli/llm/OpenAIClient.java) | OpenAI 客户端 |
| LlmClientFactory | [LlmClientFactory.java](src/main/java/com/yukicli/llm/LlmClientFactory.java) | 工厂，按 provider 创建客户端 + 别名归一 + 列出可用 provider |

### 关键设计点

- **模板方法模式**：通用逻辑（请求组装、响应解析、tool_calls 解析、HTTP 客户端管理）抽到基类，子类只实现差异化部分
- **provider 别名归一**：`moonshot / moonshotai / moonshot-ai` → `kimi`，`zhipu / bigmodel` → `glm`
- **能力描述接口**：`maxContextWindow()` 让 TokenBudget 按模型动态计算；`supportsImageInput()` / `supportsPromptCaching()` 为后续多模态 / 缓存优化预留
- **运行时切换**：`/model <provider> [model]` 命令通过 holder 数组模式重建 Agent（Agent 的 llmClient 是 final，切换必须重建）
- **共享 HTTP 客户端**：`SHARED_HTTP_CLIENT` 单例，超时可通过系统属性 `yukicli.llm.{connect|read|write|call}.timeout.seconds` 覆盖
- **扩展点**：子类可覆写 `customizeRequestBody`（注入 provider 专属字段）/ `customizeRequest`（注入专属 header）/ `httpClient`（替换客户端）

### /model 命令

```
/model                    # 显示当前模型 + 可用 provider 列表
/model glm                # 切换到 GLM provider（用默认模型）
/model glm glm-4          # 切换到 GLM 并指定模型
/model deepseek deepseek-reasoner
```

切换会重建 Agent（对话历史重置），RAG 检索器也会重新初始化。

## 第九期：联网模块

### 核心设计

**三段式 web_fetch 流水线 + 多 provider 搜索抽象**：所有外网请求先过 `NetworkPolicy`（SSRF 防护 + 限流），再走 `WebFetcher`（5MB 截断 + 30s 超时），最后 `HtmlExtractor` 把 HTML 转为主正文 Markdown。搜索则通过 `SearchProvider` 接口抽象，支持 zhipu / serpapi / searxng 三种 provider。

```
web_fetch(url)
   │
   ▼
NetworkPolicy.checkUrl     # 1. SSRF 校验：scheme 白名单 + 主机黑名单（loopback/site-local/link-local）
   │
   ▼
NetworkPolicy.acquire      # 2. 限流：60s 窗口内最多 30 次
   │
   ▼
WebFetcher.fetch           # 3. HTTP GET + 5MB 流式截断 + 字符集解析
   │
   ▼
HtmlExtractor.extract      # 4. 清理噪声标签 → 找主语义容器 → 递归转 Markdown
   │
   ▼
按 maxChars 截断 → FetchResult.ok → 格式化输出


web_search(query)
   │
   ▼
NetworkPolicy.acquire      # 限流
   │
   ▼
SearchProviderFactory.create  # 按配置 / 环境变量选 provider
   │
   ├─ ZhipuSearchProvider   # 智谱 Web Search（国内首选，与 GLM 共用 API Key）
   ├─ SerpApiSearchProvider  # SerpAPI（国际通用，付费）
   └─ SearxngSearchProvider  # SearXNG（自托管，免费）
   │
   ▼
按 topK 截断 → SearchResult.of → 格式化输出
```

### 模块说明

**com.yukicli.web 包**：

| 模块 | 文件 | 职责 |
|---|---|---|
| NetworkPolicy | [NetworkPolicy.java](src/main/java/com/yukicli/web/NetworkPolicy.java) | SSRF 防护（scheme 白名单 + 主机黑名单）+ token bucket 限流 |
| WebFetcher | [WebFetcher.java](src/main/java/com/yukicli/web/WebFetcher.java) | OkHttp 抓取，5MB 上限 + 30s 超时 + 流式截断 |
| HtmlExtractor | [HtmlExtractor.java](src/main/java/com/yukicli/web/HtmlExtractor.java) | 极简 readability：HTML → 主正文 Markdown |
| SearchProvider | [SearchProvider.java](src/main/java/com/yukicli/web/SearchProvider.java) | 搜索 provider 接口 |
| SearchProviderFactory | [SearchProviderFactory.java](src/main/java/com/yukicli/web/SearchProviderFactory.java) | 按 config / 环境变量选 provider |
| ZhipuSearchProvider | [ZhipuSearchProvider.java](src/main/java/com/yukicli/web/ZhipuSearchProvider.java) | 智谱 Web Search（search_std / search_pro / sogou / quark） |
| SerpApiSearchProvider | [SerpApiSearchProvider.java](src/main/java/com/yukicli/web/SerpApiSearchProvider.java) | SerpAPI（Google 聚合，含 answer_box 兜底） |
| SearxngSearchProvider | [SearxngSearchProvider.java](src/main/java/com/yukicli/web/SearxngSearchProvider.java) | SearXNG 自托管元搜索 |
| SearchResult | [SearchResult.java](src/main/java/com/yukicli/web/SearchResult.java) | 搜索结果 record（position 从 1 开始） |
| FetchResult | [FetchResult.java](src/main/java/com/yukicli/web/FetchResult.java) | 抓取结果 record（含 bodyEmpty / hint 边界提示） |

**工具层**：

| 模块 | 文件 | 职责 |
|---|---|---|
| WebFetchTool | [WebFetchTool.java](src/main/java/com/yukicli/tool/tools/WebFetchTool.java) | web_fetch 工具，编排 NetworkPolicy → WebFetcher → HtmlExtractor |
| WebSearchTool | [WebSearchTool.java](src/main/java/com/yukicli/tool/tools/WebSearchTool.java) | web_search 工具，懒加载 SearchProvider |

### 关键设计点

- **SSRF 防护**：scheme 白名单（仅 http/https）+ 字面量黑名单（localhost / 0.0.0.0）+ DNS 解析后检查 InetAddress（loopback / anyLocal / linkLocal / siteLocal 全拒绝）
- **限流围栏**：token bucket，60s 窗口内最多 30 次；`web_fetch` 与 `web_search` 共享同一 `NetworkPolicy` 实例（共用限流窗口）
- **流式截断**：`WebFetcher.readBounded` 用 8KB buffer 流式读取，达 5MB 立即停止，避免 OOM
- **HTML 正文提取**：先清噪声标签（script/style/nav/aside/footer 等）+ 广告 class 关键词，再找 `<article>/<main>/[role=main]`，都没有则按文本密度打分（textLen × (1 - linkRatio×2)）选最高分块
- **Markdown 渲染**：递归处理 h1-h6 / p / br / hr / strong / em / code / pre / blockquote / ul / ol / a / img(alt) / table
- **搜索 provider 自动选择**：有 `GLM_API_KEY` → zhipu（国内首选，与 GLM 推理共用 Key）→ 有 `SERPAPI_KEY` → serpapi → 有 `SEARXNG_URL` → searxng；可用 `SEARCH_PROVIDER` 显式指定
- **边界提示**：SPA / 防爬站抓到 HTML 但提取不出正文时，`FetchResult.hint` 明确告诉 LLM「本期不重试」，避免反复重试浪费 token
- **懒加载**：`WebFetcher` / `HtmlExtractor` / `SearchProvider` 首次调用时才创建，启动零开销
- **限流顺序**：`web_fetch` 必须先 `checkUrl`（SSRF）→ `acquire`（限流）→ 再 fetch，围栏对所有路径生效

### /web 命令

```
/web                        # 显示用法
/web status                 # 查看 SearchProvider 状态
/web search <关键词>         # 联网搜索（默认 topK=5）
/web fetch <URL>            # 抓取网页正文（默认 maxChars=8000）
```

### 搜索 provider 配置

| Provider | 环境变量 | 说明 |
|---|---|---|
| zhipu（默认） | `GLM_API_KEY` | 智谱 Web Search，与 GLM 推理共用 Key；可选 `ZHIPU_SEARCH_ENGINE` 切换引擎（search_std / search_pro / search_pro_sogou / search_pro_quark） |
| serpapi | `SERPAPI_KEY` | SerpAPI 商业聚合，国际通用 |
| searxng | `SEARXNG_URL` | SearXNG 自托管，免费（`docker run --rm -p 8888:8888 searxng/searxng`） |

显式指定：`SEARCH_PROVIDER=zhipu|serpapi|searxng`

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
| `/hitl on` `/hitl off` | 开关 HITL 审批（第六期） |
| `/audit [n]` | 查看今天最近 n 条审计记录（默认 20，第六期） |
| `/index` | 索引当前项目（第四期 RAG） |
| `/index status` | 查看索引统计 |
| `/index clear` | 清空当前项目索引 |
| `/index rebuild` | 重建索引（= clear + index） |
| `/parallel on` `/parallel off` | 开关并行执行（第七期） |
| `/parallel timeout <秒数>` | 设置并行批次超时 |
| `/model` | 显示当前模型 + 可用 provider（第八期） |
| `/model <provider> [model]` | 运行时切换 provider/model（第八期） |
| `/web search <关键词>` | 联网搜索（第九期） |
| `/web fetch <URL>` | 抓取网页正文（第九期） |
| `/web status` | 查看联网模块状态（第九期） |
| `/clear` | 清空对话历史 |
| `/exit` `/quit` | 退出程序 |

## 目录结构

```
src/main/java/com/yukicli/
├── Main.java              入口类，UTF-8 设置 + 雪花图标 + 交互循环
├── agent/
│   ├── Agent.java              ReAct 循环核心（含并行执行 + RAG 注入）
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
│   ├── MemoryQueryTokenizer.java 简化分词器（public，供 RAG 复用）
│   ├── TokenBudget.java        Token 预算计算
│   ├── ContextCompressor.java  Map-Reduce 短期记忆压缩
│   ├── ConversationHistoryCompactor.java Agent 主循环历史压缩
│   └── MemoryManager.java      门面，组合所有子系统
├── policy/                     第六期：策略层
│   ├── PolicyException.java    策略异常
│   ├── PathGuard.java          路径围栏（绝对路径/.. 穿越/符号链接）
│   ├── CommandGuard.java       命令黑名单（9 条规则）
│   └── AuditLog.java           JSONL 审计日志 + 自动脱敏
├── hitl/                       第六期：审批层
│   ├── ApprovalRequest.java    审批请求 record
│   ├── ApprovalResult.java     审批结果 record（5 种 Decision）
│   ├── ApprovalPolicy.java     静态判断哪些工具需要审批
│   ├── HitlHandler.java        审批处理器接口
│   ├── TerminalHitlHandler.java 终端交互式实现（synchronized）
│   └── HitlToolRegistry.java   装饰器，继承 ToolRegistry 覆写 execute
├── rag/                        第四期：RAG 检索
│   ├── CodeChunk.java          块 record
│   ├── CodeChunker.java        分块器（Java 正则 + 大括号配对）
│   ├── CodeRelation.java       关系 record
│   ├── CodeAnalyzer.java       关系抽取（import/extends/implements）
│   ├── EmbeddingClient.java    OpenAI 兼容 embedding API
│   ├── VectorStore.java        JSON 持久化 + 余弦相似度 + 关键词检索
│   ├── CodeRetriever.java      检索入口（semantic/keyword/hybrid）
│   ├── CodeIndex.java          索引管理器（遍历 + 分块 + embedding）
│   └── SearchResultFormatter.java 检索结果格式化
├── config/
│   └── YukiCliConfig.java      配置管理（JSON + .env）
├── llm/
│   ├── LlmClient.java          LLM 客户端接口（含能力描述方法，第八期）
│   ├── AbstractOpenAiCompatibleClient.java  模板基类（第八期）
│   ├── GLMClient.java          智谱 GLM 客户端（第八期）
│   ├── DeepSeekClient.java     DeepSeek 客户端（第八期）
│   ├── KimiClient.java         Kimi 客户端（第八期）
│   ├── OpenAIClient.java       OpenAI 客户端（第八期）
│   ├── OpenAiCompatibleClient.java  通用 OpenAI 兼容协议实现（向后兼容）
│   ├── LlmClientFactory.java   工厂，按 provider 创建 + 别名归一 + 列出可用 provider
│   ├── LlmMessage.java         对话消息
│   ├── LlmResponse.java        LLM 响应
│   └── ToolCall.java           工具调用请求
├── tool/
│   ├── Tool.java               工具接口
│   ├── AbstractTool.java       工具基类（含 PathGuard 注入）
│   ├── ToolRegistry.java       工具注册表（含 PathGuard/AuditLog/并行执行）
│   ├── ToolExecutionResult.java 工具执行结果 record（第七期）
│   └── tools/
│       ├── ReadFileTool.java
│       ├── WriteFileTool.java
│       ├── ListDirTool.java
│       ├── ExecuteCommandTool.java  含 CommandGuard 拦截
│       ├── CreateProjectTool.java
│       ├── SaveMemoryTool.java  LLM 主动保存长期记忆（第三期）
│       ├── SearchCodeTool.java  RAG 代码检索（第四期）
│       ├── WebFetchTool.java    网页抓取工具（第九期）
│       └── WebSearchTool.java   联网搜索工具（第九期）
├── web/                        第九期：联网模块
│   ├── NetworkPolicy.java      SSRF 防护 + 限流
│   ├── WebFetcher.java         HTTP 抓取器（5MB 上限 + 30s 超时）
│   ├── HtmlExtractor.java      HTML → Markdown 正文提取
│   ├── SearchProvider.java     搜索 provider 接口
│   ├── SearchProviderFactory.java  按 config / 环境变量选 provider
│   ├── ZhipuSearchProvider.java    智谱 Web Search（国内首选）
│   ├── SerpApiSearchProvider.java  SerpAPI（国际通用）
│   ├── SearxngSearchProvider.java  SearXNG（自托管）
│   ├── SearchResult.java       搜索结果 record
│   └── FetchResult.java        抓取结果 record
└── render/
    ├── Renderer.java           渲染器接口
    └── PlainRenderer.java      纯文本渲染器
```

## License

MIT
