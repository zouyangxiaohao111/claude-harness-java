package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mcp_instructions 动态 section 测试 · 对齐目标 CC <b>2.1.278</b>。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：
 * <ul>
 *   <li><b>I-2 mcp 专项（2026-09-21 按 2.1.278 改写）</b>：2.1.88 时代 mcp_instructions 是 13 条动态
 *       注册中唯一 {@code DANGEROUS_uncachedSystemPromptSection}（{@code cacheBreak=true}，每轮重算）。
 *       2.1.278 实测 {@code cacheBreak:!0}=0 处、独立段名 {@code mcp_instructions} 已不存在
 *       （改走 {@code mcp_instructions_delta} 尾部附件）⇒ 本仓把该段改为<b>普通可缓存段</b>：
 *       会话内<b>首次</b> resolve 计算一次后由会话级分段缓存钉住，⛔ 不再每轮重算。
 *       本用例钉死这一新契约（旧断言「cacheBreak=true 且每轮重算」已按新语义改写，⛔ 不是删用例）；</li>
 *   <li><b>REQ-SP-08 过滤产块</b>：仅 connected 且含 instructions 的客户端生成指令块
 *       （prompts.ts:579-582 过滤）；无 MCP / 全部 disconnected / 无 instructions → null
 *       （不产块，下游 null filter 移除）。</li>
 * </ul>
 *
 * <p><b>⭐ 已知分歧（登记）</b>：改为可缓存段后，MCP 服务器<b>会话中途</b>连接/断开，其指令不再即时
 * 进系统提示，要等 {@code /clear} 或 {@code /compact} 触发分段缓存失效；CC 2.1.278 用 delta 尾部附件
 * 解决该时序 ⇒ 本仓缺该通道（A1 留作后续批次）。
 */
class McpInstructionsTest {

    private static final AtomicInteger COMPUTE_COUNT = new AtomicInteger();

    private static List<SystemPromptSection> dynamicSections(SystemPromptAssemblyInput input) {
        return SystemPromptSections.buildDynamicSections(input);
    }

    private static SystemPromptSection mcpSection(SystemPromptAssemblyInput input) {
        return dynamicSections(input).stream()
            .filter(s -> "mcp_instructions".equals(s.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("mcp_instructions 未注册"));
    }

    private static SystemPromptAssemblyInput withClients(List<SystemPromptAssemblyInput.McpClientInfo> clients) {
        return new SystemPromptAssemblyInput(Set.of("Read"), "claude-sonnet-4-6", List.of(), clients, null, List.of(), null, null, false);
    }

    @Test
    @DisplayName("I-2 mcp 专项：mcp_instructions 注册为普通可缓存段（cacheBreak=false，对齐 CC 2.1.278）")
    void mcp_registeredAsCacheable() {
        SystemPromptSection section = mcpSection(withClients(List.of()));

        assertThat(section.name()).isEqualTo("mcp_instructions");
        assertThat(section.cacheBreak())
            .as("mcp_instructions 必须 cacheBreak=false（2.1.278 口径：全份提示 cacheBreak=true 计数 0；"
                + "本仓改走会话级冻结，MCP 中途变更等 /clear 或 /compact）")
            .isFalse();
    }

    @Test
    @DisplayName("I-2 会话级冻结：mcp_instructions 两次 resolve 只调一次 compute（缓存命中短路），且首值跨轮固定")
    void mcp_computeCalledOnce_thenFrozen() {
        // 经 registry 全链：cacheBreak=false → 二次 resolve 命中会话级分段缓存，compute 计数不增
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        SystemPromptAssemblyInput first = withClients(List.of(
            new SystemPromptAssemblyInput.McpClientInfo("github", "使用 gh 命令", true)));

        // 用真实注册产物的 cacheBreak（=false）+ 包装计数验证「真实 compute 只被调一次」
        SystemPromptSection real = mcpSection(first);
        registry.register(new SystemPromptSection("mcp_instructions", () -> {
            COMPUTE_COUNT.incrementAndGet();
            return real.compute().compute();
        }, real.cacheBreak()));

        COMPUTE_COUNT.set(0);
        List<String> firstResolve = registry.resolveAll(cache);
        List<String> secondResolve = registry.resolveAll(cache);

        assertThat(COMPUTE_COUNT.get())
            .as("I-2（2.1.278 口径）：cacheBreak=false → 首轮 compute 1 次，次轮命中缓存短路 ⇒ 计数 1")
            .isEqualTo(1);
        assertThat(cache.has("mcp_instructions"))
            .as("首轮结果写回会话级分段缓存（CC systemPromptSections.ts:54 无条件 set）")
            .isTrue();
        assertThat(secondResolve).as("次轮返回与首轮逐字节相同（前缀缓存稳定的前提）")
            .isEqualTo(firstResolve);

        // ⭐ 冻结语义直接验证：即便 MCP 连接状态变化（换一个不同 compute 闭包），同会话仍返回首值
        SystemPromptSectionRegistry fresh = new SystemPromptSectionRegistry();
        fresh.register(new SystemPromptSection("mcp_instructions",
            () -> java.util.concurrent.CompletableFuture.completedFuture("# MCP Server Instructions\n\n换了一批服务器"),
            false));
        List<String> afterChange = fresh.resolveAll(cache);
        assertThat(afterChange)
            .as("会话级冻结：同会话内 MCP 集合变化不刷新该段（要等 /clear 或 /compact）—— 已登记分歧")
            .isEqualTo(firstResolve);
    }

    @Test
    @DisplayName("REQ-SP-08：connected 且含 instructions 的客户端 → 产 # MCP Server Instructions 块")
    void mcp_connectedClientProducesBlock() {
        SystemPromptSection section = mcpSection(withClients(List.of(
            new SystemPromptAssemblyInput.McpClientInfo("github", "使用 gh 命令", true))));

        String text = section.compute().compute().join();

        assertThat(text).as("段头（prompts.ts:584-587）")
            .startsWith("# MCP Server Instructions\n");
        assertThat(text).as("客户端子块 ## name + instructions（prompts.ts:588-608）")
            .contains("## github\n使用 gh 命令");
    }

    @Test
    @DisplayName("REQ-SP-08：无 MCP 客户端 → compute 返回 null（null filter 移除，OPD-SP-19）")
    void mcp_noClients_returnsNull() {
        SystemPromptSection section = mcpSection(withClients(List.of()));

        String text = section.compute().compute().join();

        assertThat(text).as("无 MCP 连接 → null，该 section 不出现在组装结果").isNull();
    }

    @Test
    @DisplayName("REQ-SP-08：disconnected 或无 instructions 客户端被过滤 → null（prompts.ts:579-582 过滤）")
    void mcp_disconnectedOrNoInstructions_filtered() {
        List<SystemPromptAssemblyInput.McpClientInfo> clients = List.of(
            new SystemPromptAssemblyInput.McpClientInfo("github", "使用 gh 命令", false),  // disconnected
            new SystemPromptAssemblyInput.McpClientInfo("slack", "", true));              // 无 instructions
        SystemPromptSection section = mcpSection(withClients(clients));

        String text = section.compute().compute().join();

        assertThat(text).as("仅 connected 且含 instructions 才产块；否则 null（:579-582 过滤）").isNull();
    }
}
