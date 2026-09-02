package com.nexusai.application.agent.memory;

/**
 * Memory 子系统常量 · 对齐 CC memdir.ts:34-38 + memoryScan.ts:21
 *
 * <p>集中管理所有阈值，避免分散在各文件中的魔术数字。
 *
 */
public final class MemoryConstants {
    private MemoryConstants() {}

    /** CC memdir.ts:34 ENTRYPOINT_NAME = 'MEMORY.md' */
    public static final String MEMORY_INDEX_NAME = "MEMORY.md";

    /** CC memoryScan.ts:21 MAX_MEMORY_FILES = 200 */
    public static final int MEMORY_MAX_FILES = 200;
}
