package com.yukicli;

import com.yukicli.agent.Agent;
import com.yukicli.agent.AgentOrchestrator;
import com.yukicli.agent.PlanExecuteAgent;
import com.yukicli.config.YukiCliConfig;
import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmClientFactory;
import com.yukicli.llm.OpenAiCompatibleClient;
import com.yukicli.memory.MemoryEntry;
import com.yukicli.memory.MemoryManager;
import com.yukicli.render.PlainRenderer;
import com.yukicli.render.Renderer;
import com.yukicli.tool.ToolRegistry;
import com.yukicli.tool.tools.*;

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
            你可以通过调用工具来读取文件、写入文件、列出目录、执行命令、创建项目和保存长期记忆。

            工作原则：
            1. 先理解用户需求，再选择合适的工具
            2. 工具调用后，基于返回结果继续推理
            3. 用中文回复用户
            4. 涉及危险操作（如删除文件）时先向用户确认
            5. 当用户明确说"记一下""记住""以后记得"时，调用 save_memory 工具保存为长期事实

            可用工具：read_file / write_file / list_dir / execute_command / create_project / save_memory
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
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            renderer.error("未找到可用的 API Key");
            renderer.info("请在 .env 文件中配置 GLM_API_KEY、DEEPSEEK_API_KEY、KIMI_API_KEY 或 OPENAI_API_KEY");
            renderer.info("（参考 .env.example）");
            return;
        }

        // 显示当前模型信息
        if (llmClient instanceof OpenAiCompatibleClient openai) {
            renderer.info("模型: " + openai.getModelName() + " (" + openai.getProviderName() + ")");
        }

        // 4. 注册工具
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new ListDirTool());
        toolRegistry.register(new ExecuteCommandTool());
        toolRegistry.register(new CreateProjectTool());
        SaveMemoryTool saveMemoryTool = new SaveMemoryTool();
        toolRegistry.register(saveMemoryTool);

        renderer.info("已加载 " + toolRegistry.getAllTools().size() + " 个工具。");
        renderer.info("当前工作目录: " + Path.of(".").toAbsolutePath().normalize());
        renderer.info("命令：/plan 规划执行 | /team 多Agent协作 | /react 普通对话（默认）");
        renderer.info("记忆：/memory 状态 | /save <事实> 保存 | /compact 压缩 | /clear 清空 | /exit 退出\n");

        // 5. 创建 Agent
        Agent reactAgent = new Agent(llmClient, toolRegistry, renderer, SYSTEM_PROMPT);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, renderer);

        // 把 Agent 的 MemoryManager 注入到 save_memory 工具
        saveMemoryTool.setMemorySaver(reactAgent.getMemoryManager()::storeFact);

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
                    reactAgent.clearHistory();
                    renderer.info("对话已清空。");
                    continue;
                }

                // === 记忆相关命令 ===
                if (handleMemoryCommands(input, reactAgent.getMemoryManager(), renderer)) {
                    continue;
                }

                // === /compact 手动压缩 ===
                if ("/compact".equals(input)) {
                    boolean compacted = reactAgent.compactHistoryNow();
                    if (compacted) {
                        renderer.info("📦 已把早期对话压缩为摘要。");
                    } else {
                        renderer.info("对话历史较短，未触发压缩。");
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
                    runTeamTask(task, llmClient, toolRegistry, reactAgent.getMemoryManager(), renderer);
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
                    runTeamTask(input, llmClient, toolRegistry, reactAgent.getMemoryManager(), renderer);
                    continue;
                }

                try {
                    reactAgent.run(input);
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
