package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * localSettings loader · 对齐 CC {@code utils/settings/settings.ts:274-296}
 *
 * <h2>文件路径</h2>
 * <p>{@code <projectRoot>/.nexusai/settings.local.json}（{@code projectRoot}=会话项目根，决策 D6）
 * <p><b>项目级 local 源（决策 D6 项目内 .nexusai，非用户级 NexusaiPaths 根）</b>：本 loader 读
 * <b>项目内</b> {@code .nexusai/settings.local.json}（对应 CC {@code .claude/settings.local.json}，
 * D2 不读 .claude），不迁移到 {@link NexusaiPaths#getAppConfigHomeDir()}（那是用户级
 * {@code ~/.nexusai/settings.local.json} 的概念，与项目 local 覆盖语义不同）。
 * <p>项目本地覆盖 —— <b>应该加入 .gitignore</b>（每个人的本地配置不同）。
 *
 * <h2>可编辑性</h2>
 * <p>是 3 个 editable source 之一（且是 3 个中优先级<b>最高</b>的 editable source）。
 *
 * <h2>优先级</h2>
 * <p>3 个 editable source 中最高：{@code userSettings < projectSettings < localSettings}。
 * 被 flag / policy / cliArg / command / session 覆盖。
 *
 * <h2>用途</h2>
 * <p>个人项目覆盖，不应提交到 git。常见用例：
 * <ul>
 *   <li>在 userSettings 基础上添加个人额外允许</li>
 *   <li>临时禁用项目共享的某些 deny 规则</li>
 *   <li>开发期间临时 allow 危险命令（避免影响团队）</li>
 * </ul>
 *
 * <h2>.gitnexusignore</h2>
 * <p>检查项目根目录的 {@code .gitnexusignore} 是否有 {@code .nexusai/settings.local.json} 模式。
 * 当前 {@code .gitnexusignore} 只列了 {@code "nexusai.db"}，本 PR 不修改 gitignore
 * （属于 infra 工作流范畴），但 README 应提示用户添加。
 *
 * <h2>配置注入</h2>
 * <p>同 {@link ProjectSettingsLoader} —— 项目根惰性 {@link java.util.function.Supplier}
 * （决策 D6 项目根，生产接 {@code CwdResolution.getOriginalCwdLayer()}，无会话回落
 * {@code user.dir}）；{@code nexusai.home} 已废弃，不再注入 {@code @Value("${nexusai.home}")}。
 *
 * <h2>异常处理</h2>
 * <p>同其他 source loader —— 失败返回空 list + warn 日志。
 */
@Component
public class LocalSettingsLoader implements PermissionSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(LocalSettingsLoader.class);

    /** 本地覆盖配置文件名（注意是 {@code settings.local.json}，不是 {@code settings.json}）。 */
    private static final String FILE_NAME = "settings.local.json";

    private final SettingsJsonParser parser;
    private final Supplier<String> projectRootSupplier;

    /**
     * Spring 生产构造器 · 项目根惰性接线 {@code CwdResolution.getOriginalCwdLayer()}
     * （语义 = D6 项目根；无会话回落 {@code user.dir}）。{@code nexusai.home} 已废弃，
     * 不再经 {@code @Value} 注入。
     *
     * @param parser settings.json 解析器
     */
    @Autowired
    public LocalSettingsLoader(SettingsJsonParser parser) {
        this(parser, CwdResolution::getOriginalCwdLayer);
    }

    /**
     * 注入式构造器（测试 / 手动接线）。
     *
     * @param parser              settings.json 解析器
     * @param projectRootSupplier 项目根惰性供应（决策 D6 项目根；生产接
     *                            {@code CwdResolution.getOriginalCwdLayer()}，无会话回落
     *                            {@code user.dir}；null 空安全回退 user.dir）
     */
    public LocalSettingsLoader(
            SettingsJsonParser parser,
            Supplier<String> projectRootSupplier
    ) {
        if (parser == null) {
            throw new IllegalArgumentException("parser is null");
        }
        this.parser = parser;
        this.projectRootSupplier = projectRootSupplier != null
                ? projectRootSupplier
                : () -> System.getProperty("user.dir");
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link PermissionRuleSource#LOCAL_SETTINGS}
     */
    @Override
    public PermissionRuleSource source() {
        return PermissionRuleSource.LOCAL_SETTINGS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PermissionRule> load() {
        Path path = resolvePath();
        try {
            List<PermissionRule> rules = parser.parse(path, source());
            if (log.isDebugEnabled()) {
                log.debug("LocalSettingsLoader: loaded {} rule(s) from {}",
                    rules.size(), path);
            }
            return rules;
        } catch (Exception e) {
            log.warn("LocalSettingsLoader: failed to load rules from {}: {}",
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
            log.debug("LocalSettingsLoader: savePermissionsField {} 桶 {} 条 -> {}", field, values.size(), targetFile);
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
            log.debug("LocalSettingsLoader: savePermissionsValue {} = {} -> {}", field, value, targetFile);
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
            log.error("LocalSettingsLoader: failed to save settings to {}: {}", targetFile, e.getMessage());
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            throw new RuntimeException("Failed to save settings: " + targetFile, e);
        }
    }

    private Path resolvePath() {
        // 项目级配置目录名动态化（决策 D1/D6）：NexusaiPaths.getProjectDirName() = "." + appName
        // （生产 appName=nexusai → .nexusai；appName 变则项目级目录名全联动）
        return Paths.get(projectRootSupplier.get(), NexusaiPaths.getProjectDirName(), FILE_NAME);
    }
}
