package com.yukicli;

import com.yukicli.agent.Agent;
import com.yukicli.agent.AgentOrchestrator;
import com.yukicli.agent.PlanExecuteAgent;
import com.yukicli.config.YukiCliConfig;
import com.yukicli.hitl.HitlHandler;
import com.yukicli.hitl.HitlToolRegistry;
import com.yukicli.hitl.TerminalHitlHandler;
import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmClientFactory;
import com.yukicli.memory.MemoryEntry;
import com.yukicli.memory.MemoryManager;
import com.yukicli.policy.AuditLog;
import com.yukicli.rag.CodeIndex;
import com.yukicli.rag.CodeRetriever;
import com.yukicli.rag.EmbeddingClient;
import com.yukicli.rag.VectorStore;
import com.yukicli.render.PlainRenderer;
import com.yukicli.render.Renderer;
import com.yukicli.tool.ToolRegistry;
import com.yukicli.tool.tools.*;
import com.yukicli.web.NetworkPolicy;
import com.yukicli.web.SearchProvider;
import com.yukicli.web.SearchProviderFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * YukiCli 入口类。
 *
 * 启动流程：
 *   1. 设置 UTF-8 输出流
 *   2. 显示雪花图标
 *   3. 加载 LLM 配置
 *   4. 注册工具（5 个基础工具 + save_memory）
 *   5. 启动交互式循环（支持 ReAct / Plan / Team 三种模式 + 记忆命令）
 */
public class Main {

    private static final String SNOWFLAKE = "\u2744"; // 雪花 Unicode

    private static final String SYSTEM_PROMPT = """
            你是 YukiCli，一个运行在终端的 AI 编程助手（类似 Claude Code）。
            你可以通过调用工具来读取文件、写入文件、列出目录、执行命令、创建项目、保存长期记忆，
            以及联网搜索和抓取网页。

            工作原则：
            1. 先理解用户需求，再选择合适的工具
            2. 工具调用后，基于返回结果继续推理
            3. 用中文回复用户
            4. 涉及危险操作（如删除文件）时先向用户确认
            5. 当用户明确说"记一下""记住""以后记得"时，调用 save_memory 工具保存为长期事实
            6. 写文件和执行命令前可能需要用户审批，返回的 [HITL] 消息表示操作被拒绝或跳过
            7. 涉及最新版本、官方文档、实时资讯时，主动使用 web_search / web_fetch 工具

            可用工具：read_file / write_file / list_dir / execute_command / create_project
                      / save_memory / search_code / web_search / web_fetch
            """;

    public static void main(String[] args) {
        // 1. 设置 UTF-8 输出流（配合 yukicli.bat 的 chcp 65001 + -Dfile.encoding=UTF-8 使用）
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(out);
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        // 2. 显示雪花图标
        System.out.println("\n  " + SNOWFLAKE + "  YukiCli\n");

        Renderer renderer = new PlainRenderer();

        // 3. 加载 LLM 配置
        YukiCliConfig config = YukiCliConfig.load();
        LlmClient[] llmClientHolder = new LlmClient[]{ LlmClientFactory.createFromConfig(config) };
        if (llmClientHolder[0] == null) {
            renderer.error("未找到可用的 API Key");
            renderer.info("请在 .env 文件中配置 GLM_API_KEY、DEEPSEEK_API_KEY、KIMI_API_KEY 或 OPENAI_API_KEY");
            renderer.info("（参考 .env.example）");
            return;
        }

        // 显示当前模型信息
        renderer.info("模型: " + llmClientHolder[0].getModelName() + " (" + llmClientHolder[0].getProviderName() + ")");

        // 4. 注册工具（使用 HitlToolRegistry 装饰，支持 HITL 审批）
        TerminalHitlHandler hitlHandler = new TerminalHitlHandler();
        HitlToolRegistry toolRegistry = new HitlToolRegistry(hitlHandler);
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new ListDirTool());
        toolRegistry.register(new ExecuteCommandTool());
        toolRegistry.register(new CreateProjectTool());
        SaveMemoryTool saveMemoryTool = new SaveMemoryTool();
        toolRegistry.register(saveMemoryTool);
        SearchCodeTool searchCodeTool = new SearchCodeTool();
        toolRegistry.register(searchCodeTool);

        // 第 9 期：联网模块工具（共享 NetworkPolicy 以共用限流窗口）
        NetworkPolicy sharedNetworkPolicy = new NetworkPolicy();
        WebFetchTool webFetchTool = new WebFetchTool();
        webFetchTool.setNetworkPolicy(sharedNetworkPolicy);
        toolRegistry.register(webFetchTool);
        WebSearchTool webSearchTool = new WebSearchTool();
        webSearchTool.setNetworkPolicy(sharedNetworkPolicy);
        // SearchProvider 懒加载：首次搜索时才创建（用 YukiCliConfig 让 zhipu 复用 GLM_API_KEY）
        webSearchTool.setProviderSupplier(() -> SearchProviderFactory.create(config));
        toolRegistry.register(webSearchTool);

        renderer.info("已加载 " + toolRegistry.getAllTools().size() + " 个工具。");
        renderer.info("当前工作目录: " + Path.of(".").toAbsolutePath().normalize());
        renderer.info("命令：/plan 规划执行 | /team 多Agent协作 | /react 普通对话（默认）");
        renderer.info("模型：/model 切换 provider/model | /parallel 并行工具开关 | /web 联网搜索/抓取");
        renderer.info("记忆：/memory 状态 | /save <事实> 保存 | /compact 压缩 | /clear 清空 | /exit 退出");
        renderer.info("安全：/hitl 审批开关 | /audit 审计日志 | /index 索引代码（第4期）\n");

        // 5. 创建 Agent
        Agent[] agentHolder = new Agent[]{
            new Agent(llmClientHolder[0], toolRegistry, renderer, SYSTEM_PROMPT)
        };
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClientHolder[0], toolRegistry, renderer);

        // 把 Agent 的 MemoryManager 注入到 save_memory 工具
        saveMemoryTool.setMemorySaver(agentHolder[0].getMemoryManager()::storeFact);

        // 注入 SearchCodeTool 的 retriever 工厂（每次调用创建新 CodeRetriever，用完即 close）
        String projectPath = Path.of(".").toAbsolutePath().normalize().toString();
        searchCodeTool.setProjectPath(projectPath);
        searchCodeTool.setRetrieverFactory(pp -> {
            try {
                return new CodeRetriever(pp, new EmbeddingClient(config));
            } catch (Exception e) {
                return new CodeRetriever(pp);
            }
        });

        // 为 Agent 注入一个共享的 CodeRetriever（用于 RAG 上下文注入）
        try {
            CodeRetriever sharedRetriever = new CodeRetriever(projectPath, new EmbeddingClient(config));
            agentHolder[0].setCodeRetriever(sharedRetriever);
        } catch (Exception e) {
            // RAG 初始化失败不影响主流程
        }

        // 是否下一条任务用 Multi-Agent 模式（用完即复位）
        boolean[] nextTaskUseTeamMode = {false};

        // 6. 交互循环
        try (Scanner scanner = new Scanner(System.in, "UTF-8")) {
            while (true) {
                System.out.print("\u001B[36m\u2744 \u001B[0m");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                // === 退出 / 清空 ===
                if ("/exit".equals(input) || "/quit".equals(input)) {
                    renderer.info("再见！");
                    break;
                }
                if ("/clear".equals(input)) {
                    agentHolder[0].clearHistory();
                    renderer.info("对话已清空。");
                    continue;
                }

                // === 记忆相关命令 ===
                if (handleMemoryCommands(input, agentHolder[0].getMemoryManager(), renderer)) {
                    continue;
                }

                // === /compact 手动压缩 ===
                if ("/compact".equals(input)) {
                    boolean compacted = agentHolder[0].compactHistoryNow();
                    if (compacted) {
                        renderer.info("📦 已把早期对话压缩为摘要。");
                    } else {
                        renderer.info("对话历史较短，未触发压缩。");
                    }
                    continue;
                }

                // === /hitl HITL 审批开关 ===
                if (input.startsWith("/hitl")) {
                    String arg = input.length() > 5 ? input.substring(5).trim().toLowerCase() : "";
                    if (arg.isEmpty()) {
                        renderer.info("HITL 审批: " + (hitlHandler.isEnabled() ? "已开启" : "已关闭"));
                        renderer.info("用法: /hitl on 开启 | /hitl off 关闭");
                    } else if (arg.equals("on")) {
                        hitlHandler.setEnabled(true);
                        hitlHandler.clearApprovedAll();
                        renderer.info("🛡️ HITL 审批已开启（write_file / execute_command / create_project 将请求审批）");
                    } else if (arg.equals("off")) {
                        hitlHandler.setEnabled(false);
                        hitlHandler.clearApprovedAll();
                        renderer.info("HITL 审批已关闭。");
                    } else {
                        renderer.error("未知参数: " + arg + "（可用 on / off）");
                    }
                    continue;
                }

                // === /audit 审计日志 ===
                if (input.startsWith("/audit")) {
                    String arg = input.length() > 6 ? input.substring(6).trim() : "";
                    int n = 20;
                    if (!arg.isEmpty()) {
                        try { n = Integer.parseInt(arg); } catch (NumberFormatException ignored) {}
                    }
                    List<AuditLog.AuditEntry> entries = toolRegistry.getAuditLog().readToday(n);
                    if (entries.isEmpty()) {
                        renderer.info("📭 今天暂无审计记录。");
                    } else {
                        System.out.println("📋 最近 " + entries.size() + " 条审计记录:");
                        for (AuditLog.AuditEntry e : entries) {
                            String icon = switch (e.outcome()) {
                                case "allow" -> "✅";
                                case "deny" -> "🛡️";
                                case "error" -> "❌";
                                default -> "❓";
                            };
                            System.out.println("  " + icon + " [" + e.timestamp() + "] " + e.tool()
                                + " (" + e.outcome() + (e.approver().isEmpty() ? "" : "/" + e.approver()) + ")"
                                + (e.reason().isEmpty() ? "" : " - " + e.reason())
                                + " (" + e.durationMs() + "ms)");
                        }
                    }
                    continue;
                }

                // === /index 代码索引（RAG） ===
                if (input.startsWith("/index")) {
                    String arg = input.length() > 6 ? input.substring(6).trim() : "";
                    if (arg.isEmpty()) {
                        // 默认：开始索引
                        runIndexCommand(projectPath, renderer);
                    } else if (arg.equals("status")) {
                        try (CodeRetriever r = new CodeRetriever(projectPath)) {
                            VectorStore.IndexStats stats = r.getStats();
                            if (stats.chunkCount() == 0) {
                                renderer.info("📭 当前项目尚未索引。运行 /index 开始索引。");
                            } else {
                                System.out.println("📋 索引状态:");
                                System.out.println("  代码块: " + stats.chunkCount());
                                System.out.println("  关系数: " + stats.relationCount());
                                System.out.println("  索引时间: " + stats.indexedAt());
                            }
                        } catch (Exception e) {
                            renderer.error("查询索引状态失败: " + e.getMessage());
                        }
                    } else if (arg.equals("clear")) {
                        try (CodeRetriever r = new CodeRetriever(projectPath)) {
                            r.clearIndex();
                            renderer.info("🧹 已清空当前项目索引。");
                        } catch (Exception e) {
                            renderer.error("清空索引失败: " + e.getMessage());
                        }
                    } else if (arg.equals("rebuild")) {
                        renderer.info("🔄 重建索引...");
                        try (CodeRetriever r = new CodeRetriever(projectPath)) {
                            r.clearIndex();
                        }
                        runIndexCommand(projectPath, renderer);
                    } else {
                        renderer.error("未知参数: " + arg + "（可用 status / clear / rebuild）");
                    }
                    continue;
                }

                // === /parallel 并行执行开关（第 7 期） ===
                if (input.startsWith("/parallel")) {
                    String arg = input.length() > 9 ? input.substring(9).trim().toLowerCase() : "";
                    if (arg.isEmpty()) {
                        renderer.info("并行执行: " + (toolRegistry.isParallelEnabled() ? "已开启" : "已关闭")
                            + "（超时 " + toolRegistry.getBatchTimeoutSeconds() + "s）");
                        renderer.info("用法: /parallel on 开启 | /parallel off 关闭 | /parallel timeout <秒数>");
                    } else if (arg.equals("on")) {
                        toolRegistry.setParallelEnabled(true);
                        renderer.info("⚡ 并行执行已开启（多 tool_calls 将并行执行）");
                    } else if (arg.equals("off")) {
                        toolRegistry.setParallelEnabled(false);
                        renderer.info("并行执行已关闭（多 tool_calls 将串行执行）");
                    } else if (arg.startsWith("timeout ")) {
                        try {
                            long t = Long.parseLong(arg.substring(8).trim());
                            toolRegistry.setBatchTimeoutSeconds(t);
                            renderer.info("并行批次超时已设为 " + t + "s");
                        } catch (NumberFormatException e) {
                            renderer.error("无效超时值: " + arg.substring(8));
                        }
                    } else {
                        renderer.error("未知参数: " + arg + "（可用 on / off / timeout <秒数>）");
                    }
                    continue;
                }

                // === /web 联网搜索/抓取（第 9 期） ===
                if (input.startsWith("/web")) {
                    String arg = input.length() > 4 ? input.substring(4).trim() : "";
                    if (arg.isEmpty()) {
                        renderer.info("用法:");
                        renderer.info("  /web search <关键词>       联网搜索");
                        renderer.info("  /web fetch <URL>           抓取网页正文");
                        renderer.info("  /web status                查看联网模块状态");
                    } else if (arg.equals("status")) {
                        SearchProvider sp = SearchProviderFactory.create(config);
                        renderer.info("🌐 联网模块状态:");
                        renderer.info("  SearchProvider: " + sp.name() + " (ready=" + sp.isReady() + ")");
                        if (!sp.isReady()) {
                            renderer.info("  提示: " + sp.unavailableHint());
                        }
                    } else if (arg.startsWith("search ")) {
                        String query = arg.substring(7).trim();
                        String result = webSearchTool.execute(java.util.Map.of("query", query, "top_k", 5));
                        System.out.println(result);
                    } else if (arg.startsWith("fetch ")) {
                        String url = arg.substring(6).trim();
                        String result = webFetchTool.execute(java.util.Map.of("url", url));
                        System.out.println(result);
                    } else {
                        renderer.error("未知子命令: " + arg + "（可用 search / fetch / status）");
                    }
                    continue;
                }

                // === /model 运行时切换 provider/model（第 8 期） ===
                if (input.startsWith("/model")) {
                    String arg = input.length() > 6 ? input.substring(6).trim() : "";
                    if (arg.isEmpty()) {
                        // 显示当前模型和可用 provider
                        renderer.info("当前模型: " + llmClientHolder[0].getModelName()
                            + " (" + llmClientHolder[0].getProviderName() + ")");
                        System.out.println("可用 provider:");
                        List<String> available = LlmClientFactory.listAvailableProviders(config);
                        for (String p : available) {
                            String mark = p.equals(llmClientHolder[0].getProviderName()) ? " *" : "  ";
                            String defModel = LlmClientFactory.defaultModel(p);
                            String curModel = config.getModel(p);
                            System.out.println(mark + " " + p
                                + (curModel != null && !curModel.isBlank() ? "  [model=" + curModel + "]"
                                : "  [default=" + defModel + "]"));
                        }
                        System.out.println("用法: /model <provider> [model]");
                        System.out.println("  例: /model glm");
                        System.out.println("  例: /model glm glm-4");
                        System.out.println("  例: /model deepseek deepseek-reasoner");
                    } else {
                        // 解析 provider [model]
                        String[] parts = arg.split("\\s+", 2);
                        String provider = parts[0];
                        String modelOverride = parts.length > 1 ? parts[1].trim() : null;
                        String normalized = LlmClientFactory.normalizeProvider(provider);
                        if (!LlmClientFactory.SUPPORTED_PROVIDERS.contains(normalized)) {
                            renderer.error("未知 provider: " + provider
                                + "（支持: " + String.join(", ", LlmClientFactory.SUPPORTED_PROVIDERS) + "）");
                            continue;
                        }
                        // 若指定了模型，临时写入 config（不持久化）
                        if (modelOverride != null && !modelOverride.isBlank()) {
                            YukiCliConfig.ProviderConfig pc = config.getProviders()
                                .computeIfAbsent(normalized, k -> new YukiCliConfig.ProviderConfig());
                            pc.setModel(modelOverride);
                        }
                        LlmClient newClient = LlmClientFactory.create(normalized, config);
                        if (newClient == null) {
                            renderer.error("切换失败: " + normalized + " 未配置 API Key");
                            continue;
                        }
                        // 重建 Agent（Agent 的 llmClient 是 final，切换必须重建）
                        Agent newAgent = new Agent(newClient, toolRegistry, renderer, SYSTEM_PROMPT);
                        try {
                            CodeRetriever sharedRetriever = new CodeRetriever(projectPath, new EmbeddingClient(config));
                            newAgent.setCodeRetriever(sharedRetriever);
                        } catch (Exception e) {
                            // RAG 初始化失败不影响主流程
                        }
                        llmClientHolder[0] = newClient;
                        agentHolder[0] = newAgent;
                        saveMemoryTool.setMemorySaver(newAgent.getMemoryManager()::storeFact);
                        renderer.info("✅ 已切换到 " + newClient.getModelName()
                            + " (" + newClient.getProviderName() + ")，对话历史已重置");
                    }
                    continue;
                }

                // === /team 多 Agent 模式 ===
                if (input.startsWith("/team")) {
                    String task = input.substring(5).trim();
                    if (task.isEmpty()) {
                        nextTaskUseTeamMode[0] = true;
                        renderer.info("下一条任务将使用 Multi-Agent 协作模式（规划者 + 执行者 + 检查者）。");
                        continue;
                    }
                    runTeamTask(task, llmClientHolder[0], toolRegistry, agentHolder[0].getMemoryManager(), renderer);
                    continue;
                }

                // === /plan 模式 ===
                if (input.startsWith("/plan")) {
                    String task = input.substring(5).trim();
                    if (task.isEmpty()) {
                        renderer.info("用法: /plan <任务描述>");
                        continue;
                    }
                    try {
                        String result = planAgent.run(task);
                        if (result != null && !result.isBlank()) {
                            System.out.println();
                            renderer.assistant(result);
                        }
                    } catch (Exception e) {
                        renderer.error("计划执行出错: " + e.getMessage());
                    }
                    continue;
                }

                // === /react 模式（默认） ===
                if (input.startsWith("/react")) {
                    input = input.substring(6).trim();
                    if (input.isEmpty()) {
                        renderer.info("已切换到 ReAct 模式。");
                        continue;
                    }
                }

                // === 默认执行 ===
                // 如果上一条 /team 没带 payload，本次任务用 Multi-Agent
                if (nextTaskUseTeamMode[0]) {
                    nextTaskUseTeamMode[0] = false;
                    runTeamTask(input, llmClientHolder[0], toolRegistry, agentHolder[0].getMemoryManager(), renderer);
                    continue;
                }

                try {
                    agentHolder[0].run(input);
                } catch (Exception e) {
                    renderer.error("执行出错: " + e.getMessage());
                }
            }
        }
    }

    /** 处理记忆相关命令（/memory, /save）。返回 true 表示已处理。 */
    private static boolean handleMemoryCommands(String input, MemoryManager mm, Renderer renderer) {
        // /memory 或 /mem - 状态
        if ("/memory".equalsIgnoreCase(input) || "/mem".equalsIgnoreCase(input)) {
            renderer.info("📋 记忆系统状态：");
            System.out.println(mm.getSystemStatus());
            System.out.println("   当前项目作用域: " + mm.getCurrentProject());
            System.out.println("   /memory list - 查看长期记忆");
            System.out.println("   /memory search <关键词> - 搜索当前项目可见长期记忆");
            System.out.println("   /memory delete <id> - 删除单条长期记忆");
            System.out.println("   /memory clear - 清空长期记忆");
            System.out.println("   /save <事实> - 保存项目级长期记忆；/save --global <事实> 保存全局记忆");
            return true;
        }

        // /memory list
        if ("/memory list".equalsIgnoreCase(input) || "/mem list".equalsIgnoreCase(input)) {
            List<MemoryEntry> entries = mm.listLongTerm();
            System.out.println("📋 长期记忆列表（" + entries.size() + " 条）:");
            if (entries.isEmpty()) {
                System.out.println("   （空）");
            } else {
                for (MemoryEntry e : entries) {
                    System.out.println("   [" + e.getId() + "] " + e.getType() + " (" + scopeOf(e) + ")");
                    System.out.println("       " + truncate(e.getContent(), 100));
                }
            }
            return true;
        }

        // /memory clear
        if ("/memory clear".equalsIgnoreCase(input) || "/mem clear".equalsIgnoreCase(input)) {
            mm.clearLongTerm();
            renderer.info("🧹 长期记忆已清空。");
            return true;
        }

        // /memory delete <id>
        if (input.regionMatches(true, 0, "/memory delete ", 0, 15)
                || input.regionMatches(true, 0, "/mem delete ", 0, 12)) {
            String id = input.contains("/memory delete ") ?
                    input.substring(15).trim() : input.substring(12).trim();
            if (id.isEmpty()) {
                renderer.error("请提供要删除的记忆 id，例如 /memory delete fact-abcd1234");
            } else if (mm.deleteLongTerm(id)) {
                renderer.info("🗑️ 已删除长期记忆: " + id);
            } else {
                renderer.error("📭 未找到长期记忆: " + id);
            }
            return true;
        }

        // /memory search <kw>
        if (input.regionMatches(true, 0, "/memory search ", 0, 15)
                || input.regionMatches(true, 0, "/mem search ", 0, 12)) {
            String query = input.contains("/memory search ") ?
                    input.substring(15).trim() : input.substring(12).trim();
            if (query.isEmpty()) {
                renderer.error("请提供搜索关键词，例如 /memory search Chrome 登录态");
            } else {
                List<MemoryEntry> entries = mm.searchLongTerm(query, 20);
                System.out.println("🔎 长期记忆搜索: " + query + "（" + entries.size() + " 条匹配）:");
                if (entries.isEmpty()) {
                    System.out.println("   （无匹配）");
                } else {
                    for (MemoryEntry e : entries) {
                        System.out.println("   [" + e.getId() + "] " + e.getType() + " (" + scopeOf(e) + ")");
                        System.out.println("       " + truncate(e.getContent(), 100));
                    }
                }
            }
            return true;
        }

        // /save [scope] <fact>
        if (input.regionMatches(true, 0, "/save ", 0, 6) || "/save".equalsIgnoreCase(input)) {
            String payload = input.length() > 5 ? input.substring(6).trim() : "";
            String scope = "project";
            String fact = payload;
            if (payload.startsWith("--global ")) {
                scope = "global";
                fact = payload.substring(9).trim();
            } else if (payload.startsWith("--project ")) {
                scope = "project";
                fact = payload.substring(10).trim();
            }
            if (fact.isEmpty()) {
                renderer.error("请提供要保存的内容，例如 /save 这个项目使用Java 17，或 /save --global 默认用中文回答");
            } else {
                mm.storeFact(fact, scope);
                renderer.info("💾 已保存到长期记忆(" + scope + "): " + fact);
            }
            return true;
        }

        return false;
    }

    /** 运行 Multi-Agent 协作任务 */
    private static void runTeamTask(String task, LlmClient llmClient, ToolRegistry toolRegistry,
                                    MemoryManager memoryManager, Renderer renderer) {
        renderer.info("👥 使用 Multi-Agent 协作模式\n");
        try {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient, toolRegistry, memoryManager,
                    new PrintStream(System.out, true, StandardCharsets.UTF_8));
            String result = orchestrator.run(task);
            if (result != null && !result.isBlank()) {
                System.out.println();
                renderer.assistant(result);
            }
        } catch (Exception e) {
            renderer.error("Multi-Agent 执行出错: " + e.getMessage());
        }
    }

    /** 运行 /index 索引命令 */
    private static void runIndexCommand(String projectPath, Renderer renderer) {
        renderer.info("🗂️ 开始索引项目: " + projectPath);
        try {
            CodeIndex indexer = new CodeIndex(msg -> System.out.println("  " + msg));
            CodeIndex.IndexResult result = indexer.index(projectPath);
            System.out.println();
            if (result.chunkCount() > 0) {
                renderer.info("✅ " + result.message());
            } else {
                renderer.error("❌ " + result.message());
            }
        } catch (Exception e) {
            renderer.error("索引失败: " + e.getMessage());
        }
    }

    /** 获取记忆条目的作用域 */
    private static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        return scope == null || scope.isBlank() ? "global" : scope;
    }

    /** 截断字符串用于显示 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
