package com.nexusai.apis.hook;

import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HookSource;
import com.nexusai.application.agent.permission.hook.HooksSettings;
import com.nexusai.application.agent.permission.hook.HttpHook;
import com.nexusai.application.agent.permission.hook.IndividualHookConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HookController} GET /api/v1/hooks 端点测试（plain JUnit，mock HooksSettings，
 * MockMvcBuilders.standaloneSetup 风格，对齐 CommandControllerBuiltInCommandsTest）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）:
 * <ol>
 *   <li><b>[批 3a] sessionId 必填</b>——缺 / blank ⇒ <b>400</b>（不再回落 MDC，也不再退化为
 *       settings-only）；?sessionId=sess-xxx → settings+session 合并（{@code getAllHooks(sessionId)}，
 *       决策 4-3『运行时会话』）。旧契约（缺省 → settings-only / MDC 兜底）已删除。</li>
 *   <li><b>返回 shape 对齐前端 HookItem</b>（types.ts:855-865）——event/config.type/config.command|url/source
 *       字段齐全、null 子类型字段省略（前端 TS 可解析）。</li>
 *   <li><b>[欠账清理批] 原「残留别的会话 id 不被读取」的反向实验用例已删（去重）</b>——其断言与
 *       {@code noParam_is400} 逐条重合，且该风险在批 3c 删除裸 MDC 会话槽后已结构性不可能
 *       （无载体可携带残留值）。</li>
 * </ol>
 */
class HookControllerTest {

    private HookController controller;
    private HooksSettings hooksSettings;
    private HookRegistry hookRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new HookController();
        hooksSettings = mock(HooksSettings.class);
        hookRegistry = mock(HookRegistry.class);
        ReflectionTestUtils.setField(controller, "hooksSettings", hooksSettings);
        ReflectionTestUtils.setField(controller, "hookRegistry", hookRegistry);
        // 默认无插件 hook（现有用例 focus settings 合并；插件合并有专门用例覆盖）
        when(hookRegistry.getRegisteredPluginHookConfigs()).thenReturn(List.of());
        // setControllerAdvice：GlobalExceptionHandler 把 ValidationException → 400（缺 sessionId 断言状态码必需）
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.nexusai.infra.exception.GlobalExceptionHandler())
            .build();
    }

    private static IndividualHookConfig sampleCommandHook() {
        return new IndividualHookConfig(
            HookEventType.SESSION_START,
            new CommandHook("echo hi", null, null, null, null, null, null, null),
            "Write", HookSource.USER_SETTINGS, null);
    }

    @Test
    @DisplayName("[批 3a] GET /api/v1/hooks 无 sessionId → 400（不再退化为 settings-only）")
    void noParam_is400() throws Exception {
        mockMvc.perform(get("/api/v1/hooks"))
            .andExpect(status().isBadRequest());

        // fail loud：不解析、不查库、不静默降级
        verify(hooksSettings, never()).getAllHooks();
        verify(hooksSettings, never()).getAllHooks(anyString());
    }

    @Test
    @DisplayName("[批 3a] GET /api/v1/hooks?sessionId=blank → 400（blank 与缺省同判）")
    void blankSessionId_is400() throws Exception {
        mockMvc.perform(get("/api/v1/hooks").param("sessionId", ""))
            .andExpect(status().isBadRequest());

        verify(hooksSettings, never()).getAllHooks();
        verify(hooksSettings, never()).getAllHooks(anyString());
    }

    @Test
    @DisplayName("GET /api/v1/hooks?sessionId=sess-xxx → settings+session 合并（verify getAllHooks('sess-xxx')，决策 4-3）")
    void sessionIdParam_usesSessionMerged() throws Exception {
        when(hooksSettings.getAllHooks("sess-xxx")).thenReturn(List.of(sampleCommandHook()));

        mockMvc.perform(get("/api/v1/hooks").param("sessionId", "sess-xxx"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        verify(hooksSettings).getAllHooks("sess-xxx");
        verify(hooksSettings, never()).getAllHooks();
    }

    // [欠账清理批 · 去重记录] 原 `mdcSessionId_isIgnored_is400` 已删：它断言「GET /hooks 无
    //   sessionId ⇒ 400 + verify(never()).getAllHooks()/getAllHooks(anyString())」——
    //   与上面 `noParam_is400`（同一 400 + 同两条 verify）逐条重合，另有 `verify(never())
    //   .getAllHooks("sess-mdc")` 命中一个**代码不可能产出**的常量（纯否定断言恒绿）。
    //   它守护的「残留（别的会话）sessionId 被采用」在批 3c 删除裸 MDC 会话槽后已结构性不可能。

    @Test
    @DisplayName("返回 shape 对齐前端 HookItem（types.ts:855-865）：config.type/command|url 齐全、null 子类型字段省略")
    void returnShape_matchesFrontendHookItem() throws Exception {
        when(hooksSettings.getAllHooks("sess-xxx")).thenReturn(List.of(
            sampleCommandHook(),
            new IndividualHookConfig(
                HookEventType.PRE_TOOL_USE,
                new HttpHook("https://x/h", null, null, null, null, "fetching", null),
                "Read", HookSource.LOCAL_SETTINGS, null)));

        mockMvc.perform(get("/api/v1/hooks").param("sessionId", "sess-xxx"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // CommandHook → config.type='command' + command
            .andExpect(jsonPath("$[0].config.type").value("command"))
            .andExpect(jsonPath("$[0].config.command").value("echo hi"))
            // HttpHook → config.type='http' + url + statusMessage；command/prompt 省略（NON_NULL）
            .andExpect(jsonPath("$[1].config.type").value("http"))
            .andExpect(jsonPath("$[1].config.url").value("https://x/h"))
            .andExpect(jsonPath("$[1].config.statusMessage").value("fetching"))
            .andExpect(jsonPath("$[1].config.command").doesNotExist())
            .andExpect(jsonPath("$[1].config.prompt").doesNotExist());
    }

    // ── [hooks-plugin-display] 插件 hook 合并：settings + HookRegistry registered 插件通道 ──

    /**
     * 插件 hook 样例 · 对齐 CC PluginHookMatcher（loadPluginHooks.ts:74-81）经
     * {@code registerRegisteredHookMatcher} 转成的 IndividualHookConfig（source=PLUGIN_HOOK +
     * pluginName，hooksConfigManager.ts:335-345）。
     */
    private static IndividualHookConfig samplePluginHook() {
        return new IndividualHookConfig(
            HookEventType.SESSION_START,
            new CommandHook("node ${CLAUDE_PLUGIN_ROOT}/hooks/session-start.cjs", null, null, 10, "Bootstrapping zjkycode capabilities...", null, null, null),
            null, HookSource.PLUGIN_HOOK, "zjkycode@zjkycode");
    }

    @Test
    @DisplayName("sessionId 分支 → settings + 插件 hook 合并（source=PLUGIN_HOOK + pluginName 透传，前端 HookPanel 渲染）")
    void sessionId_mergesPluginHooks() throws Exception {
        when(hooksSettings.getAllHooks("sess-xxx")).thenReturn(List.of(sampleCommandHook()));
        when(hookRegistry.getRegisteredPluginHookConfigs()).thenReturn(List.of(samplePluginHook()));

        mockMvc.perform(get("/api/v1/hooks").param("sessionId", "sess-xxx"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // settings hook 在前
            .andExpect(jsonPath("$[0].source").value("USER_SETTINGS"))
            // 插件 hook：source=PLUGIN_HOOK + pluginName + 完整 config
            .andExpect(jsonPath("$[1].event").value("SESSION_START"))
            .andExpect(jsonPath("$[1].config.type").value("command"))
            .andExpect(jsonPath("$[1].config.command").value("node ${CLAUDE_PLUGIN_ROOT}/hooks/session-start.cjs"))
            .andExpect(jsonPath("$[1].config.statusMessage").value("Bootstrapping zjkycode capabilities..."))
            .andExpect(jsonPath("$[1].source").value("PLUGIN_HOOK"))
            .andExpect(jsonPath("$[1].pluginName").value("zjkycode@zjkycode"));

        verify(hooksSettings).getAllHooks("sess-xxx");
        verify(hookRegistry).getRegisteredPluginHookConfigs();
    }

    @Test
    @DisplayName("sessionId 分支同样合并插件 hook（插件 registered 通道与 sessionId 无关，CC 全局注册表语义）")
    void sessionIdParam_mergesPluginHooks() throws Exception {
        when(hooksSettings.getAllHooks("sess-xxx")).thenReturn(List.of(sampleCommandHook()));
        when(hookRegistry.getRegisteredPluginHookConfigs()).thenReturn(List.of(samplePluginHook()));

        mockMvc.perform(get("/api/v1/hooks").param("sessionId", "sess-xxx"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].source").value("PLUGIN_HOOK"))
            .andExpect(jsonPath("$[1].pluginName").value("zjkycode@zjkycode"));

        verify(hooksSettings).getAllHooks("sess-xxx");
        verify(hookRegistry).getRegisteredPluginHookConfigs();
    }

    @Test
    @DisplayName("hookRegistry 为 null（非 Spring 直构）→ 仅 settings，不 NPE（null-safe 守卫）")
    void nullHookRegistry_skipsPluginMerge() throws Exception {
        // 覆盖 setUp 注入：模拟未注入 HookRegistry（生产 @Autowired 恒注入，直构测试防御）
        ReflectionTestUtils.setField(controller, "hookRegistry", null);
        when(hooksSettings.getAllHooks("sess-xxx")).thenReturn(List.of(sampleCommandHook()));

        mockMvc.perform(get("/api/v1/hooks").param("sessionId", "sess-xxx"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].source").value("USER_SETTINGS"));
    }
}
