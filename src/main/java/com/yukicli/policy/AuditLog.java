package com.yukicli.policy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 审计日志 —— 把所有危险工具调用（含 HITL 决策和策略拒绝）以 JSONL 落盘。
 *
 * 文件位置：
 *   - 默认 ~/.yukicli/audit/audit-YYYY-MM-DD.jsonl
 *   - 可由 -Dyukicli.audit.dir 或环境变量 YUKICLI_AUDIT_DIR 覆盖
 *
 * 写入策略：
 *   - 一行一条 JSON，按天分文件
 *   - 写入失败只 stderr 提示，不抛出（不阻塞 Agent 主循环）
 *   - 自动脱敏 Bearer / token= / password= 等敏感字段
 */
public class AuditLog {

    public static final String APPROVER_HITL = "hitl";
    public static final String APPROVER_POLICY = "policy";

    public static final String OUTCOME_ALLOW = "allow";
    public static final String OUTCOME_DENY = "deny";
    public static final String OUTCOME_ERROR = "error";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
        "(?i)(Bearer\\s+\\S+|token=[^&\\s\"']+|password=[^&\\s\"']+|api[_-]?key=[^&\\s\"']+)");

    private final Path auditDir;

    public AuditLog() {
        this(resolveDefaultDir());
    }

    public AuditLog(Path auditDir) {
        this.auditDir = auditDir;
        try {
            Files.createDirectories(auditDir);
        } catch (IOException e) {
            System.err.println("[AuditLog] 无法创建审计目录: " + auditDir + " - " + e.getMessage());
        }
    }

    /** 写入一条审计记录 */
    public void record(AuditEntry entry) {
        try {
            Path file = auditDir.resolve("audit-" + LocalDateNow() + ".jsonl");
            String json = MAPPER.writeValueAsString(entry.toMap());
            Files.writeString(file, json + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[AuditLog] 写入失败: " + e.getMessage());
        }
    }

    /** 读取今天的审计记录（最多 n 条，按时间倒序） */
    public java.util.List<AuditEntry> readToday(int n) {
        return readFile(auditDir.resolve("audit-" + LocalDateNow() + ".jsonl"), n);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<AuditEntry> readFile(Path file, int n) {
        java.util.List<AuditEntry> list = new java.util.ArrayList<>();
        if (!Files.exists(file)) return list;
        try {
            java.util.List<String> lines = Files.readAllLines(file);
            for (int i = lines.size() - 1; i >= 0 && list.size() < n; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> m = MAPPER.readValue(line, Map.class);
                    list.add(AuditEntry.fromMap(m));
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return list;
    }

    private static Path resolveDefaultDir() {
        String dir = System.getProperty("yukicli.audit.dir");
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("YUKICLI_AUDIT_DIR");
        }
        if (dir == null || dir.isBlank()) {
            dir = Paths.get(System.getProperty("user.home"), ".yukicli", "audit").toString();
        }
        return Paths.get(dir);
    }

    private static String LocalDateNow() {
        return LocalDateTime.now().format(DATE_FMT);
    }

    private static String now() {
        return LocalDateTime.now().format(DATETIME_FMT);
    }

    /** 脱敏：把 Bearer / token= / password= 等替换为 *** */
    public static String sanitize(String input) {
        if (input == null) return null;
        return SENSITIVE_PATTERN.matcher(input).replaceAll("***");
    }

    /**
     * 审计条目 record。
     */
    public record AuditEntry(
            String timestamp,
            String tool,
            String args,
            String outcome,
            String reason,
            String approver,
            long durationMs) {

        public static AuditEntry allow(String tool, String args, long durationMs) {
            return new AuditEntry(now(), tool, sanitize(args), OUTCOME_ALLOW, "", "", durationMs);
        }

        public static AuditEntry denyByHitl(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(now(), tool, sanitize(args), OUTCOME_DENY, reason, APPROVER_HITL, durationMs);
        }

        public static AuditEntry denyByPolicy(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(now(), tool, sanitize(args), OUTCOME_DENY, reason, APPROVER_POLICY, durationMs);
        }

        public static AuditEntry error(String tool, String args, String reason, long durationMs) {
            return new AuditEntry(now(), tool, sanitize(args), OUTCOME_ERROR, reason, "", durationMs);
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "timestamp", timestamp,
                "tool", tool,
                "args", args,
                "outcome", outcome,
                "reason", reason == null ? "" : reason,
                "approver", approver == null ? "" : approver,
                "durationMs", durationMs);
        }

        @SuppressWarnings("unchecked")
        public static AuditEntry fromMap(Map<String, Object> m) {
            return new AuditEntry(
                String.valueOf(m.get("timestamp")),
                String.valueOf(m.get("tool")),
                String.valueOf(m.get("args")),
                String.valueOf(m.get("outcome")),
                String.valueOf(m.getOrDefault("reason", "")),
                String.valueOf(m.getOrDefault("approver", "")),
                m.get("durationMs") instanceof Number n ? n.longValue() : 0L);
        }
    }
}
