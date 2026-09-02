package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mcp_instructions 动态 section 测试 · 对齐 CC
 * {@code DANGEROUS_uncachedSystemPromptSection('mcp_instructions', ...)}（prompts.ts:511-516）
 * + {@code getMcpInstructions} 过滤（prompts.ts:578-608）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：
 * <ul>
 *   <li><b>I-2 mcp 专项</b>：mcp_instructions 是 13 条动态注册中唯一 DANGEROUS_uncached
 *       （cacheBreak=true），MCP 连接/断开跨轮变化，必须每轮重算打破 prompt 缓存
 *       （systemPromptSections.ts:24/:37）。RegistryResolveTest 已用通用 volatile section
 *       钉死 cacheBreak=true 机制，本测试钉死<b>真实注册</b>的 mcp_instructions 是
 *       cacheBreak=true 且 compute 每轮重算；</li>
 *   <li><b>REQ-SP-08 过滤产块</b>：仅 connected 且含 instructions 的客户端生成指令块
 *       （prompts.ts:579-582 过滤）；无 MCP / 全部 disconnected / 无 instructions → null
 *       （不产块，下游 null filter 移除）。</li>
 * </ul>
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
    @DisplayName("I-2 mcp 专项：mcp_instructions 注册为 DANGEROUS_uncached（cacheBreak=true）")
    void mcp_registeredAsDangerousUncached() {
        SystemPromptSection section = mcpSection(withClients(List.of()));

        assertThat(section.name()).isEqualTo("mcp_instructions");
        assertThat(section.cacheBreak())
            .as("mcp_instructions 必须 cacheBreak=true（MCP 连接/断开跨轮变化，systemPromptSections.ts:24/:37）")
            .isTrue();
    }

    @Test
    @DisplayName("I-2 每轮重算：mcp_instructions compute 两次 resolve 均被调用（registry 短路条件恒不成立）")
    void mcp_computeCalledEveryTurn() {
        // 经 registry 全链：mcp_instructions cacheBreak=true → 二次 resolve 不短路，compute 计数递增
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        SystemPromptAssemblyInput input = withClients(List.of(
            new SystemPromptAssemblyInput.McpClientInfo("github", "使用 gh 命令", true)));

        // 手工注册真实 compute（buildDynamicSections 返回即已含 mcp_instructions，直接注册）
        dynamicSections(input).forEach(registry::register);

        COMPUTE_COUNT.set(0);
        SystemPromptSection.ComputeFn original = mcpSection(input).compute();
        // 用计数包装验证真实 compute 每轮被调
        registry = new SystemPromptSectionRegistry();
        registry.register(new SystemPromptSection("mcp_instructions", () -> {
            COMPUTE_COUNT.incrementAndGet();
            return original.compute();
        }, true));

        registry.resolveAll(cache);
        registry.resolveAll(cache);

        assertThat(COMPUTE_COUNT.get())
            .as("I-2：cacheBreak=true → 两轮都调 compute（计数 2），不被 per-section 缓存短路")
            .isEqualTo(2);
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
