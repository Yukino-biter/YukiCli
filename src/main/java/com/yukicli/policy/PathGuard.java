package com.yukicli.policy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径围栏 —— 把所有文件类工具的路径参数约束在项目根内。
 *
 * 拦截三类越界：
 *   1. 绝对路径逃出项目根（如 /etc/passwd）
 *   2. 相对路径用 .. 穿越（如 ../../etc/passwd）
 *   3. 符号链接逃逸（项目内的软链指向外部）
 *
 * 实现要点：
 *   - 对目标路径先 toAbsolutePath().normalize()，再尝试 toRealPath() 解析符号链接
 *   - 目标路径可能尚不存在（write_file 创建新文件），用 resolveRealPath
 *     向上找最近的存在祖先做 toRealPath()，再把剩余段接回
 *   - 越界抛 {@link PolicyException}，由 ToolRegistry 统一捕获
 */
public class PathGuard {

    private Path rootPath;

    public PathGuard(String root) {
        if (root == null || root.isBlank()) {
            root = System.getProperty("user.dir");
        }
        try {
            this.rootPath = Paths.get(root).toAbsolutePath().normalize();
            if (Files.exists(this.rootPath)) {
                this.rootPath = this.rootPath.toRealPath();
            }
        } catch (Exception e) {
            // toRealPath 失败时退化到 normalize 后的路径
            this.rootPath = Paths.get(root).toAbsolutePath().normalize();
        }
    }

    /** 获取项目根（已展开符号链接） */
    public Path getRootPath() {
        return rootPath;
    }

    /** 更新项目根（/cd 命令或切换工作目录时调用） */
    public void setRootPath(String root) {
        PathGuard tmp = new PathGuard(root);
        this.rootPath = tmp.rootPath;
    }

    /**
     * 把输入路径安全解析为项目根内的绝对路径。
     *
     * @param input 用户或 LLM 提供的路径字符串（绝对/相对/含 .. 都可以）
     * @return 已解析的绝对路径（已展开符号链接）
     * @throws PolicyException 越界时抛出
     */
    public Path resolveSafe(String input) {
        if (input == null || input.isBlank()) {
            throw new PolicyException("路径为空");
        }

        Path raw = Paths.get(input);
        Path absolute = raw.isAbsolute() ? raw : rootPath.resolve(raw);
        absolute = absolute.normalize();

        // 对存在的部分做 toRealPath 解析符号链接
        Path resolved = resolveRealPath(absolute);

        // 检查是否在项目根内
        if (!startsWith(resolved, rootPath) && !startsWith(resolved, rootPath.getParent())) {
            throw new PolicyException(
                "路径越界（不在项目根内）: " + input + " → " + resolved
                + "（项目根: " + rootPath + "）");
        }

        return resolved;
    }

    /**
     * 对可能尚不存在的路径做 toRealPath：向上找最近的存在祖先，对祖先 toRealPath，
     * 再把剩余段拼接回去。这样能识别中段符号链接越界（项目内软链指向外部）。
     */
    private Path resolveRealPath(Path path) {
        Path normalized = path.normalize();
        try {
            if (Files.exists(normalized)) {
                return normalized.toRealPath();
            }
        } catch (Exception e) {
            return normalized;
        }

        // 向上找最近的存在祖先
        Path existingAncestor = normalized.getRoot();
        Path relative = null;
        for (Path current = normalized; current != null && !current.equals(normalized.getRoot()); current = current.getParent()) {
            if (Files.exists(current)) {
                existingAncestor = current;
                relative = existingAncestor.relativize(normalized);
                break;
            }
        }
        if (relative == null) {
            // 整条路径都不存在（含根目录都不可达），直接返回 normalized
            return normalized;
        }

        try {
            Path realAncestor = existingAncestor.toRealPath();
            return realAncestor.resolve(relative).normalize();
        } catch (Exception e) {
            return normalized;
        }
    }

    /** path.startsWith(root) 的等价判断（兼容 Windows 大小写） */
    private boolean startsWith(Path path, Path root) {
        if (path == null || root == null) return false;
        if (path.equals(root)) return true;
        return path.startsWith(root);
    }
}
