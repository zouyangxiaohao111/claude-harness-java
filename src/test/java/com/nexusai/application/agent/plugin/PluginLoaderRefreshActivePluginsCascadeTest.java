package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [P2-24 · 2026-09-11] {@code PluginLoader.refreshActivePlugins} 必须走 {@code clearAllCaches()} 级联。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：CC 的 {@code refreshActivePlugins} 第一句即
 * {@code clearAllCaches()}（refresh.ts:74-79），其注释明写「Clears ALL plugin caches (unlike the old
 * needsRefresh path which only cleared loadAllPlugins and returned stale data from downstream memoized
 * loaders)」。该级联（cacheUtils.ts:44-50）含 {@code clearCommandsCache} —— 而<b>技能列表正是经命令
 * memoize 层枚举的</b>。nexusai 修复前只做 {@code clearPluginCache + pruneRemovedPluginHooks}，
 * 命令/技能 memoize 没清 → 跑 {@code /reload-plugins}（经 ActivePluginRefresher →
 * {@code refreshActivePlugins}）时<b>技能列表压根没重新枚举</b>（审计 P2-24，比 P2-22 宽一格）。
 *
 * <p>本类用<b>真实</b> {@link PluginCacheUtils}（非 mock，走真级联）+ mock {@link SkillRegistry}
 * 钉死「重枚举确实被触发」；把 {@code refreshActivePlugins} 改回只清 feed 单槽即 RED。
 */
@DisplayName("[P2-24] refreshActivePlugins 走 clearAllCaches 级联（技能重枚举）")
class PluginLoaderRefreshActivePluginsCascadeTest {

    @Test
    @DisplayName("refreshActivePlugins → 级联抵达 SkillRegistry.refresh()（技能/命令 memoize 失效）+ 仍做 loadAllPlugins 新鲜枚举")
    void refreshActivePlugins_cascadesIntoSkillRegistryRefresh() {
        PluginLoader loader = new PluginLoader();
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        PluginCacheUtils cacheUtils = new PluginCacheUtils();
        cacheUtils.setSkillRegistry(skillRegistry); // 真实级联：clearAllCaches → SkillRegistry.refresh
        loader.setPluginCacheUtils(cacheUtils);

        int enumerationsBefore = loader.enumerationCount();
        loader.refreshActivePlugins();

        verify(skillRegistry, times(1)).refresh();
        assertThat(loader.enumerationCount())
            .as("refreshActivePlugins 仍须执行 loadAllPlugins 新鲜枚举（CC refresh.ts:83 loadAllPlugins）")
            .isGreaterThan(enumerationsBefore);
    }

    @Test
    @DisplayName("未注入 PluginCacheUtils（直构/无 Spring）→ 退化路径不抛，且仍做 loadAllPlugins 新鲜枚举")
    void refreshActivePlugins_withoutCacheUtils_stillEnumerates() {
        PluginLoader loader = new PluginLoader();

        loader.refreshActivePlugins();

        assertThat(loader.enumerationCount())
            .as("退化路径（clearPluginCache + pruneRemovedPluginHooks）仍须 loadAllPlugins 新鲜枚举，不得抛")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("@Lazy 断环：禁循环引用下 PluginLoader ↔ PluginCacheUtils 双向 setter 注入仍可装配并完成注入")
    void pluginLoaderAndCacheUtils_wireWithoutCircularDependency() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            // 复现 Spring Boot 2.6+ 的默认约束（禁止循环引用）——去掉 setPluginCacheUtils 上的 @Lazy 即抛
            //   BeanCurrentlyInCreationException（PluginLoader ↔ PluginCacheUtils 互相注入）。
            ((org.springframework.beans.factory.support.DefaultListableBeanFactory) ctx.getBeanFactory())
                .setAllowCircularReferences(false);
            ctx.registerBean(PluginLoader.class, PluginLoader::new);
            ctx.registerBean(PluginCacheUtils.class, PluginCacheUtils::new);
            ctx.refresh();

            PluginLoader loader = ctx.getBean(PluginLoader.class);
            assertThat(ReflectionTestUtils.getField(loader, "pluginCacheUtils"))
                .as("PluginCacheUtils 必须真实注入（refreshActivePlugins 的 clearAllCaches 级联依赖它；"
                    + "去掉 @Lazy 或改回 volatile 字段直注入即在此 RED）")
                .isNotNull();
        }
    }
}
