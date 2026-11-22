package com.nexusai.domain.settings;

/**
 * AST 结构化工具（AstGrep/AstEdit）的内置参数 · <b>固定值，不提供运行期配置</b>。
 *
 * <p>WHY 不开放配置：工具面板里其它工具都没有「结果上限 / 超时」这类项；AST 工具单独开一项会让
 * 同类工具出现两套使用方式。需要调整时改本类常量并随版本发布，⛔ 不走 DB / 端点。
 */
public final class AstToolsConfig {

    /** AstGrep 每页匹配上限（{@code limit} 的默认值与硬上限）。 */
    public static final int MAX_MATCHES = 50;

    /** 每次 ast-grep CLI 调用的超时（AstEdit 多个 op 时逐 op 生效，最坏 ops × 本值）。 */
    public static final int TIMEOUT_MS = 20_000;

    private AstToolsConfig() {
    }
}
