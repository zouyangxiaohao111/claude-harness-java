package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 读权限检查器 · 对齐 CC {@code utils/permissions/filesystem.ts:1030-1193 checkReadPermissionForTool}。
 *
 * <h2>L1 语义：CC 九步决策链（filesystem.ts:1030-1193）· 逐步骤实现状态标注</h2>
 * <ol>
 *   <li>取路径（CC {@code tool.getPath(input)}，filesystem.ts:1035-1041）——✓ 已实现
 *       （[G3] 各工具 {@code getPath} 接口扩展点；无路径概念工具 default null → ask）；
 *       [S08] 取路径后先做 {@link PermissionPaths#getPathsForPermissionCheck} 展开
 *       （original+symlink 全路径），以下步骤 1-4/6 全部遍历展开路径</li>
 *   <li>UNC 路径 → ask（防 NTLM 凭据泄露，CC :1050-1064）——✓ 已实现（遍历 pathsToCheck，CC :1053-1064）</li>
 *   <li>可疑 Windows 路径模式 → ask（CC :1066-1079）——✓ 已实现
 *       （{@link PathValidation#hasSuspiciousWindowsPathPattern} 7 类全查，OPD-WF5-02-01；遍历 pathsToCheck）</li>
 *   <li>read-specific deny rule → deny（CC :1081-1101）——✓ 已实现（content rule 近似，遍历 pathsToCheck）</li>
 *   <li>read-specific ask rule → ask（CC :1103-1122）——✓ 已实现（content rule 近似，遍历 pathsToCheck）</li>
 *   <li><b>edit allow 蕴含 read allow（CC :1124-1134 调 checkWritePermissionForTool）——✓ 已实现</b>
 *       （P-AL-02）：步骤 5 委托 {@link WritePermissionChecker}（checkWritePermissionForTool
 *       等价物），仅消费 allow 结果——read-specific deny/ask 规则优先（CC :1124-1125 注释），
 *       edit deny/ask 不阻断 read（CC :1132 仅 behavior==='allow' 返回）。DEL-33 关闭。</li>
 *   <li>路径在工作目录内 → allow（CC :1136-1151）——✓ 已实现（{@link #isInWorkingDir}，
 *       全部 pathsToCheck 必须落在工作目录展开形式内，CC pathInAllowedWorkingPath :683-707）</li>
 *   <li><b>内部路径白名单 checkReadableInternalPath（CC :1153-1158）——△ 部分实现</b>：
 *       agent-memory（CC :1704-1712 isAgentMemoryPath）/ auto-memory（CC :1716-1723 memdir）/
 *       bundled-skills（CC :1759-1774 getBundledSkillsRoot）三个读 carve-out 已补（本类
 *       步骤 4.4/4.5/4.6）；session-memory / plans / tool-results 白名单仍缺（Java 端无对应
 *       概念，部分等价物散落在 ExecAgentHook / SessionFileAccessHooks，本类不做）。</li>
 *   <li><b>read allow rule → allow（CC :1160-1176）——△ 上游近似覆盖</b>：
 *       本类不查 allow rule；由权限管线上游 CheckLayer2b_ToolAlwaysAllowed（CheckLayer2b/BypassAndAllowHook）
 *       近似覆盖（deletion-manifest DEL-24 KEEP_WITH_REASON 关联）。</li>
 *   <li>兜底 → ask（CC :1178-1193）——✓ 已实现</li>
 * </ol>
 *
 * <h2>Java 端实现差异（L.md 标注 · concerns · 反 2026-08-05 reflector-L R1 修正披露）</h2>
 * <ul>
 *   <li><b>step5 edit-implies-read 已实现（P-AL-02）</b>：CC 在 read-specific 规则之后、
 *       working-dir 之前调 {@code checkWritePermissionForTool}，edit allow 时直接放行 read。
 *       Java 等价物 = {@link WritePermissionChecker}（新建，2026-08-06，DEL-33/O-L-1 关闭）。
 *       旧缺口（grep 0 命中 → 已授 Edit 的文件 Read 仍 ask）已消除，不再登记。</li>
 *   <li><b>step7 internal-path 部分实现</b>：CC 的 {@code checkReadableInternalPath} 三读 carve-out
 *       （agent-memory :1704-1712 / auto-memory :1716-1723 / bundled-skills :1759-1774）已补，
 *       bundled-skills 由 V-BD-5 新增（注入 {@link com.nexusai.application.agent.skill.BundledSkillFileExtractor}，
 *       与解压落盘路径同源 nonce 一致）；session-memory / plans / tool-results 仍缺，等价物在
 *       ExecAgentHook / SessionFileAccessHooks（非本类职责）。</li>
 *   <li><b>step8 allow rule 上游近似 + step5 联动</b>：whole-tool allow 仍由上游
 *       CheckLayer2b_ToolAlwaysAllowed 显式检查（CC permissions.ts:1284-1297，DEL-24
 *       KEEP_WITH_REASON）；content-specific edit allow 现由 step5 委托的
 *       {@link WritePermissionChecker} 步骤 4 直接命中（CC filesystem.ts:1377-1393，
 *       matchingRuleForInput 仅匹配 ruleContent 非空规则）——两端各司其职，无重复。</li>
 *   <li><b>[S08] symlink 路径展开已实现</b>：{@link PermissionPaths#getPathsForPermissionCheck}
 *       （CC fsOperations.ts:288-382 等价物）在步骤 1-4/6 前展开 original+symlink 全路径，
 *       全部展开路径参与 UNC / 可疑 Windows / deny / ask / 工作目录检查（CC filesystem.ts:1043-1048
 *       pathsToCheck 单次计算透传语义）；悬空/越界 symlink 目标同样入检（fail-closed，
 *       消除旧"仅检查原始字符串、PathGuard execute 阶段兜底"的 TOCTOU 绕过面）。</li>
 * </ul>
 *
 * @see Tool#checkPermissions(JsonNode, ToolUseContext)
 */
@Component
public class ReadPermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(ReadPermissionChecker.class);

    /**
     * Windows 路径可疑模式判定 · OPD-WF5-02-01 已委派 {@link PathValidation#hasSuspiciousWindowsPathPattern}
     * （CC filesystem.ts:537-602 7 类全查）。旧正则仅 4/7 且 {@code .{3,}} 无分隔符边界过度命中 → 已删。
     */


    /**
     * step5 edit-implies-read 委托目标（CC filesystem.ts:1124-1134 调
     * checkWritePermissionForTool）。null = 未装配（测试便捷构造器）→ step5 调用期
     * fail-loud ISE（对齐 ReadFileTool:314-317 依赖缺失模式，Pattern #11 关闭）。
     */
    private final WritePermissionChecker writePermissionChecker;

    // IMP-M-P2-4: auto-memory 路径解析（读 carve-out · 对齐 CC filesystem.ts:1716-1723
    //   checkReadableInternalPath memdir carve-out）。@Autowired(required=false)：无 bean 跳过。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths;
    public void setAutoMemPaths(com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths) {
        this.autoMemPaths = autoMemPaths;
    }

    // IMP-M-P2-2: agent-memory 路径解析（读 carve-out 第一分支 · 对齐 CC filesystem.ts:1704-1712
    //   isAgentMemoryPath）。@Autowired(required=false)：无 bean 跳过。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory;
    public void setAgentMemoryDirectory(com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory) {
        this.agentMemoryDirectory = agentMemoryDirectory;
    }

    // V-BD-5: bundled-skills 根目录解析（读 carve-out · 对齐 CC filesystem.ts:1759-1774
    //   checkReadableInternalPath bundled-skills 分支）。@Autowired(required=false)：无 bean 时
    //   注入缺失 → fail-closed 走兜底 ask（不静默 allow，防绕过）。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.skill.BundledSkillFileExtractor bundledSkillFileExtractor;
    public void setBundledSkillFileExtractor(com.nexusai.application.agent.skill.BundledSkillFileExtractor bundledSkillFileExtractor) {
        this.bundledSkillFileExtractor = bundledSkillFileExtractor;
    }

    // OPD-WF5-02-02/03/05: 通用路径校验核心（CC utils/permissions/pathValidation.ts + filesystem.ts
    //   核心）为静态工具类，本 checker 直接静态调用 PathValidation.checkReadableInternalPath /
    //   pathInWorkingPath / hasSuspiciousWindowsPathPattern（无 Spring 注入）。

    /**
     * 测试便捷构造器：writePermissionChecker 未注入 → step5 调用期 fail-loud ISE。
     */
    public ReadPermissionChecker() {
        this(null);
    }

    /**
     * 生产构造器（Spring 注入）。
     *
     * @param writePermissionChecker step5 edit-implies-read 委托目标（可为 null，
     *                                null 时 step5 调用期 fail-loud ISE）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReadPermissionChecker(WritePermissionChecker writePermissionChecker) {
        this.writePermissionChecker = writePermissionChecker;
    }

    /**
     * 对齐 CC {@code checkReadPermissionForTool(tool, input, permCtx)}。
     *
     * <p>[Session M.4.4 收尾] ctx / permCtx 为 null → fail-loud
     * {@link IllegalArgumentException}（对齐 {@code PermissionContextBuilder.java:177} 范例）。
     * 生产链路（CheckLayer1b/1c/1d/1e → {@code ReadFileTool.checkPermissions}）恒传
     * 非 null ctx/permCtx（权限管线构造）；null = 调用方 bug，不再静默 Allow。
     * CC {@code filesystem.ts:1030-1193} 无 null 守卫（null 解构即 TypeError = fail）。
     *
     * @param tool  工具实例（当前仅读 path 字段，未来可读 getPath）
     * @param input LLM 给的参数（JSON）
     * @param ctx   工具调用上下文（含 permissionContext）
     * @return      {@link PermissionResult}
     */
    public PermissionResult check(Tool tool, JsonNode input, ToolUseContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ReadPermissionChecker ctx is null");
        }
        if (ctx.permissionContext() == null) {
            throw new IllegalArgumentException("ReadPermissionChecker permissionContext is null");
        }
        ToolPermissionContext permCtx = ctx.permissionContext();
        // [G3] 路径提取迁出 extractPath → tool.getPath(input)（CC filesystem.ts:1035-1041
        //   typeof tool.getPath !== 'function' → ask; 否则 tool.getPath(input)）。各工具按 CC
        //   语义实现 getPath；无路径概念工具 default null → ask（CC 等价）。
        String path = tool.getPath(input);
        if (path == null || path.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[ReadPermissionChecker] 缺少 path（tool.getPath=null/空）→ ask: tool={}",
                    tool == null ? "null" : tool.name());
            }
            return new PermissionResult.Ask(
                "read 工具调用缺少 path",
                new PermissionDecisionReason.Other("missing path"),
                List.of(), null, input, null, false,null, List.of());
        }

        // expandPath（[FIX-A-R2] ~ 与相对路径 → 绝对；供 agent-memory/auto-mem carve-out
        // 判定用，对齐 CC filesystem.ts:1154 `absolutePath = expandPath(path)`）。
        // baseDir = ctx.effectiveCwd()（工具会话工作目录，与文件工具 backfill 的
        // guard.workdir() 语义一致）。
        String expanded = expandPath(path, ctx.effectiveCwd());

        // 权限检查路径展开（original+symlink 全路径，CC fsOperations.ts:288-382
        // getPathsForPermissionCheck 等价物）。单次计算供本链全部步骤复用，对齐 CC
        // filesystem.ts:1043-1048 pathsToCheck 透传语义（避免每步重复 existsSync/lstat/realpath）。
        // 注（[FIX-A-R2]）：CC 对 pathsToCheck 用原始 path（filesystem.ts:1048
        // `getPathsForPermissionCheck(path)`），相对→绝对由 backfill 在 gate 之前完成
        // （toolExecution.ts:781-793）；Java 对称保留原始 path，相对路径防绕过由
        // StreamingToolExecutor 把 backfilledInput 透传给 permission 门兜底。
        List<String> pathsToCheck = PermissionPaths.getPathsForPermissionCheck(path);

        // ── 步骤 1: UNC 路径 ask（防 NTLM 凭据泄露；遍历全部展开路径，CC :1053-1064） ──
        for (String pathToCheck : pathsToCheck) {
            if (pathToCheck.startsWith("\\\\") || pathToCheck.startsWith("//")) {
                if (log.isInfoEnabled()) {
                    log.info("[ReadPermissionChecker] UNC 路径 → ask: path={} pathToCheck={}",
                        path, pathToCheck);
                }
                return new PermissionResult.Ask(
                    "Claude 请求读取 UNC 网络路径 " + path + "，需用户确认",
                    new PermissionDecisionReason.Other("UNC path detected"),
                    List.of(), path, input, null, false, null, List.of());
            }
        }

        // ── 步骤 2: 可疑 Windows 路径 ask（遍历全部展开路径，CC :1066-1079） ──
        // OPD-WF5-02-01：委派 PathValidation.hasSuspiciousWindowsPathPattern（CC filesystem.ts:537-602
        // 7 类全查：ADS 冒号 / 8.3 短名 / 长路径前缀 / 尾点空格 / DOS 设备名 / 3+点路径段 / UNC）。
        for (String pathToCheck : pathsToCheck) {
            if (PathValidation.hasSuspiciousWindowsPathPattern(pathToCheck)) {
                if (log.isInfoEnabled()) {
                    log.info("[ReadPermissionChecker] 可疑 Windows 路径 → ask: path={} pathToCheck={}",
                        path, pathToCheck);
                }
                return new PermissionResult.Ask(
                    "Claude 请求读取含可疑 Windows 路径模式的文件 " + path + "，需用户确认",
                    new PermissionDecisionReason.Other("suspicious Windows path"),
                    List.of(), path, input, null, false, null, List.of());
            }
        }

        // ── 步骤 3: read-specific deny rule → deny（遍历全部展开路径，CC :1081-1101） ──
        // Java 端没有 read 桶的专门标记；RuleQuery 仅按 toolName + content 匹配。
        // 这里按 content-specific deny 查（CC 行为等价的近似）；symlink 目标路径同样
        // 参与匹配——deny 规则可经解析后落点命中（CC :1084-1100 SECURITY 注释语义：
        // deny 必须先于一切 allow 检查，防 symlink 间接路径绕过）。
        for (String pathToCheck : pathsToCheck) {
            PermissionRule denyRule = lookupRule(permCtx, tool, pathToCheck, true);
            if (denyRule != null) {
                if (log.isInfoEnabled()) {
                    log.info("[ReadPermissionChecker] deny rule 命中 → deny: rule={} path={} pathToCheck={}",
                        RuleQuery.ruleToString(denyRule), path, pathToCheck);
                }
                return new PermissionResult.Deny(
                    "读取 " + path + " 被权限规则拒绝",
                    new PermissionDecisionReason.Rule(denyRule),
                    null);
            }
        }

        // ── 步骤 4: read-specific ask rule → ask（遍历全部展开路径，CC :1103-1122） ──
        for (String pathToCheck : pathsToCheck) {
            PermissionRule askRule = lookupRule(permCtx, tool, pathToCheck, false);
            if (askRule != null) {
                if (log.isInfoEnabled()) {
                    log.info("[ReadPermissionChecker] ask rule 命中 → ask: rule={} path={} pathToCheck={}",
                        RuleQuery.ruleToString(askRule), path, pathToCheck);
                }
                return new PermissionResult.Ask(
                    "Claude 请求读取 " + path + "，权限规则要求确认",
                    new PermissionDecisionReason.Rule(askRule),
                    List.of(), path, input, null, false, null, List.of());
            }
        }

        // ── 步骤 5: edit allow 蕴含 read allow（CC filesystem.ts:1124-1134） ──
        // read-specific 规则之后、working-dir 之前：委托 WritePermissionChecker
        // （checkWritePermissionForTool 等价物），仅消费 behavior==='allow'——
        // deny/ask 结果忽略继续（显式 read 限制不被 edit 覆盖；edit deny 不拒 read）。
        // [S08] pathsToCheck 单次计算透传（CC :1130 checkWritePermissionForTool 第 4 参）。
        if (writePermissionChecker == null) {
            // 依赖缺失静默跳过 step5 = Pattern #11 门禁绕过 → fail-loud
            throw new IllegalStateException(
                "ReadPermissionChecker 未注入 WritePermissionChecker（step5 edit-implies-read 不可跳过）");
        }
        PermissionResult editResult = writePermissionChecker.check(tool, input, ctx, pathsToCheck);
        if (editResult instanceof PermissionResult.Allow) {
            if (log.isDebugEnabled()) {
                log.debug("[ReadPermissionChecker] edit 授权蕴含 read → allow: path={}", path);
            }
            return editResult;
        }

        // ── 步骤 4.4-4.6: 内部可读路径白名单（OPD-WF5-02-02）──
        // 对齐 CC filesystem.ts:1611-1777 checkReadableInternalPath 11 分支。委派核心
        // PathValidation.checkReadableInternalPath（新增 session-memory/project-dir/plan/
        // tool-results/scratchpad/project-temp/tasks/teams + auto-mem + bundled-skills，
        // 经 env 携带 bean 派生基址）；agent-memory 分支仍由本类 bean 判定（scope-aware，
        // 核心不重复）。
        PathValidationEnv env = PathValidationEnv.fromToolUseContext(ctx)
            .withAutoMem(autoMemPaths)
            .withBundledSkillsRoot(bundledSkillFileExtractor != null
                ? bundledSkillFileExtractor.getBundledSkillsRoot().toString() : null);
        PathValidation.InternalPathResult internal = PathValidation.checkReadableInternalPath(expanded, env);
        if (internal.allowed()) {
            if (log.isDebugEnabled()) {
                log.debug("[ReadPermissionChecker] 内部可读路径白名单命中 → allow: path={} reason={}",
                    path, internal.decisionReason());
            }
            return new PermissionResult.Allow(
                input, internal.decisionReason(), null, false, null, List.of());
        }

        // ── 步骤 4.5 (IMP-M-P2-2): agent-memory 读 carve-out（静默 allow）──
        // 对齐 CC filesystem.ts:1704-1712 checkReadableInternalPath 的 isAgentMemoryPath 分支：
        //   if (isAgentMemoryPath(normalizedPath)) return { behavior:'allow', reason:'Agent memory files are allowed for reading' }
        // CC 置于 memdir carve-out 之前（filesystem.ts:1704 isAgentMemoryPath → 1716 memdir）。
        // 无 isAutoMemoryEnabled 门控（isAgentMemoryPath 是纯路径判定，agentMemory.ts:67-104）。
        if (agentMemoryDirectory != null && agentMemoryDirectory.isAgentMemoryPath(expanded)) {
            if (log.isDebugEnabled()) {
                log.debug("[ReadPermissionChecker] agent-memory 读 carve-out 静默 allow (IMP-M-P2-2): path={}",
                    path);
            }
            return defaultAllow(input);
        }

        // ── 步骤 6: 路径在工作目录内 → allow（全部展开路径必须在内，CC :1136-1151 + pathInAllowedWorkingPath :683-707） ──
        if (isInWorkingDir(pathsToCheck, ctx)) {
            if (log.isDebugEnabled()) {
                log.debug("[ReadPermissionChecker] 全部展开路径在 working dir → allow: path={} pathsToCheck={}",
                    path, pathsToCheck);
            }
            return defaultAllow(input);
        }

        // ── 步骤 6: content-specific allow rule → allow ──
        // [IMPL-09] 对齐 CC checkReadPermissionForTool step 8 (filesystem.ts:1160-1176):
        //   CC 的 content allow（如 hook agent 的 Read(/transcriptPath) session rule，
        //   execAgentHook.ts:141-153）在工具 checkPermissions 内匹配 — 旧 R26 hook 层
        //   的 2b' 在 hook 内做同样匹配，随 6 hook 删除收窄回本路径（OD-SS-02）。
        PermissionRule allowRule = RuleQuery.getAllowRuleByContentsForTool(permCtx, tool, input);
        if (allowRule != null) {
            if (log.isInfoEnabled()) {
                log.info("[ReadPermissionChecker] allow rule 命中 → allow: rule={} path={}",
                    RuleQuery.ruleToString(allowRule), path);
            }
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Rule(allowRule),
                null, false, null, List.of());
        }
        // ── 步骤 7: 兜底 → ask（read + outside working dir → 产出 Read rule 建议） ──
        // [IMP-4 G2 + GAP-3] 对齐 CC checkReadPermissionForTool 兜底（filesystem.ts:1178-1193）：
        //   suggestions = generateSuggestions(path,'read',permCtx,pathsToCheck)。
        //   兜底只在本链已判"不在工作目录"之后到达（step 6 isInWorkingDir 为 false 才继续），
        //   故 isOutsideWorkingDir 恒 true（CC :1183 注释同义）。read 分支
        //   （filesystem.ts:1426-1437）对目录 getDirectoryForPath → getPathsForPermissionCheck
        //   展开 → 逐条 createReadRuleSuggestion(dir,'session')，由 PermissionUpdates 统一承接。
        List<PermissionUpdate> readSuggestions = PermissionUpdates.generateSuggestions(
            expanded, PermissionUpdates.OperationType.READ, permCtx.mode(), true);
        if (log.isDebugEnabled()) {
            log.debug("[ReadPermissionChecker] 兜底 → ask: path={} suggestions={}", path, readSuggestions);
        }
        return new PermissionResult.Ask(
            "Claude 请求读取 " + path + "，需要用户授权",
            new PermissionDecisionReason.Other("default ask for read outside working dir"),
            readSuggestions, path, input, null, false, null, List.of());
    }

    /**
     * expandPath：[FIX-A-R2] {@code ~} 与相对路径 → 绝对路径（委托
     * {@link PathGuard#expandPath(String, String)}，镜像 CC {@code utils/path.ts:32-85}）。
     *
     * <p>CC {@code expandPath} 同时展开 {@code ~}（→ homedir）、{@code ~/x}（→ home/x）、
     * 相对路径（→ resolve(baseDir, path).normalize()）与绝对路径（→ normalize()），
     * 防 {@code ~}/相对路径绕过 hook allowlist 与权限内容规则（CC FileReadTool.ts:389-390
     * 注释 "expand so hook allowlists can't be bypassed via ~ or relative paths"）。
     *
     * <p>[FIX-A-R2 升级] 旧实现只做 {@code ~} 展开（相对路径原样返回），导致相对
     * {@code file_path} 不命中绝对 deny/ask glob；本方法现委托 {@link PathGuard#expandPath}
     * 补齐相对→绝对展开（baseDir = 工具会话工作目录）。null 字节等非法输入 → try/catch
     * 回退原 path（fail-closed，落到后续可疑/UNC 检查兜底，不抛异常）。
     *
     * <p>包内共享：{@link WritePermissionChecker}（checkWritePermissionForTool 等价物，
     * 内部同样 expandPath 后做安全检查）复用本实现，保持单点。
     *
     * @param path    原始路径（可 null；null → 返回 null）
     * @param baseDir 相对路径解析基座（工具会话工作目录；null → PathGuard 回退 user.dir）
     * @return 展开后的绝对路径（平台原生格式、归一化）；非法输入回退原 path
     */
    static String expandPath(String path, java.nio.file.Path baseDir) {
        if (path == null) return null;
        try {
            return PathGuard.expandPath(path, baseDir != null ? baseDir.toString() : null);
        } catch (IllegalArgumentException e) {
            // null 字节等非法输入 → 回退原 path（backfill 阶段不阻断工具；fail-closed）
            if (log.isDebugEnabled()) {
                log.debug("[ReadPermissionChecker] expandPath 失败回退原 path: path={} cause={}",
                    path, e.getMessage());
            }
            return path;
        }
    }

    /**
     * 查 deny / ask rule（仅查 ruleContent 不为 null 的 content rule；whole-tool rule
     * 在更上层 ToolPermissionGate 已处理）。CC 行为近似。
     *
     * <p>[S08] 按展开路径逐条匹配（对齐 CC {@code matchingRuleForInput(pathToCheck, ...)}，
     * filesystem.ts:1084-1101/:1105-1122）：以 pathToCheck 构造合成 input（file_path），
     * 复用 RuleQuery 既有 matchesContent 匹配链（toolName 等价组 + glob/前缀/精确）。
     *
     * @param permCtx     权限上下文
     * @param tool        工具实例
     * @param pathToCheck 展开路径（original 或 symlink 目标）
     * @param deny        true=deny 桶；false=ask 桶
     * @return 第一个 content 匹配的规则；无匹配返回 null
     */
    private static PermissionRule lookupRule(
            ToolPermissionContext permCtx, Tool tool, String pathToCheck, boolean deny
    ) {
        JsonNode syntheticInput = JsonNodeFactory.instance.objectNode().put("file_path", pathToCheck);
        if (deny) {
            return RuleQuery.getDenyRuleByContentsForTool(permCtx, tool, syntheticInput);
        }
        // ask 桶走 getAskRuleByContentsForTool（仅查 ask 桶，对齐 CC ask 专用桶语义）。
        // deny 桶已在上方 deny=true 分支查过（无命中才走到这里），无需再查。
        return RuleQuery.getAskRuleByContentsForTool(permCtx, tool, syntheticInput);
    }

    /**
     * 路径在工作目录内？对齐 CC {@code pathInAllowedWorkingPath}（filesystem.ts:683-707）：
     * <b>全部</b> pathsToCheck（original+symlink 展开全路径）必须落在某工作目录的展开形式内
     * ——任一解析路径越界即拒绝（CC :702-706 every/some 语义，fail-closed，防 symlink 目标
     * 逃逸工作目录的自动放行）。
     *
     * <p>工作目录侧同样做 original+symlink 展开（CC :696-698
     * {@code allWorkingDirectories flatMap getResolvedWorkingDirPaths}，双侧解析对称——
     * 防 resolved 输入路径 vs 未解析 cwd 的误拒）。
     *
     * <p><b>[OD-FINAL-3b] 白名单锚收敛</b>：工作目录白名单根取
     * {@code CwdResolution.getOriginalCwdLayer(ctx.sessionId())}（对齐 CC
     * {@code allWorkingDirectories} 锚 {@code getOriginalCwd()}，filesystem.ts:667-674）。
     * WHY：白名单根=会话启动/worktree 目录，cd 进子目录不变（CC originalCwd 会话稳定）。
     * {@code ctx.effectiveCwd()} 仅保留作 {@link #expandPath} 相对路径解析 baseDir（:180-182，
     * 相对路径基准应 getCwd 随 cd 变，与白名单锚区分语义，不混改）。
     *
     * <p>包内共享：{@link WritePermissionChecker} step3（acceptEdits 模式 + 工作目录内
     * → allow，CC filesystem.ts:1360-1375）与兜底 reason 判定复用。
     *
     * @param pathsToCheck 展开路径集合（getPathsForPermissionCheck 结果）
     * @param ctx          工具调用上下文（sessionId 取 originalCwdLayer 白名单锚 + additionalWorkingDirectories）
     * @return true = 全部展开路径都在某工作目录内
     */
    static boolean isInWorkingDir(List<String> pathsToCheck, ToolUseContext ctx) {
        if (ctx == null || pathsToCheck == null || pathsToCheck.isEmpty()) {
            return false;
        }
        // 工作目录双侧解析（CC :696-698）
        // [OD-FINAL-3b] 白名单锚 ctx.effectiveCwd() → CwdResolution.getOriginalCwdLayer
        // （对齐 CC allWorkingDirectories 锚 getOriginalCwd，filesystem.ts:667-674）。
        // getOriginalCwdLayer 恒非 null（worktree originalCwd 槽 ?? boundProject ?? user.dir），
        // 故删 effectiveCwd != null 守卫；additionalWorkingDirectories 保留（CC 集合语义）。
        List<Path> workingPaths = new ArrayList<>();
        String originalCwd = com.nexusai.application.agent.agent.CwdResolution
            .getOriginalCwdLayer(ctx.sessionId());
        for (String wp : PermissionPaths.getPathsForPermissionCheck(originalCwd)) {
            workingPaths.add(toNormalizedPath(wp));
        }
        var awd = ctx.additionalWorkingDirectories();
        if (awd != null) {
            for (var entry : awd.values()) {
                if (entry.path() == null) continue;
                for (String wp : PermissionPaths.getPathsForPermissionCheck(
                        Paths.get(entry.path()).toAbsolutePath().toString())) {
                    workingPaths.add(toNormalizedPath(wp));
                }
            }
        }
        if (workingPaths.isEmpty()) {
            return false;
        }
        // 全部路径必须落在某工作目录内（CC :702-706 pathsToCheck.every(...)）
        // OPD-WF5-02-03：内层比较委派 PathValidation.pathInWorkingPath（CC filesystem.ts:709-744）——
        // macOS /private/var→/var、/private/tmp→/tmp 归一 + 大小写归一（防 .cLauDe/CoMmAnDs case
        // 变体绕过），旧 Path.startsWith 大小写敏感已替换。
        for (String pathToCheck : pathsToCheck) {
            Path resolved = toNormalizedPath(pathToCheck);
            if (resolved == null) {
                return false;
            }
            boolean inside = false;
            for (Path wp : workingPaths) {
                if (PathValidation.pathInWorkingPath(resolved.toString(), wp.toString())) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                if (log.isDebugEnabled()) {
                    log.debug("[ReadPermissionChecker] 路径不在任何工作目录（fail-closed）: pathToCheck={} workingPaths={}",
                        pathToCheck, workingPaths);
                }
                return false;
            }
        }
        return true;
    }

    /** 绝对化 + 归一化；解析失败（非法路径）返回 null（fail-closed）。 */
    private static Path toNormalizedPath(String p) {
        try {
            return Paths.get(p).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 默认 Allow result（updatedInput 回传原 input；reason=Other）。
     * <p>bundled-skills 分支判定已迁至 {@link PathValidation#checkReadableInternalPath}
     * （OPD-WF5-02-02，isWithin 尾分隔符防 nonce 前缀攻击语义等价），本类不再重复。
     */
    public static PermissionResult defaultAllow(JsonNode input) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("read permission default allow"),
            null, false, null, List.of());
    }
}