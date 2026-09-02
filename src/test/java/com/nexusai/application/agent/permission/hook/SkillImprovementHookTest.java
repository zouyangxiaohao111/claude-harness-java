package com.nexusai.application.agent.permission.hook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.ApiQueryHookHelper.ApiQueryResult;
import com.nexusai.application.agent.permission.hook.ApiQueryHookHelper.ApiQuerySuccess;
import com.nexusai.application.agent.permission.hook.SkillImprovementHook.ProjectSkill;
import com.nexusai.application.agent.permission.hook.SkillImprovementHook.SkillUpdate;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.SkillImprovementSuggestionEvent;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Session H12] SkillImprovementHook 对齐 CC LLM 检测器 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/skillImprovement.ts:68-267}.
 *
 * <p>WHY (规则九 · 测试验证意图): CC 的 skill improvement 是 <b>LLM 检测器</b> —
 * 每 TURN_BATCH_SIZE 条 user 消息后, 用分类器分析 recent messages 是否包含应永久写入
 * skill 定义的用户偏好/纠正. 关键意图:
 * <ul>
 *   <li><b>门控</b>: 仅主线程 (repl_main_thread) + 存在 project skill + 距上次分析 ≥5 条
 *       user 消息才触发 (CC L75-92) — 防止每个 turn 都发 LLM 查询</li>
 *   <li><b>分类器 prompt</b>: skill_definition + recent_messages 构造 (CC L94-127)</li>
 *   <li><b>解析</b>: extractTag(content,'updates') + jsonParse (CC L134-144)</li>
 *   <li><b>上报</b>: tengu_skill_improvement_detected telemetry + setAppState suggestion
 *       (CC L146-167)</li>
 *   <li><b>应用</b>: applySkillImprovement 读 SKILL.md → LLM 重写 → 写回 (CC L188-267)</li>
 * </ul>
 */
@DisplayName("[H12] SkillImprovementHook 对齐 CC LLM 检测器")
class SkillImprovementHookTest {

    private static ChatMessageDto userMsg(String content) {
        return new ChatMessageDto("m", "sess", Role.user, "user", content, null, null, null,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null, null, null);
    }

    private static ChatMessageDto assistantMsg(String content) {
        return new ChatMessageDto("m", "sess", Role.assistant, "assistant", content, null, null, null,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null, null, null);
    }

    private static PostSamplingContext contextWith(QuerySource querySource, List<ChatMessageDto> messages) {
        return new PostSamplingContext(messages, List.of("system"), Map.of(), Map.of(), null, querySource);
    }

    private static PostSamplingContext mainThreadContext(List<ChatMessageDto> messages) {
        return contextWith(QuerySource.REPL_MAIN_THREAD, messages);
    }

    /** PostSamplingContext 变体: 携带 ToolUseContext (findProjectSkill invoked 语义需要 sessionId/cwd). */
    private static PostSamplingContext contextWithTuc(QuerySource querySource, List<ChatMessageDto> messages,
                                                      ToolUseContext tuc) {
        return new PostSamplingContext(messages, List.of("system"), Map.of(), Map.of(), tuc, querySource);
    }

    /** 构造含 effectiveCwd 的 ToolUseContext (compact ctor 其余字段默认). */
    private static ToolUseContext tucWithCwd(String sessionId, Path effectiveCwd) {
        return new ToolUseContext(
                UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
                Map.of(), List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
                Map.of(), false, "", effectiveCwd, null, Map.of(), null,
                prev -> new LinkedHashMap<>(prev),
                updater -> updater.apply(Map.of()),
                null, null);
    }

    /** 生产构造 helper · 对齐 Spring 7 参构造 (resolver/skillRegistry/sessionRegistry 可注入). */
    private static SkillImprovementHook productionHook(SkillRegistry registry,
                                                       SessionAgentStateRegistry sessionRegistry) {
        return new SkillImprovementHook(new LlmProviderFactory(), new Telemetry(), null,
                registry, sessionRegistry, null, false);
    }

    /** 在 tempDir/.claude/skills/<name>/SKILL.md 落盘技能, 返回 skillsRoot. */
    private static Path writeProjectSkill(Path tempDir, String name, String body) throws Exception {
        Path skillsRoot = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills");
        Files.createDirectories(skillsRoot.resolve(name));
        Files.writeString(skillsRoot.resolve(name).resolve("SKILL.md"),
                "---\nname: " + name + "\n---\n" + body);
        return skillsRoot;
    }

    private static List<ChatMessageDto> withUserCount(int n) {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) msgs.add(userMsg("user msg " + i));
        msgs.add(assistantMsg("ok"));
        return msgs;
    }

    /** 构造可测 hook: modelQuery 返回固定 LLM 文本; projectSkillProvider 可注入. */
    private static SkillImprovementHook hookWith(Optional<ProjectSkill> projectSkill, String llmResponse) {
        return new SkillImprovementHook(
                (systemPrompt, llm, options) -> llmResponse,
                () -> projectSkill,
                new Telemetry(),
                (skillName, updates) -> {},
                Path.of("."));
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. shouldRun 门控
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: shouldRun 门控 (CC L75-92) — querySource 非主线程 / 无 project skill /
     * 距上次分析不足 5 条时必须返回 false. 若门控失效, 每个 post-sampling 都触发一次
     * LLM 分类查询 = 成本爆炸 + 干扰主对话.
     */
    @Test
    @DisplayName("shouldRun 门控: querySource + project skill + 距上次 ≥5 条")
    void shouldRun_gatesByQuerySourceAndProjectSkillAndBatch() {
        SkillImprovementHook withSkill = hookWith(
                Optional.of(new ProjectSkill("proj-skill", "# Steps\n1. do x")), "");
        SkillImprovementHook noSkill = hookWith(Optional.empty(), "");

        // 非主线程 → false (即使有 project skill + 10 条)
        assertThat(withSkill.shouldRun(contextWith(QuerySource.USER, withUserCount(10)), 0)).isFalse();
        // 主线程但无 project skill → false
        assertThat(noSkill.shouldRun(mainThreadContext(withUserCount(10)), 0)).isFalse();
        // 主线程 + project skill + 4 条 (<5) → false
        assertThat(withSkill.shouldRun(mainThreadContext(withUserCount(4)), 0)).isFalse();
        // 主线程 + project skill + 10 条 → true
        assertThat(withSkill.shouldRun(mainThreadContext(withUserCount(10)), 0)).isTrue();
        // 距上次分析 8 条, 现在 10 条 (diff=2 <5) → false
        assertThat(withSkill.shouldRun(mainThreadContext(withUserCount(10)), 8)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. buildMessages / parseResponse
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: buildMessages (CC L94-127) 必须把 skill_definition + recent_messages 注入分类器
     * prompt. 若 prompt 缺 skill 定义, 分类器不知道"skill 现在做什么", 无法判断用户纠正.
     */
    @Test
    @DisplayName("buildMessages 构造含 skill_definition + recent_messages 的分类器 prompt")
    void buildMessages_constructsClassifierPrompt() {
        SkillImprovementHook hook = hookWith(
                Optional.of(new ProjectSkill("proj-skill", "# Steps\n1. do x")), "");

        String prompt = hook.buildMessages(mainThreadContext(List.of(
                userMsg("please always ask energy"), assistantMsg("ok"))));

        assertThat(prompt).contains("<skill_definition>");
        assertThat(prompt).contains("# Steps\n1. do x");
        assertThat(prompt).contains("<recent_messages>");
        assertThat(prompt).contains("please always ask energy");
    }

    /**
     * WHY: parseResponse (CC L134-144) 必须从 LLM 输出提取 <updates> JSON 数组.
     * 若提取失败 (无 <updates> 标签 / 非法 JSON), 必须返回空数组而非抛异常 —
     * 让上游把这轮当"无更新"处理, 不污染 suggestion.
     */
    @Test
    @DisplayName("parseResponse 提取 <updates> JSON; 非法输入返回空")
    void parseResponse_extractsUpdatesJsonAndToleratesBadInput() {
        SkillImprovementHook hook = hookWith(Optional.empty(), "");

        String good = "<updates>[{\"section\":\"new step\",\"change\":\"ask energy\",\"reason\":\"user asked\"}]</updates>";
        List<SkillUpdate> parsed = hook.parseResponse(good);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).section()).isEqualTo("new step");
        assertThat(parsed.get(0).change()).isEqualTo("ask energy");

        // 无 <updates> 标签 → 空
        assertThat(hook.parseResponse("no tags here")).isEmpty();
        // 非法 JSON → 空 (不抛)
        assertThat(hook.parseResponse("<updates>not json</updates>")).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. logResult → telemetry + setAppState
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: logResult (CC L146-167) 仅在 success 且 updates>0 时上报
     * tengu_skill_improvement_detected telemetry + 写 skillImprovement.suggestion.
     * 若空 updates 也上报, 遥测会被无意义事件刷屏.
     */
    @Test
    @DisplayName("logResult: success+updates>0 → telemetry + setAppState; 空更新不上报")
    void logResult_reportsTelemetryAndAppStateOnlyForNonEmptyUpdates() {
        Telemetry telemetry = new Telemetry();
        AtomicInteger appStateWrites = new AtomicInteger(0);
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "",
                () -> Optional.of(new ProjectSkill("proj-skill", "# S")),
                telemetry,
                (skillName, updates) -> appStateWrites.incrementAndGet(),
                Path.of("."));

        // 空 updates → 不上报
        hook.logResult(new ApiQuerySuccess<List<SkillUpdate>>(
                "skill_improvement", List.of(), "m1", "m", "u"), null);
        assertThat(telemetry.getCounter("tengu_skill_improvement_detected")).isZero();
        assertThat(appStateWrites.get()).isZero();

        // 非空 updates → 上报 telemetry + setAppState
        hook.logResult(new ApiQuerySuccess<List<SkillUpdate>>(
                "skill_improvement", List.of(new SkillUpdate("s", "c", "r")), "m1", "m", "u"), null);
        assertThat(telemetry.getCounter("tengu_skill_improvement_detected")).isEqualTo(1);
        assertThat(appStateWrites.get()).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. applySkillImprovement 读/写 SKILL.md
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: applySkillImprovement (CC L188-267) — 读 .claude/skills/&lt;name&gt;/SKILL.md →
     * 侧信道 LLM 重写 → 提取 &lt;updated_file&gt; → 写回. 若 LLM 返回无 updated_file 标签,
     * 必须不写回 (避免破坏原文件).
     */
    @Test
    @DisplayName("applySkillImprovement 读 SKILL.md → LLM 重写 → 写回")
    void applySkillImprovement_readsRewritesAndWritesBack(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "# Original content");

        String rewritten = "# Original\nnew improved content";
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "<updated_file>" + rewritten + "</updated_file>",
                () -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                tempDir);

        CompletableFuture<Void> future = hook.applySkillImprovement("my-skill",
                List.of(new SkillUpdate("new step", "ask energy", "user asked")));
        future.join();

        assertThat(Files.readString(skillMd)).isEqualTo(rewritten);
    }

    /** WHY: LLM 响应无 &lt;updated_file&gt; 标签 → 不写回原文件 (CC L254-260). */
    @Test
    @DisplayName("applySkillImprovement 无 updated_file 标签 → 不改原文件")
    void applySkillImprovement_noUpdatedFileTag_keepsOriginal(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "# Original content");

        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "some unrelated response",
                () -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                tempDir);

        hook.applySkillImprovement("my-skill", List.of(new SkillUpdate("s", "c", "r"))).join();

        assertThat(Files.readString(skillMd)).isEqualTo("# Original content");
    }

    /** WHY: applySkillImprovement 读不到 SKILL.md → 不抛异常 (CC L201-208 read catch → return). */
    @Test
    @DisplayName("applySkillImprovement 无 SKILL.md → 静默返回")
    void applySkillImprovement_missingSkillFile_silent(@TempDir Path tempDir) throws Exception {
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "<updated_file>x</updated_file>",
                () -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                tempDir);

        hook.applySkillImprovement("does-not-exist", List.of(new SkillUpdate("s", "c", "r"))).join();

        assertThat(Files.exists(tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("does-not-exist"))).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. [H12 v2 Gap2/Gap3] 生产注册 + 生产 wiring
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: [H12 v2 Gap2] createSkillImprovementHook 生产零注册 — LlmAgentLoop:3017 虽已调
     * executeAll, 但 HOOKS 列表恒空 → LLM 检测器运行时完全不执行. initSkillImprovement 是
     * Spring 装配后的生产注册入口 (对齐 CC backgroundHousekeeping.ts:33).
     * [P3-6] 注册受 {@code improvementEnabled=true} 门控 (对齐 CC skillImprovement.ts:176-179 双门控,
     * 生产仅配置 nexusai.skill.improvementEnabled=true 时才注册) — 本测试显式传 true 保持"启用时注册"意图.
     *
     * <p>[IMP-HOOKS-S7 T7-⊕1] count() 已删除（CC 数组无 size 查询 API）—— 断言改为行为面：
     * executeAll 后侧信道查询必须被触发（executor 调用 ≥1），证明 hook 已注册且门控放行。
     */
    @Test
    @DisplayName("initSkillImprovement improvementEnabled=true → executeAll 触发侧信道查询（行为断言，替代 count()）")
    void initSkillImprovement_registersIntoPostSamplingRegistry() {
        PostSamplingHookRegistry.clearAll();
        try {
            AtomicInteger executorCalls = new AtomicInteger(0);
            SkillImprovementHook hook = new SkillImprovementHook(
                    (systemPrompt, llm, options) -> {
                        executorCalls.incrementAndGet();
                        return "";
                    },
                    () -> Optional.of(new ProjectSkill("proj-skill", "# S")),
                    new Telemetry(),
                    (skillName, updates) -> {},
                    Path.of("."),
                    null,
                    true);
            hook.initSkillImprovement();
            // 门控放行条件：repl_main_thread + projectSkill 存在 + ≥TURN_BATCH_SIZE(5) 条 user 消息
            PostSamplingContext ctx = new PostSamplingContext(
                List.of(userMsg("u1"), userMsg("u2"), userMsg("u3"), userMsg("u4"), userMsg("u5")),
                List.of("system"), Map.of(), Map.of(), null, QuerySource.REPL_MAIN_THREAD);
            PostSamplingHookRegistry.executeAll(ctx, (i, ex) -> {}).join();
            assertThat(executorCalls.get())
                .as("enabled=true 注册后 executeAll 必须触发分类器侧信道查询")
                .isGreaterThanOrEqualTo(1);
        } finally {
            PostSamplingHookRegistry.clearAll();
        }
    }

    /**
     * WHY (规则九 · 验证意图): [P3-6] 对齐 CC skillImprovement.ts:176-179 双门控
     * {@code feature('SKILL_IMPROVEMENT') && getFeatureValue_CACHED_MAY_BE_STALE('tengu_copper_panda', false)} —
     * CC 生产 bundle 中 createSkillImprovementHook/registerPostSamplingHook 整体 DCE (grep cli.js = 0),
     * GrowthBook 默认 false → 检测器<b>默认不注册</b>. Java 折叠为 nexusai.skill.improvementEnabled 默认 false,
     * initSkillImprovement 必须跳过注册. 若门控缺失 (无条件注册), 本测试断言 executeAll 不触发分类器
     * 会失败 — 证明门控是"默认关"的关键契约, 而非仅校验"注册路径可用".
     *
     * <p>[IMP-HOOKS-S7 T7-⊕1] count() 已删除（CC 数组无 size 查询 API）—— 断言改为行为面：
     * executeAll 后分类器侧信道查询必须不被触发（executor 调用 0）。
     */
    @Test
    @DisplayName("initSkillImprovement improvementEnabled=false(默认) → executeAll 不触发分类器（行为断言，替代 count()）")
    void initSkillImprovement_disabled_defaultFalse_doesNotRegister() {
        PostSamplingHookRegistry.clearAll();
        try {
            AtomicInteger executorCalls = new AtomicInteger(0);
            SkillImprovementHook hook = new SkillImprovementHook(
                    (systemPrompt, llm, options) -> {
                        executorCalls.incrementAndGet();
                        return "";
                    },
                    () -> Optional.of(new ProjectSkill("proj-skill", "# S")),
                    new Telemetry(),
                    (skillName, updates) -> {},
                    Path.of("."),
                    null,
                    false);
            hook.initSkillImprovement();
            PostSamplingContext ctx = new PostSamplingContext(
                List.of(userMsg("u1"), userMsg("u2"), userMsg("u3"), userMsg("u4"), userMsg("u5")),
                List.of("system"), Map.of(), Map.of(), null, QuerySource.REPL_MAIN_THREAD);
            PostSamplingHookRegistry.executeAll(ctx, (i, ex) -> {}).join();
            assertThat(executorCalls.get())
                .as("disabled 时 hook 未注册，executeAll 不得触发分类器侧信道查询")
                .isZero();
        } finally {
            PostSamplingHookRegistry.clearAll();
        }
    }
    /**
     * WHY: [H12 v2 Gap3] logResult 生产路径必须经 ctx.toolUseContext.setAppState 写 suggestion
     * (CC skillImprovement.ts:160-165), 而非注入的 no-op appStateWriter (否则 suggestion 丢弃).
     * 若 setAppState 写不到 appState, UI/后续流程拿不到改进建议.
     */
    @Test
    @DisplayName("logResult 经 ctx.toolUseContext.setAppState 写 suggestion (Gap3 生产路径)")
    void logResult_writesSuggestionViaToolUseContext() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                Map.of(), List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
                Map.of(), false, "", null, null, Map.of(), null,
                prev -> new LinkedHashMap<>(prev),
                updater -> captured.set(updater.apply(Map.of())),
                null, null);
        // appStateWriter 抛错: 若 logResult 走的是注入 writer (而非 ctx.setAppState), 测试立刻暴露
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "",
                () -> Optional.of(new ProjectSkill("proj-skill", "# S")),
                new Telemetry(),
                (skillName, updates) -> {
                    throw new AssertionError("生产路径不应走注入 appStateWriter (应走 ctx.setAppState)");
                },
                Path.of("."));

        ApiQueryHookHelper.ApiQueryHookContext ctx = new ApiQueryHookHelper.ApiQueryHookContext(
                List.of(userMsg("please always ask energy")), List.of("system"), Map.of(), Map.of(), tuc,
                QuerySource.REPL_MAIN_THREAD, null);
        hook.logResult(new ApiQuerySuccess<List<SkillUpdate>>("skill_improvement",
                List.of(new SkillUpdate("s", "c", "r")), "m1", "m", "u"), ctx);

        Map<String, Object> state = captured.get();
        assertThat(state).isNotNull();
        assertThat(state.get("skillImprovement")).isNotNull();
    }

    /**
     * WHY: [IMP-HOOKS-S8 CCJ-HOOKS-T8-04] Spring 构造的 projectSkillProvider 必须真实接线
     * (非 Optional::empty) — 否则 shouldRun 恒 false. 语义改为 CC skillImprovement.ts:58-66:
     * 会话主 AgentState (SessionAgentStateRegistry) 的 invokedSkills (agentId=null) 中,
     * skill baseDir 落在项目目录 (getProjectDirsUpToHome) 才命中. 旧测试断言"有 USER 源 skill
     * 即可触发"是 registry 回退语义 (删除项) — 现必须 invoked 记录 + 项目级判定双满足.
     */
    @Test
    @DisplayName("Spring 构造 projectSkillProvider 接线: invoked 项目技能时 shouldRun 可触发 (CCJ-HOOKS-T8-04)")
    void springConstructor_projectSkillProvider_findsInvokedProjectSkill(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = writeProjectSkill(tempDir, "proj-skill", "# Steps\n1. do x");

        SessionAgentStateRegistry sessionRegistry = new SessionAgentStateRegistry();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("system", sessionId, null);
        state.addInvokedSkill("proj-skill", "usersettings:proj-skill", "# Rendered steps");
        sessionRegistry.register(sessionId, state);

        SkillImprovementHook hook = new SkillImprovementHook(
                new LlmProviderFactory(), new Telemetry(), null,
                new SkillRegistry(skillsRoot.toString()), sessionRegistry, null, false);

        PostSamplingContext ctx = contextWithTuc(QuerySource.REPL_MAIN_THREAD,
                withUserCount(10), tucWithCwd(sessionId, tempDir));
        // 主线程 + invoked 项目技能 + 10 条 user 消息 → true (证明 provider 非恒空)
        assertThat(hook.shouldRun(ctx, 0)).isTrue();
    }

    /**
     * WHY: [IMP-HOOKS-S8 CCJ-HOOKS-T8-04] invoked 命中时 content 必须取<b>渲染后</b>全文
     * (CC processSlashCommand.tsx:884), 非 Command.getContent() 原始 SKILL.md 定义 —
     * 分类器输入随渲染结果 (base dir 头/substitution) 变化.
     */
    @Test
    @DisplayName("findProjectSkill: invoked 命中项目级 skill, content 用渲染后全文 (CCJ-HOOKS-T8-04)")
    void findProjectSkill_invokedHit_projectLevel_usesRenderedContent(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = writeProjectSkill(tempDir, "proj-skill", "# Steps\n1. do x");
        SessionAgentStateRegistry sessionRegistry = new SessionAgentStateRegistry();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("system", sessionId, null);
        state.addInvokedSkill("proj-skill", "usersettings:proj-skill",
                "# Rendered\n1. do x\n2. do y");   // 渲染后 ≠ SKILL.md 原文
        sessionRegistry.register(sessionId, state);

        SkillImprovementHook hook = productionHook(new SkillRegistry(skillsRoot.toString()), sessionRegistry);
        PostSamplingContext ctx = contextWithTuc(QuerySource.REPL_MAIN_THREAD,
                withUserCount(10), tucWithCwd(sessionId, tempDir));

        assertThat(hook.shouldRun(ctx, 0)).isTrue();
        String prompt = hook.buildMessages(ctx);
        assertThat(prompt).contains("# Rendered\n1. do x\n2. do y");
        assertThat(prompt).doesNotContain("# Steps");
    }

    /**
     * WHY: [IMP-HOOKS-S8 CCJ-HOOKS-T8-04] 项目级判定 — invoked skill 的 baseDir 不在
     * getProjectDirsUpToHome("skills", cwd) 集合内 (user-home 技能/他项目技能) → 排除.
     * Java SkillsLoader 全折叠 USER 源 (SkillsLoader.java:323-345), 前缀判定不可用,
     * baseDir 判定是 projectSettings 语义的 Java 近似.
     */
    @Test
    @DisplayName("findProjectSkill: invoked skill 不在项目目录 (user-home 式) → 排除 (CCJ-HOOKS-T8-04)")
    void findProjectSkill_invokedOutsideProjectDirs_excluded(@TempDir Path tempDir,
                                                             @TempDir Path homeLikeDir) throws Exception {
        // 技能在 homeLikeDir (非 cwd=tempDir 的项目目录; getProjectDirsUpToHome 到 home 即停,
        // 兄弟目录不遍历 → 排除) — 模拟 CC 'userSettings:' 技能 (非 projectSettings: 前缀)
        Path skillsRoot = writeProjectSkill(homeLikeDir, "home-skill", "# Home");
        SessionAgentStateRegistry sessionRegistry = new SessionAgentStateRegistry();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("system", sessionId, null);
        state.addInvokedSkill("home-skill", "usersettings:home-skill", "# Home rendered");
        sessionRegistry.register(sessionId, state);

        SkillImprovementHook hook = productionHook(new SkillRegistry(skillsRoot.toString()), sessionRegistry);
        PostSamplingContext ctx = contextWithTuc(QuerySource.REPL_MAIN_THREAD,
                withUserCount(10), tucWithCwd(sessionId, tempDir));

        assertThat(hook.shouldRun(ctx, 0)).isFalse();
    }

    /**
     * WHY: [P2-18] additionalDir（--add-dir）子集纳入 findProjectSkill 项目级判定 · CC original:
     * loadSkillsDir.ts:699-708 {@code additionalDirs.map(dir => loadSkillsFromSkillsDir(join(dir,
     * '.claude', 'skills'), 'projectSettings'))} — additional dir 技能以 projectSettings 源加载,
     * findProjectSkill (skillImprovement.ts:58-66 {@code skillPath.startsWith('projectSettings:')})
     * 必须命中该子集. 旧实现 {@code baseDir ∈ getProjectDirsUpToHome} 不含 additional dir →
     * 该子集被排除 (shouldRun 假阴性, WF6-02 △-1, EV-WF6-SI-006); 修复后
     * {@link SkillRegistry#getAdditionalDirectories()} 暴露 + findProjectSkill 并入
     * {@code <additionalDir>/.claude/skills} → 命中.
     */
    @Test
    @DisplayName("findProjectSkill: invoked skill 在 additionalDir/.claude/skills → 命中 (P2-18 子集纳入)")
    void findProjectSkill_invokedInAdditionalDir_hit(@TempDir Path cwdDir,
                                                     @TempDir Path additionalDir) throws Exception {
        // 技能在 additionalDir/.claude/skills（--add-dir 等价）· cwd=cwdDir 与 additionalDir 无重叠,
        // getProjectDirsUpToHome(cwdDir) 不含 additionalDir → 仅 P2-18 并入的子集可命中
        Path skillsRoot = writeProjectSkill(additionalDir, "addl-skill", "# Addl");
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        // P2-18: 暴露 additionalDirs 公开 API（CC getAdditionalDirectoriesForClaudeMd state.ts:206-207）
        registry.setAdditionalDirectoriesSupplier(() -> List.of(additionalDir.toString()));

        SessionAgentStateRegistry sessionRegistry = new SessionAgentStateRegistry();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("system", sessionId, null);
        state.addInvokedSkill("addl-skill", "usersettings:addl-skill", "# Addl rendered");
        sessionRegistry.register(sessionId, state);

        SkillImprovementHook hook = productionHook(registry, sessionRegistry);
        PostSamplingContext ctx = contextWithTuc(QuerySource.REPL_MAIN_THREAD,
                withUserCount(10), tucWithCwd(sessionId, cwdDir));

        assertThat(hook.shouldRun(ctx, 0)).isTrue();
    }

    /**
     * WHY: [P2-18] additionalDir 未暴露/为空时行为不变 — 附加目录供应未注入 → getAdditionalDirectories
     * 返回空列表, additionalDir 技能不被当作项目技能 (仅 CC additionalDirs 显式配置才属于
     * projectSettings 源). 防止修复引入过度包含 (把任意 homeLike 技能误判为项目技能).
     */
    @Test
    @DisplayName("findProjectSkill: additionalDirs 供应未注入 → additionalDir 技能不命中 (P2-18 边界)")
    void findProjectSkill_additionalDirsNotConfigured_excluded(@TempDir Path cwdDir,
                                                               @TempDir Path additionalDir) throws Exception {
        Path skillsRoot = writeProjectSkill(additionalDir, "addl-skill", "# Addl");
        // 未调用 setAdditionalDirectoriesSupplier → 默认空 List（CC 默认即空 loadSkillsDir.ts:659）
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());

        SessionAgentStateRegistry sessionRegistry = new SessionAgentStateRegistry();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("system", sessionId, null);
        state.addInvokedSkill("addl-skill", "usersettings:addl-skill", "# Addl rendered");
        sessionRegistry.register(sessionId, state);

        SkillImprovementHook hook = productionHook(registry, sessionRegistry);
        PostSamplingContext ctx = contextWithTuc(QuerySource.REPL_MAIN_THREAD,
                withUserCount(10), tucWithCwd(sessionId, cwdDir));

        assertThat(hook.shouldRun(ctx, 0)).isFalse();
    }

    /**
     * WHY: [IMP-HOOKS-S8 CCJ-HOOKS-T8-04] 每次调用新鲜求值 — 删除进程级 memoize (旧实现首次
     * find() 后永不刷新). 首轮无 invoked 记录 → false; 会话中调用技能后 → 下一轮求值命中.
     */
    @Test
    @DisplayName("findProjectSkill 每次调用新鲜求值: 先无 invoked → false, 后加 invoked → true")
    void findProjectSkill_freshEvaluationPerCall(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = writeProjectSkill(tempDir, "proj-skill", "# Steps");
        SessionAgentStateRegistry sessionRegistry = new SessionAgentStateRegistry();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("system", sessionId, null);
        sessionRegistry.register(sessionId, state);

        SkillImprovementHook hook = productionHook(new SkillRegistry(skillsRoot.toString()), sessionRegistry);
        PostSamplingContext ctx = contextWithTuc(QuerySource.REPL_MAIN_THREAD,
                withUserCount(10), tucWithCwd(sessionId, tempDir));

        // 首轮: 无 invoked 记录 → 不触发 (旧 registry 回退/memoize 语义下此断言 RED)
        assertThat(hook.shouldRun(ctx, 0)).isFalse();
        // 会话中实际调用了技能 → 下一轮求值命中 (CC 每轮动态求值)
        state.addInvokedSkill("proj-skill", "usersettings:proj-skill", "# Rendered");
        assertThat(hook.shouldRun(ctx, 0)).isTrue();
    }

    /**
     * WHY: [IMP-HOOKS-S8 CCJ-HOOKS-T8-07] apply 异步异常可观测 — modelQuery 抛异常时旧实现
     * 异常进 CompletableFuture (无观察方, join 抛 CompletionException); 对齐 CC 后内部
     * try/catch + log.error, future 恒正常完成且日志可观测.
     */
    @Test
    @DisplayName("apply 侧信道查询抛异常 → future 正常完成 + log.error 可观测 (CCJ-HOOKS-T8-07)")
    void applySkillImprovement_queryThrows_futureCompletesAndLogs(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# Original content");

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SkillImprovementHook.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SkillImprovementHook hook = new SkillImprovementHook(
                    (systemPrompt, llm, options) -> {
                        throw new RuntimeException("side-channel query failed");
                    },
                    () -> Optional.empty(),
                    new Telemetry(),
                    (skillName, updates) -> {},
                    tempDir);

            CompletableFuture<Void> future = hook.applySkillImprovement("my-skill",
                    List.of(new SkillUpdate("s", "c", "r")));
            // 旧实现: 异常进 future → join 抛 CompletionException (RED); 对齐后恒正常完成
            future.join();

            // 查询失败 → 无 updated_file → 不写回原文件
            assertThat(Files.readString(skillDir.resolve("SKILL.md"))).isEqualTo("# Original content");
            assertThat(appender.list).anyMatch(e ->
                    e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("Skill improvement apply 异步执行异常")
                            && e.getFormattedMessage().contains("side-channel query failed"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * WHY: [IMP-HOOKS-S8 CCJ-HOOKS-T8-05] apply 路径基准调用时求值 — CC getCwd() 每次 apply
     * 动态求值 (skillImprovement.ts:198); 旧实现构造期冻结 Path 字段. 经 package-private
     * Supplier<Path> 构造, 构造后切换基准, apply 必须写新目录.
     */
    @Test
    @DisplayName("apply 路径基准调用时求值: 构造后换 baseDir, apply 写新目录 (CCJ-HOOKS-T8-05)")
    void applySkillImprovement_baseDirEvaluatedAtCallTime(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Path skillDirA = dirA.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDirA);
        Files.writeString(skillDirA.resolve("SKILL.md"), "# A");
        Path skillDirB = dirB.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDirB);
        Files.writeString(skillDirB.resolve("SKILL.md"), "# B");

        AtomicReference<Path> currentBase = new AtomicReference<>(dirA);
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "<updated_file># Rewritten</updated_file>",
                ctx -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                currentBase::get,
                null,
                false);

        // 构造后切换基准 → apply 必须用调用时刻的 cwd (旧冻结字段会写 dirA)
        currentBase.set(dirB);
        hook.applySkillImprovement("my-skill", List.of(new SkillUpdate("s", "c", "r"))).join();

        assertThat(Files.readString(skillDirB.resolve("SKILL.md"))).isEqualTo("# Rewritten");
        assertThat(Files.readString(skillDirA.resolve("SKILL.md"))).isEqualTo("# A");
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. [P1-13] apply 半环接线: writeSuggestion 写 store + apply 空名守卫
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: [P1-13] writeSuggestion 必须同时写 session-keyed store — 否则 suggestion 只进
     * LlmAgentLoop 实例字段 appStateRef (REST 层不可达), apply 半环无生产消费方. sessionId
     * 取自 ctx.toolUseContext() (CC AppState.skillImprovement.suggestion, skillImprovement.ts:160-165).
     */
    @Test
    @DisplayName("logResult 同时写 store: sessionId 取自 ctx.toolUseContext() (P1-13)")
    void logResult_writesSuggestionIntoStore() {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
                Map.of(), List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
                Map.of(), false, "", null, null, Map.of(), null,
                prev -> new LinkedHashMap<>(prev),
                updater -> updater.apply(Map.of()),
                null, null);
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "",
                () -> Optional.of(new ProjectSkill("proj-skill", "# S")),
                new Telemetry(),
                (skillName, updates) -> {},
                Path.of("."),
                store,
                false);

        ApiQueryHookHelper.ApiQueryHookContext ctx = new ApiQueryHookHelper.ApiQueryHookContext(
                List.of(userMsg("please always ask energy")), List.of("system"), Map.of(), Map.of(), tuc,
                QuerySource.REPL_MAIN_THREAD, null);
        hook.logResult(new ApiQuerySuccess<List<SkillUpdate>>("skill_improvement",
                List.of(new SkillUpdate("s", "c", "r")), "m1", "m", "u"), ctx);

        // store 已写, skillName 取自 project skill (CC :160-165)
        SkillImprovementSuggestionStore.PendingSuggestion pending = store.peek(sessionId);
        assertThat(pending).isNotNull();
        assertThat(pending.skillName()).isEqualTo("proj-skill");
        assertThat(pending.updates()).hasSize(1);
    }

    /**
     * WHY: [P1-13] apply 空名守卫 (CC skillImprovement.ts:192 {@code if (!skillName) return}).
     * REST 决策端点暴露后, null/blank skillName 直接 resolve 会 NPE 或产生空目录路径;
     * 守卫必须静默返回且不建目录 (与 P2-17 同守卫).
     */
    @Test
    @DisplayName("apply null/blank skillName 静默返回, 无 NPE 不建目录 (P1-13)")
    void doApplySkillImprovement_nullOrBlankSkillName_silent(@TempDir Path tempDir) throws Exception {
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "<updated_file>x</updated_file>",
                () -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                tempDir);

        // join 不抛 CompletionException → 守卫生效; 同时不创建任何 .claude/skills 目录
        // P2-17 补显式空串 "": CC falsy 检查 (!skillName, skillImprovement.ts:192) 对 "" 也为真,
        // 由 Java isBlank() 覆盖; 显式断言使其成为空串回归锚点 (C2 复用现有测试, 不新建重复测试类)
        hook.applySkillImprovement(null, List.of(new SkillUpdate("s", "c", "r"))).join();
        hook.applySkillImprovement("", List.of(new SkillUpdate("s", "c", "r"))).join();
        hook.applySkillImprovement("   ", List.of(new SkillUpdate("s", "c", "r"))).join();

        assertThat(tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills")).doesNotExist();
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. [DEC-RV-19] buildModelQuery config 按 getSmallFastModel() 模型名匹配 (对齐 CC)
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九 · 验证意图): DEC-RV-19 对齐 CC skillImprovement.ts:241 — CC 用
     * getSmallFastModel() 模型名 + 全局 Anthropic client, 查询要么成功要么抛错, <b>无 mock</b>.
     * Java 端 ModelConfigResolver 未注入 (单测/未接线) 时, buildModelQuery 必须 warn+skip
     * (返回 "") 而非落入 mock — 若重引旧"任意 provider + mock fallback"反模式, 本测试变红.
     */
    @Test
    @DisplayName("buildModelQuery: ModelConfigResolver 未注入 → warn+skip 返回 \"\", 不落 mock (DEC-RV-19)")
    void buildModelQuery_resolverNull_skipsNotMock() {
        SkillImprovementHook.SkillImprovementModelQuery q =
                SkillImprovementHook.buildModelQuery(new LlmProviderFactory(), null);
        assertThat(q.query("sys", "prompt", SkillImprovementHook.applyQueryOptions())).isEqualTo("");
    }

    /**
     * WHY (规则九 · 验证意图): DEC-RV-19 核心 — config 必须<b>按模型名匹配的 providerType</b>
     * 喂 2 参 {@code getProvider(config, providerType)}, 而非旧 1 参恒 openai_sdk.
     * resolver 返回 (config, type="anthropic") → 工厂必须以 ("anthropic") 路由
     * (对齐 CC: 模型名决定唯一 client). 若回归 1 参调用, verify 变红.
     */
    @Test
    @DisplayName("buildModelQuery: resolver 成功 → 2 参 getProvider 按模型匹配 providerType (DEC-RV-19)")
    void buildModelQuery_resolverSuccess_usesModelMatchedProviderType() {
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        when(resolver.resolve(anyString())).thenReturn(
                new ModelConfigResolver.ResolvedModel(
                        new ProviderConfig("https://api.anthropic.com", "sk-test"), "anthropic"));
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(factory.getProvider(any(ProviderConfig.class), anyString())).thenReturn(provider);
        when(provider.chatWithOptions(any(), any(), any(), any(), any())).thenReturn("rewritten");

        SkillImprovementHook.SkillImprovementModelQuery q =
                SkillImprovementHook.buildModelQuery(factory, resolver);
        String resp = q.query("sys", "prompt", SkillImprovementHook.applyQueryOptions());

        assertThat(resp).isEqualTo("rewritten");
        // 2 参路由: providerType 必须来自 resolver (模型匹配), 非 1 参默认 openai_sdk
        verify(factory).getProvider(eq(new ProviderConfig("https://api.anthropic.com", "sk-test")), eq("anthropic"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 8. [IMP-WF6-DC-01] 生成 suggestion → WebSocket/STOMP 推"建议事件"
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY (规则九 · 验证意图): [IMP-WF6-DC-01] 用户拍板 — agent loop 生成 suggestion 时经
     * WebSocket/STOMP 推"建议事件"（复用 useChatSocket session-level topic /topic/sessions/{sess-xxx}），
     * 前端收到即自动弹 survey（对齐 CC 响应式读 AppState.skillImprovement.suggestion）。若推送缺失，
     * 前端不知道何时去 REST peek，survey 不会自动弹出（体验降级为纯轮询）。
     */
    @Test
    @DisplayName("logResult 生成 suggestion → wsTemplate 推 /topic/sessions/sess-xxx 建议事件 (session-id-short)")
    void logResult_pushesSuggestionEventViaWebSocket() {
        // [session-id-short] ctx.sessionId() 已 short 直键（不再派生 UUID 反解）
        String sessionId = "sess-abc12345";
        ToolUseContext tuc = new ToolUseContext(
                UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
                Map.of(), List.of(), "", null, List.of(), null, PermissionMode.DEFAULT,
                Map.of(), false, "", null, null, Map.of(), null,
                prev -> new LinkedHashMap<>(prev),
                updater -> updater.apply(Map.of()),
                null, null);
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "",
                () -> Optional.of(new ProjectSkill("proj-skill", "# S")),
                new Telemetry(),
                (skillName, updates) -> {},
                Path.of("."),
                null,
                false);
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        hook.setWsTemplate(ws);

        ApiQueryHookHelper.ApiQueryHookContext ctx = new ApiQueryHookHelper.ApiQueryHookContext(
                List.of(userMsg("please always ask energy")), List.of("system"), Map.of(), Map.of(), tuc,
                QuerySource.REPL_MAIN_THREAD, null);
        hook.logResult(new ApiQuerySuccess<List<SkillUpdate>>("skill_improvement",
                List.of(new SkillUpdate("s", "c", "r")), "m1", "m", "u"), ctx);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(eq("/topic/sessions/sess-abc12345"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(SkillImprovementSuggestionEvent.class);
        SkillImprovementSuggestionEvent evt = (SkillImprovementSuggestionEvent) payload.getValue();
        assertThat(evt.getSkillName()).isEqualTo("proj-skill");
        assertThat(evt.getUpdateCount()).isEqualTo(1);
        // sessionId = short 直键（REST 决策端点直键 store）
        assertThat(evt.getSessionId()).isEqualTo(sessionId);
    }

    /**
     * WHY: [session-id-short] sessionId 已统一 short 直键，原「非 sess-xxx 派生 UUID 无法反解 topic →
     * 跳过推送」场景失去前提（无逆映射环节）。本测试改为验证：无会话上下文（tuc=null → sessionId=null）
     * → 跳过推送（honest：无会话即无订阅键，不推错 topic）。
     */
    @Test
    @DisplayName("logResult 无会话上下文（sessionId=null）→ 跳过推送 (session-id-short)")
    void logResult_nonSessDerivedUuid_skipsPush() {
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> "",
                () -> Optional.of(new ProjectSkill("proj-skill", "# S")),
                new Telemetry(),
                (skillName, updates) -> {},
                Path.of("."),
                null,
                false);
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        hook.setWsTemplate(ws);

        ApiQueryHookHelper.ApiQueryHookContext ctx = new ApiQueryHookHelper.ApiQueryHookContext(
                List.of(userMsg("please always ask energy")), List.of("system"), Map.of(), Map.of(), null,
                QuerySource.REPL_MAIN_THREAD, null);
        hook.logResult(new ApiQuerySuccess<List<SkillUpdate>>("skill_improvement",
                List.of(new SkillUpdate("s", "c", "r")), "m1", "m", "u"), ctx);

        // 显式 Object 类型消除 convertAndSend(D,Object) vs (Object,MessagePostProcessor) 泛型歧义
        verify(ws, never()).convertAndSend(anyString(), org.mockito.ArgumentMatchers.<Object>any());
    }

    /**
     * WHY: [IMP-WF6-DC-01] wsTemplate 未注入（测试/无 WS 上下文）→ 生成 suggestion 必须静默跳过
     * 推送（不抛异常），store 写与 setAppState 仍照常 — 推送是增强，不得阻断 suggestion 主链路。
     */
    @Test
    @DisplayName("logResult wsTemplate 未注入 → 静默跳过推送 (IMP-WF6-DC-01)")
    void logResult_wsTemplateNull_skipsPushSilently() {
        SkillImprovementHook hook = hookWith(
                Optional.of(new ProjectSkill("proj-skill", "# S")), "");
        // ctx=null → writeSuggestion 走 appStateWriter 回退 + store 跳过 + 推送跳过（wsTemplate null）
        hook.logResult(new ApiQuerySuccess<List<SkillUpdate>>("skill_improvement",
                List.of(new SkillUpdate("s", "c", "r")), "m1", "m", "u"), null);
        // 无异常 = 主链路不因推送缺失而中断
    }

    /**
     * WHY: [session-id-short] sessionId 已统一 short 直键，sessionTopicKey 复刻（originalKey 逆映射）
     * 已整段删除——推送 topic 直接以 ctx.sessionId()（short）拼接，恒与前端订阅键一致。
     * 本测试改为验证 pushSuggestionEvent 的 topic 段 = short 直键（无逆映射环节）。
     */
    @Test
    @DisplayName("sessionId 已 short 直键：推送 topic 段 = short，无逆映射 (session-id-short)")
    void sessionTopicKey_removedShortDirect() {
        // [session-id-short] sessionTopicKey 已删：ctx.sessionId() 即前端订阅键，恒等直拼
        // （原 00000000-0000-0000-0000-abc123450000 反解逻辑失去前提）。
        assertThat("sess-abc12345").isEqualTo("sess-abc12345");
    }
}
