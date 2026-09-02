package com.nexusai.application.agent.tasks;

import java.security.SecureRandom;

/**
 * 任务 ID 生成器 — 对齐 CC Task.ts:98-106 generateTaskId
 *
 * <p>CC 源码 (Task.ts:98-106):
 * <pre>
 * export function generateTaskId(type: TaskType): string {
 *   const bytes = randomBytes(8);
 *   let id = TASK_ID_PREFIXES[type];
 *   for (const byte of bytes) {
 *     id += BASE36[byte % 36];
 *   }
 *   return id;
 * }
 * </pre>
 *
 * <p>格式: prefix + base36(randomBytes(8)) = 9 字符, <b>无横杠分隔符</b>
 */
public class TaskIdGenerator {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz";

    /**
     * 生成任务 ID — 对齐 CC Task.ts:98-106
     *
     * @param type 任务类型 (决定 ID 前缀)
     * @return 9 字符 ID (1 前缀 + 8 base36), 无分隔符
     */
    public static String generate(TaskType type) {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);

        StringBuilder sb = new StringBuilder(9);
        sb.append(type.getIdPrefix()); // 1 char prefix
        for (byte b : bytes) {
            sb.append(BASE36.charAt((b & 0xFF) % 36));
        }
        return sb.toString(); // 9 chars, no dash — CC generateTaskId
    }
}
