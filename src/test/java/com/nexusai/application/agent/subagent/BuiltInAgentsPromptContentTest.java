package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SUB-07 D9 返工 R1] statusline-setup / verification 系统 prompt 内容对齐测试。
 *
 * <p><b>WHY（意图）</b>：D9 将两个内置 agent 的 prompt 从桩文本补全为 CC 全文
 * （statuslineSetup.ts:3-132 STATUSLINE_SYSTEM_PROMPT / verificationAgent.ts:10-129
 * VERIFICATION_SYSTEM_PROMPT），并拆装配路径使其<b>不含</b> DEFAULT_AGENT_PROMPT 前缀
 * （对齐 CC runAgent.ts:906-919 以 [agentPrompt] 直进 enhanceSystemPromptWithEnvDetails）。
 * 若未来有人把 prompt 截短 / 改回带前缀 / 增删字节，本测试必须报警 —— 否则 D9 核心内容
 * （全文 + no-DEFAULT-prefix + 字节对齐）无防回归保护。
 *
 * <p><b>口径</b>：字节长度守卫按 CC 运行时字符串 UTF-8 字节数（STL=7001 / VER=9634，
 * 返工时已按 TS 模板语义独立提取 CC 文本并与 Java 反射值逐字节 cmp BYTE-IDENTICAL）。
 * SPECIFIC 常量经反射读取（私有字段），全文经 {@link AgentDefinition#getSystemPrompt} 渲染。
 */
class BuiltInAgentsPromptContentTest {

    /** CC statuslineSetup.ts 第 1 行（模板字符串首行，运行时以该句开头）。 */
    private static final String CC_STATUSLINE_FIRST_LINE =
        "You are a status line setup agent for Claude Code.";

    /** CC verificationAgent.ts 第 1 行（模板字符串首行，运行时以该句开头）。 */
    private static final String CC_VERIFICATION_FIRST_LINE =
        "You are a verification specialist.";

    /** CC DEFAULT_AGENT_PROMPT 首句（prompts.ts:758）；D9 后 statusline/verification 必须<b>不含</b>它。 */
    private static final String DEFAULT_PREFIX_MARKER =
        "You are an agent for Claude Code";

    private static String statuslineSpecific() throws Exception {
        return readPrivateConstant("STATUSLINE_SETUP_SPECIFIC");
    }

    private static String verificationSpecific() throws Exception {
        return readPrivateConstant("VERIFICATION_SPECIFIC");
    }

    private static String readPrivateConstant(String fieldName) throws Exception {
        Field f = BuiltInAgents.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    // ── R1a 首行（CC 原文开头）──────────────────────────────────────────────

    @Test
    @DisplayName("statusline SPECIFIC 以 CC 首行开头")
    void statusline_prompt_starts_with_cc_first_line() throws Exception {
        // WHY: 桩文本被全文替换后，必须以 CC statuslineSetup.ts 首行开头才算对齐。
        assertThat(statuslineSpecific()).startsWith(CC_STATUSLINE_FIRST_LINE);
    }

    @Test
    @DisplayName("verification SPECIFIC 以 CC 首行开头")
    void verification_prompt_starts_with_cc_first_line() throws Exception {
        // WHY: 6 行桩被全文替换后，必须以 CC verificationAgent.ts 首行开头才算对齐。
        assertThat(verificationSpecific()).startsWith(CC_VERIFICATION_FIRST_LINE);
    }

    // ── R1b 无 DEFAULT 前缀（no-DEFAULT-prefix 是 D9 核心行为）──────────────

    @Test
    @DisplayName("statusline SPECIFIC 不含 DEFAULT 前缀")
    void statusline_prompt_has_no_default_prefix() throws Exception {
        // WHY: CC statusline 内置 agent getSystemPrompt 返回独立全文（无 SHARED_PREFIX）。
        assertThat(statuslineSpecific()).doesNotContain(DEFAULT_PREFIX_MARKER);
    }

    @Test
    @DisplayName("verification SPECIFIC 不含 DEFAULT 前缀")
    void verification_prompt_has_no_default_prefix() throws Exception {
        // WHY: CC verification 内置 agent 同样无 SHARED_PREFIX（verificationAgent.ts:134-152）。
        assertThat(verificationSpecific()).doesNotContain(DEFAULT_PREFIX_MARKER);
    }

    @Test
    @DisplayName("statusline 全文装配路径无 DEFAULT 前缀 + 仍以 CC 首行开头")
    void statusline_full_prompt_has_no_default_prefix() {
        // WHY: 装配层 buildStandaloneSystemPrompt(basePrompt=null) 不得注入 DEFAULT 前缀；
        // 若误改回 buildSystemPrompt（DEFAULT 前缀路径），全文首行将被污染。
        String full = BuiltInAgents.STATUSLINE_SETUP_AGENT.getSystemPrompt(null, List.of());
        assertThat(full).startsWith(CC_STATUSLINE_FIRST_LINE);
        assertThat(full).doesNotContain(DEFAULT_PREFIX_MARKER);
    }

    @Test
    @DisplayName("verification 全文装配路径无 DEFAULT 前缀 + 仍以 CC 首行开头")
    void verification_full_prompt_has_no_default_prefix() {
        // WHY: 同上 —— verification 装配层同样必须走 standalone 路径。
        String full = BuiltInAgents.VERIFICATION_AGENT.getSystemPrompt(null, List.of());
        assertThat(full).startsWith(CC_VERIFICATION_FIRST_LINE);
        assertThat(full).doesNotContain(DEFAULT_PREFIX_MARKER);
    }

    @Test
    @DisplayName("general-purpose 保留 DEFAULT 前缀（无回归）")
    void general_purpose_keeps_default_prefix() {
        // WHY: CC general-purpose 走 SHARED_PREFIX 结构（generalPurposeAgent.ts:12-14），
        // 装配重构不得让 general-purpose 也丢前缀。
        String full = BuiltInAgents.GENERAL_PURPOSE_AGENT.getSystemPrompt(null, List.of());
        assertThat(full).contains(DEFAULT_PREFIX_MARKER);
    }

    // ── R1c 关键标记（PS1 转换表 / statusline-setup 复用句 / VERDICT 段）─────

    @Test
    @DisplayName("statusline 含 PS1 转换表（表头 + 行 \\u → $(whoami)）")
    void statusline_prompt_contains_ps1_conversion_table() throws Exception {
        // WHY: CC statuslineSetup.ts 的 PS1 转换表（\\u → $(whoami) 等）是 prompt 的核心指令。
        // 测试源 \\u → 运行时单反斜杠 \\u（与文本块 \\u → 运行时 \\u 一致）。
        assertThat(statuslineSpecific())
            .contains("Convert PS1 escape sequences to shell commands")
            .contains("\\u → $(whoami)");
    }

    @Test
    @DisplayName("statusline 含 statusline-setup 复用句")
    void statusline_prompt_contains_statusline_setup_reuse_sentence() throws Exception {
        // WHY: 该句是 statusline 工作流的关键 —— 收尾时通知父 agent 后续仍须使用本 agent。
        assertThat(statuslineSpecific())
            .contains("\"statusline-setup\" agent must be used");
    }

    @Test
    @DisplayName("verification 含 VERDICT 段（PASS/FAIL/PARTIAL）")
    void verification_prompt_contains_verdict_section() throws Exception {
        // WHY: VERDICT 行由调用方解析，三态缺失任一都会破坏验证结果的机器可读契约。
        assertThat(verificationSpecific())
            .contains("VERDICT: PASS")
            .contains("VERDICT: FAIL")
            .contains("VERDICT: PARTIAL");
    }

    // ── R1d 字节长度守卫（对齐 CC 运行时 UTF-8 字节数）──────────────────────

    @Test
    @DisplayName("statusline SPECIFIC 字节长度守卫 7001")
    void statusline_prompt_byte_length_guard() throws Exception {
        // WHY: 7001 = CC STATUSLINE_SYSTEM_PROMPT 运行时 UTF-8 字节数（含尾随空白占位符还原）。
        // 防止未来无意截断 / 增删行导致与 CC 全文漂移。
        // 注：独立复验按 TS 模板语义提取 CC 运行时文本，与 Java 反射值逐字节 cmp BYTE-IDENTICAL，
        //     长度即 7001（IMP-SUB-07-reflection 误报 6977，属其提取工具少计字节，见 concerns）。
        assertThat(statuslineSpecific().getBytes(StandardCharsets.UTF_8).length).isEqualTo(7001);
    }

    @Test
    @DisplayName("verification SPECIFIC 字节长度守卫 9634")
    void verification_prompt_byte_length_guard() throws Exception {
        // WHY: 9634 = CC VERIFICATION_SYSTEM_PROMPT（${BASH_TOOL_NAME}→Bash、
        // ${WEB_FETCH_TOOL_NAME}→WebFetch 还原后）运行时 UTF-8 字节数。
        // 注：同 statusline —— 逐字节 cmp BYTE-IDENTICAL，正确长度为 9634（reflection 误报 9520）。
        assertThat(verificationSpecific().getBytes(StandardCharsets.UTF_8).length).isEqualTo(9634);
    }

    // ── R1e 返工 R2 回归：whenToUse 补 user's（对齐 CC statuslineSetup.ts:137）─

    @Test
    @DisplayName("statusline whenToUse 含 user's（对齐 CC statuslineSetup.ts:137）")
    void statusline_when_to_use_contains_users() {
        // WHY: CC 原文本为 "the user's Claude Code status line"，漏 user's 属翻译丢失。
        assertThat(BuiltInAgents.STATUSLINE_SETUP_AGENT.whenToUse())
            .isEqualTo("Use this agent to configure the user's Claude Code status line setting.");
    }
}
