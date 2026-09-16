package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.source.InitialPermissionModeSource;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P11d] 项目级权限配置的<b>读写同址</b>守护 —— 会话隔离（本批最关键的不变量）。
 *
 * <h2>被守护的缺陷</h2>
 * <p>改前「项目根」被硬接成 {@code null} ⇒ {@code CwdResolution.getOriginalCwdLayer(null)} 恒返回
 * <b>进程 {@code user.dir}</b>（后端启动目录）。本仓 1 JVM : N 会话 ⇒ 所有会话共用同一份项目级
 * 权限配置（既<b>串</b>又<b>错</b>）。
 *
 * <h2>为什么必须「读写同批」守护（本类存在的理由）</h2>
 * <pre>
 *   只改读：读 会话根 ←→ 写 user.dir  ⇒ 界面显示已保存、agent 运行期不认（静默，无可见信号）
 *   只改写：读 user.dir ←→ 写 会话根  ⇒ 同上
 * </pre>
 * ⇒ 本类断言「<b>写进哪个文件</b>」== 「<b>读出来自哪个文件</b>」：同一个 {@code sessionId} 的
 * 写必须能被同 sessionId 的读看见，且<b>不得</b>被另一个 sessionId 的读看见（反之亦然）。
 * 任一侧漏传/忽略 sessionId ⇒ 本类必红。
 *
 * <h2>夹具</h2>
 * <p>{@link SessionProjectRoot#lookup} <b>先查内存冻结表</b> {@code BY_SESSION} ⇒
 * {@code setForSession} 即可固定「sess-a → tmpA / sess-b → tmpB」，<b>不需要</b> {@code setDbResolver}
 * （⚠️ 本仓陷阱：测试环境的 {@code NoDatabaseSessionProjectRootExtension} 对任意 sessionId 答
 * sessionless，但它在冻结表 miss 后才生效 ⇒ 本夹具不受其影响）。
 * <p>loader 走<b>生产 1 参构造器</b>（{@code projectRootSupplier = getOriginalCwdLayer(null)}）⇒
 * 断言的是真会话腿（{@code CwdResolution.getProjectRoot(sessionId)}），不是测试 seam。
 */
class PermissionProjectRootSessionScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PermissionRuleValueParser RULE_PARSER = new PermissionRuleValueParser();
    private static final SettingsJsonParser PARSER = new SettingsJsonParser(MAPPER, RULE_PARSER);

    /** 会话专属规则串（刻意与 user.dir 下可能存在的规则不重合，避免假绿）。 */
    private static final String RULE_A = "Bash(echo P11D_SESS_A_ONLY)";
    private static final String RULE_B = "Bash(echo P11D_SESS_B_ONLY)";

    @TempDir
    Path tempDir;

    private Path projA;
    private Path projB;
    private Path noSessionDir;

    private ProjectSettingsLoader projectLoader;
    private LocalSettingsLoader localLoader;
    private PermissionUpdatePersister persister;

    @BeforeEach
    void setUp() throws Exception {
        SessionProjectRoot.reset();
        projA = Files.createDirectories(tempDir.resolve("proj-a"));
        projB = Files.createDirectories(tempDir.resolve("proj-b"));
        noSessionDir = Files.createDirectories(tempDir.resolve("no-session-dir"));
        // 会话 → 项目根 冻结（首查命中，不触 DB 回源）
        SessionProjectRoot.setForSession("sess-a", projA.toString());
        SessionProjectRoot.setForSession("sess-b", projB.toString());

        projectLoader = new ProjectSettingsLoader(PARSER);
        localLoader = new LocalSettingsLoader(PARSER);
        persister = new PermissionUpdatePersister(
            new UserSettingsLoader(PARSER), projectLoader, localLoader, RULE_PARSER);
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.reset();
    }

    private static Path settingsFile(Path projectRoot, String fileName) {
        return projectRoot.resolve(NexusaiPaths.getProjectDirName()).resolve(fileName);
    }

    private static List<String> ruleStrings(List<PermissionRule> rules) {
        return rules.stream().map(r -> r.ruleValue().toRuleString()).toList();
    }

    /** 按规则串（{@code Tool(content)} / {@code Tool}）构造 AddRules · 与 {@code toRuleString()} 往返一致。 */
    private static PermissionUpdate.AddRules addRuleStrings(PermissionUpdate.Destination dest, String... ruleStrings) {
        List<PermissionRule> rules = java.util.Arrays.stream(ruleStrings)
            .map(s -> {
                int open = s.indexOf('(');
                PermissionRuleValue v = open < 0
                    ? PermissionRuleValue.wholeTool(s)
                    : PermissionRuleValue.withContent(s.substring(0, open),
                        s.substring(open + 1, s.length() - 1));
                return new PermissionRule(PermissionRuleSource.PROJECT_SETTINGS, PermissionBehavior.ALLOW, v);
            })
            .toList();
        return new PermissionUpdate.AddRules(dest, rules, PermissionBehavior.ALLOW);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ⭐⭐ 本批最关键断言：读写同址（写进哪个文件 == 读出来自哪个文件）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("⭐⭐ 读写同址（projectSettings）：sess-a 写的只有 sess-a 读得到，sess-b 读不到")
    void projectSettings_readWriteSameAddress() {
        persister.persist(addRuleStrings(PermissionUpdate.Destination.PROJECT_SETTINGS, RULE_A), "sess-a");
        persister.persist(addRuleStrings(PermissionUpdate.Destination.PROJECT_SETTINGS, RULE_B), "sess-b");

        // 写侧落点：各落各自项目根（不是后端启动目录）
        assertThat(settingsFile(projA, "settings.json"))
            .as("sess-a 的写盘必须落到 sess-a 的项目根").exists()
            .content().contains("P11D_SESS_A_ONLY");
        assertThat(settingsFile(projB, "settings.json"))
            .as("sess-b 的写盘必须落到 sess-b 的项目根").exists()
            .content().contains("P11D_SESS_B_ONLY");

        // ⭐ 读侧 == 写侧（同址）：读回的正是自己写的那个文件
        assertThat(ruleStrings(projectLoader.load("sess-a")))
            .as("sess-a 读到的必须恰好是 sess-a 写的（读写同址）").containsExactly(RULE_A);
        assertThat(ruleStrings(projectLoader.load("sess-b")))
            .as("sess-b 读到的必须恰好是 sess-b 写的（读写同址）").containsExactly(RULE_B);
    }

    @Test
    @DisplayName("⭐⭐ 读写同址（localSettings）：同 sessionId 的 save→read 必须命中同一文件")
    void localSettings_readWriteSameAddress() {
        // 直接用 loader 的写 API → 读 API（不经 persister），最直接的一对读写
        localLoader.savePermissionsField("allow", List.of(RULE_A), "sess-a");
        localLoader.savePermissionsValue("defaultMode", "plan", "sess-a");
        localLoader.savePermissionsField("allow", List.of(RULE_B), "sess-b");

        assertThat(localLoader.readPermissionsStringArray("allow", "sess-a"))
            .as("sess-a 写进 settings.local.json 的内容必须被 sess-a 的读看见").containsExactly(RULE_A);
        assertThat(localLoader.readPermissionsStringArray("allow", "sess-b"))
            .as("sess-b 的读不得看见 sess-a 的写（反之亦然 —— 跨会话串值即红）").containsExactly(RULE_B);

        Path fileA = settingsFile(projA, "settings.local.json");
        Path fileB = settingsFile(projB, "settings.local.json");
        assertThat(fileA).exists();
        assertThat(fileB).exists();
        assertThat(localLoader.load("sess-a"))
            .as("load(sessionId) 与 readPermissionsStringArray(field, sessionId) 必须同址")
            .extracting(r -> r.ruleValue().toRuleString()).containsExactly(RULE_A);
        assertThat(localLoader.load("sess-b"))
            .extracting(r -> r.ruleValue().toRuleString()).containsExactly(RULE_B);
    }

    @Test
    @DisplayName("projectSettings 与 localSettings 的 defaultMode 互不串（sess-a 的 defaultMode 不被 sess-b 看见）")
    void defaultMode_notCrossSession() throws Exception {
        localLoader.savePermissionsValue("defaultMode", "plan", "sess-a");

        InitialPermissionModeSource source = new InitialPermissionModeSource(PARSER);
        assertThat(source.resolveInput("sess-a", null, false).settingsDefaultMode())
            .as("sess-a 的 defaultMode 读侧必须取自 sess-a 的项目根").isEqualTo("plan");
        // sess-b 的项目级文件不存在 ⇒ user 层（~/.nexusai/settings.json）未设 ⇒ null
        assertThat(source.resolveInput("sess-b", null, false).settingsDefaultMode())
            .as("sess-b 不得读到 sess-a 的 defaultMode（串值即红）").isNull();
        // 落点复核：只写在 sess-a 的项目根
        assertThat(settingsFile(projA, "settings.local.json")).exists();
        assertThat(settingsFile(projB, "settings.local.json")).doesNotExist();
    }

    @Test
    @DisplayName("⭐⭐ 生产入口 persistAll（复数）：sessionId 必须逐条下传到 loader（写落该会话项目根）")
    void persistAll_propagatesSessionIdToLoader() {
        // ⭐ 两条腿必须**可区分**：无会话腿 → noSessionDir；会话腿 → projA/projB。
        //   （若夹具把两条腿指向同一目录，漏传 sessionId 也照样绿 —— 这正是本类要防的盲区。）
        ProjectSettingsLoader distinguishableProject =
            new ProjectSettingsLoader(PARSER, () -> noSessionDir.toString());
        LocalSettingsLoader distinguishableLocal =
            new LocalSettingsLoader(PARSER, () -> noSessionDir.toString());
        PermissionUpdatePersister distinguishable = new PermissionUpdatePersister(
            new UserSettingsLoader(PARSER), distinguishableProject, distinguishableLocal, RULE_PARSER);

        // 生产调用形态：一次 persistAll 携带多条 update（ToolPermissionGate / WebSocketPermissionPrompter 同形态）
        distinguishable.persistAll(List.of(
            addRuleStrings(PermissionUpdate.Destination.PROJECT_SETTINGS, RULE_A),
            addRuleStrings(PermissionUpdate.Destination.LOCAL_SETTINGS, RULE_B)), "sess-a");

        // 1) 落点 = 该会话的项目根
        assertThat(settingsFile(projA, "settings.json"))
            .as("persistAll → persist → loader 的 sessionId 必须逐条下传（project source）")
            .exists().content().contains("P11D_SESS_A_ONLY");
        assertThat(settingsFile(projA, "settings.local.json"))
            .as("persistAll → persist → loader 的 sessionId 必须逐条下传（local source）")
            .exists().content().contains("P11D_SESS_B_ONLY");
        // 2) ⛔ 不得落无会话腿（漏传 sessionId 时就是这里会红）
        assertThat(settingsFile(noSessionDir, "settings.json"))
            .as("漏传 sessionId 会把写盘落到无会话腿（本断言就是那条漏传的红灯）").doesNotExist();
        assertThat(settingsFile(noSessionDir, "settings.local.json")).doesNotExist();
        // 3) 另一会话读不到
        assertThat(ruleStrings(distinguishableProject.load("sess-b")))
            .as("sess-b 不得看见 sess-a 经 persistAll 写入的规则").isEmpty();
        // 4) 读写同址：读写经生产入口往返一致
        assertThat(ruleStrings(distinguishableProject.load("sess-a"))).containsExactly(RULE_A);
        assertThat(ruleStrings(distinguishableLocal.load("sess-a"))).containsExactly(RULE_B);
    }

    @Test
    @DisplayName("⭐ 生产入口 persist（单数，/add-dir 走的那条）：sessionId 必须下传到 loader")
    void persist_propagatesSessionIdToLoader() {
        ProjectSettingsLoader distinguishableProject =
            new ProjectSettingsLoader(PARSER, () -> noSessionDir.toString());
        LocalSettingsLoader distinguishableLocal =
            new LocalSettingsLoader(PARSER, () -> noSessionDir.toString());
        PermissionUpdatePersister distinguishable = new PermissionUpdatePersister(
            new UserSettingsLoader(PARSER), distinguishableProject, distinguishableLocal, RULE_PARSER);

        // CommandRegistrationConfigGroupAGitDir:412（/add-dir → LOCAL_SETTINGS）走的就是单数版
        distinguishable.persist(
            addRuleStrings(PermissionUpdate.Destination.LOCAL_SETTINGS, RULE_A), "sess-a");

        assertThat(settingsFile(projA, "settings.local.json"))
            .as("persist（单数）的 sessionId 必须下传到 loader").exists()
            .content().contains("P11D_SESS_A_ONLY");
        assertThat(settingsFile(noSessionDir, "settings.local.json"))
            .as("单数版漏传 sessionId ⇒ 落无会话腿 ⇒ 本断言红").doesNotExist();
        assertThat(ruleStrings(distinguishableLocal.load("sess-b")))
            .as("sess-b 不得看见 sess-a 的写盘").isEmpty();
        assertThat(ruleStrings(distinguishableLocal.load("sess-a"))).containsExactly(RULE_A);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 确无会话腿（命名出口）——⛔ 不得退化成「别的会话的项目根 / user.dir 静默兜底」
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("确无会话腿：sessionId=null 走注入的命名出口，不读任何会话的项目根")
    void noSessionLeg_usesNamedExitNotAnyProjectRoot() {
        // 先给 sess-a 放一条会话专属规则（且会话腿必须能看到它）
        projectLoader.savePermissionsField("allow", List.of(RULE_A), "sess-a");
        assertThat(settingsFile(projA, "settings.json")).exists();

        // 注入式构造器把「无会话腿」钉到 no-session-dir（生产 = getOriginalCwdLayer(null)）
        ProjectSettingsLoader noSessionLoader =
            new ProjectSettingsLoader(PARSER, () -> noSessionDir.toString());
        noSessionLoader.savePermissionsField("allow", List.of(RULE_B), null);

        assertThat(settingsFile(noSessionDir, "settings.json"))
            .as("null sessionId ⇒ 走命名无会话出口（注入的 supplier）").exists();
        assertThat(settingsFile(projB, "settings.json"))
            .as("无会话腿不得写到任何会话的项目根").doesNotExist();

        // ⛔ 无会话腿的读不得命中任何会话项目根（读的正是命名出口那个文件，⛔ 不是 sess-a 的）
        //   注：这里断言的是**注入命名出口**的 loader（确定性）；生产 loader 的 null 腿 =
        //   进程 user.dir（本机可能已有 .nexusai/settings.json）⇒ 不对其断言文件内容。
        assertThat(ruleStrings(noSessionLoader.load(null)))
            .as("无会话腿必须读命名出口文件（⛔ 不得跨到 sessions 的项目根）")
            .containsExactly(RULE_B);
        // 会话腿必须看见自己的 —— 两侧文件各自独立
        assertThat(ruleStrings(projectLoader.load("sess-a")))
            .as("会话腿读到的是会话项目根那个文件").containsExactly(RULE_A);
        assertThat(settingsFile(projA, "settings.json"))
            .as("sess-a 的规则只落在 sess-a 的项目根").exists()
            .content().contains("P11D_SESS_A_ONLY");
        assertThat(settingsFile(noSessionDir, "settings.json")).content()
            .doesNotContain("P11D_SESS_A_ONLY");
    }

    @Test
    @DisplayName("哨兵 no-session：与 null 同语义（命名出口），不落任何会话项目根")
    void noSessionSentinel_sameAsNull() {
        ProjectSettingsLoader noSessionLoader =
            new ProjectSettingsLoader(PARSER, () -> noSessionDir.toString());
        noSessionLoader.savePermissionsField("allow", List.of(RULE_A),
            com.nexusai.common.SessionKeys.NO_SESSION);

        assertThat(settingsFile(noSessionDir, "settings.json")).exists();
        assertThat(settingsFile(projA, "settings.json")).doesNotExist();
        assertThat(settingsFile(projB, "settings.json")).doesNotExist();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 鉴权/失败态：真会话但无绑定 ⇒ fail-loud（⛔ 不用 user.dir 冒充）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("会话存在但无绑定项目根 ⇒ 读侧 load() 记 warn 返回空（lenient 契约）；写侧 fail-loud 抛")
    void unboundSession_failsLoudOnWrite_lenientOnRead() {
        // sess-c 未登记 ⇒ 冻结表 miss ⇒ 回源器答「会话存在但未绑定」（unbound）
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unbound());

        try {
            // 读侧：PermissionSourceLoader 契约 = 失败返回空 list（⛔ 不落 user.dir 冒充）
            assertThat(projectLoader.load("sess-c"))
                .as("会话存在却无绑定 ⇒ 读侧 lenient 返回空表").isEmpty();

            // 写侧：不得静默写到 user.dir —— fail-loud 抛（CwdResolution 的 cwd 域铁律）
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> projectLoader.savePermissionsField("allow", List.of(RULE_A), "sess-c"))
                .as("会话存在却无绑定 ⇒ 写侧 fail-loud（⛔ 不得用进程 user.dir 冒充项目根）")
                .isInstanceOf(IllegalStateException.class);
            // 且不得留下任何落盘文件
            assertThat(settingsFile(projA, "settings.json")).doesNotExist();
            assertThat(settingsFile(projB, "settings.json")).doesNotExist();
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }
}
