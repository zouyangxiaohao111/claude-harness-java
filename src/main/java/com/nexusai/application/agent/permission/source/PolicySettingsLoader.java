package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 企业管控策略 loader · 对齐 CC {@code utils/settings/settings.ts} §5.19-5.23 policySettings 4 层合并。
 *
 * <h2>4 层合并（优先级从低到高）</h2>
 * <pre>
 *   Layer 1: managed-file   — 企业 managed-settings.json（最低优先级）
 *   Layer 2: MDM            — macOS plist / Windows registry — Phase 2 stub
 *   Layer 3: remote         — 网络拉取 enterprise policy    — Phase 2 stub
 *   Layer 4: HKCU registry  — Windows HKCU 注册表           — Phase 2 stub
 * </pre>
 *
 * <p>高优先级覆盖低优先级：相同 toolName 的规则，后面的 layer 覆盖前面的。
 * 当前阶段只实现 Layer 1（managed-file），其他 3 层留接口返回空 list。
 *
 * <h2>可编辑性</h2>
 * <p>{@link PermissionRuleSource#POLICY_SETTINGS} 是 read-only source —— 用户不可删改。
 * 企业管理员通过 MDM / remote / file / registry 推送，终端用户只能查看不能编辑。
 *
 * <h2>配置注入</h2>
 * <p>企业策略文件路径通过 {@code nexusai.policy.path} 配置注入。
 * 为空时不加载任何 policy（与无企业管控的场景一致）。
 *
 * <h2>异常处理</h2>
 * <p>同其他 source loader —— 失败返回空 list + warn 日志。单个 layer 失败不影响其他 layer。
 *
 * @see PermissionRuleSource#POLICY_SETTINGS
 * @see SettingsJsonParser
 */
@Component
public class PolicySettingsLoader implements PermissionSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(PolicySettingsLoader.class);

    /** 4 层策略源（优先级从低到高：file &lt; MDM &lt; remote &lt; HKCU 注册表）。 */
    // 当前阶段只实现 managed-file，其他 3 层返回空

    private final SettingsJsonParser parser;
    /** 企业策略文件路径（来自 {@code nexusai.policy.path} 配置）。 */
    private final String policyFilePath;

    /**
     * Spring 注入构造器。
     *
     * @param parser     settings.json 解析器
     * @param policyPath 企业策略文件路径（可为空——表示无企业管控）
     */
    public PolicySettingsLoader(SettingsJsonParser parser,
                                 @Value("${nexusai.policy.path:}") String policyPath) {
        if (parser == null) {
            throw new IllegalArgumentException("parser is null");
        }
        // policyPath 允许为空/blank（无企业管控场景）
        this.parser = parser;
        this.policyFilePath = policyPath;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link PermissionRuleSource#POLICY_SETTINGS}
     */
    @Override
    public PermissionRuleSource source() {
        return PermissionRuleSource.POLICY_SETTINGS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>从 managed file 加载策略（Layer 1）· 对齐 CC settings.ts 4 层 policy
     * first-wins 语义（settings.ts:322-345，非合并）。其余 3 层空 stub 已删
     * （O11：CC 无对应概念；4 层 first-wins 语义由 settings 加载链承载，S01 写盘对齐时核对）。
     */
    @Override
    public List<PermissionRule> load() {
        return loadManagedFile();
    }

    /**
     * Layer 1: 从 managed-settings.json 加载企业策略。
     *
     * <p>对齐 CC {@code settings.ts loadManagedFileSettings()}。
     * 从 {@code nexusai.policy.path} 指定的文件读取 JSON 并解析为规则列表。
     *
     * @return 规则列表（可能为空）
     */
    private List<PermissionRule> loadManagedFile() {
        if (policyFilePath == null || policyFilePath.isBlank()) {
            return List.of();
        }
        Path path = Paths.get(policyFilePath);
        try {
            List<PermissionRule> rules = parser.parse(path, source());
            if (log.isDebugEnabled()) {
                log.debug("PolicySettingsLoader: loaded {} rule(s) from managed file {}",
                    rules.size(), path);
            }
            return rules;
        } catch (Exception e) {
            log.warn("PolicySettingsLoader: failed to load from {}: {}", path, e.getMessage());
            return List.of();
        }
    }

}
