package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.browser.BrowserWsChannel;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.common.RequestContext;
import com.nexusai.model.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * /chrome 命令注册配置 · 对齐 CC commands/chrome/index.ts + chrome.tsx call。
 *
 * <p><b>接线通道</b>（同 {@link CommandRegistrationConfig}）：
 * <ul>
 *   <li><b>元数据</b>：Command(name='chrome', type='local-jsx', description='NexusAI in Chrome (Beta)
 *       settings') 注册进 {@link BundledSkills}（source=BUNDLED + loadedFrom=BUNDLED）→
 *       SkillRegistry.getAllCommands 合并进 web GET /api/command。经 getModelInvocableCommands 的
 *       {@code type==='prompt'} 过滤天然排除（对齐 CC commands.ts:568，local 命令不进模型可调用清单）。</li>
 *   <li><b>执行 handler</b>：{@link UserInputDispatcher#registerSlashCommand} 注册 —— 前端 /chrome 输入 →
 *       显示 NexusAI in Chrome 连接状态（读 {@link BrowserWsChannel#hasSessionConnection()}，<b>全局语义</b>：
 *       一个扩展连接服务所有会话，有连接即「已连接」）+ 引导文案。</li>
 * </ul>
 *
 * <p><b>isEnabled 门控（对齐 CC chrome/index.ts:8）</b>：CC {@code isEnabled: () => !getIsNonInteractiveSession()}
 * （非交互会话禁用）；web 端恒交互 → 恒 true，命令始终可查看状态（CC chrome.tsx call 在交互会话渲染菜单）。
 *
 * <p><b>执行行为（对齐 CC chrome.tsx:278-284 call）</b>：CC 渲染 {@code ClaudeInChromeMenu}
 * （Status: Enabled/Disabled = MCP client 是否 connected + Extension: Installed/Not detected + 引导
 * Install/Reconnect/Manage permissions 菜单）。Java web 无 React 渲染等价物 → handler 以文本披露
 * 连接状态 + 引导文案（安装扩展 / 打开扩展面板 / Reconnect）。
 *
 * <p><b>受控差异（fail loud 登记）</b>：
 * <ul>
 *   <li>availability：CC chrome/index.ts:7 声明 {@code ['claude-ai']}；web 无 claude-ai/console 订阅模型
 *       （DEC-8），沿用 CommandRegistrationConfig 不设 availability → universal 可见（受控差异）。</li>
 *   <li>菜单动作（install-extension / reconnect / manage-permissions / toggle-default）为浏览器/全局
 *       配置写操作，web 无对应通道 → handler 仅展示引导文案，不执行菜单动作（受控差异）。</li>
 * </ul>
 */
@Configuration
public class BrowserCommandConfig {

    private static final Logger log = LoggerFactory.getLogger(BrowserCommandConfig.class);

    // ════════════════════════════════════════════════════════════════════════
    // 1. /chrome 命令元数据注册（对齐 CC commands/chrome/index.ts 合并清单）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：注册 /chrome 命令元数据进 {@link BundledSkills}。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使注册副作用
     * 在上下文刷新时必然执行（同 {@code CommandRegistrationConfig.commandBundledRegistration} 模式）。
     */
    @Bean
    public ChromeCommandBundledRegistration chromeCommandBundledRegistration() {
        Command command = new Command();
        command.setName("chrome");                          // CC chrome/index.ts:5
        command.setType("local-jsx");                       // CC chrome/index.ts:9
        command.setDescription("NexusAI in Chrome (Beta) settings"); // CC chrome/index.ts:6
        // CC chrome/index.ts:8 isEnabled: () => !getIsNonInteractiveSession()；
        // web 恒交互 → 恒 true（命令始终可查看状态）
        command.setIsEnabled(() -> true);
        command.setIsHidden(false);
        BundledSkills.register(command);
        log.info("[BrowserCommandConfig] registered /chrome command metadata (name=chrome, type=local-jsx, enabled=true)（对齐 CC commands/chrome/index.ts）");
        return new ChromeCommandBundledRegistration();
    }

    /** 副作用 bean 标记 record · 使 {@link #chromeCommandBundledRegistration} 的注册副作用在 context refresh 时执行。 */
    public record ChromeCommandBundledRegistration() {}

    // ════════════════════════════════════════════════════════════════════════
    // 2. /chrome 执行 handler 注册（UserInputDispatcher registerSlashCommand）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：把 /chrome 的<b>执行 handler</b> 注册进 {@link UserInputDispatcher}。
     *
     * <p>前端 /chrome 输入 → UserInputDispatcher.dispatch → 本 handler 显示连接状态 + 引导文案
     * （同 CommandRegistrationConfig advisor/cost 模式）。BrowserWsChannel 为 @Component bean；
     * plain JUnit 缺省 null → handler 空安全回退（显示未连接 + 登记警告）。
     */
    @Bean
    public ChromeSlashRegistration chromeSlashRegistration(
            UserInputDispatcher dispatcher,
            @Autowired(required = false) BrowserWsChannel browserWsChannel) {
        if (dispatcher == null) {
            log.warn("[BrowserCommandConfig] UserInputDispatcher 未注入，/chrome 执行 handler 注册跳过");
            return new ChromeSlashRegistration();
        }
        dispatcher.registerSlashCommand("chrome", args -> {
            // 全局连接语义：一个扩展连接服务所有会话，hasSessionConnection() 有连接即「已连接」
            boolean connected = browserWsChannel != null && browserWsChannel.hasSessionConnection();
            if (browserWsChannel == null) {
                log.warn("[BrowserCommandConfig] /chrome BrowserWsChannel 未注入，连接状态不可读（显示未连接）");
            }
            if (log.isInfoEnabled()) {
                log.info("[BrowserCommandConfig] /chrome 执行完成: sessionId={} 全局连接状态:\n{}",
                    RequestContext.sessionId(), formatChromeStatus(connected));
            }
        });
        log.info("[BrowserCommandConfig] /chrome 已注册为生产 slash command（对齐 CC commands/chrome/index.ts + chrome.tsx call）");
        return new ChromeSlashRegistration();
    }

    /** 副作用 bean 标记 record · 使 {@link #chromeSlashRegistration} 的注册副作用在 context refresh 时执行。 */
    public record ChromeSlashRegistration() {}

    /**
     * /chrome 状态文本 · 对齐 CC chrome.tsx:223-229 ClaudeInChromeMenu 关键披露段
     * （Status: Enabled/Disabled + Extension: Installed/Not detected + Usage + 权限说明）。
     *
     * <p>Java web 无 React 渲染 → 以中文文本披露：连接状态 + 引导文案（安装扩展 / 打开扩展面板）。
     * 纯函数（可测试）：connected → 文本。
     *
     * <p><b>全局语义（browser-mcp-align 多会话并行）</b>：一个扩展连接服务所有会话，
     * {@link BrowserWsChannel#hasSessionConnection()} 有连接即「已连接」，不再绑定具体会话
     * （会话隔离由扩展侧 tab 组承担）→ 状态文本不再显示会话后缀。
     *
     * @param connected 全局是否有已连接扩展（{@link BrowserWsChannel#hasSessionConnection()}）
     * @return /chrome 状态披露文本
     */
    static String formatChromeStatus(boolean connected) {
        StringBuilder sb = new StringBuilder();
        sb.append("NexusAI in Chrome (Beta) 设置\n");
        sb.append("  连接状态: ").append(connected ? "已连接" : "未连接");
        if (connected) {
            sb.append("（全局扩展连接，服务所有会话）");
        }
        sb.append('\n');
        sb.append("  引导:\n");
        sb.append("    - 安装 Chrome 扩展: 打开 https://claude.ai/chrome 安装 NexusAI in Chrome 扩展\n");
        sb.append("    - 打开扩展面板: 点击 Chrome 工具栏 NexusAI 图标 → 选择 Reconnect extension 连接\n");
        sb.append("  说明: 站点级权限由 Chrome 扩展设置管理（控制 NexusAI 可浏览/点击/输入的站点）\n");
        sb.append("  了解更多: https://code.claude.com/docs/en/chrome");
        return sb.toString();
    }
}
