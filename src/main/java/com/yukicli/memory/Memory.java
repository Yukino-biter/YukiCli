package com.yukicli.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆系统统一接口。
 *
 * 抽象短期对话记忆（{@link ConversationMemory}）和长期事实记忆（{@link LongTermMemory}）
 * 的共同行为，便于上层 MemoryManager 以统一方式管理两类记忆。
 */
public interface Memory {

    /** 存储一条记忆 */
    void store(MemoryEntry entry);

    /** 按 id 取回单条记忆 */
    Optional<MemoryEntry> retrieve(String id);

    /** 关键词检索，返回最多 limit 条匹配结果 */
    List<MemoryEntry> search(String query, int limit);

    /** 取出全部记忆条目 */
    List<MemoryEntry> getAll();

    /** 按 id 删除单条记忆，返回是否成功 */
    boolean delete(String id);

    /** 清空所有记忆 */
    void clear();

    /** 当前所有记忆占用 token 数 */
    int getTokenCount();

    /** 当前记忆条目数 */
    int size();
}
