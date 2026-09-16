package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.SessionKeys;
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
 * projectSettings loader · 对齐 CC {@code utils/settings/settings.ts:274-296}
 *
 * <h2>文件路径</h2>
 * <p>{@code <projectRoot>/.nexusai/settings.json}（{@code projectRoot}=会话项目根，决策 D6）
 * <p><b>项目级 source（决策 D6 项目内 .nexusai，非用户级 NexusaiPaths 根）</b>：本 loader 读
 * <b>项目内</b> {@code .nexusai/settings.json}（对应 CC {@code <project>/.claude/settings.json}，
 * D2 不读 .claude），不迁移到 {@link NexusaiPaths#getAppConfigHomeDir()}（那是用户级
 * {@code ~/.nexusai/settings.json}，与项目共享配置语义不同）。
 * <p>项目级配置 —— <b>通常 commit 到 git</b> 让团队共享权限策略。
 *
 * <h2>可编辑性</h2>
 * <p>是 3 个 editable source 之一。PR 2 后续支持 {@code applyPermissionUpdate} 写回。
 *
 * <h2>优先级</h2>
 * <p>中等 —— 高于 {@code userSettings}，被 {@code localSettings} / flag / policy / cliArg /
 * command / session 覆盖。
 *
 * <h2>配置注入</h2>
 * <p><b>[P11d] 项目根按会话现算</b>：{@link #load(String)} /
 * {@link #readPermissionsStringArray(String, String)} /
 * {@link #savePermissionsField(String, java.util.List, String)} /
 * {@link #savePermissionsValue(String, String, String)} 的 {@code sessionId} 非空 ⇒
 * {@code CwdResolution.getProjectRoot(sessionId)}（会话冻结项目根，四态 fail-loud）；
 * sessionId 为 null/空白/{@code no-session} 哨兵 ⇒ 无会话腿
 * {@link #projectRootSupplier}（生产 = {@code CwdResolution.getOriginalCwdLayer(null)}
 * → 命名无会话出口 {@code getOriginalCwdLayerForNonSession()} = 进程 {@code user.dir}）。
 * 注入式构造器可显式传 supplier（测试）。{@code nexusai.home} / {@code NEXUSAI_HOME} 已废弃
 * （第二轮拍板），不再注入 {@code @Value("${nexusai.home}")}。
 *
 * <h2>无状态 / Spring 单例</h2>
 * <p>项目根 supplier 每次调用时惰性求值（会话项目根随会话绑定变化）；{@link #load()} 每次重新读盘。
 *
 * <h2>异常处理</h2>
 * <p>同 {@link UserSettingsLoader} —— 失败返回空 list + warn 日志。
 */
@Component
public class ProjectSettingsLoader implements PermissionSourceLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectSettingsLoader.class);

    /** 配置文件名。 */
    private static final String FILE_NAME = "settings.json";

    private final SettingsJsonParser parser;
    /**
     * <b>无会话腿</b>的项目根供应（[P11d] 语义收窄 —— 原「恒项目根供应」）。
     *
     * <p>只在 {@code sessionId} 为 null/空白/{@code no-session} 哨兵时使用（= 铁律出口
     * 「本环境确无会话」）。生产 = {@code CwdResolution.getOriginalCwdLayer(null)} → 命名无会话
     * 出口（进程 {@code user.dir} + ≥WARN）；测试注入临时目录（夹具 seam，保持既有测试零改）。
     * <p>⛔ 真会话（sessionId 非空）<b>不读</b>本供应 —— 见 {@link #resolveProjectRoot(String)}。
     */
    private final Supplier<String> projectRootSupplier;

    /**
     * Spring 生产构造器 · 无会话腿项目根接线 {@code CwdResolution.getOriginalCwdLayer(null)}
     * （语义 = D6 项目根的无会话出口；真会话腿在调用期按 sessionId 现算）。
     * {@code nexusai.home} 已废弃，不再经 {@code @Value} 注入。
     *
     * @param parser settings.json 解析器
     */
    @Autowired
    public ProjectSettingsLoader(SettingsJsonParser parser) {
        this(parser, () -> CwdResolution.getOriginalCwdLayer(null));
    }

    /**
     * 注入式构造器（测试 / 手动接线）。
     *
     * @param parser              settings.json 解析器
     * @param projectRootSupplier <b>无会话腿</b>项目根惰性供应（生产接
     *                            {@code CwdResolution.getOriginalCwdLayer()}；null 空安全回退
     *                            {@code user.dir}）
     */
    public ProjectSettingsLoader(
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
     * @return {@link PermissionRuleSource#PROJECT_SETTINGS}
     */
    @Override
    public PermissionRuleSource source() {
        return PermissionRuleSource.PROJECT_SETTINGS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>[P11d] 无会话腿：转调 {@link #load(String)}（{@code null} sessionId）。
     */
    @Override
    public List<PermissionRule> load() {
        return load(null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>[P11d] 覆写：按 {@code sessionId} 解析项目根（见 {@link #resolveProjectRoot(String)}）。
     */
    @Override
    public List<PermissionRule> load(String sessionId) {
        // [P11d] resolvePath 也纳入 try：项目根解析失败（会话无绑定 / DB 答无此会话 / 无法判定）
        //   属「本 source 加载失败」⇒ 保持本接口既有的 lenient 契约（返回空 list + ≥WARN，
        //   ⛔ 不落 user.dir 冒充）；写侧保持 fail-loud（见 savePermissionsField）。
        try {
            Path path = resolvePath(sessionId);
            List<PermissionRule> rules = parser.parse(path, source());
            if (log.isDebugEnabled()) {
                log.debug("ProjectSettingsLoader: loaded {} rule(s) from {}", rules.size(), path);
            }
            return rules;
        } catch (Exception e) {
            log.warn("ProjectSettingsLoader: failed to load rules (sessionId={}): {}",
                sessionId, e.toString());
            return Collections.emptyList();
        }
    }

    /**
     * 读取 {@code permissions.<field>} 原始字符串数组（增量写盘前读现有桶内容）。
     */
    @Override
    public List<String> readPermissionsStringArray(String field, String sessionId) {
        return parser.readPermissionsStringArray(resolvePath(sessionId), field);
    }

    /**
     * 单字段 merge 写 {@code permissions.<field>} 数组（整体替换）。
     */
    @Override
    public void savePermissionsField(String field, List<String> values, String sessionId) {
        if (values == null) {
            throw new IllegalArgumentException("values is null");
        }
        Path targetFile = resolvePath(sessionId);
        String json = parser.mergeWritePermissions(targetFile, field, values);
        atomicWrite(targetFile, json);
        if (log.isDebugEnabled()) {
            log.debug("ProjectSettingsLoader: savePermissionsField {} 桶 {} 条 -> {}", field, values.size(), targetFile);
        }
    }

    /**
     * 单字段 merge 写 {@code permissions.<field>} 字符串值（如 {@code defaultMode}）。
     */
    @Override
    public void savePermissionsValue(String field, String value, String sessionId) {
        Path targetFile = resolvePath(sessionId);
        String json = parser.mergeWritePermissionsValue(targetFile, field, value);
        atomicWrite(targetFile, json);
        if (log.isDebugEnabled()) {
            log.debug("ProjectSettingsLoader: savePermissionsValue {} = {} -> {}", field, value, targetFile);
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
            log.error("ProjectSettingsLoader: failed to save settings to {}: {}", targetFile, e.getMessage());
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            throw new RuntimeException("Failed to save settings: " + targetFile, e);
        }
    }

    /**
     * 解析<b>项目根</b>（[P11d] 读与写共用本方法 ⇒ 必然同址）。
     *
     * <p>会话非空 ⇒ {@code CwdResolution.getProjectRoot(sessionId)}（会话冻结项目根；四态语义见
     * 该方法 javadoc —— 会话存在却无绑定 / DB 明确答无此会话 / 无法判定 ⇒ fail-loud 抛，
     * ⛔ 绝不用进程 {@code user.dir} 冒充）。
     * <p>会话为 null / 空白 / {@code no-session} 哨兵 ⇒ 铁律出口「本环境确无会话」⇒ 无会话腿
     * {@link #projectRootSupplier}（生产 = 命名无会话出口，进程 {@code user.dir} + ≥WARN）。
     *
     * @param sessionId 会话 ID（short；可为 null = 确无会话）
     * @return 项目根绝对路径字符串
     */
    private String resolveProjectRoot(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || SessionKeys.isNoSession(sessionId)) {
            return projectRootSupplier.get();
        }
        return CwdResolution.getProjectRoot(sessionId);
    }

    private Path resolvePath(String sessionId) {
        // 项目级配置目录名动态化（决策 D1/D6）：NexusaiPaths.getProjectDirName() = "." + appName
        // （生产 appName=nexusai → .nexusai；appName 变则项目级目录名全联动）
        return Paths.get(resolveProjectRoot(sessionId), NexusaiPaths.getProjectDirName(), FILE_NAME);
    }
}
