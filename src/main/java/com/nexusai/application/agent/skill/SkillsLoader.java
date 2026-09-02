package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.infra.util.PluginOnlyPolicy;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 文件系统技能加载器 · 对齐 CC skills/loadSkillsDir.ts getSkillDirCommands() + loadSkillsFromSkillsDir()
 *
 * <p>P2-20 起为<b>五源聚合</b>加载（{@link #getSkillDirCommands(String)}，对齐 CC getSkillDirCommands
 * loadSkillsDir.ts:638-804）：
 * <ul>
 *   <li><b>managed</b>（policySettings）→ {@code getManagedFilePath()/.claude/skills}（门控
 *       CLAUDE_CODE_DISABLE_POLICY_SKILLS，:686-688）</li>
 *   <li><b>user</b>（userSettings）→ <b>[T3 双目录]</b> {@code NexusaiPaths.getAppConfigHomeDir()/skills}
 *       （nexusai 自有根优先）+ {@code getClaudeConfigHomeDir()/skills}（claude 回落），均
 *       source=USER（门控 skillsLocked，:689-691）；同 name 由 {@link #dedupByName} nexusai 覆盖 claude</li>
 *   <li><b>project</b>（projectSettings）→ {@code getProjectDirsUpToHome('skills', cwd)} 逐层（:692-698）</li>
 *   <li><b>additional</b>（projectSettings）→ 每个 additionalDir/.claude/skills（:699-708）</li>
 *   <li><b>legacy</b>（commands_DEPRECATED）→ {@link LegacyCommandsLoader}（:713）</li>
 * </ul>
 *
 * <p>per-source 门控 + bare 模式（CLAUDE_CODE_SIMPLE）+ per-file realpath 去重 first-wins + 条件分离
 * 均在本类聚合层完成（对齐 CC :638-804）。POJO 单目录构造（{@link #loadFromDirectory(String)} /
 * {@link #loadFromDirectoryUnconditional(String)}）保留 = 等价 CC loadSkillsFromSkillsDir 原语
 * （:407-480，source=USER，语义 = CC userSettings 源 :689），既有 SkillRegistry 测试不回归。
 *
 * <p>X1 删除：skill.json v1 格式分支与 {@code loadFromSkillJson} 方法已删除（CC 无此格式，
 * loadSkillsFromSkillsDir :435-445 仅 SKILL.md、ENOENT 即 skip）。
 *
 * <h2>name 与 displayName（P1-5 · 对齐 CC loadSkillsDir.ts:452/:238-239）</h2>
 * <p>name 恒=目录名（CC {@code const skillName = entry.name}）；SKILL.md frontmatter 中显式
 * 指定的 name 只进入 displayName（CC {@code displayName: String(frontmatter.name)}），由
 * {@link Command#userFacingName()}（CC :337-339）优先展示。不再反置为 frontmatter.name 优先。
 */
public class SkillsLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillsLoader.class);
    private final ParseSkillFrontmatter parser = new ParseSkillFrontmatter();

    /**
     * 技能 frontmatter token 估算 · CC original: {@code estimateSkillFrontmatterTokens}
     * （loadSkillsDir.ts:100-105）+ {@code roughTokenCountEstimation}（tokenEstimation.ts:203-208）。
     *
     * <p>CC：{@code [skill.name, skill.description, skill.whenToUse].filter(Boolean).join(' ')}
     * → {@code Math.round(content.length / bytesPerToken)}（bytesPerToken=4）。Java：过滤 null/空串
     * （等价 JS {@code filter(Boolean)}——空白串在 JS 为 truthy 保留，Java {@code !s.isEmpty()} 亦保留）→
     * 空格 join → {@code Math.round(len / 4.0)}（/4.0 浮点除法与 JS 非整数除法一致；4 为默认 bytesPerToken）。
     *
     * <p><b>P3-8 迁移</b>：函数自 P2-18 落地于 AnalyzeContext.java 折叠（仅被 countSkillTokens 单一消费），
     * P3-8 按 05-task-register 行65 原目标迁移回本类——CC 定义于 loadSkillsDir.ts（本类对应模块），
     * analyzeContext.ts:23 是唯一 import 方（Java 迁移后本类为唯一宿主；AnalyzeContext 已删，DEL-SP-14）。
     *
     * @param skill 技能命令（CC {@code Command}）
     * @return 仅基于 frontmatter（name/description/whenToUse）的 token 估算
     */
    public static int estimateSkillFrontmatterTokens(Command skill) {
        String joined = String.join(" ",
            Arrays.asList(skill.getName(), skill.getDescription(), skill.getWhenToUse())
                .stream()
                .filter(s -> s != null && !s.isEmpty())
                .toList());
        int tokens = (int) Math.round(joined.length() / 4.0);
        if (log.isDebugEnabled()) {
            log.debug("[SkillsLoader] estimateSkillFrontmatterTokens({}) frontmatter 文本长度 {} → {} tokens (CC loadSkillsDir.ts:100-105 + tokenEstimation.ts:203-208)",
                skill.getName(), joined.length(), tokens);
        }
        return tokens;
    }

    /**
     * P1-2: 条件技能管理器 · 对齐 CC conditionalSkills（loadSkillsDir.ts:771-790）。
     * <p>由 {@link SkillRegistry#setDynamicSkillsManager} 注入；null 时 {@link #loadFromDirectory(String)}
     * 不做条件分离（POJO 兼容，现有测试不破）。
     */
    private DynamicSkillsManager dynamicSkillsManager;

    /**
     * 注入条件技能管理器（setter · @Autowired(required=false)）· 对齐 CC getSkillDirCommands
     * conditional 分离（loadSkillsDir.ts:771-790）。null 时保持现行为（全量返回）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDynamicSkillsManager(DynamicSkillsManager dynamicSkillsManager) {
        this.dynamicSkillsManager = dynamicSkillsManager;
    }

    public DynamicSkillsManager getDynamicSkillsManager() {
        return dynamicSkillsManager;
    }

    /**
     * P2-12: plugin-only policy settings 读取器 · 对齐 CC isRestrictedToPluginOnly('skills')
     * （loadSkillsDir.ts:650 + pluginOnlyPolicy.ts:19-27）。
     *
     * <p>默认 {@code Map::of} = 不锁定（对齐 CC pluginOnlyPolicy.ts:26 缺省 policy → false 与
     * DynamicSkillsManager.java:114 默认）。由 {@link SkillRegistry#setSettingsSupplier} 注入；
     * 未注入时 {@link #loadFromDirectory(String)} 维持现行为（不锁定）。
     */
    private Supplier<Map<String, Object>> settingsSupplier = Map::of;

    /**
     * 注入 plugin-only policy settings 读取器（setter · null 空安全）· 对齐 CC isRestrictedToPluginOnly
     * （loadSkillsDir.ts:650 + pluginOnlyPolicy.ts:19-27；setter 模式对齐 DynamicSkillsManager.java:183-187）。
     */
    public void setSettingsSupplier(Supplier<Map<String, Object>> settingsSupplier) {
        if (settingsSupplier != null) {
            this.settingsSupplier = settingsSupplier;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // P2-20: 五源加载编排字段（对齐 CC getSkillDirCommands loadSkillsDir.ts:638-804）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 附加目录供应（--add-dir 等价）· CC original: {@code getAdditionalDirectoriesForClaudeMd()}
     * （state.ts:206-207，来自 --add-dir CLI）。Java 无 CLI/会话状态等价物（concern #1），默认空 List
     * （CC 默认即空，bare 分支 loadSkillsDir.ts:659 {@code additionalDirs.length===0}）；生产由
     * {@link ToolRegistrationConfig#skillRegistry()} 注入 {@link ClaudePaths#getAdditionalDirectoriesFromEnv}。
     */
    private Supplier<List<String>> additionalDirectoriesSupplier = List::of;

    /**
     * 当前工作目录供应 · 默认会话 cwd（无 sessionId 回落 user.dir）。
     * {@link #getSkillDirCommands(String)} 的 cwd 入参为空时回退本供应。
     *
     * <p>cwd-align-ext：兜底 supplier 改走会话 cwd（CC loadSkillsDir.ts:638-642 getSkillDirCommands(cwd)
     * 的 cwd 顶传 getCwd()）；无 sessionId 回落 user.dir（方案 1，零行为变化）。
     */
    private Supplier<String> cwdSupplier = () -> {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    };

    /**
     * bare 模式判定（可注入）· CC original: {@code isBareMode()}（envUtils.ts:60-65）
     * {@code isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE) || argv.includes('--bare')}。
     * Java Web 后端无 --bare argv（N/A），仅 env CLAUDE_CODE_SIMPLE truthy 判定（对齐 CC
     * isBareMode 唯一权威 env 通道）。默认委托 {@link MemoryBareModeConfig#isBareMode()}
     * —— 该静态方法实现 CC isBareMode 唯一权威通道（envUtils.ts:60-65 env CLAUDE_CODE_SIMPLE
     * truthy）+ Java 配置优先级 {@code nexusai.memory.bare-mode}（配置→env→false 三级），
     * 并含 env 覆盖测试缝（与 {@link #setBareModeSupplier} 同款），保证三调用点收敛一致。
     * 可注入供测试（Java 无法进程内改 env）。
     */
    private Supplier<Boolean> bareModeSupplier = () -> MemoryBareModeConfig.isBareMode();

    /**
     * managed（policySettings）技能加载开关（可注入）· CC original: loadSkillsDir.ts:686-688
     * {@code isEnvTruthy(process.env.CLAUDE_CODE_DISABLE_POLICY_SKILLS) ? [] : loadSkillsFromSkillsDir(managedSkillsDir, 'policySettings')}。
     * 可注入供测试（Java 无法进程内改 env）。
     */
    private Supplier<Boolean> managedSkillsEnabledSupplier =
        () -> !TaskSystemConfig.isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_POLICY_SKILLS"));

    /**
     * user（userSettings）技能加载开关（可注入）· P2-2 引入，CC original: loadSkillsDir.ts:689-691
     * {@code isSettingSourceEnabled('userSettings') && !skillsLocked ? loadSkillsFromSkillsDir(userSkillsDir,'userSettings') : []}。
     *
     * <p>CC isSettingSourceEnabled 由 CLI --settings 状态驱动（settings/constants.ts:174-177），缺省全源启用；
     * Java Web 后端无 CLI（concern #2 补开关），默认 {@code () -> true} 对齐 CC 缺省（全源启用），
     * 可注入（组合根配置）关闭 user 源。供 {@link #getSkillDirCommands} user 分支门控。
     */
    private Supplier<Boolean> userSkillsEnabledSupplier = () -> true;

    /**
     * project（projectSettings）技能加载开关（可注入）· P2-2 引入，CC original: loadSkillsDir.ts:651-652
     * {@code projectSettingsEnabled = isSettingSourceEnabled('projectSettings') && !skillsLocked}
     * （:692-708 project/additional/bare 三处共用）。
     *
     * <p>同上：默认 {@code () -> true} 对齐 CC 缺省（全源启用），可注入关闭 project 源。
     */
    private Supplier<Boolean> projectSkillsEnabledSupplier = () -> true;

    /**
     * 注入附加目录供应 · CC original: getAdditionalDirectoriesForClaudeMd（state.ts:206-207）。
     * null → 忽略（保持默认空 List）。
     */
    public void setAdditionalDirectoriesSupplier(Supplier<List<String>> additionalDirectoriesSupplier) {
        if (additionalDirectoriesSupplier != null) {
            this.additionalDirectoriesSupplier = additionalDirectoriesSupplier;
        }
    }

    /**
     * [P2-18] 读取当前附加目录列表（公开 API）· CC original: getAdditionalDirectoriesForClaudeMd
     * （state.ts:206-207，--add-dir）。附加目录技能以 {@code projectSettings} 源加载
     * （loadSkillsDir.ts:699-708 {@code join(dir, '.claude', 'skills')}），findProjectSkill
     * （skillImprovement.ts:58-66）需把该子集纳入 projectSettings 判定 —— 暴露给
     * {@link SkillImprovementHook#findProjectSkill} 消费（WF6-02 △-1 additionalDir 子集排除修复）。
     *
     * @return 当前附加目录列表（未注入供应 → 默认空 List；供应返回 null 时按空 List 兜底）
     */
    public List<String> getAdditionalDirectories() {
        List<String> dirs = additionalDirectoriesSupplier.get();
        return dirs == null ? List.of() : dirs;
    }

    /**
     * 注入 cwd 供应 · 供 {@link #getSkillDirCommands(String)} cwd 入参为空时回退。null → 忽略。
     */
    public void setCwdSupplier(Supplier<String> cwdSupplier) {
        if (cwdSupplier != null) {
            this.cwdSupplier = cwdSupplier;
        }
    }

    /**
     * 注入 bare 模式判定（测试用）· 等价 CC envUtils.ts:60-65 {@code isBareMode()}。
     * null → 忽略（保持 env 判定）。
     */
    public void setBareModeSupplier(Supplier<Boolean> bareModeSupplier) {
        if (bareModeSupplier != null) {
            this.bareModeSupplier = bareModeSupplier;
        }
    }

    /**
     * 注入 managed（policySettings）技能加载开关（测试用）· 等价 CC loadSkillsDir.ts:686-688
     * {@code CLAUDE_CODE_DISABLE_POLICY_SKILLS} 门控。null → 忽略。
     */
    public void setManagedSkillsEnabledSupplier(Supplier<Boolean> managedSkillsEnabledSupplier) {
        if (managedSkillsEnabledSupplier != null) {
            this.managedSkillsEnabledSupplier = managedSkillsEnabledSupplier;
        }
    }

    /**
     * 注入 user（userSettings）技能加载开关（测试/组合根用）· P2-2，等价 CC
     * {@code isSettingSourceEnabled('userSettings')}（settings/constants.ts:174-177）。null → 忽略。
     */
    public void setUserSkillsEnabledSupplier(Supplier<Boolean> userSkillsEnabledSupplier) {
        if (userSkillsEnabledSupplier != null) {
            this.userSkillsEnabledSupplier = userSkillsEnabledSupplier;
        }
    }

    /**
     * 注入 project（projectSettings）技能加载开关（测试/组合根用）· P2-2，等价 CC
     * {@code isSettingSourceEnabled('projectSettings')}（settings/constants.ts:174-177）。null → 忽略。
     */
    public void setProjectSkillsEnabledSupplier(Supplier<Boolean> projectSkillsEnabledSupplier) {
        if (projectSkillsEnabledSupplier != null) {
            this.projectSkillsEnabledSupplier = projectSkillsEnabledSupplier;
        }
    }

    /**
     * 注入 Telemetry · 转发到 {@link MarkdownConfigLoader#setTelemetry}（tengu_dir_search 搜索路径遥测）。
     *
     * <p><b>[拍板#7 · NG-LD-1]</b>：本类搜索路径（{@link #getSkillDirCommands} → legacy
     * {@code LegacyCommandsLoader.loadSkillsFromCommandsDir} → {@link MarkdownConfigLoader#loadMarkdownFilesForSubdir}
     * "commands"）是 tengu_dir_search 的生产触发链；生产接线主载体为 {@link MarkdownConfigLoader}
     * {@code @Component} 构造器（Spring 启动注入 Telemetry → 静态字段，对齐 PostCompactionState /
     * MemoryBareModeConfig 静态桥接模式）。本 setter 为显式注入 seam（对齐
     * {@link #setDynamicSkillsManager} / {@link #setSettingsSupplier} 模式），供组合根或测试
     * 直接注入。null → 忽略（保持静态桥接现状）。
     *
     * @param telemetry Telemetry bean（可 null）
     */
    public void setTelemetry(Telemetry telemetry) {
        if (telemetry != null) {
            MarkdownConfigLoader.setTelemetry(telemetry);
        }
    }

    /**
     * bare 模式判定 · 对齐 CC envUtils.ts:60-65 {@code isBareMode()} —— 跳过自动发现
     * （managed/user/project 目录遍历 + legacy commands），仅加载显式 --add-dir 路径；
     * skillsLocked 仍适用（bare 非 policy 绕过，loadSkillsDir.ts:654-657）。
     *
     * @return true = --bare（CLAUDE_CODE_SIMPLE truthy）
     */
    public boolean isBareMode() {
        return Boolean.TRUE.equals(bareModeSupplier.get());
    }

    /**
     * 从 skills 目录加载所有用户技能 · 对齐 CC getSkillDirCommands(dir)
     *
     * <p><b>P2-12 skillsLocked 门控</b>（CC original: loadSkillsDir.ts:650
     * {@code const skillsLocked = isRestrictedToPluginOnly('skills')} + pluginOnlyPolicy.ts:19-27）：
     * 入口先判 {@link PluginOnlyPolicy#isRestrictedToPluginOnly(String, Supplier)}，锁定 → 该用户技能根
     * 直接返回空、不扫描磁盘（对齐 CC :689 {@code isSettingSourceEnabled('userSettings') && !skillsLocked
     * ? loadSkillsFromSkillsDir(userSkillsDir,'userSettings') : Promise.resolve([])}）。Java 单一技能根
     * = CC userSettings 源锁定语义；managed/policySettings 源不在本类（P2-20 多源落地时须绕过门控，
     * 登记为 P2-20 依赖）。
     *
     * <p><b>无双门控</b>：门控只在 getSkillDirCommands(:650) 与 addSkillDirectories(:925-927) 两个调用点，
     * 低层 loadSkillsFromSkillsDir（:407）本身无 skillsLocked 检查 —— {@link #loadFromDirectoryUnconditional}
     * 与私有核心 {@link #loadFromDirectory(String, boolean)} 一律不门控（addSkillDirectories 门控已由
     * DynamicSkillsManager.isProjectSettingsEnabled:468-470 对齐），避免与动态目录加载双门控。
     *
     * <p>P1-2 条件分离（对齐 loadSkillsDir.ts:771-790）：{@code paths} 非空且未激活的条件技能
     * 不再随返回值暴露为无条件技能，而是登记到 {@link DynamicSkillsManager#registerConditional}。
     * 已激活条件技能（activatedConditionalSkillNames 含该名）→ 正常返回（CC :779 判断）。
     *
     * @param skillsRoot skills 目录根路径
     * @return 加载的 Command 列表（不含内置 bundled 技能，不含 paths 非空且未激活的条件技能）
     */
    public List<Command> loadFromDirectory(String skillsRoot) {
        if (PluginOnlyPolicy.isRestrictedToPluginOnly(PluginOnlyPolicy.SURFACE_SKILLS, settingsSupplier)) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillsLoader] skillsLocked: strictPluginOnlyCustomization 锁定 skills 面，"
                    + "跳过用户技能目录: {}（CC loadSkillsDir.ts:650 + :689 用户源返回空）", skillsRoot);
            }
            return List.of();
        }
        // 单目录 POJO/测试回退：source=USER + loadedFrom=SKILLS（语义 = CC userSettings 源 :689，
        // loadSkillsFromSkillsDir :467 loadedFrom:'skills'）
        return loadFromDirectory(skillsRoot, true, CommandSource.USER, CommandLoadedFrom.SKILLS);
    }

    /**
     * 无条件加载变体（不分离条件技能）· 供 {@link DynamicSkillsManager#addSkillDirectories}
     * 动态目录加载复用（对齐 CC loadSkillsFromSkillsDir — 条件分离只发生在 getSkillDirCommands，
     * 动态发现目录保持全量）。
     *
     * <p>P2-19 source 拆分：动态技能目录（CC loadSkillsDir.ts:941
     * {@code addSkillDirectories → loadSkillsFromSkillsDir(dir, 'projectSettings')}）源为
     * projectSettings → 本变体固定 {@link CommandSource#PROJECT_SETTINGS}（旧实现折叠 USER）。
     *
     * @param skillsRoot skills 目录根路径
     * @return 加载的 Command 列表（含 paths 非空技能）
     */
    public List<Command> loadFromDirectoryUnconditional(String skillsRoot) {
        return loadFromDirectory(skillsRoot, false, CommandSource.PROJECT_SETTINGS, CommandLoadedFrom.SKILLS);
    }

    /**
     * P2-20: 五源技能加载 · 对齐 CC {@code getSkillDirCommands(cwd)}（loadSkillsDir.ts:638-804）。
     *
     * <p>五源 + per-source 门控（CC :686-714）：
     * <ul>
     *   <li><b>managed</b>（policySettings，:686-688）：{@code !isEnvTruthy(CLAUDE_CODE_DISABLE_POLICY_SKILLS)}
     *       → 加载 {@code getManagedFilePath()/.claude/skills}，门控 = {@link #managedSkillsEnabledSupplier}</li>
     *   <li><b>user</b>（userSettings，:689-691）：{@code !skillsLocked} → 加载 <b>[T3 双目录]</b>
     *       {@code NexusaiPaths.getAppConfigHomeDir()/skills}（nexusai 自有根优先）+
     *       {@code getClaudeConfigHomeDir()/skills}（claude 回落），均 source=USER</li>
     *   <li><b>project</b>（projectSettings，:692-698）：{@code !skillsLocked} → {@code getProjectDirsUpToHome('skills', cwd)}
     *       逐层加载（up-to-home）</li>
     *   <li><b>additional</b>（projectSettings，:699-708）：{@code !skillsLocked} → 每个
     *       additionalDir/.claude/skills</li>
     *   <li><b>legacy</b>（commands_DEPRECATED，:713）：{@code skillsLocked ? [] : loadSkillsFromCommandsDir(cwd)}</li>
     * </ul>
     *
     * <p><b>bare 模式</b>（:658-675）：{@link #isBareMode()} → additionalDirs 空或 !projectSettingsEnabled 返回
     * []；否则仅加载 additionalDirs/.claude/skills，无去重（用户显式控制唯一性，:673）。
     *
     * <p><b>realpath 去重 first-wins</b>（:728-763）：顺序 managed &gt; user &gt; project &gt; additional &gt; legacy，
     * 按 {@link Command#getContentPath()} realpath（fail-open null 保留）。非 prompt 型命令被丢弃（CC :744）。
     *
     * <p><b>条件分离</b>（:771-790）：paths 非空且未激活 → {@link DynamicSkillsManager#registerConditional}，
     * 不随返回值暴露；manager 为 null（POJO）→ 全量返回（不分离）。
     *
     * @param cwd 当前工作目录（project-up-to-home 遍历基准；空 → 回退 {@link #cwdSupplier}）
     * @return 无条件技能列表（不含条件技能 / 非 prompt）
     */
    public List<Command> getSkillDirCommands(String cwd) {
        String effectiveCwd = (cwd == null || cwd.isBlank()) ? cwdSupplier.get() : cwd;
        // T3: 内容读兼容（nexusai 复刻版 .claude 改造）—— 用户技能源拆两层：
        //   nexusai 自有根（~/.{appName}/skills，NexusaiPaths）优先 + claude（~/.claude/skills）回落。
        //   nexusai 在前加载（source=USER），同 name 时由 name 去重层保证 nexusai 赢（见 dedupByName）。
        String nexusaiUserSkillsDir = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "skills").toString();
        String claudeUserSkillsDir = Paths.get(ClaudePaths.getClaudeConfigHomeDir(), "skills").toString();
        String managedSkillsDir = Paths.get(ClaudePaths.getManagedFilePath(), ".claude", "skills").toString();
        List<String> projectSkillsDirs = MarkdownConfigLoader.getProjectDirsUpToHome("skills", effectiveCwd);
        List<String> additionalDirs = additionalDirectoriesSupplier.get();
        boolean skillsLocked = PluginOnlyPolicy.isRestrictedToPluginOnly(PluginOnlyPolicy.SURFACE_SKILLS, settingsSupplier);
        // P2-2: 引入 user/project 加载开关（CC isSettingSourceEnabled，settings/constants.ts:174-177）。
        //   Java 无 CLI --settings（concern #2 补开关）：默认 () -> true 对齐 CC 缺省全源启用，
        //   projectSettingsEnabled = isSettingSourceEnabled('projectSettings') && !skillsLocked（:651-652）。
        boolean userSettingsEnabled = Boolean.TRUE.equals(userSkillsEnabledSupplier.get());
        boolean projectSettingsEnabled =
            Boolean.TRUE.equals(projectSkillsEnabledSupplier.get()) && !skillsLocked;

        if (log.isDebugEnabled()) {
            log.debug("[SkillsLoader] getSkillDirCommands(cwd={}): managed={}, nexusaiUser={}, claudeUser={}, "
                    + "project={}, additional={}, skillsLocked={} (CC loadSkillsDir.ts:640-652)",
                effectiveCwd, managedSkillsDir, nexusaiUserSkillsDir, claudeUserSkillsDir,
                projectSkillsDirs, additionalDirs, skillsLocked);
        }

        // bare 模式（CC :658-675）：仅加载显式 additionalDirs，跳过自动发现；skillsLocked 仍适用
        if (isBareMode()) {
            if (additionalDirs == null || additionalDirs.isEmpty() || !projectSettingsEnabled) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillsLoader] bare 模式跳过技能目录发现 (additionalDirs 空或 projectSettings 禁用) "
                        + "(CC loadSkillsDir.ts:659-663)");
                }
                return List.of();
            }
            List<Command> bareSkills = new ArrayList<>();
            for (String dir : additionalDirs) {
                // P2-19 source 拆分：bare 模式 additional 源 = projectSettings（CC :665-674
                // loadSkillsFromSkillsDir(join(dir,'.claude','skills'), 'projectSettings')）。
                // 决策 D1/D6：additionalDir 技能目录 nexusai 优先 + claude 回落（与 project 源同构，
                //   .nexusai/skills 在前加载，bare 无去重时 nexusai 定义优先暴露）
                bareSkills.addAll(loadFromDirectory(
                    Paths.get(dir, NexusaiPaths.getProjectDirName(), "skills").toString(), false, CommandSource.PROJECT_SETTINGS, CommandLoadedFrom.SKILLS)); // nexusai 优先
                bareSkills.addAll(loadFromDirectory(
                    Paths.get(dir, ".claude", "skills").toString(), false, CommandSource.PROJECT_SETTINGS, CommandLoadedFrom.SKILLS)); // claude 回落
            }
            // 无去重 —— 显式目录，用户控制唯一性（CC :673）
            if (log.isDebugEnabled()) {
                log.debug("[SkillsLoader] bare 模式加载 {} 个技能 (additionalDirs 仅) (CC loadSkillsDir.ts:665-674)",
                    bareSkills.size());
            }
            return bareSkills;
        }

        // 五源并行加载（每源独立，顺序 managed>user>project>additional>legacy）
        // P2-21: 磁盘四源 source/loadedFrom 落位 —— managed（policySettings）→ source=POLICY_SETTINGS
        //   （△-1/组4 全部对齐 CC loadSkillsDir.ts:688 source='policySettings'，不再折叠 USER）
        //   + loadedFrom=SKILLS（CC :467/:688 managed 经 loadSkillsFromSkillsDir loadedFrom 恒
        //   'skills'，绝非 bundled —— 修正误当 bundled 特权的行为 bug）；
        //   user → source=USER（CC :689-691 userSettings）+ loadedFrom=SKILLS；
        //   project/additional → source=PROJECT_SETTINGS（P2-19 拆分，CC :695/:704
        //   loadSkillsFromSkillsDir(dir,'projectSettings')）+ loadedFrom=SKILLS。
        List<Command> managed = Boolean.TRUE.equals(managedSkillsEnabledSupplier.get())
            ? loadFromDirectory(managedSkillsDir, false, CommandSource.POLICY_SETTINGS, CommandLoadedFrom.SKILLS) // :686-688
            : List.of();
        // T3: 用户技能源两层（nexusai 自有根优先 + claude 回落），均 source=USER（CC :689-691）。
        //   顺序 = [nexusaiUser, claudeUser]，name 去重层据此保证 nexusai 同名覆盖 claude；
        //   nexusai 无同名则 claude 正常加载（回落语义）。
        List<Command> user = new ArrayList<>();
        if (userSettingsEnabled && !skillsLocked) {
            user.addAll(loadFromDirectory(nexusaiUserSkillsDir, false, CommandSource.USER, CommandLoadedFrom.SKILLS)); // nexusai 用户源优先
            user.addAll(loadFromDirectory(claudeUserSkillsDir, false, CommandSource.USER, CommandLoadedFrom.SKILLS)); // claude 回落
        }
        List<Command> project = new ArrayList<>();
        if (projectSettingsEnabled) {
            for (String d : projectSkillsDirs) {
                project.addAll(loadFromDirectory(d, false, CommandSource.PROJECT_SETTINGS, CommandLoadedFrom.SKILLS)); // :692-698
            }
        }
        List<Command> additional = new ArrayList<>();
        if (projectSettingsEnabled) {
            for (String d : additionalDirs) {
                // 决策 D1/D6：additionalDir 技能目录 nexusai 优先 + claude 回落（:699-708）——
                //   .nexusai/skills 在前加载，realpath/name 去重时 nexusai 赢（与 user 双层同构）
                additional.addAll(loadFromDirectory(
                    Paths.get(d, NexusaiPaths.getProjectDirName(), "skills").toString(), false, CommandSource.PROJECT_SETTINGS, CommandLoadedFrom.SKILLS)); // nexusai 优先
                additional.addAll(loadFromDirectory(
                    Paths.get(d, ".claude", "skills").toString(), false, CommandSource.PROJECT_SETTINGS, CommandLoadedFrom.SKILLS)); // claude 回落
            }
        }
        // legacy commands-as-skills（CC :709-713，skillsLocked 时也是 skills，阻断）
        List<Command> legacy = skillsLocked ? List.of() : LegacyCommandsLoader.loadSkillsFromCommandsDir(effectiveCwd);

        List<Command> allSkills = new ArrayList<>();
        allSkills.addAll(managed);
        allSkills.addAll(user);
        allSkills.addAll(project);
        allSkills.addAll(additional);
        allSkills.addAll(legacy);

        // realpath 去重 first-wins（CC :728-763，按 filePath 顺序 managed>...>legacy）
        List<Command> realpathDeduped = dedupByRealpath(allSkills);
        int duplicatesRemoved = allSkills.size() - realpathDeduped.size();
        if (duplicatesRemoved > 0 && log.isDebugEnabled()) {
            log.debug("[SkillsLoader] 跨源去重移除 {} 个技能 (same file) (CC loadSkillsDir.ts:765-769)",
                duplicatesRemoved);
        }
        // T3: name first-wins 去重层（内容读兼容）—— 必须在 realpath 去重之后（先折叠 symlink
        //   同物理文件，保留 CC :728-763 既有语义，再按逻辑名折叠双目录同内容）。
        //   按 Command.getName() 键、优先级 = 列表序 managed > nexusai用户 > claude用户 >
        //   project > additional > legacy：同 name 后源丢弃（nexusai 覆盖 claude，nexusai 无同名
        //   则 claude 正常加载）。
        List<Command> deduplicated = dedupByName(realpathDeduped);
        int nameDuplicatesRemoved = realpathDeduped.size() - deduplicated.size();
        if (nameDuplicatesRemoved > 0 && log.isDebugEnabled()) {
            log.debug("[SkillsLoader] name 去重移除 {} 个技能 (same name，nexusai 优先) (T3 内容读兼容)",
                nameDuplicatesRemoved);
        }

        // 条件分离（CC :771-790）
        List<Command> unconditional = new ArrayList<>();
        int conditionalCount = 0;
        for (Command skill : deduplicated) {
            if (shouldSeparateAsConditional(skill)) {
                dynamicSkillsManager.registerConditional(skill);
                conditionalCount++;
                if (log.isDebugEnabled()) {
                    log.debug("[SkillsLoader] 条件技能分离存储 (待 paths 激活): {} (CC loadSkillsDir.ts:788-790)",
                        skill.getName());
                }
            } else {
                unconditional.add(skill);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillsLoader] getSkillDirCommands 汇总: 合并 {} → realpath去重 {} → name去重 {} "
                    + "(无条件 {}, 条件 {}); managed={} user(nexusai+claude)={} project={} additional={} legacy={} "
                    + "(CC loadSkillsDir.ts:798-801)",
                allSkills.size(), realpathDeduped.size(), deduplicated.size(),
                unconditional.size(), conditionalCount,
                managed.size(), user.size(), project.size(), additional.size(), legacy.size());
        }
        return unconditional;
    }

    /**
     * 跨源 realpath 去重 first-wins · 对齐 CC loadSkillsDir.ts:728-763（getFileIdentity=realpath(filePath)，:118-124）。
     * 顺序 = 传入列表序（调用方保证 managed&gt;user&gt;project&gt;additional&gt;legacy）；非 prompt 型丢弃（CC :744）；
     * realpath fail-open null → 保留（CC :748-751）。
     */
    private static List<Command> dedupByRealpath(List<Command> allSkills) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Command> result = new ArrayList<>();
        for (Command skill : allSkills) {
            if (skill == null || !"prompt".equals(skill.getType())) {
                // 非 prompt 型不进入去重结果（CC :744 entry.skill.type !== 'prompt' continue）
                if (log.isDebugEnabled() && skill != null) {
                    log.debug("[SkillsLoader] 丢弃非 prompt 型技能 (去重环): {}", skill.getName());
                }
                continue;
            }
            String fileId = realpathOf(skill.getContentPath());
            if (fileId == null) {
                result.add(skill); // fail-open（CC :748-751）
                continue;
            }
            if (seen.add(fileId)) {
                result.add(skill);
            } else if (log.isDebugEnabled()) {
                log.debug("[SkillsLoader] 跳过重复技能 '{}' (same file already loaded) (CC loadSkillsDir.ts:753-759)",
                    skill.getName());
            }
        }
        return result;
    }

    /**
     * T3: name first-wins 去重层（内容读兼容 · nexusai 复刻版 .claude 改造）。
     *
     * <p>优先级 = 列表序（调用方保证 managed &gt; nexusai用户 &gt; claude用户 &gt; project &gt;
     * additional &gt; legacy），按 {@link Command#getName()} 键 first-wins —— 同 name 时前源
     * （nexusai）覆盖后源（claude），nexusai 无同名则 claude 正常加载（后源同名丢弃）。
     *
     * <p><b>必须在 {@link #dedupByRealpath} 之后执行</b>：先按 realpath 折叠 symlink 同物理文件
     * （CC loadSkillsDir.ts:728-763 既有语义，防 symlink 双加载），再按逻辑名折叠双目录同内容
     * （nexusai 用户根与 claude 用户根是不同物理目录，realpath 不折叠，需 name 层）。
     */
    private static List<Command> dedupByName(List<Command> skills) {
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        List<Command> result = new ArrayList<>();
        for (Command skill : skills) {
            if (skill == null) {
                continue;
            }
            String name = skill.getName();
            if (name == null || name.isBlank() || seenNames.add(name)) {
                result.add(skill);
            } else if (log.isDebugEnabled()) {
                log.debug("[SkillsLoader] 跳过同名技能 '{}' (name first-wins，nexusai 优先) (T3 内容读兼容去重)",
                    name);
            }
        }
        return result;
    }

    /** realpath 解析（fail-open null）· 对齐 CC getFileIdentity（loadSkillsDir.ts:118-124）。 */
    private static String realpathOf(String contentPath) {
        if (contentPath == null || contentPath.isBlank()) {
            return null;
        }
        try {
            return Paths.get(contentPath).toRealPath().toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 核心加载逻辑 · 对齐 CC getSkillDirCommands（loadSkillsDir.ts:638-804）。
     *
     * @param skillsRoot            skills 目录根路径
     * @param separateConditionals  true = 执行条件分离（getSkillDirCommands 语义）；false = 全量返回
     *                              （loadSkillsFromSkillsDir 动态目录语义）
     * @param source                Command.source（CC PromptCommand.source，command.ts:32 —— managed
     *                              （policySettings）落 POLICY_SETTINGS（CC loadSkillsDir.ts:688，
     *                              △-1/组4 对齐）；user/project/additional 落 USER（CC :689-708））
     * @param loadedFrom            Command.loadedFrom（CC LoadedFrom，loadSkillsDir.ts:67-74 —— 磁盘技能
     *                              恒 SKILLS，:467）
     * @return 加载的 Command 列表
     */
    private List<Command> loadFromDirectory(String skillsRoot, boolean separateConditionals, CommandSource source, CommandLoadedFrom loadedFrom) {
        Path root = Paths.get(skillsRoot);
        if (!Files.isDirectory(root)) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillsLoader] Skills root not found: {}", skillsRoot);
            }
            return List.of();
        }

        List<Command> skills = new ArrayList<>();
        // P1-6: realpath 去重集合 — 同一物理目录 (symlink / 大小写别名) 只加载一次.
        //   对齐 CC loadSkillsDir.ts:113-120 realpath 规范化去重.
        java.util.Set<String> seenReal = new java.util.HashSet<>();
        try (Stream<Path> dirs = Files.list(root)) {
            Iterator<Path> it = dirs.iterator();
            while (it.hasNext()) {
                Path dir = it.next();
                if (!Files.isDirectory(dir)) continue;

                // P1-6: 用 toRealPath 规范化 (解析 symlink + 在大小写不敏感 FS 上折叠大小写),
                //   作为去重 key; 失败则 fail-open 用 normalize 绝对路径.
                String realKey;
                try {
                    realKey = dir.toRealPath().toString();
                } catch (IOException e) {
                    realKey = dir.toAbsolutePath().normalize().toString();
                }
                if (!seenReal.add(realKey)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SkillsLoader] 跳过重复 skill 目录 (realpath 已加载): {}", dir);
                    }
                    continue;
                }

                // X1 删除：不再检查旧 skill.json（CC loadSkillsFromSkillsDir :435-445 仅 SKILL.md，ENOENT 即 skip）
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.exists(skillMd)) {
                    continue;
                }
                Command c = loadFromSkillMd(dir, skillMd, source, loadedFrom);
                if (c != null) {
                    // P1-2: 条件分离 · 对齐 CC loadSkillsDir.ts:771-790
                    //   skill.type==='prompt' && paths 非空 && !activatedConditionalSkillNames.has(name)
                    //   → conditionalSkills.set(name, skill)，不随 getSkillDirCommands 返回
                    if (separateConditionals && shouldSeparateAsConditional(c)) {
                        dynamicSkillsManager.registerConditional(c);
                        if (log.isDebugEnabled()) {
                            log.debug("[SkillsLoader] 条件技能分离存储 (待 paths 激活): {} from {}",
                                c.getName(), skillMd);
                        }
                    } else {
                        skills.add(c);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[SkillsLoader] Failed to list {}: {}", root, e.getMessage());
        }

        if (log.isDebugEnabled()) {
            log.debug("[SkillsLoader] Loaded {} skill(s) from {}", skills.size(), skillsRoot);
        }
        return skills;
    }

    /**
     * 从单个 SKILL.md 文件加载技能（默认 source=USER + loadedFrom=SKILLS · POJO/测试路径）。
     *
     * @param dir    技能目录
     * @param skillMd SKILL.md 文件路径
     * @return 加载的 Command；读失败返回 null
     */
    public Command loadFromSkillMd(Path dir, Path skillMd) {
        return loadFromSkillMd(dir, skillMd, CommandSource.USER, CommandLoadedFrom.SKILLS);
    }

    /**
     * 从单个 SKILL.md 文件加载技能 · 对齐 CC loadSkillsFromSkillsDir（loadSkillsDir.ts:407-480）。
     *
     * <p>P2-21：磁盘技能 source + loadedFrom 独立落位（CC command.ts:32 source 与 :191-197 loadedFrom
     * 是两独立字段，M20 △ 根因）——managed（policySettings，:688）→ source=POLICY_SETTINGS（△-1/组4
     * 对齐，不再折叠 USER）+ loadedFrom=SKILLS（CC :467 loadedFrom:'skills'，managed 绝非 bundled，
     * bundled 特权不授予）；user（userSettings）→ source=USER、project（projectSettings）→
     * source=PROJECT_SETTINGS + loadedFrom=SKILLS（P2-19 拆分，project 不再折叠进 USER）。
     *
     * @param dir       技能目录
     * @param skillMd   SKILL.md 文件路径
     * @param source    Command.source（CC source 字段）
     * @param loadedFrom Command.loadedFrom（CC loadedFrom 字段，磁盘技能恒 SKILLS）
     * @return 加载的 Command；读失败返回 null
     */
    private Command loadFromSkillMd(Path dir, Path skillMd, CommandSource source, CommandLoadedFrom loadedFrom) {
        String raw;
        try {
            raw = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SkillsLoader] Cannot read {}: {}", skillMd, e.getMessage());
            return null;
        }

        Map<String, Object> fm = parser.parse(raw);
        String body = parser.extractBody(raw);

        // P1-5: name 恒=目录名（CC loadSkillsDir.ts:452 `const skillName = entry.name`），
        //   frontmatter.name 只作 displayName（:238-239），不再反置为 name 优先。
        //   ⚠️ 现网技能样本 fm.name 均 == 目录名，去重/持久化键无漂移（SkillRegistry putIfAbsent / CommandService name 主键）。
        String name = dir.getFileName().toString();

        Command c = new Command();
        c.setName(name);
        // P0-6: description 双段流程 —— coerceDescriptionToString ?? extractDescriptionFromMarkdown(body, 'Skill')
        //   对齐 CC loadSkillsDir.ts:208-214 parseSkillFrontmatterFields description（技能侧 fallback 标签 'Skill'）
        //   coerce 对数组/对象 description 返回 null + warn，走 markdown 首行提取；不取 List 首元素
        String validated = ParseSkillFrontmatter.coerceDescriptionToString(fm.get("description"), name, null);
        c.setDescription(validated != null
            ? validated
            : ParseSkillFrontmatter.extractDescriptionFromMarkdown(body, "Skill"));
        // P1-5: hasUserSpecifiedDescription = coerce 结果非 null（CC loadSkillsDir.ts:241）
        c.setHasUserSpecifiedDescription(validated != null);
        c.setContent(body);
        c.setContentPath(skillMd.toAbsolutePath().toString());
        c.setBaseDir(dir.toAbsolutePath().toString());
        c.setSource(source);
        // P2-21: loadedFrom 独立落位（CC loadSkillsDir.ts:467 loadedFrom:'skills'）
        c.setLoadedFrom(loadedFrom);
        if (log.isDebugEnabled()) {
            log.debug("[SkillsLoader] 加载技能 '{}' source={} loadedFrom={} (CC loadSkillsDir.ts:461-469)",
                name, source, loadedFrom);
        }

        // 从 frontmatter 提取所有字段（P2-13 follow-up 闭环：统一复用 parseSkillFrontmatterFields）
        applyFrontmatter(c, fm, body);

        return c;
    }

    /**
     * 应用 frontmatter 到 Command · P2-13 follow-up 闭环：统一复用
     * {@link ParseSkillFrontmatter#parseSkillFrontmatterFields}（CC loadSkillsDir.ts:185-265），
     * 消除本方法 13 字段手写映射双实现漂移。保留 Java 扩展字段（paths）独立处理。
     *
     * <p>frontmatter 键直接按 CC kebab/snake 键解析（ParseSkillFrontmatter 只读 CC 键），
     * 无 camelCase 旧键回退（CC parseSkillFrontmatterFields :185-265 亦无回退，已删除
     * normalizeKebabAliases/copyIfAbsent/pick，LD-⊕-1 全量对齐 CC）。
     *
     * @param c    目标 Command
     * @param fm   已解析 frontmatter Map（原始 YAML 键，ParseSkillFrontmatter.parse 产物）
     * @param body 去除 frontmatter 后的 markdown 正文（不 trim，CC :214）
     */
    @SuppressWarnings("unchecked")
    private void applyFrontmatter(Command c, Map<String, Object> fm, String body) {
        // Java 扩展：paths（CC :182-184 caller 单独提供）——parseSkillPaths 对齐 glob 语义
        List<String> skillPaths = ParseSkillFrontmatter.parseSkillPaths(fm.get("paths"));
        if (skillPaths != null) c.setPaths(skillPaths);

        // 16 字段统一解析（CC kebab/snake 键，无 camelCase 回退）
        SkillFrontmatterFields parsed = ParseSkillFrontmatter.parseSkillFrontmatterFields(
            fm, body, c.getName(), "Skill");
        if (parsed.displayName() != null) c.setDisplayName(parsed.displayName());
        if (parsed.description() != null) c.setDescription(parsed.description());
        c.setHasUserSpecifiedDescription(parsed.hasUserSpecifiedDescription());
        c.setAllowedTools(parsed.allowedTools());
        c.setModel(parsed.model());
        c.setContext(parsed.executionContext() != null ? parsed.executionContext() : "inline");
        c.setAgent(parsed.agent());
        c.setVersion(parsed.version());
        c.setArgumentHint(parsed.argumentHint());
        c.setWhenToUse(parsed.whenToUse());
        c.setEffort(parsed.effort());
        c.setHooks(parsed.hooks());
        c.setUserInvocable(parsed.userInvocable());
        c.setDisableModelInvocation(parsed.disableModelInvocation());
        c.setShell(parsed.shell());
        // P1-2: 磁盘技能 isHidden/progressMessage 补齐（CC createSkillCommand loadSkillsDir.ts:335-336
        //   isHidden: !userInvocable + progressMessage: 'running'）——此前磁盘主路径漏设两字段，
        //   user-invocable:false 磁盘技能 UI 不隐藏、progressMessage 恒 null（EV-WF1-LD-010 闭合）。
        c.setIsHidden(!Boolean.TRUE.equals(parsed.userInvocable()));   // CC :335 isHidden: !userInvocable
        c.setProgressMessage("running");                                // CC :336 progressMessage: 'running'
        // CC :324 argNames: argumentNames.length>0?argumentNames:undefined —— 空数组→null
        c.setArgNames(parsed.argumentNames().isEmpty() ? null : parsed.argumentNames());
        if (log.isDebugEnabled()) {
            log.debug("[SkillsLoader] applyFrontmatter 16 字段解析完成: name={} desc={} shell={} "
                    + "(CC loadSkillsDir.ts:185-265)",
                c.getName(), parsed.description(), parsed.shell());
        }
    }

    /**
     * P1-2: 是否应分离为条件技能 · 对齐 CC loadSkillsDir.ts:775-780
     * {@code skill.type==='prompt' && skill.paths && skill.paths.length>0 && !activatedConditionalSkillNames.has(skill.name)}。
     * <p>Java Command 无 type 字段（loadFromSkillMd 产物恒为 prompt 技能），故仅判 paths 非空 +
     * 未激活。manager 为 null → false（POJO 兼容，全量返回）。
     */
    private boolean shouldSeparateAsConditional(Command c) {
        if (dynamicSkillsManager == null) {
            return false;
        }
        List<String> paths = c.getPaths();
        if (paths == null || paths.isEmpty()) {
            return false;
        }
        return !dynamicSkillsManager.isActivatedConditionalSkill(c.getName());
    }
}
