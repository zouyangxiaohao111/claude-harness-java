package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-3 G1] managed-only 写盘门控测试 · 对齐 CC {@code addPermissionRulesToSettings}
 * （permissionsLoader.ts:239-242）：{@code allowManagedPermissionRulesOnly === true} 时
 * 写规则前早退 {@code return false}，不落盘任何新规则。
 *
 * <p><b>WHY</b>: hooks 侧 managed-only 门控已实现，permission-rules 侧完全缺失——旧
 * {@link PermissionUpdatePersister#persist} 无条件写盘，企业管控"仅 managed 规则"在用户
 * 点击 "always allow" 后仍被写进可编辑 settings.json，破坏企业策略。本测试锁定：
 * <ol>
 *   <li>managed-only 开启 → addRules 被拒，文件逐字节不变；</li>
 *   <li>managed-only 关闭 → addRules 正常写盘（回归防护）；</li>
 *   <li>门控<b>仅作用于 addRules</b>（CC 只在此路径早退），removeRules 不被误伤。</li>
 * </ol>
 */
@DisplayName("[IMP-3 G1] managed-only 写盘门控：allowManagedPermissionRulesOnly 拒绝写新规则")
class PermissionUpdatePersisterManagedOnlyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SettingsJsonParser PARSER =
        new SettingsJsonParser(JSON, new PermissionRuleValueParser());

    /** 装配 persister，写盘目标是 {@code <projectHome>/.nexusai/settings.json}，并注入 managed-only 判定器。 */
    private static PermissionUpdatePersister persister(Path projectHome, PermissionManagedPolicy policy) {
        PermissionUpdatePersister p = new PermissionUpdatePersister(
            new UserSettingsLoader(PARSER),
            new ProjectSettingsLoader(PARSER, () -> projectHome.toString()),
            new LocalSettingsLoader(PARSER, () -> projectHome.toString()),
            new PermissionRuleValueParser());
        p.setManagedPolicy(policy);
        return p;
    }

    /** 写 managed policy 文件（含/不含 managed-only 开关），返回读该文件的判定器。 */
    private static PermissionManagedPolicy managedPolicy(Path dir, boolean allowManagedOnly) throws IOException {
        Path policy = dir.resolve("managed-settings.json");
        Files.createDirectories(dir);
        Files.writeString(policy,
            allowManagedOnly ? "{\"allowManagedPermissionRulesOnly\": true}" : "{}");
        return new PermissionManagedPolicy(new ManagedPolicySettingsSupplier(JSON, policy.toString()));
    }

    private static Path writeProjectSettings(Path projectHome) throws IOException {
        Path settingsFile = projectHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, """
            { "permissions": { "allow": [ "Read" ] } }
            """);
        return settingsFile;
    }

    private static PermissionRule allowRule(String toolName) {
        return new PermissionRule(PermissionRuleSource.PROJECT_SETTINGS,
            PermissionBehavior.ALLOW, PermissionRuleValue.wholeTool(toolName));
    }

    @Test
    @DisplayName("managed-only 开启 → addRules 被拒，allow 桶逐字节不变")
    void managedOnly_rejectsAddRules(@TempDir Path tmp) throws IOException {
        Path projectHome = tmp.resolve("project");
        Path file = writeProjectSettings(projectHome);
        String before = Files.readString(file);

        persister(projectHome, managedPolicy(tmp, true)).persist(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.PROJECT_SETTINGS,
            List.of(allowRule("Bash")),
            PermissionBehavior.ALLOW));

        assertThat(Files.readString(file)).isEqualTo(before);
        JsonNode allow = JSON.readTree(file.toFile()).path("permissions").path("allow");
        assertThat(allow).extracting(JsonNode::asText).containsExactly("Read");
    }

    @Test
    @DisplayName("managed-only 关闭 → addRules 正常写盘（回归防护）")
    void notManagedOnly_addRulesPersists(@TempDir Path tmp) throws IOException {
        Path projectHome = tmp.resolve("project");
        Path file = writeProjectSettings(projectHome);

        persister(projectHome, managedPolicy(tmp, false)).persist(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.PROJECT_SETTINGS,
            List.of(allowRule("Bash")),
            PermissionBehavior.ALLOW));

        JsonNode allow = JSON.readTree(file.toFile()).path("permissions").path("allow");
        assertThat(allow).extracting(JsonNode::asText).containsExactly("Read", "Bash");
    }

    @Test
    @DisplayName("门控仅作用于 addRules（CC 只在 addPermissionRulesToSettings 早退）：removeRules 仍可执行")
    void managedOnly_doesNotGateRemoveRules(@TempDir Path tmp) throws IOException {
        Path projectHome = tmp.resolve("project");
        Path file = writeProjectSettings(projectHome);

        persister(projectHome, managedPolicy(tmp, true)).persist(new PermissionUpdate.RemoveRules(
            PermissionUpdate.Destination.PROJECT_SETTINGS,
            List.of(allowRule("Read")),
            PermissionBehavior.ALLOW));

        JsonNode allow = JSON.readTree(file.toFile()).path("permissions").path("allow");
        assertThat(allow).isEmpty();
    }
}
