package com.yukicli.policy;

import java.util.regex.Pattern;

/**
 * 命令围栏 —— 对 execute_command 的命令字符串做快速拒绝黑名单匹配。
 *
 * 定位：辅助 HITL 而非主防线。黑名单永远列不全，真正责任在 HITL 审批和用户判断。
 * 命中任何一条规则返回拒绝原因字符串；未命中返回 null（放行）。
 *
 * 拦截规则（与 paicli 对齐）：
 *   1. sudo 提权
 *   2. rm -rf / / rm -rf ~ / rm -rf $HOME
 *   3. mkfs 格式化
 *   4. dd ... of=/dev/... 写裸设备
 *   5. fork bomb :(){ :|:& };:
 *   6. curl/wget | sh 远端脚本执行
 *   7. find / / find ~ 全盘扫描
 *   8. chmod -R 777 /
 *   9. shutdown / reboot / halt / poweroff
 */
public final class CommandGuard {

    private CommandGuard() {}

    // 规则按"危险等级 + 拒绝原因"配对
    private static final Rule[] RULES = {
        new Rule("\\bsudo\\b", "禁止使用 sudo 提权"),
        new Rule("\\brm\\s+(-[a-zA-Z]*r[a-zA-Z]*f?|--recursive[^|]*-f|-rf?\\s+--recursive)\\s+(/|~|\\$HOME)(\\s|$)", "禁止递归删除根目录/家目录"),
        new Rule("\\bmkfs\\b", "禁止格式化磁盘"),
        new Rule("\\bdd\\b.*\\bof=/dev/", "禁止写裸设备"),
        new Rule(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;\\s*:", "禁止 fork bomb"),
        new Rule("\\b(curl|wget)\\b.*\\|\\s*(sh|bash|zsh)\\b", "禁止管道执行远端脚本"),
        new Rule("\\bfind\\s+(/|~|\\$HOME)(\\s|$)", "禁止全盘扫描"),
        new Rule("\\bchmod\\s+(-R\\s+)?777\\s+/", "禁止对根目录 chmod 777"),
        new Rule("\\b(shutdown|reboot|halt|poweroff)\\b", "禁止关机/重启"),
    };

    /**
     * 检查命令是否命中黑名单。
     *
     * @param command 待检查的命令字符串
     * @return null 表示放行；非 null 表示拒绝原因
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) return null;
        for (Rule rule : RULES) {
            if (rule.pattern.matcher(command).find()) {
                return rule.reason;
            }
        }
        return null;
    }

    private static class Rule {
        final Pattern pattern;
        final String reason;
        Rule(String regex, String reason) {
            this.pattern = Pattern.compile(regex);
            this.reason = reason;
        }
    }
}
