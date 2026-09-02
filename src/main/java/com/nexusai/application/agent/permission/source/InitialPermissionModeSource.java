package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.InitialPermissionModeResolver;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * 初始权限模式「真实输入源」· 对齐 CC {@code getSettings_DEPRECATED}
 * （= {@code getInitialSettings}，Open-ClaudeCode/src/utils/settings/settings.ts:812-820）
 * + {@code initialPermissionModeFromCLI} 对 settings 的读取
 * （Open-ClaudeCode/src/utils/permissions/permissionSetup.ts:696/:705-706/:743）。
 *
 * <p><b>WHY（RV-11 · 生产输入源接线）</b>：WF3-01 自述缺口「生产调用点未接线」（WF3-01-progress.md:94），
 * 复验确认 {@code LlmAgentLoop.buildBaseToolUseContext} 用 1 参 {@code Input.empty()} → 生产恒
 * DEFAULT。本组件把磁盘 3 层 settings 的权限元数据（{@code permissions.defaultMode} /
 * {@code permissions.disableBypassPermissionsMode}）合并成 {@link InitialPermissionModeResolver.Input}
 * 的 settings 侧输入，与调用方传入的 CLI 侧（{@code --permission-mode} / {@code --dangerously-skip-permissions}）
 * 一起喂给 6 参重载，使初始 mode 解析在生产生效（非恒 DEFAULT）。
 *
 * <h2>合并语义（对齐 CC getInitialSettings）</h2>
 * <p>CC 合并序（constants.ts:4-16 {@code SETTING_SOURCES}，后置覆盖前置）：
 * {@code userSettings} &lt; {@code projectSettings} &lt; {@code localSettings}。
 * <ul>
 *   <li>{@code permissions.defaultMode}（字符串）：后置 source 有值则覆盖前置 —— Java 侧
 *       {@code local ?? project ?? user}。</li>
 *   <li>{@code permissions.disableBypassPermissionsMode}（settings.ts types.ts:67-70，
 *       {@code z.enum(['disable']).optional()}，唯一合法值 {@code 'disable'}）：lodash mergeWith
 *       对后置 source「未设置」不覆盖前置 —— 等价「任一层设置 'disable' 即禁用」—— Java 侧 OR。</li>
 * </ul>
 *
 * <h2>路径（对齐既有 3 个 editable source loader）</h2>
 * <ul>
 *   <li>user：{@code ~/.nexusai/settings.json}（{@link NexusaiPaths#getAppConfigHomePath()}，
 *       同 UserSettingsLoader/AutoModeGate 源，决策 D2）</li>
 *   <li>project：{@code <projectRoot>/.nexusai/settings.json}（ProjectSettingsLoader:53-55）</li>
 *   <li>local：{@code <projectRoot>/.nexusai/settings.local.json}（LocalSettingsLoader:58-60）</li>
 * </ul>
 *
 * <p><b>lenient 加载</b>：单文件缺失 / JSON 损坏 → 该层 {@link SettingsJsonParser.PermissionsMeta#EMPTY}，
 * 不阻断启动（对齐 CC getSettings_DEPRECATED 读失败返回空 settings）。
 */
@Component
public class InitialPermissionModeSource {

    private static final Logger log = LoggerFactory.getLogger(InitialPermissionModeSource.class);

    /** 用户级配置文件名。 */
    private static final String USER_FILE_NAME = "settings.json";
    /** 项目级配置文件名。 */
    private static final String PROJECT_FILE_NAME = "settings.json";
    /** 本地覆盖配置文件名（注意是 settings.local.json）。 */
    private static final String LOCAL_FILE_NAME = "settings.local.json";

    private final SettingsJsonParser parser;
    private final Supplier<String> projectRootSupplier;

    /** settings 单例行 id（singleton，对齐 SettingsService.SINGLETON_ID）。 */
    private static final int SINGLETON_ID = 1;

    /**
     * [V44] DB 全局默认权限模式读源（settings.permission_mode 列）。
     * 字段注入（required=false）：Spring 装配后注入 SettingsMapper bean → 生产可读 DB 全局默认；
     * POJO 单测 / 无 Spring → null → DB 读跳过（fail-soft 回落磁盘 settings.json defaultMode）。
     */
    @Autowired(required = false)
    private SettingsMapper settingsMapper;

    /**
     * Spring 生产构造器 · 项目根惰性接线 {@code CwdResolution.getOriginalCwdLayer()}
     * （语义 = D6 项目根；无会话回落 {@code user.dir}）；settingsMapper 走
     * {@code @Autowired(required=false)} 字段注入。{@code nexusai.home} 已废弃，不再经
     * {@code @Value} 注入。
     *
     * @param parser settings.json 解析器（共享 bean，复用 {@link SettingsJsonParser#parsePermissionsMeta}）
     */
    @Autowired
    public InitialPermissionModeSource(SettingsJsonParser parser) {
        this(parser, CwdResolution::getOriginalCwdLayer, null);
    }

    /**
     * 2 参构造器（测试/手动接线；settingsMapper = null → 不读 DB 全局，回落磁盘）。
     *
     * @param parser               settings.json 解析器（共享 bean）
     * @param projectRootSupplier  项目根惰性供应（决策 D6 项目根；生产接
     *                             {@code CwdResolution.getOriginalCwdLayer()}，无会话回落
     *                             {@code user.dir}；null 空安全回退 user.dir）
     */
    public InitialPermissionModeSource(
            SettingsJsonParser parser, Supplier<String> projectRootSupplier) {
        this(parser, projectRootSupplier, null);
    }

    /**
     * 3 参构造器重载（测试直接注入 settingsMapper；2 参构造器委托 null）。
     *
     * @param parser               settings.json 解析器（共享 bean）
     * @param projectRootSupplier  项目根惰性供应（决策 D6 项目根）
     * @param settingsMapper       settings 单例行 mapper（可为 null = 不读 DB 全局，回落磁盘）
     */
    public InitialPermissionModeSource(
            SettingsJsonParser parser, Supplier<String> projectRootSupplier, SettingsMapper settingsMapper) {
        if (parser == null) {
            throw new IllegalArgumentException("parser is null");
        }
        this.parser = parser;
        this.projectRootSupplier = projectRootSupplier != null
                ? projectRootSupplier
                : () -> System.getProperty("user.dir");
        this.settingsMapper = settingsMapper;
    }

    /**
     * 解析初始 mode 多源输入 = CLI 参数 + 磁盘 settings 权限元数据。
     *
     * @param permissionModeCli         CLI {@code --permission-mode} 值
     *                                  （CC original: {@code permissionModeCli}，main.tsx:1099
     *                                  {@code permissionMode: permissionModeCli}；可为 null）
     * @param dangerouslySkipPermissions CLI {@code --dangerously-skip-permissions}
     *                                  （CC original: main.tsx:621
     *                                  {@code rawCliArgs.includes('--dangerously-skip-permissions')}）
     * @return 对齐 CC initialPermissionModeFromCLI 入参（permissionSetup.ts:689-695）+ settings 派生字段
     */
    public InitialPermissionModeResolver.Input resolveInput(
            String permissionModeCli, boolean dangerouslySkipPermissions) {
        SettingsJsonParser.PermissionsMeta meta = readMergedPermissionsMeta();
        // [V44] DB 全局默认（settings.permission_mode 列）合并进 settings 槽：DB ?? 磁盘 settings.json。
        //   优先级链（settings 槽内部）：DB 全局 > 磁盘三源（local>project>user）；最终链：
        //   per-call > 会话 override（CLI 槽）> DB 全局（settings 槽）> settings.json defaultMode > default。
        //   对齐 readDbAgentSwarmsEnabled（SettingsService:198-208）容错先例——DB 读异常 fail-soft
        //   回落磁盘，绝不阻断 loop 启动。
        String dbGlobal = readDbGlobalPermissionMode();
        String defaultMode = dbGlobal != null ? dbGlobal : meta.defaultMode();
        return new InitialPermissionModeResolver.Input(
                permissionModeCli,
                dangerouslySkipPermissions,
                // CC original: settings.permissions?.defaultMode（permissionSetup.ts:743）
                defaultMode,
                // CC original: settings.permissions?.disableBypassPermissionsMode === 'disable'（:705-706）
                meta.disableBypassPermissionsMode());
    }

    /**
     * [V44] 读 DB 全局默认权限模式（settings.permission_mode 单例行）。
     *
     * <p><b>fail-soft（绝不阻断 loop 启动）</b>：settingsMapper 未注入（POJO 单测/无 Spring）/
     * selectOneById(1) 行 null / 异常 → null → 回落磁盘 settings.json defaultMode（零行为变化）。
     * 对齐 SettingsService.readDbAgentSwarmsEnabled（:198-208）容错先例。返回 CC 串原样
     * （round-trip 保真，非枚举 name——误存 ACCEPT_EDITS 会在 fromString 静默折叠 DEFAULT）。
     *
     * @return settings.permission_mode 列值（trim 后）；未配置 / 异常 → null
     */
    private String readDbGlobalPermissionMode() {
        if (settingsMapper == null) {
            return null;
        }
        try {
            SettingsRecord s = settingsMapper.selectOneById(SINGLETON_ID);
            if (s == null) {
                return null;
            }
            String mode = s.getPermissionMode();
            if (mode == null || mode.isBlank()) {
                return null;
            }
            return mode.trim();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[InitialPermissionModeSource] 读 settings.permission_mode（DB 全局默认）失败，"
                        + "回落磁盘 settings.json defaultMode: {}", e.toString());
            }
            return null;
        }
    }

    /**
     * 读 3 层 settings 磁盘权限元数据并按 CC 覆盖序合并（user → project → local）。
     *
     * @return 合并后的权限元数据（defaultMode + disableBypassPermissionsMode）
     */
    private SettingsJsonParser.PermissionsMeta readMergedPermissionsMeta() {
        // user 源改走 NexusaiPaths（决策 D2，用户级 ~/.nexusai/settings.json）
        Path userPath = NexusaiPaths.getAppConfigHomePath().resolve(USER_FILE_NAME);
        // project/local 源保持项目内 .nexusai（决策 D6，projectRoot=会话项目根；单次求值防会话切换漂移）
        // 项目级目录名动态化（决策 D1/D6）：NexusaiPaths.getProjectDirName() = "." + appName
        // （生产 appName=nexusai → .nexusai；appName 变则项目级目录名全联动）
        String projectRoot = projectRootSupplier.get();
        Path projectPath = Paths.get(projectRoot, NexusaiPaths.getProjectDirName(), PROJECT_FILE_NAME);
        Path localPath = Paths.get(projectRoot, NexusaiPaths.getProjectDirName(), LOCAL_FILE_NAME);

        SettingsJsonParser.PermissionsMeta userMeta = parser.parsePermissionsMeta(userPath);
        SettingsJsonParser.PermissionsMeta projectMeta = parser.parsePermissionsMeta(projectPath);
        SettingsJsonParser.PermissionsMeta localMeta = parser.parsePermissionsMeta(localPath);

        // CC getInitialSettings 覆盖序（constants.ts:4-16，后置覆盖前置）：local > project > user
        String defaultMode = localMeta.defaultMode() != null
                ? localMeta.defaultMode()
                : (projectMeta.defaultMode() != null ? projectMeta.defaultMode() : userMeta.defaultMode());
        // disableBypassPermissionsMode 仅合法值 'disable'（types.ts:67-70）→ 层间 OR 等价 lodash mergeWith 后置保留
        boolean disableBypass = localMeta.disableBypassPermissionsMode()
                || projectMeta.disableBypassPermissionsMode()
                || userMeta.disableBypassPermissionsMode();

        if (log.isDebugEnabled()) {
            log.debug("InitialPermissionModeSource: 磁盘 settings 权限元数据合并 → defaultMode={} "
                    + "disableBypass={}（local>project>user，CC getSettings_DEPRECATED）",
                defaultMode, disableBypass);
        }
        return new SettingsJsonParser.PermissionsMeta(defaultMode, disableBypass);
    }
}
