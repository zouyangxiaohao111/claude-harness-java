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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMPL-08] 多来源 hooks 配置加载链单元测试（RED 先行）.
 *
 * <p>WHY (规则九 · 测试验证意图): CC loadSettingsFromDisk (settings.ts:645-796) 按
 * userSettings → projectSettings → localSettings → policySettings 顺序逐源读取 +
 * lodash mergeWith 深合并, settingsMergeCustomizer (settings.ts:538-547) 对数组
 * concat + 去重, 后合并源覆盖先合并源 (constants.ts:5-6 "later sources override
 * earlier ones"). 旧实现 HooksConfigLoader 仅单源 (USER_SETTINGS, EV-CFG-011),
 * 运行中配置变更 0 调用方 (EV-CFG-007/020). 本测试锁定:
 * <ol>
 *   <li>多来源合并: 各源 hooks 全部进入 merged (concat)</li>
 *   <li>来源优先级: 同 matcher 同内容 hook 后源覆盖先源 (last-wins, 对齐 CC 匹配层 dedup)</li>
 *   <li>policy 覆盖: policy hooks 进 merged, 且覆盖同内容 user hook</li>
 *   <li>运行中变更生效: 改 settings.json 文件 → updateHooksConfigSnapshot() → 新 hook 生效 (不重启)</li>
 *   <li>seenFiles 去重: 同 resolvedPath 只处理一次 (CC settings.ts:746-747)</li>
 * </ol>
 *
 * <p>对齐 CC: settings 加载失败不中断 (lenient), 解析失败 warn + 跳过.
 */
@DisplayName("[IMPL-08] MultiSourceHooksConfigLoader 多来源合并 + 快照刷新")
class MultiSourceHooksConfigLoaderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 测试前原始 user.home（@AfterEach 恢复，防污染同 JVM 其它测试）。 */
    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    /**
     * 构造加载链:
     * <pre>
     * tempDir/user/.nexusai/settings.json          (user 源)
     * tempDir/proj/.nexusai/settings.json          (project 源)
     * tempDir/proj/.nexusai/settings.local.json    (local 源)
     * tempDir/policy.json                          (policy 源)
     * </pre>
     */
    private MultiSourceHooksConfigLoader newLoader(
            HooksSettings settings,
            HooksConfigSnapshot snapshot,
            String policyPath,
            String userHome,
            String nexusaiHome) throws IOException {
        ManagedPolicySettingsSupplier policy =
            new ManagedPolicySettingsSupplier(mapper, policyPath);
        return new MultiSourceHooksConfigLoader(
            mapper, settings, snapshot, policy, () -> nexusaiHome, userHome);
    }

    private void writeUser(String userHome, String json) throws IOException {
        Path dir = Path.of(userHome, ".nexusai");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
    }

    private void writeProject(String nexusaiHome, String json) throws IOException {
        Path dir = Path.of(nexusaiHome, ".nexusai");
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
     * 隔离 NexusaiPaths 自有根到 {@code tempDir/user/.nexusai}（决策 D1/D2）。
     *
     * <p>WHY (R5-1): 生产 {@code userSettingsPath()} 已改读
     * {@link NexusaiPaths#getAppConfigHomeDir()}/settings.json（= {user.home}/.{appName}/settings.json，
     * appName=nexusai），忽略构造传入 userHome。本测试 writeUser 写
     * {@code tempDir/user/.nexusai/settings.json} —— 故 @BeforeEach 必须把 {@code user.home}
     * 指向 {@code tempDir/user} 且 appName 固定 nexusai，否则 loader 读真实 ~/.nexusai/settings.json，
     * user 源恒空（9 失败根因）。
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

    // ── 1. 多来源合并: 各源 hooks 全部进入 merged (concat) ────────────────

    @Test
    @DisplayName("1. user+project+local 三源同名 matcher 不同 hook → 全部保留 (concat)")
    void threeSources_distinctHooks_allMerged() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo user-write"}]}]}}
            """);
        writeProject(projHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo project-write"}]}]}}
            """);
        writeLocal(projHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Read", "hooks": [
                {"type": "command", "command": "echo local-read"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all)
            .extracting(h -> h.matcher() + ":" + ((CommandHook) h.config()).command())
            .containsExactlyInAnyOrder(
                "Write:echo user-write",
                "Write:echo project-write",
                "Read:echo local-read");
        // 快照分支 5 (merged) 亦含 3 条
        List<HookMatcher> matchers =
            snapshot.getHooksConfigFromSnapshot().get(HookEventType.PRE_TOOL_USE);
        assertThat(matchers).hasSize(2); // Write + Read 两个 matcher
    }

    // ── 2. 来源优先级: 同 matcher 同内容 hook 后源覆盖先源 (last-wins) ─────

    @Test
    @DisplayName("2. 同 matcher 同内容 hook: local 覆盖 project 覆盖 user (后源 last-wins)")
    void sameHookAcrossSources_lastWins_highPrioritySource() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        // user 与 project 定义完全相同 hook → 只保留 1 条 (去重, 后源胜出)
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo same"}]}]}}
            """);
        writeProject(projHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo same"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).source()).isEqualTo(HookSource.PROJECT_SETTINGS);
    }

    // ── 3. policy 覆盖: policy hooks 进 merged, 覆盖同内容 user hook ─────

    @Test
    @DisplayName("3. policy hooks 进 merged 且覆盖同内容 user hook (最高优先级)")
    void policyHooks_overrideUserHook() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy.json").toString();
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo user-hook"}]}]}}
            """);
        writePolicy(policyPath, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo managed-hook"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        // user 与 policy 同 matcher 不同 hook 内容 → concat 2 条; 同内容则 policy 胜出
        assertThat(all)
            .extracting(h -> ((CommandHook) h.config()).command())
            .containsExactlyInAnyOrder("echo user-hook", "echo managed-hook");

        // policy 覆盖同内容: user 改为与 policy 相同内容 → 仅 1 条, source=POLICY_SETTINGS
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo managed-hook"}]}]}}
            """);
        loader.updateHooksConfigSnapshot();
        List<IndividualHookConfig> after = settings.getAllHooks();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).source()).isEqualTo(HookSource.POLICY_SETTINGS);
    }

    // ── 4. 运行中变更生效: 改 settings.json → updateHooksConfigSnapshot → 生效 ──

    @Test
    @DisplayName("4. 运行中改 user settings.json → updateHooksConfigSnapshot() → 新 hook 生效 (不重启)")
    void runtimeSettingsChange_hookTakesEffectWithoutRestart() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        // 初始: user 无 hooks
        writeUser(userHome, "{}");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        assertThat(settings.getAllHooks()).isEmpty();

        // 运行中写入 hooks 配置 (模拟外部编辑 / REST 更新落盘)
        writeUser(userHome, """
            {"hooks": {"Stop": [{"hooks": [
                {"type": "command", "command": "echo stop-hook"}]}]}}
            """);
        loader.updateHooksConfigSnapshot();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).event()).isEqualTo(HookEventType.STOP);
        // 快照同步刷新 (对齐 CC updateHooksConfigSnapshot = resetSettingsCache + recapture)
        assertThat(snapshot.getHooksConfigFromSnapshot().containsKey(HookEventType.STOP)).isTrue();
    }

    // ── 5. seenFiles 去重: 同 resolvedPath 只处理一次 ─────────────────────

    @Test
    @DisplayName("5. user 与 project 指向同一文件 → 只加载一次 (CC settings.ts:746-747)")
    void sameFileAcrossSources_loadedOnce() throws Exception {
        // userSettingsPath = NexusaiPaths 根 = {user.home}/.nexusai/settings.json（user.home=tempDir/user，
        // 见 @BeforeEach）；令 projectRoot(=projHome) 亦 = tempDir/user → projectSettingsPath 同文件
        // → seenFiles 去重只加载一次（CC settings.ts:746-747）
        String userHome = tempDir.resolve("user").toString();
        String projHome = userHome;
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo once"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all).hasSize(1); // 而非 2 (user+project 双份)
    }

    // ── 6. lenient: 单源解析失败不影响其它源 ──────────────────────────────

    @Test
    @DisplayName("6. local settings.json 损坏 → user/project 仍加载 (lenient)")
    void brokenLocalFile_otherSourcesStillLoad() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo ok"}]}]}}
            """);
        writeLocal(projHome, "{ invalid json !!!");

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all).hasSize(1);
        assertThat(((CommandHook) all.get(0).config()).command()).isEqualTo("echo ok");
    }

    // ── 7. HookCommand sealed 4 种子类型反序列化（旧 HooksConfigLoaderTest 语义迁移） ──

    @Test
    @DisplayName("7. hooks JSON 4 种子类型 (command/prompt/http/agent) + matcher → 反序列化 + 快照")
    void fourSubtypes_deserializeAndCapture() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [
                {"matcher": "Write", "hooks": [
                    {"type": "command", "command": "echo hi", "shell": "bash", "timeout": 30}
                ]},
                {"matcher": "Edit", "hooks": [
                    {"type": "prompt", "prompt": "review this"}
                ]},
                {"matcher": "Bash", "hooks": [
                    {"type": "http", "url": "https://example.com/hook"}
                ]},
                {"matcher": "Read", "hooks": [
                    {"type": "agent", "prompt": "agent prompt"}
                ]}
            ]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all).hasSize(4);
        assertThat(all).extracting(h -> h.config().hookType().name())
            .containsExactlyInAnyOrder("COMMAND", "PROMPT", "HTTP", "AGENT");
        // 快照已捕获 (启动链路)
        assertThat(snapshot.getHooksConfigFromSnapshot()
            .get(HookEventType.PRE_TOOL_USE)).hasSize(4);
    }

    // ── 8. H1 (DIF-CFG-01): merged allowlist 注入 getHttpHookPolicy ─────────
    // CC execHttpHook.ts:49-58 getHttpHookPolicy 读 getInitialSettings()（全源合并）,
    // settings.ts:529-531 mergeArrays=uniq([...target,...source]) 保序去重跨源 concat.

    @Test
    @DisplayName("8a. user 源配置 allowedHttpHookUrls + 无 policy → getHttpHookPolicy 返回 user 值 (RED 前为 null)")
    void mergedAllowlist_userSourceNoPolicy_httpHookPolicyReadsUserValue() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        writeUser(userHome, """
            {"allowedHttpHookUrls": ["https://user.example.com/*"],
             "httpHookAllowedEnvVars": ["TOKEN"]}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        HttpHookPolicy policy = settings.getHttpHookPolicy();
        assertThat(policy.allowedUrls())
            .as("user 源 allowlist 必须经 merged 视图生效 (CC getInitialSettings 全源)")
            .containsExactly("https://user.example.com/*");
        assertThat(policy.allowedEnvVars())
            .as("user 源 env var allowlist 必须经 merged 视图生效")
            .containsExactly("TOKEN");
    }

    @Test
    @DisplayName("8b. user+policy 跨源 concat 保序去重 (CC mergeArrays uniq([...target,...source]))")
    void mergedAllowlist_userPlusPolicy_concatOrderedDeduped() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy.json").toString();
        writeUser(userHome, """
            {"allowedHttpHookUrls": ["https://u1.example.com/*", "https://u2.example.com/*"]}
            """);
        writePolicy(policyPath, """
            {"allowedHttpHookUrls": ["https://p1.example.com/*", "https://u1.example.com/*"]}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);
        loader.init();

        HttpHookPolicy policy = settings.getHttpHookPolicy();
        // user → policy 顺序累加; u1 重复 → 首序保留 (uniq 保首现)
        assertThat(policy.allowedUrls())
            .as("跨源 concat 保序去重: [u1, u2] + [p1, u1] → [u1, u2, p1]")
            .containsExactly(
                "https://u1.example.com/*",
                "https://u2.example.com/*",
                "https://p1.example.com/*");
    }

    @Test
    @DisplayName("8c. 显式空数组 → [] (全拦三态保留) vs 全部未配置 → null (不限制)")
    void mergedAllowlist_explicitEmptyVsAbsent_tristatePreserved() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();

        // 显式空数组: user 配置 [] → merged 非 null 空 list = 全拦
        writeUser(userHome, """
            {"allowedHttpHookUrls": []}
            """);
        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();
        assertThat(settings.getHttpHookPolicy().allowedUrls())
            .as("显式空数组必须保留为 [] (CC 三态: [] = 全拦, 非 null)")
            .isNotNull()
            .isEmpty();

        // 全部未配置 → merged 保持 null = undefined = 不限制
        writeUser(userHome, "{}");
        loader.updateHooksConfigSnapshot();
        assertThat(settings.getHttpHookPolicy().allowedUrls())
            .as("全部源未配置 → null (undefined = 不限制)")
            .isNull();
        assertThat(settings.getHttpHookPolicy().allowedEnvVars())
            .as("env var 未配置 → null")
            .isNull();
    }

    @Test
    @DisplayName("8d. policy hooks 非对象 + disableAllHooks=true → 整层丢弃 (DIF-CFG-05 zod 整文件校验)")
    void policyHooksNonObject_disableAllHooksDroppedWholeLayer() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy.json").toString();
        // hooks 为数组 (非对象) → CC SettingsSchema().safeParse 失败 → 该源 settings=null,
        // disableAllHooks 与 allowlist 一并丢弃 (settings.ts:749-758)
        writePolicy(policyPath, """
            {"hooks": [{"matcher": "Bash", "hooks": ["echo x"]}],
             "disableAllHooks": true,
             "allowedHttpHookUrls": ["https://policy.example.com/*"]}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged())
            .as("policy 整层无效 → disableAllHooks 一并丢弃 (CC zod 整文件校验)")
            .isFalse();
        assertThat(settings.getHttpHookPolicy().allowedUrls())
            .as("policy 整层无效 → allowlist 一并丢弃")
            .isNull();
        assertThat(settings.getAllHooks())
            .as("policy hooks 无效 → 不加载")
            .isEmpty();
    }

    // ── [H-WF1-01] 插件 hook 热重载快照（对齐 CC loadPluginHooks.ts:233-247 getPluginAffectingSettingsSnapshot）──

    /**
     * [H-WF1-01] pluginAffectingSettingsSnapshot 4 字段 + policy 覆盖重叠键 + 变更检测。
     *
     * <p>WHY (规则九): CC setupPluginHookHotReload 仅当 4 字段（enabledPlugins /
     * extraKnownMarketplaces / strictKnownMarketplaces / blockedMarketplaces）任一变化才重载
     * （loadPluginHooks.ts:266-272）—— 快照必须反映 merged settings 的对象键深合并
     * （高源 policy 覆盖重叠键、低源非重叠键保留，settings.ts:534-547）且对字段变化敏感，
     * 否则热重载判定失灵（漏重载或空重载）。
     */
    @Test
    @DisplayName("[H-WF1-01] pluginAffectingSettingsSnapshot: policy 覆盖 + 字段变更敏感")
    void pluginAffectingSettingsSnapshot_policyLastWins_andChangeSensitive() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy").resolve("managed.json").toString();
        writeUser(userHome, """
            {"enabledPlugins": {"a@market": true}}
            """);
        writePolicy(policyPath, """
            {"enabledPlugins": {"a@market": true, "b@market": false},
             "strictKnownMarketplaces": ["m1"]}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);

        String s1 = loader.pluginAffectingSettingsSnapshot();
        assertThat(s1)
            .as("policy enabledPlugins 必须合并进 merged（对象键深合并, CC :241-246）")
            .contains("\"b@market\"");
        assertThat(s1)
            .as("policy strictKnownMarketplaces 必须进入快照（CC :244）")
            .contains("\"m1\"");

        // enabledPlugins 字段变化 → 快照变化（热重载判定据此触发）
        writePolicy(policyPath, """
            {"enabledPlugins": {"a@market": true, "b@market": true},
             "strictKnownMarketplaces": ["m1"]}
            """);
        String s2 = loader.pluginAffectingSettingsSnapshot();
        assertThat(s2)
            .as("policy enabledPlugins 变化 → 快照必须变化（CC :267-272 diff 敏感）")
            .isNotEqualTo(s1);

        // 无关字段变化（strictKnownMarketplaces 相同, enabledPlugins 相同）→ 快照稳定
        String s3 = loader.pluginAffectingSettingsSnapshot();
        assertThat(s3)
            .as("相同插件相关字段 → 快照确定性（防空重载, CC :266-272）")
            .isEqualTo(s2);
    }

    /**
     * [H-WF1-01 R1 返工] pluginAffectingSettingsSnapshot 对象键<b>深合并</b>（CC
     * {@code mergeWith} + {@code settingsMergeCustomizer} 对象默认深合并，settings.ts:534-547
     * + :663-779，非 last-wins 整体替换）。
     *
     * <p>WHY (规则九): CC getInitialSettings 逐源深合并 —— 低源<b>非重叠键</b>必须保留进快照，
     * 否则 policy 只覆盖重叠键时低源键丢失 → 快照差异失真；同一键双方数组 concat+去重
     * （settings.ts:529-531 {@code uniq}）。R1 前 Java 为 last-wins 整体替换（低源键全丢）→
     * spurious reload 边界：低源已有 {a,b}、policy 在 {a}↔{b} 间切换时 CC 深合并不变<b>跳过</b>、
     * Java 变化<b>误触发</b>（空 I/O + 重注册 hook）。
     */
    @Test
    @DisplayName("[H-WF1-01 R1] enabledPlugins 对象键深合并: 低源非重叠键保留 + 高源覆盖重叠键 + 数组 concat")
    void pluginAffectingSettingsSnapshot_objectKeyDeepMerge() throws Exception {
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        String policyPath = tempDir.resolve("policy").resolve("managed.json").toString();
        writeUser(userHome, """
            {"enabledPlugins": {"a@market": true, "userOnly@market": true,
                                "p@market": ["x"]}}
            """);
        writePolicy(policyPath, """
            {"enabledPlugins": {"a@market": false, "policyOnly@market": false,
                                "p@market": ["y"]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, policyPath, userHome, projHome);

        String s = loader.pluginAffectingSettingsSnapshot();
        assertThat(s)
            .as("深合并: 低源非重叠键 userOnly@market 在 policy 存在时仍进入快照（CC mergeWith 默认对象合并 settings.ts:663-779）")
            .contains("\"userOnly@market\"");
        assertThat(s)
            .as("深合并: 高源 policy 重叠键 a@market 覆盖低源 user 值（last-wins per-key）")
            .contains("\"a@market\":false");
        assertThat(s)
            .as("深合并: policy 非重叠键 policyOnly@market 进入快照")
            .contains("\"policyOnly@market\"");
        assertThat(s)
            .as("深合并: 同一键双方数组 concat+去重（settings.ts:529-531 uniq([...target,...source])）")
            .contains("\"x\"", "\"y\"");
    }

    // ── [T3 hook 读兼容] claude 只读回落（nexusai 优先 + claude 回落，对齐 skills/commands） ─────

    @Test
    @DisplayName("T3-1: 仅 claude user settings 有 hook（nexusai 无）→ claude hook 回落加载")
    void claudeFallback_onlyClaudeHooksLoaded() throws Exception {
        // WHY（规则九）：老用户已在 ~/.claude/settings.json 配 hook，切 nexusai 后应读兼容（T3）
        // —— nexusai 无配置时 claude hook 正常加载（回落），不丢既有 hook。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        // claude user settings（user.home 隔离 → tempDir/user/.claude/settings.json）
        Path claudeDir = Path.of(userHome, ".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), """
            {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "command", "command": "echo claude-user-bash"}]}]}}
            """);
        // nexusai user settings 不写 → 回落 claude

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all)
            .extracting(h -> h.matcher() + ":" + ((CommandHook) h.config()).command())
            .contains("Bash:echo claude-user-bash");
    }

    @Test
    @DisplayName("T3-2: claude + nexusai 配置完全相同 hook → 折叠为 1 条（last-wins 去重，nexusai 不重复加载）")
    void claudeFallback_nexusaiOverridesClaude() throws Exception {
        // WHY（规则九）：双目录配置完全相同的 hook 时 last-wins 折叠为 1 条（getAllHooks 同
        // identity 去重）——claude 回落不产生重复 hook（决策 D1/D6 自有根优先，不双发）。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        // claude user settings（回落源）与 nexusai 配置完全相同 hook
        Path claudeDir = Path.of(userHome, ".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo same-hook"}]}]}}
            """);
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Write", "hooks": [
                {"type": "command", "command": "echo same-hook"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        // 同 (event, matcher, config) 折叠 → 只保留 1 条（不因 claude 回落双发）
        assertThat(all)
            .extracting(h -> h.matcher() + ":" + ((CommandHook) h.config()).command())
            .containsExactly("Write:echo same-hook");
    }

    @Test
    @DisplayName("T3-3: claude 独有 hook + nexusai 独有 hook → 两者均加载（合并）")
    void claudeFallback_distinctHooksMerged() throws Exception {
        // WHY（规则九）：claude 独有 hook 回落 + nexusai 独有 hook 并存，互不覆盖（T3 合并语义）。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        // claude user settings（回落源）：Bash hook（claude 独有）
        Path claudeDir = Path.of(userHome, ".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), """
            {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "command", "command": "echo claude-bash"}]}]}}
            """);
        // nexusai user settings（主源）：Read hook（nexusai 独有）
        writeUser(userHome, """
            {"hooks": {"PreToolUse": [{"matcher": "Read", "hooks": [
                {"type": "command", "command": "echo nexusai-read"}]}]}}
            """);

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all)
            .extracting(h -> h.matcher() + ":" + ((CommandHook) h.config()).command())
            .containsExactlyInAnyOrder("Bash:echo claude-bash", "Read:echo nexusai-read");
    }

    @Test
    @DisplayName("T3-4: claude user settings 配置 disableAllHooks / allowlist → 纳入 claude base（nexusai 未配置时生效）")
    void claudeFallback_disableAllAndAllowlistFromClaude() throws Exception {
        // WHY（决策点 4 拍板）：hook 读 claude 的 T3 读兼容范围含 allowlist/disableAllHooks 全局项。
        // claude 配置 disableAllHooks=true → nexusai 未配置时禁用全部 hook（标量 last-wins 兜底）。
        String userHome = tempDir.resolve("user").toString();
        String projHome = tempDir.resolve("proj").toString();
        // claude user settings（回落源）配置全局项
        Path claudeDir = Path.of(userHome, ".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), """
            {"disableAllHooks": true,
             "allowedHttpHookUrls": ["https://claude.example.com/hook"],
             "hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
                {"type": "command", "command": "echo claude-bash"}]}]}}
            """);
        // nexusai user settings 不写 → disableAll/allowlist 从 claude base 兜底

        HooksSettings settings = new HooksSettings();
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        MultiSourceHooksConfigLoader loader =
            newLoader(settings, snapshot, "", userHome, projHome);
        loader.init();

        assertThat(settings.shouldDisableAllMerged())
            .as("claude disableAllHooks=true 回落生效（决策点 4：T3 含全局项，merged 视图）").isTrue();
        assertThat(settings.getHttpHookPolicy().allowedUrls())
            .as("claude allowlist 回落纳入 merged allowlist").contains("https://claude.example.com/hook");
    }
}
