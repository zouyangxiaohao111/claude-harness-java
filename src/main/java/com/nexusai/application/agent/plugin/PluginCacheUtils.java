package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.skill.SkillListingSentRegistry;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.subagent.loadAgentsDir;
import com.nexusai.application.agent.tool.impl.SkillToolPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Plugin Cache Utils · 对齐 CC {@code utils/plugins/cacheUtils.ts}（clearAllCaches 级联）。
 *
 * <p>[MPL7] clearAllCaches（cacheUtils.ts:44-50）= clearAllPluginCaches + clearCommandsCache +
 * clearAgentDefinitionsCache + clearPromptCache + resetSentSkillNames。
 * clearAllPluginCaches（:26-42）= clearPluginCache + clearPluginCommandCache + clearPluginAgentCache
 * + clearPluginHookCache + pruneRemovedPluginHooks + clearPluginOptionsCache +
 * clearPluginOutputStyleCache + clearAllOutputStylesCache。
 *
 * <p>Java 侧映射（MPL7 范围）：
 * <ul>
 *   <li>clearPluginCache / clearPluginAgentCache / clearPluginCommandCache → {@link PluginLoader#clearPluginCache(String)}
 *       （feed 单槽失效，commands/agents/组件扫描下次 loadAllPluginsCacheOnly 重枚举）</li>
 *   <li>clearPluginHookCache + pruneRemovedPluginHooks → {@link PluginLoader#pruneRemovedPluginHooks()}
 *       （禁用/卸载插件 hooks 立即停止触发，CC loadPluginHooks.ts:179-204）</li>
 *   <li>clearCommandsCache → {@link SkillRegistry#refresh()}（cacheUtils.ts:46 clearCommandsCache，
 *       commands.ts 等价 —— 命令/skill memoize 全量失效）</li>
 *   <li>clearAgentDefinitionsCache → {@link loadAgentsDir#clearCache()}（cacheUtils.ts:47，
 *       loadAgentsDir.ts:395 等价，agent 定义 memoize 失效）</li>
 *   <li>clearPromptCache → {@link SkillToolPrompt#clearPromptCache()}（cacheUtils.ts:48，
 *       prompt.ts:217-219 等价）</li>
 *   <li>clearAllOutputStylesCache → {@code OutputStyleDirLoader.clearOutputStyleCaches()}（未接线项，保留）</li>
 *   <li>resetSentSkillNames → {@link SkillListingSentRegistry#resetSentAllSessions()}
 *       （attachments.ts:2681-2685 {@code sentSkillNames.clear() + suppressNext=false}）。
 *       <b>[P2-22 · 2026-09-11 纠偏]</b>本条此前写「无生产实例/agent-session 作用域，登记 open-decisions
 *       （MPL7 downgrade）」—— 该理由<b>已失效</b>：{@code SkillListingSentRegistry}（进程级会话键控表）
 *       就是 CC {@code sentSkillNames} 的生产对应物，其 {@code resetSentAllSessions()} 即 CC reset 的
 *       faithful 翻译（清各槽 sent、保留 INITIALIZED = {@code suppressNext===false}，见该表 javadoc）。</li>
 * </ul>
 *
 * <p>WHY（规则三）：CC 用函数调用做级联清空（cacheUtils.ts:44-50）；Java 用本类聚合，
 * 未注入 PluginLoader/SkillRegistry 时对应项 no-op（兼容直构/测试）。
 */
@Component
public class PluginCacheUtils {

    private static final Logger log = LoggerFactory.getLogger(PluginCacheUtils.class);

    /** [MPL7] feed 提供者 · 可选注入，未注入 → no-op（不破坏直构/测试）。 */
    private volatile PluginLoader pluginLoader;

    /** [MPL7] 命令/skill 注册表（clearCommandsCache 目标）· 可选注入（SkillRegistry 为 ToolRegistrationConfig @Bean）。 */
    private volatile SkillRegistry skillRegistry;

    /**
     * [C-方案3][DEC-C-03] SubagentTool 引用 · 插件刷新时连带清 per-cwd registry 缓存。
     *
     * <p>对齐 CC clearAllCaches（cacheUtils.ts:44-50）内 clearAgentDefinitionsCache
     * （loadAgentsDir.ts:395）——CC 清 getAgentDefinitionsWithOverrides.cache，Java 侧 agent-defs
     * 组装层缓存（SubagentTool.registriesByCwd per-cwd 视图）同样须清，否则磁盘 agent 变更不可见
     * （loadAgentsDir.clearCache 只清文件发现层）。@Lazy 断 ToolRegistry/SubagentTool 装配环
     * （同 setMcpToolPool 先例）；@Autowired(required=false)：直构/测试未注入 → null → 跳过。
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private volatile com.nexusai.application.agent.tool.impl.SubagentTool subagentTool;

    @Autowired(required = false)
    public void setPluginLoader(PluginLoader pluginLoader) {
        this.pluginLoader = pluginLoader;
        if (log.isDebugEnabled()) {
            log.debug("[MPL7] PluginCacheUtils PluginLoader 注入: {}", pluginLoader != null);
        }
    }

    @Autowired(required = false)
    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        if (log.isDebugEnabled()) {
            log.debug("[MPL7] PluginCacheUtils SkillRegistry 注入: {}", skillRegistry != null);
        }
    }

    /**
     * [MPL7] clearAllPluginCaches · 对齐 CC cacheUtils.ts:26-42。
     *
     * <p>feed 单槽失效（clearPluginCache）+ 移除禁用/卸载插件 hooks（pruneRemovedPluginHooks）。
     * 调用后下次 {@code loadAllPluginsCacheOnly()} 重枚举，禁用插件 hooks 立即停止触发。
     */
    public void clearAllPluginCaches() {
        PluginLoader loader = this.pluginLoader;
        if (loader == null) {
            if (log.isDebugEnabled()) {
                log.debug("[MPL7] clearAllPluginCaches 跳过: PluginLoader 未注入");
            }
            return;
        }
        loader.clearPluginCache("clearAllPluginCaches");
        loader.pruneRemovedPluginHooks();
        if (log.isInfoEnabled()) {
            log.info("[MPL7] clearAllPluginCaches: feed 失效 + 禁用插件 hooks prune (对齐 CC cacheUtils.ts:26-42)");
        }
    }

    /**
     * [MPL7] clearAllCaches · 对齐 CC cacheUtils.ts:44-50 级联清空。
     *
     * <p>clearAllPluginCaches + clearCommandsCache（SkillRegistry.refresh）+
     * clearAgentDefinitionsCache（loadAgentsDir.clearCache）+ clearPromptCache（SkillToolPrompt.clearPromptCache）
     * + resetSentSkillNames（SkillListingSentRegistry.resetSentAllSessions，<b>末位</b>，对齐 CC cacheUtils.ts:49）。
     * 全量级联后：插件卸载/禁用 → feed 重枚举 + hooks prune + 命令/agent/prompt 缓存失效 + skill_listing
     * 去重态清零（下次 decide 因各槽 sent 空 → isInitial=true → 整份重发），下次查询全新鲜。
     *
     * <p>未注入 SkillRegistry → clearCommandsCache 项 no-op（直构/测试兼容），其余级联仍执行。
     */
    public void clearAllCaches() {
        clearAllPluginCaches();
        // CC cacheUtils.ts:46 clearCommandsCache → SkillRegistry.refresh（commands.ts memoize 全量失效）
        SkillRegistry skillRegistry = this.skillRegistry;
        if (skillRegistry != null) {
            skillRegistry.refresh();
            if (log.isDebugEnabled()) {
                log.debug("[MPL7] clearAllCaches: SkillRegistry.refresh() 已执行 (对齐 CC cacheUtils.ts:46)");
            }
        }
        // CC cacheUtils.ts:47 clearAgentDefinitionsCache → loadAgentsDir.clearCache（loadAgentsDir.ts:395）
        loadAgentsDir.clearCache();
        // [C-方案3][DEC-C-03] 连带清 SubagentTool per-cwd registry 视图（组装层）——
        //   loadAgentsDir.clearCache 只清文件发现层（LOAD_CACHE + MarkdownConfigLoader memoize）；
        //   per-cwd registry 缓存不清则磁盘 agent 变更仍不可见（必须成对）。
        com.nexusai.application.agent.tool.impl.SubagentTool st = this.subagentTool;
        if (st != null) {
            st.clearRegistryCache();
        }
        // CC cacheUtils.ts:48 clearPromptCache → SkillToolPrompt.clearPromptCache（prompt.ts:217-219）
        SkillToolPrompt.clearPromptCache();
        // [P2-22 · 2026-09-11] CC cacheUtils.ts:49 resetSentSkillNames() → 清 skill_listing 去重态。
        //   末位次序对齐 CC（cacheUtils.ts:44-50 的最后一个语句）。WHY：插件启用/禁用/升级后技能候选集
        //   发生变化，CC 借此让下一次 attachment pass 因 sent 空而 isInitial=true → 整份重发；
        //   我们此前缺这一步 → 只走增量分支（新增技能名能发，但整份重发语义缺失）。
        //   生产实例 = 静态表 SkillListingSentRegistry（无 bean 可注入，直呼静态方法）；
        //   清各槽 sent + CLEARED，保留 INITIALIZED（= CC suppressNext===false）→ 各会话走「已初始化 +
        //   sent 空」增量分支 = 全量重发（SkillListingSentRegistry.resetSentAllSessions javadoc）。
        SkillListingSentRegistry.resetSentAllSessions();
        if (log.isDebugEnabled()) {
            log.debug("[MPL7] clearAllCaches: 级联清空完成 (对齐 CC cacheUtils.ts:44-50，含末位 resetSentSkillNames)");
        }
    }
}
