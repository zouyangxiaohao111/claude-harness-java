package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.PolicySettingsLoader;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-3 G1] managed-only 加载门控测试 · 对齐 CC {@code loadAllPermissionRulesFromDisk}
 * （permissionsLoader.ts:120-133）：{@code allowManagedPermissionRulesOnly === true} 时
 * 只返回 {@code getPermissionRulesForSource('policySettings')}，跳过所有可编辑源。
 *
 * <p><b>WHY</b>: 旧 {@link PermissionContextBuilder#buildPermissionContextCore} 遍历<b>全部</b>
 * 注入 loader 全源加载，无 managed-only 过滤——企业管控"仅 managed 规则"时，用户/项目
 * settings 里的旧 allow 规则仍生效，绕过企业策略。本测试锁定：managed-only 开启时上下文
 * 只含 POLICY_SETTINGS 源；关闭时含全部源（回归防护）。
 */
@DisplayName("[IMP-3 G1] managed-only 加载门控：仅加载 POLICY_SETTINGS 源规则")
class PermissionContextBuilderManagedOnlyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SettingsJsonParser PARSER =
        new SettingsJsonParser(JSON, new PermissionRuleValueParser());

    /** 写 managed policy 文件（含/不含 managed-only 开关 + policy allow 规则），返回其路径。 */
    private static Path writePolicy(Path dir, boolean allowManagedOnly) throws IOException {
        Path policy = dir.resolve("managed-settings.json");
        Files.createDirectories(dir);
        Files.writeString(policy, allowManagedOnly
            ? "{\"allowManagedPermissionRulesOnly\": true, \"permissions\": {\"allow\": [\"PolicyRead\"]}}"
            : "{\"permissions\": {\"allow\": [\"PolicyRead\"]}}");
        return policy;
    }

    private static Path writeProjectSettings(Path projectHome) throws IOException {
        Path settingsFile = projectHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, """
            { "permissions": { "allow": [ "ProjectRead" ] } }
            """);
        return settingsFile;
    }

    /** 装配 builder：project loader + policy loader，注入 managed-only 判定器。 */
    private PermissionContextBuilder builder(Path projectHome, Path policyDir, boolean managedOnly) throws IOException {
        writeProjectSettings(projectHome);
        Path policy = writePolicy(policyDir, managedOnly);
        PermissionContextBuilder b = new PermissionContextBuilder(List.of(
            new ProjectSettingsLoader(PARSER, () -> projectHome.toString()),
            new PolicySettingsLoader(PARSER, policy.toString())
        ));
        b.setManagedPolicy(new PermissionManagedPolicy(
            new ManagedPolicySettingsSupplier(JSON, policy.toString())));
        return b;
    }

    private static AgentState state() {
        return new AgentState("system", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
    }

    @Test
    @DisplayName("managed-only 开启 → 只加载 POLICY_SETTINGS，跳过 project 源")
    void managedOnly_onlyLoadsPolicy(@TempDir Path tmp) throws IOException {
        Path projectHome = tmp.resolve("project");
        Path policyDir = tmp.resolve("policy");

        ToolPermissionContext ctx = builder(projectHome, policyDir, true).buildPermissionContext(state(), false, null, false, true);

        Set<PermissionRuleSource> sources = ctx.alwaysAllowRules().keySet();
        assertThat(sources).containsExactly(PermissionRuleSource.POLICY_SETTINGS);
        assertThat(sources).doesNotContain(PermissionRuleSource.PROJECT_SETTINGS);
    }

    @Test
    @DisplayName("managed-only 关闭 → 加载全部源（project + policy，回归防护）")
    void notManagedOnly_loadsAllSources(@TempDir Path tmp) throws IOException {
        Path projectHome = tmp.resolve("project");
        Path policyDir = tmp.resolve("policy");

        ToolPermissionContext ctx = builder(projectHome, policyDir, false).buildPermissionContext(state(), false, null, false, true);

        Set<PermissionRuleSource> sources = ctx.alwaysAllowRules().keySet();
        assertThat(sources).contains(
            PermissionRuleSource.POLICY_SETTINGS,
            PermissionRuleSource.PROJECT_SETTINGS);
    }
}
