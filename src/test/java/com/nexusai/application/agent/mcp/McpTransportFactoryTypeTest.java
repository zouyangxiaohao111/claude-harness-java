package com.nexusai.application.agent.mcp;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T8 · McpTransportFactory 显式 type 判别 + sdk 传输（Q-26）。
 *
 * <p><b>WHY (意图验证)</b>: 旧工厂用 URL 前缀推断（{@code String url = config.command()} 把
 * command 当 url 读是 bug）。显式 type 判别让 type=stdio/http/ws/sdk 各返回对应 transport，
 * 删除 URL 前缀推断；type 缺省旧请求按 command 推导 stdio（仅过渡）。claudeai-proxy 拍板
 * 不实现 → 抛异常含 TODO。
 */
class McpTransportFactoryTypeTest {

    private final McpTransportFactory factory = new McpTransportFactory();

    private McpTransport.TransportConfig cfg(String type, String command) {
        return new McpTransport.TransportConfig(command, List.of(), null, null, "srv", type);
    }

    @Test
    @DisplayName("type=sdk → SdkMcpTransport（SDK 进程内承载）")
    void sdkReturnsSdkTransport() {
        McpTransport t = factory.create(cfg("sdk", null));
        assertThat(t).isInstanceOf(SdkMcpTransport.class);
    }

    @Test
    @DisplayName("type=http → HttpMcpTransport")
    void httpReturnsHttpTransport() {
        McpTransport t = factory.create(cfg("http", "https://example.com/mcp"));
        assertThat(t).isInstanceOf(HttpMcpTransport.class);
    }

    @Test
    @DisplayName("type=stdio → StdioMcpTransport")
    void stdioReturnsStdioTransport() {
        McpTransport t = factory.create(cfg("stdio", "python"));
        assertThat(t).isInstanceOf(StdioMcpTransport.class);
    }

    @Test
    @DisplayName("type 缺省但 command 非空 → StdioMcpTransport（back-compat 推导）")
    void noTypeWithCommandInfersStdio() {
        McpTransport t = factory.create(cfg(null, "python"));
        assertThat(t).isInstanceOf(StdioMcpTransport.class);
    }

    @Test
    @DisplayName("type=claudeai-proxy → 抛 IllegalArgumentException 含 TODO")
    void claudeaiProxyThrowsWithTodo() {
        assertThatThrownBy(() -> factory.create(cfg("claudeai-proxy", "https://proxy")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TODO");
    }

    @Test
    @DisplayName("type 缺省且 command 也为空 → 抛（无法推断）")
    void noTypeNoCommandThrows() {
        assertThatThrownBy(() -> factory.create(cfg(null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
