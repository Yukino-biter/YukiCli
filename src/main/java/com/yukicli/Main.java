package com.yukicli;

import com.yukicli.agent.Agent;
import com.yukicli.agent.PlanExecuteAgent;
import com.yukicli.config.YukiCliConfig;
import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmClientFactory;
import com.yukicli.llm.OpenAiCompatibleClient;
import com.yukicli.render.PlainRenderer;
import com.yukicli.render.Renderer;
import com.yukicli.tool.ToolRegistry;
import com.yukicli.tool.tools.*;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * YukiCli 入口类。
 *
 * 启动流程：
 *   1. 设置 UTF-8 输出流
 *   2. 显示雪花图标
 *   3. 加载 LLM 配置
 *   4. 注册工具
 *   5. 启动交互式循环（支持 ReAct 和 Plan 两种模式）
 */
public class Main {

    private static final String SNOWFLAKE = "\u2744"; // 雪花 Unicode

    private static final String SYSTEM_PROMPT = """
            你是 YukiCli，一个运行在终端的 AI 编程助手（类似 Claude Code）。
            你可以通过调用工具来读取文件、写入文件、列出目录、执行命令和创建项目。

            工作原则：
            1. 先理解用户需求，再选择合适的工具
            2. 工具调用后，基于返回结果继续推理
            3. 用中文回复用户
            4. 涉及危险操作（如删除文件）时先向用户确认

            可用工具：read_file / write_file / list_dir / execute_command / create_project
            """;

    public static void main(String[] args) {
        // 1. 设置 UTF-8 输出流（修复 Windows 终端特殊字符显示问号问题）
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

        renderer.info("已加载 " + toolRegistry.getAllTools().size() + " 个工具。");
        renderer.info("当前工作目录: " + Path.of(".").toAbsolutePath().normalize());
        renderer.info("命令：/plan 规划执行 | /react 普通对话（默认）| /clear 清空 | /exit 退出\n");

        // 5. 创建 Agent
        Agent reactAgent = new Agent(llmClient, toolRegistry, renderer, SYSTEM_PROMPT);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, toolRegistry, renderer);

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

                // 命令处理
                if ("/exit".equals(input) || "/quit".equals(input)) {
                    renderer.info("再见！");
                    break;
                }
                if ("/clear".equals(input)) {
                    reactAgent.clearHistory();
                    renderer.info("对话已清空。");
                    continue;
                }

                // /plan 模式
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

                // /react 模式（默认）
                if (input.startsWith("/react")) {
                    input = input.substring(6).trim();
                    if (input.isEmpty()) {
                        renderer.info("已切换到 ReAct 模式。");
                        continue;
                    }
                }

                // 默认 ReAct 模式
                try {
                    reactAgent.run(input);
                } catch (Exception e) {
                    renderer.error("执行出错: " + e.getMessage());
                }
            }
        }
    }
}
