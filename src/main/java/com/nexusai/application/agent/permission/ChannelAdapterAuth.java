package com.nexusai.application.agent.permission;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 渠道适配器身份校验（[P0.1] 堵伪造 fromServer）。
 *
 * <h2>为什么需要（本仓特有缺口）</h2>
 * CC 的渠道回执走 MCP server 自己的连接，{@code fromServer} 由传输层给出、用户伪造不了。
 * 本仓把回执面换成 STOMP（{@code /app/sessions/{id}/permission-channel-response}），
 * 谁能往这个 topic 发消息，谁就能自称任意 {@code fromServer} 批准权限 —— 这是真实的提权面。
 *
 * <h2>怎么证明身份</h2>
 * 运维在适配器侧配置一个共享口令（{@code nexusai.mcp.channel-adapter-token}），回执必须原样带上
 * {@code adapterToken}。口令未配置 ⇒ <b>一律拒绝</b>（fail-closed），因为无法区分「适配器」与
 * 「能发 STOMP 的任何客户端」。IM 入站（{@code ImInboundRouter}）是服务端内部路径，不经本校验。
 *
 * <p>⛔ 口令不进 DB/不进前端（避免随 settings 接口外泄）；只走 yml/env。
 */
@Component
public class ChannelAdapterAuth {

    /** 适配器共享口令的配置键（yml/env）。 */
    public static final String PROP = "nexusai.mcp.channel-adapter-token";

    private final String token;

    @Autowired
    public ChannelAdapterAuth(Environment env) {
        this(env != null ? env.getProperty(PROP, "") : "");
    }

    /** 测试/手动构造：直接给口令（空/空白 = 未配置）。 */
    public ChannelAdapterAuth(String token) {
        this.token = token == null ? "" : token.trim();
    }

    /** 是否配置了口令（未配置 ⇒ 所有 STOMP 渠道回执一律拒绝）。 */
    public boolean configured() {
        return !token.isEmpty();
    }

    /**
     * 校验回执携带的口令。
     *
     * @param presented 回执里的 {@code adapterToken}
     * @return true 仅当「已配置口令」且「常量时间比较相等」
     */
    public boolean verify(String presented) {
        if (!configured() || presented == null) {
            return false;
        }
        String p = presented.trim();
        return !p.isEmpty() && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), p.getBytes(StandardCharsets.UTF_8));
    }
}
