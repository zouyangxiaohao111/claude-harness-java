package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径校验会话上下文 · 供 {@link PathValidation} 内部路径白名单判定使用。
 *
 * <p>对齐 CC 内部路径白名单的全局会话状态来源：
 * <ul>
 *   <li>{@code getSessionId()}/{@code getAgentId()}（bootstrap/state.ts）→ {@link #sessionId}/{@link #agentId}</li>
 *   <li>{@code getCwd()}（utils/cwd.ts，会话当前工作目录）→ {@link #effectiveCwd}</li>
 *   <li>{@code getOriginalCwd()}（进程启动 cwd）→ {@link #originalCwd}</li>
 *   <li>{@code getClaudeConfigHomeDir()}（envUtils.ts:7-14）→ {@link #claudeConfigHomeDir}（CC 只读兼容根，D3/D4 读取回落源）</li>
 *   <li>{@code getNexusaiConfigHomeDir()}（镜像 envUtils.ts:7-14，自有根 NexusaiPaths）→ {@link #nexusaiConfigHomeDir}（受保护主根，D1）</li>
 *   <li>{@code getClaudeTempDir()}（filesystem.ts:331-347）→ {@link #claudeTempDir}</li>
 *   <li>{@code isScratchpadEnabled()}（filesystem.ts:298-300，Statsig tengu_scratch）→ {@link #scratchpadEnabled}</li>
 *   <li>{@code hasAutoMemPathOverride()}（memdir/paths.ts:194-196）→ {@link #hasAutoMemPathOverride}</li>
 *   <li>{@code isAutoMemPath()} 的基址 {@code getAutoMemPath()}（memdir/paths.ts）→ {@link #autoMemBaseDir}</li>
 *   <li>{@code getBundledSkillsRoot()}（filesystem.ts:365-370）→ {@link #bundledSkillsRoot}</li>
 * </ul>
 *
 * <p><b>路径形态（Java 平台映射）</b>：CC 各内部路径形状与 Java 端真实落盘介质对齐（探查
 * EV-FS-012~023 已核验），使白名单分支具备真实功能而非死代码。config-home 派生路径一律以
 * nexusai 自有根 {@link #nexusaiConfigHomeDir}（=~/.{appName}，决策 D1）为基址；claude 根
 * {@link #claudeConfigHomeDir}（=~/.claude）保留为只读兼容根（D3 transcript / D4 plugins 读取回落源）：
 * <ul>
 *   <li>session-memory：Java 存 {@code {nexusaiConfigHomeDir}/session-memory/}（MemoryFileDetection 自证，
 *       sessionStoragePortable 等价）；CC 为 {@code ~/.claude/projects/{sanitized-cwd}/{sessionId}/session-memory}。</li>
 *   <li>projects（transcript）：Java 存 {@code {nexusaiConfigHomeDir}/projects/} 直接子项（MemoryFileDetection 自证）；
 *       CC 为 {@code ~/.claude/projects/{sanitized-cwd}}。</li>
 *   <li>tool-results：Java 存 {@code {effectiveCwd}/{sessionId}/tool-results}（ToolResultStorage.getToolResultsDir）；CC 为 projectDir/sessionId/tool-results。</li>
 *   <li>plans：Java 存 {@code {nexusaiConfigHomeDir}/plans/{sessionId}.md}（PlanProviderImpl sessionId-as-slug）；CC 同构。</li>
 *   <li>tasks/teams：{@code {nexusaiConfigHomeDir}/tasks|teams}（CC 同构）。</li>
 *   <li>project-temp：{@code {claudeTempDir}/{sanitizePath(originalCwd)}}（CC 同构）。</li>
 *   <li>launch.json：{@code {originalCwd}/.claude/launch.json}（CC 同构，项目级）。</li>
 * </ul>
 *
 * <p>record 不可变；bean 派生字段（autoMemBaseDir/bundledSkillsRoot）经 wither 填充。
 * 非 Spring 测试可经 {@code new PathValidationEnv(...)} 直接构造任意前缀验证分支逻辑。
 */
public record PathValidationEnv(
        String sessionId,
        String agentId,
        String effectiveCwd,
        String originalCwd,
        String claudeConfigHomeDir,
        String nexusaiConfigHomeDir,
        boolean scratchpadEnabled,
        String claudeTempDir,
        boolean hasAutoMemPathOverride,
        String autoMemBaseDir,
        String bundledSkillsRoot
) {

    /**
     * 从 {@link ToolUseContext} 派生环境 · 供 ReadPermissionChecker / WritePermissionChecker 使用。
     *
     * <p>scratchpadEnabled 默认 false（Java 无 Statsig 门，CoordinatorMode.SCRATCHPAD_FEATURE 常量未接线为
     * 权限门，探查 EV-FS-016）；需启用时调用方经 {@link #withScratchpadEnabled(boolean)} 显式覆写。
     *
     * <p>双根接线（R3-6）：{@code claudeConfigHomeDir} = CC 只读兼容根（D3/D4 读取回落源）；
     * {@code nexusaiConfigHomeDir} = nexusai 自有受保护主根 {@link NexusaiPaths#getAppConfigHomeDir()}
     * （session-memory/projects/plans 派生覆盖 ~/.nexusai）。
     *
     * @param ctx 工具调用上下文（sessionId/agentId/effectiveCwd）
     * @return 路径校验环境（autoMemBaseDir/bundledSkillsRoot 待 wither 填充）
     */
    public static PathValidationEnv fromToolUseContext(ToolUseContext ctx) {
        // [WF-1D · DEL-06] originalCwd 走统一入口 CwdResolution.getOriginalCwdLayer(sessionId)
        // （对齐 CC getOriginalCwd() state.ts:500-502，作 projectTempDir/launchJsonPath 锚）。
        //   原 Java 直读 System.getProperty("user.dir")，绑定项目场景 originalCwd 恒 JVM
        //   启动目录 → projectTempDir/launch.json 落错根（违反 G9/G4 对齐）。D-1：不读 resolve()。
        String originalCwd = CwdResolution.getOriginalCwdLayer(
            ctx.sessionId() == null ? null : ctx.sessionId());
        return new PathValidationEnv(
            ctx.sessionId() == null ? null : ctx.sessionId(),
            ctx.agentId() == null ? null : ctx.agentId().toString(),
            ctx.effectiveCwd() == null ? null
                : ctx.effectiveCwd().toAbsolutePath().normalize().toString(),
            originalCwd,
            ClaudePaths.getClaudeConfigHomeDir(),
            NexusaiPaths.getAppConfigHomeDir(),
            false,
            NexusaiPaths.getAppTempDir(),
            false,
            null,
            null
        );
    }

    /**
     * 便捷构造（Bash 等无 ToolUseContext 调用方）：sessionId/agentId 为 null →
     * 会话级白名单分支（session-memory/scratchpad/plan/tool-results）不命中（fail-closed）。
     *
     * @param cwd   校验基准 cwd（BashPathValidator 的 cwd 参数）
     * @return 路径校验环境
     */
    public static PathValidationEnv forProcess(Path cwd) {
        // [WF-1D · DEL-06] originalCwd 走统一入口（对齐 CC getOriginalCwd）。forProcess 无
        //   sessionId 槽（record sessionId=null）→ getOriginalCwdLayer(null) 回落 user.dir
        //   （INV-4），但经统一入口，满足 INV-6「无 user.dir 直读残留于工作目录域」。
        String originalCwd = CwdResolution.getOriginalCwdLayer(null);
        return new PathValidationEnv(
            null,
            null,
            cwd == null ? null : cwd.toAbsolutePath().normalize().toString(),
            originalCwd,
            ClaudePaths.getClaudeConfigHomeDir(),
            NexusaiPaths.getAppConfigHomeDir(),
            false,
            NexusaiPaths.getAppTempDir(),
            false,
            null,
            null
        );
    }

    public PathValidationEnv withScratchpadEnabled(boolean scratchpadEnabled) {
        return new PathValidationEnv(sessionId, agentId, effectiveCwd, originalCwd,
            claudeConfigHomeDir, nexusaiConfigHomeDir, scratchpadEnabled, claudeTempDir,
            hasAutoMemPathOverride, autoMemBaseDir, bundledSkillsRoot);
    }

    public PathValidationEnv withAutoMem(AutoMemPaths autoMemPaths) {
        if (autoMemPaths == null) {
            return this;
        }
        String base = autoMemPaths.getAutoMemPath();
        return new PathValidationEnv(sessionId, agentId, effectiveCwd, originalCwd,
            claudeConfigHomeDir, nexusaiConfigHomeDir, scratchpadEnabled, claudeTempDir,
            autoMemPaths.hasAutoMemPathOverride(),
            base == null ? null : Path.of(base).normalize().toString(),
            bundledSkillsRoot);
    }

    public PathValidationEnv withBundledSkillsRoot(String root) {
        String normalized = root == null ? null : Path.of(root).normalize().toString();
        return new PathValidationEnv(sessionId, agentId, effectiveCwd, originalCwd,
            claudeConfigHomeDir, nexusaiConfigHomeDir, scratchpadEnabled, claudeTempDir,
            hasAutoMemPathOverride, autoMemBaseDir, normalized);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 派生内部路径 · 对齐 CC filesystem.ts 各 getXxx 等价
    //   基址 = nexusaiConfigHomeDir（=~/.{appName}，决策 D1 自有根）；claude 根仅作
    //   D3/D4 读取回落兼容（claudeConfigHomeDir 字段保留，isJobDirectoryPath 等仍消费）。
    // ────────────────────────────────────────────────────────────────────────

    /** CC getSessionMemoryDir（filesystem.ts:261-263）· Java 真实介质 = nexusaiConfigHome/session-memory。 */
    public String sessionMemoryDir() {
        return Path.of(nexusaiConfigHomeDir, "session-memory").normalize().toString()
            + java.io.File.separator;
    }

    /** CC getProjectDir(getCwd())（sessionStorage.ts getProjectDir）+ getProjectsDir 形状。 */
    public String projectDir() {
        return Path.of(nexusaiConfigHomeDir, "projects").normalize().toString()
            + java.io.File.separator;
    }

    /** CC getPlansDirectory() + getPlanSlug()（plans.ts:79-111；Java PlanProviderImpl sessionId-as-slug）。 */
    public String plansPrefix() {
        if (sessionId == null) {
            return null;
        }
        return Path.of(nexusaiConfigHomeDir, "plans", sessionId).normalize().toString();
    }

    /** CC getToolResultsDir()（toolResultStorage.ts:97-105）· Java 真实介质 = nexusaiConfigHome/projects/{slug}/sessionId/tool-results（S2 迁移）。 */
    public String toolResultsDir() {
        if (sessionId == null || effectiveCwd == null) {
            return null;
        }
        // S2: 用本 env 的 nexusaiConfigHomeDir 字段派生（对齐 ToolResultStorage.getToolResultsDir 的
        //   getProjectDir 派生），使白名单与 ToolResultStorage 落盘同根（模型 FileRead 读回不 fail-closed）。
        return Path.of(nexusaiConfigHomeDir, "projects")
            .resolve(AutoMemPaths.sanitizePath(Path.of(effectiveCwd).normalize().toString()))
            .resolve(sessionId).resolve("tool-results").normalize().toString();
    }

    /** CC getProjectTempDir()（filesystem.ts:376-378）· join(getClaudeTempDir(), sanitizePath(getOriginalCwd())) + sep。 */
    public String projectTempDir() {
        if (originalCwd == null || claudeTempDir == null) {
            return null;
        }
        String sanitized = AutoMemPaths.sanitizePath(Path.of(originalCwd).toAbsolutePath().normalize().toString());
        return Path.of(claudeTempDir, sanitized).normalize().toString()
            + java.io.File.separator;
    }

    /** CC getScratchpadDir()（filesystem.ts:384-386）· join(getProjectTempDir(), getSessionId(), 'scratchpad')。 */
    public String scratchpadDir() {
        String temp = projectTempDir();
        if (temp == null || sessionId == null) {
            return null;
        }
        return Path.of(temp, sessionId, "scratchpad").normalize().toString();
    }

    /** CC tasks 目录（filesystem.ts:1728）· join(getNexusaiConfigHomeDir(), 'tasks') + sep。 */
    public String tasksDir() {
        return Path.of(nexusaiConfigHomeDir, "tasks").normalize().toString()
            + java.io.File.separator;
    }

    /** CC teams 目录（filesystem.ts:1744）· join(getNexusaiConfigHomeDir(), 'teams') + sep。 */
    public String teamsDir() {
        return Path.of(nexusaiConfigHomeDir, "teams").normalize().toString()
            + java.io.File.separator;
    }

    /** CC launch.json 路径（filesystem.ts:1590-1592）· 决策 D1/D6：nexusai 优先 + claude 回落。 */
    public String launchJsonPath() {
        if (originalCwd == null) {
            return null;
        }
        Path nexusaiLaunch = Path.of(originalCwd, NexusaiPaths.getProjectDirName(), "launch.json");
        if (Files.exists(nexusaiLaunch)) {
            return nexusaiLaunch.normalize().toString();
        }
        return Path.of(originalCwd, ".claude", "launch.json").normalize().toString();
    }
}
