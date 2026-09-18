package com.nexusai.application.agent.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.permission.PermissionContextBuilder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.PromptAlignSettingsResolver;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.subagent.ForkSubagentConfig;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [coordinator-session V75] 会话级 coordinator 模式 · <b>四层可得性（写侧 / 读侧 / 三层优先级 / 门接点）</b>。
 *
 * <h2>WHY 存在（本任务要修的故障形态）</h2>
 * <p>coordinator 在 CC 里是<b>会话属性</b>：持久化在主会话 JSONL 的
 * {@code {"type":"mode","mode":"coordinator"}}（{@code types/logs.ts:138-142} {@code ModeEntry}，
 * 写于 {@code utils/sessionStorage.ts:825-831}），恢复时 {@code matchSessionMode}
 * （{@code coordinator/coordinatorMode.ts:49-78}）把运行时<b>对齐</b>到存档模式。nexusai 此前
 * <b>完全没有</b>这个能力 —— coordinator 只能是全局进程级（{@code settings.coordinator_mode_enabled}
 * 单例 / feature+env），<b>做不到「A 会话是协调者、B 会话不是」</b>。本类钉死补齐后的契约：
 * <ol>
 *   <li><b>写侧</b>：会话级设定真落库（{@code sessions.coordinator_mode} V75）+
 *       transcript 出现 {@code mode} 行（值与判定同源）；</li>
 *   <li><b>读侧 / 对齐</b>：判定按 sessionId 读会话列（CC 翻进程 env 的 Web 多会话等价物）；</li>
 *   <li><b>三层优先级</b>：会话列 &gt; settings &gt; feature&amp;&amp;env，
 *       任一层为 null 才回落下一层；</li>
 *   <li><b>门接点</b>：会话层必须真的走到生产门（工具池 / 权限链），否则「设了会话级却不生效」
 *       = 半激活。</li>
 * </ol>
 *
 * <h2>架构差异（不照搬 CC）</h2>
 * <p>CC 单进程单会话 ⇒ {@code matchSessionMode} 直接翻 {@code process.env}；Web 多会话后端多会话并存
 * 同一进程 ⇒ <b>不能用进程级 env 表达会话属性</b>（本仓铁律：会话态不得经 ThreadLocal/MDC 读，
 * 必须直传或按 sessionId 查 DB）。故「对齐」落点 = 判定时按 sessionId 直查会话列。
 *
 * <h2>夹具（对齐 test-env-failloud-unreachable 教训）</h2>
 * <p>不读真实 env / Spring 环境：回落层一律用 2 参构造器 {@code new CoordinatorMode(() -> f, () -> e)}；
 * DB 层用 {@code mock(SettingsMapper)}；会话层用 {@code mock(SessionMapper)} —— 三者都在用例里显式设置，
 * 「跑了但某层从没真开过」的假绿在本类不可能发生。{@code @AfterEach} 逐项复位静态槽。
 *
 * <p><b>本类不含</b> PATCH 写库路径（{@code SessionService.update}）——那在
 * {@code SessionServiceTest#update_persistsCoordinatorMode*}。
 */
@DisplayName("[V75] 会话级 coordinator 模式 · 写侧落库 / 读侧对齐 / 三层优先级 / 门接点")
class CoordinatorSessionModeTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 会话 A / B（多会话并存场景）。 */
    private static final String SESSION_A = "sess-A";
    private static final String SESSION_B = "sess-B";

    // ════════════════════════════════════════════════════════════════════
    // 夹具
    // ════════════════════════════════════════════════════════════════════

    /**
     * 真实 {@link PromptAlignSettingsResolver} + mock 两个 mapper · <b>不用 mock 类</b>：
     * 三层优先级是真实方法体（mock 掉就等于把被测逻辑删了）。
     *
     * @param settingsCoordinatorValue settings.coordinator_mode_enabled 列值（null = 未配置）
     */
    private static PromptAlignSettingsResolver resolver(Boolean settingsCoordinatorValue) {
        PromptAlignSettingsResolver r = new PromptAlignSettingsResolver();
        if (settingsCoordinatorValue != null) {
            SettingsRecord row = new SettingsRecord();
            row.setCoordinatorModeEnabled(settingsCoordinatorValue);
            SettingsMapper sm = mock(SettingsMapper.class);
            when(sm.selectOneById(1)).thenReturn(row);
            r.setSettingsMapper(sm);
        }
        return r;
    }

    /** 给 resolver 挂会话 mapper：sessionId → 列值（null 值 = 该会话行存在但列 NULL）。 */
    private static PromptAlignSettingsResolver withSession(PromptAlignSettingsResolver r,
                                                           String sessionId, Integer columnValue) {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionRecord row = new SessionRecord();
        row.setId(sessionId);
        row.setCoordinatorMode(columnValue);
        when(mapper.selectOneById(sessionId)).thenReturn(row);
        r.setSessionMapper(mapper);
        return r;
    }

    /** feature/env 回落层（2 参构造器：不依赖真实 Spring Environment）。 */
    private static CoordinatorMode envFeatureLayer(boolean feature, String env) {
        return new CoordinatorMode(() -> feature, () -> env);
    }

    /** 生产默认形态的回落层：feature 恒 false（application.yml）+ env 未设 → isCoordinatorMode()=false。 */
    private static CoordinatorMode envFeatureOff() {
        return envFeatureLayer(false, null);
    }

    /** feature 恒 false + env="1" 也不会开（CC isCoordinatorMode 要求两者皆真）—— 用于反向。 */
    private static CoordinatorMode envFeatureEnvOnly() {
        return envFeatureLayer(false, "1");
    }

    /** 双真回落层。 */
    private static CoordinatorMode envFeatureOn() {
        return envFeatureLayer(true, "1");
    }

    @AfterEach
    void resetStaticSeams() {
        PromptAlignSettingsResolver.setStaticResolver(null);
        LlmAgentLoop.setCoordinatorMode(null);
        MemoryBareModeConfig.reset();
        ForkSubagentConfig.register(null);
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    // ── 工具池夹具（对齐 LlmAgentLoopCoordinatorFilterTest） ──

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    private static ToolUseContext tuc(String sessionId) {
        return new ToolUseContext(
            UUID.randomUUID(),
            sessionId,
            PermissionMode.DEFAULT,
            Map.of(),
            List.of(
                tool("Agent"), tool("TaskStop"), tool("SendMessage"), tool("StructuredOutput"),
                tool("Bash"), tool("Read"), tool("Edit"), tool("WebSearch"),
                tool("github.com.mycorp.subscribe_pr_activity")),
            null,
            AbortController.NOOP,
            List.of(),
            null,
            PermissionMode.DEFAULT);
    }

    private static List<String> visibleToolNames(String sessionId) {
        return LlmAgentLoop.sessionVisibleToolsBase(tuc(sessionId), QuerySource.USER).stream()
            .map(Tool::name)
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 三层优先级矩阵（唯一判定 · 会话列 → settings → feature&&env）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("三层优先级: 会话列有值即用（1/0 双向压过 settings + feature&&env）")
    void threeLayers_sessionColumnWinsBothDirections() {
        // ① 会话列=1 + settings=false + feature/env 关 → true（唯一用户可达的「只开本会话」路径）
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                SESSION_A, withSession(resolver(false), SESSION_A, 1), envFeatureOff()))
            .as("会话列=1 必须压过 settings=false（否则「A 会话协调者」无法在不改全局开关下成立）")
            .isTrue();

        // ② 会话列=0 + settings=true + feature/env 双真 → false（B 会话被显式压回普通）
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                SESSION_B, withSession(resolver(true), SESSION_B, 0), envFeatureOn()))
            .as("会话列=0 必须压过 settings=true 且压过 feature&&env（「B 不是协调者」的载体）")
            .isFalse();
    }

    @Test
    @DisplayName("三层优先级: 会话列 NULL → 回落 settings（有值即用，false 也压过 env 真）")
    void threeLayers_nullSessionFallsBackToSettings() {
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                SESSION_A, withSession(resolver(true), SESSION_A, null), envFeatureOff()))
            .as("会话列 NULL + settings=true → true（回落第二层）")
            .isTrue();

        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                SESSION_A, withSession(resolver(false), SESSION_A, null), envFeatureOn()))
            .as("会话列 NULL + settings=false + feature/env 双真 → false（settings 显式关也是「有值」）")
            .isFalse();
    }

    @Test
    @DisplayName("三层优先级: 会话列 NULL + settings 未配置 → 回落 feature && env（末层）")
    void threeLayers_nullSessionNullSettingsFallsBackToEnvFeature() {
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                SESSION_A, withSession(resolver(null), SESSION_A, null), envFeatureOn()))
            .as("两层都未配置 → 回落 CC 原判定链 feature && env（双真 = true）")
            .isTrue();

        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                SESSION_A, withSession(resolver(null), SESSION_A, null), envFeatureOff()))
            .as("两层都未配置 + feature/env 关 → false（默认关，零行为变化）")
            .isFalse();

        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                SESSION_A, withSession(resolver(null), SESSION_A, null), envFeatureEnvOnly()))
            .as("末层是 AND（feature 假 + env 真 ≠ 开），与 CC isCoordinatorMode 同源")
            .isFalse();
    }

    @Test
    @DisplayName("三层优先级: 两层重载（无会话身份）与会话重载同源，会话层缺席时逐位一致")
    void twoArgOverload_isSessionLayerAbsent() {
        // 无会话身份调用方（启动期装配 / Tool.prompt()）走的入口：等价 sessionOverride=null
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(resolver(true), envFeatureOff()))
            .as("两层重载 = 会话层缺席，回落 settings")
            .isTrue();
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(
                null, resolver(true), envFeatureOff()))
            .as("sessionId=null → 会话层缺席，与两层重载同值（来源解析在调用方，优先级只有一份）")
            .isTrue();
        assertThat(PromptAlignSettingsResolver.coordinatorModeActive(resolver(null), null))
            .as("三层全缺 → fail-closed false")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 多会话并存（本任务的头号需求）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("多会话并存: 同一进程内 A 协调者 / B 普通 —— 逐次判定各自取值，互不串味")
    void multiSession_sameProcessDifferentVerdicts() {
        // WHY（本任务的原始缺陷）：coordinator 此前只能全局进程级 ⇒ A 与 B 恒同值。会话列落地后，
        //   同一 resolver 实例（同一进程）按 sessionId 分别取值。
        // 变异点：让环境压过会话列 / 去掉会话层 → A、B 判定合流 → 红。
        PromptAlignSettingsResolver r = resolver(null);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESSION_A)).thenReturn(sessionRow(SESSION_A, 1));
        when(mapper.selectOneById(SESSION_B)).thenReturn(sessionRow(SESSION_B, 0));
        r.setSessionMapper(mapper);
        CoordinatorMode fallback = envFeatureOn();   // 末层双真（最容易被误当权威的一层）

        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(SESSION_A, r, fallback))
            .as("会话 A（列=1）→ 协调者").isTrue();
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(SESSION_B, r, fallback))
            .as("会话 B（列=0）→ 普通").isFalse();
        // 交错再取一次：无状态、无缓存、无进程级翻转（CC 的 matchSessionMode 会改进程全局，
        // 多会话下正是不可用之处）
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(SESSION_A, r, fallback))
            .as("再取 A 仍为协调者（判定无进程级残留）").isTrue();
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(SESSION_B, r, fallback))
            .as("再取 B 仍为普通（另一个会话的判定未污染本会话）").isFalse();
    }

    @Test
    @DisplayName("多会话并存（主循环工具池级）: A 会话工具池被裁，B 会话工具池不动")
    void multiSession_toolPoolTrimmedPerSession() {
        // WHY：工具池裁剪是「半激活」最典型的症状面（提示词已协调者、工具池没裁 ⇒ 模型看得见
        //   Bash/Read/Edit，编排意图落空）。本用例断言会话层真的走到了该门。
        PromptAlignSettingsResolver r = new PromptAlignSettingsResolver();
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESSION_A)).thenReturn(sessionRow(SESSION_A, 1));
        when(mapper.selectOneById(SESSION_B)).thenReturn(sessionRow(SESSION_B, 0));
        r.setSessionMapper(mapper);
        PromptAlignSettingsResolver.setStaticResolver(r);   // 静态门（sessionVisibleToolsBase）的读源槽
        LlmAgentLoop.setCoordinatorMode(envFeatureOff());

        assertThat(visibleToolNames(SESSION_A))
            .as("会话 A（列=1）→ 顶层工具池裁为协调者白名单（无 Bash/Read/Edit）")
            .contains("Agent", "TaskStop", "SendMessage")
            .doesNotContain("Bash", "Read", "Edit", "WebSearch");

        assertThat(visibleToolNames(SESSION_B))
            .as("会话 B（列=0）→ 同一进程、同一静态槽，工具池保持全量")
            .contains("Bash", "Read", "Edit");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 会话列读源本身（三态 / fail-soft）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("会话列读源三态: 1→TRUE / 0→FALSE（不是 null）/ NULL→null / 行缺失→null")
    void sessionCoordinatorMode_triState() {
        PromptAlignSettingsResolver r = resolver(null);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESSION_A)).thenReturn(sessionRow(SESSION_A, 1));
        when(mapper.selectOneById(SESSION_B)).thenReturn(sessionRow(SESSION_B, 0));
        when(mapper.selectOneById("sess-missing")).thenReturn(null);
        r.setSessionMapper(mapper);

        assertThat(r.sessionCoordinatorMode(SESSION_A)).as("列=1 → TRUE").isTrue();
        assertThat(r.sessionCoordinatorMode(SESSION_B)).as("列=0 → FALSE（显式关，非「未设置」）").isFalse();
        assertThat(r.sessionCoordinatorMode("sess-missing")).as("行缺失 → null（回落下一层）").isNull();
        assertThat(r.sessionCoordinatorMode(null)).as("sessionId null → null").isNull();
        assertThat(r.sessionCoordinatorMode("  ")).as("sessionId blank → null").isNull();
        assertThat(new PromptAlignSettingsResolver().sessionCoordinatorMode(SESSION_A))
            .as("mapper 未注入（plain POJO / 测试直构）→ null，不抛").isNull();
    }

    @Test
    @DisplayName("会话列读源 fail-soft: mapper 抛异常 → null 回落下一层（不冒泡、不误判为开）")
    void sessionCoordinatorMode_readFailureFallsBack() {
        PromptAlignSettingsResolver r = resolver(true);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESSION_A)).thenThrow(new IllegalStateException("db down"));
        r.setSessionMapper(mapper);

        assertThat(r.sessionCoordinatorMode(SESSION_A))
            .as("查询异常 → null（fail-soft，同 settings 单行读失败范式）").isNull();
        assertThat(PromptAlignSettingsResolver.coordinatorModeActiveForSession(SESSION_A, r, envFeatureOff()))
            .as("读失败 → 回落 settings=true → 仍为 true（不因异常把门关掉）").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 写侧 · transcript mode 行（CC ModeEntry）+ 与判定同源
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("写侧: mode 行标签 = 三层判定的结果（coordinator|normal），与读侧同源不分叉")
    void writeSide_modeLabelFollowsEffectiveMode() {
        // 会话列=1 → "coordinator"
        assertThat(PromptAlignSettingsResolver.coordinatorSessionModeLabel(
                SESSION_A, withSession(resolver(false), SESSION_A, 1), envFeatureOff()))
            .as("会话列=1 → coordinator 标签").isEqualTo("coordinator");
        // 会话列 NULL + settings=true → "coordinator"（回落层也参与，等价 CC saveMode(isCoordinatorMode())）
        assertThat(PromptAlignSettingsResolver.coordinatorSessionModeLabel(
                SESSION_A, withSession(resolver(true), SESSION_A, null), envFeatureOff()))
            .as("回落 settings=true → coordinator 标签").isEqualTo("coordinator");
        // 全关 → "normal"
        assertThat(PromptAlignSettingsResolver.coordinatorSessionModeLabel(
                SESSION_A, withSession(resolver(null), SESSION_A, null), envFeatureOff()))
            .as("三层全关 → normal 标签").isEqualTo("normal");
        // 静态槽便捷入口同源
        PromptAlignSettingsResolver.setStaticResolver(withSession(resolver(null), SESSION_A, 1));
        assertThat(PromptAlignSettingsResolver.staticCoordinatorSessionModeLabel(SESSION_A, envFeatureOff()))
            .as("静态槽入口与实例入口同源（transcript 写侧三个生产调用点走本入口）")
            .isEqualTo("coordinator");
        assertThat(PromptAlignSettingsResolver.staticCoordinatorSessionModeLabel(SESSION_B, envFeatureOff()))
            .as("staticResolver 未接线 / 行缺失 → normal（fail-soft，不抛）").isEqualTo("normal");
    }

    @Test
    @DisplayName("写侧: mode 标签经 reAppendSessionMetadata 落 transcript（{\"type\":\"mode\",\"mode\":…}）")
    void writeSide_transcriptGetsModeEntry() throws Exception {
        // WHY：CC 把会话模式双通道持久化（JSONL mode 行 = 查询/恢复源）。本仓 DB 列=恢复源、
        //   transcript 行=CC 兼容查询通道（transcript-dual-channel-decision）。写侧标签与判定同源，
        //   故 CC 侧读到的会话模式与本仓运行时一致。
        String sessionId = "sess-transcript-mode";

        // 生成本会话的有效标签（会话列=1），并原样喂给写入器（= 三个生产构造点的形态）
        String label = PromptAlignSettingsResolver.coordinatorSessionModeLabel(
            sessionId, withSession(resolver(null), sessionId, 1), envFeatureOff());
        SessionStorage.reAppendSessionMetadata(tempDir, sessionId,
            new SessionStorage.SessionMetadata(null, null, null, null, null, null,
                label, null, null, null, null));

        List<JsonNode> entries = readAllEntries(sessionId);
        assertThat(entries).as("写侧必须真的产生一条 mode 行（此前三处生产构造点全传 null ⇒ 永不落盘）")
            .hasSize(1);
        JsonNode mode = entries.get(0);
        assertThat(mode.path("type").asText()).isEqualTo("mode");
        assertThat(mode.path("mode").asText()).as("CC ModeEntry.mode 字面量")
            .isEqualTo("coordinator");
        assertThat(mode.path("sessionId").asText()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("写侧反向: mode=null → 不写 mode 行（未设置不伪造 normal）")
    void writeSide_nullModeWritesNothing() throws Exception {
        // 修 CC 对齐边界：会话级未设置且所有回落层都关时，标签是 "normal"（CC 也写 normal）——但
        // 「不传 mode」必须保持「不写」（既有契约：null 字段不 append），否则会把「未追踪」伪装成
        // 「已设为 normal」，与 mode 行的「还原点」语义冲突（CC 用 undefined 表示旧会话无此记录）。
        String sessionId = "sess-transcript-no-mode";
        SessionStorage.reAppendSessionMetadata(tempDir, sessionId,
            new SessionStorage.SessionMetadata(null, "T", null, null, null, null,
                null, null, null, null, null));

        List<JsonNode> entries = readAllEntries(sessionId);
        assertThat(entries).as("仅 custom-title 一行").hasSize(1);
        assertThat(entries.get(0).path("type").asText()).isEqualTo("custom-title");
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. 门接点（会话层必须真的走到生产门）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("门接点: 权限链 isAwaitAutomatedChecksBeforeDialog 吃会话列（DB/env 全关也生效）")
    void gate_permissionChainReadsSessionColumn() throws Exception {
        PromptAlignSettingsResolver.setStaticResolver(withSession(resolver(null), SESSION_A, 1));
        PermissionContextBuilder b = new PermissionContextBuilder();
        ReflectionTestUtils.setField(b, "coordinatorMode", envFeatureOff());

        Method m = PermissionContextBuilder.class
            .getDeclaredMethod("isAwaitAutomatedChecksBeforeDialog", String.class);
        m.setAccessible(true);

        assertThat((Boolean) m.invoke(b, SESSION_A))
            .as("会话列=1（settings/env 全关）→ 权限链必须切协调者语义（否则 DB-only/会话-only 激活时半激活）")
            .isTrue();
        assertThat((Boolean) m.invoke(b, SESSION_B))
            .as("同一 builder 实例、另一会话 → false（会话间不串味）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static SessionRecord sessionRow(String id, Integer coordinatorMode) {
        SessionRecord row = new SessionRecord();
        row.setId(id);
        row.setCoordinatorMode(coordinatorMode);
        return row;
    }

    private List<JsonNode> readAllEntries(String sessionId) throws Exception {
        Path transcript = SessionStorage.getTranscriptPath(tempDir, sessionId);
        assertThat(Files.exists(transcript)).as("transcript 应已写出: %s", transcript).isTrue();
        List<JsonNode> out = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(transcript)) {
            if (!line.isBlank()) {
                out.add(JSON.readTree(line));
            }
        }
        return out;
    }
}
