package com.yukicli;

import com.yukicli.agent.Agent;
import com.yukicli.llm.LlmClient;
import com.yukicli.llm.LlmClientFactory;
import com.yukicli.render.PlainRenderer;
import com.yukicli.render.Renderer;
import com.yukicli.tool.ToolRegistry;
import com.yukicli.tool.tools.*;

import java.nio.file.Path;
import java.util.Scanner;

/**
 * YukiCli 入口类。
 *
 * 启动流程：
 *   1. 显示雪花图标
 *   2. 加载 LLM 配置
 *   3. 注册工具
 *   4. 启动交互式 ReAct 循环
 */
public class Main {

    private static final String SNOWFLAKE = "\u2744"; // ❄ 雪花 Unicode

    // System Prompt：定义 Agent 的行为准则
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
        // 1. 显示雪花图标
        System.out.println("\n  " + SNOWFLAKE + "  YukiCli\n");

        Renderer renderer = new PlainRenderer();

        // 2. 加载 LLM 配置
        LlmClient llmClient;
        try {
            llmClient = LlmClientFactory.create();
        } catch (Exception e) {
            renderer.error("初始化失败: " + e.getMessage());
            renderer.info("请复制 .env.example 为 .env 并配置 API_KEY。");
            return;
        }

        // 3. 注册工具
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new ListDirTool());
        toolRegistry.register(new ExecuteCommandTool());
        toolRegistry.register(new CreateProjectTool());

        renderer.info("已加载 " + toolRegistry.getAllTools().size() + " 个工具。");
        renderer.info("当前工作目录: " + Path.of(".").toAbsolutePath().normalize());
        renderer.info("输入 /exit 退出，/clear 清空对话。\n");

        // 4. 启动交互式 ReAct 循环
        Agent agent = new Agent(llmClient, toolRegistry, renderer, SYSTEM_PROMPT);

        try (Scanner scanner = new Scanner(System.in, "UTF-8")) {
            while (true) {
                System.out.print("\u001B[36m❯ \u001B[0m");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }
                if ("/exit".equals(input) || "/quit".equals(input)) {
                    renderer.info("再见！");
                    break;
                }
                if ("/clear".equals(input)) {
                    agent.clearHistory();
                    renderer.info("对话已清空。");
                    continue;
                }

                try {
                    agent.run(input);
                } catch (Exception e) {
                    renderer.error("执行出错: " + e.getMessage());
                }
            }
        }
    }
}
