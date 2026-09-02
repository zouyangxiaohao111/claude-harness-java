package com.nexusai.application.agent.plugin;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Plugin Dependency Resolver · 对齐 CC {@code utils/plugins/dependencyResolver.ts}（305 行）。
 *
 * <p><b>纯函数、无 I/O</b>（CC 头部注释 :1-12 "pure functions, no I/O"）。语义是 apt 式
 * "presence guarantee"：插件 A 依赖 B 意味着 "B 的 namespaced 组件必须在 A 运行时可用"。
 *
 * <h2>两个入口（CC :8-11）</h2>
 * <ul>
 *   <li>{@link #resolveDependencyClosure} — 安装期 DFS 遍历 + cycle/not-found/cross-marketplace 三态</li>
 *   <li>{@link #verifyAndDemote} — 加载期不动点检查，降级依赖不满足的插件（session 本地，不写 settings）</li>
 * </ul>
 *
 * <p><b>CC 行号索引</b>：
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #qualifyDependency}</td><td>{@code qualifyDependency}</td><td>dependencyResolver.ts:38-46</td></tr>
 *   <tr><td>{@link #resolveDependencyClosure}</td><td>{@code resolveDependencyClosure}</td><td>dependencyResolver.ts:95-159</td></tr>
 *   <tr><td>{@link #verifyAndDemote}</td><td>{@code verifyAndDemote}</td><td>dependencyResolver.ts:177-234</td></tr>
 *   <tr><td>{@link #findReverseDependents}</td><td>{@code findReverseDependents}</td><td>dependencyResolver.ts:244-263</td></tr>
 * </table>
 */
@Component
public class PluginDependencyResolver {

    /** CC {@code INLINE_MARKETPLACE}（dependencyResolver.ts:25）· --plugin-dir 插件合成 marketplace 哨兵。 */
    public static final String INLINE_MARKETPLACE = "inline";

    /**
     * verifyAndDemote 需要的插件最小视图 · CC {@code LoadedPlugin}
     * （{@code source}/{@code name}/{@code enabled}/{@code manifest.dependencies} 子集）。
     * Java {@code PluginLoader.LoadedPlugin} 无 manifest dependencies 字段，resolver 用本视图保持纯函数可测。
     */
    public record PluginView(String source, String name, boolean enabled, List<String> dependencies) {
    }

    /** 依赖查找回调 · CC {@code DependencyLookupResult}（dependencyResolver.ts:53-56）{@code {dependencies?}}。 */
    @FunctionalInterface
    public interface Lookup {
        /** 返回 null = 插件不存在（CC lookup 返回 null → not-found）。 */
        DependencyLookupResult lookup(String id);
    }

    public record DependencyLookupResult(List<String> dependencies) {
    }

    /** 解析结果 · CC {@code ResolutionResult}（dependencyResolver.ts:58-67）判别联合。 */
    public sealed interface ResolutionResult permits ResolutionResult.Resolved, ResolutionResult.Failed {
        boolean ok();

        /** {@code {ok:true, closure}}（:59）· closure 恒含 rootId。 */
        record Resolved(List<String> closure) implements ResolutionResult {
            @Override
            public boolean ok() {
                return true;
            }
        }

        /**
         * {@code {ok:false, reason, ...}}（:60-67）。
         * reason ∈ {@code cycle}（chain）/ {@code not-found}（missing+requiredBy）/
         * {@code cross-marketplace}（dependency+requiredBy）；不适用字段为 null。
         */
        record Failed(String reason, List<String> chain, String missing, String dependency, String requiredBy)
            implements ResolutionResult {
            @Override
            public boolean ok() {
                return false;
            }
        }
    }

    /** verifyAndDemote 结果 · CC {@code {demoted, errors}}（dependencyResolver.ts:177-179）。 */
    public record DemotionResult(Set<String> demoted, List<PluginError> errors) {
    }

    /** CC {@code PluginError} dependency-unsatisfied 条目（dependencyResolver.ts:214-222）。 */
    public record PluginError(String type, String source, String plugin, String dependency, String reason) {
    }

    /**
     * 归一化依赖引用为全限定 {@code name@marketplace} 形式 · CC {@code qualifyDependency}（:38-46）。
     *
     * <p>裸名（无 @）继承声明它的插件的 marketplace；cross-marketplace 反正被阻断，@ 后缀是常见场景的
     * 样板。例外：声明插件是 {@code inline}（--plugin-dir）→ 裸依赖原样返回（inline 是合成哨兵，
     * 编造 "dep@inline" 永不匹配）。
     */
    public String qualifyDependency(String dep, String declaringPluginId) {
        if (PluginIdentifier.parse(dep).marketplace() != null) {
            return dep;
        }
        String mkt = PluginIdentifier.parse(declaringPluginId).marketplace();
        if (mkt == null || mkt.equals(INLINE_MARKETPLACE)) {
            return dep;
        }
        return dep + "@" + mkt;
    }

    /**
     * DFS 遍历 rootId 的传递依赖闭包 · CC {@code resolveDependencyClosure}（:95-159）。
     *
     * <p>返回的 closure <b>恒含 rootId</b>（root 永不被 skip，即使 alreadyEnabled —— 重装已装插件
     * 仍须 cache/register，:110-116）。已启用的<b>依赖</b>被跳过（不递归，避免意外 settings 写入）。
     * Cross-marketplace 依赖默认阻断（安全边界，:118-132）；root marketplace 的
     * {@code allowCrossMarketplaceDependenciesOn} allowlist 仅 root 的名单对整个 walk 生效
     * （无传递信任）。
     *
     * @param rootId                   解析的根插件（{@code name@marketplace}）
     * @param lookup                   返回 {@code {dependencies}} 或 null（not-found）的查找回调
     * @param alreadyEnabled           要跳过的插件 ID（仅依赖，root 永不被跳过）；null → 空集
     * @param allowedCrossMarketplaces root 信任可自动安装的 marketplace 名单；null → 空集
     * @return 待安装闭包，或 cycle/not-found/cross-marketplace 错误
     */
    public ResolutionResult resolveDependencyClosure(String rootId, Lookup lookup,
                                                     Set<String> alreadyEnabled,
                                                     Set<String> allowedCrossMarketplaces) {
        String rootMarketplace = PluginIdentifier.parse(rootId).marketplace();
        List<String> closure = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>();
        Set<String> enabled = alreadyEnabled == null ? Set.of() : alreadyEnabled;
        Set<String> allowed = allowedCrossMarketplaces == null ? Set.of() : allowedCrossMarketplaces;

        ResolutionResult err = walk(rootId, rootId, rootId, rootMarketplace, lookup,
            enabled, allowed, closure, visited, stack);
        if (err != null) {
            return err;
        }
        return new ResolutionResult.Resolved(List.copyOf(closure));
    }

    /** DFS 递归核心 · CC {@code walk}（dependencyResolver.ts:106-154）。返回错误或 null（继续）。 */
    private ResolutionResult walk(String id, String requiredBy, String rootId, String rootMarketplace,
                                  Lookup lookup, Set<String> alreadyEnabled, Set<String> allowedCrossMarketplaces,
                                  List<String> closure, Set<String> visited, List<String> stack) {
        // Skip already-enabled DEPENDENCIES（避免意外 settings 写入），但 root 永不被跳过（:117）。
        // 匹配判式 = CC 全限定 ID（alreadyEnabled.has(id)）+ Java 注册表裸名主键适配
        // （MPL9-UNIFY：生产 getEnabledPluginIds 返回 rec.name() 裸名集合，须按 name 匹配）。
        // 跨 marketplace 同名不误判：alreadyEnabled 含全限定 "b@epic" 时，allowlist 放行的 "b@other"
        // 其裸名 "b" 不在集合 → 仍被 walk（不误判为已启用）。
        if (!id.equals(rootId) && (alreadyEnabled.contains(id)
                || alreadyEnabled.contains(PluginIdentifier.parse(id).name()))) {
            return null;
        }
        // Security: 跨 marketplace 阻断（:121-132）。在 alreadyEnabled 检查之后执行 ——
        // 用户手动装过的 cross-mkt 依赖已在 alreadyEnabled，永远走不到这里。
        // CC 判式：idMarketplace !== rootMarketplace && !(idMarketplace && allowed.has(idMarketplace))。
        // null marketplace id（inline 插件裸依赖）与 root 的 marketplace 不同即阻断（不要求非 null）。
        String idMarketplace = PluginIdentifier.parse(id).marketplace();
        if (!Objects.equals(idMarketplace, rootMarketplace)
                && !(idMarketplace != null && allowedCrossMarketplaces.contains(idMarketplace))) {
            return new ResolutionResult.Failed("cross-marketplace", null, null, id, requiredBy);
        }
        if (stack.contains(id)) {
            List<String> chain = new ArrayList<>(stack);
            chain.add(id);
            return new ResolutionResult.Failed("cycle", chain, null, null, requiredBy);
        }
        if (visited.contains(id)) {
            return null;
        }
        visited.add(id);

        DependencyLookupResult entry = lookup.lookup(id);
        if (entry == null) {
            return new ResolutionResult.Failed("not-found", null, id, null, requiredBy);
        }

        stack.add(id);
        if (entry.dependencies() != null) {
            for (String rawDep : entry.dependencies()) {
                String dep = qualifyDependency(rawDep, id);
                ResolutionResult err = walk(dep, id, rootId, rootMarketplace, lookup,
                    alreadyEnabled, allowedCrossMarketplaces, closure, visited, stack);
                if (err != null) {
                    return err;
                }
            }
        }
        stack.remove(stack.size() - 1);

        closure.add(id);
        return null;
    }

    /**
     * 加载期安全网：对每个 enabled 插件校验 manifest 依赖都在 enabled 集合 · CC
     * {@code verifyAndDemote}（:177-234）。
     *
     * <p>不动点循环：降级 A 可能破坏依赖 A 的 B，故迭代至无变化（:164-166/:197-228）。
     * {@code reason} 区分 {@code not-enabled}（dep 在已加载集合但禁用）与 {@code not-found}
     * （dep 完全缺失）。<b>不改输入数组</b>（不 mutate），返回待降级插件 ID 集合 + /doctor 错误。
     *
     * @param plugins 全部已加载插件（enabled + disabled）
     * @return 待降级插件 ID 集合 + errors
     */
    public DemotionResult verifyAndDemote(List<PluginView> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return new DemotionResult(Set.of(), List.of());
        }
        Set<String> known = new HashSet<>();
        // Name-only 索引：inline 插件裸依赖按名匹配（enabledByName 是 multiset：B@epic 与 B@other
        // 同时启用时，降级一个不能让 "B" 从索引消失，:184-194）
        Set<String> knownByName = new HashSet<>();
        for (PluginView p : plugins) {
            known.add(p.source);
            knownByName.add(PluginIdentifier.parse(p.source).name());
        }
        Set<String> enabled = new LinkedHashSet<>();
        Map<String, Integer> enabledByName = new HashMap<>();
        for (PluginView p : plugins) {
            if (p.enabled()) {
                enabled.add(p.source);
                String n = PluginIdentifier.parse(p.source).name();
                enabledByName.merge(n, 1, Integer::sum);
            }
        }
        List<PluginError> errors = new ArrayList<>();

        boolean changed = true;
        while (changed) {
            changed = false;
            for (PluginView p : plugins) {
                if (!enabled.contains(p.source)) {
                    continue;
                }
                List<String> deps = p.dependencies() == null ? List.of() : p.dependencies();
                for (String rawDep : deps) {
                    String dep = qualifyDependency(rawDep, p.source);
                    boolean isBare = PluginIdentifier.parse(dep).marketplace() == null;
                    boolean satisfied = isBare
                        ? (enabledByName.getOrDefault(dep, 0) > 0)
                        : enabled.contains(dep);
                    if (!satisfied) {
                        enabled.remove(p.source);
                        String pName = PluginIdentifier.parse(p.source).name();
                        int count = enabledByName.getOrDefault(pName, 0);
                        if (count <= 1) {
                            enabledByName.remove(pName);
                        } else {
                            enabledByName.put(pName, count - 1);
                        }
                        // reason：dep 在 known 集合（同名或全限定）→ not-enabled；否则 not-found（:219-221）
                        String reason = (isBare ? knownByName.contains(dep) : known.contains(dep))
                            ? "not-enabled"
                            : "not-found";
                        errors.add(new PluginError("dependency-unsatisfied", p.source, pName, dep, reason));
                        changed = true;
                        break;
                    }
                }
            }
        }

        Set<String> demoted = new LinkedHashSet<>();
        for (PluginView p : plugins) {
            if (p.enabled() && !enabled.contains(p.source)) {
                demoted.add(p.source);
            }
        }
        return new DemotionResult(Collections.unmodifiableSet(demoted), List.copyOf(errors));
    }

    /**
     * 找出声明依赖 {@code pluginId} 的所有 enabled 插件 · CC {@code findReverseDependents}
     * （:244-263）。用于卸载/禁用时的 "required by: X, Y" 警告。裸依赖（inline 插件）按名匹配。
     */
    public List<String> findReverseDependents(String pluginId, List<PluginView> plugins) {
        String targetName = PluginIdentifier.parse(pluginId).name();
        List<String> result = new ArrayList<>();
        if (plugins == null) {
            return result;
        }
        for (PluginView p : plugins) {
            if (!p.enabled() || p.source.equals(pluginId)) {
                continue;
            }
            List<String> deps = p.dependencies() == null ? List.of() : p.dependencies();
            for (String rawDep : deps) {
                String qualified = qualifyDependency(rawDep, p.source);
                boolean matches = PluginIdentifier.parse(qualified).marketplace() != null
                    ? qualified.equals(pluginId)
                    : qualified.equals(targetName);
                if (matches) {
                    result.add(p.name());
                    break;
                }
            }
        }
        return result;
    }
}
