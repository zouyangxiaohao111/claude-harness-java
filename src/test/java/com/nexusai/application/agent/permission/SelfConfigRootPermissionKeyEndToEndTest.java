package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【批 2026-09-22「发钥匙」】自有设置专用档的<b>端到端链条</b>（产出侧 + 消费侧同批验收）。
 *
 * <h2>被验收的链条（用户症状的最后一块）</h2>
 * <p>用户原话（2026-09-22）：「你在弹窗里对某些路径点『始终允许』，不生效」。
 * 两轮取证后的结构：后端消费侧（1.6）此前只收 {@code '/.claude/'}、{@code '~/.claude/'} 与 skill
 * scope 前缀 ⇒ 弹窗给的是 {@code SetMode(acceptEdits)} + {@code AddDirectories}（不产任何 path 级
 * Edit 规则）⇒ <b>门开着也没人来</b>。本批按 CC 补两半：
 * <ol>
 *   <li><b>产出侧</b>（{@code PermissionUpdates#selfConfigRootRuleSuggestion}，照 CC
 *       {@code permissionOptions.tsx:105-113} + {@code usePermissionHandler.ts:104-129}）：
 *       目标在 {@code ~/.{appName}} 或 {@code <cwd>/.{appName}} 内且非 read ⇒ 产出
 *       {@code addRules(Edit, '~/.{appName}/**' | '/.{appName}/**', allow, session)}，
 *       <b>替换</b>通用会话档；</li>
 *   <li><b>消费侧</b>（{@code WritePermissionChecker#checkClaudeFolderSessionAllow} 1.6）：
 *       接受集补上同源的两个 {@code .{appName}} 文件夹级前缀。</li>
 * </ol>
 *
 * <h2>为什么必须端到端（⛔ 只测一半不算通过）</h2>
 * <p>本仓有前科：产出侧与消费侧不同源 ⇒ 用户点了档「全程报成功却不生效」
 * （见记忆条目 {@code ui-offer-structurally-unconsumable}）。故本测试把五段串成一条真链，
 * <b>每一段的产物都是下一段的输入</b>：
 * <ol>
 *   <li><b>写请求（无任何规则）</b> → {@link WritePermissionChecker#check} ⇒ 1.7 第 3 道 Ask
 *       （前置自证：证明该路径确实走 1.7，且 Ask 的 suggestions 就是弹窗档位载荷）；</li>
 *   <li><b>弹窗档</b> = 上一步 {@code Ask.suggestions()}（真产出点，⛔ 不手工捏规则字符串）
 *       ⇒ 形状断言 = 一条 {@code addRules(Edit, ~/.{appName}/**, allow, session)}；</li>
 *   <li><b>用户点档</b> → 真单点 {@code WebSocketPermissionPrompter.onResponse(allow, <wire 节点>)}
 *       （镜像生产 {@code applyAndPersistUpdates}）⇒ <b>规则落库</b>（{@code SessionRecord}
 *       的 {@code session_permission_rules} 列）；</li>
 *   <li><b>下次 send 回读</b> → 新 {@code LlmAgentLoop} 实例 + 真
 *       {@code PermissionContextBuilder} ⇒ per-turn {@code ToolPermissionContext} 的
 *       SESSION 桶含该规则；</li>
 *   <li><b>同一写请求（用回读出的 permCtx）</b> ⇒ 1.6 收下 ⇒ <b>Allow(Rule)</b>。</li>
 * </ol>
 *
 * <h2>变异红点（本测试是两处变异的判别器）</h2>
 * <ul>
 *   <li>变异①：抹掉产出侧专用档（{@code selfConfigRootRuleSuggestion} 恒 empty）⇒ 步骤 2 形状断言
 *       红（弹窗给的是 SetMode 通用档 ⇒ 生不出 Edit 规则）；</li>
 *   <li>变异②：抹掉消费侧新前缀 ⇒ 步骤 5 红（规则在库里/在 permCtx 里，但 1.6 拒收 ⇒ 落回 1.7 Ask）。</li>
 * </ul>
 *
 * <h2>夹具姿态</h2>
 * <ul>
 *   <li>⛔ <b>非 Spring 测试</b>（无 {@code @SpringBootTest} 等注解）⇒ 不迁移用户真库；</li>
 *   <li>DB 姿态显式声明（{@link SessionProjectRootTestSupport#declareNoDatabase()}）：
 *       {@code SessionProjectRoot.lookup} 未接线 ⇒ 显式声明「确无会话」防 CwdResolution fail-loud；</li>
 *   <li>{@code SessionMapper} 为 mock，{@code SessionRecord} 是内存对象（列写入走<b>生产持久化代码</b>，
 *       非真库）；{@code NexusaiPaths} 的 configHome/appName 覆写在 {@code @BeforeEach} 显式清空
 *       （防同 JVM 内其它测试的静态覆写残留污染）。</li>
 * </ul>
 */
@DisplayName("[批 2026-09-22 发钥匙] 自有设置专用档 · 端到端链条（弹窗档 → 落库 → 回读 → 1.6 → Allow）")
class SelfConfigRootPermissionKeyEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final String SESSION = "sess-selfroot-e2e";

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
        // 静态覆写显式清空：NexusaiPaths 的 appName / configHome 是进程级静态，别的测试设过就外溢。
        NexusaiPaths.setAppNameOverride(null);
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
        NexusaiPaths.setAppNameOverride(null);
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 端到端主用例
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("端到端：无规则写请求 ⇒ 弹窗专用档 ⇒ 点档 ⇒ 落库 ⇒ 回读 ⇒ 同一请求 1.6 收下 ⇒ Allow")
    void selfConfigRootKey_endToEnd() throws Exception {
        String appDir = NexusaiPaths.getProjectDirName();
        String home = System.getProperty("user.home");
        // 目标 = 自有根下非 skill、非 memory 路径（生产日志实测的那类：~/.nexusai/teams/...）
        String target = Paths.get(home, appDir, "teams", "proj-a", "notes.md").toString();
        Path cwd = Paths.get("target", "selfroot-e2e-cwd-" + UUID.randomUUID().toString().substring(0, 8));

        // ── 步骤 1：无任何规则的写请求 ⇒ 1.7 第 3 道 Ask（前置自证：确实走到 1.7，非 1.5/1.6 兜住）──
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(cwd));
        PermissionResult before = checker.check(tool, input(target), permCtxOnly(noRules(), cwd));
        assertThat(before)
            .as("前置自证：无规则时该路径必须落 1.7 危险目录 Ask（否则本链条无意义）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) before).reason())
            .as("归因必须是 1.7 SafetyCheck（危险文件/目录），不是兜底 ask")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);

        // ── 步骤 2：弹窗档 = 真产出点（Ask.suggestions）⇒ 契约形状断言 ──
        List<PermissionUpdate> dialogOptions = ((PermissionResult.Ask) before).suggestions();
        assertThat(dialogOptions)
            .as("弹窗必须给出**恰好一条**档位：自有设置专用档（替换通用会话档，CC permissionOptions.tsx:105 的 if/else）")
            .hasSize(1);
        assertThat(dialogOptions.get(0))
            .as("档位类型必须是 addRules（这才是 1.6 认的『钥匙』；SetMode/AddDirectories 不让 1.7 让路）")
            .isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules patch = (PermissionUpdate.AddRules) dialogOptions.get(0);
        assertThat(patch.destination())
            .as("destination=session（会话级不落盘，CC usePermissionHandler.ts:123）")
            .isEqualTo(PermissionUpdate.Destination.SESSION);
        assertThat(patch.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(patch.rules()).hasSize(1);
        assertThat(patch.rules().get(0).ruleValue().toolName()).isEqualTo("Edit");
        assertThat(patch.rules().get(0).ruleValue().ruleContent())
            .as("ruleContent = '~/.{appName}/**'（消费侧 1.6 必须收下同源前缀）")
            .isEqualTo("~/" + appDir + "/**");

        // ── 步骤 3：用户点该档 ⇒ 真 prompter 单点 onResponse(allow, wire 节点) ⇒ 规则落库列 ──
        SessionRecord row = sessionRow(SESSION);
        WebSocketPermissionPrompter prompter = prompterWith(mapperFor(row));
        driveAllow(prompter, SESSION, "req-e2e-selfroot", wireNodes(dialogOptions));
        assertThat(row.getSessionPermissionRules())
            .as("规则必须写进 sessions.session_permission_rules 列（生产 applyAndPersistUpdates 路径）")
            .isNotNull()
            .contains(patch.rules().get(0).ruleValue().ruleContent());

        // ── 步骤 4：下次 send 回读 ⇒ per-turn permCtx 的 SESSION 桶含该规则 ──
        PermissionContextBuilder builder = new PermissionContextBuilder();
        LlmAgentLoop loop = newLoop(mapperFor(row), builder);
        runOnce(loop, SESSION);
        ToolPermissionContext readBack = perTurnPermCtx(
            appStateBoundBaseTuc(SESSION, loop.getAppStateSnapshot()), SESSION, builder);
        Set<PermissionRule> sessionRules = readBack.alwaysAllowRules().get(PermissionRuleSource.SESSION);
        assertThat(sessionRules)
            .as("回读的 SESSION 桶必须含 'Edit(~/.{appName}/**)'（否则链条断在落库/回读）")
            .isNotNull()
            .anySatisfy(r -> assertThat(
                r.ruleValue().toolName() + "(" + r.ruleValue().ruleContent() + ")")
                .isEqualTo("Edit(" + patch.rules().get(0).ruleValue().ruleContent() + ")"));

        // ── 步骤 5：同一写请求（回读出的 permCtx）⇒ 1.6 收下 ⇒ Allow(Rule) ──
        PermissionResult after = checker.check(tool, input(target), permCtxOnly(readBack, cwd));
        assertThat(after)
            .as("闭环：规则已落库并回读 ⇒ 同一路径的写请求必须 Allow（1.6 收下同源前缀），"
                + "⛔ 若仍 Ask 则说明产出侧与消费侧不同源（本批要消灭的失效模式）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) after).reason())
            .as("Allow 必须来自 1.6 命中的那条会话规则（Rule）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);

        // ── 反向对照（防恒绿）：同一 permCtx、目标换成**非**自有根路径 ⇒ 该规则不覆盖 ⇒ Ask ──
        String outsider = Paths.get(home, "Downloads", "plain-" + UUID.randomUUID().toString().substring(0, 6) + ".md")
            .toString();
        assertThat(checker.check(tool, input(outsider), permCtxOnly(readBack, cwd)))
            .as("反面对照：'~/.{appName}/**' 不得覆盖自有根之外的路径（否则步骤 5 的 Allow 是恒绿）")
            .isInstanceOf(PermissionResult.Ask.class);

        // ── 步骤 5b（**变异② 的唯一判别器**）：自有根下的 settings.json ⇒ 必须 Allow ──
        // WHY 必须另立一条：步骤 5 的目标（teams/...）有**两条**机制都能给 Allow —— 1.6（本批新前缀）
        //   与 1.7 第 3 道的用户批准穿透门（上一批已合，`isCoveredByUserApprovedSessionRule`）。
        //   二者对同一规则冗余 ⇒ 抹掉消费侧新前缀时步骤 5 **仍 Allow**（实测：变异② 对该断言恒绿）。
        //   而 1.7 第 2 道（Claude 配置文件，含 '.{appName}/settings.json'）**不可**被任何用户规则穿透
        //   （仅第 3 道有穿透门）⇒ settings.json 这条路径上 **1.6 是唯一能给出 Allow 的机制**
        //   ⇒ 抹掉消费侧新前缀必然转 Ask（第 2 道）。判别器成立。
        PermissionResult settingsFile = checker.check(tool,
            input(Paths.get(home, appDir, "settings.json").toString()), permCtxOnly(readBack, cwd));
        assertThat(settingsFile)
            .as("闭环判别器：自有根下 settings.json 必须 Allow —— 该路径上 1.7 第 2 道不可穿透，"
                + "故唯一能放行的机制就是 1.6 收下本批新增的 '.{appName}/' 前缀（抹掉它 ⇒ 此处 Ask）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) settingsFile).reason())
            .as("Allow 必须来自 1.6 命中的会话规则（Rule）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 夹具
    // ══════════════════════════════════════════════════════════════════════

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    private static ToolPermissionContext noRules() {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
    }

    /** 13 参工厂：显式 effectiveCwd（工作目录与目标路径刻意互不包含）。 */
    private static ToolUseContext permCtxOnly(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(AGENT_ID, SESSION, permCtx.mode(),
            List.of(), "", AbortController.NOOP, List.of(), permCtx, permCtx.mode(),
            Map.of(), false, "", effectiveCwd);
    }

    /** mock SessionMapper：{@code selectOneById(sessionId)} 恒返回同一可写 record（= 一行 DB 记录）。 */
    private static SessionMapper mapperFor(SessionRecord record) {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(record.getId())).thenReturn(record);
        return mapper;
    }

    private static SessionRecord sessionRow(String sessionId) {
        SessionRecord row = new SessionRecord();
        row.setId(sessionId);
        return row;
    }

    /** 更新列表 → wire 形状 JsonNode（与前端回传 updatedPermissions 同一形态）。 */
    private static List<JsonNode> wireNodes(List<PermissionUpdate> updates) {
        List<JsonNode> out = new ArrayList<>();
        for (PermissionUpdate update : updates) {
            out.add(JSON.valueToTree(update));
        }
        return out;
    }

    /** provider 首调返回 stop 纯文本 → loop 正常退出。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 新 LlmAgentLoop 实例（≡ 下一条用户消息 / 新 send）· 反射注入 permissionContextBuilder。 */
    private static LlmAgentLoop newLoop(SessionMapper sessionMapper,
                                       PermissionContextBuilder builder) throws Exception {
        LlmProvider provider = stopProvider("e2e response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setSessionMapper(sessionMapper);
        if (builder != null) {
            Field f = LlmAgentLoop.class.getDeclaredField("permissionContextBuilder");
            f.setAccessible(true);
            f.set(loop, builder);
        }
        return loop;
    }

    private static void runOnce(LlmAgentLoop loop, String sessionId) {
        loop.run(RunRequest.session("e2e query", sessionId, null, ProviderConfig.empty(),
            "test-model", null, null, null, null, false, null));
    }

    private static void awaitPendingRegistration(WebSocketPermissionPrompter prompter, String requestId)
            throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        f.setAccessible(true);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            Map<String, ?> pending = (Map<String, ?>) f.get(prompter);
            if (pending.containsKey(requestId)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("pending not registered: " + requestId);
    }

    private static WebSocketPermissionPrompter prompterWith(SessionMapper sessionMapper) throws Exception {
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(
            mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), 100);
        prompter.setSessionMapper(sessionMapper);
        return prompter;
    }

    /** 提示流 ctx（生产形状）：per-turn TUC 必带非空 permissionContext，否则 apply/persist 会一起早退。 */
    private static ToolUseContext promptCtx(String sessionId) {
        return ToolUseContext.of(AGENT_ID, sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), noRules(), PermissionMode.DEFAULT);
    }

    /** 真单点：用户点档并携带 updatedPermissions（CC onAllow 语义，interactiveHandler.ts:154-167）。 */
    private static void driveAllow(WebSocketPermissionPrompter prompter, String sessionId,
                                   String requestId, List<JsonNode> updatedPermissions) throws Exception {
        ToolUseContext ctx = promptCtx(sessionId);
        Thread t = new Thread(() -> {
            try {
                prompter.prompt(new StubTool("Edit"),
                    JSON.createObjectNode().put("file_path", "/tmp/e2e/x"),
                    new PermissionDecisionReason.Other("test"), ctx, requestId,
                    new PermissionPromptDetails("desc", List.of(), null));
            } catch (Throwable ignored) {
                // 用户响应为正常返回路径
            }
        });
        t.start();
        awaitPendingRegistration(prompter, requestId);
        prompter.onResponse(requestId, "allow", updatedPermissions, null, null);
        t.join(5_000);
        assertThat(t.isAlive()).as("权限流程必须结束（requestId=%s）", requestId).isFalse();
    }

    private static AgentLoopContext loopCtx(PermissionContextBuilder builder) {
        return new AgentLoopContext(
            null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, null, null, null, null,
            builder, null, null, null);
    }

    /** base TUC：getAppState/setAppState 绑定到 loop 的 appState 快照。 */
    private static ToolUseContext appStateBoundBaseTuc(String sessionId, Map<String, Object> appState) {
        Function<Map<String, Object>, Map<String, Object>> getAppState = prev -> Map.copyOf(appState);
        Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState = updater -> {
            Map<String, Object> next = updater.apply(Map.copyOf(appState));
            appState.clear();
            if (next != null) {
                appState.putAll(next);
            }
        };
        return new ToolUseContext(
            AGENT_ID, sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of(),
            noRules(), PermissionMode.DEFAULT,
            Map.of(), false, "", null, null, Map.of(), null,
            getAppState, setAppState, null, null);
    }

    private static ToolPermissionContext perTurnPermCtx(ToolUseContext baseTuc, String sessionId,
                                                        PermissionContextBuilder builder) {
        ToolUseContext perTurn = AgentLoopContext.toolExecContext(
            loopCtx(builder), baseTuc, new AgentState("sys", sessionId, AGENT_ID), Map.of());
        assertThat(perTurn).as("toolExecContext 必须返回 per-turn TUC").isNotNull();
        assertThat(perTurn.permissionContext()).as("per-turn permCtx 必须重建").isNotNull();
        return perTurn.permissionContext();
    }
}
