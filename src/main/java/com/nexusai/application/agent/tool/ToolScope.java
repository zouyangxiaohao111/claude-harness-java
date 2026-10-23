package com.nexusai.application.agent.tool;

/**
 * 工具可见范围 · 本仓扩展（CC 无对应概念）。
 *
 * <p>{@link #COMMON}：通用工具，默认池与通配池都可见（CC 现有语义）。
 * <p>{@link #EXCLUSIVE}：专属工具，只有被 {@code AgentDefinition.tools} 逐名点名才可见
 * （标注方式见 {@link ExclusiveTool}）。
 *
 * <p><b>单一判定点</b>：{@link #of(Tool)} 是「某工具是不是专属」的唯一来源——
 * {@link ToolRegistry} 注册缓存与 {@link AgentToolPolicy} 装配过滤都调本方法，
 * 避免两处各自读注解读出分叉。
 */
public enum ToolScope {

    /** 通用工具：默认池可见。 */
    COMMON,

    /** 专属工具：仅被 AgentDefinition.tools 点名时可见。 */
    EXCLUSIVE;

    /**
     * 判定工具范围。
     *
     * @param tool 工具实例（null → {@link #COMMON}，防御性兜底）
     * @return 标注了 {@link ExclusiveTool} → {@link #EXCLUSIVE}，否则 {@link #COMMON}
     */
    public static ToolScope of(Tool tool) {
        if (tool == null) {
            return COMMON;
        }
        return tool.getClass().isAnnotationPresent(ExclusiveTool.class) ? EXCLUSIVE : COMMON;
    }
}
