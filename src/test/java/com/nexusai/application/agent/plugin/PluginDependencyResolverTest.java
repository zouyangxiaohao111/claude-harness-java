package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginDependencyResolver.DependencyLookupResult;
import com.nexusai.application.agent.plugin.PluginDependencyResolver.Lookup;
import com.nexusai.application.agent.plugin.PluginDependencyResolver.PluginView;
import com.nexusai.application.agent.plugin.PluginDependencyResolver.ResolutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL9] PluginDependencyResolver · 对齐 CC utils/plugins/dependencyResolver.ts（305 行）。
 *
 * <p>WHY（规则九 · 测试验证意图）：CC 的依赖解析是安装期 DFS（root 永不被跳过 / 三态错误）
 * + 加载期不动点降级（reason 区分 not-enabled/not-found / 不改输入）。旧 Java stub 只有
 * 简单的 BFS closure，无 cycle/not-found/cross-marketplace 三态、无不动点降级 —— 会静默
 * 安装跨市场依赖（安全边界被破坏）或在依赖缺失时带着损坏状态加载。本测试锁定五组契约。
 */
@DisplayName("[MPL9] PluginDependencyResolver 对齐 CC dependencyResolver.ts")
class PluginDependencyResolverTest {

    private final PluginDependencyResolver resolver = new PluginDependencyResolver();

    private static PluginView view(String source, boolean enabled, String... deps) {
        return new PluginView(source, PluginIdentifier.parse(source).name(), enabled,
            deps.length == 0 ? List.of() : List.of(deps));
    }

    private static Lookup lookupOf(Map<String, List<String>> deps) {
        return id -> {
            List<String> d = deps.get(id);
            return d == null ? null : new DependencyLookupResult(d);
        };
    }

    // ── qualifyDependency ────────────────────────────────────────────────

    @Test
    @DisplayName("qualifyDependency：裸依赖继承声明插件 marketplace")
    void qualifyBareDepInheritsDeclaringMarketplace() {
        assertThat(resolver.qualifyDependency("b", "a@epic")).isEqualTo("b@epic");
    }

    @Test
    @DisplayName("qualifyDependency：已带 marketplace 的依赖原样返回")
    void qualifyDepWithMarketplaceUnchanged() {
        assertThat(resolver.qualifyDependency("b@other", "a@epic")).isEqualTo("b@other");
    }

    @Test
    @DisplayName("qualifyDependency：inline 插件裸依赖原样返回（合成哨兵不编造 @inline）")
    void qualifyInlineDeclaringBareDepUnchanged() {
        assertThat(resolver.qualifyDependency("b", "a@inline")).isEqualTo("b");
    }

    // ── resolveDependencyClosure ─────────────────────────────────────────

    @Test
    @DisplayName("closure：root + 传递依赖 DFS 闭包")
    void closureIncludesRootPlusTransitive() {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        deps.put("a@epic", List.of("b"));
        deps.put("b@epic", List.of("c"));
        deps.put("c@epic", List.of());

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of(), Set.of());

        assertThat(r.ok()).isTrue();
        assertThat(((ResolutionResult.Resolved) r).closure()).containsExactly("c@epic", "b@epic", "a@epic");
    }

    @Test
    @DisplayName("closure：root 即使在 alreadyEnabled 也永不被跳过（重装必须 cache/register）")
    void closureRootNeverSkippedEvenWhenEnabled() {
        Map<String, List<String>> deps = Map.of("a@epic", List.of("b@epic"), "b@epic", List.of());

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of("a@epic"), Set.of());

        assertThat(r.ok()).isTrue();
        List<String> closure = ((ResolutionResult.Resolved) r).closure();
        assertThat(closure).contains("a@epic");
    }

    @Test
    @DisplayName("closure：已启用依赖被跳过（避免意外 settings 写入）")
    void closureAlreadyEnabledDepSkipped() {
        Map<String, List<String>> deps = Map.of("a@epic", List.of("b@epic"), "b@epic", List.of());

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of("b@epic"), Set.of());

        assertThat(r.ok()).isTrue();
        assertThat(((ResolutionResult.Resolved) r).closure()).containsExactly("a@epic");
    }

    @Test
    @DisplayName("closure：已启用依赖按裸名跳过（MPL9-UNIFY 生产 getEnabledPluginIds 返回裸名集合）")
    void closureAlreadyEnabledDepSkippedByBareName() {
        // WHY（规则九）：生产 PluginInstaller 的 getEnabledPluginIds() 返回注册表裸名（rec.name()），
        // 而 walk 用的是全限定 ID。统一后实例方法必须同时支持全限定 + 裸名匹配，否则已装依赖
        // 会被重新递归（意外 settings 写入）。CC :117 是全限定匹配，裸名匹配是 Java 注册表裸名
        // 主键（MPL5 concern #3）的必要适配。
        Map<String, List<String>> deps = Map.of("a@epic", List.of("b@epic"), "b@epic", List.of());

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of("b"), Set.of());

        assertThat(r.ok()).isTrue();
        assertThat(((ResolutionResult.Resolved) r).closure()).containsExactly("a@epic");
    }

    @Test
    @DisplayName("closure：跨 marketplace 同名不误判（enabled b@epic 不跳过 allowlist 放行的 b@other）")
    void closureCrossMarketplaceSameNameNotMisjudged() {
        // WHY（规则九）：裸名匹配只应在"同一 marketplace 上下文"生效。alreadyEnabled 含 b@epic 时，
        // allowlist 放行的 b@other 必须仍被 walk（跨 marketplace 同名不得误判为已启用）。
        Map<String, List<String>> deps = Map.of("a@epic", List.of("b@other"), "b@other", List.of());

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of("b@epic"), Set.of("other"));

        assertThat(r.ok()).isTrue();
        assertThat(((ResolutionResult.Resolved) r).closure()).containsExactly("b@other", "a@epic");
    }

    @Test
    @DisplayName("closure：cycle 检测返回 chain 错误")
    void closureCycleDetected() {
        Map<String, List<String>> deps = Map.of(
            "a@epic", List.of("b@epic"),
            "b@epic", List.of("a@epic"));

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of(), Set.of());

        assertThat(r.ok()).isFalse();
        ResolutionResult.Failed f = (ResolutionResult.Failed) r;
        assertThat(f.reason()).isEqualTo("cycle");
        assertThat(f.chain()).endsWith("a@epic");
    }

    @Test
    @DisplayName("closure：not-found 返回 missing + requiredBy")
    void closureNotFound() {
        Map<String, List<String>> deps = Map.of("a@epic", List.of("ghost"));

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of(), Set.of());

        assertThat(r.ok()).isFalse();
        ResolutionResult.Failed f = (ResolutionResult.Failed) r;
        assertThat(f.reason()).isEqualTo("not-found");
        assertThat(f.missing()).isEqualTo("ghost@epic");
        assertThat(f.requiredBy()).isEqualTo("a@epic");
    }

    @Test
    @DisplayName("closure：cross-marketplace 默认阻断（安全边界）")
    void closureCrossMarketplaceBlocked() {
        Map<String, List<String>> deps = Map.of("a@epic", List.of("b@other"));

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of(), Set.of());

        assertThat(r.ok()).isFalse();
        ResolutionResult.Failed f = (ResolutionResult.Failed) r;
        assertThat(f.reason()).isEqualTo("cross-marketplace");
        assertThat(f.dependency()).isEqualTo("b@other");
        assertThat(f.requiredBy()).isEqualTo("a@epic");
    }

    @Test
    @DisplayName("closure：root 的 allowlist 允许 cross-marketplace 依赖")
    void closureCrossMarketplaceAllowedViaAllowlist() {
        Map<String, List<String>> deps = Map.of("a@epic", List.of("b@other"), "b@other", List.of());

        ResolutionResult r = resolver.resolveDependencyClosure("a@epic", lookupOf(deps), Set.of(), Set.of("other"));

        assertThat(r.ok()).isTrue();
        assertThat(((ResolutionResult.Resolved) r).closure()).containsExactly("b@other", "a@epic");
    }

    @Test
    @DisplayName("closure：inline 插件裸依赖（null marketplace）在 root 有 marketplace 时按 cross-marketplace 阻断（CC:121-132）")
    void closureInlineBareDepNullMarketplaceBlockedWhenRootHasMarketplace() {
        // WHY（规则九 · 返工 F2）：CC 判式 idMarketplace !== rootMarketplace && !(idMarketplace && allowed.has)
        // 不要求 idMarketplace 非 null——inline 插件（--plugin-dir，source "a@inline"）的裸依赖 "b"
        // 被 qualifyDependency 原样返回（null marketplace），root 是 "inline"（非 null）→ 必须阻断。
        // Java 旧实现 `idMarketplace != null && ...` 会让该裸依赖继续 lookup，破坏跨市场安全边界。
        Map<String, List<String>> deps = Map.of("a@inline", List.of("b"));

        ResolutionResult r = resolver.resolveDependencyClosure("a@inline", lookupOf(deps), Set.of(), Set.of());

        assertThat(r.ok()).isFalse();
        ResolutionResult.Failed f = (ResolutionResult.Failed) r;
        assertThat(f.reason()).isEqualTo("cross-marketplace");
        assertThat(f.dependency()).isEqualTo("b");
        assertThat(f.requiredBy()).isEqualTo("a@inline");
    }

    // ── verifyAndDemote ──────────────────────────────────────────────────

    @Test
    @DisplayName("demote：依赖已加载但禁用 → reason=not-enabled")
    void demoteNotEnabledReason() {
        List<PluginView> plugins = List.of(
            view("a@epic", true, "b@epic"),
            view("b@epic", false));

        var result = resolver.verifyAndDemote(plugins);

        assertThat(result.demoted()).containsExactly("a@epic");
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).reason()).isEqualTo("not-enabled");
        assertThat(result.errors().get(0).dependency()).isEqualTo("b@epic");
    }

    @Test
    @DisplayName("demote：依赖完全缺失 → reason=not-found")
    void demoteNotFoundReason() {
        List<PluginView> plugins = List.of(
            view("a@epic", true, "ghost@epic"));

        var result = resolver.verifyAndDemote(plugins);

        assertThat(result.demoted()).containsExactly("a@epic");
        assertThat(result.errors().get(0).reason()).isEqualTo("not-found");
    }

    @Test
    @DisplayName("demote：不动点级联（A→B→缺失 C，A 与 B 都被降级）")
    void demoteFixedPointCascade() {
        List<PluginView> plugins = List.of(
            view("a@epic", true, "b@epic"),
            view("b@epic", true, "c@epic"));

        var result = resolver.verifyAndDemote(plugins);

        // 第一轮降级 B（缺 C），第二轮 A 的依赖 B 不再 enabled → 降级 A
        assertThat(result.demoted()).containsExactlyInAnyOrder("a@epic", "b@epic");
        assertThat(result.errors()).hasSize(2);
    }

    @Test
    @DisplayName("demote：不改输入数组（原 enabled 标志不变）")
    void demoteDoesNotMutateInput() {
        PluginView a = view("a@epic", true, "ghost@epic");
        List<PluginView> plugins = new java.util.ArrayList<>(List.of(a));

        resolver.verifyAndDemote(plugins);

        assertThat(plugins.get(0).enabled()).isTrue();
    }

    @Test
    @DisplayName("demote：inline 插件裸依赖按名匹配")
    void demoteBareDepFromInlineMatchesByName() {
        List<PluginView> plugins = List.of(
            view("a@inline", true, "b"),
            view("b@epic", true));

        var result = resolver.verifyAndDemote(plugins);

        assertThat(result.demoted()).isEmpty();
    }

    // ── findReverseDependents ────────────────────────────────────────────

    @Test
    @DisplayName("findReverseDependents：全限定依赖精确匹配 + 排除自身 + 仅 enabled")
    void reverseDependentsQualifiedMatch() {
        List<PluginView> plugins = List.of(
            view("a@epic", true, "b@epic"),
            view("c@epic", true, "b@epic"),
            view("b@epic", true),
            view("d@epic", false, "b@epic"));

        assertThat(resolver.findReverseDependents("b@epic", plugins))
            .containsExactlyInAnyOrder("a", "c");
    }

    @Test
    @DisplayName("findReverseDependents：inline 裸依赖按名匹配")
    void reverseDependentsBareMatchByName() {
        List<PluginView> plugins = List.of(
            view("a@inline", true, "b"),
            view("b@epic", true));

        assertThat(resolver.findReverseDependents("b@epic", plugins)).containsExactly("a");
    }
}
