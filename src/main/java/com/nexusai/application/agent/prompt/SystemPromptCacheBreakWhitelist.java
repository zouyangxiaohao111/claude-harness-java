package com.nexusai.application.agent.prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>重算白名单</b> · 允许 {@code cacheBreak=true}（每轮强制重算并覆盖缓存）的段名清单。
 *
 * <p><b>WHY 需要它</b>：{@code cacheBreak=true} 的段会<b>毁掉其后所有前缀</b>
 * （每轮重算 ⇒ 值一变，其后全部 token 由 cache hit 变 cache_creation）。Java 侧把
 * 「破坏缓存必须显式且需要理由」这条纪律<b>显式化</b>为白名单：段名不在白名单 ⇒
 * 构造期直接 <b>fail loud</b>（{@link IllegalArgumentException}），⛔ 不允许
 * 「顺手加一个易失段」悄悄打掉前缀缓存。
 *
 * <p><b>当前条目数 = 0（空表）</b>，对齐目标 CC <b>2.1.278</b> 的实测事实：
 * <ul>
 *   <li>发行产物 2.1.278（Bun 单文件，{@code bin/claude.exe}）里 {@code cacheBreak:!0} <b>0 处</b>、
 *       {@code cacheBreak:true} <b>0 处</b>，仅 {@code cacheBreak:!1} 1 处；</li>
 *   <li>独立段名 {@code mcp_instructions} <b>已不存在</b>（11 次命中全是
 *       {@code mcp_instructions_delta} 9 + {@code pool_change} 2）⇒ MCP 指令在 2.1.278 走
 *       <b>delta 尾部附件</b>，不再是每轮重算的 system 段。</li>
 * </ul>
 * ⇒ 2.1.278 口径下「全份系统提示里没有一个段每轮破坏缓存」，故本表<b>置空</b>。
 * ⛔ 置空 ≠ 删机制：工厂 + 白名单 + record 构造器的<b>双点 fail-loud 全部保留</b>，
 * 新增易失段仍然必须显式登记理由（登记纪律见下）。
 *
 * <p><b>历史（CC 2.1.88 口径 · 已不是当前依据，留作审计对照）</b>：2.1.88 整份系统提示里
 * {@code cacheBreak=true} 的段<b>恰好 1 处</b> —— 段名 {@code mcp_instructions}，理由字符串
 * {@code 'MCP servers connect/disconnect between turns'}（{@code constants/prompts.ts:513-520}，
 * Open-ClaudeCode = 官方 2.1.88 逐字节抽取）。本仓当时把这条「恰好一处」的不变量逐字落成了
 * 本表的唯一条目；2.1.278 已把该段整体删除 ⇒ 本表随之清空。
 *
 * <p><b>登记纪律（不变）</b>：新增白名单条目 = 在 {@link #ALLOWED} 里显式写「段名 → 理由」，
 * 理由必须说明「为什么该段每轮都可能变」。⛔ 无理由 / 理由为空白一律拒绝进表。
 */
public final class SystemPromptCacheBreakWhitelist {

    /**
     * 段名 → 允许 cacheBreak=true 的理由（插入序保持，便于日志/审计稳定输出）。
     *
     * <p><b>当前为空表（0 条）</b>：对齐 CC 2.1.278「{@code cacheBreak:true} 计数 0」。
     * 若将来确需新增易失段，在此 {@code m.put(段名, 理由)} 并同步更新
     * {@code SystemPromptSections.dangerousUncachedSystemPromptSection} 的 javadoc。
     */
    private static final Map<String, String> ALLOWED;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // [C4-A2 · 2026-09-21] 对齐目标 CC 2.1.278 ⇒ 0 条。
        // 原唯一条目（2.1.88 逐字对齐）：
        //   DANGEROUS_uncachedSystemPromptSection('mcp_instructions', ..., 'MCP servers connect/disconnect between turns')
        //   (Open-ClaudeCode/src/constants/prompts.ts:513-520)
        // 2.1.278 已删除该段（独立段名 0 命中；cacheBreak 计数 0），mcp_instructions 改走
        // 普通可缓存段（会话级冻结，见 SystemPromptSections.buildDynamicSections 第 7 条登记注释）。
        ALLOWED = Collections.unmodifiableMap(m);
    }

    private SystemPromptCacheBreakWhitelist() {
    }

    /**
     * 该段名是否允许 cacheBreak=true。
     *
     * @param name 段名（null → false）
     * @return 在白名单内则 true（当前白名单为空 ⇒ 恒 false）
     */
    public static boolean isAllowed(String name) {
        return name != null && ALLOWED.containsKey(name);
    }

    /**
     * 白名单内该段名的登记理由（未登记 → null）。
     *
     * @param name 段名
     * @return 登记理由（附 CC 真源出处）；当前空表 ⇒ 恒 null
     */
    public static String reasonFor(String name) {
        return name != null ? ALLOWED.get(name) : null;
    }

    /** 全部已登记段名（只读快照 · 测试 / 审计用）。当前为空集。 */
    public static Set<String> allowedNames() {
        return ALLOWED.keySet();
    }

    /** 已登记条目数（测试用：钉「对齐 2.1.278 后为 0 条」）。 */
    public static int size() {
        return ALLOWED.size();
    }
}
