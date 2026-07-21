package com.yukicli.tool.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yukicli.tool.AbstractTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行 Shell 命令工具。
 * 第一期为简化版，第 6 期会加入 CommandGuard 安全策略。
 */
public class ExecuteCommandTool extends AbstractTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Override
    public String getName() { return "execute_command"; }

    @Override
    public String getDescription() {
        return "执行 Shell 命令并返回输出。默认超时 30 秒。";
    }

    @Override
    protected ObjectNode buildSchema() {
        ObjectNode schema = objectSchema();
        addProperty(schema, "command", stringProp("要执行的命令"), true);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return "[error] 缺少参数: command";
        }

        // CommandGuard 快速拒绝黑名单（命中抛 PolicyException，交给 ToolRegistry 写 audit）
        String denyReason = com.yukicli.policy.CommandGuard.check(command);
        if (denyReason != null) {
            throw new com.yukicli.policy.PolicyException(denyReason);
        }

        try {
            // Windows 用 cmd /c，其他系统用 sh -c
            String[] cmd;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                cmd = new String[]{"cmd", "/c", command};
            } else {
                cmd = new String[]{"sh", "-c", command};
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "[error] 命令执行超时 (" + DEFAULT_TIMEOUT_SECONDS + "s): " + command;
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (exitCode != 0) {
                result = (result.isEmpty() ? "" : result + "\n") + "[exit code: " + exitCode + "]";
            }
            return result.isEmpty() ? "[无输出]" : result;
        } catch (com.yukicli.policy.PolicyException e) {
            throw e;
        } catch (Exception e) {
            return "[error] 命令执行失败: " + e.getMessage();
        }
    }
}
