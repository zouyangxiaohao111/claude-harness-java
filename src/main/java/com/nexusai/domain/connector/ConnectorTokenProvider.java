package com.nexusai.domain.connector;

/**
 * 连接器加密凭据 → MCP 运行时 Bearer 的供给接口。
 *
 * <p>MCP 传输层（{@code McpAuthHeaderProvider}）优先询问本接口：连接器按 mcp server 名
 * （= connector key）持有加密 token 时接管鉴权；无记录才回退既有 {@code mcp_oauth_tokens}。
 *
 * <p><b>三段结果</b>：{@code connectorManaged=false} = 不是连接器的 server（调用方回退旧源）；
 * {@code managed + token} = 可用；{@code managed + null} = 连接器接管但需要重新授权
 * （调用方⛔不得回退旧源，直接不发 Authorization → server 401 → 前端提示重新授权）。
 */
public interface ConnectorTokenProvider {

    /**
     * 解析指定 mcp server 的 Bearer。
     *
     * @param connectorKey mcp server 名（= 连接器 key）
     * @return 三段结果（永不为 null）
     */
    Result resolve(String connectorKey);

    /**
     * 强制刷新（401 且本地 token 未过期时用）。
     *
     * @return 新 access token；无刷新条件/失败返回 null（失败时已标记 reauth_required）
     */
    String forceRefresh(String connectorKey);

    /**
     * @param connectorManaged 连接器是否接管该 server（false = 回退旧源）
     * @param accessToken      可用 token（null = 接管但需重新授权）
     * @param status           连接器侧状态（connected / reauth_required / …）
     */
    record Result(boolean connectorManaged, String accessToken, String status) {

        public static Result notManaged() {
            return new Result(false, null, null);
        }

        public static Result connected(String accessToken) {
            return new Result(true, accessToken, "connected");
        }

        public static Result reauthRequired(String status) {
            return new Result(true, null, status == null ? "reauth_required" : status);
        }

        public boolean usable() {
            return connectorManaged && accessToken != null && !accessToken.isBlank();
        }
    }
}
