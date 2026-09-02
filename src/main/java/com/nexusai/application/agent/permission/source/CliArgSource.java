package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CliArgSource · 对齐 CC {@code --allowed-tools} / {@code --disallowed-tools} CLI 参数路径.
 *
 * <p><b>[s03 P2 #3 修补] web 系统不实装</b>：
 * nexusai 是 web 系统, CLI 参数不在启动时传入. 用户通过
 * {@link com.nexusai.application.agent.permission.PermissionUpdateApplier}
 * 持久化到 settings.json (USER_SETTINGS / PROJECT_SETTINGS),不走 CLI 参数路径.
 *
 * <p>该 source 必须被 Spring 注入以填满 8-source 框架,但 {@link #load()} 永远返回空 list.
 *
 * <h2>CC 源码位置</h2>
 * <ul>
 *   <li>{@code utils/permissions/permissionsLoader.ts} loadCliAllowedTools / loadCliDisallowedTools</li>
 *   <li>{@code main.ts} parseAllowedTools / parseDisallowedTools</li>
 * </ul>
 *
 * @see PermissionSourceLoader
 */
@Component
public class CliArgSource implements PermissionSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(CliArgSource.class);

    @Override
    public PermissionRuleSource source() {
        return PermissionRuleSource.CLI_ARG;
    }

    @Override
    public List<PermissionRule> load() {
        log.debug("CliArgSource: web 系统无 CLI --allowed-tools, 永远 empty (用户通过 settings.json 配置)");
        return List.of();
    }

    @Override
    public List<PermissionRule> load(String sessionId) {
        return load();
    }
}
