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
 * <p>同 {@link ProjectSettingsLoader} —— <b>[P11d] 项目根按会话现算</b>：
 * {@link #load(String)} / {@link #readPermissionsStringArray(String, String)} /
 * {@link #savePermissionsField(String, java.util.List, String)} /
 * {@link #savePermissionsValue(String, String, String)} 的 {@code sessionId} 非空 ⇒
 * {@code CwdResolution.getProjectRoot(sessionId)}（会话冻结项目根，四态 fail-loud）；
 * sessionId 为 null/空白/{@code no-session} 哨兵 ⇒ 无会话腿
 * {@link #projectRootSupplier}（生产 = {@code CwdResolution.getOriginalCwdLayer(null)}
 * → 命名无会话出口 {@code getOriginalCwdLayerForNonSession()} = 进程 {@code user.dir}）。
 * {@code nexusai.home} 已废弃，不再注入 {@code @Value("${nexusai.home}")}。
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
     * [批 A4c P4] gitignore 守护（写盘成功后把 {@code .nexusai/settings.local.json} 加入忽略列表）。
     *
     * <p>{@code @Autowired(required = false)} <b>字段注入</b>（不走构造器）—— 本类有两个 public
     * 构造器，构造器注入正是批 A4b 那条「Spring 回落无参构造器 ⇒ loaders 恒空」缺陷的现场；
     * 字段注入与构造器选择无关，天然免疫该坑。
     *
     * <p>为 {@code null}（POJO 测试路径 / 注入缺失）⇒ 写盘后记 <b>WARN</b> 跳过（不静默）。
     */
    private LocalSettingsGitignore gitignoreGuard;

    /**
     * 注入 gitignore 守护 · 生产由 Spring 注入；POJO 测试可注入桩/置 null 验证
     * ①写盘成功后确实调用 ②调用传入的项目根正确。
     */
    @Autowired(required = false)
    public void setGitignoreGuard(LocalSettingsGitignore gitignoreGuard) {
        this.gitignoreGuard = gitignoreGuard;
    }

    /**
     * Spring 生产构造器 · 无会话腿项目根接线 {@code CwdResolution.getOriginalCwdLayer(null)}
     * （语义 = D6 项目根的无会话出口；真会话腿在调用期按 sessionId 现算）。
     * {@code nexusai.home} 已废弃，不再经 {@code @Value} 注入。
     *
     * @param parser settings.json 解析器
     */
    @Autowired
    public LocalSettingsLoader(SettingsJsonParser parser) {
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
                log.debug("LocalSettingsLoader: loaded {} rule(s) from {}", rules.size(), path);
            }
            return rules;
        } catch (Exception e) {
            log.warn("LocalSettingsLoader: failed to load rules (sessionId={}): {}",
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
        atomicWrite(targetFile, json, sessionId);
        if (log.isDebugEnabled()) {
            log.debug("LocalSettingsLoader: savePermissionsField {} 桶 {} 条 -> {}", field, values.size(), targetFile);
        }
    }

    /**
     * 单字段 merge 写 {@code permissions.<field>} 字符串值（如 {@code defaultMode}）。
     */
    @Override
    public void savePermissionsValue(String field, String value, String sessionId) {
        Path targetFile = resolvePath(sessionId);
        String json = parser.mergeWritePermissionsValue(targetFile, field, value);
        atomicWrite(targetFile, json, sessionId);
        if (log.isDebugEnabled()) {
            log.debug("LocalSettingsLoader: savePermissionsValue {} = {} -> {}", field, value, targetFile);
        }
    }

    /**
     * 原子写盘：写临时文件 → ATOMIC_MOVE 替换目标文件。
     *
     * <p>[批 A4c P4] 写成功后补一步 <b>gitignore 守护</b>（对齐 CC
     * {@code settings.ts:508-514} —— {@code source === 'localSettings'} 时调
     * {@code addFileGlobRuleToGitignore}）。⛔ 该步<b>不阻断写盘</b>：守护内部自吞异常（≥WARN），
     * 写盘的成败判定完全不受影响 —— 与 CC 的 {@code void addFileGlobRuleToGitignore(...)} 同语义。
     *
     * @param targetFile 目标文件（{@code <projectRoot>/<projectDirName>/settings.local.json}）
     * @param json       完整文件内容
     * @param sessionId  会话 id（解析项目根用；与读侧同值 ⇒ 读写同址）
     */
    private void atomicWrite(Path targetFile, String json, String sessionId) {
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
        ensureGitignored(sessionId);
    }

    /**
     * [批 A4c P4] 写盘成功后确保 {@code <项目>/.nexusai/settings.local.json} 被 git 忽略。
     *
     * <p><b>WHY</b>：{@code local} 的语义是「个人、不共享」（见类 javadoc），但该文件此前
     * <b>完全不在 .gitignore 覆盖范围内</b>（实测 {@code git check-ignore -v
     * .nexusai/settings.local.json} 无匹配）⇒ 会被提交、规则变团队共享。CC 在同一个时机做同样的
     * 事（{@code settings.ts:508-514}），落点是全局 ignore（{@code ~/.config/git/ignore}，
     * 见 {@link LocalSettingsGitignore}）。
     *
     * <p>未接线（POJO 测试构造路径 / Spring 注入缺失）⇒ <b>WARN</b>（禁只 DEBUG ——
     * 本仓「不许静默失效」铁律；静默正是这类缺陷能躲过三个批次的原因）。
     */
    private void ensureGitignored(String sessionId) {
        if (gitignoreGuard == null) {
            log.warn("LocalSettingsLoader: gitignore 守护未接线 ⇒ {} 不会被自动加入忽略列表，"
                + "该文件可能被误提交（sessionId={}）", FILE_NAME, sessionId);
            return;
        }
        gitignoreGuard.ensureIgnored(resolveProjectRoot(sessionId), NexusaiPaths.getProjectDirName());
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
