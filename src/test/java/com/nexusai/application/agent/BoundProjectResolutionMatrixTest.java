package com.nexusai.application.agent;

import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⭐ <b>[F-24 Step 3 · 判定矩阵 · 2026-09-14]</b> B′ 兜底第二链删除的<b>承重装置</b>：
 * 逐格证明「改前 {@code boolean} ↔ 改后 {@code lookup(...).projectRoot()!=null}」的关系 ——
 * 一致 / 有意不同 / 不可达，<b>三分类逐格标明</b>。
 *
 * <h2>S2 的收口第 2 步（逐字）</h2>
 * <p>「⚠️ <b>必须先跑「判定矩阵」基线</b>（(sessionId, DB 状态) 组合下改前 {@code boolean} 与改后
 * {@code lookup(...).projectRoot()!=null} 逐格一致）—— 否则「改完还绿」无法区分「等价」与
 * 「矩阵没覆盖」。」
 *
 * <h2>两阶段（本文件 = 第 2 阶段；第 1 阶段读数作为<b>常量</b>留档）</h2>
 * <ul>
 *   <li><b>第 1 阶段（改前 @ {@code cdf5bbe}）</b>：同一份矩阵的旧版逐格打印
 *       {@code OLD(B′)} 与 {@code NEW-naive(lookup)}。命令：<br>
 *       {@code mvn -o test -DargLine=-Xmx768m -Dtest=BoundProjectResolutionMatrixTest}
 *       （日志 {@code /tmp/f24s3-matrix-phase1.log}；结论 24/24 绿，读数见下表）。</li>
 *   <li><b>第 2 阶段（本文件）</b>：B′ 已删 ⇒ 改前读数<b>无法再被驱动</b>，故作为<b>文档化常量</b>
 *       （{@link #PHASE1_OLD} / {@link #PHASE1_NAIVE}）留档；活断言改为驱动<b>真实</b>
 *       {@code resolveSessionProjectRoot(String)}（= 合并后的生产方法，含调用点 1 的
 *       {@code clearSession} + 唯一链）。</li>
 * </ul>
 *
 * <h2>逐格分类（{@link Verdict}）</h2>
 * <ul>
 *   <li>{@code EQUIVALENT} —— 改前 boolean 与改后「有项目」判定<b>逐格一致</b>（含 workspaceDir
 *       同值）；</li>
 *   <li>{@code SIDE_EFFECT_ONLY} —— <b>判定一致，冻结表终态有意不同</b>（Step 3 顺带修掉的静默失效：
 *       原 B′ 查到新鲜值却因 {@code setForSession} 首写胜写不进冻结表，无效条目继续被
 *       {@code getForSession} 返回；合并后调用点 1 先 {@code clearSession} 再回源）；</li>
 *   <li>{@code DIVERGENT_INTENDED} —— <b>有意不同</b>：B′ 自带 DB 直查 ⇒ 回源器<b>不可用</b>
 *       （未接线 / 违约返回 null）时它仍能取到绑定；合并后唯一链取不到 ⇒ memory 域保持无项目
 *       （fail-soft，⛔ 不抛）。生产里二者同源（{@code sessionProjectRootResolver} 的 {@code @Bean}
 *       体就是 {@code setDbResolver} 的注册者）⇒ 该差只在「装配异常 / 夹具分叉」形态可见；</li>
 *   <li>{@code UNREACHABLE} —— 两条链<b>都到不了</b>（冻结表已命中有效值 / 显式锚分支 / 无会话守卫）
 *       ⇒ 本格不构成对「等价」的证据，<b>照实标明</b>（⛔ 不用它冒充覆盖面）。</li>
 * </ul>
 *
 * <h2>⛔ 矩阵<b>没</b>覆盖到的格子（照实列出 —— 这正是 S2 强调「区分等价与矩阵没覆盖」的理由）</h2>
 * <ol>
 *   <li><b>字节级等价</b>：已删的 {@code normalizeSessionProjectRoot} 与
 *       {@code CwdResolution.normalizeCwd} 的<b>逐输入</b>等价未穷举（实现同形 = realpath + NFC、
 *       失败回退「原文 + NFC」；读数只覆盖正斜杠与 symlink 两形态）——
 *       见 {@code ProjectRootNormalizationDivergenceExperimentTest} 读数 1/3；</li>
 *   <li><b>并发时序</b>：两线程同时 miss → 同时回源 → 首写胜的竞态终态不可确定性构造（未覆盖）；</li>
 *   <li><b>TOCTOU</b>：「回源校验通过 → 使用前目录被删」的窗口，单线程不可构造（未覆盖）；</li>
 *   <li><b>projects.path 指向文件而非目录</b>：链级未构造（判据层由
 *       {@code SessionProjectRootValidityParityTest} 覆盖该输入）；</li>
 *   <li><b>回源器抛非 Exception（Error）</b>：{@code refillFromDb} 只 catch {@code Exception}
 *       ⇒ 该格未覆盖（g12/g13 覆盖的是 RuntimeException 两种来源）；</li>
 *   <li><b>真实 Spring 装配异常形态</b>：「{@code @Bean} 未创建」只用 {@code setDbResolver(null)}
 *       模拟，未用部分上下文（缺 {@code ToolRegistrationConfig}）构造（未覆盖）；</li>
 *   <li><b>memory 域下游</b>：冻结表被清空后 {@code resolveAutoMemoryProjectRoot} 抛
 *       {@code AutoMemoryNoBoundProjectException} 的行为由既有
 *       {@code LlmAgentLoopAutoMemoryBoundGuardTest} 覆盖，本矩阵不重复覆盖该下游。</li>
 * </ol>
 *
 * <h2>RED（反向实验配方 · 本类每条断言都有鉴别力）</h2>
 * <ol>
 *   <li>把调用点 1 的 {@code SessionProjectRoot.clearSession(sessionIdStr)} 删掉 ⇒
 *       g16/g17 的 {@code frozen} 断言翻红（cache hit 把陈旧无效值原样返回）；</li>
 *   <li>把 {@code applyBoundProjectFromLookup} 换回「自带 mapper 直查」（即复活 B′）⇒
 *       g10/g14/g18 的「改后应为 false」断言翻红；</li>
 *   <li>把 {@code applyBoundProjectFromLookup} 的返回值改成恒 true ⇒ g2/g3/g7 等翻红。</li>
 * </ol>
 */
@DisplayName("[F-24 Step 3 判定矩阵] B′ 删除前后逐格关系：一致 / 有意不同 / 不可达")
class BoundProjectResolutionMatrixTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 第 1 阶段（改前 @ cdf5bbe）实测读数 —— 文档化常量
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 第 1 阶段实测的 OLD 腿（反射驱动 {@code tryResolveBoundProjectFromDb}）读数。
     * <p>产生它的命令：{@code cd backend && MAVEN_OPTS=-Xmx512m mvn -o test -DargLine=-Xmx768m
     * -DfailIfNoSpecifiedTests=false -Dtest=BoundProjectResolutionMatrixTest}
     * （第 1 阶段文件版本；日志含 {@code [MATRIX-CHAIN]}/{@code [MATRIX-SITE]} 行）。
     */
    private static final java.util.Map<String, Boolean> PHASE1_OLD = java.util.Map.ofEntries(
        java.util.Map.entry("g1", true), java.util.Map.entry("g2", false),
        java.util.Map.entry("g3", false), java.util.Map.entry("g4", false),
        java.util.Map.entry("g5", false), java.util.Map.entry("g6", false),
        java.util.Map.entry("g7", false), java.util.Map.entry("g8", false),
        java.util.Map.entry("g9", false), java.util.Map.entry("g10", true),
        java.util.Map.entry("g11", false), java.util.Map.entry("g12", false),
        java.util.Map.entry("g13", false), java.util.Map.entry("g14", true),
        java.util.Map.entry("g16", true), java.util.Map.entry("g17", false),
        java.util.Map.entry("g18", true), java.util.Map.entry("s2p", true),
        java.util.Map.entry("s3", true), java.util.Map.entry("s4", true),
        java.util.Map.entry("s5", true));

    /**
     * 第 1 阶段实测的 NEW-naive 腿（{@code lookup(sid).projectRoot()!=null}，<b>不清理陈旧冻结</b>）
     * 读数 —— ⚠️ 保留它的意义：它是「假装等价」的陷阱面（g17 被陈旧缓存骗成 true）。
     */
    private static final java.util.Map<String, Boolean> PHASE1_NAIVE = java.util.Map.ofEntries(
        java.util.Map.entry("g1", true), java.util.Map.entry("g2", false),
        java.util.Map.entry("g3", false), java.util.Map.entry("g4", false),
        java.util.Map.entry("g5", false), java.util.Map.entry("g6", false),
        java.util.Map.entry("g7", false), java.util.Map.entry("g8", false),
        java.util.Map.entry("g9", false), java.util.Map.entry("g10", false),
        java.util.Map.entry("g11", false), java.util.Map.entry("g12", false),
        java.util.Map.entry("g13", false), java.util.Map.entry("g14", false),
        java.util.Map.entry("g16", true), java.util.Map.entry("g17", true),
        java.util.Map.entry("g18", true));

    /** 逐格关系分类（见类 javadoc）。 */
    private enum Verdict { EQUIVALENT, SIDE_EFFECT_ONLY, DIVERGENT_INTENDED, UNREACHABLE }

    // ══════════════════════════════════════════════════════════════════════
    // 格的定义
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 一格。
     *
     * @param name            格名（与第 1 阶段读数表对齐；{@code g*} = 数据库/回源器/冻结表轴，
     *                        {@code s*} = 调用点轴）
     * @param sessionId       传给解析链的会话键（可 null / 空白）
     * @param dbPath          {@code projects.path}（null = 项目行缺失 / 无绑定）
     * @param mainProjectId   {@code sessions.main_project_id}（null = 会话存在但未绑定）
     * @param projectRowMissing projects 行缺失（脏绑定）
     * @param sessionRowMissing DB 无此会话行
     * @param sessionMapperThrows / @param projectMapperThrows 查询抛错
     * @param resolverState   回源器接线状态：{@link ResolverState}
     * @param overrideResolver 违约返回 null 的回源器（{@code RESOLVER_NULL} 用）
     * @param fieldResolver   注入 loop 的 {@code sessionProjectRootResolver} 字段
     *                        （{@code null} = 不注入 ⇒ 命中调用点 2）；用 {@code FIELD_*} 常量选择
     * @param staleFrozen     冻结表预置陈旧条目（先冻结有效目录再删目录）⇒ 命中调用点 1
     * @param expectWsNull    改后 {@code workspaceDir} 是否应为 null（= 改后的「有无项目」判定）
     * @param verdict         逐格关系分类
     * @param why             该格为何是这个分类（人读）
     */
    private record Cell(String name, String sessionId, String dbPath, String mainProjectId,
                        boolean projectRowMissing, boolean sessionRowMissing,
                        boolean sessionMapperThrows, boolean projectMapperThrows,
                        ResolverState resolverState, SessionProjectRoot.DbResolver overrideResolver,
                        Function<String, String> fieldResolver, boolean staleFrozen,
                        boolean expectWsNull, Verdict verdict, String why) {}

    /** 回源器（{@link SessionProjectRoot#setDbResolver}）接线状态。 */
    private enum ResolverState { PRODUCTION_BEAN, UNWIRED, RETURNS_NULL }

    // ── 调用点用的字段解析器常量 ─────────────────────────────────────────

    /** 调用点 3：resolver 字段返回 null（触发「未命中 → 回源」出口）。 */
    private static final Function<String, String> FIELD_RETURNS_NULL = sid -> null;

    /** 调用点 4：resolver 字段返回一个无效目录（触发「结果无效 → 回源」出口）。 */
    private static Function<String, String> fieldReturnsInvalid(String ghost) {
        return sid -> ghost;
    }

    /** 调用点 5：resolver 字段抛错（触发 catch → 回源出口）。 */
    private static final Function<String, String> FIELD_THROWS = sid -> {
        throw new IllegalStateException("[矩阵构造] resolver 字段抛错");
    };

    // ══════════════════════════════════════════════════════════════════════
    // DB 打桩
    // ══════════════════════════════════════════════════════════════════════

    private SessionMapper sessionMapper(Cell c) {
        SessionMapper m = mock(SessionMapper.class);
        if (c.sessionRowMissing() || c.sessionId() == null || c.sessionId().isBlank()) {
            return m;   // 未 stub ⇒ selectOneById 返回 null
        }
        if (c.sessionMapperThrows()) {
            when(m.selectOneById(ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("[矩阵构造] sessionMapper 抛错"));
            return m;
        }
        SessionRecord row = new SessionRecord();
        row.setId(c.sessionId());
        row.setMainProjectId(c.mainProjectId());
        when(m.selectOneById(c.sessionId())).thenReturn(row);
        return m;
    }

    private ProjectMapper projectMapper(Cell c) {
        ProjectMapper m = mock(ProjectMapper.class);
        if (c.projectMapperThrows()) {
            when(m.selectOneById(ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("[矩阵构造] projectMapper 抛错"));
            return m;
        }
        if (c.projectRowMissing() || c.mainProjectId() == null || c.dbPath() == null) {
            return m;   // 未 stub ⇒ null
        }
        ProjectRecord p = new ProjectRecord();
        p.setId(c.mainProjectId());
        p.setPath(c.dbPath());
        when(m.selectOneById(c.mainProjectId())).thenReturn(p);
        return m;
    }

    private void wireResolver(Cell c) {
        switch (c.resolverState()) {
            case UNWIRED -> SessionProjectRoot.setDbResolver(null);
            case RETURNS_NULL -> SessionProjectRoot.setDbResolver(c.overrideResolver());
            case PRODUCTION_BEAN -> new com.nexusai.application.agent.config.ToolRegistrationConfig()
                .sessionProjectRootResolver(sessionMapper(c), projectMapper(c));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 改后读数 = 驱动真实 resolveSessionProjectRoot（唯一入口，不重写判据）
    // ══════════════════════════════════════════════════════════════════════

    /** 造「冻结后目录被删」的陈旧条目（调用点 1 的唯一可构造入口）。 */
    private String staleFrozen(String sid) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("stale-" + sid));
        SessionProjectRoot.setForSession(sid, dir.toString());
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(SessionProjectRoot.getForSession(sid))
            .as("陈旧条目必须仍在冻结表里（否则本格退化为『无条目』= 零产点）")
            .isEqualTo(dir.toString());
        return dir.toString();
    }

    /**
     * 改后读数：反射驱动<b>真实</b> {@code resolveSessionProjectRoot(String)}（run() 入口唯一调用点，
     * 内含合并后的唯一链），返回 {@code [workspaceDir, frozen]}（null 以字符串 "null" 表示）。
     */
    private String[] readMerged(Cell c) throws Exception {
        LlmAgentLoop loop = new LlmAgentLoop(mock(LlmProviderFactory.class));
        if (c.sessionId() != null) {
            loop.setStreamContext(null, c.sessionId(), "msg-1");
        }
        if (c.fieldResolver() != null) {
            loop.setSessionProjectRootResolver(c.fieldResolver());
        }
        Method m = LlmAgentLoop.class.getDeclaredMethod("resolveSessionProjectRoot", String.class);
        m.setAccessible(true);
        m.invoke(loop, new Object[] {null});   // ⚠️ 必须包 Object[]：裸 null 会被当成「零实参」
        // 冻结表终态读法：先清回源器 ⇒ getForSession 只可能读冻结表（cache hit 短路，不回源不写）
        SessionProjectRoot.setDbResolver(null);
        String frozen = c.sessionId() == null ? null : SessionProjectRoot.getForSession(c.sessionId());
        Path ws = loop.workspaceDir();
        return new String[] {ws == null ? "null" : ws.toString(), frozen == null ? "null" : frozen};
    }

    // ══════════════════════════════════════════════════════════════════════
    // 矩阵（唯一 @TestFactory）
    // ══════════════════════════════════════════════════════════════════════

    @TestFactory
    @DisplayName("逐格：改后（驱动真实 resolveSessionProjectRoot）vs 改前读数（第 1 阶段常量）+ 分类断言")
    List<DynamicTest> matrix() throws IOException {
        List<DynamicTest> out = new ArrayList<>();
        String valid = Files.createDirectories(tempDir.resolve("proj-valid")).toRealPath().toString();
        String ghost = tempDir.resolve("proj-ghost-does-not-exist").toString();

        // ── 轴 2：DB 状态（回源器 = 生产 bean；调用点 2 形态）────────────────
        out.add(row(new Cell("g1", "sess-g1", valid, "proj-g1", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, false, Verdict.EQUIVALENT,
            "有行且有 main_project_id（path 有效）⇒ 两腿都取到该目录")));
        out.add(row(new Cell("g2", "sess-g2", null, null, false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "有行且 main_project_id 空 ⇒ 两腿都「保持无项目」")));
        out.add(row(new Cell("g3", "sess-g3", null, null, false, true, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "无此会话行 ⇒ 两腿都「保持无项目」")));
        out.add(row(new Cell("g4", "inbound-mcp-7f3a", null, null, false, true, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "合成 id（MCP 入站 / 文档更新器现造 id）⇒ 两腿都「保持无项目」")));
        out.add(row(new Cell("g5", null, null, null, false, true, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "sessionId=null（漏传）⇒ 两腿都「保持无项目」")));
        out.add(row(new Cell("g6", "   ", null, null, false, true, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "sessionId 空白 ⇒ 两腿都「保持无项目」")));
        out.add(row(new Cell("g7", "sess-g7", ghost, "proj-g7", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "有绑定但 path 指向不存在目录 ⇒ 判据同源 ⇒ 两腿都拒绝")));
        out.add(row(new Cell("g8", "sess-g8", "relative/path", "proj-g8", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "有绑定但 path 是相对路径（脏数据形态）⇒ 两腿都拒绝")));
        out.add(row(new Cell("g9", "sess-g9", null, "proj-g9-missing", true, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "有绑定但 projects 行缺失（脏绑定）⇒ 两腿都拒绝")));

        // ── 轴 3：回源器状态 ────────────────────────────────────────────
        // 生产形态：字段未注入 ⟺ 回源器未接线（同一个 @Bean）⇒ g10 是**生产可达**的有意差
        out.add(row(new Cell("g10", "sess-g10", valid, "proj-g10", false, false, false, false,
            ResolverState.UNWIRED, null, null, false, true, Verdict.DIVERGENT_INTENDED,
            "回源器未接线（= @Bean 未创建）⇒ 改前 B′ 用 loop 自有 mapper 仍取到绑定（true），"
                + "改后唯一链无法判定 ⇒ 保持无项目（false）。生产里二者同源，故只见于装配异常")));
        out.add(row(new Cell("g11", "sess-g11", valid, "proj-g11", false, false, false, false,
            ResolverState.UNWIRED, null, null, false, true, Verdict.EQUIVALENT,
            "回源器未接线 + loop mapper 未注入（POJO 单测形态）⇒ 改前 B′ 也取不到 ⇒ 两腿都无项目")));
        out.add(row(new Cell("g12", "sess-g12", null, null, false, false, true, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "sessionMapper 抛错（Lookup 闭包内 catch ⇒ unknown）⇒ 两腿都无项目")));
        out.add(row(new Cell("g13", "sess-g13", valid, "proj-g13", false, false, false, true,
            ResolverState.PRODUCTION_BEAN, null, null, false, true, Verdict.EQUIVALENT,
            "projectMapper 抛错（Lookup 闭包无 catch 点 ⇒ 解析失败）⇒ 两腿都无项目")));
        out.add(row(new Cell("g14", "sess-g14", valid, "proj-g14", false, false, false, false,
            ResolverState.RETURNS_NULL, sid -> null, null, false, true, Verdict.DIVERGENT_INTENDED,
            "回源器违约返回 null ⇒ 改前 B′ 绕过它自取绑定（true），改后记「解析失败」⇒ 保持无项目（false）")));

        // ── 轴 4：冻结表状态（调用点 1 入口）────────────────────────────
        out.add(row(new Cell("g16", "sess-g16", valid, "proj-g16", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, true, false, Verdict.SIDE_EFFECT_ONLY,
            "陈旧冻结条目 + DB 有有效绑定 ⇒ 判定同为「有项目」；但改前冻结表**仍留陈旧无效值**"
                + "（B′ 的 setForSession 被首写胜拒绝），改后先 clearSession ⇒ 冻结表 = 新鲜有效值")));
        out.add(row(new Cell("g17", "sess-g17", null, null, false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, true, true, Verdict.SIDE_EFFECT_ONLY,
            "陈旧冻结条目 + DB 无绑定 ⇒ 判定同为「无项目」；改前冻结表仍留无效值（会被 memory 域读到！），"
                + "改后条目被清除")));
        out.add(row(new Cell("g18", "sess-g18", valid, "proj-g18", false, false, false, false,
            ResolverState.UNWIRED, null, null, true, true, Verdict.DIVERGENT_INTENDED,
            "陈旧冻结条目 + 回源器未接线 + mapper 可用 ⇒ 改前 true（B′ 直查），改后 false"
                + "（clearSession 后唯一链无法判定）—— 与 g10 同根因")));

        // ── 轴 1+调用点：5 个调用点各驱动一次（含 g10 的对照/控制格）────────
        out.add(row(new Cell("s2p", "sess-s2p", valid, "proj-s2p", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, null, false, false, Verdict.EQUIVALENT,
            "调用点 2'：字段未注入但回源器<已>接线（仅夹具可造）⇒ 改前 B′ 取到，改后唯一链也取到 "
                + "⇒ 证明 g10 的差异**只**来自回源器未接线，与调用点无关")));
        out.add(row(new Cell("s3", "sess-s3", valid, "proj-s3", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, FIELD_RETURNS_NULL, false, false, Verdict.EQUIVALENT,
            "调用点 3：resolver 字段返回 null ⇒ 回源出口 ⇒ 两腿都取到同一 DB 值")));
        out.add(row(new Cell("s4", "sess-s4", valid, "proj-s4", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, fieldReturnsInvalid(ghost), false, false, Verdict.EQUIVALENT,
            "调用点 4：resolver 字段返回无效目录 ⇒ 回源出口 ⇒ 两腿都取到同一 DB 值")));
        out.add(row(new Cell("s5", "sess-s5", valid, "proj-s5", false, false, false, false,
            ResolverState.PRODUCTION_BEAN, null, FIELD_THROWS, false, false, Verdict.EQUIVALENT,
            "调用点 5：resolver 字段抛错 ⇒ catch → 回源出口 ⇒ 两腿都取到同一 DB 值")));

        return out;
    }

    /** 逐格：装配 → 预置陈旧冻结 → 驱动真实方法 → 断言改后判定 + 分类 + 冻结表终态。 */
    private DynamicTest row(Cell c) {
        return DynamicTest.dynamicTest(c.name() + " · " + c.sessionId(), () -> {
            String stale = c.staleFrozen() ? staleFrozen(c.sessionId()) : null;
            wireResolver(c);
            String[] merged = readMerged(c);
            boolean mergedOk = !"null".equals(merged[0]);

            System.out.println("[MATRIX] " + pad(c.name()) + " mergedWs=" + merged[0]
                + " mergedFrozen=" + merged[1] + " | phase1Old=" + PHASE1_OLD.getOrDefault(c.name(), null)
                + " phase1Naive=" + PHASE1_NAIVE.getOrDefault(c.name(), null)
                + " | " + c.verdict() + "  <- [" + c.why() + "]");

            assertThat(mergedOk)
                .as("改后「有项目」判定（workspaceDir 是否被赋值）· %s", c.why())
                .isEqualTo(!c.expectWsNull());
            assertThat(merged[0].equals("null") == c.expectWsNull())
                .as("workspaceDir 读数与 expectWsNull 口径一致（%s）", c.why()).isTrue();

            // 分类断言：等价/意图差必须与第 1 阶段 OLD 常量对得上（⛔ 不含糊）
            Boolean old = PHASE1_OLD.get(c.name());
            switch (c.verdict()) {
                case EQUIVALENT -> assertThat(mergedOk)
                    .as("EQUIVALENT 格：改后判定必须等于第 1 阶段 OLD 读数（%s）", c.name())
                    .isEqualTo(old);
                case DIVERGENT_INTENDED -> assertThat(mergedOk)
                    .as("DIVERGENT_INTENDED 格：改后判定必须**相反**于第 1 阶段 OLD 读数（%s）", c.name())
                    .isNotEqualTo(old);
                case SIDE_EFFECT_ONLY -> assertThat(mergedOk)
                    .as("SIDE_EFFECT_ONLY 格：改后判定必须等于第 1 阶段 OLD 读数（差异只在冻结表终态）")
                    .isEqualTo(old);
                default -> throw new IllegalStateException("本表不应出现 " + c.verdict());
            }

            // 冻结表终态：改后必须**没有**陈旧无效值残留（Step 3 顺带修掉的静默失效）
            if (stale != null) {
                assertThat(merged[1])
                    .as("⭐ 改后冻结表<b>不得</b>残留陈旧无效值（改前 B′ 因首写胜写不进去 ⇒ 残留）")
                    .isNotEqualTo(stale);
            }
        });
    }

    private static String pad(String s) {
        return s.length() >= 6 ? s : s + " ".repeat(6 - s.length());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 「两条链都到不了」的三格 —— ⛔ 它们不是等价证据，照实标明（UNREACHABLE）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * UNREACHABLE 三格：<b>改前 B′ 与改后唯一链都不会被触达</b> ⇒ 这三格对「等价」问题
     * <b>零信息量</b>，必须与 EQUIVALENT 格分开计（S2 点名的「区分等价与矩阵没覆盖」）。
     *
     * <p><b>判据是「真的没被触达」而不是「看起来提前 return 了」</b>：两枚计数装置 ——
     * ① DB 查询计数（SessionMapper + ProjectMapper 的 {@code selectOneById}）；
     * ② resolver 字段调用计数。⛔ 二者都恒 0 才算「不触达」。
     *
     * <p><b>RED（逐格有鉴别力 · 均为实测）</b>：
     * <ul>
     *   <li>g15：把冻结命中分支的短路去掉（{@code getForSession} 恒返回 null）⇒ 走回源 ⇒
     *       DB 计数 &gt; 0 ⇒ <b>实测红</b>；</li>
     *   <li>g19：把显式锚分支挪到 {@code streamSessionId} 守卫<b>之后</b> ⇒ 守卫提前 return ⇒
     *       {@code ws=null} ⇒ <b>实测红</b>（⚠️ 本格刻意<b>不设</b> streamSessionId —— 首版误设了它，
     *       结果该变异仍绿：设了会话 id 时守卫不触发，锚分支挪后照样执行 ⇒ 那条「计数 &gt; 0」的
     *       声称是<b>假守卫</b>，已按实测改正）；</li>
     *   <li>g20：删掉 {@code streamSessionId == null} 守卫 ⇒ 走会话路径（resolver 字段被调用、
     *       且返回有效目录 ⇒ {@code ws} 非 null）⇒ <b>实测红</b>。</li>
     * </ul>
     * <p>⛔ <b>本面不覆盖</b>的变体：把锚检查挪到「会话解析<b>之后</b>」（而非 null 守卫之后）——
     * 那种错位本面检测不到（锚值最终仍会覆盖 ws）。该优先级的既有守卫是
     * {@code LlmAgentLoopSessionProjectRootFreezeTest} / {@code AutoMemoryCronAnchorPlumbingTest} /
     * {@code LlmAgentLoopRunBoundProjectWiringTest}（各自的 RED 配方见其类 javadoc）。
     */
    @org.junit.jupiter.api.Test
    @DisplayName("UNREACHABLE 三格：冻结表已命中 / 显式锚 / 无会话 ⇒ 两链都不触达（DB 查询=0 且 resolver 字段调用=0）")
    void unreachableCells_bothChainsAreSkipped() throws Exception {
        java.util.concurrent.atomic.AtomicInteger dbQueries = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger fieldCalls = new java.util.concurrent.atomic.AtomicInteger();
        String sid = "sess-unreach";
        String valid = Files.createDirectories(tempDir.resolve("unreach-proj")).toRealPath().toString();

        // ── g15：冻结表已有**有效**条目 ⇒ 冻结命中分支提前 return（B′ 与唯一链都到不了）──
        Path frozenDir = Files.createDirectories(tempDir.resolve("frozen-valid")).toRealPath();
        SessionProjectRoot.reset();
        // 接上**计数**回源器：若冻结命中短路被去掉，计数立刻 > 0 ⇒ 本格可检出
        new com.nexusai.application.agent.config.ToolRegistrationConfig()
            .sessionProjectRootResolver(countingSessionMapper(sid, "proj-unreach", dbQueries),
                countingProjectMapper("proj-unreach", valid, dbQueries));
        SessionProjectRoot.setForSession(sid, frozenDir.toString());

        LlmAgentLoop loopG15 = new LlmAgentLoop(mock(LlmProviderFactory.class));
        loopG15.setStreamContext(null, sid, "msg-1");
        loopG15.setSessionProjectRootResolver(fieldCounting(fieldCalls, valid));
        invokeResolve(loopG15, null);
        System.out.println("[MATRIX] g15  mergedWs=" + loopG15.workspaceDir()
            + " dbQueries=" + dbQueries.get() + " fieldCalls=" + fieldCalls.get());
        assertThat(loopG15.workspaceDir())
            .as("g15：冻结表有效值直接复用（workspaceDir = 归一后的冻结值）").isEqualTo(frozenDir);
        assertThat(dbQueries.get()).as("g15：不得查 DB（冻结命中短路）").isZero();
        assertThat(fieldCalls.get()).as("g15：resolver 字段也不得被调用（F1 冻结优先）").isZero();

        // ── g19：显式项目锚非空 ⇒ 锚分支在 sessionId 守卫**之前** return ──
        Path anchor = Files.createDirectories(tempDir.resolve("anchor-unreach")).toRealPath();
        SessionProjectRoot.reset();
        dbQueries.set(0);
        fieldCalls.set(0);
        new com.nexusai.application.agent.config.ToolRegistrationConfig()
            .sessionProjectRootResolver(countingSessionMapper(sid, "proj-unreach", dbQueries),
                countingProjectMapper("proj-unreach", valid, dbQueries));
        LlmAgentLoop loopG19 = new LlmAgentLoop(mock(LlmProviderFactory.class));
        // ⚠️ 刻意**不设** streamSessionId：生产形态 = cron DURABLE fire 的创建会话已关（headless）。
        //   这正是「锚检查必须在 null 守卫之前」的检出形态 —— 实测：把锚分支挪到守卫之后 ⇒
        //   ws=null（不设 streamSessionId 时守卫直接 return）⇒ 本格红（M5 变异实测）。
        loopG19.setSessionProjectRootResolver(fieldCounting(fieldCalls, valid));
        invokeResolve(loopG19, anchor.toString());
        System.out.println("[MATRIX] g19  mergedWs=" + loopG19.workspaceDir()
            + " dbQueries=" + dbQueries.get() + " fieldCalls=" + fieldCalls.get());
        assertThat(loopG19.workspaceDir()).as("g19：显式锚直接落 workspaceDir").isEqualTo(anchor);
        assertThat(dbQueries.get()).as("g19：锚分支不查 DB").isZero();
        assertThat(fieldCalls.get()).as("g19：锚分支不调用 resolver 字段").isZero();

        // ── g20：无 streamSessionId 且无锚 ⇒ null 守卫提前 return ──
        SessionProjectRoot.reset();
        dbQueries.set(0);
        fieldCalls.set(0);
        new com.nexusai.application.agent.config.ToolRegistrationConfig()
            .sessionProjectRootResolver(countingSessionMapper(sid, "proj-unreach", dbQueries),
                countingProjectMapper("proj-unreach", valid, dbQueries));
        LlmAgentLoop loopG20 = new LlmAgentLoop(mock(LlmProviderFactory.class));
        loopG20.setSessionProjectRootResolver(fieldCounting(fieldCalls, valid));
        invokeResolve(loopG20, null);
        System.out.println("[MATRIX] g20  mergedWs=" + loopG20.workspaceDir()
            + " dbQueries=" + dbQueries.get() + " fieldCalls=" + fieldCalls.get());
        assertThat(loopG20.workspaceDir()).as("g20：无会话无锚 ⇒ 保持无项目（不回落 configHome）").isNull();
        assertThat(fieldCalls.get()).as("g20：null 守卫必须先于 resolver 调用").isZero();
        assertThat(dbQueries.get()).as("g20：不得查 DB").isZero();
    }

    /** resolver 字段桩：计数 + 返回固定值（用来证明「字段没被触达」）。 */
    private static Function<String, String> fieldCounting(
            java.util.concurrent.atomic.AtomicInteger counter, String value) {
        return sid -> {
            counter.incrementAndGet();
            return value;
        };
    }

    private static SessionMapper countingSessionMapper(String sid, String mainProjectId,
                                                      java.util.concurrent.atomic.AtomicInteger counter) {
        SessionMapper m = mock(SessionMapper.class);
        when(m.selectOneById(ArgumentMatchers.anyString())).thenAnswer(inv -> {
            counter.incrementAndGet();
            SessionRecord s = new SessionRecord();
            s.setId(sid);
            s.setMainProjectId(mainProjectId);
            return s;
        });
        return m;
    }

    private static ProjectMapper countingProjectMapper(String pid, String path,
                                                      java.util.concurrent.atomic.AtomicInteger counter) {
        ProjectMapper m = mock(ProjectMapper.class);
        when(m.selectOneById(ArgumentMatchers.anyString())).thenAnswer(inv -> {
            counter.incrementAndGet();
            ProjectRecord p = new ProjectRecord();
            p.setId(pid);
            p.setPath(path);
            return p;
        });
        return m;
    }

    private static void invokeResolve(LlmAgentLoop loop, String anchor) throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod("resolveSessionProjectRoot", String.class);
        m.setAccessible(true);
        m.invoke(loop, anchor);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 结构门：第二链的「管道」不得复活
    // ══════════════════════════════════════════════════════════════════════

    /**
     * <b>「合并成一个」的结构门</b>：{@code LlmAgentLoop} 侧不得再有第二链的管道 ——
     * ① 无 {@code tryResolveBoundProjectFromDb} 方法；② 无 {@code projectMapper} 字段与其 setter。
     *
     * <p><b>WHY</b>：DB 查询逻辑的唯一位置 = {@code ToolRegistrationConfig#sessionProjectRootResolver}
     * （经 {@code SessionProjectRoot.setDbResolver} 注册）。若有人为了「兜底」把 mapper 注入点加回来，
     * 就等于为第二条链铺路 —— 本门把这一步变成红灯（本仓前科：删了载体但管道被悄悄加回，
     * 见 {@code DeadSymbolReferenceGuardTest}）。
     *
     * <p><b>RED（反向实验）</b>：把 {@code private ProjectMapper projectMapper} 字段或
     * {@code setProjectMapper} setter 加回 {@code LlmAgentLoop}（或把
     * {@code tryResolveBoundProjectFromDb} 方法加回）⇒ 本用例红。
     *
     * <p><b>⛔ 本门守不住（照实声明）</b>：它只认<b>这三个名字</b>。若有人用别的名字/别的类再写一条
     * DB 直查链，本门<b>无感</b>（那要靠 code review / {@code SessionProjectRoot} 的 DbResolver
     * 单点接线约定）。它守的是「已删载体的管道不得以原名复活」，不是「全仓只有一处查询」的完整证明。
     */
    @org.junit.jupiter.api.Test
    @DisplayName("结构门：B′ 的管道（方法 / projectMapper 字段 / setter）不得复活")
    void secondChainPlumbing_mustStayDeleted() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            LlmAgentLoop.class.getDeclaredMethod("tryResolveBoundProjectFromDb", String.class)))
            .as("B′ 方法不得复活（唯一链 = SessionProjectRoot.lookup）")
            .isInstanceOf(NoSuchMethodException.class);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            LlmAgentLoop.class.getDeclaredField("projectMapper")))
            .as("projectMapper 字段不得复活（它只服务被删的 B′ 链）")
            .isInstanceOf(NoSuchFieldException.class);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            LlmAgentLoop.class.getDeclaredMethod(
                "setProjectMapper", com.nexusai.repository.project.mapper.ProjectMapper.class)))
            .as("setProjectMapper 注入点不得复活（否则等于为第二条链铺路）")
            .isInstanceOf(NoSuchMethodException.class);
    }
}
