package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

/**
 * userSettings loader · 对齐 CC {@code utils/settings/settings.ts:274-296}
 *
 * <h2>文件路径</h2>
 * <p>{@code <user.home>/.{appName}/settings.json}（NexusaiPaths 动态自有根，决策 D1；
 * {@code appName}=spring.application.name 默认 nexusai，当前生产 nexusai-backend）
 * <p>对应 CC 的 {@code ~/.claude/settings.json} —— 我们用 {@link NexusaiPaths#getAppConfigHomeDir()}
 * （{@code ~/.{appName}}）替代（决策 D2：不读 claude settings.json）。
 *
 * <h2>可编辑性</h2>
 * <p>是 3 个 editable source 之一（{@code userSettings} / {@code projectSettings} /
 * {@code localSettings}）。Phase 2 PR 2 后续会支持
 * {@code applyPermissionUpdate} 写回此文件（PR 2 范围）。
 *
 * <h2>优先级</h2>
 * <p><b>最低</b>（被 {@code projectSettings} / {@code localSettings} / flag / policy / cliArg
 * / command / session 覆盖）。
 * 见 {@link PermissionRuleSource} 优先级列表。
 *
 * <h2>无状态 / Spring 单例</h2>
 * <p>{@link #load()} 每次重新读盘（{@code user.home} 不变）—— Spring 单例 OK。
 *
 * <h2>异常处理</h2>
 * <p>本类捕获所有异常并返回空 list（与 {@link PermissionSourceLoader} 契约一致）。
 * 这是 lenient 加载策略：单个 source 失败不应让 PermissionContextBuilder 失败。
 */
@Component
public class UserSettingsLoader implements PermissionSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsLoader.class);

    /** 配置文件名（CC 也用 {@code settings.json}）。 */
    private static final String FILE_NAME = "settings.json";

    private final SettingsJsonParser parser;

    /**
     * Spring 注入构造器。
     *
     * @param parser settings.json 解析器（共享 bean）
     */
    public UserSettingsLoader(SettingsJsonParser parser) {
        if (parser == null) {
            throw new IllegalArgumentException("parser is null");
        }
        this.parser = parser;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link PermissionRuleSource#USER_SETTINGS}
     */
    @Override
    public PermissionRuleSource source() {
        return PermissionRuleSource.USER_SETTINGS;
    }

    /**
     * {@inheritDoc}
     *
     * <h3>实现细节</h3>
     * <p>用 {@code System.getProperty("user.home")} 解析 {@code ~}（不依赖 shell 展开，
     保证跨平台）。路径不存在 / JSON 损坏时返回空 list 并 warn 日志（不抛异常）。
     */
    @Override
    public List<PermissionRule> load() {
        Path path = resolvePath();
        try {
            List<PermissionRule> rules = parser.parse(path, source());
            if (log.isDebugEnabled()) {
                log.debug("UserSettingsLoader: loaded {} rule(s) from {}", rules.size(), path);
            }
            return rules;
        } catch (Exception e) {
            // lenient 加载：单个 source 失败不影响其他 source
            log.warn("UserSettingsLoader: failed to load rules from {}: {}",
                path, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 读取 {@code permissions.<field>} 原始字符串数组（增量写盘前读现有桶内容）。
     */
    @Override
    public List<String> readPermissionsStringArray(String field) {
        return parser.readPermissionsStringArray(resolvePath(), field);
    }

    /**
     * 单字段 merge 写 {@code permissions.<field>} 数组（整体替换）。
     */
    @Override
    public void savePermissionsField(String field, List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("values is null");
        }
        Path targetFile = resolvePath();
        String json = parser.mergeWritePermissions(targetFile, field, values);
        atomicWrite(targetFile, json);
        if (log.isDebugEnabled()) {
            log.debug("UserSettingsLoader: savePermissionsField {} 桶 {} 条 -> {}", field, values.size(), targetFile);
        }
    }

    /**
     * 单字段 merge 写 {@code permissions.<field>} 字符串值（如 {@code defaultMode}）。
     */
    @Override
    public void savePermissionsValue(String field, String value) {
        Path targetFile = resolvePath();
        String json = parser.mergeWritePermissionsValue(targetFile, field, value);
        atomicWrite(targetFile, json);
        if (log.isDebugEnabled()) {
            log.debug("UserSettingsLoader: savePermissionsValue {} = {} -> {}", field, value, targetFile);
        }
    }

    /**
     * 原子写盘：写临时文件 → ATOMIC_MOVE 替换目标文件。
     */
    private void atomicWrite(Path targetFile, String json) {
        Path dir = targetFile.getParent();
        Path tempFile = dir.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tempFile, json);
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("UserSettingsLoader: failed to save settings to {}: {}", targetFile, e.getMessage());
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw new RuntimeException("Failed to save user settings: " + targetFile, e);
        }
    }

    private Path resolvePath() {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), FILE_NAME);
    }
}