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
| 3 | Memory 系统 | 短期/长期记忆 + 上下文压缩 + Token 预算 | 规划中 |
| 4 | RAG 检索 | 代码向量化 + 向量库 + 语义检索 | 规划中 |
| 5 | Multi-Agent | 规划者/执行者/检查者三角色协作 | 规划中 |
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

### 内置工具（5 个）

| 工具 | 说明 |
|---|---|
| `read_file` | 读取指定路径的文件内容 |
| `write_file` | 将内容写入文件，自动创建父目录 |
| `list_dir` | 列出目录下的文件和子目录 |
| `execute_command` | 执行 Shell 命令（Windows: cmd /c，其他: sh -c） |
| `create_project` | 创建标准项目目录骨架 |

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
| `/react <消息>` | 使用 ReAct 模式（默认） |
| `/clear` | 清空对话历史 |
| `/exit` `/quit` | 退出程序 |

## 目录结构

```
src/main/java/com/yukicli/
├── Main.java              入口类，UTF-8 设置 + 雪花图标 + 交互循环
├── agent/
│   ├── Agent.java              ReAct 循环核心
│   └── PlanExecuteAgent.java   Plan-and-Execute 编排
├── plan/
│   ├── Task.java               任务节点（DAG 节点）
│   ├── ExecutionPlan.java      执行计划（DAG + 拓扑排序 + 并行批次）
│   └── Planner.java            规划器（LLM 生成计划）
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
│       └── CreateProjectTool.java
└── render/
    ├── Renderer.java           渲染器接口
    └── PlainRenderer.java      纯文本渲染器
```

## License

MIT
