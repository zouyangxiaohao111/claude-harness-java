package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;

import java.util.Map;

/**
 * MCP 技能构建器 write-once leaf registry · 对齐 CC {@code mcpSkillBuilders.ts:26-44}.
 *
 * <p>持有 loadSkillsDir 的两个函数引用（{@link CreateSkillCommand#create} +
 * {@link ParseSkillFrontmatter#parseSkillFrontmatterFields}），供 MCP 技能发现
 * （{@code McpToolPool#fetchMcpSkills}）在无循环依赖的前提下取用。等价 CC 的
 * dependency-graph leaf：本类只 import 类型，mcpSkills.ts 与 loadSkillsDir.ts 可同时
 * 依赖它而不成环（client.ts → mcpSkills.ts → loadSkillsDir.ts → … → client.ts）。
 *
 * <p>CC 真源（E2，Read mcpSkillBuilders.ts 全 44 行）：
 * <pre>
 * :26-29  export type MCPSkillBuilders = {
 *            createSkillCommand: typeof createSkillCommand
 *            parseSkillFrontmatterFields: typeof parseSkillFrontmatterFields
 *          }
 * :31     let builders: MCPSkillBuilders | null = null
 * :33-35  export function registerMCPSkillBuilders(b: MCPSkillBuilders): void { builders = b }
 * :37-44  export function getMCPSkillBuilders(): MCPSkillBuilders {
 *           if (!builders) { throw new Error('MCP skill builders not registered — loadSkillsDir.ts has not been evaluated yet') }
 *           return builders
 *         }
 * </pre>
 *
 * <p>Java 端注册宿主：{@code ToolRegistrationConfig.skillRegistry()} @Bean init
 * （等价 CC loadSkillsDir.ts:1083 模块 init eager 注册），保证任意 MCP 连接
 * （{@code McpServerService.start}）前 builders 已注册。SkillsLoader 是 POJO
 * （{@code new SkillsLoader()} SkillRegistry:73），不可作注册宿主。
 *
 * <p>⚠️ 未注册时 {@link #get()} fail-loud 抛 {@link IllegalStateException}（CC :38-41），
 * 不静默降级——builders 缺失 = loadSkillsDir 未求值 = MCP 技能发现不应发生。
 */
public final class McpSkillBuilders {

    /**
     * CC original: {@code createSkillCommand}（mcpSkillBuilders.ts:27，
     * {@code typeof createSkillCommand} loadSkillsDir.ts:270-401）——22 入参 → 25 属性 Command。
     */
    @FunctionalInterface
    public interface CreateSkillCommandFn {
        Command create(CreateSkillCommand.Params params);
    }

    /**
     * CC original: {@code parseSkillFrontmatterFields}（mcpSkillBuilders.ts:28，
     * {@code typeof parseSkillFrontmatterFields} loadSkillsDir.ts:185-265）——16 字段 frontmatter 解析。
     */
    @FunctionalInterface
    public interface ParseSkillFrontmatterFieldsFn {
        SkillFrontmatterFields parse(Map<String, Object> frontmatter, String markdownContent,
                                     String resolvedName, String fallbackLabel);
    }

    /**
     * CC original: {@code MCPSkillBuilders}（mcpSkillBuilders.ts:26-29）——两个函数引用的聚合。
     */
    public record Builders(
            CreateSkillCommandFn createSkillCommand,
            ParseSkillFrontmatterFieldsFn parseSkillFrontmatterFields
    ) {}

    /** CC original: {@code let builders: MCPSkillBuilders | null = null}（mcpSkillBuilders.ts:31）。 */
    private static volatile Builders builders;

    private McpSkillBuilders() {
        // 工具类：静态 registry
    }

    /**
     * 注册 builders · CC original: {@code registerMCPSkillBuilders}（mcpSkillBuilders.ts:33-35）。
     *
     * <p>last-write-wins，无守卫（CC :33-35 直接 {@code builders = b}）。后续注册覆盖前值；
     * 传入 null 等价 CC {@code builders = null}（清空注册，测试用）。
     *
     * @param b builders 聚合（CreateSkillCommand::create + ParseSkillFrontmatter::parseSkillFrontmatterFields）
     */
    public static void register(Builders b) {
        builders = b;
    }

    /**
     * 取 builders · CC original: {@code getMCPSkillBuilders}（mcpSkillBuilders.ts:37-44）。
     *
     * <p>未注册时 fail-loud 抛 {@link IllegalStateException}（CC :39-41 错误文案
     * {@code 'MCP skill builders not registered — loadSkillsDir.ts has not been evaluated yet'}）。
     * 本类静态 holder 不依赖 Spring，注册时机由调用方保证（ToolRegistrationConfig @Bean init）。
     *
     * @return 已注册的 builders（非 null）
     * @throws IllegalStateException builders 未注册（loadSkillsDir 未求值等价）
     */
    public static Builders get() {
        Builders b = builders;
        if (b == null) {
            throw new IllegalStateException(
                "MCP skill builders not registered — loadSkillsDir.ts has not been evaluated yet"
                    + " (CC mcpSkillBuilders.ts:39-41 fail-loud)");
        }
        return b;
    }
}
