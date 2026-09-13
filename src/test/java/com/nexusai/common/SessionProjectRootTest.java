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
        // 回源解析器是 static 注入口 → 用例必须自行注销，否则跨用例/跨类污染。
        SessionProjectRoot.setDbResolver(null);
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
        //       （[TL-W2 P11] 不再经裸 MDC 会话槽（批 3c 已删除）间接解析 —— 消费方持 sessionId 现算）。
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
    @DisplayName("[批 4a #9] 冻结表 miss ⇒ 回源 DB + 回填（Redis miss → 回源 → 回填）；回填后不再回源")
    void dbResolver_refillsOnMiss() throws Exception {
        // WHY（规则九 · impact C）：BY_SESSION 纯内存 → 后端重启后首条消息前必然 miss。
        //   判据必须是「DB 也查不到」⇒ miss 回源、命中回填（回填是<b>承重</b>动作，不是优化）。
        // RED①: 删掉 refillFromDb 的 setForSession 回填 ⇒ 第二次调用回源计数变 2 ⇒ 红。
        // RED②: 把 lookup 的 bound 返回改成 unknown ⇒ 首次断言红。
        final String path = bindDir("db-project");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SessionProjectRoot.setDbResolver(sid -> {
            calls.incrementAndGet();
            return SessionProjectRoot.Lookup.bound(path);
        });

        assertThat(SessionProjectRoot.getForSession("sess-restarted"))
            .as("冻结表 miss 但 DB 有绑定 ⇒ 回源返回 DB 值")
            .isEqualTo(path);
        assertThat(SessionProjectRoot.getForSession("sess-restarted"))
            .as("回填后第二次读命中冻结表")
            .isEqualTo(path);
        assertThat(calls.get()).as("回源只发生一次（回填生效）").isEqualTo(1);
    }

    @Test
    @DisplayName("[批 4a #7/#13] 三态判据：DB 有会话但未绑定 ⇒ sessionKnown=true（cwd 域据此 fail-loud）")
    void dbResolver_unboundKnownSession_reportsSessionKnown() throws Exception {
        // WHY（规则九 · 用户裁定 #7/#13）：cwd 域必须能区分「会话存在却无项目根」（数据链路异常 ⇒
        //   fail-loud）与「DB 无此会话」（合成/伪造 id ⇒ 无会话出口）。本用例锁定前一态的判据载体。
        // RED: 把 unbound() 的实现改成 new Lookup(null, false) ⇒ 本用例红（cwd 域会静默走 user.dir）。
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unbound());

        SessionProjectRoot.Lookup lk = SessionProjectRoot.lookup("sess-known-unbound");
        assertThat(lk.projectRoot()).isNull();
        assertThat(lk.sessionKnown())
            .as("会话存在（DB 有行）却无绑定项目根 ⇒ sessionKnown=true")
            .isTrue();
        assertThat(SessionProjectRoot.getForSession("sess-known-unbound"))
            .as("既有读法（memory 域）仍返回 null（不回落 config home/user.dir）")
            .isNull();
    }

    @Test
    @DisplayName("[批 4a #9] DB 也无此会话 ⇒ unknown（不回落 config home/user.dir；与 unbound 严格可分）")
    void dbResolver_unknownSession() throws Exception {
        // WHY: 回源不是回落 —— 查不到就不能伪造项目根（D-1 身份域红线）；同时必须与「有会话未绑定」
        //   严格可分（两者 projectRoot 都为 null，靠 sessionKnown 分流）。
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());

        SessionProjectRoot.Lookup lk = SessionProjectRoot.lookup("sess-db-unknown");
        assertThat(lk.projectRoot()).isNull();
        assertThat(lk.sessionKnown()).as("DB 无此会话 ⇒ sessionKnown=false").isFalse();
    }

    @Test
    @DisplayName("[批 4a #9] 回源值无效（目录不存在）⇒ 按「有会话但绑定失效」处理且<b>不回填</b>")
    void dbResolver_invalidRootNotCached() throws Exception {
        // WHY: 与 LlmAgentLoop.tryResolveBoundProjectFromDb 同一判据：无效绑定目录不得冒充项目根，
        //   否则 memory 域会按无效路径建目录（跨项目污染）。且不得回填 —— 否则「先无效后修复」永不生效。
        String ghost = tempDir.resolve("does-not-exist").toString();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SessionProjectRoot.setDbResolver(sid -> {
            calls.incrementAndGet();
            return SessionProjectRoot.Lookup.bound(ghost);
        });

        assertThat(SessionProjectRoot.getForSession("sess-ghost")).isNull();
        assertThat(SessionProjectRoot.lookup("sess-ghost").sessionKnown())
            .as("有绑定但目录不存在 ⇒ 判为「有会话但绑定失效」（数据链路异常）")
            .isTrue();
        assertThat(SessionProjectRoot.getForSession("sess-ghost")).isNull();
        assertThat(calls.get())
            .as("无效值不得回填 ⇒ 每次读取都回源（3 次读取 = 3 次回源；回填了就会恒为 1）")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("[批 4a #12] 改绑 = 先失效缓存 + 再绑定；失效后回源取到新值（不残留旧项目根）")
    void rebind_afterInvalidation_resolvesNewRoot() throws Exception {
        // WHY（规则九 · 用户裁定 #12）：setForSession 是 putIfAbsent 首写胜 ⇒ 改绑（A→B）必须显式
        //   clearSession 失效，否则 CwdResolution/memory/transcript 仍解析到<b>旧项目根</b>
        //   （ProjectSessionBindingService.bind 与 SessionService.update 两条路径都必须失效）。
        String pa = bindDir("old-project");
        final String pb = bindDir("new-project");
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.bound(pb));

        SessionProjectRoot.setForSession("sess-a", pa);
        assertThat(SessionProjectRoot.getForSession("sess-a")).isEqualTo(pa);

        // 改绑：失效旧缓存（DB 已是新项目根）→ 下次读取回源回填新值
        SessionProjectRoot.clearSession("sess-a");
        assertThat(SessionProjectRoot.getForSession("sess-a"))
            .as("失效后必须回源到新项目根，不得残留旧冻结值")
            .isEqualTo(pb)
            .isNotEqualTo(pa);
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
