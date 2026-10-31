package com.nexusai.application.agent.computeruse;

/**
 * computer-use 授权档位 · 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/types.ts} 的 {@code CuAppPermTier}
 * （{@code 'read' | 'click' | 'full'}）。
 *
 * <p><b>CCB 真源</b>：
 * <ul>
 *   <li>取值域 = {@code 'read' | 'click' | 'full'}（CCB {@code deniedApps.ts:45-51}
 *       {@code categoryToTier} 的返回类型 / {@code types.ts:64-65} 的 {@code AppGrant.tier}）</li>
 *   <li>缺省语义 = {@code tier = grantTier ?? 'full'}（CCB {@code toolCalls.ts:361}）——
 *       <b>只有 null/undefined 才回落 full</b>；一个"不认识"的档位串<b>不会</b>被当成 full</li>
 * </ul>
 *
 * <p><b>为什么枚举里没有 UNKNOWN</b>：档位串只可能来自
 * {@link ComputerUseAppPolicy#defaultTier}（本仓自己写，恒是三档之一）或历史脏数据。
 * 脏数据必须按 CCB 语义处理（"不认识" ⇒ 除 mouse_position 外一律不通过），所以
 * {@link #fromWire(String)} 对未知串返回 {@code null}，由
 * {@link ComputerUseTierGate#tierSatisfies(String, ComputerUseActionKind)} 走
 * 「null ⇒ full」的分支前先判空 —— 见该类 javadoc 的逐字说明。
 */
public enum ComputerUseTier {

    /**
     * 只读档 · 浏览器类应用（CCB {@code deniedApps.ts:48} {@code categoryToTier}：
     * {@code 'browser' | 'trading' ⇒ 'read'}）。
     *
     * <p>语义 = 只在截图里可见，<b>不能点、不能打字</b>（CCB {@code toolCalls.ts:341-343}）。
     */
    READ("read"),

    /**
     * 点击档 · 终端 / IDE 类应用（CCB {@code deniedApps.ts:49} {@code 'terminal' ⇒ 'click'}）。
     *
     * <p>语义 = 可见 + **纯左键点击**；禁打字 / 按键 / 右键 / 修饰键点击 / 拖放
     * （CCB {@code toolCalls.ts:346-352}）。
     */
    CLICK("click"),

    /**
     * 完整档 · 其它一切应用（CCB {@code deniedApps.ts:50} 兜底 {@code ⇒ 'full'}）。
     *
     * <p>语义 = 全部输入动作放行（含键盘）。
     */
    FULL("full");

    /** wire 值（CCB 的档位串本体）。 */
    private final String wire;

    ComputerUseTier(String wire) {
        this.wire = wire;
    }

    /**
     * @return wire 值（{@code "read"} / {@code "click"} / {@code "full"}）
     */
    public String wire() {
        return wire;
    }

    /**
     * wire 串 → 枚举。
     *
     * <p><b>CCB 语义</b>：{@code grantTier ?? 'full'} 只对 null/undefined 回落 full；
     * 一个未知档位串在 CCB 里保持原样参与比较（既不等于 'full' 也不等于 'click'）
     * ⇒ 等价于"只放行 mouse_position"。
     *
     * @param wire 档位串（可 null / 空白）
     * @return 对应枚举；<b>null / 空白 ⇒ {@link #FULL}</b>（CCB 的 {@code ?? 'full'}）；
     *         <b>未知串 ⇒ null</b>（调用方须按"未知档位"处理，见类 javadoc）
     */
    public static ComputerUseTier fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return FULL;
        }
        for (ComputerUseTier tier : values()) {
            if (tier.wire.equals(wire)) {
                return tier;
            }
        }
        return null;
    }
}
