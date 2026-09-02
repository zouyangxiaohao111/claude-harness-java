package com.nexusai.application.agent.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PermissionRequestResult;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.eventbus.ws.BridgePermissionRequestEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * [Session S16] 更新三态闭环集成测试（apply → persist → updatedPermissions 事件推送）。
 *
 * <p>对齐 CC 真源：
 * <ul>
 *   <li>{@code handleUserAllow} / {@code persistPermissions}（PermissionContext.ts:139-147/291-318）</li>
 *   <li>bridge onResponse（interactiveHandler.ts:266-280）</li>
 *   <li>hook allow（PermissionContext.ts:233-239/319-336 handleHookAllow）</li>
 *   <li>applyPermissionUpdates（PermissionUpdate.ts:196-206）</li>
 * </ul>
 *
 * <p>验证路径全部使用<b>真实</b> Applier / Persister + 临时目录 settings.json
 * （非 mock），断言磁盘文件与 reload 结果 —— "Allow forever" 下一轮真实生效的 E3 证据。
 */
@DisplayName("[S16] 更新三态闭环：建议批准 → apply → persist → updatedPermissions 推送")
class UpdateTriStateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    /** 桩工具（对齐 WebSocketPermissionPrompterPromptDetailsTest.StubTool 模式）。 */
    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return name + " description"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    /** CC 形状的 addRules 建议 JSON（PermissionUpdateSchema 判别联合）。 */
    private static ObjectNode addRulesJson(String destination, String toolName, String ruleContent) {
        ObjectNode update = JSON.createObjectNode();
        update.put("type", "addRules");
        update.put("destination", destination);
        update.put("behavior", "allow");
        ObjectNode rule = JSON.createObjectNode();
        rule.put("toolName", toolName);
        rule.put("ruleContent", ruleContent);
        update.set("rules", JSON.createArrayNode().add(rule));
        return update;
    }

    /** 构造 ctx（携带 permCtx + 可捕获的 getAppState/setAppState）。 */
    private static ToolUseContext newCtx(ToolPermissionContext permCtx,
                                         AtomicReference<Map<String, Object>> appStateRef) {
        Function<Map<String, Object>, Map<String, Object>> getAppState = prev -> {
            Map<String, Object> snap = new LinkedHashMap<>(appStateRef.get());
            return snap;
        };
        Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState = updater -> {
            Map<String, Object> next = updater.apply(appStateRef.get());
            appStateRef.set(next);
        };
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT, List.of(), "",
            AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, null, null,
            getAppState, setAppState, null, null);
    }

    /** 真实管线装配 · persister 仅触碰 projectSettings（tempDir），不污染 user.home。 */
    private static Object[] wireRealPipeline(Path projectHome) {
        SettingsJsonParser parser = new SettingsJsonParser(JSON, new PermissionRuleValueParser());
        UserSettingsLoader userLoader = new UserSettingsLoader(parser);
        ProjectSettingsLoader projectLoader = new ProjectSettingsLoader(parser, () -> projectHome.toString());
        LocalSettingsLoader localLoader = new LocalSettingsLoader(parser, () -> projectHome.toString());
        PermissionUpdateApplier applier = new PermissionUpdateApplier();
        PermissionUpdatePersister persister = new PermissionUpdatePersister(userLoader, projectLoader, localLoader, new PermissionRuleValueParser());
        return new Object[] { applier, persister, projectLoader };
    }

    private static WebSocketPermissionPrompter newPrompter(PermissionUpdateApplier applier,
                                                           PermissionUpdatePersister persister) {
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(
            mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireUpdatePipelineForTesting(applier, persister);
        return prompter;
    }

    private static List<JsonNode> asNodes(ObjectNode... updates) {
        return List.of(updates);
    }

    private static PermissionRule rule(PermissionRuleSource source, String toolName, String content) {
        return new PermissionRule(source, PermissionBehavior.ALLOW,
            new PermissionRuleValue(toolName, content));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. 本地用户批准（onResponse 5 参）→ apply → persist → 下一轮生效
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("用户批准建议(updatedPermissions) → apply+persist → 磁盘含规则 → reload 生效")
    void localAllow_appliesAndPersists(@TempDir Path projectHome) throws Exception {
        Object[] pipeline = wireRealPipeline(projectHome);
        PermissionUpdateApplier applier = (PermissionUpdateApplier) pipeline[0];
        PermissionUpdatePersister persister = (PermissionUpdatePersister) pipeline[1];
        ProjectSettingsLoader projectLoader = (ProjectSettingsLoader) pipeline[2];
        WebSocketPermissionPrompter prompter = newPrompter(applier, persister);

        AtomicReference<Map<String, Object>> appState = new AtomicReference<>(new LinkedHashMap<>());
        ToolPermissionContext permCtx = ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(), Map.of(), Map.of(), Map.of());
        ToolUseContext ctx = newCtx(permCtx, appState);
        String requestId = "tri-allow-1";

        AtomicReference<PermissionResult> promptResult = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                promptResult.set(prompter.prompt(new StubTool("Bash"),
                    JSON.createObjectNode().put("command", "git status"),
                    new PermissionDecisionReason.Other("test"), ctx, requestId,
                    PermissionPromptDetails.none()));
            } catch (Throwable th) {
                // 预期被 onResponse 完成
            }
        });
        t.start();
        Thread.sleep(200);

        // 前端回传用户批准的 addRules 建议（CC onAllow 第 2 参 permissionUpdates）
        prompter.onResponse(requestId, "allow",
            asNodes(addRulesJson("projectSettings", "Bash", "git status")), null, null);
        t.join(3_000);

        // 1) future 完成且为 Allow
        assertThat(promptResult.get()).isInstanceOf(PermissionResult.Allow.class);

        // 2) persist：projectSettings 文件已含规则（合并写回，仅 allow 桶）
        Path settingsFile = projectHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        assertThat(settingsFile).exists();
        JsonNode root = JSON.readTree(settingsFile.toFile());
        JsonNode allowBucket = root.path("permissions").path("allow");
        assertThat(allowBucket.toString())
            .as("persist 后 settings.json allow 桶必须含 'Bash(git status)'（CC addPermissionRulesToSettings 合并语义）")
            .contains("Bash(git status)");

        // 3) appState.toolPermissionContext 已同步（CC setToolPermissionContext）
        Object appStateCtx = appState.get().get("toolPermissionContext");
        assertThat(appStateCtx).isInstanceOf(ToolPermissionContext.class);
        ToolPermissionContext applied = (ToolPermissionContext) appStateCtx;
        assertThat(applied.alwaysAllowRules().get(PermissionRuleSource.PROJECT_SETTINGS))
            .as("apply 后 allow 桶必须含新规则（CC applyPermissionUpdates）")
            .anySatisfy(r -> {
                assertThat(r.ruleValue().toolName()).isEqualTo("Bash");
                assertThat(r.ruleValue().ruleContent()).isEqualTo("git status");
            });

        // 4) 下一轮真实生效：重新从磁盘加载（PermissionContextBuilder 同源）
        List<PermissionRule> reloaded = projectLoader.load();
        assertThat(reloaded).anySatisfy(r -> {
            assertThat(r.ruleValue().toolName()).isEqualTo("Bash");
            assertThat(r.ruleValue().ruleContent()).isEqualTo("git status");
            assertThat(r.ruleBehavior()).isEqualTo(PermissionBehavior.ALLOW);
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. bridge 竞速批准（CC interactiveHandler.ts:266-280）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bridge 远程批准携带 updatedPermissions → apply+persist（CC :266-269）")
    void bridgeRaceAllow_persistsUpdatedPermissions(@TempDir Path projectHome) throws Exception {
        Object[] pipeline = wireRealPipeline(projectHome);
        PermissionUpdateApplier applier = (PermissionUpdateApplier) pipeline[0];
        PermissionUpdatePersister persister = (PermissionUpdatePersister) pipeline[1];
        ProjectSettingsLoader projectLoader = (ProjectSettingsLoader) pipeline[2];

        SimpMessagingTemplate bridgeWs = mock(SimpMessagingTemplate.class);
        StompBridgePermissionCallbacks bridge = new StompBridgePermissionCallbacks(bridgeWs);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(
            mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireRacersForTesting(bridge, null);
        prompter.wireUpdatePipelineForTesting(applier, persister);

        AtomicReference<Map<String, Object>> appState = new AtomicReference<>(new LinkedHashMap<>());
        ToolPermissionContext permCtx = ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(), Map.of(), Map.of(), Map.of());
        ToolUseContext ctx = newCtx(permCtx, appState);
        String requestId = "tri-bridge-1";

        AtomicReference<PermissionResult> promptResult = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                promptResult.set(prompter.prompt(new StubTool("Bash"),
                    JSON.createObjectNode().put("command", "npm publish"),
                    new PermissionDecisionReason.Other("test"), ctx, requestId,
                    new PermissionPromptDetails("desc", List.of(), null)));
            } catch (Throwable th) {
                // 预期被 bridge resolve 完成
            }
        });
        t.start();
        Thread.sleep(200);

        // 捕获出站 bridge 请求事件（含随机 bridgeRequestId）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(bridgeWs).convertAndSend(anyString(), captor.capture());
        BridgePermissionRequestEvent requestEvent = (BridgePermissionRequestEvent) captor.getValue();

        // 远程表面批准 + 回传 updatedPermissions（CC BridgePermissionResponse）
        bridge.resolve(requestEvent.getRequestId(),
            new BridgePermissionCallbacks.BridgeResponse("allow", null, null,
                List.of(new PermissionUpdate.AddRules(
                    PermissionUpdate.Destination.PROJECT_SETTINGS,
                    List.of(rule(PermissionRuleSource.PROJECT_SETTINGS, "Bash", "npm publish")),
                    PermissionBehavior.ALLOW))));
        t.join(3_000);

        assertThat(promptResult.get()).isInstanceOf(PermissionResult.Allow.class);

        Path settingsFile = projectHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        assertThat(settingsFile).exists();
        JsonNode root = JSON.readTree(settingsFile.toFile());
        assertThat(root.path("permissions").path("allow").toString())
            .as("bridge 批准后规则必须持久化（CC interactiveHandler.ts:266-269 persistPermissions）")
            .contains("Bash(npm publish)");

        // 下一轮生效
        assertThat(projectLoader.load()).anySatisfy(r ->
            assertThat(r.ruleValue().ruleContent()).isEqualTo("npm publish"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. hook 竞速批准（CC PermissionContext.ts:233-239/319-336）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hook allow 携带 updatedPermissions → apply+persist（CC handleHookAllow）")
    void hookRaceAllow_persistsUpdatedPermissions(@TempDir Path projectHome) throws Exception {
        Object[] pipeline = wireRealPipeline(projectHome);
        PermissionUpdateApplier applier = (PermissionUpdateApplier) pipeline[0];
        PermissionUpdatePersister persister = (PermissionUpdatePersister) pipeline[1];
        ProjectSettingsLoader projectLoader = (ProjectSettingsLoader) pipeline[2];

        // hook 决策：allow + updatedPermissions（HookOutputParser 同款 Map 载荷形状）
        Map<String, Object> updateMap = new LinkedHashMap<>();
        updateMap.put("type", "addRules");
        updateMap.put("destination", "projectSettings");
        updateMap.put("behavior", "allow");
        Map<String, Object> ruleMap = new LinkedHashMap<>();
        ruleMap.put("toolName", "Bash");
        ruleMap.put("ruleContent", "ls -la");
        updateMap.put("rules", List.of(ruleMap));

        HookRegistry registry = mock(HookRegistry.class);
        when(registry.executeEvent(any())).thenReturn(
            GenericHook.HookResult.proceed().withPermissionRequestResult(
                new PermissionRequestResult.Allow(null, List.of(updateMap))));

        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(
            mock(SimpMessagingTemplate.class), 5_000);
        prompter.setHookRegistryForTesting(registry);
        prompter.wireUpdatePipelineForTesting(applier, persister);

        AtomicReference<Map<String, Object>> appState = new AtomicReference<>(new LinkedHashMap<>());
        ToolPermissionContext permCtx = ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(), Map.of(), Map.of(), Map.of());
        ToolUseContext ctx = newCtx(permCtx, appState);
        String requestId = "tri-hook-1";

        AtomicReference<PermissionResult> promptResult = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                promptResult.set(prompter.prompt(new StubTool("Bash"),
                    JSON.createObjectNode().put("command", "ls -la"),
                    new PermissionDecisionReason.Other("test"), ctx, requestId,
                    new PermissionPromptDetails("desc", List.of(), null, null, true)));
            } catch (Throwable th) {
                // 预期被 hook 竞速完成
            }
        });
        t.start();
        t.join(3_000);

        assertThat(promptResult.get()).isInstanceOf(PermissionResult.Allow.class);

        Path settingsFile = projectHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        assertThat(settingsFile).exists();
        JsonNode root = JSON.readTree(settingsFile.toFile());
        assertThat(root.path("permissions").path("allow").toString())
            .as("hook allow 携带的 updatedPermissions 必须持久化（CC handleHookAllow → persistPermissions）")
            .contains("Bash(ls -la)");
        assertThat(projectLoader.load()).anySatisfy(r ->
            assertThat(r.ruleValue().ruleContent()).isEqualTo("ls -la"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. Applier 单元：OPD-PERM-08 source 归一 + CC spread 全字段保留
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[OPD-PERM-08/DEL-WF1-04] AddRules 规则 source 由生产者负责（保留原值，桶 key 即归属）")
    void applier_normalizesRuleSourceToBucket() {
        PermissionUpdateApplier applier = new PermissionUpdateApplier();
        ToolPermissionContext ctx = ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.of(), Map.of(), Map.of(), Map.of());

        // 建议对象：destination=USER_SETTINGS 但规则携带 PROJECT_SETTINGS（CheckLayer1b/1f 现状）
        ToolPermissionContext applied = applier.apply(
            new PermissionUpdate.AddRules(PermissionUpdate.Destination.USER_SETTINGS,
                List.of(rule(PermissionRuleSource.PROJECT_SETTINGS, "Bash", "git status")),
                PermissionBehavior.ALLOW),
            ctx);

        // DEL-WF1-04（perm-wf2 d14d1aa18）：规则 source 由生产者负责（桶 key 即归属），
        //   applyAddRules 不再归一化 source —— 落桶后规则保留原 source（PROJECT_SETTINGS）。
        Set<PermissionRule> bucket = applied.alwaysAllowRules().get(PermissionRuleSource.USER_SETTINGS);
        assertThat(bucket).isNotNull().hasSize(1);
        assertThat(bucket.iterator().next().source())
            .as("DEL-WF1-04 规则 source 由生产者负责：落桶到 USER_SETTINGS 但规则 source 保留原 PROJECT_SETTINGS")
            .isEqualTo(PermissionRuleSource.PROJECT_SETTINGS);
        // 其他桶不受影响
        assertThat(applied.alwaysDenyRules()).isEmpty();
        assertThat(applied.alwaysAskRules()).isEmpty();
    }

    @Test
    @DisplayName("apply 全字段保留（CC spread 语义：stash/prePlanMode/标志位）")
    void applier_preservesAllContextFields() {
        PermissionUpdateApplier applier = new PermissionUpdateApplier();
        Map<PermissionRuleSource, Set<PermissionRule>> stash = Map.of(
            PermissionRuleSource.USER_SETTINGS,
            java.util.Set.of(rule(PermissionRuleSource.USER_SETTINGS, "Bash", "python -c 'x'")));
        ToolPermissionContext ctx = new ToolPermissionContext(
            PermissionMode.PLAN,
            Map.of(), Map.of(), Map.of(), Map.of(), true, true,
            stash, true, true, PermissionMode.DEFAULT);

        // setMode：CC {...context, mode} —— 其余字段原样保留
        ToolPermissionContext applied = applier.apply(
            new PermissionUpdate.SetMode(
                PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS), ctx);

        assertThat(applied.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(applied.strippedDangerousRules())
            .as("CC spread 保留 strippedDangerousRules（S04 stash 恢复依赖）")
            .isEqualTo(stash);
        assertThat(applied.prePlanMode())
            .as("CC spread 保留 prePlanMode（plan 退出恢复依赖）")
            .isEqualTo(PermissionMode.DEFAULT);
        assertThat(applied.shouldAvoidPermissionPrompts()).isTrue();
        assertThat(applied.awaitAutomatedChecksBeforeDialog()).isTrue();
        assertThat(applied.isBypassPermissionsModeAvailable()).isTrue();
        assertThat(applied.isAutoModeAvailable()).isTrue();
    }

    @Test
    @DisplayName("updatedPermissions 解析：CC 判别联合形状（camelCase）")
    void parseUpdatedPermissions_ccShape() {
        List<PermissionUpdate> updates = WebSocketPermissionPrompter.parseUpdatedPermissions(
            asNodes(addRulesJson("userSettings", "Edit", "/tmp/**")));
        assertThat(updates).hasSize(1);
        PermissionUpdate update = updates.get(0);
        assertThat(update).isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules add = (PermissionUpdate.AddRules) update;
        assertThat(add.destination()).isEqualTo(PermissionUpdate.Destination.USER_SETTINGS);
        assertThat(add.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(add.rules()).hasSize(1);
        assertThat(add.rules().get(0).ruleValue().toolName()).isEqualTo("Edit");
        assertThat(add.rules().get(0).ruleValue().ruleContent()).isEqualTo("/tmp/**");
        // [OPD-PERM-08] 解析层 source 即取 destination
        assertThat(add.rules().get(0).source()).isEqualTo(PermissionRuleSource.USER_SETTINGS);
    }

    @Test
    @DisplayName("updatedPermissions 解析：非法节点跳过（best-effort，不抛）")
    void parseUpdatedPermissions_skipsInvalid() {
        List<JsonNode> nodes = new ArrayList<>();
        nodes.add(addRulesJson("projectSettings", "Bash", "git status"));
        nodes.add(JSON.createObjectNode()); // 无判别字段
        nodes.add(JSON.createObjectNode().put("type", "noSuchType"));
        List<PermissionUpdate> updates = WebSocketPermissionPrompter.parseUpdatedPermissions(nodes);
        assertThat(updates).hasSize(1);
    }
}
