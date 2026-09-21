package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 写权限检查器 · 对齐 CC {@code utils/permissions/filesystem.ts:1205-1412 checkWritePermissionForTool}。
 *
 * <h2>L1 语义：CC 决策链（filesystem.ts:1205-1412）· 逐步骤实现状态标注</h2>
 * <ol>
 *   <li>路径提取（CC {@code tool.getPath(input)}）——✓ 已实现（[G3] 各工具 {@code getPath}
 *       接口扩展点，ReadPermissionChecker 同源迁移；无路径概念工具 default null → ask）</li>
 *   <li>edit deny rule → deny（CC :1219-1239）——✓ 已实现
 *       （{@link RuleQuery#getEditRuleByContentsForPath}，edit 桶 content 匹配）</li>
 *   <li><b>1.5 内部可编辑路径白名单 checkEditableInternalPath（CC :1241-1250）——工具层已实现
 *       memory 分支，本 checker 内仅 passthrough</b>：CC checkEditableInternalPath
 *       （filesystem.ts:1479-1605）含 plan 文件 / scratchpad / job 目录 / agent memory /
 *       auto-mem / launch.json 六分支。Java 端 agent-memory / auto-memory 分支已在工具层实现
 *       （EditFileTool/WriteFileTool.checkPermissions step 1.5 carve-out，先于本 checker 执行，
 *       顺序对齐 CC deny 步骤1 先于 carve-out 步骤1.5）；plan 文件已接线（PlanProviderImpl 读磁盘 plan）
 *       但 1.5 白名单门控 passthrough 保留（plan 模式写盘走 ask 而非 CC auto-allow，UX 偏差已登记
 *       OD-20 子项4）；scratchpad / job / launch.json 分支 Java 无对应概念 → passthrough 继续。</li>
 *   <li><b>1.6 .claude/** session allow（CC :1252-1300）——✓ 已实现</b>
 *       （{@link #checkClaudeFolderSessionAllow}：session-only 桶 + 范围校验）。</li>
 *   <li><b>1.7 checkPathSafetyForAutoEdit（CC :1302-1338）——✓ 已实现</b>
 *       （suspicious windows / claude config / dangerous files，见 {@link #checkPathSafetyForAutoEdit}）。</li>
 *   <li>edit ask rule → ask（CC :1340-1358）——✓ 已实现</li>
 *   <li>acceptEdits 模式 + 工作目录内 → allow（CC :1360-1375）——✓ 已实现</li>
 *   <li>edit allow rule → allow（CC :1377-1393）——✓ 已实现</li>
 *   <li>兜底 → ask（CC :1395-1411，工作目录外带 workingDir reason）——✓ 已实现</li>
 * </ol>
 *
 * <h2>Java 端实现差异（如实标注）</h2>
 * <ul>
 *   <li><b>规则匹配近似</b>：CC matchingRuleForInput 做 root-relative 匹配（patternWithRoot +
 *       ignore 库相对路径），Java 沿用 ReadPermissionChecker 既有 content-rule 近似
 *       （ruleContent glob 直接匹配路径字符串，RuleQuery.matchRuleContent）——同类近似已在
 *       ReadPermissionChecker steps 3/4 披露，保持一致。</li>
 *   <li><b>ruleContent 匹配用原始 input 路径</b>（未 expandPath）——1.6/4 步对齐 CC 用原始
 *       path（filesystem.ts:1262/:1378）；deny/ask/safety 步按 CC 遍历展开路径
 *       （[S08] pathsToCheck）。</li>
 *   <li><b>1.6 范围校验常量</b>：CLAUDE_FOLDER_PERMISSION_PATTERN（'/.claude/**'）与
 *       GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN（'~/.claude/**'）取自 CC
 *       FileEditTool/constants.ts:5/:8（slice(0,-2) = '…/.claude/' 前缀比较）。
 *       <b>[同源修复]</b> 接受集另含 {@link #skillScopeRoots} 产出的 skill scope 前缀
 *       （与 {@link #getClaudeSkillScope} 单点同源）——修复「产出
 *       '~/.{appName}/skills/{name}/**' 被消费侧拒收 ⇒ 会话授权永不生效」的前缀漂移缺陷。</li>
 *   <li><b>建议（suggestions）兜底已实现（GAP-3）</b>：兜底 ask 现附
 *       {@link PermissionUpdates#generateSuggestions} 的 write 分支
 *       （filesystem.ts:1448-1463）——default/plan mode 建议 SetMode(acceptEdits)，
 *       工作目录外再建议 AddDirectories。1.7 安全检查 ask 与兜底 ask <b>同口径</b>：
 *       skill scope 命中 → session-scoped addRules（{@link #getClaudeSkillScope}，
 *       CC :1313-1326）；未命中 → 回落 generateSuggestions
 *       （{@link #safetyAskSuggestions}，CC :1327）。⛔ <b>两条分支均不得传
 *       {@code List.of()}</b>：空建议 ⇒ 前端「一键授权」第三档不渲染
 *       （PermissionBubble.tsx:97 / permissionSuggestionLabels.ts:253）。1.6 消费侧与
 *       skill 建议产出侧单点同源（{@link #skillScopeRoots}）。</li>
 *   <li><b>[S08] symlink 路径展开已实现</b>：{@link PermissionPaths#getPathsForPermissionCheck}
 *       （CC fsOperations.ts:288-382 等价物）在 deny/safety/ask/working-dir 检查前展开
 *       original+symlink 全路径并遍历（CC filesystem.ts:1219-1221 precomputedPathsToCheck
 *       透传；1.7 :626-628 同样遍历）——悬空/越界 symlink 目标入检，fail-closed。</li>
 * </ul>
 *
 * <p><b>消费方</b>：{@link ReadPermissionChecker} step5 edit-implies-read
 * （CC checkReadPermissionForTool filesystem.ts:1124-1134 调 checkWritePermissionForTool，
 * 仅消费 behavior==='allow' 结果）。Edit/Write 工具自身的 checkPermissions 仍走
 * 10 层管线（1a-3，CC hasPermissionsToUseToolInner 等价），本类不重复接入
 * （管线 1c 层接入为独立对齐项，登记 open-decisions）。
 */
@Component
public class WritePermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(WritePermissionChecker.class);

    /**
     * CC DANGEROUS_DIRECTORIES（filesystem.ts:74-79）——auto-edit 禁改目录（大小写不敏感，
     * 防 case 变体绕过；.claude/worktrees 结构性目录除外）。'.claude' 保留 CC mirror（只读兼容）；
     * 项目级 nexusai 目录（.{appName}）为动态（决策 D1/D6）→ isDangerousFilePathToAutoEdit 方法内
     * {@link NexusaiPaths#getProjectDirName()} 判定（静态 Set 无法运行时动态，R12-3）。
     */
    private static final Set<String> DANGEROUS_DIRECTORIES = Set.of(".git", ".vscode", ".idea", ".claude");

    /**
     * CC DANGEROUS_FILES（filesystem.ts:57-68）——auto-edit 禁改文件（大小写不敏感）。
     */
    private static final Set<String> DANGEROUS_FILES = Set.of(
        ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".zshrc",
        ".zprofile", ".profile", ".ripgreprc", ".mcp.json", ".claude.json", ".nexusai.json"
    );

    /**
     * CC CLAUDE_FOLDER_PERMISSION_PATTERN（FileEditTool/constants.ts:5）——
     * 项目 .claude/ 会话授权前缀。
     */
    private static final String CLAUDE_FOLDER_PERMISSION_PATTERN = "/.claude/**";

    /**
     * CC GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN（FileEditTool/constants.ts:8）——
     * 全局 ~/.claude/ 会话授权前缀。
     */
    private static final String GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN = "~/.claude/**";

    /**
     * {@link #CLAUDE_FOLDER_PERMISSION_PATTERN} 的 {@code slice(0, -2)} 展开（CC 原实现即
     * {@code pattern.slice(0, -2)}，FileEditTool/constants.ts:5）—— 文件夹级前缀
     * {@code '/.claude/'}。仅作可读性分解，语义与
     * {@code CLAUDE_FOLDER_PERMISSION_PATTERN.substring(0, len - 2)} 逐字节相同。
     */
    private static final String CLAUDE_FOLDER_ROOT_PREFIX =
        CLAUDE_FOLDER_PERMISSION_PATTERN.substring(
            0, CLAUDE_FOLDER_PERMISSION_PATTERN.length() - 2);

    /**
     * {@link #GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN} 的 {@code slice(0, -2)} 展开
     * （CC FileEditTool/constants.ts:8）—— 文件夹级前缀 {@code '~/.claude/'}。
     */
    private static final String GLOBAL_CLAUDE_FOLDER_ROOT_PREFIX =
        GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN.substring(
            0, GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN.length() - 2);

    /**
     * [T2 · 写侧 auto-mem 基址] auto-memory 路径解析（<b>写</b> carve-out · 对齐 CC
     * {@code checkEditableInternalPath} 的 memdir 写分支（仓内既有注释口径 filesystem.ts:1572-1581，
     * 见 {@code PathValidation} 同分支注释），reason 文案 {@code "auto memory files are allowed for writing"}）。
     *
     * <p><b>WHY 必须有本字段</b>：{@link PathValidationEnv#fromToolUseContext} 构造时把
     * {@code hasAutoMemPathOverride=false} / {@code autoMemBaseDir=null} 硬编码（工厂是 read/write
     * 共用的纯静态派生，拿不到注入 bean），唯一填充口是 {@link PathValidationEnv#withAutoMem}。
     * 读侧 {@code ReadPermissionChecker} 早已在同一处调用点 wither 填充（ReadPermissionChecker.java:284-285），
     * 写侧此前<b>没有</b>任何基址来源 ⇒ {@code PathValidation.isAutoMemPath} 在 {@code base==null}
     * 时恒返回 false ⇒ CC 那条写 carve-out <b>结构性不可达</b>，写
     * {@code <memoryBase>/projects/<slug>/memory/*.md} 永远落到 1.7 safety Ask（每次写记忆都弹窗 ⇒
     * 记忆提取链被用户交互打断）。
     *
     * <p>本字段 = <b>与读侧同一个 Spring bean</b>（{@code ToolRegistrationConfig#autoMemPaths}，
     * 全仓单例）⇒ ⛔ 不新起第二套路径算法。{@code @Autowired(required=false)}：无 bean（POJO /
     * 未装配）时 {@code withAutoMem(null)} 直接 return this，行为与接线前<b>逐字节一致</b>
     * （fail-closed，不伪造基址）。
     */
    @Autowired(required = false)
    private AutoMemPaths autoMemPaths;

    /** 显式注入槽（镜像 {@code ReadPermissionChecker.setAutoMemPaths}）· 供非 Spring 测试装配。 */
    public void setAutoMemPaths(AutoMemPaths autoMemPaths) {
        this.autoMemPaths = autoMemPaths;
    }

    /**
     * 对齐 CC {@code checkWritePermissionForTool(tool, input, toolPermissionContext)}
     * （filesystem.ts:1205-1412）。入口形态：本方法委托 4 参重载（precomputed=null）。
     *
     * @param tool  工具实例（当前仅保留签名对称，路径从 input 提取）
     * @param input LLM 给的参数（JSON）
     * @param ctx   工具调用上下文（含 permissionContext）
     * @return      {@link PermissionResult}
     */
    public PermissionResult check(Tool tool, JsonNode input, ToolUseContext ctx) {
        return check(tool, input, ctx, null);
    }

    /**
     * 对齐 CC {@code checkWritePermissionForTool(..., precomputedPathsToCheck)}
     * （filesystem.ts:1205-1412；可选缓存参数 :1209/:1220-1221）。
     *
     * <p>[S08] read 路径 step5（edit-implies-read，checkReadPermissionForTool :1130）把
     * {@code getPathsForPermissionCheck} 结果单次计算透传本方法，避免重复
     * existsSync/lstatSync/realpathSync 系统调用（CC filesystem.ts:1044-1047 注释）。
     *
     * <p>[Session M.4.4 收尾] ctx / permCtx 为 null → fail-loud
     * {@link IllegalArgumentException}（对齐 {@code ReadPermissionChecker} 同款守卫）。
     *
     * @param tool                    工具实例（当前仅保留签名对称，路径从 input 提取）
     * @param input                   LLM 给的参数（JSON）
     * @param ctx                     工具调用上下文（含 permissionContext）
     * @param precomputedPathsToCheck 调用方已计算的展开路径（须与同一 tool+input 同步帧派生，
     *                                CC :1199-1203 注释；null = 本方法自行计算）
     * @return                        {@link PermissionResult}
     */
    PermissionResult check(Tool tool, JsonNode input, ToolUseContext ctx,
            List<String> precomputedPathsToCheck) {
        if (ctx == null) {
            throw new IllegalArgumentException("WritePermissionChecker ctx is null");
        }
        if (ctx.permissionContext() == null) {
            throw new IllegalArgumentException("WritePermissionChecker permissionContext is null");
        }
        ToolPermissionContext permCtx = ctx.permissionContext();
        // [G3] 路径提取迁出 extractPath → tool.getPath(input)（CC filesystem.ts:1211-1217
        //   typeof tool.getPath !== 'function' → ask; 否则 tool.getPath(input)）。各工具按 CC
        //   语义实现 getPath；无路径概念工具 default null → ask（CC 等价）。
        String path = tool.getPath(input);
        if (path == null || path.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[WritePermissionChecker] 缺少 path（tool.getPath=null/空）→ ask: tool={}",
                    tool == null ? "null" : tool.name());
            }
            return new PermissionResult.Ask(
                "write 权限检查缺少 path",
                new PermissionDecisionReason.Other("missing path"),
                List.of(), null, input, null, false, null, List.of());
        }

        // expandPath（[FIX-A-R2] ~ 与相对路径 → 绝对，对齐 CC filesystem.ts:1243
        // `absolutePathForEdit = expandPath(path)`；Java 端 1.5 checkEditableInternalPath
        // 为 N/A passthrough，故本值当前无消费方，保留作未来 1.5 对齐锚点）。
        String expanded = ReadPermissionChecker.expandPath(path, ctx.effectiveCwd());

        // 权限检查路径展开（original+symlink 全路径，CC fsOperations.ts:288-382）。
        // 单次计算供 deny/safety/ask/working-dir 复用（CC :1220-1221
        // precomputedPathsToCheck ?? getPathsForPermissionCheck；:1044-1047 注释说明
        // 避免每步重复系统调用）。
        // 注（[FIX-A-R2]）：CC 对 pathsToCheck 用原始 path（filesystem.ts:1221），
        // 相对→绝对由 backfill 在 gate 之前完成（toolExecution.ts:781-793）；Java 对称
        // 保留原始 path，相对路径防绕过由 StreamingToolExecutor 把 backfilledInput 透传给
        // permission 门兜底。
        List<String> pathsToCheck = precomputedPathsToCheck != null
            ? precomputedPathsToCheck
            : PermissionPaths.getPathsForPermissionCheck(path);

        // ── 1. edit deny rule → deny（遍历全部展开路径，CC :1219-1239；read 路径 step5 会忽略本结果） ──
        // symlink 目标路径同样参与匹配——deny 规则可经解析后落点命中（CC :1222-1238）。
        // [WF2-04] 抽为公共 checkDeny（供工具层 deny-first 复用）；此处透传 precomputed pathsToCheck。
        PermissionResult deny = checkDeny(tool, input, ctx, pathsToCheck);
        if (deny != null) {
            return deny;
        }

        // ── 1.5 内部可编辑路径白名单（CC :1241-1250 checkEditableInternalPath）──
        // OPD-WF5-02-02：委派核心 PathValidation.checkEditableInternalPath（scratchpad / job /
        // launch.json；plan 按 OD-20 passthrough 写盘仍走 ask；agent-memory 分支在工具层
        // EditFileTool/WriteFileTool 已实现，先于本 checker 执行，核心不重复）。
        // [T2 · 写侧 auto-mem 基址] auto-memory 分支必须由核心层接管：CC filesystem.ts:1572-1581
        //   的写 carve-out（`!hasAutoMemPathOverride && isAutoMemPath(p)` →
        //   "auto memory files are allowed for writing"）判定在核心层，其基址只能经本 wither 填入
        //   （工厂硬编码 null ⇒ 不填则 isAutoMemPath 恒 false、该分支结构性不可达、写记忆恒落 1.7
        //   safety Ask）。逐字镜像读侧 ReadPermissionChecker.java:284-285 的同一接线，⛔ 不另起路径算法；
        //   autoMemPaths==null（未装配）时 withAutoMem 直接 return this ⇒ 与接线前行为一致（fail-closed）。
        PathValidationEnv editEnv = PathValidationEnv.fromToolUseContext(ctx)
            .withAutoMem(autoMemPaths);
        PathValidation.InternalPathResult internalEdit = PathValidation.checkEditableInternalPath(expanded, editEnv);
        if (internalEdit.allowed()) {
            if (log.isDebugEnabled()) {
                log.debug("[WritePermissionChecker] 1.5 内部可编辑路径白名单命中 → allow: path={} reason={}",
                    path, internalEdit.decisionReason());
            }
            return new PermissionResult.Allow(
                input, internalEdit.decisionReason(), null, false, null, List.of());
        }

        // ── 1.6 .claude/** session allow（CC :1252-1300，安全检查前放行；原始 path 匹配，CC :1262；root-relative 传 cwd） ──
        // cwdStr 供 1.6/2/4 步 root-relative 规则匹配锚定根（CC getOriginalCwd 等价）
        String cwdStr = cwdOf(ctx);
        PermissionResult claudeFolderAllow = checkClaudeFolderSessionAllow(permCtx, path, input, cwdStr);
        if (claudeFolderAllow != null) {
            return claudeFolderAllow;
        }

        // ── 1.7 checkPathSafetyForAutoEdit（CC :1302-1338，安全检查在 allow 规则之前；遍历全部展开路径） ──
        // expanded 透传：1.7 的 generateSuggestions 回落需要已展开绝对路径
        // （CC 传原始 path，但其 getDirectoryForPath 内部自行 expandPath；本仓
        //  PermissionUpdates.getDirectoryForPath 反过来要求入参已展开，见其 javadoc）。
        PermissionResult safety = checkPathSafetyForAutoEdit(pathsToCheck, path, expanded, ctx);
        if (safety != null) {
            return safety;
        }

        // ── 2. edit ask rule → ask（遍历全部展开路径，CC :1340-1358；root-relative 传 cwd） ──
        for (String pathToCheck : pathsToCheck) {
            PermissionRule askRule = RuleQuery.getEditRuleByContentsForPath(
                permCtx, pathToCheck, PermissionBehavior.ASK, cwdStr);
            if (askRule != null) {
                if (log.isInfoEnabled()) {
                    log.info("[WritePermissionChecker] edit ask rule 命中 → ask: rule={} path={} pathToCheck={}",
                        RuleQuery.ruleToString(askRule), path, pathToCheck);
                }
                return new PermissionResult.Ask(
                    "Claude 请求编辑 " + path + "，权限规则要求确认",
                    new PermissionDecisionReason.Rule(askRule),
                    List.of(), path, input, null, false, null, List.of());
            }
        }

        // ── 3. acceptEdits 模式 + 工作目录内 → allow（CC :1360-1375；全部展开路径必须在工作目录内） ──
        boolean inWorkingDir = ReadPermissionChecker.isInWorkingDir(pathsToCheck, ctx);
        if (permCtx.mode() == PermissionMode.ACCEPT_EDITS && inWorkingDir) {
            if (log.isDebugEnabled()) {
                log.debug("[WritePermissionChecker] acceptEdits 模式 + 工作目录内 → allow: path={}", path);
            }
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Mode(PermissionMode.ACCEPT_EDITS),
                null, false, null, List.of());
        }

        // ── 4. edit allow rule → allow（CC :1377-1393；原始 path 匹配，CC :1378；root-relative 传 cwd） ──
        PermissionRule allowRule = RuleQuery.getEditRuleByContentsForPath(
            permCtx, path, PermissionBehavior.ALLOW, cwdStr);
        if (allowRule != null) {
            if (log.isInfoEnabled()) {
                log.info("[WritePermissionChecker] edit allow rule 命中 → allow: rule={} path={}",
                    RuleQuery.ruleToString(allowRule), path);
            }
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Rule(allowRule),
                null, false, null, List.of());
        }

        // ── 5. 兜底 → ask（CC :1395-1411；工作目录外带 workingDir reason） ──
        // [GAP-3] 对齐 CC generateSuggestions write 分支（filesystem.ts:1448-1463）：
        //   shouldSuggestAcceptEdits（default/plan mode）→ SetMode(acceptEdits)；
        //   工作目录外 → AddDirectories(getPathsForPermissionCheck(dirPath))。
        //   旧实现空 suggestions → 用户"始终允许"时无建议；补齐 write/create 建议。
        List<PermissionUpdate> writeSuggestions = PermissionUpdates.generateSuggestions(
            expanded, PermissionUpdates.OperationType.WRITE, permCtx.mode(), !inWorkingDir);
        if (log.isDebugEnabled()) {
            log.debug("[WritePermissionChecker] 兜底 → ask: path={} inWorkingDir={} suggestions={}",
                path, inWorkingDir, writeSuggestions);
        }
        return new PermissionResult.Ask(
            "Claude 请求编辑 " + path + "，需要用户授权",
            inWorkingDir
                ? new PermissionDecisionReason.Other("default ask for write inside working dir")
                : new PermissionDecisionReason.WorkingDir("Path is outside allowed working directories"),
            writeSuggestions, path, input, null, false, null, List.of());
    }

    /**
     * 步骤 1 deny 规则检查（CC filesystem.ts:1219-1239）· 供工具层 deny-first 重排复用。
     *
     * <p>EditFileTool/WriteFileTool.checkPermissions 在 carve-out 之前调用本方法
     * （对齐 CC deny 步骤1 先于 carve-out 步骤1.5）；本方法自算 {@code pathsToCheck}
     * （工具层无 precomputed 展开路径）。
     *
     * <p><b>null 安全</b>：ctx / permissionContext 为 null → 返回 null（工具层 carve-out
     * 测试用 3 参 {@code ToolUseContext.of(...)}，permissionContext=null；此时无 deny
     * 规则可查，跳过 deny 检查继续 carve-out）。
     *
     * @param tool  工具实例（路径从 tool.getPath(input) 提取）
     * @param input LLM 给的参数（JSON）
     * @param ctx   工具调用上下文（含 permissionContext，可为 null）
     * @return      {@link PermissionResult.Deny} 或 null（未命中 deny / 无 permissionContext）
     */
    public PermissionResult checkDeny(Tool tool, JsonNode input, ToolUseContext ctx) {
        return checkDeny(tool, input, ctx, null);
    }

    /**
     * 步骤 1 deny 规则检查（带 precomputedPathsToCheck）· 包内共享。
     *
     * <p>{@code check(tool, input, ctx, precomputedPathsToCheck)} 主体透传已展开的
     * {@code pathsToCheck}（read 路径 step5 edit-implies-read 单次计算，避免重复系统调用，
     * CC filesystem.ts:1044-1047）；工具层传 null 自算。
     *
     * @param tool         工具实例（路径从 tool.getPath(input) 提取）
     * @param input        LLM 给的参数（JSON）
     * @param ctx          工具调用上下文（含 permissionContext，可为 null）
     * @param pathsToCheck 调用方已计算的展开路径（null = 自算）
     * @return             {@link PermissionResult.Deny} 或 null
     */
    PermissionResult checkDeny(Tool tool, JsonNode input, ToolUseContext ctx,
            List<String> pathsToCheck) {
        if (ctx == null || ctx.permissionContext() == null) {
            return null;
        }
        ToolPermissionContext permCtx = ctx.permissionContext();
        String path = tool == null ? null : tool.getPath(input);
        if (path == null || path.isBlank()) {
            return null;
        }
        List<String> effective = pathsToCheck != null
            ? pathsToCheck
            : PermissionPaths.getPathsForPermissionCheck(path);
        String cwdStr = cwdOf(ctx);
        for (String pathToCheck : effective) {
            PermissionRule denyRule = RuleQuery.getEditRuleByContentsForPath(
                permCtx, pathToCheck, PermissionBehavior.DENY, cwdStr);
            if (denyRule != null) {
                if (log.isInfoEnabled()) {
                    log.info("[WritePermissionChecker] edit deny rule 命中 → deny: rule={} path={} pathToCheck={}",
                        RuleQuery.ruleToString(denyRule), path, pathToCheck);
                }
                return new PermissionResult.Deny(
                    "编辑 " + path + " 被权限规则拒绝",
                    new PermissionDecisionReason.Rule(denyRule),
                    null);
            }
        }
        return null;
    }

    /**
     * 1.6 .claude/** session allow（CC filesystem.ts:1252-1300）。
     *
     * <p>语义：仅查 <b>session</b> 桶的 edit allow 规则（CC 用
     * {@code alwaysAllowRules: {session: ...}} 构造 session-only 上下文，:1262-1272），
     * 命中后做范围校验（ruleContent 须以 CC 文件夹级前缀 '/.claude/' 或 '~/.claude/' 开头、
     * <b>或</b>以与 {@link #getClaudeSkillScope} 同源的 skill scope 前缀
     * （{@link #skillScopeRoots}，含 nexusai 自有根 '~/.{appName}/skills/'）开头，
     * 不含 '..'、以 '/**' 结尾，CC :1281-1290）——防止会话级授权借 '/.claude/../**' 逃逸到
     * .claude/ 之外，也防止非 session 源规则绕过安全检查。
     *
     * <p><b>同源约束（本方法的存在理由之一）</b>：接受的前缀集合必须与
     * {@link #getClaudeSkillScope} 产出的前缀集合同源。历史缺陷：产出侧第三条 pattern
     * {@code '~/.{appName}/skills/<name>/**'} 因消费侧硬编码 {'/.claude/','~/.claude/'}
     * 结构上必被拒 → 落 1.7 safety Ask（自有根段判危险目录）→ 步骤 4（edit allow rule）不可达
     * ⇒ 用户确认过的会话授权永不生效，每次同类调用重新弹窗。
     *
     * @param cwd  校验基准 cwd（root-relative 匹配根锚定，CC getOriginalCwd 等价；
     *             同时供 {@link #skillScopeRoots} 的 project 段配对使用，其 prefix 与 cwd 无关）
     * @return Allow 或 null（未命中/范围校验失败 → 继续 1.7 安全检查）
     */
    private PermissionResult checkClaudeFolderSessionAllow(
            ToolPermissionContext permCtx, String path, JsonNode input, String cwd) {
        Set<PermissionRule> sessionRules = permCtx.alwaysAllowRules().get(PermissionRuleSource.SESSION);
        if (sessionRules == null || sessionRules.isEmpty()) {
            return null;
        }
        // session-only 上下文（对齐 CC :1262-1272 只保留 session 桶）
        ToolPermissionContext sessionOnly = new ToolPermissionContext(
            permCtx.mode(),
            Map.of(PermissionRuleSource.SESSION, sessionRules),
            Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
        PermissionRule rule = RuleQuery.getEditRuleByContentsForPath(
            sessionOnly, path, PermissionBehavior.ALLOW, cwd);
        if (rule == null) {
            return null;
        }
        String content = rule.ruleValue().ruleContent();
        // 接受集 = CC 两个「文件夹级」前缀（filesystem.ts:1281-1290）
        //   ∪ 与产出侧 getClaudeSkillScope 同源的 skill scope 前缀（修前缀漂移）。
        // ⛔ 刻意不对 CC 那两个前缀做「skills 级收窄」：CC 接受 '/.claude/' 本身，收窄成
        //   '/.claude/skills/' 会把 '/.claude/agents/**' 这类会话授权从 1.6 放行降级为 1.7 ask，
        //   属偏离 CC 的行为回退（规则七：显式择优，不折中调和）。
        boolean scopeOk = content != null
            && (content.startsWith(CLAUDE_FOLDER_ROOT_PREFIX)
                || content.startsWith(GLOBAL_CLAUDE_FOLDER_ROOT_PREFIX)
                || startsWithSkillScopePrefix(content, cwd))
            // ⛔ 护栏①：'..' 拒绝（防 '/.claude/../**' 逃逸到文件夹之外）——不得放宽
            && !content.contains("..")
            // ⛔ 护栏②：'/ **' 结尾（须是目录级授权，非裸前缀/具体文件）——不得放宽
            && content.endsWith("/**");
        if (!scopeOk) {
            if (log.isDebugEnabled()) {
                log.debug("[WritePermissionChecker] session allow 范围校验失败（'..' 或前缀不在接受集或非 /** 结尾）→ 继续安全检查: content={} path={}",
                    content, path);
            }
            return null;
        }
        if (log.isInfoEnabled()) {
            log.info("[WritePermissionChecker] .claude/** session allow 命中 → allow: rule={} path={}",
                RuleQuery.ruleToString(rule), path);
        }
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Rule(rule),
            null, false, null, List.of());
    }

    /**
     * 1.7 checkPathSafetyForAutoEdit（CC filesystem.ts:620-665 + :1302-1338）。
     *
     * <p>三道检查（顺序对齐 CC :630-661），每道遍历<b>全部展开路径</b>（original+symlink，
     * CC :626-628 precomputedPathsToCheck ?? getPathsForPermissionCheck；symlink 目标同样
     * 参与——防 symlink 指向敏感文件的写入逃逸安全检查）：
     * <ol>
     *   <li>可疑 Windows 路径模式 → ask（classifierApprovable=false）</li>
     *   <li>Claude 配置文件（.claude/settings.json / settings.local.json /
     *       {cwd}/.claude/{commands,agents,skills} 内）→ ask（classifierApprovable=true）</li>
     *   <li>危险文件/目录（.git/.vscode/.idea/.claude + shell/安全敏感文件）→ ask
     *       （classifierApprovable=true）</li>
     * </ol>
     *
     * @param pathsToCheck 展开路径集合（original + symlink 全路径）
     * @param rawPath      原始 input 路径（skill scope 判定 + 消息展示；getClaudeSkillScope 内部自行 expandPath）
     * @param expanded     已展开绝对路径（回落的 generateSuggestions 用；⛔ 不可传 rawPath，
     *                     见 {@link #safetyAskSuggestions} 的「第一实参」说明）
     * @param ctx          工具调用上下文（{cwd}/.claude/{commands,agents,skills} 判定 + 建议生成用）
     * @return Ask 或 null（全部通过）
     */
    private PermissionResult checkPathSafetyForAutoEdit(
            List<String> pathsToCheck, String rawPath, String expanded, ToolUseContext ctx) {
        // 1.7 建议（CC :1312-1327）：path 在 .claude/skills/{name}/ 内 → getClaudeSkillScope
        //    session-scoped addRules 建议（OPD-WF5-FS-018）；否则回落 generateSuggestions
        //    （filesystem.ts:1327）。⛔ 不得退回 List.of()：空建议 ⇒ 前端「一键授权」第三档
        //    不渲染（PermissionBubble.tsx:97），用户点什么都生不出规则。
        List<PermissionUpdate> safetySuggestions =
            safetyAskSuggestions(rawPath, expanded, pathsToCheck, ctx);
        // 1. 可疑 Windows 路径模式（CC :630-639，classifierApprovable=false）
        // OPD-WF5-02-01：委派 PathValidation.hasSuspiciousWindowsPathPattern（7 类全查）。
        for (String pathToCheck : pathsToCheck) {
            if (PathValidation.hasSuspiciousWindowsPathPattern(pathToCheck)) {
                if (log.isInfoEnabled()) {
                    log.info("[WritePermissionChecker] 可疑 Windows 路径 → ask: path={} pathToCheck={}",
                        rawPath, pathToCheck);
                }
                return new PermissionResult.Ask(
                    "Claude 请求写入含可疑 Windows 路径模式的文件 " + rawPath + "，需用户确认",
                    new PermissionDecisionReason.SafetyCheck(
                        "Path contains suspicious Windows-specific patterns", false),
                    safetySuggestions, rawPath, null, null, false, null, List.of());
            }
        }
        // 2. Claude 配置文件（CC :641-650，classifierApprovable=true）
        for (String pathToCheck : pathsToCheck) {
            if (isClaudeConfigFilePath(pathToCheck, ctx)) {
                if (log.isInfoEnabled()) {
                    log.info("[WritePermissionChecker] Claude 配置文件 → ask: path={} pathToCheck={}",
                        rawPath, pathToCheck);
                }
                return new PermissionResult.Ask(
                    "Claude 请求写入 " + rawPath + "，但尚未授权",
                    new PermissionDecisionReason.SafetyCheck("Claude config file path", true),
                    safetySuggestions, rawPath, null, null, false, null, List.of());
            }
        }
        // 3. 危险文件/目录（CC :652-661，classifierApprovable=true）
        for (String pathToCheck : pathsToCheck) {
            if (isDangerousFilePathToAutoEdit(pathToCheck)) {
                if (log.isInfoEnabled()) {
                    log.info("[WritePermissionChecker] 危险文件/目录 → ask: path={} pathToCheck={}",
                        rawPath, pathToCheck);
                }
                return new PermissionResult.Ask(
                    "Claude 请求编辑 " + rawPath + "，属敏感文件",
                    new PermissionDecisionReason.SafetyCheck("Path is a sensitive file", true),
                    safetySuggestions, rawPath, null, null, false, null, List.of());
            }
        }
        return null;
    }

    /** 校验基准 cwd（CC getOriginalCwd 等价；null → 统一入口 CwdResolution.getCwd）。
     *  [WF-1D · DEL-06] 原 user.dir 直读兜底改走统一入口，绑定项目场景 baseDir 取对
     *  （对齐 CC resolve(cwd, path) cwd=getCwd()）。实践中 ctx.effectiveCwd 经 WF-1A
     *  ToolUseContext DEL-02 已非 null，此分支为防御兜底 + INV-6 清理。 */
    private static String cwdOf(ToolUseContext ctx) {
        return ctx != null && ctx.effectiveCwd() != null
            ? ctx.effectiveCwd().toString()
            : CwdResolution.getCwd(
                ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null);
    }

    /** CC getClaudeSkillScope 返回值（filesystem.ts:101-157）。 */
    private record SkillScope(String skillName, String pattern) {}

    /**
     * skill scope 根配对 · <b>单点同源</b>：一组 {@code (base, prefix)} ——
     * {@code base} 用于匹配展开后的绝对路径（{@link #getClaudeSkillScope} 消费），
     * {@code prefix} 用于拼出会话授权 ruleContent（{@link #getClaudeSkillScope} 产出，
     * {@link #checkClaudeFolderSessionAllow} 1.6 消费）。
     *
     * <p><b>WHY 必须成对承载</b>：两侧若各自字面维护（历史形态 = 两个平行数组 + 消费侧硬编码
     * {{'/.claude/','~/.claude/'}}），第三组前缀（nexusai 自有根）就会漂移 —— 产出侧写出
     * {@code '~/.{appName}/skills/<name>/**'}，消费侧收不下 ⇒ 用户确认过的会话规则永不生效。
     * ⛔ 不得退回两个平行数组。
     *
     * @param base   绝对路径匹配基（POSIX 归一；大小写不敏感比较由调用方 {@code toLowerCase} 做）
     * @param prefix 会话授权模式前缀（{@code pattern = prefix + skillName + "/**"}）
     */
    private record SkillRoot(String base, String prefix) {}

    /**
     * skill scope 三条根配对 · <b>1.6 消费侧与 getClaudeSkillScope 产出侧的唯一来源</b>。
     *
     * <p>三条根（CC {@code getClaudeSkillScope} filesystem.ts:101-157 的两条 + nexusai 自有根
     * 扩展，决策 D1/D6）：
     * <ol>
     *   <li>project base = {@code {cwd}/.claude/skills}，prefix {@code '/.claude/skills/'}；</li>
     *   <li>global base = {@code {user.home}/.claude/skills}，prefix {@code '~/.claude/skills/'}；</li>
     *   <li>nexusai 自有根 base = {@code {user.home}/.{appName}/skills}，prefix
     *       {@code '~/.{appName}/skills/'}（nexusai 复刻版 .claude 改造）。</li>
     * </ol>
     *
     * <p>⛔ <b>必须是方法不能是 static final 常量</b>：{@code appName} 与 {@code cwd} 运行时可变
     * （{@link NexusaiPaths#setAppNameOverride} / {@code CwdResolution}），静态常量会在
     * {@code spring.application.name} 注入前被固化 —— 时序纪律同
     * {@link NexusaiPaths#getAppTempDirName()}。
     *
     * @param cwd       校验基准 cwd（null/空 → 经 sessionId 显式解析，见 {@link CwdResolution#getCwd}）
     * @param sessionId 会话 ID（cwd 缺省时的显式来源；null = 无会话 → 进程 user.dir）
     * @return 三条配对（顺序稳定：project → global → nexusai 自有根）
     */
    private static List<SkillRoot> skillScopeRoots(String cwd, String sessionId) {
        // [批 3c] cwd 缺省 → 显式 sessionId 解析（原经裸 MDC 的无参重载已删）；sessionId 亦空
        //   → 进程 user.dir（调用方 cwdOf(ctx) 恒非 null，此分支仅显式传空 cwd 的调用方命中）。
        String cwdPosix = toPosix(cwd != null && !cwd.isEmpty()
            ? cwd : CwdResolution.getCwd(sessionId));
        String homePosix = toPosix(System.getProperty("user.home", ""));
        // 决策 D1/D6 全动态：用户级 nexusai 自有根 = NexusaiPaths.getAppConfigHomeDir()（~/.{appName}），
        // 其 skills 目录与 ~/.claude/skills 等价，加入 global base（nexusai 复刻版 .claude 改造）。
        String nexusaiHomePosix = toPosix(NexusaiPaths.getAppConfigHomeDir());
        return List.of(
            new SkillRoot(cwdPosix + "/.claude/skills", "/.claude/skills/"),
            new SkillRoot(homePosix + "/.claude/skills", "~/.claude/skills/"),
            new SkillRoot(nexusaiHomePosix + "/skills",
                "~/" + NexusaiPaths.getProjectDirName() + "/skills/"));
    }

    /**
     * 1.6 范围校验的 skill scope 前缀判定 · <b>与产出侧同源</b>。
     *
     * <p>{@link #getClaudeSkillScope}（产出：{@code prefix + skillName + "/**"}）与本方法（消费）
     * 共用 {@link #skillScopeRoots} 的 prefix 集合，消除「产出
     * {@code ~/.{appName}/skills/<name>/**} 却被消费侧 {'/.claude/','~/.claude/'} 拒收」的漂移 ——
     * 该漂移使会话授权规则永不生效（用户确认后每次同类调用重新弹窗；实机证据：
     * {@code sessions.session_permission_rules} 已写入
     * {@code {"toolName":"Edit","ruleContent":"~/.nexusai/skills/tbox-generator/**"}} 仍弹）。
     *
     * <p>⛔ 只放宽「前缀集合」这一处；{@code '..'} 与 {@code '/**'} 两条护栏不得动。
     * ⛔ 前缀必须停在 {@code skills/}：退化为 {@code '~/.{appName}/'} 会把自有根下
     * settings.json 一并放行，属扩大豁免面。
     *
     * @param content ruleContent（可为 null）
     * @param cwd     校验基准 cwd（仅供 skillScopeRoots 的 project 段配对；prefix 与 cwd 无关）
     * @return true = content 以任一同源 skill scope 前缀开头
     */
    private static boolean startsWithSkillScopePrefix(String content, String cwd) {
        if (content == null) {
            return false;
        }
        for (SkillRoot root : skillScopeRoots(cwd, null)) {
            if (content.startsWith(root.prefix())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 会话级 skill 写保护窄化建议 · 对齐 CC {@code getClaudeSkillScope}
     * （filesystem.ts:101-157）：path 在项目/全局 {@code .claude/skills/{name}/} 内时，
     * 返回 skillName + 会话级 allow 模式（pattern = 前缀 + skillName + '/**'），供 1.7
     * 安全检查 ask 建议窄化的 "仅允许编辑该 skill" 会话授权（迭代单个 skill 无需放开
     * 整个 .claude/ 的 settings.json / hooks/）。
     *
     * <p>语义细节（读 CC 实际源码，非注释）：
     * <ul>
     *   <li>project base = cwd/.claude/skills（prefix '/.claude/skills/'），
     *       global base = homedir/.claude/skills（prefix '~/.claude/skills/'）（:107-116），
     *       另加 nexusai 自有根 {user.home}/.{appName}/skills（prefix '~/.{appName}/skills/'，决策 D1/D6）
     *       —— 三组配对统一由 {@link #skillScopeRoots} 产出，1.6 消费侧共用同一集合</li>
     *   <li>skillName 取 base 后第一段，需有分隔符（直接 skills/ 下的文件无 scope，:134-136）</li>
     *   <li>拒绝遍历（skillName 含 '..'，对齐 1.6 ruleContent.includes('..') guard，:138-144）、
     *       拒绝 '.'/空、拒绝 glob 元字符 [*?[\]]（防根目录通配模式匹配所有 skill，:150）</li>
     * </ul>
     *
     * @param filePath 原始 input 路径（可含 ~ / 相对，内部 expandPath 展开）
     * @param cwd       校验基准 cwd（project base 用；null/空 → 走 sessionId 显式解析）
     * @param sessionId 会话 ID（[批 3c] cwd 缺省时的显式来源；null = 无会话 → 进程 user.dir）
     * @return SkillScope 或 null（不在 skill 目录 / 不合法 skillName）
     */
    private static SkillScope getClaudeSkillScope(String filePath, String cwd, String sessionId) {
        if (filePath == null) {
            return null;
        }
        String absolutePath = toPosix(ReadPermissionChecker.expandPath(filePath,
            cwd != null && !cwd.isEmpty() ? Paths.get(cwd) : null));
        if (absolutePath == null || absolutePath.isEmpty()) {
            return null;
        }
        String absolutePathLower = absolutePath.toLowerCase();
        // 单点同源：base/prefix 配对由 skillScopeRoots 产出（⛔ 不再维护两个平行字面数组 ——
        //   历史缺陷即 base[2]/prefixes[2] 与 1.6 消费侧硬编码前缀三方不同步）。
        for (SkillRoot root : skillScopeRoots(cwd, sessionId)) {
            String dirLower = root.base().toLowerCase();
            if (!absolutePathLower.startsWith(dirLower + "/")) {
                continue;
            }
            String rest = absolutePath.substring(dirLower.length() + 1);
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                return null; // 直接 skills/ 下的文件，无 skill scope（CC :134-136）
            }
            String skillName = rest.substring(0, slash);
            // 拒绝遍历/./空（CC :138-144）；glob 元字符（CC :150）
            if (skillName.isEmpty() || skillName.equals(".") || skillName.contains("..")) {
                return null;
            }
            if (skillName.matches(".*[*?\\[\\]].*")) {
                return null;
            }
            return new SkillScope(skillName, root.prefix() + skillName + "/**");
        }
        return null;
    }

    /**
     * 1.7 安全检查 ask 建议 · 对齐 CC {@code filesystem.ts:1312-1327}：
     * <ol>
     *   <li>skill scope 命中（{@link #getClaudeSkillScope}）→
     *       {@code addRules: [Edit(prefix+skillName+'/**')] session allow}
     *       （窄化授权单个 skill，CC :1313-1326）；</li>
     *   <li>未命中 → {@code generateSuggestions(path, 'write', toolPermissionContext, pathsToCheck)}
     *       （CC :1327）—— 逐字对齐第二实参<b>字面量 {@code 'write'}</b>（CC :1327 原文，
     *       ⛔ 非按操作类型推导；CC generateSuggestions :1453 {@code write || create} 同分支）。</li>
     * </ol>
     *
     * <p><b>WHY 必须回落（恒非空建议）</b>：前端「一键授权」第三档仅在 suggestions 非空时渲染
     * （PermissionBubble.tsx:97 / permissionSuggestionLabels.ts:253 / useChatSocket.ts:155
     * 空数组 → null）。空建议 ⇒ 弹窗只剩「允许 / 拒绝」两档 ⇒ 用户点什么都生不出规则，
     * 与 CC（safety ask 恒附建议）不一致。
     *
     * <p><b>第一实参用 {@code expanded} 而非 CC 的原始 {@code path}</b>：CC 的路径展开藏在
     * {@code getDirectoryForPath} 内部（CC path.ts:109-125 自身 {@code expandPath(path)}）；
     * 本仓 {@link PermissionUpdates#getDirectoryForPath} 反过来要求「入参恒为已展开的绝对路径」
     * （见其 javadoc :163）。若直喂 rawPath，相对路径输入下 AddDirectories 会基于相对目录构造，
     * 偏离 CC 的<b>可观测结果</b>（CC 内部展开后得到绝对目录）。故 Java 侧等价物是 expanded
     * （与兜底步骤 5 同一惯例，见 {@code check} 的 writeSuggestions 调用点）。
     *
     * <p><b>残留（CC 同构，非本仓分歧，如实标注）</b>{@link PermissionUpdates#generateSuggestions}
     * 的 {@code shouldSuggestAcceptEdits} 判据只认 DEFAULT/PLAN（PermissionUpdates.java:247-248；
     * CC :1449-1451 同一判据，CC 亦含 'auto' | 'bubble' 内部模式）。故
     * <b>「工作目录内 + mode ∉ {DEFAULT, PLAN}」一格</b>（含子 Agent 的 {@link PermissionMode#BUBBLE}、
     * ACCEPT_EDITS / BYPASS_PERMISSIONS / DONT_ASK / AUTO）write 分支仍返回空。工作目录外因
     * AddDirectories（:257-266）恒非空。⛔ 不得以「顺手放宽判据」的方式私自补掉——那会偏离 CC；
     * 如需覆盖应作为独立对齐项另行登记。
     *
     * @param rawPath     原始 input 路径（skill scope 判定；getClaudeSkillScope 内部 expandPath）
     * @param expanded    已展开绝对路径（回落的 generateSuggestions 第一实参）
     * @param pathsToCheck 展开路径集合（CC 第四实参等价物 · isInWorkingDir 判定用）
     * @param ctx         工具调用上下文（sessionId / mode 来源）
     * @return 非空建议（skill 命中 → AddRules；否则 → generateSuggestions 结果，见上方残留说明）
     */
    private static List<PermissionUpdate> safetyAskSuggestions(
            String rawPath, String expanded, List<String> pathsToCheck, ToolUseContext ctx) {
        String cwd = cwdOf(ctx);
        // [批 3c] sessionId 显式穿透（cwd 缺省时不再经裸 MDC 解析）
        SkillScope scope = getClaudeSkillScope(rawPath, cwd, ctx != null ? ctx.sessionId() : null);
        if (scope != null) {
            PermissionRule rule = new PermissionRule(
                PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.withContent("Edit", scope.pattern()));
            if (log.isDebugEnabled()) {
                log.debug("[WritePermissionChecker] getClaudeSkillScope 命中，会话级窄化建议: path={} skill={} pattern={}",
                    rawPath, scope.skillName(), scope.pattern());
            }
            return List.of(new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.SESSION, List.of(rule), PermissionBehavior.ALLOW));
        }
        // 非 skill 路径 → 回落 generateSuggestions（CC filesystem.ts:1327）。
        if (ctx == null || ctx.permissionContext() == null) {
            // 防御兜底：唯一调用方 check 已在入口 fail-loud（permissionContext 恒非 null）；
            // 此分支仅防未来新调用方漏守卫（fail-loud 留痕，⛔ 不静默返回空）。
            if (log.isWarnEnabled()) {
                log.warn("[WritePermissionChecker] 1.7 safety ask 缺少 permissionContext，无法生成回落建议: path={}",
                    rawPath);
            }
            return List.of();
        }
        // 第四实参等价物：CC pathInAllowedWorkingPath(filePath, ctx, pathsToCheck) 的取反
        // （filesystem.ts:1425-1429 → :689-690 直接用 pathsToCheck，不再二次展开）。
        boolean outsideWorkingDir = !ReadPermissionChecker.isInWorkingDir(pathsToCheck, ctx);
        List<PermissionUpdate> fallback = PermissionUpdates.generateSuggestions(
            expanded, PermissionUpdates.OperationType.WRITE,
            ctx.permissionContext().mode(), outsideWorkingDir);
        if (log.isDebugEnabled()) {
            log.debug("[WritePermissionChecker] 1.7 safety ask 非 skill 路径，回落 generateSuggestions: path={} mode={} outsideWorkingDir={} 建议数={}",
                rawPath, ctx.permissionContext().mode(), outsideWorkingDir, fallback.size());
        }
        return fallback;
    }

    /** POSIX 归一（Windows 反斜杠 → 正斜杠，供 skill scope 路径前缀比较）。 */
    private static String toPosix(String s) {
        return s == null ? null : s.replace('\\', '/');
    }

    /**
     * Claude 配置文件判定（CC filesystem.ts:200-242 isClaudeConfigFilePath）。
     *
     * <p>两段语义：
     * <ol>
     *   <li>isClaudeSettingsPath（CC :200-222）：路径以 {@code <sep>.claude<sep>settings.json}
     *       或 {@code .claude<sep>settings.local.json} 结尾（大小写不敏感，防 case 绕过）。
     *       CC 另有 getSettingsPaths 精确匹配（managed/CLI-arg settings）——Java 无
     *       settings 路径机制，且 ~/.claude/settings.json 已被 endsWith 覆盖，略。</li>
     *   <li>路径在 {@code {cwd}/.claude/{commands,agents,skills}} 内（CC :230-241，
     *       pathInWorkingPath 判定）——Java 用 cwd 归一化前缀（大小写不敏感）。</li>
     * </ol>
     */
    private static boolean isClaudeConfigFilePath(String expanded, ToolUseContext ctx) {
        String sep = java.io.File.separator;
        String lower = expanded.toLowerCase();
        // 决策 D2/D6 全动态：项目级 nexusai 目录名 = NexusaiPaths.getProjectDirName()（.{appName}），
        // .nexusai/settings.json + .nexusai/settings.local.json 与 .claude 等价受保护配置 carve-out。
        String nexusaiDirLower = NexusaiPaths.getProjectDirName().toLowerCase();
        if (lower.endsWith(sep + ".claude" + sep + "settings.json")
                || lower.endsWith(sep + ".claude" + sep + "settings.local.json")
                || lower.endsWith(sep + nexusaiDirLower + sep + "settings.json")
                || lower.endsWith(sep + nexusaiDirLower + sep + "settings.local.json")) {
            return true;
        }
        // {cwd}/.claude/{commands,agents,skills} 子目录（CC :230-241）+ .nexusai 等价 carve-out
        if (ctx == null || ctx.effectiveCwd() == null) {
            return false;
        }
        String cwd = ctx.effectiveCwd().toAbsolutePath().normalize().toString();
        return isWithin(cwd, ".claude", "commands", lower)
            || isWithin(cwd, ".claude", "agents", lower)
            || isWithin(cwd, ".claude", "skills", lower)
            || isWithin(cwd, nexusaiDirLower, "commands", lower)
            || isWithin(cwd, nexusaiDirLower, "agents", lower)
            || isWithin(cwd, nexusaiDirLower, "skills", lower);
    }

    /** 大小写不敏感前缀判定：lowerExpanded 是否在 {cwd}/{sub1}/{sub2}/ 内。 */
    private static boolean isWithin(String cwd, String sub1, String sub2, String lowerExpanded) {
        String root = cwd.toLowerCase()
            .replace('\\', '/')
            + "/" + sub1 + "/" + sub2 + "/";
        return lowerExpanded.replace('\\', '/').startsWith(root);
    }

    /**
     * 危险文件/目录判定（CC filesystem.ts:435-488 isDangerousFilePathToAutoEdit）。
     *
     * <p>语义：UNC 前缀（防 NTLM）→ 路径段命中 DANGEROUS_DIRECTORIES
     * （.claude/worktrees 结构性目录例外，CC :456-468）→ 文件名命中 DANGEROUS_FILES，
     * 均大小写不敏感。CC 用平台分隔符 split；Java 兼容 '/' 与 '\\' 双分隔符
     * （Windows 上 input 可能混用，CC 该场景会漏检——Java 更严格，如实注明）。
     */
    private static boolean isDangerousFilePathToAutoEdit(String expanded) {
        // UNC 路径（CC :442-444，纵深防御）
        if (expanded.startsWith("\\\\") || expanded.startsWith("//")) {
            return true;
        }
        String[] segments = expanded.split("[\\\\/]");
        // [R12-3] 项目级 nexusai 目录名动态（决策 D1/D6）· DANGEROUS_DIRECTORIES 静态 Set 无法运行时
        //   动态，'.nexusai' 字面随 appName 变（spring.application.name）而失效 → getProjectDirName()
        //   兜底判定；'.claude' 保留 CC mirror（只读兼容）。appName=nexusai 时 = ".nexusai" 行为不变。
        String projectDirName = NexusaiPaths.getProjectDirName();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            if (!isDangerousDirectorySegment(segment, projectDirName)) {
                continue;
            }
            // .claude/worktrees 与 .{appName}/worktrees 是结构性目录（git worktree 存放处，决策 D7），
            //   跳过该段（不视为危险目录）
            if (segment.equalsIgnoreCase(".claude") || segment.equalsIgnoreCase(projectDirName)) {
                String next = i + 1 < segments.length ? segments[i + 1] : null;
                if (next != null && next.equalsIgnoreCase("worktrees")) {
                    continue;
                }
            }
            return true;
        }
        if (segments.length > 0) {
            String fileName = segments[segments.length - 1];
            for (String f : DANGEROUS_FILES) {
                if (f.equalsIgnoreCase(fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * [R12-3] 危险目录段判定 · 静态黑名单（{@link #DANGEROUS_DIRECTORIES}，CC filesystem.ts:74-79）
     * + 动态项目级 nexusai 目录名（{@link NexusaiPaths#getProjectDirName()}）。静态 Set 无法运行时
     * 动态 → 方法内 getProjectDirName() 兜底：appName 变（spring.application.name）时 '.{appName}'
     * 仍判危险。
     *
     * @param segment        路径段（单目录名）
     * @param projectDirName 动态项目级 nexusai 目录名（.{appName}）
     * @return 该段命中危险目录
     */
    private static boolean isDangerousDirectorySegment(String segment, String projectDirName) {
        for (String dir : DANGEROUS_DIRECTORIES) {
            if (segment.equalsIgnoreCase(dir)) {
                return true;
            }
        }
        return segment.equalsIgnoreCase(projectDirName);
    }

}
