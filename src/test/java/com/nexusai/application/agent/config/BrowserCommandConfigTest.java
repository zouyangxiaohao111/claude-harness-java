package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.browser.BrowserWsChannel;
import com.nexusai.application.agent.skill.BundledSkillFeatureFlags;
import com.nexusai.application.agent.skill.BundledSkillFeatureFlagsConfig;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.BundledSkillsBootstrapper;
import com.nexusai.common.RequestContext;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /chrome 命令 + nexusai-in-chrome skill 门控接线测试（browser-mcp-align）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>/chrome 命令注册（对齐 CC commands/chrome/index.ts）</b>——name='chrome'、
 *       type='local-jsx'、description='NexusAI in Chrome (Beta) settings'、isEnabled 恒 true
 *       （web 恒交互，CC :8 !nonInteractive → true）、isHidden=false。web GET /api/command 可见，
 *       且经 type==='prompt' 过滤不进模型可调用清单（对齐 CC commands.ts:568）。</li>
 *   <li><b>/chrome handler 显示连接状态</b>——前端 /chrome 输入 → UserInputDispatcher 命中命名
 *       handler，读取 {@link BrowserWsChannel#hasSessionConnection()} 判断已连接/未连接 + 引导文案
 *       （对齐 CC chrome.tsx:223-229 Status: Enabled/Disabled）。mock 有/无连接两种路径都验证。</li>
 *   <li><b>skill 门控接连接探测</b>——{@link BundledSkillFeatureFlagsConfig} 生产 @Bean 把
 *       nexusai-in-chrome gate 接 BrowserWsChannel 连接探测（CC setup.ts:72-84 等价）；
 *       有连接 → run() 注册 nexusai-in-chrome，无连接 → 不注册（对齐 CC bundled/index.ts:70-72）。</li>
 *   <li><b>真实探测语义（全局连接）</b>——{@link BrowserWsChannel#hasSessionConnection} 全局有 open
 *       扩展连接即 true（一个扩展服务所有会话，不按会话路由）；无连接/连接关闭均 false。</li>
 * </ol>
 */
class BrowserCommandConfigTest {

    private final BrowserCommandConfig config = new BrowserCommandConfig();
    private final BundledSkillFeatureFlagsConfig skillConfig =
        new BundledSkillFeatureFlagsConfig(BundledSkillFeatureFlags.DEFAULTS);

    @BeforeEach
    void clearRegistry() {
        BundledSkills.clear();
    }

    @AfterEach
    void clearRegistryAndMdc() {
        BundledSkills.clear();
        RequestContext.clear();
    }

    private Map<String, Command> byName() {
        return BundledSkills.getAll().stream()
            .collect(Collectors.toMap(Command::getName, Function.identity(), (a, b) -> a));
    }

    // ════════════════════════════════════════════════════════════════════════
    // /chrome 命令元数据
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/chrome 元数据：name/type/description/enabled/hidden 对齐 CC chrome/index.ts")
    void chromeMetadataRegisteredWithCcContract() {
        config.chromeCommandBundledRegistration();

        Map<String, Command> cmds = byName();
        assertThat(cmds).containsKey("chrome");

        Command chrome = cmds.get("chrome");
        assertThat(chrome.getType()).as("CC chrome/index.ts:9 type='local-jsx'").isEqualTo("local-jsx");
        assertThat(chrome.getDescription())
            .as("CC chrome/index.ts:6 description='NexusAI in Chrome (Beta) settings'")
            .isEqualTo("NexusAI in Chrome (Beta) settings");
        assertThat(chrome.isCommandEnabled())
            .as("CC chrome/index.ts:8 isEnabled: !nonInteractive；web 恒交互 → 恒 true")
            .isTrue();
        assertThat(chrome.getIsHidden()).as("CC chrome/index.ts isHidden 缺省 false").isFalse();
    }

    @Test
    @DisplayName("模型可调用过滤：/chrome 为 local-jsx → 不进 getModelInvocableCommands（对齐 CC commands.ts:568）")
    void chromeExcludedFromModelInvocable() {
        config.chromeCommandBundledRegistration();

        // /chrome 经 getAllCommands web 可见（bundled 注册集）
        assertThat(byName()).containsKey("chrome");
        // type='local-jsx' 非 'prompt' → getModelInvocableCommands 排除（CC commands.ts:568）
        com.nexusai.application.agent.skill.SkillRegistry registry =
            new com.nexusai.application.agent.skill.SkillRegistry("");
        registry.refresh();
        List<String> invocable = registry.getModelInvocableCommands().stream()
            .map(Command::getName).toList();
        assertThat(invocable).doesNotContain("chrome");
    }

    // ════════════════════════════════════════════════════════════════════════
    // /chrome handler
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/chrome handler 注册：/chrome 输入路由到命名 handler + 读取 BrowserWsChannel 连接状态")
    void chromeHandlerRegisteredAndProbesConnection() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        BrowserWsChannel mockChannel = mock(BrowserWsChannel.class);
        when(mockChannel.hasSessionConnection()).thenReturn(true);
        RequestContext.setSession("sess-chrome");

        config.chromeSlashRegistration(dispatcher, mockChannel);

        UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/chrome");
        assertThat(r.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(r.routedTo()).as("/chrome 命中命名 handler").isEqualTo("chrome");
        // handler 必须读取连接状态（对齐 CC chrome.tsx:56 isConnected 判定）
        verify(mockChannel).hasSessionConnection();
    }

    @Test
    @DisplayName("/chrome handler 无连接：BrowserWsChannel 未注入 → 空安全回退（不抛，仍可路由）")
    void chromeHandlerNullChannelIsNullSafe() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        RequestContext.setSession("sess-chrome");

        config.chromeSlashRegistration(dispatcher, null);

        UserInputDispatcher.RoutingResult r = dispatcher.dispatch("/chrome");
        assertThat(r.kind()).isEqualTo(UserInputDispatcher.InputKind.SLASH_COMMAND);
        assertThat(r.routedTo()).isEqualTo("chrome");
    }

    @Test
    @DisplayName("formatChromeStatus：全局已连接/未连接文本 + 引导文案（对齐 CC chrome.tsx Status 段）")
    void formatChromeStatusReflectsConnectionState() {
        String connected = BrowserCommandConfig.formatChromeStatus(true);
        assertThat(connected)
            .as("全局连接：有连接即「已连接」，不再绑定具体会话")
            .contains("连接状态: 已连接").contains("全局扩展连接，服务所有会话");
        assertThat(connected).contains("安装 Chrome 扩展").contains("打开扩展面板");

        String disconnected = BrowserCommandConfig.formatChromeStatus(false);
        assertThat(disconnected).contains("连接状态: 未连接");
        assertThat(disconnected).doesNotContain("已连接");
        assertThat(disconnected).doesNotContain("会话 ");
    }

    // ════════════════════════════════════════════════════════════════════════
    // BrowserWsChannel 真实连接探测
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("BrowserWsChannel.hasSessionConnection 真实探测：全局有 open 连接 → 任何会话/无上下文均 true；关闭 → false")
    void browserWsChannelRealProbe() {
        BrowserWsChannel channel = new BrowserWsChannel();
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        channel.register(ws);

        RequestContext.setSession("sess-x");
        assertThat(channel.hasSessionConnection())
            .as("全局有 open 连接 → true").isTrue();
        assertThat(channel.isSessionConnected("sess-x")).isTrue();

        // 全局连接：一个扩展服务所有会话 → 其他会话/无会话上下文均 true
        RequestContext.setSession("sess-y");
        assertThat(channel.hasSessionConnection())
            .as("其他会话 → 仍 true（全局连接，不按会话路由）").isTrue();

        RequestContext.clear();
        assertThat(channel.hasSessionConnection())
            .as("无会话上下文 → 仍 true（全局连接，不读 RequestContext）").isTrue();
        assertThat(channel.isSessionConnected(null)).isTrue();
        assertThat(channel.isSessionConnected("  ")).isTrue();

        // 连接关闭后 → false（open 判定）
        when(ws.isOpen()).thenReturn(false);
        assertThat(channel.isSessionConnected("sess-x"))
            .as("连接已关闭 → false（对齐 send 的 isOpen 校验）").isFalse();
        assertThat(channel.hasSessionConnection()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // skill 门控（BundledSkillFeatureFlagsConfig 生产 @Bean 接线）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("skill 门控：BrowserWsChannel 有连接 → nexusai-in-chrome 注册（对齐 CC setup.ts:72-84 + index.ts:70-72）")
    void skillGateRegistersWhenConnected() {
        BrowserWsChannel mockChannel = mock(BrowserWsChannel.class);
        when(mockChannel.hasSessionConnection()).thenReturn(true);

        BundledSkillsBootstrapper bootstrapper =
            skillConfig.bundledSkillsBootstrapper(BundledSkillFeatureFlags.DEFAULTS, null, mockChannel);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(Command::getName).toList();
        assertThat(names).contains("nexusai-in-chrome");
        // DEFAULTS 其它 skill 正常注册（证明 run 完整执行）
        assertThat(names).contains("loop", "claude-api");
    }

    @Test
    @DisplayName("skill 门控：BrowserWsChannel 无连接 → nexusai-in-chrome 不注册（对齐 CC 默认关）")
    void skillGateSkipsWhenNotConnected() {
        BrowserWsChannel mockChannel = mock(BrowserWsChannel.class);
        when(mockChannel.hasSessionConnection()).thenReturn(false);

        BundledSkillsBootstrapper bootstrapper =
            skillConfig.bundledSkillsBootstrapper(BundledSkillFeatureFlags.DEFAULTS, null, mockChannel);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(Command::getName).toList();
        assertThat(names).doesNotContain("nexusai-in-chrome");
        // 注册链未被掏空
        assertThat(names).contains("loop");
    }

    @Test
    @DisplayName("skill 门控：BrowserWsChannel 未注入（null）→ 默认关（gate 回落 false）")
    void skillGateDefaultsOffWhenChannelNull() {
        // 2 参重载（browserWsChannel=null）→ gate=() -> false，保持既有测试调用签名
        BundledSkillsBootstrapper bootstrapper =
            skillConfig.bundledSkillsBootstrapper(BundledSkillFeatureFlags.DEFAULTS, null);
        bootstrapper.run(null);

        List<String> names = BundledSkills.getAll().stream()
            .map(Command::getName).toList();
        assertThat(names).doesNotContain("nexusai-in-chrome");
        assertThat(names).contains("loop");
    }
}
