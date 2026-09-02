package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [EX_G_DisableAllHooks R1 残留] merged disableAllHooks 接线测试（RED 先行）.
 *
 * <p>WHY (R1): {@code HooksSettings.shouldDisableAllMerged()} 原经 {@code FileConfigStorage}
 * 读单文件, 生产无注入 → 恒 false → CC hooksConfigSnapshot.ts:47-49 分支 4
 * (merged.disableAllHooks → 仅 managed/policy hooks) 在 Java 端永不触发, 非 managed 想禁全部
 * hook 的企业场景失效. 本测试锁定修复后的链路: {@link MultiSourceHooksConfigLoader} 加载完成后
 * 从 merged settings 顶层 disableAllHooks (标量 last-wins: user→project→local→policy,
 * 对齐 CC getInitialSettings 全源 mergeWith, settings.ts:674-729) 注入 HooksSettings.
 *
 * <p>对齐 CC 真源 (唯一语义基线, 不信注释):
 * <ul>
 *   <li>getSettings_DEPRECATED = getInitialSettings (settings.ts:820) — 合并<b>全部</b>
 *       enabled 源含 policy (settings.ts:674-729), 标量后源覆盖先源</li>
 *   <li>mergedSettings.disableAllHooks === true → 仅 policy hooks (hooksConfigSnapshot.ts:47-49)</li>
 *   <li>managed-only 双条件: merged.disableAllHooks 且 policy.disableAllHooks !== true
 *       (hooksConfigSnapshot.ts:62-76) — policy 自身禁全部时不回退 managed-only</li>
 * </ul>
 */
@DisplayName("[EX_G_DisableAllHooks] merged disableAllHooks 接线（user settings → shouldDisableAllMerged）")
class MultiSourceDisableAllHooksTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 测试前原始 user.home（@AfterEach 恢复，防污染同 JVM 其它测试）。 */
    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    // ── 构造 helper（镜像 MultiSourceHooksConfigLoaderTest）─────────────────

    private MultiSourceHooksConfigLoader newLoader(
            HooksSettings settings,
            HooksConfigSnapshot snapshot,
            String policyPath,
            String userHome,
            String nexusaiHome) throws IOException {
        ManagedPolicySettingsSupplier policy =
            new ManagedPolicySettingsSupplier(mapper, policyPath);
        // 生产同路径: loader 与 HooksSettings 共用同一 supplier
        // (setManagedPolicySettingsSupplier @Autowired 注入; 测试手动 wire,
        //  否则 shouldDisableAll()/policyHooksFromSettings() 恒 key->null)
        settings.setManagedPolicySettingsSupplier(policy);
        return new MultiSourceHooksConfigLoader(
            mapper, settings, snapshot, policy, () -> nexusaiHome, userHome);
    }

    private void writeUser(String userHome, String json) throws IOException {
        Path dir = Path.of(userHome, ".nexusai");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
    }

    private void writeLocal(String nexusaiHome, String json) throws IOException {
        Path dir = Path.of(nexusaiHome, ".nexusai");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.local.json"), json);
    }

    private void writePolicy(String policyPath, String json) throws IOException {
        Files.createDirectories(Path.of(policyPath).getParent());
        Files.writeString(Path.of(policyPath), json);
    }

    /**
     * 隔离 NexusaiPaths 自有根到 {@code tempDir/user/.nexusai}（决策 D1/D2，R5-1）。
     *
     * <p>WHY: 生产 {@code userSettingsPath()} 读 {@link NexusaiPaths#getAppConfigHomeDir()}/settings.json
     * （= {user.home}/.{appName}/settings.json，appName=nexusai），忽略构造 userHome；@BeforeEach 把
     * {@code user.home} 指向 {@code tempDir/user} 使 NexusaiPaths 根落 writeUser 写盘处，否则 user 源恒空。
     */
    @BeforeEach
    void setUp() {
        System.setProperty("user.home", tempDir.resolve("user").toString());
        NexusaiPaths.setAppNameOverride("nexusai");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", ORIGINAL_USER_HOME);
        NexusaiPaths.setAppNameOverride(null);   // 复位 nexusai 自有根 appName 隔离
    }

    // ── 1. user settings 顶层 disableAllHooks=true → merged true ─────────────

    @Test
    @DisplayName("1. user settings.json 顶层 disableAllHooks=true（无 hooks 字段）→ shouldDisableAllMerged=true")
    void userDisableAllHooksTrue_shouldDisableAllMergedTrue() throws Exception {
        // WHY: R1 残留 —— 修复前 shouldDisableAllMerged 生产恒 false（configStorage 无注入）。
        //       文件只有顶层 disableAllHooks、无 hooks 字段：两个键相互独立，disableAll 仍须注入
        //       （CC getInitialSettings 合并的是整个 settings 对象，非仅 hooks 子对象）。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, "{\"disableAllHooks\": true}");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged())
            .as("user settings disableAllHooks=true → merged 判定 true").isTrue();
        // 无 policy → managed-only 语义（非 managed 禁全部但管不了 managed，CC :69-74）
        assertThat(settings.shouldAllowManagedHooksOnly()).isTrue();
        // 分支 4: 仅 policy hooks（无 policy → 空）
        assertThat(snapshot.getHooksConfigFromSnapshot()).isEmpty();
    }

    // ── 2. 缺省 / 显式 false → merged false（回归）────────────────────────

    @Test
    @DisplayName("2. 缺省与显式 false → shouldDisableAllMerged=false（回归）")
    void absentAndExplicitFalse_shouldDisableAllMergedFalse() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, "{}");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged())
            .as("缺省 → false（不误伤正常 hook 链）").isFalse();
        assertThat(settings.shouldAllowManagedHooksOnly()).isFalse();

        // 显式 false 同样不触发
        writeUser(userHome, "{\"disableAllHooks\": false}");
        loader.updateHooksConfigSnapshot();
        assertThat(settings.shouldDisableAllMerged())
            .as("显式 false → false").isFalse();
    }

    // ── 3. 标量 last-wins: local 显式 false 覆盖 user true ─────────────────

    @Test
    @DisplayName("3. user=true + local=false → merged false（后源显式 false 覆盖先源 true）")
    void explicitFalseOverridesUserTrue_lastWins() throws Exception {
        // WHY: CC mergeWith 标量覆盖（settings.ts:529-547）—— 后合并源覆盖先合并源。
        //       false 也是 present 值，必须覆盖（非“仅 true 参与合并”）。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, "{\"disableAllHooks\": true}");
        writeLocal(projHome, "{\"disableAllHooks\": false}");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged())
            .as("local(false) 覆盖 user(true) → merged false").isFalse();
    }

    // ── 4. 标量 last-wins: policy 覆盖 local ───────────────────────────────

    @Test
    @DisplayName("4. local=true + policy=false → merged false（policy 最高优先级覆盖）")
    void localTrueThenPolicyFalse_mergedFalse() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy.json").toString();
        writeLocal(projHome, "{\"disableAllHooks\": true}");
        writePolicy(policyPath, "{\"disableAllHooks\": false}");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged())
            .as("policy(false) 覆盖 local(true) → merged false").isFalse();
    }

    // ── 5. allowManagedHooksOnly 分支不回退：policy 禁全部时 managed-only 必须 false ──

    @Test
    @DisplayName("5. user=true + policy 也 disableAllHooks=true → merged true 但 managed-only 不回退")
    void policyDisableAllHooks_managedOnlyNotFallback() throws Exception {
        // WHY: CC :69-74 双条件第二项要求 policySettings.disableAllHooks !== true。
        //       policy 自身禁全部 → 分支 1（全部禁用含 managed）短路，不得误判为 managed-only。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy.json").toString();
        writeUser(userHome, "{\"disableAllHooks\": true}");
        writePolicy(policyPath, "{\"disableAllHooks\": true}");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged())
            .as("merged 含 policy → true（CC getInitialSettings 全源合并）").isTrue();
        assertThat(settings.shouldDisableAll())
            .as("policy disableAllHooks=true → 全部禁用含 managed").isTrue();
        assertThat(settings.shouldAllowManagedHooksOnly())
            .as("policy 自身禁全部 → 不回退 managed-only（CC :69-74 排除 policy）").isFalse();
        // 分支 1: 快照为空（含 managed 全禁）
        assertThat(snapshot.getHooksConfigFromSnapshot()).isEmpty();
    }

    // ── 6. 运行中变更生效：改 settings.json → updateHooksConfigSnapshot → merged 更新 ──

    @Test
    @DisplayName("6. 运行中 user settings 增加 disableAllHooks=true → update 后 merged=true（不重启）")
    void runtimeSettingsEdit_updateReflectsDisableAllHooks() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, "{}");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged()).isFalse();

        // 运行中写入 disableAllHooks（模拟外部编辑 / REST 更新落盘，对齐 CC updateHooksConfigSnapshot）
        writeUser(userHome, "{\"disableAllHooks\": true}");
        loader.updateHooksConfigSnapshot();

        assertThat(settings.shouldDisableAllMerged())
            .as("运行中变更生效（CC resetSettingsCache + recapture 等价）").isTrue();
    }

    // ── 7. 端到端分支 4: merged disableAllHooks=true + policy hooks → 快照返回 policy hooks ──

    @Test
    @DisplayName("7. user disableAllHooks=true + policy hooks → 快照分支 4 返回 policy hooks（端到端）")
    void mergedDisableAllHooks_snapshotBranch4_returnsPolicyHooks() throws Exception {
        // WHY: PolicyGateHookRegistryTest test 9 用手动注入；本用例走真实加载链 ——
        //       settings.json → loader → HooksSettings → snapshot 分支 4（R1 生产链路闭环）。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy.json").toString();
        writeUser(userHome, "{\"disableAllHooks\": true}");
        writePolicy(policyPath, """
            {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "command", "command": "echo policy-hook"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged()).isTrue();
        assertThat(settings.shouldAllowManagedHooksOnly())
            .as("merged disableAllHooks && policy 未禁 → managed-only 语义").isTrue();

        List<HookMatcher> matchers = snapshot.getHooksConfigFromSnapshot().get(HookEventType.PRE_TOOL_USE);
        assertThat(matchers).as("分支 4 必须返回 policy hooks（非空）").isNotEmpty();
        assertThat(matchers.get(0).matcher()).isEqualTo("Bash");
        assertThat(matchers.get(0).hooks())
            .extracting(h -> ((CommandHook) h).command())
            .containsExactly("echo policy-hook");
    }

    // ── 8. 分支 5 回归: 无 disableAllHooks → 快照返回合并 user hooks ─────────

    @Test
    @DisplayName("8. 无 disableAllHooks → 分支 5 返回 user hooks（回归，门控不误伤）")
    void defaultNoDisableAll_snapshotBranch5_regression() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "command", "command": "echo user-hook"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged()).isFalse();
        assertThat(settings.shouldAllowManagedHooksOnly()).isFalse();

        List<HookMatcher> matchers = snapshot.getHooksConfigFromSnapshot().get(HookEventType.PRE_TOOL_USE);
        assertThat(matchers).as("分支 5 返回合并 user hooks（向后兼容）").isNotEmpty();
        assertThat(matchers.get(0).hooks())
            .extracting(h -> ((CommandHook) h).command())
            .containsExactly("echo user-hook");
    }
}
