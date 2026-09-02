package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H3] HookCommand sealed 4 类型 + 配置层补全 · 对齐 CC
 * {@code Open-ClaudeCode/src/schemas/hooks.ts:176-189} (HookCommandSchema discriminatedUnion) +
 * {@code utils/hooks/hooksConfigSnapshot.ts:62-133} (5 方法).
 *
 * <p>WHY (规则九): 本测试覆盖 3 条核心行为:
 * <ol>
 *   <li>Jackson 多态反序列化按 type 字段路由到 4 子类 record (对齐 CC discriminatedUnion)</li>
 *   <li>getAllHooks 合并多 source + allowManagedHooksOnly 时返回空 (对齐 hooksSettings.ts:96-101)</li>
 *   <li>shouldDisableAll 读 policySettings.disableAllHooks (不再永远 false) +
 *       HooksConfigSnapshot.shouldDisableAllHooksIncludingManaged (对齐 hooksConfigSnapshot.ts:83-88)</li>
 * </ol>
 *
 * <p>[IMPL-08 DEL-CFG-02..05] isHookEqual/getHookDisplayText/getHooksForEvent/sortMatchersByPriority
 * 已从 HooksSettings 删除（生产 0 消费者，EV-CFG-020）；对应测试 2/3 与测试 4 的
 * getHooksForEvent 断言随删。isHookEqual 语义由 SessionHookStore 私有实现承担
 * （SessionHookStoreTest 覆盖）。
 *
 * @since Session H3 (P1)
 */
@DisplayName("[H3] HookCommand sealed 4 类型 + 配置层补全对齐 CC")
class R33H3_HookCommandSealedTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("1. Jackson 多态反序列化: type 字段路由到 4 子类 record (CC schemas/hooks.ts:176-189)")
    void jacksonPolymorphicDispatch_routesByTypeField() throws Exception {
        // 4 个 JSON, 各带 type 字段
        String commandJson = """
            {"type":"command","command":"echo hi","if":"Bash(git *)","shell":"bash","timeout":30,
             "statusMessage":"running","once":true,"async":false,"asyncRewake":false}""";
        String promptJson = """
            {"type":"prompt","prompt":"check $ARGUMENTS","if":null,"timeout":10,
             "model":"haiku","statusMessage":"eval","once":false}""";
        String httpJson = """
            {"type":"http","url":"https://example.com/hook","if":null,"timeout":5,
             "headers":{"Authorization":"Bearer $TOK"},"allowedEnvVars":["TOK"],
             "statusMessage":"posting","once":false}""";
        String agentJson = """
            {"type":"agent","prompt":"verify tests passed","if":null,"timeout":60,
             "model":null,"statusMessage":"verifying","once":true}""";

        HookCommand c = mapper.readValue(commandJson, HookCommand.class);
        HookCommand p = mapper.readValue(promptJson, HookCommand.class);
        HookCommand h = mapper.readValue(httpJson, HookCommand.class);
        HookCommand a = mapper.readValue(agentJson, HookCommand.class);

        assertThat(c).isInstanceOf(CommandHook.class);
        assertThat(c.hookType()).isEqualTo(HookCommand.HookType.COMMAND);
        assertThat(((CommandHook) c).command()).isEqualTo("echo hi");
        assertThat(((CommandHook) c).ifCondition()).isEqualTo("Bash(git *)");
        assertThat(((CommandHook) c).asyncFlag()).isFalse();  // async → asyncFlag

        assertThat(p).isInstanceOf(PromptHook.class);
        assertThat(p.hookType()).isEqualTo(HookCommand.HookType.PROMPT);
        assertThat(((PromptHook) p).prompt()).isEqualTo("check $ARGUMENTS");

        assertThat(h).isInstanceOf(HttpHook.class);
        assertThat(h.hookType()).isEqualTo(HookCommand.HookType.HTTP);
        assertThat(((HttpHook) h).url()).isEqualTo("https://example.com/hook");
        assertThat(((HttpHook) h).headers()).containsEntry("Authorization", "Bearer $TOK");
        assertThat(((HttpHook) h).allowedEnvVars()).containsExactly("TOK");

        assertThat(a).isInstanceOf(AgentHook.class);
        assertThat(a.hookType()).isEqualTo(HookCommand.HookType.AGENT);
        assertThat(((AgentHook) a).prompt()).isEqualTo("verify tests passed");

        // 序列化回写应包含 type 字段
        String reserialized = mapper.writeValueAsString(c);
        assertThat(reserialized).contains("\"type\":\"command\"");
    }


    @Test
    @DisplayName("4. getAllHooks 合并多 source + allowManagedHooksOnly 时返回空 (CC hooksSettings.ts:92-161)")
    void getAllHooks_mergesSourcesAndRespectsAllowManagedHooksOnly() {
        // 场景 A: 无 policy 限制, 合并 user + project
        // [IMP-HOOKS-S1 DEL-CFG-A] SESSION_HOOK 写入与断言已删除 —— bySource[SESSION_HOOK]
        // 全仓主代码 0 写入方（死读分支）; session hook 执行走 SessionHookStore 独立链
        // （HookAggregationLastWinsTest:284 registry.addSessionHook 佐证）。
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo u", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        settings.loadFromSource(HookSource.PROJECT_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.POST_TOOL_USE,
                new PromptHook("check p", null, null, null, null, null),
                "Write", HookSource.PROJECT_SETTINGS, null)
        ));

        List<IndividualHookConfig> all = settings.getAllHooks();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(IndividualHookConfig::source)
            .containsExactlyInAnyOrder(HookSource.USER_SETTINGS, HookSource.PROJECT_SETTINGS);

        // [IMPL-08 DEL-CFG-04] getHooksForEvent 已删除 (0 消费者) — 过滤断言随删

        // 场景 B: allowManagedHooksOnly=true → 返回空
        HooksSettings restricted = new HooksSettings(key ->
            "allowManagedHooksOnly".equals(key) ? Boolean.TRUE : null);
        restricted.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo u", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        assertThat(restricted.getAllHooks()).isEmpty();

        // 场景 C [IMP-HOOKS-S1 / DIF-CFG-04]: CC 守卫为单条件 (hooksSettings.ts:96-101
        // policySettings.allowManagedHooksOnly===true) —— merged.disableAllHooks=true 且
        // policy.disableAllHooks=false 时 getAllHooks 非空（旧双条件守卫应返回空 → RED）。
        // 生产等价链: getAllHooks 唯一生产调用方 HooksConfigSnapshot 分支 5 (:207) 仅在
        // policyOnly=false 时可达, shouldAllowManagedHooksOnly() 双条件已被快照分支 2/3/4
        // 前置拦截（hooksConfigSnapshot.ts:47-49 分支 4 merged.disableAllHooks===true →
        // 仅 policy hooks）→ 生产可观察行为不变。
        HooksSettings mergedDisabled = new HooksSettings(key -> null);
        mergedDisabled.setDisableAllHooksMerged(true);
        mergedDisabled.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo u", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        assertThat(mergedDisabled.getAllHooks())
            .as("单条件守卫: merged.disableAllHooks=true 且 policy 未禁 → 仍展示 hook (CC 单条件)")
            .hasSize(1);
    }

    @Test
    @DisplayName("5. shouldDisableAll 读 policySettings.disableAllHooks (不再永远 false) + Snapshot.shouldDisableAllHooksIncludingManaged (CC hooksConfigSnapshot.ts:83-88)")
    void shouldDisableAll_readsPolicySettingsNotAlwaysFalse() {
        // 无 policy → false
        HooksSettings noPolicy = new HooksSettings(key -> null);
        assertThat(noPolicy.shouldDisableAll()).isFalse();

        // policy disableAllHooks=true → true (原实现永远 false, 此处验证已修复)
        HooksSettings withPolicy = new HooksSettings(key ->
            "disableAllHooks".equals(key) ? Boolean.TRUE : null);
        assertThat(withPolicy.shouldDisableAll()).isTrue();

        // HooksConfigSnapshot.shouldDisableAllHooksIncludingManaged 透传
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(withPolicy);
        assertThat(snapshot.shouldDisableAllHooksIncludingManaged()).isTrue();

        // 无 policy 的 snapshot → false
        HooksConfigSnapshot noPolicySnapshot = new HooksConfigSnapshot(noPolicy);
        assertThat(noPolicySnapshot.shouldDisableAllHooksIncludingManaged()).isFalse();

        // 快照 capture/get/reset 流程
        noPolicySnapshot.captureHooksConfigSnapshot();
        Map<HookEventType, List<HookMatcher>> cfg = noPolicySnapshot.getHooksConfigFromSnapshot();
        assertThat(cfg).isNotNull();

        noPolicySnapshot.resetHooksConfigSnapshot();
        // reset 后 get 会重新 capture (非 null)
        assertThat(noPolicySnapshot.getHooksConfigFromSnapshot()).isNotNull();

        // updateHooksConfigSnapshot 重新捕获
        noPolicySnapshot.updateHooksConfigSnapshot();
        assertThat(noPolicySnapshot.getHooksConfigFromSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("6. [5-W1-2] 恢复公开的 getHookDisplayText / isHookEqual (CC hooksSettings.ts:33-90, DEL-CFG-02 撤销)")
    void restoredPublicApi_isHookEqual_and_getHookDisplayText() {
        // ── isHookEqual (CC hooksSettings.ts:33-65) ──
        // command: shell 缺省 'bash' (CC L48-52) → shell=null 与 shell="bash" 相等
        assertThat(HooksSettings.isHookEqual(
            new CommandHook("echo hi", "Bash(git *)", null, null, null, null, null, null),
            new CommandHook("echo hi", "Bash(git *)", "bash", null, null, null, null, null)))
            .as("shell null → 缺省 bash === 'bash' (CC L48-52)")
            .isTrue();
        // if 是身份一部分 (CC L41-43): 同 command 不同 if 是不同 hook
        assertThat(HooksSettings.isHookEqual(
            new CommandHook("echo hi", "Bash(git *)", null, null, null, null, null, null),
            new CommandHook("echo hi", "Bash(npm *)", null, null, null, null, null, null)))
            .as("同 command 不同 if 是不同 hook (CC L41-43)")
            .isFalse();
        // type 不同 → false (CC L37)
        assertThat(HooksSettings.isHookEqual(
            new CommandHook("echo hi", null, null, null, null, null, null, null),
            new PromptHook("echo hi", null, null, null, null, null)))
            .as("type 不同 → false (CC L37)")
            .isFalse();
        // function hook 无稳定标识 → 永远不等 (CC L61-63)
        assertThat(HooksSettings.isHookEqual(
            new FunctionHook("f1", 5000, (m, s) -> CompletableFuture.completedFuture(true), "err", null),
            new FunctionHook("f2", 5000, (m, s) -> CompletableFuture.completedFuture(true), "err", null)))
            .as("function hook 永远不等 (CC L61-63)")
            .isFalse();

        // ── getHookDisplayText (CC hooksSettings.ts:68-90) ──
        assertThat(HooksSettings.getHookDisplayText(
            new CommandHook("echo hi", null, null, null, "running", null, null, null)))
            .as("statusMessage 优先 (CC L72-74)")
            .isEqualTo("running");
        assertThat(HooksSettings.getHookDisplayText(
            new CommandHook("echo hi", null, null, null, null, null, null, null)))
            .as("command → command 串 (CC L77-78)")
            .isEqualTo("echo hi");
        assertThat(HooksSettings.getHookDisplayText(
            new PromptHook("check p", null, null, null, null, null)))
            .as("prompt → prompt 串 (CC L79-80)")
            .isEqualTo("check p");
        assertThat(HooksSettings.getHookDisplayText(
            new AgentHook("verify tests", null, null, null, null, null)))
            .as("agent → prompt 串 (CC L81-82)")
            .isEqualTo("verify tests");
        assertThat(HooksSettings.getHookDisplayText(
            new HttpHook("https://example.com/hook", null, null, null, null, null, null)))
            .as("http → url (CC L83-84)")
            .isEqualTo("https://example.com/hook");
        assertThat(HooksSettings.getHookDisplayText(
            new FunctionHook("f1", 5000, (m, s) -> CompletableFuture.completedFuture(true), "err", null)))
            .as("function → 'function' (CC L87-88)")
            .isEqualTo("function");
    }
}