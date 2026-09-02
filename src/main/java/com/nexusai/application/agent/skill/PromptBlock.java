package com.nexusai.application.agent.skill;

/**
 * Prompt 内容块 · 对齐 CC {@code ContentBlockParam} 的 {@code {type:'text',text}} 形态
 * （bundledSkills.ts:37-40 getPromptForCommand 返回值）。
 *
 * <p><b>P1-4 统一类型</b>：CC 全部 bundled skill 的 getPromptForCommand 都返回
 * {@code [{ type: 'text', text }]} 单形态（bundledSkills.ts:37-40），无需多态。本类替代
 * 改造前散布在 6 处嵌套重复的 text-block 类型（P1-4 createList）：
 * <ul>
 *   <li>BatchSkillRegistrar.PromptBlock（BatchSkillRegistrar.java:65-67）</li>
 *   <li>ClaudeApiSkillRegistrar.PromptBlock（ClaudeApiSkillRegistrar.java:293-295）</li>
 *   <li>DebugSkillRegistrar.PromptBlock（DebugSkillRegistrar.java:81-83）</li>
 *   <li>SkillifySkillRegistrar.PromptBlock（SkillifySkillRegistrar.java:159-161）</li>
 *   <li>NexusaiInChromeSkill.PromptResult（NexusaiInChromeSkill.java:58-60）</li>
 *   <li>KeybindingsSkill.KeybindingsPrompt（KeybindingsSkill.java:65-67）</li>
 *   <li>LoremIpsumSkill.PromptBlock（LoremIpsumSkill.java:125）</li>
 * </ul>
 */
public record PromptBlock(String type, String text) {
    /** CC {@code { type: 'text', text }} · text 块工厂. */
    public static PromptBlock text(String text) {
        return new PromptBlock("text", text);
    }
}
