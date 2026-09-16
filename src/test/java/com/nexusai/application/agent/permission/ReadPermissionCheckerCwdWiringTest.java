package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.common.SessionKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P22] <b>接线守护</b>：{@code ReadPermissionChecker} 把 {@code ctx.effectiveCwd()} <b>真传给</b>
 * {@code RuleQuery}（read 桶路径规则的 root-relative 根锚）。
 *
 * <h2>为什么需要这个测试（缺口证据）</h2>
 * <p>批 <b>P19</b> 把 Read 桶的根锚对齐 CC（与 Edit 桶共用一份 {@code patternWithRoot} 等价物
 * {@code RuleQuery.matchesPathRuleRootRelative}），做法是让 {@code RuleQuery.matchRuleContent} 的
 * PathTool 分支改走 root-relative 实现，并<b>沿调用链补 {@code cwd} 形参</b>——
 * 因为 {@code ToolPermissionContext} <b>不带 cwd</b>（CC 靠全局 {@code getCwd()}，本仓无 ambient
 * 单例）⇒ cwd 必须由调用方（{@code ReadPermissionChecker.cwdString(ctx)}）显式传入。
 *
 * <p>P19 <b>只测了 {@code RuleQuery} 内部</b>，没有任何测试断言「生产调用点真传了 cwd」。
 * 实测（本批复现）：把 {@code ReadPermissionChecker} 的 3 处调用（{@code :229} / {@code :244} /
 * {@code :325}）的 {@code cwdString(ctx)} 全部换成 {@code null}（模拟「接线没接上」）⇒
 * 既有权限域 7 个测试类 <b>43 run / 7 F / 0 E，与基线逐条同名同行</b>——即
 * <b>36 条绿色断言一条都没察觉</b>。
 *
 * <p><b>根因 = 「夹具两条腿指向同一目录」</b>：旧夹具的 {@code ctx.effectiveCwd()} 与回落值
 * {@code RuleQuery.resolveEffectiveCwd(null)} → {@code CwdResolution.getCwd(null)} →
 * {@code normalizeCwd(进程 user.dir)} <b>恰好同值</b>；规则用 {@code //…}（文件系统根）或
 * {@code //c/…} 前缀表达，根锚与 cwd 无关 ⇒ 变异后落点不变 ⇒ 恒绿。
 *
 * <h2>本测试怎么打破「两腿同值」（鉴别力的唯一来源）</h2>
 * <ol>
 *   <li><b>夹具 leg</b>：{@code ctx.effectiveCwd()} = {@code target/} 下的<b>全新随机目录</b>
 *       （{@code target/p22-cwd-<8hex>}），且规则写成<b>裸 {@code /…} 前缀</b>（CC {@code patternWithRoot}
 *       语义 = 「规则 source 根相对」；source=SESSION ⇒ 根 <b>= cwd</b>，见
 *       {@code RuleQuery.rootPathForSource}）⇒ <b>只有 cwd 取到夹具值时规则才命中</b>。</li>
 *   <li><b>回落 leg</b>：{@code CwdResolution.getCwd(null)} = 进程 {@code user.dir}（maven 下 =
 *       {@code <repo>/backend}）。夹具目录是它的<b>子目录</b>，两者<b>不可能同值</b> ⇒
 *       变异（cwd→null）后根锚从「夹具目录」变成「user.dir」，同一目标路径的 relativePath
 *       从 {@code <子目录名>/…} 变成 {@code target/<子目录名>/…}，<b>必然不同</b>。</li>
 *   <li>每个用例开头用 {@link #assertTwoLegsDiverge} <b>显式断言两腿不同值</b>——前提一旦被将来
 *       改动破坏（夹具落进 user.dir 等），测试<b>立刻红</b>而不是静默退化成恒绿空转。</li>
 * </ol>
 *
 * <h2>反向变异配方（改坏 ⇒ 必红，用于自证非恒绿）</h2>
 * <ul>
 *   <li><b>MUT-W（本批主变异，调用点层）</b>：把 {@code ReadPermissionChecker} 的 3 处
 *       {@code cwdString(ctx)} 换成 {@code null} ⇒ {@link #denyRuleAnchoredAtCtxEffectiveCwd_denies}
 *       （{@code :229}）、{@link #askRuleAnchoredAtCtxEffectiveCwd_asksWithRuleReason}（{@code :244}）、
 *       {@link #allowRuleAnchoredAtCtxEffectiveCwd_allowsWithRuleReason}（{@code :325}）、
 *       {@link #sameRuleSameCtx_targetUnderFallbackRoot_isNotDenied}（镜像方向）<b>四条全红</b>。</li>
 *   <li><b>MUT-G（守护自坏，证明非恒绿）</b>：把 {@link #assertTwoLegsDiverge} 的夹具改成
 *       {@code Path.of(CwdResolution.getCwd(null))}（把两腿折叠成同值）⇒ 该断言红，
 *       连带证明「本测试的鉴别力<b>完全来自</b>两腿分叉」。</li>
 * </ul>
 *
 * <h2>与 CC 的对应</h2>
 * <p>CC {@code matchingRuleForInput(pattern, ctx, …)}（filesystem.ts:955-1025）的 cwd 来自
 * {@code ctx}（{@code getCwd()}/{@code getOriginalCwd()}）；本仓无 ambient 单例 ⇒
 * {@code ctx.effectiveCwd()} 是唯一可信来源（⛔ 不经 ThreadLocal/MDC 读）。
 * 本类锁定的正是「这个来源真的接到了 RuleQuery」。
 *
 * @see com.nexusai.application.agent.permission.check.RuleQuery#matchesPathRuleRootRelative
 */
@DisplayName("[P22] ReadPermissionChecker → RuleQuery cwd 接线守护（root-relative 根锚 = ctx.effectiveCwd()）")
class ReadPermissionCheckerCwdWiringTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ──────────────────────────────────────────────────────────────────────
    // 夹具
    // ──────────────────────────────────────────────────────────────────────

    private static JsonNode input(String filePath) {
        return JSON.createObjectNode().put("file_path", filePath);
    }

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String toPosix(String s) {
        return s == null ? null : s.replace('\\', '/');
    }

    /**
     * 夹具 cwd · {@code target/} 下的全新随机目录（工作目录 <b>内</b>）。
     *
     * <p>用工作目录内是为了让「命中/未命中」的<b>前置步骤不干扰</b>：未命中时链条会经
     * step6 工作目录判定给出 {@code Allow}（而非兜底 {@code Ask}），与「命中」的
     * {@code Deny}/{@code Ask(Rule)} 形成清晰对照。
     */
    private static Path fixtureCwdInside() {
        return Paths.get("target", "p22-cwd-" + rand()).toAbsolutePath();
    }

    /**
     * 夹具 cwd · 系统临时目录下的全新随机目录（工作目录 <b>外</b>）。
     *
     * <p>专供 {@code :325}（read 桶 content-allow 规则）用例：该步在 step6「工作目录内 → allow」
     * <b>之后</b>，工作目录内的路径根本走不到它 ⇒ 必须用工作目录外的目标路径。
     */
    private static Path fixtureCwdOutside() {
        return Paths.get(System.getProperty("java.io.tmpdir", "."), "p22-outside-" + rand()).toAbsolutePath();
    }

    /**
     * 13 参工厂（显式 {@code effectiveCwd}；会话键用 {@link SessionKeys#NO_SESSION} 哨兵）。
     *
     * <p>用 {@code NO_SESSION} 而非随机 {@code sess-xxx}：本类只考 {@code cwdString(ctx)} 的接线，
     * 而 {@code NO_SESSION} 让 {@code CwdResolution} 走<b>无会话命名出口</b>（进程 user.dir）——
     * 不查 DB、不依赖 JUnit 扩展的 resolver 装配状态，工作目录判定完全确定。
     */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), SessionKeys.NO_SESSION, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolPermissionContext rulesCtx(
            Map<PermissionRuleSource, Set<PermissionRule>> allow,
            Map<PermissionRuleSource, Set<PermissionRule>> deny,
            Map<PermissionRuleSource, Set<PermissionRule>> ask) {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, allow, deny, ask, Map.of());
    }

    /**
     * 规则构造 · {@code source} 决定裸 {@code /…} 前缀的根锚
     * （{@code SESSION} → {@code cwd}，见 {@code RuleQuery.rootPathForSource}）。
     */
    private static PermissionRule rule(PermissionRuleSource source, PermissionBehavior behavior,
            String toolName, String content) {
        return new PermissionRule(source, behavior, PermissionRuleValue.withContent(toolName, content));
    }

    /**
     * ⭐ <b>两腿分叉前置断言</b>——本类鉴别力的唯一来源，也是「禁止退化成恒绿空转」的闸门。
     *
     * <p>左腿 = 夹具的 {@code ctx.effectiveCwd()}；右腿 = 变异后（{@code cwdString(ctx)} → null）
     * {@code RuleQuery} 实际使用的回落值 {@code CwdResolution.getCwd(null)}（= 进程 user.dir）。
     * <b>两腿必须不同值</b>，否则「cwd 真传」与「cwd 丢失」落点同值 ⇒ 断言对 P19 接线无鉴别力。
     */
    private static void assertTwoLegsDiverge(Path fixtureCwd) {
        assertThat(toPosix(CwdResolution.getCwd(null)))
            .as("夹具前提（两腿分叉）：RuleQuery 无 cwd 入参时的回落基准 = CwdResolution.getCwd(null) = 进程 user.dir，"
                + "它必须 ≠ 本夹具的 ctx.effectiveCwd()；若两者同值，则「cwd 真传」与「cwd 丢失」落点相同，"
                + "本测试对 P19 接线（ReadPermissionChecker.cwdString(ctx)）恒绿空转 —— 必须红，不许静默")
            .isNotEqualTo(toPosix(fixtureCwd.toString()));
    }

    private static PermissionResult check(ToolPermissionContext permCtx, Path effectiveCwd, String filePath) {
        PathGuard guard = new PathGuard(effectiveCwd);
        ReadFileTool tool = new ReadFileTool(guard);
        return new ReadPermissionChecker(new WritePermissionChecker())
            .check(tool, input(filePath), ctx(permCtx, effectiveCwd));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 1 · :229（step3 read-specific deny rule → deny）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deny 规则裸 '/' 前缀（根=cwd）命中 → Deny(Rule)：证明 :229 真传了 ctx.effectiveCwd()")
    void denyRuleAnchoredAtCtxEffectiveCwd_denies() {
        Path cwd = fixtureCwdInside();
        assertTwoLegsDiverge(cwd);
        ToolPermissionContext permCtx = rulesCtx(Map.of(),
            Map.of(PermissionRuleSource.SESSION, Set.of(
                rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, "Read", "/p22secret/**"))),
            Map.of());

        PermissionResult result = check(permCtx, cwd, cwd.resolve("p22secret").resolve("a.txt").toString());

        assertThat(result)
            .as("CC matchingRuleForInput：规则 '/p22secret/**' 的裸 '/' 前缀按 source 根锚定（SESSION → cwd）；"
                + "cwd 取到 ctx.effectiveCwd() 时相对路径 = 'p22secret/a.txt' → 命中 → deny；"
                + "cwd 丢失（回落 user.dir）时同一路径的相对形式变成 'target/<夹具>/p22secret/a.txt' → 不命中")
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .as("CC filesystem.ts:1081-1101：deny 的 decisionReason 必须是 Rule(deny rule)")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 2 · :244（step4 read-specific ask rule → ask）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ask 规则裸 '/' 前缀（根=cwd）命中 → Ask(Rule)：证明 :244 真传了 ctx.effectiveCwd()")
    void askRuleAnchoredAtCtxEffectiveCwd_asksWithRuleReason() {
        Path cwd = fixtureCwdInside();
        assertTwoLegsDiverge(cwd);
        ToolPermissionContext permCtx = rulesCtx(Map.of(), Map.of(),
            Map.of(PermissionRuleSource.SESSION, Set.of(
                rule(PermissionRuleSource.SESSION, PermissionBehavior.ASK, "Read", "/p22ask/**"))));

        PermissionResult result = check(permCtx, cwd, cwd.resolve("p22ask").resolve("a.txt").toString());

        assertThat(result)
            .as("cwd 正确 → ask 桶 content 规则命中 → Ask；cwd 丢失 → 规则不命中，随后 step6 工作目录内 → Allow（不是 Ask）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("CC filesystem.ts:1103-1122：ask 必须归因为 Rule(ask rule)，而非兜底 Other")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 3 · :325（step8 read 桶 content allow rule → allow）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("allow 规则裸 '/' 前缀（根=cwd）命中 → Allow(Rule)：证明 :325 真传了 ctx.effectiveCwd()")
    void allowRuleAnchoredAtCtxEffectiveCwd_allowsWithRuleReason() {
        Path cwd = fixtureCwdOutside();
        assertTwoLegsDiverge(cwd);
        String target = cwd.resolve("p22allow").resolve("a.txt").toString();
        ToolPermissionContext permCtx = rulesCtx(
            Map.of(PermissionRuleSource.SESSION, Set.of(
                rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, "Read", "/p22allow/**"))),
            Map.of(), Map.of());
        ToolUseContext ctx = ctx(permCtx, cwd);

        // 前置：:325 步在 step6「工作目录内 → allow」之后，只有工作目录外的路径才可达
        assertThat(ReadPermissionChecker.isInWorkingDir(List.of(target), ctx))
            .as("夹具前提：本用例目标必须落在工作目录之外，否则 read 链在 step6 就先 Allow 了，"
                + ":325 content-allow 规则步不可达 —— 必须红，不许静默空转")
            .isFalse();

        PermissionResult result = new ReadPermissionChecker(new WritePermissionChecker())
            .check(new ReadFileTool(new PathGuard(cwd)), input(target), ctx);

        assertThat(result)
            .as("cwd 正确 → content allow 规则命中 → Allow；cwd 丢失 → 不命中 → 兜底 Ask")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("CC filesystem.ts:1160-1176：allow 必须归因为 Rule(allow rule)，而非 defaultAllow 的 Other")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 用例 4 · 镜像见证：同一规则 + 同一 ctx，仅换目标 → 结论翻转
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("同一规则同一 ctx，目标改落到「回落根」下 → 不命中 Deny（证明根锚是 cwd，不是目标所在目录）")
    void sameRuleSameCtx_targetUnderFallbackRoot_isNotDenied() {
        Path cwd = fixtureCwdInside();
        assertTwoLegsDiverge(cwd);
        ToolPermissionContext permCtx = rulesCtx(Map.of(),
            Map.of(PermissionRuleSource.SESSION, Set.of(
                rule(PermissionRuleSource.SESSION, PermissionBehavior.DENY, "Read", "/p22secret/**"))),
            Map.of());
        // 目标改放到「回落 leg」的根（进程 user.dir）之下：路径段完全相同，只是不在夹具 cwd 之下。
        // 若根锚实现退化成「用目标自己的父目录」或任何与 cwd 无关的常量，这里会命中 Deny → 红。
        String target = Paths.get(System.getProperty("user.dir", "."))
            .toAbsolutePath().resolve("p22secret").resolve("a.txt").toString();

        PermissionResult result = check(permCtx, cwd, target);

        assertThat(result)
            .as("CC patternWithRoot：根锚 = cwd（规则来源根），⛔ 不是「目标路径自身的父目录」。"
                + "目标在 cwd 之外 ⇒ relativePath 越界 ⇒ 不命中 ⇒ 沿链走到 step6（工作目录内）→ Allow。"
                + "⭐ 与用例 1 同规则同 ctx、仅目标不同即结论翻转 —— 这正是「两腿分叉」的镜像见证；"
                + "若两腿同值（变异 W），本用例会变 Deny 而红")
            .isNotInstanceOf(PermissionResult.Deny.class);
    }
}
