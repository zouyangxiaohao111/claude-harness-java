package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-13: write-once leaf registry 测试 · 对齐 CC {@code mcpSkillBuilders.ts:26-44}.
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>注册后可用</b>——get() 返回与 register 时相同的 Builders（mcpSkillBuilders.ts:33-44
 *       单一模块级 {@code let builders}）。若 registry 缓存了旧值或复制了引用，get() 语义漂移。</li>
 *   <li><b>last-write-wins 无守卫</b>（mcpSkillBuilders.ts:33-35 {@code builders = b}）——重复注册
 *       覆盖前值（CC 依赖此保证 loadSkillsDir 模块 init 只注册一次但可被测试重注册）。</li>
 *   <li><b>未注册 fail-loud</b>（mcpSkillBuilders.ts:38-41）——get() 未注册时抛
 *       {@link IllegalStateException}，不静默返回 null。builders 缺失 = loadSkillsDir 未求值 =
 *       MCP 技能发现不应发生，静默降级会掩盖接线错误。</li>
 * </ol>
 */
@DisplayName("P2-13 write-once leaf registry（mcpSkillBuilders.ts:26-44）")
class McpSkillBuildersTest {

    /** 每个用例前置：注册真实 builders（隔离其他测试类的静态污染 + 保证用例可用）。 */
    @BeforeEach
    void setUp() {
        registerRealBuilders();
    }

    /** 每个用例后置：恢复真实 builders（未注册 fail-loud 用例把静态置 null 后必须恢复）。 */
    @AfterEach
    void tearDown() {
        registerRealBuilders();
    }

    private static void registerRealBuilders() {
        McpSkillBuilders.register(new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields));
    }

    @Test
    @DisplayName("register 后 get 返回同一 Builders 实例（mcpSkillBuilders.ts:33-44）")
    void register_then_get_returnsSameBuilders() {
        McpSkillBuilders.Builders b = new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields);

        McpSkillBuilders.register(b);

        assertThat(McpSkillBuilders.get()).isSameAs(b);
        // 两个函数引用均已接通（调用不抛）
        assertThat(McpSkillBuilders.get().createSkillCommand()).isNotNull();
        assertThat(McpSkillBuilders.get().parseSkillFrontmatterFields()).isNotNull();
    }

    @Test
    @DisplayName("last-write-wins：重复注册覆盖前值（mcpSkillBuilders.ts:33-35 无守卫）")
    void register_lastWriteWins_overwritesPrevious() {
        McpSkillBuilders.Builders b1 = new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields);
        McpSkillBuilders.Builders b2 = new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields);

        McpSkillBuilders.register(b1);
        McpSkillBuilders.register(b2);

        assertThat(McpSkillBuilders.get()).isSameAs(b2);
    }

    @Test
    @DisplayName("未注册时 get() fail-loud 抛 IllegalStateException（mcpSkillBuilders.ts:38-41）")
    void get_unregistered_throwsIllegalStateException() {
        // 等价 CC mcpSkillBuilders.ts:31 let builders = null（register(null) = CC builders = b 的 null 特例）
        McpSkillBuilders.register(null);

        assertThatThrownBy(McpSkillBuilders::get)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP skill builders not registered");
    }
}
