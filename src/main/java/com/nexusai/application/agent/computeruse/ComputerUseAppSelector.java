package com.nexusai.application.agent.computeruse;

/**
 * 观察对象选择器 · <b>对齐 dsh {@code computer_observe} 的 {@code app} 入参</b>
 * （{@code src/tools.ts:363} {@code app: { ...appSelectorSchema, required: true }}）。
 *
 * <h2>dsh 的原始形状（{@code src/tools.ts:60-66}）</h2>
 * <pre>
 * const appSelectorSchema = {
 *   type: 'object',
 *   additionalProperties: false,
 *   properties: {
 *     bundleId: { type: 'string', description: 'Preferred exact macOS bundle identifier.' },
 *     pid:       { type: 'integer', description: 'Exact current process id when already observed.' },
 *     name:      { type: 'string', description: 'Display name accepted only when it resolves uniquely.' },
 *   },
 * }
 * </pre>
 *
 * <h2>WHY 三个字段而不是一个</h2>
 * <ul>
 *   <li><b>bundleId</b>：macOS 上最精确（{@code com.apple.TextEdit}）—— dsh 明确「preferred」；</li>
 *   <li><b>pid</b>：应用重启后 pid 会变 ⇒ pid 只在「刚观察过」时可靠；</li>
 *   <li><b>name</b>：显示名（如「文本编辑」）<b>只在唯一匹配时接受</b> ——
 *       dsh 原文「Display name accepted only when it resolves uniquely」，
 *       本仓实现同样：多个同名 ⇒ 明确失败、不猜。</li>
 * </ul>
 *
 * <h2>优先级（对齐 dsh {@code resolveApp}）</h2>
 * <p>pid 精确 → bundleId 精确 → name 唯一匹配。<b>解析失败必须显式报错</b>，
 * 不得静默回落到前台应用（否则模型以为在看 A、实际看的是 B）。
 */
public record ComputerUseAppSelector(String bundleId, long pid, String name) {

    public ComputerUseAppSelector {
        bundleId = bundleId == null ? "" : bundleId.trim();
        name = name == null ? "" : name.trim();
    }

    /** 空选择器 ⇒ 观察前台应用。 */
    public static ComputerUseAppSelector frontmost() {
        return new ComputerUseAppSelector("", 0L, "");
    }

    /** @return 是否至少给了一项定位信息 */
    public boolean isPresent() {
        return !bundleId.isEmpty() || pid > 0 || !name.isEmpty();
    }

    /** @return 是否为空选择器（观察前台应用） */
    public boolean isEmpty() {
        return !isPresent();
    }
}
