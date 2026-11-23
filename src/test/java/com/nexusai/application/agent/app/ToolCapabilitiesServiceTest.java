package com.nexusai.application.agent.app;

import com.nexusai.application.agent.browser.BrowserWsChannel;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.app.dto.ToolCapabilitiesResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具能力目录：CU/BR 已知名 + 未注册原因 + 浏览器真实连接态（不改注册）。
 */
@DisplayName("ToolCapabilitiesService · CU/BR 能力目录")
class ToolCapabilitiesServiceTest {

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), name);
            }
        };
    }

    private static ToolCapabilitiesService svc(MockEnvironment env, BrowserWsChannel browser,
                                               Tool... tools) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.all()).thenReturn(List.of(tools));
        return new ToolCapabilitiesService(registry, browser, env);
    }

    @Test
    @DisplayName("两个 flag 关：CU/BR 都报「功能未开启」，仍返回已知工具名")
    void flagsOff_reportsFeatureOff() {
        BrowserWsChannel browser = mock(BrowserWsChannel.class);
        var r = svc(new MockEnvironment(), browser).capabilities();

        var cu = r.capabilities().get(0);
        var br = r.capabilities().get(1);
        assertThat(cu.category()).isEqualTo("CU");
        assertThat(cu.reason()).isEqualTo("功能未开启");
        assertThat(cu.knownTools()).isNotEmpty(); // 40 个 CU 已知名
        assertThat(cu.registeredCount()).isZero();
        assertThat(br.category()).isEqualTo("BR");
        assertThat(br.reason()).isEqualTo("功能未开启");
        assertThat(br.knownTools()).hasSize(18);
        assertThat(r.browser().connected()).isFalse();
    }

    @Test
    @DisplayName("BR flag 开但扩展未连接：status=disconnected + 扩展未连接原因")
    void browserFlagOnButDisconnected() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("nexusai.feature.browser-mcp", "true");
        BrowserWsChannel browser = mock(BrowserWsChannel.class);
        when(browser.hasSessionConnection()).thenReturn(false);
        var r = svc(env, browser, tool("mcp__nexusai-in-chrome__read_page")).capabilities();

        var br = r.capabilities().get(1);
        assertThat(br.status()).isEqualTo("disconnected");
        assertThat(br.reason()).isEqualTo("Chrome 扩展未连接");
        assertThat(br.registeredCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR flag 开 + 扩展已连接：ready + lastSeen 非空")
    void browserReadyWithLastSeen() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("nexusai.feature.browser-mcp", "true");
        BrowserWsChannel browser = mock(BrowserWsChannel.class);
        when(browser.hasSessionConnection()).thenReturn(true);
        when(browser.lastSeenMillis()).thenReturn(System.currentTimeMillis());
        var r = svc(env, browser, tool("mcp__nexusai-in-chrome__read_page")).capabilities();

        var br = r.capabilities().get(1);
        assertThat(br.status()).isEqualTo("ready");
        assertThat(br.reason()).isNull();
        assertThat(r.browser().connected()).isTrue();
        assertThat(r.browser().lastSeen()).isNotNull();
    }

    @Test
    @DisplayName("CU flag 开且已注册：ready；未注册（flag 开）→ 「工具未注册」")
    void computerUseStates() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("nexusai.feature.computer-use", "true");
        BrowserWsChannel browser = mock(BrowserWsChannel.class);

        var ready = svc(env, browser, tool("mcp__computer-use__screenshot")).capabilities();
        assertThat(ready.capabilities().get(0).status()).isEqualTo("ready");

        var unregistered = svc(env, browser).capabilities();
        assertThat(unregistered.capabilities().get(0).status()).isEqualTo("unregistered");
        assertThat(unregistered.capabilities().get(0).reason()).isEqualTo("工具未注册");
    }
}
