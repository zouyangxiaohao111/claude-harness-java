package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 路径校验会话上下文 · 供 {@link PathValidation} 内部路径白名单判定使用。
 *
 * <p>对齐 CC 内部路径白名单的全局会话状态来源：
 * <ul>
 *   <li>{@code getSessionId()}/{@code getAgentId()}（bootstrap/state.ts）→ {@link #sessionId}/{@link #agentId}</li>
 *   <li>{@code getCwd()}（utils/cwd.ts，会话当前工作目录）→ {@link #effectiveCwd}</li>
 *   <li>{@code getOriginalCwd()}（进程启动 cwd）→ {@link #originalCwd}</li>
 *   <li>{@code getProjectRoot()}（bootstrap/state.ts:498-508，<b>稳定</b>项目身份根，⛔ 不被 worktree 重锚）
 *       → {@link #sessionProjectRoot}（批 E2：session-memory / project-dir 两分支的 slug 锚，与落盘介质同源）</li>
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
 *   <li>session-memory：Java 存 {@code {nexusaiConfigHomeDir}/projects/{sanitizePath(sessionProjectRoot)}/
 *       {sessionId}/session-memory/summary.md}（<b>批 E2 实测自证</b>：config-home 下 50 个 summary.md
 *       全在此形状；写盘方 {@code SessionMemoryService.resolvePath:2272-2283}）；CC 同形
 *       {@code ~/.claude/projects/{sanitized-cwd}/{sessionId}/session-memory}。</li>
 *   <li>projects（transcript）：Java 存 {@code {nexusaiConfigHomeDir}/projects/{sanitizePath(sessionProjectRoot)}/}
 *       （<b>批 E2 收窄 + ③′ 两半</b>：白名单根 = <b>cwd 半</b>（{@code sanitizePath(effectiveCwd)}，对齐 CC
 *       {@code isProjectDirPath = getProjectDir(getCwd())} filesystem.ts:284-291；bash 通道经
 *       {@code forProcess} 的 {@code resolutionBase} 天然可得）<b>∪ 稳定根半</b>
 *       （{@code sanitizePath(sessionProjectRoot)}，介质同源 ⇒ 保写→读闭环）。旧实现放行整个
 *       {@code projects/} 根 = 实测 662 个 slug 全放行）；
 *       CC 为 {@code ~/.claude/projects/{sanitized-cwd}}。</li>
 *   <li>tool-results：Java 存 {@code {nexusaiConfigHomeDir}/projects/{sanitizePath(effectiveCwd)}/
 *       {sessionId}/tool-results}（ToolResultStorage.getToolResultsDir → SessionStorage.getProjectDir，
 *       锚 {@code ToolUseContext.effectiveCwd} = {@code CwdResolution.getCwd}）；CC 为
 *       projectDir/sessionId/tool-results。
 *       ⚠️ 本分支与 session-memory/project-dir 的锚<b>不同源</b>（effectiveCwd vs sessionProjectRoot）：
 *       二者在「未 cd / 未进 worktree」时同值 ⇒ 该分支被前两者遮蔽（同 CC 分支顺序）；
 *       分裂时（cd / worktree）本分支独立命中。</li>
 *   <li>plans：Java 存 {@code {nexusaiConfigHomeDir}/plans/{sessionId}.md}（PlanProviderImpl sessionId-as-slug）；CC 同构。</li>
 *   <li>tasks/teams：{@code {nexusaiConfigHomeDir}/tasks|teams}（CC 同构）。</li>
 *   <li>project-temp：{@code {claudeTempDir}/{sanitizePath(originalCwd)}}（CC 同构）。</li>
 *   <li>launch.json：{@code {originalCwd}/.claude/launch.json}（CC 同构，项目级）。</li>
 * </ul>
 *
 * <p>record 不可变；bean 派生字段（autoMemBaseDir/bundledSkillsRoot）经 wither 填充。
 * 非 Spring 测试可经 {@code new PathValidationEnv(...)} 直接构造任意前缀验证分支逻辑。
 *
 * @param sessionProjectRoot [批 E2] 会话<b>稳定项目根</b>（raw，未派生；对齐 CC {@code getProjectRoot()}
 *        bootstrap/state.ts:498-508）。{@link #sessionMemoryDir()} / {@link #projectDir()} 的 slug 由
 *        它派生，<b>必须与落盘介质同源</b>：介质链实测 =
 *        {@code SessionMemoryService.resolvePath}（SessionMemoryService.java:2272-2283）
 *        → {@code SessionStorage.sessionProjectDir(sessionId)}（SessionStorage.java:187-189）
 *        → {@code getProjectDir(CwdResolution.getProjectRoot(sessionId))}；transcript 同在
 *        {@code SessionStorage} 内按 {@code sessionProjectRoot} 派生。
 *        ⛔ <b>不能</b>改用 {@link #effectiveCwd}（= CC {@code getCwd()}，bash {@code cd} 会改）或
 *        {@link #originalCwd}（= CC {@code getOriginalCwd()}，{@code EnterWorktreeTool} 会重锚）——
 *        二者与 slug 不同源，进 worktree / cd 后分裂（CC gh-30217 同型）。
 *        null/空白（无会话 / 介质侧解析不出来）⇒ 上述两分支 fail-closed 不命中。
 */
public record PathValidationEnv(
        String sessionId,
        String agentId,
        String effectiveCwd,
        String originalCwd,
        String sessionProjectRoot,
        String claudeConfigHomeDir,
        String nexusaiConfigHomeDir,
        boolean scratchpadEnabled,
        String claudeTempDir,
        boolean hasAutoMemPathOverride,
        String autoMemBaseDir,
        String bundledSkillsRoot
) {

    private static final Logger log = LoggerFactory.getLogger(PathValidationEnv.class);

    /** 「会话项目根解析失败」告警一次性开关（防权限热路径刷屏；本仓铁律：取不到会话态不得静默）。 */
    private static final AtomicBoolean PROJECT_ROOT_WARNED = new AtomicBoolean(false);

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
        // [批 E2] 稳定项目根走统一入口 CwdResolution.getProjectRoot（对齐 CC getProjectRoot state.ts:498-508），
        //   与 SessionStorage.sessionProjectRoot / sessionProjectDir（介质侧写盘锚）同源 ⇒
        //   sessionMemoryDir()/projectDir() 的 slug 与真实落盘目录一致。解析不出来 ⇒ null（fail-closed）。
        String sessionProjectRoot = resolveSessionProjectRootOrNull(ctx.sessionId());
        return new PathValidationEnv(
            ctx.sessionId() == null ? null : ctx.sessionId(),
            ctx.agentId() == null ? null : ctx.agentId().toString(),
            ctx.effectiveCwd() == null ? null
                : ctx.effectiveCwd().toAbsolutePath().normalize().toString(),
            originalCwd,
            sessionProjectRoot,
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
     * 解析会话稳定项目根（介质同源）· 解析不出来 ⇒ <b>null</b>（fail-closed，⛔ 不回落 user.dir）。
     *
     * <p>本仓对应物 = {@code SessionStorage.sessionProjectRoot} → {@code CwdResolution.getProjectRoot}
     * （唯一写入方 = 项目绑定 / run 入口冻结 / DB 回源；⛔ 无 worktree 写入方 ⇒ 稳定）。
     *
     * <p><b>为何 try/catch</b>：{@code CwdResolution.getProjectRoot} 在「该会话解析不出项目根 /
     * DB 明确答无此会话」时 fail-loud 抛（CwdResolution.java:786-819）。本 env 是<b>权限判定</b>的入参，
     * 让它抛出会直接打断工具调用；而同一态下介质侧（{@code SessionStorage.sessionProjectDir}）同样抛
     * ⇒ 该会话结构上不存在「项目内已落盘文件」可读 ⇒ 返回 null（两分支都不命中）语义正确且安全。
     * ⛔ 绝不回落 {@code user.dir} 冒充项目根（本仓已裁定红线：user.dir 是进程常量，不是会话项目根）。
     * 成功路径不 catch ⇒ 仍与介质侧逐字节同源。
     *
     * @param sessionId 会话 ID（null/空白 ⇒ 无会话命名出口，返回 null）
     * @return 稳定项目根 raw 值；无会话 / 解析失败 ⇒ null
     */
    private static String resolveSessionProjectRootOrNull(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return CwdResolution.getProjectRoot(sessionId);
        } catch (RuntimeException e) {
            if (PROJECT_ROOT_WARNED.compareAndSet(false, true)) {
                log.warn("[PathValidationEnv] 会话项目根解析失败 ⇒ 内部路径白名单按 fail-closed 处理"
                    + "（session-memory / project-dir 两分支本次不命中）: sessionId={} err={}",
                    sessionId, e.toString());
            }
            return null;
        }
    }

    /**
     * 便捷构造（Bash 等无 ToolUseContext 调用方）：sessionId/agentId 为 null →
     * 会话级白名单分支（session-memory/scratchpad/plan/tool-results）不命中（fail-closed）。
     *
     * @param cwd   校验基准 cwd（BashPathValidator 的 cwd 参数）
     * @return 路径校验环境
     */
    public static PathValidationEnv forProcess(Path cwd) {
        // [WF-1D · DEL-06 · 批 P12] originalCwd 走统一入口（对齐 CC getOriginalCwd）。forProcess
        //   结构性无 sessionId 槽（record sessionId=null）⇒ 走【无会话命名出口】getOriginalCwdLayerForNonSession()
        //   （值 = 归一化进程 user.dir，INV-4；⭐ 原写法 getOriginalCwdLayer(null) 值逐字节相同，但它会把
        //   结构性无会话报成「漏传 sessionId」）。经统一入口，满足 INV-6「无 user.dir 直读残留于工作目录域」。
        String originalCwd = CwdResolution.getOriginalCwdLayerForNonSession();
        return new PathValidationEnv(
            null,
            null,
            cwd == null ? null : cwd.toAbsolutePath().normalize().toString(),
            originalCwd,
            // [批 E2] forProcess 结构性无会话槽（record sessionId=null）⇒ 无稳定项目根可解析
            //   ⇒ null（sessionMemoryDir()/projectDir() fail-closed 不命中，兑现本方法 javadoc
            //   已有的「会话级白名单分支不命中」承诺；⛔ 不回落 user.dir 冒充项目根）。
            null,
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
            sessionProjectRoot, claudeConfigHomeDir, nexusaiConfigHomeDir, scratchpadEnabled, claudeTempDir,
            hasAutoMemPathOverride, autoMemBaseDir, bundledSkillsRoot);
    }

    public PathValidationEnv withAutoMem(AutoMemPaths autoMemPaths) {
        if (autoMemPaths == null) {
            return this;
        }
        // [批 4b-1] 显式传会话项目根（原经 AutoMemPaths ThreadLocal 隐式解析，载体已删）：
        //   本 env 的 effectiveCwd 即 CwdResolution.getCwd(sessionId) 的显式快照。
        String base = autoMemPaths.getAutoMemPath(effectiveCwd);
        return new PathValidationEnv(sessionId, agentId, effectiveCwd, originalCwd,
            sessionProjectRoot, claudeConfigHomeDir, nexusaiConfigHomeDir, scratchpadEnabled, claudeTempDir,
            autoMemPaths.hasAutoMemPathOverride(),
            base == null ? null : Path.of(base).normalize().toString(),
            bundledSkillsRoot);
    }

    public PathValidationEnv withBundledSkillsRoot(String root) {
        String normalized = root == null ? null : Path.of(root).normalize().toString();
        return new PathValidationEnv(sessionId, agentId, effectiveCwd, originalCwd,
            sessionProjectRoot, claudeConfigHomeDir, nexusaiConfigHomeDir, scratchpadEnabled, claudeTempDir,
            hasAutoMemPathOverride, autoMemBaseDir, normalized);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 派生内部路径 · 对齐 CC filesystem.ts 各 getXxx 等价
    //   基址 = nexusaiConfigHomeDir（=~/.{appName}，决策 D1 自有根）；claude 根仅作
    //   D3/D4 读取回落兼容（claudeConfigHomeDir 字段保留，isJobDirectoryPath 等仍消费）。
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 会话 session-memory 目录集合（<b>两半</b>）· CC {@code getSessionMemoryDir()}（filesystem.ts:261-263）：
     * <pre>{@code join(getProjectDir(getCwd()), getSessionId(), 'session-memory') + sep}</pre>
     *
     * <p><b>[批 E2 · 假门改真门]</b> 原实现返回 {@code {configHome}/session-memory/} —— 批 O-4 实测
     * 该目录 <b>0 个文件</b>（真实 {@code summary.md} 50 个全在
     * {@code {configHome}/projects/{slug}/{sessionId}/session-memory/} 下，由
     * {@code SessionMemoryService.resolvePath:2272-2283} 写盘）⇒ 该分支对真实文件
     * <b>结构性从不命中</b>，读回只能靠 {@code projectDir()} 的宽口兜住。现改为与介质同形的
     * per-session 路径。
     *
     * <p><b>[批 E2 · ③′] 两半</b>（与 {@link #projectDirs()} **同一 slug 集**，仅多 {@code {sessionId}/session-memory} 后缀）：
     * <ol>
     *   <li><b>cwd 半</b> = {@code …/projects/{sanitizePath(effectiveCwd)}/{sessionId}/session-memory/}
     *       —— 对齐 CC 的 {@code getProjectDir(getCwd())}：Read 通道 {@code effectiveCwd = ctx.effectiveCwd()}，
     *       bash 通道 {@code forProcess} 的 {@code effectiveCwd = resolutionBase}（= 当前 cwd，⭐ bash 也拿得到）。</li>
     *   <li><b>稳定根半</b> = {@code …/projects/{sanitizePath(sessionProjectRoot)}/{sessionId}/session-memory/}
     *       —— <b>介质同源</b>（{@code SessionStorage.sessionProjectDir}），保「媒体写 → 权限读」闭环。</li>
     * </ol>
     * ⛔ 两半的 slug 集与 {@link #projectDirs()} 完全相同 ⇒ 本访问器相对它**不新增任何可读面**
     * （cwd 半与稳定根半都在 project-dir 的对应半之内），差异仅在**哪个分支先命中**（本分支在前）。
     *
     * <p>⛔ 注意 {@code {configHome}/session-memory/} 仍是别的用途（{@code SessionMemoryPrompts:114}
     * 的 {@code session-memory/config/} 模板目录），但 CC 的 {@code checkReadableInternalPath}
     * 同样<b>不</b>放行它（CC 该分支只覆盖 per-session 目录）⇒ 本改动是与 CC 对齐，非功能丢失。
     *
     * @return 带尾分隔符的目录列表（可能 1~2 项，有序去重）；<b>空列表</b> = 无会话身份 ⇒ 该分支
     *         fail-closed 不命中（⛔ 不回落整根；兑现 `forProcess` javadoc 的既有承诺）
     */
    public List<String> sessionMemoryDirs() {
        if (sessionId == null || sessionId.isBlank()) {
            // 无会话 ⇒ CC 的 join(projectDir, undefined, 'session-memory') 无意义 ⇒ fail-closed
            return List.of();
        }
        List<String> dirs = new ArrayList<>(2);
        for (String slug : projectSlugs()) {
            dirs.add(Path.of(nexusaiConfigHomeDir, "projects", slug, sessionId, "session-memory")
                .normalize().toString() + java.io.File.separator);
        }
        return List.copyOf(dirs);
    }

    /**
     * 当前项目 slug 目录集合（<b>两半</b>）· CC {@code getProjectDir(getCwd())}（sessionStorage.ts getProjectDir）
     * + {@code isProjectDirPath}（filesystem.ts:284-291）。
     *
     * <p><b>[批 E2 · 大门收窄]</b> 原实现返回<b>整个</b> {@code {configHome}/projects/} 根 ⇒ 模型可
     * 静默读<b>任意项目、任意会话</b>的 session-memory 与历史 transcript（实测该根下 <b>662</b> 个
     * slug 目录）。CC 只放行<b>当前项目</b> {@code getProjectDir(getCwd())} ⇒ 收窄到下面两半。
     *
     * <p><b>[批 E2 · ③′] 为什么是两半（用户裁定 ③′）</b>：
     * <ol>
     *   <li><b>cwd 半</b> = {@code …/projects/{sanitizePath(effectiveCwd)}/}
     *       —— <b>对齐 CC</b>：CC 该分支恒用 {@code getCwd()}（filesystem.ts:284-291），且 CC 的
     *       **bash 路径校验确实会走到它**（`pathValidation.ts:232` 步骤 3.5 调
     *       {@code checkReadableInternalPath}）⇒ **CC 的 bash 放行「当前 cwd 那个 slug」**。
     *       本仓 bash 由 {@code forProcess(resolutionBase)} 构造，{@code resolutionBase} 就是
     *       「当前 cwd」（随 {@code cd} 变，⛔ 不需要 sessionId）⇒ 该半在 bash 通道**天然可得**。
     *       Read 通道 {@code effectiveCwd = ctx.effectiveCwd()}（= {@code CwdResolution.getCwd}）⇒ 同源。</li>
     *   <li><b>稳定根半</b> = {@code …/projects/{sanitizePath(sessionProjectRoot)}/}
     *       —— <b>介质同源</b>（{@code SessionStorage.sessionProjectDir = getProjectDir(CwdResolution.getProjectRoot)}，
     *       SessionStorage.java:187-189）⇒ 保「媒体写 → 权限读」闭环（session-memory / transcript /
     *       subagents 全在此 slug 下）。bash 无 sessionId ⇒ 拿不到 ⇒ 只有 cwd 半生效（可接受）。</li>
     * </ol>
     * ⚠️ 与 CC 的**已知差异**：CC 的 tool-results 落点用 {@code getProjectDir(getOriginalCwd())}
     * （toolResultStorage.ts:97-98），而本仓 {@code toolResultsDir()} 用 {@code effectiveCwd}（= getCwd）
     * ⇒ 本仓 tool-results 根恒 ⊂ 本方法的 cwd 半 ⇒ {@code checkReadableInternalPath} 的
     * **tool-results 分支在本仓结构性不可达**（CC 仅在 cd 后可达）。已登记，⛔ 本批不改分支顺序。
     *
     * <h2>⚠️⚠️ 已知行为（必须登记，⛔ 不得写成「无风险」）：{@code cd} 会移动白名单根 · **双通道**</h2>
     * <p>cwd 半锚在「当前 cwd」上 ⇒ <b>{@code cd} 会把它挪走</b>，且挪动<b>不止 bash 一条通道</b>：
     * <ol>
     *   <li><b>bash 通道</b>：{@code BashPathValidator} 的 {@code resolutionBase} 就是本次命令的解析基准
     *       ⇒ 命令内 {@code cd} 后校验基准随之改变。</li>
     *   <li><b>Read 通道（下一 turn 起）</b>：{@code BashTool} 在 {@code cd} 后会写
     *       {@code SessionCwdHolder.set(sessionId, newCwd)}（{@code BashTool.java:1446}）
     *       ⇒ {@code CwdResolution.getCwd(sessionId)} 改变 ⇒ 下一 turn 的
     *       {@code ToolUseContext.effectiveCwd()}（= 本字段 {@link #effectiveCwd}）一起改变。</li>
     * </ol>
     * <p><b>这是 CC 共有行为，不是本仓新引入</b>：CC 的 step 3.5 锚就是 {@code getProjectDir(getCwd())}
     * （{@code filesystem.ts:284-291}），而 CC 的 {@code STATE.cwd} 同样被 bash {@code cd} 更新。
     * <b>用户已知情接受（批 E2 · 方案 ①，2026-09-16 裁定）</b>。
     *
     * <p><b>⭐ 实测暴露面量化（⛔ 不许弱化）：</b>要利用它，模型须 {@code cd} 到「
     * <b>{@code sanitizePath(cwd)} 恰好等于目标项目 slug</b>」的目录。例：
     * {@code cd ~/.nexusai} ⇒ {@code sanitizePath} = {@code C--Users-WIN--nexusai}
     * ⇒ 随后可读<b>该项目全部会话</b>的 transcript / session-memory。
     * ⇒ <b>可行，但需刻意构造</b>（不是「随手 cd 就跨项目」）；且这只是回到 CC 的放行面，
     * <b>⛔ 不是</b> E2 之前那种「662 个 slug 全放行」的宽口。
     *
     * @return 带尾分隔符的目录列表（可能 1~2 项，有序去重）；<b>空列表</b> = 两半都拿不到 ⇒
     *         fail-closed 不命中（⛔ 不回落整个 {@code projects/} 根）
     */
    public List<String> projectDirs() {
        List<String> dirs = new ArrayList<>(2);
        for (String slug : projectSlugs()) {
            dirs.add(Path.of(nexusaiConfigHomeDir, "projects", slug).normalize().toString()
                + java.io.File.separator);
        }
        return List.copyOf(dirs);
    }

    /**
     * 白名单 slug 集（有序去重）· = cwd 半 + 稳定根半（两半各自独立承重，见 {@link #projectDirs()}）。
     *
     * @return slug 列表（0~2 项）
     */
    private List<String> projectSlugs() {
        List<String> slugs = new ArrayList<>(2);
        String cwdSlug = slugOrNull(effectiveCwd);
        if (cwdSlug != null) {
            slugs.add(cwdSlug);
        }
        String rootSlug = slugOrNull(sessionProjectRoot);
        if (rootSlug != null && !slugs.contains(rootSlug)) {
            slugs.add(rootSlug);
        }
        return slugs;
    }

    /** 项目 slug 规范化 · 与 {@code SessionStorage.getProjectDir}（SessionStorage.java:130-132）**逐字同源**。 */
    private static String slugOrNull(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return null;
        }
        return AutoMemPaths.sanitizePath(Path.of(projectRoot).toString());
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
