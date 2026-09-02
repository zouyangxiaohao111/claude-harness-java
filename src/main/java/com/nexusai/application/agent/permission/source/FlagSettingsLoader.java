package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FlagSettingsLoader · 对齐 CC {@code --settings} CLI flag 路径。
 *
 * <p><b>[s03 P2 #3 修补] web 系统不实装</b>：
 * nexusai 是 web 系统, 无 CLI 启动参数机制 (无 main() 接 arg).
 * 该 source 必须被 Spring 注入以填满 8-source 框架 (满足 {@link PermissionRuleSource} 枚举完整性),
 * 但 {@link #load()} 永远返回空 list.
 *
 * <p>Phase 2+ 升级路径: 如果未来提供 CLI 启动类 (如 SDK 二进制),
 * 可以注入 {@code --settings} 参数从指定 JSON 文件加载并改写 {@link #load()}.
 *
 * <h2>CC 源码位置</h2>
 * <ul>
 *   <li>{@code utils/permissions/permissionsLoader.ts} loadFlagSettings</li>
 *   <li>{@code main.ts} parseSettingsFlag (--settings 处理)</li>
 * </ul>
 *
 * @see PermissionSourceLoader
 * @see <a href="https://docs.anthropic.com/en/docs/claude-code/cli-reference">CC CLI reference</a>
 */
@Component
public class FlagSettingsLoader implements PermissionSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(FlagSettingsLoader.class);

    @Override
    public PermissionRuleSource source() {
        return PermissionRuleSource.FLAG_SETTINGS;
    }

    @Override
    public List<PermissionRule> load() {
        log.debug("FlagSettingsLoader: web 系统无 CLI --settings, 永远 empty");
        return List.of();
    }

    @Override
    public List<PermissionRule> load(String sessionId) {
        return load();  // 全局 load (无 session 隔离)
    }
}
