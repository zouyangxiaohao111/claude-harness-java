package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-20 ClaudePaths 四源决策测试（RED→GREEN）· 对齐 CC getSkillsPath（loadSkillsDir.ts:78-94，
 * A6 四源决策）+ getClaudeConfigHomeDir（envUtils.ts:7-14）+ getManagedFilePath（managedPath.ts:8-25）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>policySettings 源指向 managed 目录</b>——CC :84 {@code join(getManagedFilePath(), '.claude', dir)}：
 *       managed 策略技能从平台 managed 目录加载，若指向 config-home 即错位（权限语义不同）。</li>
 *   <li><b>userSettings 源指向 config-home</b>——CC :86 {@code join(getClaudeConfigHomeDir(), dir)}。</li>
 *   <li><b>projectSettings 源为相对 .claude/${dir}</b>——CC :88 {@code `.claude/${dir}`}（相对 cwd）。</li>
 *   <li><b>plugin→'plugin' / 未知→''</b>——CC :90/:92 缺省分支。</li>
 * </ol>
 */
class ClaudePathsTest {

    @AfterEach
    void resetOverrides() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
    }

    @Test
    @DisplayName("policySettings → join(managed, '.claude', dir) · CC loadSkillsDir.ts:84")
    void getSkillsPath_policySettings_joinsManaged() {
        ClaudePaths.setManagedFilePathOverride("/tmp/claude-managed");
        assertThat(ClaudePaths.getSkillsPath("policySettings", "skills"))
            .isEqualTo(Paths.get("/tmp/claude-managed", ".claude", "skills").toString());
    }

    @Test
    @DisplayName("userSettings → join(configHome, dir) · CC loadSkillsDir.ts:86")
    void getSkillsPath_userSettings_joinsConfigHome() {
        ClaudePaths.setConfigDirOverride("/tmp/claude-config");
        // config-home 返回 absolute+normalize（Windows 前缀当前盘符），期望值同构计算
        assertThat(ClaudePaths.getSkillsPath("userSettings", "commands"))
            .isEqualTo(Paths.get("/tmp/claude-config").toAbsolutePath().normalize().resolve("commands").toString());
    }

    @Test
    @DisplayName("projectSettings → .claude/${dir}（相对 cwd）· CC loadSkillsDir.ts:88")
    void getSkillsPath_projectSettings_isRelative() {
        assertThat(ClaudePaths.getSkillsPath("projectSettings", "skills")).isEqualTo(".claude/skills");
        assertThat(ClaudePaths.getSkillsPath("projectSettings", "commands")).isEqualTo(".claude/commands");
    }

    @Test
    @DisplayName("plugin → 'plugin' · CC loadSkillsDir.ts:90")
    void getSkillsPath_plugin_returnsLiteral() {
        assertThat(ClaudePaths.getSkillsPath("plugin", "skills")).isEqualTo("plugin");
    }

    @Test
    @DisplayName("未知 source → 空串 · CC loadSkillsDir.ts:92 default")
    void getSkillsPath_unknown_returnsEmpty() {
        assertThat(ClaudePaths.getSkillsPath("unknownSource", "skills")).isEmpty();
        assertThat(ClaudePaths.getSkillsPath(null, "skills")).isEmpty();
    }

    @Test
    @DisplayName("getClaudeConfigHomeDir：测试覆写优先（Java 无法进程内改 env）· CC envUtils.ts:7-14")
    void getClaudeConfigHomeDir_overrideWins() {
        ClaudePaths.setConfigDirOverride("/tmp/cfg-home");
        assertThat(ClaudePaths.getClaudeConfigHomeDir())
            .isEqualTo(Paths.get("/tmp/cfg-home").toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("getManagedFilePath：测试覆写优先 · CC managedPath.ts:8-25")
    void getManagedFilePath_overrideWins() {
        ClaudePaths.setManagedFilePathOverride("/tmp/managed");
        assertThat(ClaudePaths.getManagedFilePath()).isEqualTo("/tmp/managed");
    }

    @Test
    @DisplayName("覆写清除后回退默认（config-home 默认 = user.home/.claude，CC envUtils.ts:10）")
    void clearOverrides_restoresDefaults() {
        ClaudePaths.setConfigDirOverride("/tmp/cfg");
        ClaudePaths.setConfigDirOverride(null);
        assertThat(ClaudePaths.getClaudeConfigHomeDir())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".claude").toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("OPD-R2-06: getClaudeConfigHomeDir 输出 NFC（envUtils.ts:11 .normalize('NFC')）")
    void getClaudeConfigHomeDir_nfc() {
        // WHY: CC (env ?? join(homedir(), '.claude')).normalize('NFC')（envUtils.ts:7-14）——
        //      分解形 Unicode（e+U+0301）路径输入产出不同字节路径串（OPD-R2-06/G-07，EV-014）。
        //      与 AutoMemPaths/TeamMemPaths 共用 normalizeNfc。
        ClaudePaths.setConfigDirOverride("/tmp/cafe\u0301home");
        String result = ClaudePaths.getClaudeConfigHomeDir();
        String expected = java.text.Normalizer.normalize(
            Paths.get("/tmp/cafe\u0301home").toAbsolutePath().normalize().toString(),
            java.text.Normalizer.Form.NFC);
        assertThat(result)
            .as("分解形路径输入必须产出合成形（NFC）字节路径")
            .isEqualTo(expected)
            .doesNotContain("\u0301");
    }
}
