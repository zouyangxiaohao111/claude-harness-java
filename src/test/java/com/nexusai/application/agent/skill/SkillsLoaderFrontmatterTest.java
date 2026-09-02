package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-5 前端 m 16 字段映射测试（RED→GREEN）· 对齐 CC parseSkillFrontmatterFields（loadSkillsDir.ts:185-265）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>name=目录名、frontmatter.name 只作 displayName</b>——CC loadSkillsDir.ts:452
 *       {@code const skillName = entry.name} + :238-239 displayName，Java 旧实现反置为 frontmatter.name 优先
 *       （SkillsLoader.java:180）导致 displayName 恒等于 name 无意义；本测试锁定新语义。</li>
 *   <li><b>model 'inherit'→null / effort 非法→null</b>——CC :221-226 + :228-235 校验语义，
 *       旧实现原样 toString 直落字段，非法值会污染运行时 model/effort。</li>
 *   <li><b>hooks 严格校验</b>——CC :136-153 HooksSchema Zod（unknown 键整体丢弃），旧实现
 *       {@code hooks.toString()} 产出 Map.toString 伪 JSON，fromHooksJson 恒解析失败。</li>
 * </ol>
 */
class SkillsLoaderFrontmatterTest {

    private final SkillsLoader loader = new SkillsLoader();

    private Command load(Path skillsRoot, String dirName, String frontmatter) throws Exception {
        Path dir = skillsRoot.resolve(dirName);
        Files.createDirectories(dir);
        Path skillMd = dir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\n" + frontmatter + "---\n# body\n");
        return loader.loadFromSkillMd(dir, skillMd);
    }

    @Test
    @DisplayName("name 恒=目录名，displayName=frontmatter.name（CC :452 + :238-239）")
    void nameIsDirName_displayNameIsFrontmatterName(@TempDir Path skillsRoot) throws Exception {
        // frontmatter.name 与目录名不同 → name 仍取目录名，displayName 取 frontmatter.name
        Command c = load(skillsRoot, "web-search", "name: 网页搜索\n");
        assertThat(c.getName()).isEqualTo("web-search");
        assertThat(c.getDisplayName()).isEqualTo("网页搜索");
        // userFacingName 优先 displayName（CC :337-339）
        assertThat(c.userFacingName()).isEqualTo("网页搜索");

        // 无 frontmatter.name → displayName null，userFacingName 回退 name
        Command noName = load(skillsRoot, "bare-skill", "");
        assertThat(noName.getName()).isEqualTo("bare-skill");
        assertThat(noName.getDisplayName()).isNull();
        assertThat(noName.userFacingName()).isEqualTo("bare-skill");
    }

    @Test
    @DisplayName("hasUserSpecifiedDescription：frontmatter description 显式 → true；缺省 → false（CC :241）")
    void hasUserSpecifiedDescription_derived(@TempDir Path skillsRoot) throws Exception {
        assertThat(load(skillsRoot, "with-desc", "description: 显式描述\n").getHasUserSpecifiedDescription())
            .isTrue();
        assertThat(load(skillsRoot, "no-desc", "").getHasUserSpecifiedDescription())
            .isFalse();
        // 非法 description（数组）→ coerce null → false（走 markdown 回退）
        assertThat(load(skillsRoot, "bad-desc", "description: [a, b]\n").getHasUserSpecifiedDescription())
            .isFalse();
    }

    @Test
    @DisplayName("model：'inherit'→null；合法值→parseUserSpecifiedModel；空白→null（CC :221-226）")
    void model_inheritAndValid(@TempDir Path skillsRoot) throws Exception {
        assertThat(load(skillsRoot, "m-inherit", "model: inherit\n").getModel()).isNull();
        assertThat(load(skillsRoot, "m-blank", "model: '  '\n").getModel()).isNull();
        assertThat(load(skillsRoot, "m-model", "model: ' claude-sonnet-4-6 '\n").getModel())
            .isEqualTo("claude-sonnet-4-6"); // parseUserSpecifiedModel trim
        assertThat(load(skillsRoot, "m-none", "").getModel()).isNull();
    }

    @Test
    @DisplayName("effort：合法档位/整数→落字段；非法→null 不落字段（CC :228-235）")
    void effort_validAndInvalid(@TempDir Path skillsRoot) throws Exception {
        assertThat(load(skillsRoot, "e-high", "effort: high\n").getEffort()).isEqualTo("high");
        assertThat(load(skillsRoot, "e-int", "effort: 5\n").getEffort()).isEqualTo("5");
        assertThat(load(skillsRoot, "e-bad", "effort: super-duper\n").getEffort()).isNull();
        assertThat(load(skillsRoot, "e-none", "").getEffort()).isNull();
    }

    @Test
    @DisplayName("shell：白名单→落 Command.shell；非法→null 回退 bash（CC frontmatterParser.ts:351-370）")
    void shell_mapsToCommand(@TempDir Path skillsRoot) throws Exception {
        assertThat(load(skillsRoot, "sh-pwsh", "shell: powershell\n").getShell()).isEqualTo("powershell");
        assertThat(load(skillsRoot, "sh-bash", "shell: bash\n").getShell()).isEqualTo("bash");
        assertThat(load(skillsRoot, "sh-unknown", "shell: zsh\n").getShell()).isNull();
        assertThat(load(skillsRoot, "sh-none", "").getShell()).isNull();
    }

    @Test
    @DisplayName("hooks：合法结构→JSON 串；未知事件键→null 不落字段（CC :136-153）")
    void hooks_validAndUnknownKey(@TempDir Path skillsRoot) throws Exception {
        Command valid = load(skillsRoot, "hk-valid",
            "hooks:\n"
                + "  PreToolUse:\n"
                + "    - matcher: Write\n"
                + "      hooks:\n"
                + "        - type: command\n"
                + "          command: echo hi\n");
        assertThat(valid.getHooks()).isNotNull();
        // 落字段的是合法 JSON（非旧实现 Map.toString 伪 JSON），fromHooksJson 可消费
        assertThat(valid.getHooks()).contains("PreToolUse").contains("\"command\":\"echo hi\"");

        Command unknown = load(skillsRoot, "hk-unknown",
            "hooks:\n"
                + "  NotARealEvent:\n"
                + "    - matcher: Write\n"
                + "      hooks:\n"
                + "        - type: command\n"
                + "          command: echo hi\n");
        assertThat(unknown.getHooks()).isNull();

        Command none = load(skillsRoot, "hk-none", "");
        assertThat(none.getHooks()).isNull();
    }

    @Test
    @DisplayName("P1-2 磁盘技能 isHidden/progressMessage 补齐（CC createSkillCommand :335-336）")
    void diskSkill_isHiddenAndProgressMessage(@TempDir Path skillsRoot) throws Exception {
        // WHY: EV-WF1-LD-010 —— 磁盘技能主路径（loadFromSkillMd + applyFrontmatter）此前漏设
        //   isHidden/progressMessage（Command.java:291 默认 isHidden=false、progressMessage=null），
        //   与 CC createSkillCommand :335-336（isHidden: !userInvocable, progressMessage: 'running'）漂移。
        //   P1-2 补齐：user-invocable:false 磁盘技能 → isHidden=true（UI 隐藏）；所有磁盘技能
        //   progressMessage='running'（进度展示）。
        // user-invocable:false → isHidden=true
        Command hidden = load(skillsRoot, "hd-hidden",
            "name: hd-hidden\nuser-invocable: false\n");
        assertThat(hidden.getIsHidden()).isTrue();
        assertThat(hidden.getProgressMessage()).isEqualTo("running");
        // 缺省（user-invocable 默认 true）→ isHidden=false
        Command visible = load(skillsRoot, "hd-visible", "name: hd-visible\n");
        assertThat(visible.getIsHidden()).isFalse();
        assertThat(visible.getProgressMessage()).isEqualTo("running");
    }
}
