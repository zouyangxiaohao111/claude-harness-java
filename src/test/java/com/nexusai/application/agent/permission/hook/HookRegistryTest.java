package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hook 注册中心测试 ·
 * ① 既有：事件级早返查询（避免无 PermissionDenied hook 时进入执行链）。
 * ② [IMP-CF-03] statusLine / fileSuggestion 后端执行器 · 对齐 CC executeStatusLineCommand
 *    (utils/hooks.ts:4584-4666) + executeFileSuggestionCommand (utils/hooks.ts:4675-4738)。
 *
 * <p>WHY (规则九 · 测试验证意图): 执行器的意图是 —— statusLine/fileSuggestion 顶层配置存在
 * 且通过 disableAll / trust / managedOnly 门控时执行 command 并解析输出；任一条件不满足时
 * <b>零副作用</b>（不得启动任何子进程）。本测试覆盖三条 CC 决策路径:
 * <ol>
 *   <li>门控拦截（disableAllHooks / trust 拒绝）→ 即使配置存在也不执行</li>
 *   <li>配置缺失 / type 非 'command' → 不执行</li>
 *   <li>配置存在且通过门控 → 执行 command，statusLine 归一化 stdout、fileSuggestion 解析行列表</li>
 * </ol>
 *
 * <p>不依赖 Spring 容器：手动构造 HookRegistry + HooksSettings / HooksConfigSnapshot /
 * CommandHookExecutor (FakeHookProcess，复用 CommandHookExecutorTest) /
 * MultiSourceHooksConfigLoader (@TempDir 写 settings.json)。
 */
@DisplayName("[IMP-CF-03] statusLine / fileSuggestion 后端执行器（对齐 CC executeStatusLineCommand/executeFileSuggestionCommand）")
class HookRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 构造 helper ─────────────────────────────────────────────────────────

    /**
     * 组装 registry：快照（门控判定）+ hooksSettings（policy 读源）+ 可选 loader（merged 读源）
     * + 可选 commandHookExecutor（执行源）。
     *
     * <p>快照与 registry 共用同一 {@code settings}（否则门控判定与 policy 读源不一致）。
     */
    private static HookRegistry newRegistry(HooksSettings settings, MultiSourceHooksConfigLoader loader,
                                            CommandHookExecutor executor) {
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHooksSettings(settings);
        if (loader != null) {
            registry.setHooksConfigLoader(loader);
        }
        if (executor != null) {
            registry.setCommandHookExecutor(executor);
        }
        return registry;
    }

    /** policy 键值 supplier：仅返回给定键值，其余 null. */
    private static HooksSettings policySettings(Map<String, Object> policy) {
        return new HooksSettings(key -> policy.get(key));
    }

    /** 构造 command 配置 JSON 值 (JsonNode) · CC {type:'command', command}. */
    private static Object commandJson(String command) {
        try {
            return MAPPER.readTree("{\"type\":\"command\",\"command\":\"" + command + "\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造 merged 读源 loader（无 policy 文件；mergedTopLevelObject 直接读盘）. */
    private static MultiSourceHooksConfigLoader newLoader(Path tmp, HooksSettings settings) {
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        return new MultiSourceHooksConfigLoader(MAPPER, settings, snapshot,
            new ManagedPolicySettingsSupplier(MAPPER, ""),
            () -> tmp.toString(), tmp.toString());
    }

    /** 构造测试用 executor · fake launcher 恒返回给定进程. */
    private static CommandHookExecutor newExecutor(CommandHookExecutorTest.FakeLauncher launcher) {
        return new CommandHookExecutor(launcher,
            k -> null, p -> true, () -> "C:/project",
            pluginId -> "C:/Users/test/.claude/plugins/" + pluginId);
    }

    /** 写 &lt;tmp&gt;/.nexusai/settings.json (user/project 源共享路径). */
    private static void writeSettingsFile(Path tmp, String json) throws Exception {
        Path dir = tmp.resolve(NexusaiPaths.getProjectDirName());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 0. 既有：hasHookForEvent 事件级早返
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void hookRegistry_hasHookForEvent_returnsTrueWhenRegistered() {
        HookRegistry registry = new HookRegistry();
        registry.register("permission-denied", event -> GenericHook.HookResult.proceed(),
            HookEventType.PERMISSION_DENIED);
        assertThat(registry.hasHookForEvent("PermissionDenied", null)).isTrue();
    }

    @Test
    void hookRegistry_hasHookForEvent_returnsFalseWhenNotRegistered() {
        HookRegistry registry = new HookRegistry();
        registry.register("post-tool", event -> GenericHook.HookResult.proceed(),
            HookEventType.POST_TOOL_USE);
        assertThat(registry.hasHookForEvent("PermissionDenied", null)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. statusLine · 门控拦截（零副作用）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1a. statusLine 未配置 → null 且不启动子进程（CC :4613-4615 无 statusLine → undefined）")
    void statusLine_noConfig_returnsNullWithoutExecuting() {
        // WHY: 未配置 statusLine 时执行器必须早返 —— 不得启动任何子进程；旧缺失实现无此入口。
        CommandHookExecutorTest.FakeLauncher launcher =
            new CommandHookExecutorTest.FakeLauncher(CommandHookExecutorTest.FakeHookProcess.normal("ignored", "", 0));
        HookRegistry registry = newRegistry(policySettings(Map.of()), null, newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false); // 非交互，trust 隐式

        assertThat(registry.executeStatusLineCommand(Map.of("query", "x"))).isNull();
        assertThat(launcher.lastSpec).as("无 statusLine 配置不得启动子进程").isNull();
    }

    @Test
    @DisplayName("1b. policy disableAllHooks=true → 即使配置存在也不执行（CC :4591-4593 + types.ts:462 注释）")
    void statusLine_disableAllHooks_returnsNull() {
        // WHY: settings 顶层 disableAllHooks 注释 "Whether to disable all hooks and statusLine
        //       execution"（types.ts:462）—— 禁用全部 hook 时 statusLine 一并禁用。
        Map<String, Object> policy = new HashMap<>();
        policy.put("disableAllHooks", Boolean.TRUE);
        policy.put("statusLine", commandJson("echo should-not-run"));
        CommandHookExecutorTest.FakeLauncher launcher =
            new CommandHookExecutorTest.FakeLauncher(CommandHookExecutorTest.FakeHookProcess.normal("x", "", 0));
        HookRegistry registry = newRegistry(policySettings(policy), null, newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeStatusLineCommand(Map.of())).isNull();
        assertThat(launcher.lastSpec).as("disableAllHooks=true 时不得执行 statusLine 命令").isNull();
    }

    @Test
    @DisplayName("1c. trust 拒绝（交互+未接受）→ 不执行（CC :4597-4602 防 RCE 安全门）")
    void statusLine_trustRejected_returnsNull() {
        // WHY: 交互模式全部 hook 要求 workspace trust —— 拒绝 trust 后 statusLine 命令不得执行
        //       （防 RCE，历史漏洞 hooks.ts:280-283）。
        Map<String, Object> policy = new HashMap<>();
        policy.put("statusLine", commandJson("echo should-not-run"));
        CommandHookExecutorTest.FakeLauncher launcher =
            new CommandHookExecutorTest.FakeLauncher(CommandHookExecutorTest.FakeHookProcess.normal("x", "", 0));
        HookRegistry registry = newRegistry(policySettings(policy), null, newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> false, () -> false); // 交互 + 未接受

        assertThat(registry.executeStatusLineCommand(Map.of())).isNull();
        assertThat(launcher.lastSpec).as("trust 拒绝时不得执行 statusLine 命令").isNull();
    }

    @Test
    @DisplayName("1d. statusLine type 非 'command' → null 且不启动子进程（CC :4613 type!=='command' → undefined）")
    void statusLine_nonCommandType_returnsNull(@TempDir Path tmp) throws Exception {
        // WHY: 配置形态允许 type 判别 —— 未来非 command 形态（如 function）不得误按 command 执行。
        writeSettingsFile(tmp, "{ \"statusLine\": { \"type\": \"function\", \"command\": \"x\" } }");
        CommandHookExecutorTest.FakeLauncher launcher =
            new CommandHookExecutorTest.FakeLauncher(CommandHookExecutorTest.FakeHookProcess.normal("x", "", 0));
        HookRegistry registry = newRegistry(policySettings(Map.of()),
            newLoader(tmp, policySettings(Map.of())), newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeStatusLineCommand(Map.of())).isNull();
        assertThat(launcher.lastSpec).as("type!=='command' 不得启动子进程").isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. statusLine · 配置存在 → 执行 + 输出归一化
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2a. statusLine 配置 + exit 0 → stdout 按 CC :4640-4644 归一化（trim/去空行/join）")
    void statusLine_configuredAndExit0_returnsNormalizedOutput(@TempDir Path tmp) throws Exception {
        // WHY: statusLine 命令 exit 0 时 stdout 需 trim → split '\n' → 行 trim 去空 → join '\n'
        //       （CC :4640-4644）—— 首尾空白与空行必须剔除，否则前端 status line 显示残渣。
        writeSettingsFile(tmp, "{ \"statusLine\": { \"type\": \"command\", \"command\": \"echo status\" } }");
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("  alpha  \n\n  beta  \n", "", 0));
        HookRegistry registry = newRegistry(policySettings(Map.of()),
            newLoader(tmp, policySettings(Map.of())), newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeStatusLineCommand(Map.of("session_id", "s1")))
            .as("exit 0 → trim + 去空行 + join").isEqualTo("alpha\nbeta");
        assertThat(launcher.lastSpec).as("配置存在 → 必须启动子进程").isNotNull();
    }

    @Test
    @DisplayName("2b. statusLine 非零退出 → null（CC :4654-4661 仅 status===0 用 stdout）")
    void statusLine_nonZeroExit_returnsNull(@TempDir Path tmp) throws Exception {
        // WHY: 非零退出表示命令失败 → statusLine 无有效输出；不得把 stderr/部分输出当 status 展示。
        writeSettingsFile(tmp, "{ \"statusLine\": { \"type\": \"command\", \"command\": \"exit 3\" } }");
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("ignored", "err", 3));
        HookRegistry registry = newRegistry(policySettings(Map.of()),
            newLoader(tmp, policySettings(Map.of())), newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeStatusLineCommand(Map.of())).isNull();
    }

    @Test
    @DisplayName("2c. managedOnly → statusLine 只读 policy 配置（CC :4607-4608 policySettings.statusLine）")
    void statusLine_managedOnly_readsPolicyConfig(@TempDir Path tmp) throws Exception {
        // WHY: managedOnly 时 statusLine 必须取自 policySettings（CC :4607-4608）——
        //       merged settings 的同名 statusLine 被忽略（企业策略优先）。
        Map<String, Object> policy = new HashMap<>();
        policy.put("allowManagedHooksOnly", Boolean.TRUE);
        policy.put("statusLine", commandJson("echo policy-status"));
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("out", "", 0));
        // loader 存在但 merged 无 statusLine；managedOnly 分支必须读 policy（不读 merged）
        HooksSettings settings = policySettings(policy);
        HookRegistry registry = newRegistry(settings, newLoader(tmp, settings), newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        registry.executeStatusLineCommand(Map.of());
        assertThat(launcher.lastSpec).as("managedOnly 分支必须执行命令").isNotNull();
        assertThat(String.join(" ", launcher.lastSpec.commandArgs()))
            .as("执行的是 policy 配置的命令，而非 merged 配置")
            .contains("policy-status");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. fileSuggestion · 执行 + 行列表解析
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3a. fileSuggestion 未配置 → 空列表且不启动子进程（CC :4703-4705 无配置 → []）")
    void fileSuggestion_noConfig_returnsEmpty() {
        CommandHookExecutorTest.FakeLauncher launcher =
            new CommandHookExecutorTest.FakeLauncher(CommandHookExecutorTest.FakeHookProcess.normal("ignored", "", 0));
        HookRegistry registry = newRegistry(policySettings(Map.of()), null, newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeFileSuggestionCommand(Map.of("query", "x"))).isEmpty();
        assertThat(launcher.lastSpec).as("无 fileSuggestion 配置不得启动子进程").isNull();
    }

    @Test
    @DisplayName("3b. fileSuggestion 配置 + exit 0 → split '\\n' + trim + 去空行（CC :4728-4731）")
    void fileSuggestion_configuredAndExit0_returnsTrimmedLines(@TempDir Path tmp) throws Exception {
        // WHY: fileSuggestion 命令输出是文件路径列表 —— 每行 trim + 空行剔除（CC :4728-4731），
        //       尾随换行不得产生空条目，行内空白不得残留。
        writeSettingsFile(tmp, "{ \"fileSuggestion\": { \"type\": \"command\", \"command\": \"echo files\" } }");
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("src/A.java\n src/B.java \n\n", "", 0));
        HookRegistry registry = newRegistry(policySettings(Map.of()),
            newLoader(tmp, policySettings(Map.of())), newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeFileSuggestionCommand(Map.of("query", "src/")))
            .as("exit 0 → split + trim + 去空行").containsExactly("src/A.java", "src/B.java");
    }

    @Test
    @DisplayName("3c. fileSuggestion 非零退出 → 空列表（CC :4724-4726 aborted||status!==0 → []）")
    void fileSuggestion_nonZeroExit_returnsEmpty(@TempDir Path tmp) throws Exception {
        // WHY: 命令失败不得把部分输出当建议列表返回 —— 前端会拿到错误文件路径。
        writeSettingsFile(tmp, "{ \"fileSuggestion\": { \"type\": \"command\", \"command\": \"exit 1\" } }");
        CommandHookExecutorTest.FakeLauncher launcher = new CommandHookExecutorTest.FakeLauncher(
            CommandHookExecutorTest.FakeHookProcess.normal("ignored", "", 1));
        HookRegistry registry = newRegistry(policySettings(Map.of()),
            newLoader(tmp, policySettings(Map.of())), newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeFileSuggestionCommand(Map.of("query", "x"))).isEmpty();
    }

    @Test
    @DisplayName("3d. fileSuggestion disableAllHooks=true → 空列表（CC :4681-4683）")
    void fileSuggestion_disableAllHooks_returnsEmpty() {
        Map<String, Object> policy = new HashMap<>();
        policy.put("disableAllHooks", Boolean.TRUE);
        policy.put("fileSuggestion", commandJson("echo should-not-run"));
        CommandHookExecutorTest.FakeLauncher launcher =
            new CommandHookExecutorTest.FakeLauncher(CommandHookExecutorTest.FakeHookProcess.normal("x", "", 0));
        HookRegistry registry = newRegistry(policySettings(policy), null, newExecutor(launcher));
        registry.setTrustGateSuppliers(() -> true, () -> false);

        assertThat(registry.executeFileSuggestionCommand(Map.of())).isEmpty();
        assertThat(launcher.lastSpec).as("disableAllHooks=true 时不得执行 fileSuggestion 命令").isNull();
    }

    @Test
    @DisplayName("3e. fileSuggestion 事件 marker ccName 对齐 CC（StatusLine/FileSuggestion 字面量）")
    void markerEventTypes_ccNames_alignWithCc() {
        // WHY: CC execCommandHook 的 hookEvent 参数接受 'StatusLine'/'FileSuggestion' 字面量
        //       （utils/hooks.ts:749）—— Java 端 marker 事件 ccName 必须精确返回这两个字面量，
        //       否则 hook 响应事件名（HookEventBus / 前端 hook 事件）偏离 CC。
        assertThat(HookEventType.STATUS_LINE.ccName()).isEqualTo("StatusLine");
        assertThat(HookEventType.FILE_SUGGESTION.ccName()).isEqualTo("FileSuggestion");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. [IMP-CF-04 TY-05] HookProgress.promptText/statusMessage · 消息流 hook_progress 载荷
    // ════════════════════════════════════════════════════════════════════════

    /** 匹配引擎 stub · 覆写 getMatchingHooks 直接返回预置 hooks（镜像 CancellationSemanticsTest）。 */
    static final class StubMatcherEngine extends HookMatcherEngine {
        volatile List<MatchedHook> hooks = List.of();

        StubMatcherEngine() {
            super(null, null);
        }

        void setHooks(List<MatchedHook> hooks) {
            this.hooks = hooks;
        }

        @Override
        public List<MatchedHook> getMatchingHooks(HookEvent event) {
            return hooks;
        }
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT
        );
    }

    @Test
    @DisplayName("3f. [MT-02] registered/插件源并入 getMatchingHooks 统一单链: 注册 plugin matcher → 返回集含插件 hook + managedOnly 门控")
    void getMatchingHooks_registeredPluginSource_mergedIntoUnifiedChain() {
        // WHY (OPD-WF2-MT-02): CC getHooksConfig 把 registered 源 (PluginHookMatcher) 并入
        //   getMatchingHooks 统一链 (hooks.ts:1519-1529), managedOnly 时跳过插件 matchers
        //   (hooks.ts:1524). 本测试验证 HookRegistry.getMatchingHooks 返回集包含注册的插件
        //   hook (hookSource="plugin:name"), 且 shouldAllowManagedHooksOnly=true 时插件 matcher
        //   被过滤.
        HookMatcherEngine engine = new HookMatcherEngine(null, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);

        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Read",
            "/root/plug", "demo", "demo", null,
            List.of(new CommandHook("echo plugin", null, null, null, null, null, null, null)));

        List<MatchedHook> matched = registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null));
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).hookSource()).isEqualTo("plugin:demo");
        assertThat(matched.get(0).pluginRoot()).isEqualTo("/root/plug");
        assertThat(matched.get(0).pluginId()).isEqualTo("demo");
    }

    @Test
    @DisplayName("3g. [MT-02] registered 源 managedOnly 门控: allowManagedHooksOnly=true → 插件 matcher 排除")
    void getMatchingHooks_registeredPluginSource_managedOnlyFiltersPlugin() {
        // WHY (OPD-WF2-MT-02): CC getHooksConfig hooks.ts:1524
        //   `managedOnly && 'pluginRoot' in matcher → skip` —— managedOnly 时插件 hooks 不并入
        //   统一链 (企业策略下禁插件 hook), SDK callback matchers (无 pluginRoot) 保留.
        HookMatcherEngine engine = new HookMatcherEngine(null, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(new HooksSettings(k -> {
            if ("allowManagedHooksOnly".equals(k)) {
                return Boolean.TRUE;
            }
            return null;
        }));
        snapshot.captureHooksConfigSnapshot();
        registry.setHooksConfigSnapshot(snapshot);

        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Read",
            "/root/plug", "demo", "demo", null,
            List.of(new CommandHook("echo plugin", null, null, null, null, null, null, null)));
        // SDK callback matcher 无 pluginRoot → managedOnly 下保留
        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Write",
            null, null, null, null,
            List.of(new CommandHook("echo sdk", null, null, null, null, null, null, null)));

        List<MatchedHook> matched = registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null));
        // 插件 matcher (pluginRoot!=null) 排除, SDK callback matcher (无 pluginRoot) 保留
        assertThat(matched).isEmpty();
    }

    @Test
    @DisplayName("3h. [IMP-HR-02 R-3] clearPluginHooks 清空 registeredHookMatchers store（对齐 CC clearRegisteredPluginHooks）")
    void clearPluginHooks_clearsRegisteredMatchers_keepsSdkCallbacks() {
        // WHY (R-3): clearPluginHooks 是 loadPluginHooks 的 clear-then-register 原子换的 clear 半，
        //   对齐 CC clearRegisteredPluginHooks（state.ts:1446-1461）——必须同时清空 registered
        //   matchers store（插件 matchers），否则每次 loadPluginHooks 逐组追加 → 插件 matcher 重复
        //   N 份 + 禁用插件 matcher 残留。SDK callback matchers（pluginRoot==null）按 CC 语义保留
        //   （state.ts:1453-1454 keep only callback hooks）。
        HookMatcherEngine engine = new HookMatcherEngine(null, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);

        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Read",
            "/root/plug", "demo", "demo", null,
            List.of(new CommandHook("echo plugin", null, null, null, null, null, null, null)));
        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Write",
            "/root/plug2", "demo2", "demo2", null,
            List.of(new CommandHook("echo plugin2", null, null, null, null, null, null, null)));
        // SDK callback matcher（无 pluginRoot）应在 clear 后保留
        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Bash",
            null, null, null, null,
            List.of(new CommandHook("echo sdk", null, null, null, null, null, null, null)));

        registry.clearPluginHooks();

        // 插件 matchers（pluginRoot!=null）全部清空 → Read/Write 事件不再命中插件 hook
        assertThat(registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null))).isEmpty();
        assertThat(registry.getMatchingHooks(
            HookEvent.toolPre("Write", null, "s1", null))).isEmpty();
        // SDK callback matcher（无 pluginRoot）保留 → Bash 事件仍命中
        List<MatchedHook> sdkMatched = registry.getMatchingHooks(
            HookEvent.toolPre("Bash", null, "s1", null));
        assertThat(sdkMatched).hasSize(1);
        assertThat(sdkMatched.get(0).pluginRoot()).isNull();
    }

    @Test
    @DisplayName("3i. [IMP-HR-02 R-3] prunePluginHooks 按 enabled 插件集剪除 registeredHookMatchers（对齐 CC pruneRemovedPluginHooks）")
    void prunePluginHooks_removesDisabledPluginRegisteredMatchers_keepsEnabledAndSdk() {
        // WHY (R-3): prunePluginHooks 必须同步剪除 registered matchers store 中已禁用/卸载插件的
        //   matchers（对齐 CC pruneRemovedPluginHooks loadPluginHooks.ts:179-204 survivors 重建）——
        //   否则禁用插件 env hooks（CwdChanged/FileChanged）经 env 收集链继续发射。
        HookMatcherEngine engine = new HookMatcherEngine(null, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);

        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Read",
            "/root/ha", "ha", "ha", null,
            List.of(new CommandHook("echo a", null, null, null, null, null, null, null)));
        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Write",
            "/root/hb", "hb", "hb", null,
            List.of(new CommandHook("echo b", null, null, null, null, null, null, null)));
        // SDK callback matcher（无 pluginName/pluginRoot）不受 prune 影响
        registry.registerRegisteredHookMatcher(HookEventType.PRE_TOOL_USE, "Bash",
            null, null, null, null,
            List.of(new CommandHook("echo sdk", null, null, null, null, null, null, null)));

        // 仅 ha enabled → hb 的 registered matcher 剪除
        registry.prunePluginHooks(java.util.Set.of("ha"));

        // ha matcher 保留
        List<MatchedHook> haMatched = registry.getMatchingHooks(
            HookEvent.toolPre("Read", null, "s1", null));
        assertThat(haMatched).hasSize(1);
        assertThat(haMatched.get(0).pluginId()).isEqualTo("ha");
        // hb matcher 剪除 → Write 事件无命中
        assertThat(registry.getMatchingHooks(
            HookEvent.toolPre("Write", null, "s1", null))).isEmpty();
        // SDK callback 保留
        List<MatchedHook> sdkMatched = registry.getMatchingHooks(
            HookEvent.toolPre("Bash", null, "s1", null));
        assertThat(sdkMatched).hasSize(1);
        assertThat(sdkMatched.get(0).pluginRoot()).isNull();
    }

    @Test
    @DisplayName("4a. HookProgress.of 工厂 → type 恒 'hook_progress' + 字段原样透传（CC types/hooks.ts:234-241）")
    void hookProgress_of_setsTypeAndFields() {
        // WHY (OPD-WF1-TY-05): CC HookProgress = {type:'hook_progress', hookEvent, hookName,
        //   command, promptText?, statusMessage?}（types/hooks.ts:234-241）—— 判别字段 type 是
        //   CC 载荷的稳定标识，Java 工厂必须恒置 'hook_progress'，否则消息流消费端无法判别类型。
        HookProgress p = HookProgress.of("PreToolUse", "config-prompt:评估磁盘",
            "检查磁盘空间…", "评估磁盘空间是否充足", "spinner 文案");

        assertThat(p.type()).isEqualTo("hook_progress");
        assertThat(p.hookEvent()).isEqualTo("PreToolUse");
        assertThat(p.hookName()).isEqualTo("config-prompt:评估磁盘");
        assertThat(p.command()).isEqualTo("检查磁盘空间…");
        assertThat(p.promptText()).isEqualTo("评估磁盘空间是否充足");
        assertThat(p.statusMessage()).isEqualTo("spinner 文案");
    }

    @Test
    @DisplayName("4b. 消息流 hook_progress 载荷：config PromptHook → command=getHookDisplayText + promptText + statusMessage（CC hooks.ts:2094-2116）")
    void messageFlowHookProgress_configPromptHook_carriesCommandPromptTextStatusMessage() {
        // WHY (OPD-WF1-TY-05 / EV-WF1-TY-030 自证缺口): 消息流 hook_progress 载荷
        //   (hooks.ts:2094-2116) 在每匹配 hook 预执行时发出 command/promptText/statusMessage:
        //   command = getHookDisplayText(hook)（statusMessage ?? prompt）; promptText 仅 prompt hook
        //   （hook.prompt）; statusMessage 仅 'statusMessage' in hook && 非 null。
        //   旧 Java 无 HookProgress record，HookMessage 通道仅 List<String> 命令清单 →
        //   promptText 不可表达；本测试验证消息流 sink 收到完整载荷。
        HookRegistry registry = new HookRegistry();
        List<HookProgress> captured = new ArrayList<>();
        registry.setHookProgressMessageSink(captured::add);
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(
            new PromptHook("评估磁盘空间是否充足", null, null, null, "检查磁盘空间…", null),
            null, null, null, "settings")));
        registry.setHookMatcherEngine(engine);

        registry.executePreToolUse("Bash", MAPPER.createObjectNode(), ctx(), "tu-1");

        assertThat(captured).as("每匹配 hook 预执行发 1 条消息流 hook_progress").hasSize(1);
        HookProgress p = captured.get(0);
        assertThat(p.type()).isEqualTo("hook_progress");
        assertThat(p.hookEvent()).isEqualTo("PreToolUse");
        assertThat(p.hookName()).isEqualTo("config-prompt:评估磁盘空间是否充足");
        assertThat(p.command()).as("command = getHookDisplayText（statusMessage 优先）")
            .isEqualTo("检查磁盘空间…");
        assertThat(p.promptText()).as("promptText = hook.prompt（仅 prompt hook）")
            .isEqualTo("评估磁盘空间是否充足");
        assertThat(p.statusMessage()).as("statusMessage = hook.statusMessage（'statusMessage' in hook）")
            .isEqualTo("检查磁盘空间…");
    }

    @Test
    @DisplayName("4c. 消息流 hook_progress 载荷：programmatic hook → command=programmaticName，promptText/statusMessage=null")
    void messageFlowHookProgress_programmaticHook_carriesCommandOnly() {
        // WHY: programmatic（function 形态）hook 无 prompt/statusMessage 字段 ——
        //   command 回落 programmaticName，promptText/statusMessage 为 null
        //   （对齐 CC getHookDisplayText：function 形态仅提供 hook 名级显示）。
        HookRegistry registry = new HookRegistry();
        List<HookProgress> captured = new ArrayList<>();
        registry.setHookProgressMessageSink(captured::add);
        registry.registerPreToolUse("hook-a", (toolName, input, ctx) -> AggregatedHookResult.proceed());

        registry.executePreToolUse("Bash", MAPPER.createObjectNode(), ctx(), "tu-1");

        assertThat(captured).as("programmatic hook 预执行也发消息流 hook_progress").hasSize(1);
        HookProgress p = captured.get(0);
        assertThat(p.hookEvent()).isEqualTo("PreToolUse");
        assertThat(p.command()).isEqualTo("hook-a");
        assertThat(p.promptText()).isNull();
        assertThat(p.statusMessage()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. [IMP-HR-04 TH-01] config ask updatedInput 透传 · Ask record 第 5 参
    // ════════════════════════════════════════════════════════════════════════

    /** 覆写 execute 的 stub：不启动真实进程，按预设 stdout/status 返回。 */
    static class StubCommandExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();
        private final Function<String, CommandHookExecutor.CommandHookResult> responder;

        StubCommandExecutor(Function<String, CommandHookExecutor.CommandHookResult> responder) {
            this.responder = responder;
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort) {
            capturedJsonInput.set(jsonInput);
            return responder.apply(jsonInput);
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort,
                                                             long defaultTimeoutMs, String hookCwd) {
            // 生产 PreToolUse 链 (promptRequester==null) 走 12 参 execute (HookRegistry:4055-4059) —
            // 必须拦截 12 参重载, 否则落到真实 CommandHookExecutor 起子进程 (stdin EPIPE).
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    private static CommandHookExecutor.CommandHookResult exit0Json(String stdout) {
        return new CommandHookExecutor.CommandHookResult(stdout, "", stdout, 0, false, false);
    }

    /** settings 配 1 条 PreToolUse:Bash command hook → registry（含 stub executor）. */
    private HookRegistry registryWithConfiguredPreToolUseHook(StubCommandExecutor stub) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(stub);
        return registry;
    }

    private static JsonNode inputNode() {
        return MAPPER.createObjectNode().put("k", "v");
    }

    @Test
    @DisplayName("5a. 配置 ask hook + updatedInput → Ask.updatedInput() 承载 hook updatedInput（对齐 CC toolHooks.ts:534 + askInput=updatedInput :417-421）")
    void configAskHook_updatedInput_carriedInAskRecord() {
        // WHY (OPD-WF3-TH-01 / EV-XP-W3-004): CC ask PermissionResult 携带 updatedInput
        //   (toolHooks.ts:534), resolveHookPermissionDecision askInput = ask.updatedInput ?? input
        //   (toolHooks.ts:417-421). 旧 Java toPermissionResult ASK 9 参构造第 5 位 (updatedInput)
        //   传 null → Ask.updatedInput() 恒 null, coordinator/swarm/投机 classifier 读
        //   AskView.updatedInput() 丢失 hook updatedInput. 本测试锁定字段承载.
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"ask\","
                + "\"updatedInput\":{\"k2\":\"v2\"}}}"));
        HookRegistry registry = registryWithConfiguredPreToolUseHook(stub);

        AggregatedHookResult outcome = registry.executePreToolUse(
            "Bash", inputNode(), ctx(), "tu-1");

        assertThat(outcome.permissionBehavior()).isInstanceOf(PermissionResult.Ask.class);
        JsonNode updatedInput = ((PermissionResult.Ask) outcome.permissionBehavior()).updatedInput();
        assertThat(updatedInput).as("ask 决策必须携带 hook updatedInput（CC toolHooks.ts:534）").isNotNull();
        assertThat(updatedInput.path("k2").asText()).isEqualTo("v2");
    }

    @Test
    @DisplayName("5b. 配置 ask hook 未给 updatedInput → Ask.updatedInput() 为 null（CC updatedInput? optional, askInput 回落 input）")
    void configAskHook_noUpdatedInput_askUpdatedInputNull() {
        // WHY: CC ask 变体 updatedInput 为 optional (toolHooks.ts:534 updatedInput: result.updatedInput;
        //   hooks.ts ask PermissionResult updatedInput?: Record) — hook 未给时 undefined,
        //   askInput 回落原 input (toolHooks.ts:417-421). 不得用原 input 占位 (与 Allow 强制非空不同).
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"ask\"}}"));
        HookRegistry registry = registryWithConfiguredPreToolUseHook(stub);

        AggregatedHookResult outcome = registry.executePreToolUse(
            "Bash", inputNode(), ctx(), "tu-1");

        assertThat(outcome.permissionBehavior()).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) outcome.permissionBehavior()).updatedInput())
            .as("hook 未给 updatedInput → Ask.updatedInput() 必须为 null（非原 input 占位）").isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. [IMP-HR-03 / MT-04] 多 hook 聚合序对齐 CC 完成序（firstStop/firstBlockingError）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY (OPD-WF2-MT-04 / EV-XP-W2-001~007): CC getHooksConfig (hooks.ts:1552-1562) 把
     * session function hooks push 进单链 hooks 数组, executeHooks 经 all(hookPromises)
     * (hooks.ts:2744 = Promise.race) 按**完成序**折叠 — function hook 是内存回调（亚毫秒
     * 完成），同事件 command hook + session function hook 双产 stop/blockingError 时，
     * CC 中 function hook 通常最先折叠（first-wins 胜者 = function hook）。Java 旧实现
     * 确定性桶序（configured → session）把 function hook 排后 → 胜者与 CC 相反。
     * 本测试锁定：同事件（PreToolUse/Bash）configured command hook 与 session function
     * hook 双产 blockingError → firstBlockingError 必须来自 session function hook
     * （对齐 CC 完成序）。
     */
    @Test
    @DisplayName("6a. 同事件 configured + session function 双产 blockingError → session function 胜出（CC 完成序）")
    void mt04_sessionFunctionHook_blocksFirst_matchingCcCompletionOrder() {
        // [IMP-HR-07 · OPD-WF6-01-05] 动态会话检查：session hooks 仅在事件 sessionId 处于活跃
        //   agent 循环内参与（对齐 CC getHooksConfig appState !== undefined）→ 测试先 markRunning
        //   使会话活跃，再断言 function hook 按完成序胜出。
        String sessionUuid = "00000000-0000-0000-0000-0000000000a1";
        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            // configured command hook 产 blockingError（exit 2 → blocking，命令 hook 慢）
            StubCommandExecutor stub = new StubCommandExecutor(j -> new CommandHookExecutor.CommandHookResult(
                "", "blocked-by-configured", "blocked-by-configured", 2, false, false));
            HookRegistry registry = registryWithConfiguredPreToolUseHook(stub);
            // session function hook 产 blockingError（内存回调，快）
            registry.addFunctionHook(sessionUuid.toString(), HookEventType.PRE_TOOL_USE, "Bash",
                (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
                "function-blocked", null, null);

            GenericHook.HookResult result = registry.executeEvent(
                HookEvent.toolPre("Bash", inputNode(), sessionUuid.toString(), null));

            assertThat(result.preventContinuation())
                .as("任一 hook blocking → preventContinuation=true").isTrue();
            assertThat(result.blockingError())
                .as("MT-04: firstBlockingError 必须来自 session function hook（CC 完成序 function hook 先折叠）")
                .isNotNull()
                .extracting(HookBlockingError::blockingError)
                .isEqualTo("function-blocked");
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    /**
     * WHY (E4-3 折叠序竞速 / X-PROBE 探查-wf2-verify.md §2 E4-3, EV-XP-W2-003): CC 折叠序是
     * **完成序**（executeHooks hooks.ts:2744 `for await (const result of all(hookPromises))`，
     * all() = Promise.race 先完成先出，generators.ts:57）——function hook 是内存回调（亚毫秒
     * 完成），command hook 需起子进程（百毫秒级）→ CC 中同事件 command + session function hook
     * 双产 blockingError 时，function hook 通常**最先折叠**（first-wins 胜者 = function hook）。
     * 本测试显式建模"竞速"时序：configured command hook 模拟<b>慢进程</b>（responder 延迟
     * 150ms 才返回 blockingError）+ session function hook 即时回调产 blockingError → 断言
     * firstBlockingError 来自 session function hook（Java 以 session-first 确定性折叠近似
     * CC 完成序，对齐 IMP-HR-03 折叠序变更）。
     */
    @Test
    @DisplayName("6d. E4-3 折叠序竞速：慢 command hook（模拟进程延迟 150ms）+ 快 session function hook 双产 blockingError → session function hook 胜出（CC 完成序）")
    void e4_foldingRace_slowCommandHook_fastFunctionHook_functionHookWins() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000a4";
        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            // configured command hook 模拟慢进程：responder 延迟 150ms 后返回 blockingError
            StubCommandExecutor stub = new StubCommandExecutor(j -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return new CommandHookExecutor.CommandHookResult(
                    "", "blocked-by-configured", "blocked-by-configured", 2, false, false);
            });
            HookRegistry registry = registryWithConfiguredPreToolUseHook(stub);
            // session function hook 即时回调（亚毫秒）产 blockingError
            registry.addFunctionHook(sessionUuid.toString(), HookEventType.PRE_TOOL_USE, "Bash",
                (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
                "function-blocked", null, null);

            GenericHook.HookResult result = registry.executeEvent(
                HookEvent.toolPre("Bash", inputNode(), sessionUuid.toString(), null));

            assertThat(result.preventContinuation())
                .as("任一 hook blocking → preventContinuation=true").isTrue();
            assertThat(result.blockingError())
                .as("E4-3: 竞速时序下 firstBlockingError 仍来自 session function hook（CC 完成序 function hook 先折叠；Java session-first 确定性近似）")
                .isNotNull()
                .extracting(HookBlockingError::blockingError)
                .isEqualTo("function-blocked");
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    /**
     * WHY (IMP-HR-07 · OPD-WF6-01-05): 白名单改动态化 —— session hooks 仅在事件 sessionId 处于
     * 活跃 agent 循环内参与（对齐 CC getHooksConfig appState !== undefined，hooks.ts:1541）。
     * 本测试锁定两条路径：
     *   <ul>
     *     <li>会话<b>未</b>运行 → session function hook 被排除（不执行）</li>
     *     <li>会话<b>运行中</b>（markRunning）→ session function hook 执行并阻断</li>
     *   </ul>
     */
    @Test
    @DisplayName("6b. 动态会话门控：session 未运行 → session function hook 排除；markRunning 后参与")
    void dynamicGate_sessionRunning_controlsSessionHookParticipation() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000a2";
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        registry.setCommandHookExecutor(new StubCommandExecutor(j -> exit0Json("{}")));
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.PRE_TOOL_USE, "Bash",
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);

        // 会话未运行 → session hook 排除（白名单动态化关键路径）
        GenericHook.HookResult excluded = registry.executeEvent(
            HookEvent.toolPre("Bash", inputNode(), sessionUuid.toString(), null));
        assertThat(excluded.preventContinuation())
            .as("session 未运行 → session function hook 必须被排除（对齐 CC appState undefined）")
            .isFalse();
        assertThat(excluded.blockingError())
            .as("session 未运行 → 无 session function hook 的 blockingError")
            .isNull();

        // 会话运行中（markRunning）→ session hook 参与并阻断
        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            GenericHook.HookResult included = registry.executeEvent(
                HookEvent.toolPre("Bash", inputNode(), sessionUuid.toString(), null));
            assertThat(included.preventContinuation())
                .as("session 运行中 → session function hook 必须参与")
                .isTrue();
            assertThat(included.blockingError())
                .as("session 运行中 → blockingError 来自 session function hook")
                .isNotNull()
                .extracting(HookBlockingError::blockingError)
                .isEqualTo("function-blocked");
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    /**
     * WHY (IMP-HR-07 返工 R-1 · 反思 F1 + 返工 R-2 · 反思 F-A): CC 对 SessionStart/Setup/SubagentStart
     * 的发射点<b>不传 toolUseContext</b> → appState 恒 undefined → session hooks 排除
     * （getHooksConfig hooks.ts:1541 `!managedOnly && appState !== undefined`；appState 由
     * toolUseContext 派生 hooks.ts:2001）。CC 真源：executeSessionStartHooks (hooks.ts:3867-3892)
     * / executeSetupHooks (hooks.ts:3902-3922) / executeSubagentStartHooks (hooks.ts:3932-3951)
     * 无 toolUseContext 参。<b>SessionEnd 相反</b>：executeSessionEndHooks (hooks.ts:4097-4141)
     * 把 getAppState 传入 executeHooksOutsideREPL（:4118）→ executeHooksOutsideREPL :3015
     * `appState = getAppState ? getAppState() : undefined` → appState 可定义；主循环调用方
     * （REPL resume :1774 `getAppState: () => store.getState()` / clear conversation.ts:69 /
     * gracefulShutdown.ts:473）均传非空 getAppState → session hooks 并入（与 StopFailure 同机制）。
     * <p>Java 这些事件发射于 doRun 内（运行态窗口内，LlmAgentLoop SessionStart :1896 / Setup
     * :1943 / SessionEnd :2200；SubagentExecutor :2873 以主会话 id 发射）——若仅按「会话运行中」
     * 判定会把 SessionStart/Setup 也误纳入（相对 CC 与旧白名单双重回归）。本测试锁定：会话运行
     * （markRunning）下 SessionStart/Setup/SubagentStart 的 session function hook 必须排除
     * （CC 无 appState）；SessionEnd 是 CC appState 发射点 → 会话运行中<b>参与并阻断</b>
     * （对齐 CC 主循环 appState 定义行为）；PRE_TOOL_USE（CC appState 发射点）正对照仍参与。
     */
    @Test
    @DisplayName("6c. CC 无 appState 事件（SessionStart/Setup/SubagentStart）会话运行中排除 session function hook；SESSION_END 为 appState 发射点 → 会话运行中参与并阻断（对齐 CC executeSessionEndHooks → executeHooksOutsideREPL getAppState）")
    void dynamicGate_ccNoAppStateEvents_excluded_sessionEndParticipates() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000a3";
        UUID agentUuid = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        registry.setCommandHookExecutor(new StubCommandExecutor(j -> exit0Json("{}")));
        // 3 类 CC 无 appState 事件各注册一条会阻断的 session function hook（matcher=null 全匹配）
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.SESSION_START, null,
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.SETUP, null,
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);
        // SubagentStart 载荷 agentId 为子代理 UUID → session hook 按 agentId 检索（executeSessionHooks :5028）
        registry.addFunctionHook(agentUuid.toString(), HookEventType.SUBAGENT_START, null,
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);
        // SESSION_END：CC appState 发射点（executeSessionEndHooks → executeHooksOutsideREPL getAppState，hooks.ts:4118）→ 会话运行中应参与并阻断
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.SESSION_END, null,
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);
        // 正对照：PRE_TOOL_USE 是 CC appState 发射点，会话运行时应参与并阻断
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.PRE_TOOL_USE, null,
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);

        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            // 正对照：PRE_TOOL_USE 同会话运行 → 参与并阻断（证明 hook 注册有效 + 门控差异在事件类型）
            GenericHook.HookResult positive = registry.executeEvent(
                HookEvent.toolPre("Bash", inputNode(), sessionUuid.toString(), null));
            assertThat(positive.preventContinuation())
                .as("正对照：PRE_TOOL_USE（CC appState 发射点）会话运行中必须参与")
                .isTrue();
            assertThat(positive.blockingError())
                .as("正对照：blockingError 来自 session function hook")
                .isNotNull()
                .extracting(HookBlockingError::blockingError)
                .isEqualTo("function-blocked");

            // CC 无 appState 事件 → 即使会话运行也必须排除（不阻断）
            assertSessionHookExcluded(registry.executeEvent(
                HookEvent.sessionStart(sessionUuid.toString(), null, "startup", null, "test-model")),
                "SessionStart");
            assertSessionHookExcluded(registry.executeEvent(
                HookEvent.setup(sessionUuid.toString(), null, "init")),
                "Setup");
            assertSessionHookExcluded(registry.executeEvent(
                HookEvent.subagentStart(agentUuid.toString(), "test-agent", sessionUuid.toString())),
                "SubagentStart");

            // SESSION_END：CC appState 发射点 → 会话运行中参与并阻断（对齐 CC 主循环 appState 定义行为）
            GenericHook.HookResult sessionEnd = registry.executeEvent(
                HookEvent.sessionEnd(sessionUuid.toString(), null, ExitReasons.OTHER));
            assertThat(sessionEnd.preventContinuation())
                .as("SESSION_END（CC appState 发射点）会话运行中必须参与并阻断（对齐 CC executeSessionEndHooks → executeHooksOutsideREPL getAppState）")
                .isTrue();
            assertThat(sessionEnd.blockingError())
                .as("SESSION_END session function hook 阻断（对齐 CC :1541 并入语义）")
                .isNotNull()
                .extracting(HookBlockingError::blockingError)
                .isEqualTo("function-blocked");
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    /**
     * WHY (IMP-E4-06 · E4-XP-W67-02): USER_PROMPT_SUBMIT 在 CC_APP_STATE_PRESENT_EVENTS（IMP-HR-07 补入），
     * CC executeUserPromptSubmitHooks（hooks.ts:3826-3854）恒传 toolUseContext → appState 定义（:2001）
     * → session function hooks 并入（:1541）。但此前<b>无聚焦测试</b>验证 USER_PROMPT_SUBMIT 发射链上
     * session function hook 真实执行。本测试锁定：会话运行中注册的 session function hook 经
     * {@code executeEvent(USER_PROMPT_SUBMIT)} 发射链执行并阻断；会话未运行 → 排除（对齐 CC appState
     * undefined）。
     */
    @Test
    @DisplayName("6e. USER_PROMPT_SUBMIT session function hook: 会话运行中执行并阻断；未运行排除（CC executeUserPromptSubmitHooks appState）")
    void userPromptSubmit_sessionFunctionHook_participates_whenSessionRunning() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000a8";
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        registry.setCommandHookExecutor(new StubCommandExecutor(j -> exit0Json("{}")));
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.USER_PROMPT_SUBMIT, null,
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);

        // 会话未运行 → session function hook 排除（CC appState undefined → :1541 不并入）
        GenericHook.HookResult excluded = registry.executeEvent(
            HookEvent.userPromptSubmit(sessionUuid.toString(), null, "hello"));
        assertThat(excluded.preventContinuation())
            .as("USER_PROMPT_SUBMIT 会话未运行 → session function hook 必须排除（CC appState undefined）")
            .isFalse();
        assertThat(excluded.blockingError())
            .as("USER_PROMPT_SUBMIT 会话未运行 → 无 session function hook 的 blockingError")
            .isNull();

        // 会话运行中（markRunning）→ session function hook 参与并阻断
        //   （CC executeUserPromptSubmitHooks hooks.ts:3830 toolUseContext.getAppState → appState 定义）
        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            GenericHook.HookResult included = registry.executeEvent(
                HookEvent.userPromptSubmit(sessionUuid.toString(), null, "hello"));
            assertThat(included.preventContinuation())
                .as("USER_PROMPT_SUBMIT 会话运行中 → session function hook 必须参与并阻断（CC :1541 并入）")
                .isTrue();
            assertThat(included.blockingError())
                .as("blockingError 来自 USER_PROMPT_SUBMIT session function hook")
                .isNotNull()
                .extracting(HookBlockingError::blockingError)
                .isEqualTo("function-blocked");
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    /**
     * WHY (IMP-E4-06 · E4-XP-W67-01): CC clearConversation（conversation.ts:69）在清空会话时点
     * {@code executeSessionEndHooks('clear', {getAppState...})} —— SessionEnd 事件 reason='clear'
     * → executeHooksOutsideREPL 传 getAppState（hooks.ts:4118）→ appState 定义 → session function
     * hooks 并入（:1541），且 SessionEnd matcher 按 reason 匹配（HookMatcherEngine:333 SESSION_END
     * → dataStr "reason"）。Java {@link ExitReasons#CLEAR} 对齐 CC coreSchemas.ts:748 'clear'。
     * 本测试锁定：reason='clear' 时 matcher='clear' 的 session function hook 经发射链真实执行。
     */
    @Test
    @DisplayName("6f. SESSION_END reason=clear session function hook: 会话运行中执行并阻断（CC conversation.ts:69 executeSessionEndHooks('clear')）")
    void sessionEndClearReason_sessionFunctionHook_participates_whenSessionRunning() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000a9";
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        registry.setCommandHookExecutor(new StubCommandExecutor(j -> exit0Json("{}")));
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.SESSION_END, "clear",
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);

        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            GenericHook.HookResult result = registry.executeEvent(
                HookEvent.sessionEnd(sessionUuid.toString(), null, ExitReasons.CLEAR));
            assertThat(result.preventContinuation())
                .as("SESSION_END reason=clear 会话运行中 → matcher='clear' session function hook 必须参与并阻断（CC executeSessionEndHooks('clear')）")
                .isTrue();
            assertThat(result.blockingError())
                .as("blockingError 来自 matcher='clear' session function hook")
                .isNotNull()
                .extracting(HookBlockingError::blockingError)
                .isEqualTo("function-blocked");
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    /**
     * WHY (IMP-E4-06 · E4-XP-W67-02): E4-XP-W67-02 要求注册<b>session command</b> hook on
     * USER_PROMPT_SUBMIT → doRun 走 UserPromptSubmit 段 → 断言执行。CC getMatchingHooks
     * （hooks.ts:1492-1566）把 session command hooks 并入统一匹配链（:1552-1562 push 进 hooks），
     * executeHooks 执行 —— 工具事件 + UserPromptSubmit 恒传 toolUseContext → appState 定义。
     * Java {@code getMatchingHooks}（HookRegistry:540-542）在 isSessionHookEligible 时经
     * {@code sessionCommandMatched} 并入 → {@code executeConfiguredHooks} 执行。本测试锁定：
     * 会话运行中 USER_PROMPT_SUBMIT 的 session command hook 真实执行（matcher=null 全匹配）。
     */
    @Test
    @DisplayName("6g. USER_PROMPT_SUBMIT session command hook: 会话运行中执行（CC getHooksConfig 并入统一链）")
    void userPromptSubmit_sessionCommandHook_executes_whenSessionRunning() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000aa";
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json("{}"));
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        registry.setCommandHookExecutor(stub);
        registry.addSessionHook(sessionUuid.toString(), HookEventType.USER_PROMPT_SUBMIT, null,
            new CommandHook("echo ups", null, null, null, null, null, null, null), null, null);

        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            registry.executeEvent(HookEvent.userPromptSubmit(sessionUuid.toString(), null, "hello"));
            assertThat(stub.capturedJsonInput.get())
                .as("USER_PROMPT_SUBMIT 会话运行中 → session command hook 必须经 executeConfiguredHooks 执行（CC :1552-1562 并入单链）")
                .isNotNull();
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    /**
     * 断言 CC 无 appState 事件的结果不含 session function hook 阻断（CC hooks.ts:1541 排除语义）。 */
    private void assertSessionHookExcluded(GenericHook.HookResult result, String eventName) {
        assertThat(result.preventContinuation())
            .as(eventName + ": CC 无 appState 发射点, 会话运行中 session function hook 也必须排除")
            .isFalse();
        assertThat(result.blockingError())
            .as(eventName + ": 无 session function hook 的 blockingError")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 7. [IMP-LC-02 / OPD-WF4-LC-02] Notification hook 通用发射点
    //    executeNotificationHooks · 对齐 CC executeNotificationHooks (utils/hooks.ts:3570-3592)
    // ════════════════════════════════════════════════════════════════════════

    /** settings 配 1 条 Notification command hook（matcher=notification_type "background-task"）+ stub executor. */
    private HookRegistry registryWithConfiguredNotificationHook(StubCommandExecutor stub) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.NOTIFICATION,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                "background-task", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(stub);
        return registry;
    }

    /**
     * WHY (OPD-WF4-LC-02 / EV-WF4-LC-004): CC Notification hook 由多源触发 executeNotificationHooks
     * （notifier.ts:25 / print.ts:1366 / elicitationHandler.ts），Java 旧实现仅 ElicitationHandler
     * 一处内联发射 → 非 elicitation 通知（后台任务完成等）配置 Notification hook 永不触发。
     * 本测试锁定新通用发射点 {@link HookRegistry#executeNotificationHooks}：按 notification_type
     * 匹配配置 hook（HookMatcherEngine NOTIFICATION 分支，HookMatcherEngine.java:332）并把
     * message/title/notification_type 载荷送达 hook stdin（对齐 hooks.ts:3579-3585 载荷构造）。
     */
    @Test
    @DisplayName("7a. executeNotificationHooks 按 notification_type 匹配配置 hook 并送达 message/title/notification_type 载荷")
    void executeNotificationHooks_matchesByNotificationType_passesPayload() throws Exception {
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json("{}"));
        HookRegistry registry = registryWithConfiguredNotificationHook(stub);

        registry.executeNotificationHooks("task finished", "Background Task", "background-task");

        assertThat(stub.capturedJsonInput.get())
            .as("匹配 notification_type → 配置 Notification hook 必须被调用（旧实现无通用发射点）")
            .isNotNull();
        JsonNode input = MAPPER.readTree(stub.capturedJsonInput.get());
        assertThat(input.path("hook_event_name").asText())
            .as("hook_event_name 必须为 Notification（hooks.ts:3581）").isEqualTo("Notification");
        assertThat(input.path("notification_type").asText())
            .as("notification_type 必须为调用方传值（hooks.ts:3584）").isEqualTo("background-task");
        assertThat(input.path("message").asText())
            .as("message 载荷必须透传（hooks.ts:3582）").isEqualTo("task finished");
        assertThat(input.path("title").asText())
            .as("title 载荷必须透传（hooks.ts:3583）").isEqualTo("Background Task");
    }

    /**
     * WHY: CC executeNotificationHooks 以 notification_type 作为 matchQuery（hooks.ts:3590），
     * 非匹配 notification_type 的 hook 不执行（executeHooksOutsideREPL :3047-3049 matchingHooks
     * 为空 → 返回空）。本测试锁定通用发射点的匹配收窄语义：不同 notification_type → 零执行。
     */
    @Test
    @DisplayName("7b. executeNotificationHooks 非匹配 notification_type → 零执行（CC matchQuery=notificationType）")
    void executeNotificationHooks_nonMatchingNotificationType_skipsHook() {
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json("{}"));
        HookRegistry registry = registryWithConfiguredNotificationHook(stub);

        registry.executeNotificationHooks("task finished", null, "different-type");

        assertThat(stub.capturedJsonInput.get())
            .as("notification_type 不匹配 → 配置 Notification hook 不得执行（CC :3047-3049）")
            .isNull();
    }

    /**
     * WHY (IMP-LC-02 · 对齐 CC 会话作用域): Notification 事件不在
     * {@link HookRegistry#CC_APP_STATE_PRESENT_EVENTS}（isSessionHookEligible 首条件 false）——
     * CC executeNotificationHooks 不传 getAppState（hooks.ts:3003-3004）→ appState undefined →
     * session hooks 排除（hooks.ts:1541）。本测试锁定：即使会话运行中（markRunning），Notification
     * 的 session function hook 也不得执行（通用发射点不得泄漏会话作用域 hook）。
     */
    @Test
    @DisplayName("7c. executeNotificationHooks 排除 session function hook（CC appState undefined：Notification ∉ CC_APP_STATE_PRESENT_EVENTS）")
    void executeNotificationHooks_sessionFunctionHook_excluded() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000c1";
        StubCommandExecutor stub = new StubCommandExecutor(j -> exit0Json("{}"));
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        registry.setCommandHookExecutor(stub);
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.NOTIFICATION, "background-task",
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "function-blocked", null, null);

        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            GenericHook.HookResult result = registry.executeNotificationHooks("task finished", null, "background-task");
            assertThat(result.preventContinuation())
                .as("Notification 无 appState（CC hooks.ts:1541）→ session function hook 必须排除，不阻断")
                .isFalse();
            assertThat(result.blockingError())
                .as("Notification session function hook 不得执行（对齐 CC executeNotificationHooks 不传 getAppState）")
                .isNull();
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-GP-01 · OPD-WF7-GC-02] registerAttributionHooks · 对齐 CC setup.ts:350-360
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构造 gate-on attribution hooks（COMMIT_ATTRIBUTION 开）· tracker 共享引用供断言.
     *
     * <p>WHY: CC setup.ts:350 仅 feature('COMMIT_ATTRIBUTION') 单门控 → 门控开才注册；
     * 门控关零注册（CC 发布构建宏 false 等价）。
     */
    private static RegisterAttributionHooks attributionHooksGateOn(
            CommitAttributionTracker tracker, Path repoRoot) {
        return new RegisterAttributionHooks(tracker, () -> true, () -> repoRoot);
    }

    /** 记录注册 hook 名的假注册中心 · 实现 SessionFileAccessHooks.PostToolUseRegistrar（复用同构接口）. */
    private static final class AttributionRecordingRegistrar
            implements SessionFileAccessHooks.PostToolUseRegistrar {
        final List<String> registeredNames = new ArrayList<>();
        @Override
        public void registerPostToolUse(String name, PostToolUseHook hook) {
            registeredNames.add(name);
        }
    }

    @Test
    @DisplayName("IMP-GP-01 a. 门控关（COMMIT_ATTRIBUTION=false）→ 零注册（CC setup.ts:350 宏 false 等价）")
    void registerAttributionHooks_gateOff_registersNothing() {
        // WHY: CC registerAttributionHooks 由 feature('COMMIT_ATTRIBUTION') 编译期宏单门控
        //   （setup.ts:350），发布构建 false → 不注册。Java 端 isEnabled() 单门控
        //   （COMMIT_ATTRIBUTION）关 → 零注册（规则九 · 测试验证意图）。
        AttributionRecordingRegistrar registrar = new AttributionRecordingRegistrar();

        // 默认 COMMIT_ATTRIBUTION=false
        new RegisterAttributionHooks().registerAttributionHooks(registrar);
        assertThat(registrar.registeredNames)
            .as("门控关 → 不得注册任何 attribution hooks")
            .isEmpty();
    }

    @Test
    @DisplayName("IMP-GP-01 b. 门控开（COMMIT_ATTRIBUTION=true）→ 注册 Edit/Write 2 个 internal PostToolUse hooks")
    void registerAttributionHooks_gateOn_registersEditWrite() {
        // WHY: commitAttribution.ts:400-401 "Called after Edit/Write tool completes" →
        //   matcher 集合为 Edit/Write；internal callback（hooks.ts:1440-1442 isInternalHook）
        //   → 走 registerPostToolUseInternal 变体。
        AttributionRecordingRegistrar registrar = new AttributionRecordingRegistrar();
        CommitAttributionTracker tracker = new CommitAttributionTracker(() -> java.nio.file.Path.of("."));

        attributionHooksGateOn(tracker, java.nio.file.Path.of("."))
            .registerAttributionHooks(registrar);

        assertThat(registrar.registeredNames)
            .as("COMMIT_ATTRIBUTION 开 → 注册 2 个 Edit/Write attribution hooks")
            .containsExactlyInAnyOrder("attribution:Edit", "attribution:Write");
    }

    @Test
    @DisplayName("IMP-GP-01 c. 端到端：gate-on 经 HookRegistry.executePostToolUse 追踪 Edit 文件修改（E3）")
    void registerAttributionHooks_endToEnd_tracksFileModification(@TempDir Path repoRoot) throws Exception {
        // WHY: setup.ts:355-360 注册的 PostToolUse hooks 在工具执行后把文件贡献计入
        //   appState.attribution.fileStates（commitAttribution.ts:402-433）—— Java 等价为
        //   CommitAttributionTracker.fileStates。首见文件（oldContent 未知 → 空）按全量计入
        //   （commitAttribution.ts:338-341 oldContent==='' → newContent.length）。
        Files.writeString(repoRoot.resolve("foo.txt"), "hello world");

        CommitAttributionTracker tracker = new CommitAttributionTracker(() -> repoRoot);
        HookRegistry registry = new HookRegistry();
        registry.registerAttributionHooks(attributionHooksGateOn(tracker, repoRoot));

        JsonNode input = MAPPER.readTree("{\"file_path\":\"foo.txt\"}");
        GenericHook.HookResult outcome = registry.executePostToolUse(
            ToolNameConstants.FILE_EDIT_TOOL_NAME, input,
            ToolResult.success("tu-1", "ok"),
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(outcome.preventContinuation())
            .as("attribution hook 是 internal 旁路（CC return {}，hooks.ts:2038）→ 不阻断工具链")
            .isFalse();
        Map<String, CommitAttributionTracker.FileState> states = tracker.snapshotFileStates();
        assertThat(states)
            .as("Edit/Write PostToolUse 后 fileStates 必须记录目标文件")
            .containsKey("foo.txt");
        assertThat(states.get("foo.txt").claudeContribution())
            .as("首见文件按全量计入（commitAttribution.ts:338-341）")
            .isEqualTo("hello world".length());
    }

    @Test
    @DisplayName("IMP-GP-01 d. 端到端：门控关经 HookRegistry 零追踪（hook 未注册 → fileStates 空）")
    void registerAttributionHooks_endToEnd_gateOff_noTracking(@TempDir Path repoRoot) throws Exception {
        // WHY: 门控关 → registerAttributionHooks 零注册 → Edit/Write 后不追踪。断言 tracker
        //   保持空 = hook 未被接线（RED→GREEN 反证，防假接线/双轨）。
        Files.writeString(repoRoot.resolve("foo.txt"), "hello world");

        CommitAttributionTracker tracker = new CommitAttributionTracker(() -> repoRoot);
        HookRegistry registry = new HookRegistry();
        // 默认 COMMIT_ATTRIBUTION=false → gate off
        registry.registerAttributionHooks(new RegisterAttributionHooks(
            tracker, () -> false, () -> repoRoot));

        JsonNode input = MAPPER.readTree("{\"file_path\":\"foo.txt\"}");
        registry.executePostToolUse(
            ToolNameConstants.FILE_EDIT_TOOL_NAME, input,
            ToolResult.success("tu-1", "ok"),
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)));

        assertThat(tracker.snapshotFileStates())
            .as("门控关 → attribution hooks 未注册 → 零追踪")
            .isEmpty();
    }

    @Test
    @DisplayName("IMP-GP-01 e. 追踪器跨次修改累加 claudeContribution（commitAttribution.ts:369 既有贡献累加）")
    void commitAttributionTracker_accumulatesAcrossEdits(@TempDir Path repoRoot) throws Exception {
        // WHY: CC computeFileModificationState 把本次贡献 + 既有 claudeContribution 累加
        //   （commitAttribution.ts:369-374）—— 多轮 Edit 累计每文件 Claude 贡献，PR attribution
        //   百分比据此计算。首轮 "abc"→"abcdef" 贡献 3，次轮 "abcdef"→"abcdefgh" 贡献 2。
        CommitAttributionTracker tracker = new CommitAttributionTracker(() -> repoRoot);

        // 首轮 Edit：磁盘 post-edit 内容 "abcdef"，缓存首见 → oldContent="" → 贡献 6
        Files.writeString(repoRoot.resolve("a.txt"), "abcdef");
        tracker.updateCachedContent("a.txt", "abcdef");
        tracker.trackFileModification("a.txt", "", "abcdef", 1L);
        // 次轮 Edit：post-edit "abcdefgh"，oldContent="abcdef" → prefix/suffix diff 贡献 2
        Files.writeString(repoRoot.resolve("a.txt"), "abcdefgh");
        tracker.trackFileModification("a.txt", "abcdef", "abcdefgh", 2L);

        CommitAttributionTracker.FileState state = tracker.snapshotFileStates().get("a.txt");
        assertThat(state).isNotNull();
        assertThat(state.claudeContribution())
            .as("跨次修改贡献累加（CC commitAttribution.ts:373 累计 claudeContribution）")
            .isEqualTo(8L);
        assertThat(state.contentHash())
            .as("contentHash = SHA-256(newContent)（CC commitAttribution.ts:244-246）")
            .isEqualTo(CommitAttributionTracker.sha256("abcdefgh"));
    }

    @Test
    @DisplayName("IMP-GP-01 f. 追踪器 computeFileModificationContribution 精确 diff（同长替换 / 全量 / 空内容）")
    void commitAttributionTracker_computeContribution() {
        // WHY: CC computeFileModificationState :342-364 common prefix/suffix diff ——
        //   同长替换（"Esc"→"esc"）common suffix "sc"=2 → 变更区仅首字符 E→e=1 字符贡献；
        //   Math.abs(newLen-oldLen)=0 会漏报（CC :344-346 注释用例，非贡献 3）；空 old/new 按内容
        //   长度（:338-341）。测试锁定算法与 CC 逐行一致（规则九：验证行为 WHY）。
        assertThat(CommitAttributionTracker.computeFileModificationContribution("Esc", "esc"))
            .as("同长替换须精确 diff（CC :344-346：suffix sc=2 → 变更区 1 字符）")
            .isEqualTo(1L);
        assertThat(CommitAttributionTracker.computeFileModificationContribution("", "hello"))
            .as("newContent 空 old → 内容长度（CC :338-341）")
            .isEqualTo(5L);
        assertThat(CommitAttributionTracker.computeFileModificationContribution("hello", ""))
            .as("oldContent 空 new → 旧内容长度（CC :338-341 全删）")
            .isEqualTo(5L);
        assertThat(CommitAttributionTracker.computeFileModificationContribution("abcdef", "abXYZef"))
            .as("prefix/suffix diff：中段替换 3 字符")
            .isEqualTo(3L);
    }

    @Test
    @DisplayName("IMP-GP-01 g. 追踪器 trackFileCreation/trackFileDeletion（commitAttribution.ts:439-480）")
    void commitAttributionTracker_trackCreationDeletion(@TempDir Path repoRoot) {
        // WHY: CC trackFileCreation = 空→内容全量（:446）；trackFileDeletion = 已删字符计入
        //   （:453-480）+ contentHash 置空 —— 非 Edit/Write 机制（bash rm/创建）的补偿路径。
        CommitAttributionTracker tracker = new CommitAttributionTracker(() -> repoRoot);

        tracker.trackFileCreation("b.txt", "created content", 1L);
        assertThat(tracker.snapshotFileStates().get("b.txt").claudeContribution())
            .as("创建 = 从空到内容全量（commitAttribution.ts:446）")
            .isEqualTo(15L);

        tracker.trackFileDeletion("b.txt", "created content");
        assertThat(tracker.snapshotFileStates().get("b.txt").claudeContribution())
            .as("删除字符数计入既有贡献（commitAttribution.ts:463-467）")
            .isEqualTo(30L);
        assertThat(tracker.snapshotFileStates().get("b.txt").contentHash())
            .as("删除文件 contentHash 置空（commitAttribution.ts:464）")
            .isEmpty();
    }

    @Test
    @DisplayName("IMP-GP-01 h. clearAttributionCaches / sweepFileContentCache 清空内容缓存（clear/caches.ts:106 · postCompactCleanup.ts:73）")
    void commitAttributionTracker_clearSweepContentCache(@TempDir Path repoRoot) {
        // WHY: attributionHooks 模块导出 clearAttributionCaches / sweepFileContentCache ——
        //   clear 命令 / 压缩后清扫文件内容缓存，防止长会话内容堆积（接口在 clear/caches.ts:106
        //   + postCompactCleanup.ts:73 可观测）。缓存清空后 cachedContent 返回 null（首见语义）。
        CommitAttributionTracker tracker = new CommitAttributionTracker(() -> repoRoot);
        tracker.updateCachedContent("a.txt", "content");
        assertThat(tracker.cachedContent("a.txt")).isEqualTo("content");

        tracker.clearAttributionCaches();
        assertThat(tracker.cachedContent("a.txt"))
            .as("clearAttributionCaches 清空内容缓存（clear/caches.ts:106）")
            .isNull();

        tracker.updateCachedContent("a.txt", "content2");
        tracker.sweepFileContentCache();
        assertThat(tracker.cachedContent("a.txt"))
            .as("sweepFileContentCache 压缩后清扫（postCompactCleanup.ts:73）")
            .isNull();
    }

    @Test
    @DisplayName("IMP-GP-01 i. HookRegistry.registerAttributionHooks null 注入 → 跳过（warn，不 NPE）")
    void registerAttributionHooks_nullInjection_skips() {
        // WHY: 注册入口 null 安全（HookRegistry.registerAttributionHooks）—— 注入缺失时跳过
        //   而非 NPE（SessionFileAccessHooks 同模式 warn 兜底）。
        HookRegistry registry = new HookRegistry();
        registry.registerAttributionHooks(null); // 不抛异常即通过
    }
}
