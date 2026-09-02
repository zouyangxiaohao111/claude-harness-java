package com.nexusai.application.agent.permission;

import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * ChannelPermission.isEnabled feature 门控 · 对齐 CC services/mcp/channelPermissions.ts:36-37
 * {@code isChannelPermissionRelayEnabled()}（GrowthBook 'tengu_harbor_permissions'）。
 *
 * <p>[OPD-WF8-02-07] Java 生产 {@code ChannelPermission} 此前两处硬编码 {@code () -> true}
 * （WebSocketPermissionPrompter:168-169 + StompChannelPermissionCallbacks:64）。用户拍板：
 * ChannelPermission 中继保留，把恒开 {@code () -> true} 改为<b>用户可配置</b>（非硬编码）。
 * 本类承载该配置：{@code nexusai.feature.channel-permission}。
 *
 * <p><b>[G27①] 默认值对齐 CC false</b>（TR-E3-Q3 H1 默认值漂移）：CC flag 默认 false
 * （GrowthBook 'tengu_harbor_permissions' 缺省，channelPermissions.ts:36-38）；本类原缺省 true
 * （WF8-02-07「默认开启」）被 G27① 拍板覆盖 → 生产/无 Spring 缺省均改 false。null（未接线 /
 * 测试直构）→ 视为关闭（对齐 CC 默认 false，不再保留 {@code () -> true} 语义）。
 *
 * <p>镜像 {@link BashClassifierFeature}（同款 Environment 读取 + @Autowired 构造器）——
 * 规范一致性（CLAUDE.md 规则十一）。
 */
@Component
public class ChannelPermissionFeature {

    private static final String CHANNEL_PERMISSION_PROPERTY = "nexusai.feature.channel-permission";

    private final Predicate<Void> enabled;

    /**
     * Spring 生产构造器 · 读 {@code nexusai.feature.channel-permission}（缺省 false，对齐 CC
     * GrowthBook 默认）。
     *
     * @param env Spring Environment（必填；测试可手动传 mock）
     */
    @Autowired
    public ChannelPermissionFeature(Environment env) {
        this.enabled = env != null
            ? v -> isTruthy(env.getProperty(CHANNEL_PERMISSION_PROPERTY, "false"))
            : v -> false;
    }

    /** 缺省构造 · 无 Spring 环境时恒 false（测试 / 手动 new 场景，对齐 CC 默认 false）。 */
    public ChannelPermissionFeature() {
        this(null);
    }

    /** CC isChannelPermissionRelayEnabled() 等价（可配置，默认关闭——对齐 CC GrowthBook flag 缺省 false）。 */
    public boolean isEnabled() {
        return enabled.test(null);
    }

    /** 以 Predicate 形态暴露（注入式兼容 ChannelPermission 构造）。 */
    public Predicate<Void> asPredicate() {
        return v -> enabled.test(null);
    }

    private static boolean isTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return "true".equals(lower) || "1".equals(lower)
            || "yes".equals(lower) || "on".equals(lower);
    }
}
