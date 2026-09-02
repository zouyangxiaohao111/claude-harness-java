package com.nexusai.application.agent.workflow;

/**
 * 不透明 host 句柄 · CC original: {@code HostHandle}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:14-37)。
 *
 * <p><b>唯一不透明耦合缝</b>：核心侧每次工具调用构造一个，内含
 * toolUseContext/canUseTool/parentMessage 等；引擎包<b>只透传不检查</b>。
 *
 * <p>CC 用 {@code Symbol('workflow.hostHandle')} 隐藏载荷 + 类型守卫
 * （{@code isHostHandle}）——Java 用 {@link #create(Object)} 私有构造 +
 * {@link #isHostHandle(Object)} 守卫 + {@link #unwrap(HostHandle)} 解包（仅 adapter 可调）。
 *
 * @see WorkflowHostBundle
 */
public final class HostHandle {

    /** 内部载荷（WorkflowHostBundle 等，对引擎 opaque） */
    private final Object bundle;

    private HostHandle(Object bundle) {
        this.bundle = bundle;
    }

    /**
     * 包装任意 bundle 成不透明句柄 · CC original: {@code createHostHandle(bundle)}
     * (ports.ts:21-23)。
     *
     * @param bundle 核心侧载荷（WorkflowHostBundle）
     * @return 不透明 HostHandle
     */
    public static HostHandle create(Object bundle) {
        return new HostHandle(bundle);
    }

    /**
     * 类型守卫 · CC original: {@code isHostHandle(value)} (ports.ts:26-32)。
     *
     * @param value 待判对象
     * @return 是否为本句柄类型
     */
    public static boolean isHostHandle(Object value) {
        return value instanceof HostHandle;
    }

    /**
     * 解包载荷（仅核心侧 adapter 调用）· CC original: {@code unwrapHostHandle(handle)}
     * (ports.ts:35-37)。
     *
     * @param handle 不透明句柄
     * @return 内部载荷（WorkflowHostBundle）
     */
    public static Object unwrap(HostHandle handle) {
        return handle != null ? handle.bundle : null;
    }
}
