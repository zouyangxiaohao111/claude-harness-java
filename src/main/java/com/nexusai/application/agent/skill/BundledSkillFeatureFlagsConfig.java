package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.browser.BrowserWsChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bundled skill feature flag 配置接线 · 对齐 CC {@code src/skills/bundled/index.ts} 编译期
 * {@code feature('...')} 门控（bundled/index.ts:35-78）的 Spring 运行时配置源。
 *
 * <p>仿 {@code McpConfig}（R32-b7a-3）：通过 {@link EnableConfigurationProperties} 注册
 * {@link BundledSkillFeatureFlags} bean，让 application.yml {@code nexusai.skill.features.*} 段可被
 * 类型化注入。{@link BundledSkillsBootstrapper} 由 {@link #bundledSkillsBootstrapper} 显式
 * {@code @Bean} 注册（注入绑定后的 flags）——因 Bootstrapper 有无参构造器，若保持 {@code @Component}
 * Spring 会用无参构造器（硬编码 {@link BundledSkillFeatureFlags#DEFAULTS}），yml 门控将失效；
 * 显式 @Bean 保证门控真正可配置（application.yml 默认三 flag true + mcpSkills false，使注册集与 CC 生产 bundle 一致）。
 *
 * <p><b>nexusai-in-chrome 门控（2026-08-30 · browser-mcp-align）</b>：chrome gate supplier 由旧硬编码
 * {@code () -> false}（Java 无 Chrome MCP 探测）→ 接 {@link BrowserWsChannel#hasSessionConnection()}
 * 连接探测（<b>全局语义</b>：一个扩展连接服务所有会话，有连接即 true，无 → false）。语义对齐 CC
 * setup.ts:72-84 {@code shouldAutoEnableClaudeInChrome()} 的「交互会话 + 扩展可用」判定 —— Java web
 * 中「扩展可用」即运行时存在扩展 WS 连接。
 */
@Configuration
@EnableConfigurationProperties(BundledSkillFeatureFlags.class)
public class BundledSkillFeatureFlagsConfig {

    private static final Logger log = LoggerFactory.getLogger(BundledSkillFeatureFlagsConfig.class);

    /**
     * [browser-mcp-yml-gate] nexusai-in-chrome 总开关 · yml {@code nexusai.feature.browser-mcp}
     * （默认 false）。开发中默认关：false → nexusai-in-chrome skill 不注册（与
     * {@link BrowserMcpToolConfig} 工具不注册同步，防「skill 提示用 mcp__nexusai-in-chrome__*
     * 工具但工具不存在」空转）。测试手动 new（未注入）→ false 默认关（BundledSkillsFeatureGatingTest
     * 只查 loop/schedule/claude-api，不受影响）。
     */
    @Value("${nexusai.feature.browser-mcp:false}")
    private boolean browserMcpEnabled;

    public BundledSkillFeatureFlagsConfig(BundledSkillFeatureFlags flags) {
        if (log.isDebugEnabled()) {
            log.debug("[BundledSkillFeatureFlagsConfig] 加载 bundled skill feature flags：agentTriggers={} agentTriggersRemote={} buildingClaudeApps={} mcpSkills={}（CC feature() 生产默认三 flag true + mcpSkills false，cli.js G15；mcpSkills 默认 false，P1-9 对齐 CC 生产 DCE）",
                flags.agentTriggers(), flags.agentTriggersRemote(), flags.buildingClaudeApps(), flags.mcpSkills());
        }
    }

    /**
     * 生产 BundledSkillsBootstrapper 注册入口 · 对齐 CC {@code initBundledSkills()} 启动调用
     * （bundled/index.ts:24）。注入 yml 绑定的 flags；chrome 门控接 {@link BrowserWsChannel}
     * 连接探测，ant 门控按 USER_TYPE env。
     *
     * <p>P2-6：显式注入两个 isEnabled gate 供应（kairosCronRuntime=() -&gt; true 对齐 CC GB
     * 'tengu_kairos_cron' 默认 true prompt.ts:41；autoMemoryEnabled=isAutoMemoryEnabled 对齐 CC
     * paths.ts:30-56 默认 true），保持生产接线路径与 3 参构造器默认一致（BundledSkillEnabledGates
     * 单一 source，避免测试/生产双实现漂移）。
     *
     * <p><b>nexusai-in-chrome 门控接线（browser-mcp-align）</b>：gate supplier =
     * {@code browserWsChannel != null && browserWsChannel.hasSessionConnection()} —— <b>全局</b>有
     * Chrome 扩展 WS 连接 → true（注册 nexusai-in-chrome），无 → false（跳过）。BrowserWsChannel
     * 为 @Component 恒在；null 兜底（测试/未接线）→ 默认关。
     */
    @Bean
    public BundledSkillsBootstrapper bundledSkillsBootstrapper(
            BundledSkillFeatureFlags flags,
            com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
            BrowserWsChannel browserWsChannel) {
        // [拍板#9 part2] 注入 SessionMemoryService bean → skillify 会话 memory 通道真实读取
        // （SkillifySkillRegistrar getSessionMemoryContent(sessionId)，替代 () -> "" 空桩）。
        return buildBootstrapper(flags, sessionMemoryService, browserWsChannel);
    }

    /**
     * 测试/无 browser 通道重载 · 保持既有 2 参调用签名（{@code BundledSkillsFeatureGatingTest}
     * configBeanFactoryWiresFlags 直接调本方法）；browserWsChannel=null → 门控回落默认关。
     */
    public BundledSkillsBootstrapper bundledSkillsBootstrapper(
            BundledSkillFeatureFlags flags,
            com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService) {
        return buildBootstrapper(flags, sessionMemoryService, null);
    }

    /**
     * 统一构造 helper · chrome 门控 = BrowserWsChannel 全局连接探测（可 null → 默认关）。
     */
    private BundledSkillsBootstrapper buildBootstrapper(
            BundledSkillFeatureFlags flags,
            com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
            BrowserWsChannel browserWsChannel) {
        return new BundledSkillsBootstrapper(
            // nexusai-in-chrome 门控 · CC original: shouldAutoEnableClaudeInChrome()
            // (src/utils/claudeInChrome/setup.ts:72-84) —— Java 等价：browserMcpEnabled（yml 总开关，
            // 开发中默认关）&& 当前会话有扩展 WS 连接
            () -> browserMcpEnabled && browserWsChannel != null && browserWsChannel.hasSessionConnection(),
            () -> "ant".equalsIgnoreCase(System.getenv("USER_TYPE")),
            flags,
            () -> true,
            BundledSkillEnabledGates::isAutoMemoryEnabled,
            sessionMemoryService);
    }
}
