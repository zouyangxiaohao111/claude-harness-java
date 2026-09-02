package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillContentLoader 组合级替换测试 · 对齐 CC loadSkillsDir.ts:344-369 getPromptForCommand 闭包
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>顺序即契约</b>——CC getPromptForCommand 闭包顺序
 *       {@code prefix → substituteArguments → ${CLAUDE_SKILL_DIR} → ${CLAUDE_SESSION_ID}}
 *       决定 $name 只作用于 skill 正文（不碰 baseDir 前缀），且 SKILL_DIR/SESSION_ID 在参数替换
 *       <b>之后</b>才注入（若参数替换发生在 SKILL_DIR 之后，技能正文里的 {@code $ARGUMENTS}
 *       若恰含 {@code ${CLAUDE_SKILL_DIR}} 会被二次替换）。顺序颠倒必须 fail。</li>
 *   <li><b>win32 ${CLAUDE_SKILL_DIR} 反斜杠→正斜杠规范化</b>是 P0-3 修复的 bash 转义回归线
 *       （loadSkillsDir.ts:359-363）——Windows 上丢规范化会让技能内 {@code !`bash`} 块把 {@code \}
 *       当转义。</li>
 *   <li><b>5 替换 + append 综合用例</b>验证 $name/$ARGUMENTS[N]/$N/$ARGUMENTS/append 在真实
 *       skill 正文上的端到端输出（5 替换任一缺失即 fail）。</li>
 * </ol>
 *
 * <p>此测试同时使 {@link SkillContentLoader} 类 Javadoc 中的 {@code SkillContentLoaderSubstitutionTest}
 * 引用由假变真（EV-24 复核的假引用在此真实化）。
 */
class SkillContentLoaderSubstitutionTest {

    /** 复刻 CC getPromptForCommand 闭包顺序（loadSkillsDir.ts:344-369）· 与 SkillToolImpl.doExecute 组合点等价 */
    private String renderSkill(Command skill, String args, String sessionId) {
        SkillContentLoader loader = new SkillContentLoader();
        String body = skill.getContent() != null ? skill.getContent() : "";
        String content = loader.withBaseDirPrefix(skill, body);
        // 1. substituteArguments(content, args, appendIfNoPlaceholder=true, argumentNames)（:349-354）
        content = ArgumentSubstitution.substituteArguments(
            content, args, true, skill.getArgNames() != null ? skill.getArgNames() : List.of());
        // 2. ${CLAUDE_SKILL_DIR} 规范化替换（:359-363）
        content = loader.replaceSkillDir(content, skill.getBaseDir());
        // 3. ${CLAUDE_SESSION_ID} 替换（:366-369）
        content = loader.replaceSessionId(content, sessionId);
        return content;
    }

    @Test
    @DisplayName("综合：5 替换 + append + SKILL_DIR 规范化 + SESSION_ID 全链")
    void renderSkill_fullChain() {
        Command skill = new Command();
        skill.setName("test");
        skill.setBaseDir("C:\\skills\\test-skill");
        skill.setArgNames(List.of("lang"));
        skill.setContent("Run $lang with $ARGUMENTS[1] and $0; dir=${CLAUDE_SKILL_DIR}; sess=${CLAUDE_SESSION_ID}");

        String result = renderSkill(skill, "java --version", "sess-abc");

        String expectedDir = isWindows() ? "C:/skills/test-skill" : "C:\\skills\\test-skill";
        assertThat(result)
            .isEqualTo("Base directory for this skill: C:\\skills\\test-skill\n\n"
                + "Run java with --version and java; dir=" + expectedDir + "; sess=sess-abc");
    }

    @Test
    @DisplayName("append：无占位符且 args 非空 → 追加 ARGUMENTS（CC :140-141）")
    void renderSkill_append() {
        Command skill = new Command();
        skill.setName("test");
        skill.setBaseDir(null);
        skill.setContent("body");

        String result = renderSkill(skill, "some args", "sess-1");

        assertThat(result).isEqualTo("body\n\nARGUMENTS: some args");
    }

    @Test
    @DisplayName("replaceSkillDir null skillDir → 不替换；SESSION_ID null → 不替换（CC :359-363/:366-369）")
    void renderSkill_nullGuards() {
        SkillContentLoader loader = new SkillContentLoader();
        assertThat(loader.replaceSkillDir("${CLAUDE_SKILL_DIR}", null))
            .isEqualTo("${CLAUDE_SKILL_DIR}");
        assertThat(loader.replaceSessionId("${CLAUDE_SESSION_ID}", null))
            .isEqualTo("${CLAUDE_SESSION_ID}");
    }

    @Test
    @DisplayName("replaceSkillDir win32 反斜杠→正斜杠（对齐 loadSkillsDir.ts:359-363，P0-3 回归线）")
    void replaceSkillDir_win32Normalization() {
        SkillContentLoader loader = new SkillContentLoader();
        String dir = "C:\\proj\\skills\\my-skill";
        String replaced = loader.replaceSkillDir("cd ${CLAUDE_SKILL_DIR} && run", dir);
        if (isWindows()) {
            assertThat(replaced).isEqualTo("cd C:/proj/skills/my-skill && run");
            assertThat(replaced).doesNotContain("\\");
        } else {
            assertThat(replaced).isEqualTo("cd " + dir + " && run");
        }
    }

    @Test
    @DisplayName("顺序契约：参数替换先于 SKILL_DIR —— args 中 ${CLAUDE_SKILL_DIR} 经 $ARGUMENTS 注入后仍被规范化")
    void renderSkill_orderContract() {
        // CC 顺序 substituteArguments(:349) → SKILL_DIR(:362)：args 里恰含 ${CLAUDE_SKILL_DIR} 时，
        // 它经 $ARGUMENTS 注入正文后仍会被 replaceSkillDir 规范化（CC 真源实证）。
        // 若顺序颠倒（SKILL_DIR 先于 substituteArguments），替换时正文尚无该字面 → 结果会残留
        // ${CLAUDE_SKILL_DIR}，本断言 fail。
        Command skill = new Command();
        skill.setName("test");
        skill.setBaseDir("C:\\skills\\dir");
        skill.setContent("echo $ARGUMENTS");

        String result = renderSkill(skill, "${CLAUDE_SKILL_DIR}", "sess-x");

        String expectedDir = isWindows() ? "C:/skills/dir" : "C:\\skills\\dir";
        assertThat(result)
            .isEqualTo("Base directory for this skill: C:\\skills\\dir\n\necho " + expectedDir);
    }

    @Test
    @DisplayName("P1-4: replacePluginVariables 替换 ${CLAUDE_PLUGIN_ROOT} + ${CLAUDE_PLUGIN_DATA}（CC pluginOptionsStorage.ts:326-351，GAP-PC-1）")
    void replacePluginVariables_bothVars() {
        SkillContentLoader loader = new SkillContentLoader();
        String root = isWindows() ? "C:\\plugins\\my-plugin" : "/plugins/my-plugin";
        String out = loader.replacePluginVariables(
            "run ${CLAUDE_PLUGIN_ROOT}/bin + ${CLAUDE_PLUGIN_DATA}/state",
            root, "path");

        String expectedRoot = isWindows() ? "C:/plugins/my-plugin" : root;
        // ${CLAUDE_PLUGIN_DATA} 替换为 getPluginDataDir(source)，win32 也归一化为正斜杠（对齐 CC normalize）
        String expectedDataDir = com.nexusai.application.agent.plugin.PluginDirectories
            .getPluginDataDir("path").replace('\\', '/');
        assertThat(out)
            .as("P1-4: ${CLAUDE_PLUGIN_ROOT} 替换为插件根（win32 归一化）+ ${CLAUDE_PLUGIN_DATA} 替换为 data 目录（CC :326-351）")
            .startsWith("run " + expectedRoot + "/bin")
            .contains(expectedDataDir + "/state");
    }

    @Test
    @DisplayName("P1-4: replacePluginVariables null pluginRoot → 保持字面（CC :333 path 缺省不替换）")
    void replacePluginVariables_nullRoot_keepsLiteral() {
        SkillContentLoader loader = new SkillContentLoader();
        assertThat(loader.replacePluginVariables("${CLAUDE_PLUGIN_ROOT}/x", null, "path"))
            .as("pluginRoot null → ${CLAUDE_PLUGIN_ROOT} 保持字面（非 plugin 源命令）")
            .isEqualTo("${CLAUDE_PLUGIN_ROOT}/x");
    }

    @Test
    @DisplayName("P1-4: replaceUserConfig 敏感键→占位符 / 已知键→值 / 未知键→字面（CC pluginOptionsStorage.ts:385-419，GAP-PC-2）")
    void replaceUserConfig_sensitiveKnownUnknown() {
        SkillContentLoader loader = new SkillContentLoader();
        String out = loader.replaceUserConfig(
            "${user_config.apiKey} ${user_config.model} ${user_config.missing}",
            java.util.Map.of("apiKey", "sk-secret", "model", "claude-4"),
            java.util.Set.of("apiKey"));

        assertThat(out)
            .as("P1-4: 敏感键→描述性占位符（密钥不进 prompt）+ 已知键→值 + 未知键→字面（CC :385-419）")
            .isEqualTo("[sensitive option 'apiKey' not available in skill content] claude-4 ${user_config.missing}");
    }

    @Test
    @DisplayName("P1-4: replaceUserConfig 空 map + 空 set → 全部字面（Java 无 pluginOptionsStorage 等价物时行为）")
    void replaceUserConfig_emptyOptions_keepsLiteral() {
        SkillContentLoader loader = new SkillContentLoader();
        assertThat(loader.replaceUserConfig("${user_config.x}", java.util.Map.of(), java.util.Set.of()))
            .as("P1-4: 空选项 → 未知键保持字面（对齐 CC :399-402 未知键不抛）")
            .isEqualTo("${user_config.x}");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }
}
