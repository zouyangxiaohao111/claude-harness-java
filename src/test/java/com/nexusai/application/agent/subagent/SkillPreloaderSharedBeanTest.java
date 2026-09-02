package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.skill.DynamicSkillsManager;
import com.nexusai.application.agent.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-11 SkillPreloader 消费共享 SkillRegistry bean 测试 · 对齐 CC runAgent.ts:580
 * {@code getSkillToolCommands} 共享 memoized 源。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图，而非仅行为）:
 * <ol>
 *   <li><b>preload 必须消费调用方传入的共享 SkillRegistry，而非 per-call 自建实例</b>——
 *       旧代码 {@code new SkillRegistry(skillsDir)} 每次预加载都新建独立 registry，
 *       没有 dynamicSkillsManager 注入 → 动态技能（CC getDynamicSkills 源）永远缺失；
 *       共享 bean 持有该注入，dyn-skill 必须命中。若本测试通过则证明 preload 用的
 *       是注入的共享 bean（对齐 CC runAgent.ts:580 模块级共享 memoized getSkillToolCommands）。</li>
 *   <li><b>缺失技能须上报</b>——missingSkills 为空代表 FS + dynamic 双源均解析成功。</li>
 *   <li><b>initialMessages 须为 isMeta:true 的 user 消息</b>——对齐 CC createUserMessage
 *       ({content, isMeta:true}) runAgent.ts:639-644，subagent 消息链前置注入。</li>
 * </ol>
 */
class SkillPreloaderSharedBeanTest {

    /** 写一个最小 SKILL.md（frontmatter name 显式）到 skillsRoot/skillName/SKILL.md。 */
    private static void writeSkill(Path skillsRoot, String skillName, String name) throws Exception {
        Path dir = skillsRoot.resolve(skillName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: " + name + "\n---\n# " + name + "\n");
    }

    @Test
    @DisplayName("preload 消费传入的共享 SkillRegistry（FS + dynamic 双源均命中，对齐 CC runAgent.ts:580）")
    void preload_consumesSharedRegistryWithDynamicSkills(@TempDir Path tempDir) throws Exception {
        // fs-skill: 文件系统技能（共享 registry 的 skillsRoot 下）
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "fs-skill", "fs-skill");

        // dyn-skill: 动态技能（注入 DynamicSkillsManager 后由 registry 的 getDynamicSkills 源叠加）
        Path dynDir = tempDir.resolve("dyn");
        writeSkill(dynDir, "dyn-skill", "dyn-skill");
        DynamicSkillsManager dynManager = new DynamicSkillsManager();
        dynManager.addSkillDirectories(List.of(dynDir.toString()));

        // 共享 SkillRegistry：注入 dynamicSkillsManager。
        // RED 于旧代码（SkillPreloader 内部 new SkillRegistry 无此注入 → dyn-skill 缺失 → missingSkills 非空）
        SkillRegistry sharedRegistry = new SkillRegistry(skillsRoot.toString());
        sharedRegistry.setDynamicSkillsManager(dynManager);

        SkillPreloader preloader = new SkillPreloader(sharedRegistry);

        SkillPreloader.PreloadResult result = preloader.preload(List.of("fs-skill", "dyn-skill"));

        // 两技能均解析（共享 bean 提供 FS + dynamic 双源 → missingSkills 为空，GREEN）
        assertThat(result.missingSkills()).isEmpty();
        assertThat(result.initialMessages()).hasSize(2);

        // 每条 initialMessage 均为 isMeta:true 的 user 消息（对齐 CC createUserMessage isMeta:true runAgent.ts:639-644）
        for (Map<String, Object> message : result.initialMessages()) {
            assertThat(message.get("type")).isEqualTo("user");
            assertThat(message.get("isMeta")).isEqualTo(true);
            assertThat(message.get("content")).isInstanceOf(List.class);
            assertThat((List<?>) message.get("content")).isNotEmpty();
        }
    }

    @Test
    @DisplayName("preload 空列表返回空结果（不触发共享 registry 加载）")
    void preload_emptyListReturnsEmpty(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot);

        SkillPreloader preloader = new SkillPreloader(new SkillRegistry(skillsRoot.toString()));
        SkillPreloader.PreloadResult result = preloader.preload(List.of());

        assertThat(result.missingSkills()).isEmpty();
        assertThat(result.initialMessages()).isEmpty();
    }
}
