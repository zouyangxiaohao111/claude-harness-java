package com.nexusai.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ODF-A1/IMP-B] SessionProjectRoot 会话级 projectRoot 载体 · 对齐 CC bootstrap/state.ts per-session projectRoot。
 *
 * <p>WHY (规则九 · 测试验证意图): CC 在启动时 realpath(cwd) 冻结为 projectRoot（state.ts:45-50 stable
 * projectRoot 注释 + :269-279），且会话中不再更新（state.ts:511-513 getProjectRoot 稳定；:523-525
 * setProjectRoot 仅 --worktree 启动时）。旧 Java 后端 memory 路径链恒读 {@code System.getProperty("user.dir")}
 * 单例 → 同一 JVM 内不同 cwd 会话解析到同一 memory 目录（跨项目记忆污染）。SessionProjectRoot 按 sessionId
 * 登记会话级 projectRoot，使 AutoMemPaths/AgentMemoryDirectory/LlmAgentLoop workspaceDir 生产链可解析到
 * 当前会话的 projectRoot。
 *
 * <p><b>[TL-W2 P11] 验证意图改写</b>：旧用例锁定 {@code resolve()}/{@code setCurrent()}（含
 * 「current 覆盖 session」「无注入无会话 → env ?? config home 回落链」）。该 API 生产 0 调用方
 * （全仓仅本测试引用），且 configHome 回落把「无会话」当「会话绑定项目」身份返回（违 cwd 身份域
 * 红线 D-1）⇒ 按死代码决策规则删除。本测试现锁定**唯一读法**
 * {@link SessionProjectRoot#getForSession(String)}：按 sessionId 直查、未登记 → null（<b>绝不</b>
 * 回落 env/config home）。
 * RED: 若在 getForSession 内加回 configHome/env 回落（或恢复 resolve() 主链）→
 * 「未登记 → null」断言变红。
 */
@DisplayName("[ODF-A1] SessionProjectRoot 会话级 projectRoot 载体")
class SessionProjectRootTest {

    /** 绑定用临时目录（setForSession 校验绝对路径且目录存在，/cwd/xxx 假路径不再合法）。 */
    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        RequestContext.clear();
    }

    /** 创建绑定目录并返回绝对路径（满足 setForSession 绝对+目录存在校验）。 */
    private String bindDir(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.createDirectories(p);
        return p.toString();
    }

    @Test
    @DisplayName("per-session: 不同 sessionId 绑定不同 projectRoot，按 sessionId 直查互不污染 (state.ts per-session projectRoot)")
    void sessionBound_projectRootsIsolated() throws IOException {
        // WHY: CC 每会话冻结自己的 projectRoot（state.ts:45-50/:269-279），会话间不得互相覆盖。
        //       按 sessionId 直查后，不同会话必须各自解析到自己的 projectRoot
        //       （[TL-W2 P11] 不再经 RequestContext/MDC 间接解析 —— 消费方持 sessionId 现算）。
        String pa = bindDir("project-a");
        String pb = bindDir("project-b");
        SessionProjectRoot.setForSession("sess-a", pa);
        SessionProjectRoot.setForSession("sess-b", pb);

        assertThat(SessionProjectRoot.getForSession("sess-a"))
            .as("会话 A 必须解析到其绑定的 projectRoot")
            .isEqualTo(pa);
        assertThat(SessionProjectRoot.getForSession("sess-b"))
            .as("会话 B 必须解析到其绑定的 projectRoot（会话间隔离）")
            .isEqualTo(pb);
    }

    @Test
    @DisplayName("setForSession 首写胜：rebind 不覆盖已冻结值（CC stable identity · OPD-SPR-03）")
    void setForSession_firstWriteWins() throws IOException {
        // WHY: CC projectRoot 启动冻结一次、会话内不更新（state.ts:45-50 stable projectRoot；
        //      getProjectRoot state.ts:511-513）；OPD-SPR-03 裁决 rebind 不覆盖已冻结值。
        String pa = bindDir("project-a");
        String pb = bindDir("project-b");
        SessionProjectRoot.setForSession("sess-a", pa);
        SessionProjectRoot.setForSession("sess-a", pb); // rebind 尝试 → 不得覆盖

        assertThat(SessionProjectRoot.getForSession("sess-a"))
            .as("已冻结会话绑定不得被 rebind 覆盖")
            .isEqualTo(pa);
        assertThat(SessionProjectRoot.getForSession("unknown-session"))
            .as("未登记会话 → null（getForSession 允许；绝不回落 env/config home）")
            .isNull();
    }

    @Test
    @DisplayName("clearSession 后冻结解除：可再绑定新 projectRoot（OPD-SPR-03 unbind 语义）")
    void clearSession_allowsRebind() throws IOException {
        // WHY: OPD-SPR-03 —— unbind 清空后首写重新生效（冻结仅限会话生命周期内）。
        String pa = bindDir("project-a");
        String pb = bindDir("project-b");
        SessionProjectRoot.setForSession("sess-a", pa);
        SessionProjectRoot.clearSession("sess-a");
        SessionProjectRoot.setForSession("sess-a", pb);

        assertThat(SessionProjectRoot.getForSession("sess-a"))
            .as("clearSession 后重新绑定生效")
            .isEqualTo(pb);
    }

    @Test
    @DisplayName("[TL-W2 P11] 未登记 / null sessionId → null（唯一读法零回落：不读 env、不读 config home、不读 ThreadLocal）")
    void unbound_returnsNull_neverFallsBack() throws IOException {
        // WHY（规则九 · 审计 P11）: 旧 resolve() 第 3 级回落把
        //   CLAUDE_PROJECT_DIR env ?? NexusaiPaths.getAppConfigHomeDir() 当「会话绑定项目」身份返回 ——
        //   无会话/未绑定会话的下游会拿到 config home 冒充项目根（内存/技能/workflow 全落错目录）。
        //   getForSession 是生产唯一读法，必须「未登记 → null」，由调用方按「无有效项目」skip（A′）。
        String pa = bindDir("project-a");
        SessionProjectRoot.setForSession("sess-a", pa);

        assertThat(SessionProjectRoot.getForSession(null))
            .as("null sessionId → null（不猜、不回落）")
            .isNull();
        assertThat(SessionProjectRoot.getForSession("sess-never-bound"))
            .as("未登记会话 → null，绝不回落 env/config home 冒充项目根")
            .isNull();
        // 反向锚定：已登记会话仍可查（防「一律返回 null」的假绿）
        assertThat(SessionProjectRoot.getForSession("sess-a")).isEqualTo(pa);
    }

    @Test
    @DisplayName("setForSession 拒绝无效项目根（非绝对 / 目录不存在）—— 不污染 cwd 解析链")
    void setForSession_rejectsInvalidRoot() {
        // WHY: [2026-08-24 cwd 污染修复] 相对/不存在路径（如绑定「抓包流程」存相对 path）若被登记，
        //       CwdResolution/工具 cwd 全失败；绑定侧校验必须拒绝。
        SessionProjectRoot.setForSession("sess-bad-abs", "relative/path");
        SessionProjectRoot.setForSession("sess-bad-missing", tempDir.resolve("not-exist").toString());

        assertThat(SessionProjectRoot.getForSession("sess-bad-abs")).isNull();
        assertThat(SessionProjectRoot.getForSession("sess-bad-missing")).isNull();
    }
}
