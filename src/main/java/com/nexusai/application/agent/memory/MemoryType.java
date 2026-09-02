package com.nexusai.application.agent.memory;

/**
 * 记忆类型枚举 · 对齐 CC memoryTypes.ts:14-21 四类封闭分类体系
 *
 * <p>CC 定义四种类型：user / feedback / project / reference。
 * 代码模式、架构、git 历史等项目可推导内容不属于记忆。
 */
public enum MemoryType {
    USER,
    FEEDBACK,
    PROJECT,
    REFERENCE;

    /**
     * 从字符串解析 MemoryType · 对齐 CC memoryTypes.ts:28-31 parseMemoryType()
     *
     * <p>CC original（memoryTypes.ts:28-31）：
     * <pre>{@code
     * export function parseMemoryType(raw: unknown): MemoryType | undefined {
     *   if (typeof raw !== 'string') return undefined
     *   return MEMORY_TYPES.find(t => t === raw)
     * }
     * }</pre>
     * 无效或缺失返回 {@code null}（对应 CC undefined）——legacy 无 {@code type:} 字段的文件继续可用，
     * 未知类型优雅降级，<b>不</b>污染记忆体系（formatMemoryManifest 对 null 省略 {@code [type]} 标签，
     * memoryScan.ts:87 {@code tag = m.type ? `[${m.type}] ` : ''}）。
     *
     * @param value frontmatter {@code type:} 字段值（可为 null）
     * @return 四类之一；无效/缺失 → null（CC undefined）
     */
    public static MemoryType fromString(String value) {
        if (value == null) return null;
        // CC parseMemoryType 大小写敏感精确匹配（t === raw，MEMORY_TYPES 全小写）—— 大写/混合大小写
        // 'USER'/'User' 一律降级 null，不得宽松（旧实现 switch(value.toLower…()) 是偏差，见 memoryTypes.ts:28-31）。
        switch (value) {
            case "user": return USER;
            case "feedback": return FEEDBACK;
            case "project": return PROJECT;
            case "reference": return REFERENCE;
            default: return null;
        }
    }

    /** 小写形式，用于 frontmatter 中的 type: 字段值 */
    public String toTypeValue() {
        return name().toLowerCase();
    }
}
