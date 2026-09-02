package com.nexusai.application.agent.skill;

import java.util.List;

/**
 * skill frontmatter 解析出的 16 字段 · 对齐 CC {@code loadSkillsDir.ts:190-206}
 * {@code parseSkillFrontmatterFields} 返回类型（MCP 与文件两条加载链共享）。
 *
 * <p>CC 真源（E2，Read loadSkillsDir.ts:185-265）——返回对象字段（:191-206）：
 * <pre>
 * :191  displayName: string | undefined
 * :192  description: string
 * :193  hasUserSpecifiedDescription: boolean
 * :194  allowedTools: string[]
 * :195  argumentHint: string | undefined
 * :196  argumentNames: string[]
 * :197  whenToUse: string | undefined
 * :198  version: string | undefined
 * :199  model: ReturnType&lt;typeof parseUserSpecifiedModel&gt; | undefined
 * :200  disableModelInvocation: boolean
 * :201  userInvocable: boolean
 * :202  hooks: HooksSettings | undefined
 * :203  executionContext: 'fork' | undefined
 * :204  agent: string | undefined
 * :205  effort: EffortValue | undefined
 * :206  shell: FrontmatterShell | undefined
 * </pre>
 *
 * <p>snake_case → camelCase，每字段 JavaDoc 标注 CC 原名 + 行号。Java 类型映射：
 * {@code HooksSettings} → String（hooks JSON 串，{@link ParseSkillFrontmatter#parseHooksFromFrontmatter} 产物）；
 * {@code EffortValue} → String（{@link ParseSkillFrontmatter#parseEffortValue} 产物）；
 * {@code FrontmatterShell} → String（{@link ParseSkillFrontmatter#parseShellFrontmatter} 产物）。
 */
public record SkillFrontmatterFields(
        /**
         * CC original: displayName（loadSkillsDir.ts:238-239，
         * {@code displayName: frontmatter.name != null ? String(frontmatter.name) : undefined}）。
         * frontmatter {@code name} 是可读展示名，与命令 {@code name}（MCP 为 mcp__{server}__{skill}）分离。
         */
        String displayName,

        /**
         * CC original: description（loadSkillsDir.ts:212-214，
         * {@code validatedDescription ?? extractDescriptionFromMarkdown(markdownContent, descriptionFallbackLabel)}）。
         * frontmatter description coerce 后非 null 用显式值，否则从 markdown 首行提取。
         */
        String description,

        /**
         * CC original: hasUserSpecifiedDescription（loadSkillsDir.ts:241，
         * {@code hasUserSpecifiedDescription: validatedDescription !== null}）。
         * 仅 frontmatter 显式声明合法 description 标量时为 true。
         */
        boolean hasUserSpecifiedDescription,

        /**
         * CC original: allowedTools（loadSkillsDir.ts:242-244，
         * {@code parseSlashCommandToolsFromFrontmatter(frontmatter['allowed-tools'])}）。
         * 缺省/空 → 空数组（slash command 语义：无允许列表）。
         */
        List<String> allowedTools,

        /**
         * CC original: argumentHint（loadSkillsDir.ts:245-248，
         * {@code frontmatter['argument-hint'] != null ? String(...) : undefined}）。
         */
        String argumentHint,

        /**
         * CC original: argumentNames（loadSkillsDir.ts:249-251，
         * {@code parseArgumentNames(frontmatter.arguments ...)}）。
         */
        List<String> argumentNames,

        /**
         * CC original: whenToUse（loadSkillsDir.ts:252，
         * {@code frontmatter.when_to_use as string | undefined}）。
         */
        String whenToUse,

        /**
         * CC original: version（loadSkillsDir.ts:253，{@code frontmatter.version as string | undefined}）。
         */
        String version,

        /**
         * CC original: model（loadSkillsDir.ts:221-226，
         * {@code frontmatter.model === 'inherit' ? undefined : frontmatter.model ? parseUserSpecifiedModel(...) : undefined}）。
         * 'inherit' → null（继承当前模型）；null/空 → null。
         */
        String model,

        /**
         * CC original: disableModelInvocation（loadSkillsDir.ts:255-257，
         * {@code parseBooleanFrontmatter(frontmatter['disable-model-invocation'])}）。
         * 仅字面量 true / 字符串 "true" 为 true（frontmatterParser.ts:332-334）。
         */
        boolean disableModelInvocation,

        /**
         * CC original: userInvocable（loadSkillsDir.ts:216-219，
         * {@code frontmatter['user-invocable'] === undefined ? true : parseBooleanFrontmatter(...)}）。
         * 未定义默认 true（CC 语义，skill 默认用户可调用）。
         */
        boolean userInvocable,

        /**
         * CC original: hooks（loadSkillsDir.ts:259，
         * {@code parseHooksFromFrontmatter(frontmatter, resolvedName)}）· HooksSettings JSON 串。
         * 无 hooks / 校验非法 → null。
         */
        String hooks,

        /**
         * CC original: executionContext（loadSkillsDir.ts:260，
         * {@code frontmatter.context === 'fork' ? 'fork' : undefined}）。
         * 仅 frontmatter context='fork' 时为 'fork'，其余（含 'inline'）→ null（Java Command 默认 inline）。
         */
        String executionContext,

        /**
         * CC original: agent（loadSkillsDir.ts:261，{@code frontmatter.agent as string | undefined}）。
         */
        String agent,

        /**
         * CC original: effort（loadSkillsDir.ts:228-235，
         * {@code parseEffortValue(frontmatter['effort'])}；非法仅 warn 不阻断）。
         */
        String effort,

        /**
         * CC original: shell（loadSkillsDir.ts:263，
         * {@code parseShellFrontmatter(frontmatter.shell, resolvedName)}）。
         * 仅 bash/powershell 白名单值；非法 → null（回退 bash）。
         */
        String shell
) {}
