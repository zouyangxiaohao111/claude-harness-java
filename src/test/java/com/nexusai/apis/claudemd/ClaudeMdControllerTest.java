package com.nexusai.apis.claudemd;

import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [FR-7 · OPD-CM5-F-09 / IMP-F1-7 · T15-3] {@link ClaudeMdController}
 * POST /include-approval + GET /include-status 意图测试 · 前端审批对话框通道。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：审批态 CC 真源为 project config 的
 * {@code config.hasClaudeMdExternalIncludesApproved}（config.ts:115，默认 :146；消费点
 * = {@code getCurrentProjectConfig()}，claudemd.ts:796 / :1420），由 {@code getMemoryFiles} 消费：
 * {@code includeExternal = forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved || false}
 * （claudemd.ts:798-801）。Java 无 getCurrentProjectConfig → 本控制器是唯一生产注入点（F1-7 缺口）。
 * 测试锁定端点语义：
 * <ol>
 *   <li><b>置位必须落到引擎接缝</b>——若 200 但未注册接缝（{@code setHasClaudeMdExternalIncludesApproved}），
 *       前端对话框显示成功但外部 @include 永不加载 = 静默 no-op；断言注册的闭包按 sessionId 返回提交值。</li>
 *   <li><b>审批翻转后必须失效 memoize 缓存</b>——置位后不失效则 {@code getMemoryFiles(false, ...)}
 *       仍返回旧列表；对齐 CC clearMemoryFileCaches「settings sync 纯正确性失效」（claudemd.ts:1110-1122）。</li>
 *   <li><b>approved / sessionId 缺失 → 400</b>——审批态二值契约 + 项目维度必填（拒绝隐式缺省）。</li>
 *   <li><b>引擎未接线 → 500 fail loud</b>——审批不落地 = 功能无效，无静默降级。</li>
 * </ol>
 *
 * <p>⭐ <b>[T15-3 2026-09-14] 键 = 项目（不是进程、也不是 sessionId）</b>：本批把两份态从
 * <b>进程级单例 volatile</b> 改为 <b>按项目键分区</b>（键 = sessionId → {@link SessionProjectRoot} 项目根
 * → {@link AutoMemPaths#findCanonicalGitRoot} canonical 键）。因此本测试同时锁两边：
 * <ul>
 *   <li><b>反向</b>：A 项目批准 ⇒ <b>B 项目不受影响</b>（旧进程级单例的跨项目泄漏不得回归）；</li>
 *   <li><b>正向</b>：<b>同项目的另一个会话受影响</b>（= CC per-project 语义；⛔ 若有人改成「按 sessionId 存」，
 *       本断言翻红 —— 那是发明 CC 没有的判据）；</li>
 *   <li><b>正向（canonical 粒度）</b>：同一 git 仓的不同子目录 / worktree <b>共享</b>审批态
 *       （CC {@code findCanonicalGitRoot} 折叠语义，git.ts:123-183 / memdir/paths.ts:204）。</li>
 * </ul>
 *
 * <p><b>WarningShown 对齐（2026-08-23 补齐）</b>：CC Dialog onDone 批准/拒绝**均**置
 * {@code hasClaudeMdExternalIncludesWarningShown=true}（config.ts:123-131）——拒绝后
 * {@code shouldShowClaudeMdExternalIncludesWarning} 返回 false（不再弹窗）。
 */
@DisplayName("[FR-7][T15-3] ClaudeMdController /include-approval + GET /include-status（项目键 + sessionId 契约）")
class ClaudeMdControllerTest {

    @TempDir
    Path tempRoot;

    private ClaudeMdController controller;
    private ClaudemdEngine engine;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ClaudeMdController();
        engine = mock(ClaudemdEngine.class);
        ReflectionTestUtils.setField(controller, "claudemdEngine", engine);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        // SessionProjectRoot 的冻结表与回源器是**进程级 static**：逐用例复位，防跨类污染
        // （⚠️ reset() 刻意不清 dbResolver，故两者都要复位）
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    /** 把会话绑定到项目根（走 SessionProjectRoot 公开 API；测试夹具无需 DB）。 */
    private void bind(String sessionId, Path projectRoot) {
        SessionProjectRoot.setForSession(sessionId, projectRoot.toString());
    }

    /**
     * 建项目根目录并返回（⚠️ 必须先建目录再 {@link #bind}：{@code setForSession} 校验
     * 「绝对路径 + 目录存在」<b>无效即静默拒绝绑定</b> ⇒ 先绑后建会让会话实际未绑定，
     * 后续 400 断言假绿/假红）。
     */
    private Path project(String name) throws Exception {
        return Files.createDirectories(tempRoot.resolve(name));
    }

    /** 建一个「常规 git 仓」夹具（{@code .git} 为目录 ⇒ canonical 根 = 本目录）。 */
    private Path createGitRepoFixture() throws Exception {
        Path repo = tempRoot.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(repo.resolve("sub"));
        return repo;
    }

    /** 取控制器注册进引擎的**审批态闭包**（= 生产上引擎两个消费点唯一读到的那个）。 */
    private Function<String, Boolean> approvedResolver() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, Boolean>> cap = ArgumentCaptor.forClass(Function.class);
        verify(engine, atLeastOnce()).setHasClaudeMdExternalIncludesApproved(cap.capture());
        return cap.getValue();
    }

    /** 取控制器注册进引擎的**警告已示闭包**。 */
    private Function<String, Boolean> warningShownResolver() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, Boolean>> cap = ArgumentCaptor.forClass(Function.class);
        verify(engine, atLeastOnce()).setHasClaudeMdExternalIncludesWarningShown(cap.capture());
        return cap.getValue();
    }

    /** 批准指定会话所在项目。 */
    private void approve(String sessionId, boolean approved) throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON)
                        .content("{\"approved\": " + approved + ", \"sessionId\": \"" + sessionId + "\"}"))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════════
    // ① 反向：A 项目批准 ⇒ B 项目不受影响
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[T15-3] ⭐反向：A 项目批准 ⇒ B 项目不受影响（进程级单例的跨项目泄漏不得回归）")
    void approveInProjectA_doesNotAffectProjectB() throws Exception {
        bind("sess-a", project("proj-a"));
        bind("sess-b", project("proj-b"));

        approve("sess-a", true);

        assertThat(approvedResolver().apply("sess-a")).as("A 项目已批准").isTrue();
        assertThat(approvedResolver().apply("sess-b"))
            .as("⛔ B 项目必须不受影响 —— A 的批准不得泄漏到 B（旧实现进程级 volatile 即此 bug）")
            .isFalse();
        assertThat(warningShownResolver().apply("sess-a")).as("A 项目已示警（CC onDone 置位）").isTrue();
        assertThat(warningShownResolver().apply("sess-b")).as("⛔ B 项目未示警").isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // ② 正向：同项目另一会话受影响（含 canonical 粒度）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[T15-3] ⭐正向：同项目的另一个会话 ⇒ 受影响（= CC per-project 语义；防「按 sessionId 存」的假实现）")
    void sameProjectOtherSession_isAffected() throws Exception {
        Path shared = project("proj-shared");
        bind("sess-a", shared);
        bind("sess-a-other", shared);   // 同项目（同一项目根）的另一个会话

        approve("sess-a", true);

        assertThat(approvedResolver().apply("sess-a-other"))
            .as("⭐ 同项目的另一个会话「本来就该」受影响（CC getCurrentProjectConfig 按项目）"
                + " —— ⛔ 若实现改成「按 sessionId 存」，本断言翻红（= 发明 CC 没有的判据）")
            .isTrue();
        assertThat(warningShownResolver().apply("sess-a-other"))
            .as("示警态同样按项目共享").isTrue();
    }

    /**
     * canonical 折叠的<b>写侧</b>：批准**子目录会话** ⇒ 主根会话必须受影响。
     *
     * <p>⚠️ 本用例与 {@link #sameGitRepoSubdirectory_readSide_isAffected} <b>必须成对存在</b>：
     * 写入键在 {@code resolveProjectKey}（写侧）推导、读取键在 {@code projectKeyForEngineRead}
     * （读侧）推导，<b>两侧都要 canonical 折叠</b>才共享。只断言一个方向会漏掉另一侧
     * （实测：只去掉写侧折叠时，单向断言仍绿 = 假守卫）。
     */
    @Test
    @DisplayName("[T15-3] ⭐正向（canonical 写侧）：批准子目录会话 ⇒ 主根会话受影响")
    void sameGitRepoSubdirectory_writeSide_isAffected() throws Exception {
        Path repo = createGitRepoFixture();
        Path sub = repo.resolve("sub");
        assertCanonicalCollapses(repo, sub);
        bind("sess-repo-root", repo);
        bind("sess-repo-sub", sub);

        // 批准走**子目录** ⇒ 写侧必须把它折叠到仓根
        approve("sess-repo-sub", true);

        assertThat(approvedResolver().apply("sess-repo-root"))
            .as("子目录会话的批准必须落在仓根键上（CC findCanonicalGitRoot 折叠：git.ts:123-183；"
                + "memdir/paths.ts:204）—— ⛔ 去掉写侧折叠本断言翻红")
            .isTrue();
    }

    /**
     * canonical 折叠的<b>读侧</b>：批准**主根会话** ⇒ 子目录会话必须受影响。
     *
     * <p>见 {@link #sameGitRepoSubdirectory_writeSide_isAffected} 的成对性说明。
     */
    @Test
    @DisplayName("[T15-3] ⭐正向（canonical 读侧）：批准主根会话 ⇒ 子目录会话受影响")
    void sameGitRepoSubdirectory_readSide_isAffected() throws Exception {
        Path repo = createGitRepoFixture();
        Path sub = repo.resolve("sub");
        assertCanonicalCollapses(repo, sub);
        bind("sess-repo-root", repo);
        bind("sess-repo-sub", sub);

        // 批准走**仓根** ⇒ 读侧拿到子目录会话时也必须折叠到仓根
        approve("sess-repo-root", true);

        assertThat(approvedResolver().apply("sess-repo-sub"))
            .as("子目录会话必须读到仓根键上的审批态 —— ⛔ 去掉读侧折叠本断言翻红")
            .isTrue();
    }

    /** 前提自证：两个项目根确实 canonical 到同一键（否则「共享」断言是假绿）。 */
    private void assertCanonicalCollapses(Path repo, Path sub) {
        assertThat(AutoMemPaths.findCanonicalGitRoot(repo.toString()))
            .as("常规仓（.git 为目录）⇒ canonical 根 = 本目录").isEqualTo(repo.toString());
        assertThat(AutoMemPaths.findCanonicalGitRoot(sub.toString()))
            .as("子目录 ⇒ canonical 根 = 仓根（CC findCanonicalGitRoot 折叠）").isEqualTo(repo.toString());
    }

    @Test
    @DisplayName("[T15-3] D-b-2：非 git 项目根 ⇒ 回落项目根本身（CC 显式语义 memdir/paths.ts:204）")
    void nonGitProjectRoot_fallsBackToProjectRoot() throws Exception {
        Path plain = Files.createDirectories(tempRoot.resolve("plain-a"));
        Path plainOther = Files.createDirectories(tempRoot.resolve("plain-b"));
        assertThat(AutoMemPaths.findCanonicalGitRoot(plain.toString()))
            .as("非 git 目录 ⇒ null（AutoMemPaths.findCanonicalGitRoot:926-933）").isNull();
        bind("sess-plain-a", plain);
        bind("sess-plain-b", plainOther);

        approve("sess-plain-a", true);

        assertThat(approvedResolver().apply("sess-plain-a")).as("非 git 项目根仍能分区").isTrue();
        assertThat(approvedResolver().apply("sess-plain-b"))
            .as("回落键必须仍是**本项目根**（⛔ 不得因 canonical=null 而让所有非 git 项目共用一键）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // ③ 引擎读路径缺值策略（(a) 抛 / (b) false + WARN）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[T15-3] 引擎读路径：resolutionFailure ⇒ 抛（⛔ 不得静默返回 false）")
    void engineReadPath_resolutionFailure_throws() throws Exception {
        // 回源器未接线（setDbResolver 已在 setUp 清空）⇒ lookup miss ⇒ resolutionFailure = (a) 本该有却没有
        Path project = Files.createDirectories(tempRoot.resolve("proj-rf"));
        bind("sess-rf", project);
        approve("sess-rf", true);

        assertThatThrownBy(() -> approvedResolver().apply("sess-never-seen"))
            .as("解析失败 = (a)「本该有却没有」⇒ fail loud；⛔ 静默 false 会造出「同一输入一处抛"
                + "（resolveOriginalCwd CwdResolution:280-284）一处吞」的新不一致")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[T15-3] 引擎读路径：(b) 会话未绑定 / 无此会话 ⇒ 返回 false（= CC 缺省 false config.ts:146）")
    void engineReadPath_unboundOrUnknown_returnsFalse() throws Exception {
        Path project = Files.createDirectories(tempRoot.resolve("proj-b2"));
        bind("sess-b2", project);
        approve("sess-b2", true);
        Function<String, Boolean> resolver = approvedResolver();

        SessionProjectRoot.setDbResolver(sessionId -> SessionProjectRoot.Lookup.unbound());
        assertThat(resolver.apply("sess-unbound"))
            .as("会话存在但未绑定项目根 ⇒ (b) 本就不需要 ⇒ false").isFalse();

        SessionProjectRoot.setDbResolver(sessionId -> SessionProjectRoot.Lookup.unknown());
        assertThat(resolver.apply("sess-ghost"))
            .as("无此会话 ⇒ false").isFalse();
    }

    /**
     * [T15-3] (b) 类三种原因的 WARN <b>各自可观测</b>（⛔ 不共用一个一次性闸）。
     *
     * <p><b>WHY（规则九 + 本改造计划 §九.C.3）</b>：三个原因是三种不同缺陷。共用一个
     * {@code AtomicBoolean} 时「先到者」会结构性抹掉「后到者」的可观测性 —— 只要 JVM 早期出现过一次
     * 「sessionId 为空」，此后「会话存在但无绑定项目根」（按用户裁定「每个会话一定有绑定目录」=
     * <b>真缺陷线索</b>）在日志里<b>永不出现</b>。P1a 批实证过「全进程一个 AtomicBoolean 会让消费侧
     * 告警结构上不可达」。
     *
     * <p><b>反向实验</b>：把三道闸改回共用一个 {@code AtomicBoolean} ⇒ 本用例翻红（后两条 WARN 不出现）。
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("[T15-3] ⭐(b) 类三种原因的 WARN 各自可观测（⛔ 不共用一次性闸）")
    void engineReadPath_unresolvedReasons_eachWarnedIndependently(CapturedOutput output) throws Exception {
        Path project = Files.createDirectories(tempRoot.resolve("proj-warn"));
        bind("sess-warn", project);
        approve("sess-warn", true);
        Function<String, Boolean> resolver = approvedResolver();

        // ① 先用掉「sessionId 为空」这一道闸 —— 单闸实现下这一步会抹掉后面两条
        assertThat(resolver.apply("")).isFalse();
        // ② 会话存在但无绑定（数据链路异常 = 真缺陷线索）
        SessionProjectRoot.setDbResolver(sessionId -> SessionProjectRoot.Lookup.unbound());
        assertThat(resolver.apply("sess-unbound")).isFalse();
        // ③ 无此会话
        SessionProjectRoot.setDbResolver(sessionId -> SessionProjectRoot.Lookup.unknown());
        assertThat(resolver.apply("sess-ghost")).isFalse();

        assertThat(output)
            .as("⭐ 三条原因必须各自独立可观测 —— 单闸实现下后两条不会出现")
            .contains("sessionId 为空/空白")
            .contains("会话存在但无绑定项目根/绑定失效")
            .contains("无此会话");
    }

    // ════════════════════════════════════════════════════════════════
    // REST 契约：POST /include-approval
    // ════════════════════════════════════════════════════════════════

    /** 接受 → 200 回显 true + 注册接缝（本项目键 true）+ WarningShown=true 置位 + 失效 memoize 缓存。 */
    @Test
    void includeApproval_approvedTrue_registersTrueAndClearsCache() throws Exception {
        Path project = Files.createDirectories(tempRoot.resolve("proj-post-true"));
        bind("sess-a", project);

        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON)
                        .content("{\"approved\": true, \"sessionId\": \"sess-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));

        assertThat(approvedResolver().apply("sess-a"))
            .as("approved=true 时注册接缝必须对**本项目会话**返回 true").isTrue();
        assertThat(warningShownResolver().apply("sess-a"))
            .as("审批后 WarningShown 必须为 true（不再弹窗）").isTrue();
        verify(engine).clearMemoryFileCaches();
    }

    /** 拒绝 → 200 回显 false + 本项目键 false + WarningShown=true（CC onDone 拒绝亦置位）。 */
    @Test
    void includeApproval_approvedFalse_registersFalseAndClearsCache() throws Exception {
        Path project = Files.createDirectories(tempRoot.resolve("proj-post-false"));
        bind("sess-a", project);

        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON)
                        .content("{\"approved\": false, \"sessionId\": \"sess-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));

        assertThat(approvedResolver().apply("sess-a"))
            .as("approved=false 时接缝必须返回 false").isFalse();
        assertThat(warningShownResolver().apply("sess-a"))
            .as("拒绝后 WarningShown 必须为 true（CC config.ts:123-131 拒绝亦置位）").isTrue();
        verify(engine).clearMemoryFileCaches();
    }

    /** approved 字段缺失 → 400（二值契约，拒绝隐式缺省；误传字段名不静默置 false）。 */
    @Test
    void includeApproval_missingApproved_rejected400() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{\"sessionId\": \"sess-a\"}"))
                .andExpect(status().isBadRequest());

        verify(engine, never()).setHasClaudeMdExternalIncludesApproved(any());
    }

    /** 请求体缺失 → 400（@RequestBody 必填）。 */
    @Test
    void includeApproval_missingBody_rejected400() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(engine, never()).setHasClaudeMdExternalIncludesApproved(any());
    }

    /** [T15-3] sessionId 缺失/空白 → 400（(a) 本该有却没有 ⇒ ⛔ 不回落 user.dir 冒充项目根）。 */
    @Test
    void includeApproval_missingSessionId_rejected400() throws Exception {
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{\"approved\": true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON).content("{\"approved\": true, \"sessionId\": \"  \"}"))
                .andExpect(status().isBadRequest());

        verify(engine, never()).setHasClaudeMdExternalIncludesApproved(any());
    }

    /** [T15-3] 解析不到项目根（会话存在但未绑定）→ 400。 */
    @Test
    void includeApproval_unresolvableProjectRoot_rejected400() throws Exception {
        SessionProjectRoot.setDbResolver(sessionId -> SessionProjectRoot.Lookup.unbound());

        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON)
                        .content("{\"approved\": true, \"sessionId\": \"sess-unbound\"}"))
                .andExpect(status().isBadRequest());

        verify(engine, never()).setHasClaudeMdExternalIncludesApproved(any());
    }

    /** 引擎未接线 → 500 fail loud（无静默降级，对齐 ExtractMemoriesController.resolveMemoryStorage）。 */
    @Test
    void includeApproval_engineNotWired_failLoud500() throws Exception {
        ReflectionTestUtils.setField(controller, "claudemdEngine", null);
        mockMvc.perform(post("/api/v1/claude-md/include-approval")
                        .contentType(APPLICATION_JSON)
                        .content("{\"approved\": true, \"sessionId\": \"sess-a\"}"))
                .andExpect(status().isInternalServerError());
    }

    // ════════════════════════════════════════════════════════════════
    // REST 契约：GET /include-status
    // ════════════════════════════════════════════════════════════════

    /** 无外部 include → needsApproval=false + files 空数组；verify 探测链被调（同参 sessionId）。 */
    @Test
    void includeStatus_noExternalIncludes() throws Exception {
        Path project = Files.createDirectories(tempRoot.resolve("proj-status-1"));
        bind("sess-status-1", project);
        when(engine.shouldShowClaudeMdExternalIncludesWarning("sess-status-1")).thenReturn(false);
        when(engine.getMemoryFiles(true, "sess-status-1")).thenReturn(List.of());
        when(engine.getExternalClaudeMdIncludes(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/claude-md/include-status").param("sessionId", "sess-status-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsApproval").value(false))
                .andExpect(jsonPath("$.files").isArray())
                .andExpect(jsonPath("$.files").isEmpty());

        verify(engine).shouldShowClaudeMdExternalIncludesWarning("sess-status-1");
        verify(engine).getMemoryFiles(true, "sess-status-1");
        verify(engine).getExternalClaudeMdIncludes(any(), any());
    }

    /** 存在外部 include 且未审批 → needsApproval=true + files 列出外部文件绝对路径。 */
    @Test
    void includeStatus_hasExternalUnapproved() throws Exception {
        Path project = Files.createDirectories(tempRoot.resolve("proj-status-2"));
        bind("sess-status-2", project);
        when(engine.shouldShowClaudeMdExternalIncludesWarning("sess-status-2")).thenReturn(true);
        when(engine.getMemoryFiles(true, "sess-status-2")).thenReturn(List.of());
        when(engine.getExternalClaudeMdIncludes(any(), any()))
                .thenReturn(List.of(new ClaudemdEngine.ExternalClaudeMdInclude("D:/external/team.md", "CLAUDE.md")));

        mockMvc.perform(get("/api/v1/claude-md/include-status").param("sessionId", "sess-status-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsApproval").value(true))
                .andExpect(jsonPath("$.files[0]").value("D:/external/team.md"));
    }

    /** 先 POST 批准 → 再 GET → needsApproval=false（审批态落地后前端不再弹窗）。 */
    @Test
    void includeStatus_afterApproval() throws Exception {
        Path project = Files.createDirectories(tempRoot.resolve("proj-status-3"));
        bind("sess-status-3", project);
        approve("sess-status-3", true);

        when(engine.shouldShowClaudeMdExternalIncludesWarning("sess-status-3")).thenReturn(false);
        when(engine.getMemoryFiles(true, "sess-status-3")).thenReturn(List.of());
        when(engine.getExternalClaudeMdIncludes(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/claude-md/include-status").param("sessionId", "sess-status-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsApproval").value(false));
    }

    /** [T15-3] GET 缺 sessionId → 400（推翻批 3c 的 (b) 分类：审批态现已按项目定义）。 */
    @Test
    void includeStatus_missingSessionId_rejected400() throws Exception {
        mockMvc.perform(get("/api/v1/claude-md/include-status"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/claude-md/include-status").param("sessionId", ""))
                .andExpect(status().isBadRequest());

        verify(engine, never()).shouldShowClaudeMdExternalIncludesWarning(any());
    }

    /** [T15-3] GET 解析不到项目根（无此会话）→ 400（⛔ 不回落 user.dir 冒充项目根）。 */
    @Test
    void includeStatus_unresolvableProjectRoot_rejected400() throws Exception {
        SessionProjectRoot.setDbResolver(sessionId -> SessionProjectRoot.Lookup.unknown());

        mockMvc.perform(get("/api/v1/claude-md/include-status").param("sessionId", "sess-ghost"))
                .andExpect(status().isBadRequest());

        verify(engine, never()).shouldShowClaudeMdExternalIncludesWarning(any());
    }

    /** 引擎未接线 → GET /include-status → 500 fail loud（与 include-approval 一致）。 */
    @Test
    void includeStatus_engineNotWired_failLoud500() throws Exception {
        ReflectionTestUtils.setField(controller, "claudemdEngine", null);
        mockMvc.perform(get("/api/v1/claude-md/include-status").param("sessionId", "sess-a"))
                .andExpect(status().isInternalServerError());
    }
}
