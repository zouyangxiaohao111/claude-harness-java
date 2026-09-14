package com.nexusai.application.agent.config;

import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [IMP-H · F11 · M-23] ToolRegistrationConfig.sessionProjectRootResolver 解析链专属测试
 * （ODF-A1-REF findings#1 登记：DB 解析链行为未覆盖）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：resolver bean 是 Web 后端唯一的
 * per-session 项目目录概念（sessionId → SessionMapper.selectOneById → mainProjectId →
 * ProjectMapper.selectOneById → path），LlmAgentLoop run() 入口冻结 workspaceDir 与
 * ChatService resume worktree 恢复共用。此前无专属测试，解析链行为（含失败分支）未锁定。
 * 本测试直接以 mock mapper 调用 bean 方法（同 ToolRegistrationConfigMemoryBeansTest
 * 直调先例），覆盖：
 * <ol>
 *   <li>happy path：sessionId → mainProjectId → project.path 完整解析</li>
 *   <li>查询失败（SessionMapper.selectOneById 抛异常）→ null（LlmAgentLoop 回落默认）</li>
 *   <li>会话不存在 / 会话未绑定项目（mainProjectId null）→ null</li>
 *   <li>项目不存在 / 项目无 path / path 空白 → null</li>
 *   <li>null / blank sessionId → null 且不触发任何 DB 查询</li>
 *   <li>+ [S2 · F-24-merge Step 2 2026-09-14] 归一化契约：命中的 path 经 realpath+NFC 归一后返回；
 *       归一化后目录无效（不存在）⇒ null（=「有会话但绑定失效」）。原 happy path 夹具用了不存在的
 *       盘符（违反 {@code ProjectService} 落库校验），已改夹具（见该用例 javadoc）。</li>
 * </ol>
 */
@DisplayName("[IMP-H F11 M-23] sessionProjectRootResolver 解析链（SessionMapper→ProjectMapper）")
class ToolRegistrationConfigSessionProjectRootResolverTest {

    private final ToolRegistrationConfig config = new ToolRegistrationConfig();

    private Function<String, String> resolver(SessionMapper sessionMapper, ProjectMapper projectMapper) {
        return config.sessionProjectRootResolver(sessionMapper, projectMapper);
    }

    private static SessionRecord session(String id, String mainProjectId) {
        SessionRecord rec = new SessionRecord();
        rec.setId(id);
        rec.setMainProjectId(mainProjectId);
        return rec;
    }

    private static ProjectRecord project(String id, String path) {
        ProjectRecord rec = new ProjectRecord();
        rec.setId(id);
        rec.setPath(path);
        return rec;
    }

    /**
     * happy path：会话 → 项目 → 路径的完整解析链。
     *
     * <p><b>[S2 · F-24-merge Step 2 2026-09-14 夹具修正 —— 原夹具断言了错的行为]</b>
     * 原夹具用 {@code "F:/workspace/alpha"}（本机不存在的盘符）当「happy path」，并断言
     * resolver 原样返回它。但那<b>违反生产不变量</b>：{@code ProjectService.normalizeProjectPath}
     * 只在 {@code Files.isDirectory(abs)} 通过时才落库 ⇒ <b>真实的 {@code projects.path} 必然指向
     * 存在的目录</b>，「DB 里有不存在的 path」在生产不可达。
     * <p>Step 2 起 resolver 在返回前做 realpath + NFC 归一 + 用同一判据校验（差异 B 消除，见
     * {@code ProjectRootNormalizationDivergenceExperimentTest}）⇒ 不存在的路径按「有会话但绑定失效」
     * 返回 null（= 正确的生产语义）。故本用例改为用<b>真实存在的 @TempDir</b> 作夹具，并断言返回
     * <b>归一化后</b>的值（= resolver 的新契约），同时保留原有意图：<b>查询链</b>（两个 mapper 的
     * 调用与入参）与三态入口不变。
     */
    @Test
    @DisplayName("happy path: sessionId → SessionMapper.selectOneById → mainProjectId → ProjectMapper.selectOneById → path（返回归一值）")
    void resolvesFullChainToProjectPath(@TempDir Path projectDir) throws Exception {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        // ⚠️ 夹具必须是真实存在的目录（见 javadoc）；用正斜杠形态模拟 ProjectService 的存储形态
        String dbPath = projectDir.toString().replace('\\', '/');
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-9"));
        when(projectMapper.selectOneById("proj-9")).thenReturn(project("proj-9", dbPath));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1"))
            .as("解析链成立；返回值 = realpath+NFC 归一形态（Step 2 契约）")
            .isEqualTo(projectDir.toRealPath().toString());
        verify(sessionMapper).selectOneById("sess-1");
        verify(projectMapper).selectOneById("proj-9");
    }

    @Test
    @DisplayName("SessionMapper.selectOneById 查询失败（DB 异常）→ null（回落，不向上抛）")
    void sessionQueryFailure_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(any())).thenThrow(new RuntimeException("db down"));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("会话不存在（selectOneById → null）→ null")
    void sessionNotFound_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-ghost")).isNull();
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("会话未绑定项目（mainProjectId null）→ null")
    void sessionWithoutMainProject_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", null));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("项目不存在（selectOneById → null）→ null")
    void projectNotFound_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-ghost"));
        when(projectMapper.selectOneById("proj-ghost")).thenReturn(null);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
    }

    @Test
    @DisplayName("项目无 path（null）→ null")
    void projectWithoutPath_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-9"));
        when(projectMapper.selectOneById("proj-9")).thenReturn(project("proj-9", null));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
    }

    @Test
    @DisplayName("项目 path 空白（blank）→ null")
    void projectBlankPath_returnsNull() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session("sess-1", "proj-9"));
        when(projectMapper.selectOneById("proj-9")).thenReturn(project("proj-9", "   "));

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("sess-1")).isNull();
    }

    @Test
    @DisplayName("sessionId null → null 且不触发任何 DB 查询")
    void nullSessionId_returnsNullWithoutDbAccess() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply(null)).isNull();
        verify(sessionMapper, never()).selectOneById(any());
        verify(projectMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("sessionId blank → null 且不触发任何 DB 查询")
    void blankSessionId_returnsNullWithoutDbAccess() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);

        Function<String, String> fn = resolver(sessionMapper, projectMapper);

        assertThat(fn.apply("  ")).isNull();
        verify(sessionMapper, never()).selectOneById(any());
        verify(projectMapper, never()).selectOneById(any());
    }

    // ═════════ [cwd3 步骤 2] Lookup 四态产出（「两处旁路」拆分）═════════
    //
    // WHY（规则九 · 意图）：上面的用例只看 String 面（旧消费方契约，仍必须恒 null），
    //   **看不到** Lookup 面 —— 而步骤 2 起 cwd 域的 fail-loud 判据全在 Lookup 的第 3/4/5 个
    //   标记上。⇒ 必须把「DB 答了什么」与「没查 DB」两种「null」在 Lookup 面上分开钉住。
    //
    // ⚠️ 照实声明（规则十二）：回源器里的**哨兵分支**经公开路径当前**不可达** ——
    //   SessionProjectRoot.lookup 自己就把哨兵短路了（见该类方法注释）⇒ 该分支没有任何测试
    //   鉴别力，只是「若将来有人绕过 lookup 直接用 DbResolver」的防御。⛔ 不得声称它被守护；
    //   下面最后一条用例把「不可达」这个事实本身钉住，防止将来被误当成已覆盖。

    @AfterEach
    void clearResolverSlot() {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    @Test
    @DisplayName("[cwd3 2a-3] 空白 sessionId ⇒ sessionless（⛔ 不是 unknown：本支没查过 DB）且不查 DB")
    void blankSessionId_isSessionlessWithoutDbQuery() {
        // RED（RE-2a-2）：把回源器首支改回 Lookup.unknown() ⇒ 本用例红。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        resolver(sessionMapper, projectMapper);   // 注册进 SessionProjectRoot（@Bean 的副作用）

        SessionProjectRoot.Lookup lk = SessionProjectRoot.lookup("   ");
        assertThat(lk.sessionless())
            .as("[2a-3] 空白 = 本环境确无会话（⛔ 不是 unknown —— 本支没查过 DB）").isTrue();
        assertThat(lk)
            .as("与 unknown() 严格可分（这是加第 5 态的全部理由）")
            .isNotEqualTo(SessionProjectRoot.Lookup.unknown());
        verify(sessionMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("[cwd3 2a-5] DB 查询抛错 ⇒ resolutionFailure（『没答』≠『答了没有』）")
    void dbThrowing_isResolutionFailure() {
        // RED（RE-2a-3）：把 catch 分支改回 Lookup.unknown() ⇒ 本用例红。
        // ⚠️ 此支是**可用性变化**：一次瞬时 DB 抖动 ⇒ cwd 域全线 fail-loud（不再静默回落 user.dir）。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(any())).thenThrow(new RuntimeException("db down"));
        resolver(sessionMapper, projectMapper);

        SessionProjectRoot.Lookup lk = SessionProjectRoot.lookup("sess-db-down");
        assertThat(lk.resolutionFailed())
            .as("[2a-5] DB 抛错 = 无法判定 ⇒ cwd 域 fail-loud").isTrue();
        assertThat(lk)
            .as("必须与 unknown() / sessionless() 都可分（resolutionFailure 独立字段的全部理由）")
            .isNotEqualTo(SessionProjectRoot.Lookup.unknown())
            .isNotEqualTo(SessionProjectRoot.Lookup.sessionlessEnvironment());
    }

    @Test
    @DisplayName("[cwd3 2a-4] DB 明确答「无此会话」⇒ 保持 unknown（⛔ 不得改成 sessionless）")
    void dbAnsweredNoRow_isUnknown() {
        // RED：把本支改成 sessionless ⇒ 本用例红（「本该有会话却没有」的信号被重新抹平）。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);
        resolver(sessionMapper, projectMapper);

        SessionProjectRoot.Lookup lk = SessionProjectRoot.lookup("sess-real-missing");
        assertThat(lk.sessionless())
            .as("[2a-4] DB 答了「没有这一行」⇒ ⛔ 不是 sessionless（那是『本来就不该有会话』）").isFalse();
        assertThat(lk.sessionKnown()).isFalse();
        assertThat(lk.resolutionFailed()).isFalse();
        assertThat(lk).as("[2a-4] 保持 unknown()").isEqualTo(SessionProjectRoot.Lookup.unknown());
    }

    @Test
    @DisplayName("[cwd3 2a-3 覆盖声明] 回源器的哨兵分支经公开路径不可达 ⇒ 无鉴别力，照实登记")
    void sentinelArmIsUnreachableThroughPublicApi() {
        // WHY 本用例存在：防止将来有人声称「哨兵分支有测试守护」。这里把事实钉住 ——
        //   lookup(NO_SESSION) 走的是 SessionProjectRoot 自己的短路，**根本不会**调到回源器。
        SessionMapper sessionMapper = mock(SessionMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);
        resolver(sessionMapper, projectMapper);

        assertThat(SessionProjectRoot.lookup(SessionKeys.NO_SESSION).sessionless()).isTrue();
        verify(sessionMapper, never()).selectOneById(any());   // 短路在 lookup 内，不在回源器内
    }
}
