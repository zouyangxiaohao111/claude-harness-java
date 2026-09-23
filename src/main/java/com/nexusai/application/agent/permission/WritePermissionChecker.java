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
 *       （suspicious windows / claude config / dangerous files，见 {@link #checkPathSafetyForAutoEdit}）。
 *       <br><b>⚠️ 本仓对 CC 顺序的唯一偏离（本批修复，登记理由见下）</b>：CC 的三道安全检查
 *       <b>无例外</b>地排在全部 allow 规则之前（CC :1303-1304 注释「This MUST come before
 *       checking allow rules to prevent users from accidentally granting permission to edit
 *       protected files」）。但第 3 道「危险文件/目录」（{@link #isDangerousFilePathToAutoEdit}）
 *       在本仓把<b>整个配置主根</b> {@code .{appName}} 判为危险目录 → 该根下<b>非 memory / 非 skill</b>
 *       的路径（如 {@code ~/.nexusai/teams/**}、{@code ~/.nexusai/projects/<其他 slug>/**}）
 *       结构性只走 Ask ⇒ <b>步骤 4（edit allow rule）永不可达</b>、用户批准过的规则永不生效。
 *       <br>本批在该道检查内加入<b>唯一穿透门</b> {@link #isCoveredByUserApprovedSessionRule}
 *       （判据 = 「用户在该会话明确批准过的、带路径内容的 Edit allow 规则覆盖此路径」）。
 *       <b>谁能穿透 1.7（仅第 3 道「危险文件/目录」）</b>：{@code alwaysAllowRules[SESSION]} 中
 *       带非空 {@code ruleContent}、不含 {@code '..'}、root-relative 匹配到该路径、
 *       <b>且其目录根（{@link #ruleDirectoryRoot}，已循环剥 {@code '/**'} 并剔 glob 元字符）
 *       落在触发本次判定的危险根之内，或恰为自有配置根<b>且批准规则文本为「锚形」</b></b>（护栏②，
 *       {@link #isApprovalScopeAllowed} / {@link #isSelfRootAnchorRule}）的 Edit allow 规则；
 *       <br><b>[本批按用户 2026-09-22 裁定放宽]</b> 「恰为自有配置根」= {@code ~/.{appName}/**} /
 *       {@code ~/.claude/**} / {@code <repo>/.{appName}/**} —— 即 CC 的 {@code ~/.claude/**}
 *       （CC 1.6 :1281-1290 接受该前缀并先于 1.7 放行）。三条等价写法（尾部 {@code /**} 重复
 *       两次、{@code /**} 后接 {@code /*}、{@code /*} 后接 {@code /**}）经本批规则根归一后
 *       <b>同档</b>（精确字面量见 {@code WritePermissionCheckerUserApprovedPenetrationTest}）；
 *       <br><b>[用户 2026-09-23 裁定「照 CC 收窄」]</b> 「恰为自有配置根」这一档<b>只对锚形规则生效</b>
 *       —— 规则<b>文本</b>必须以 {@code ~/.{appName}/} / {@code /.{appName}/} / {@code ~/.claude/} /
 *       {@code /.claude/} 开头（<b>含尾分隔符</b>，逐字节等于 CC 的 {@code slice(0, -2)} 实参；
 *       {@link #isSelfRootAnchorRule}，与 1.6 范围校验的四个「文件夹级」前缀同一批常量 /
 *       同一派生方法 ⇒ 同一来源、同一边界）。收窄前只看「危险根末段」，故
 *       {@code <home>/Downloads/.{appName}/**}、{@code D:/scratch/.{appName}/**}
 *       这类<b>任意位置的同名目录</b>也获相等档放行；CC 的 1.6 是文本前缀锚且 1.7 无穿透门
 *       ⇒ 该形态在 CC 里恒 Ask，本批起与 CC 同构（拒收，落 1.7 Ask）；
 *       <br><b>[批 2026-09-23 · 锚改含尾分隔符]</b> 上一批的锚<b>去了</b>尾分隔符
 *       （{@code ~/.{appName}}）⇒ <b>前缀碰撞形</b>（{@code ~/.{appName}foo/.{appName}/**}、
 *       {@code ~/.{appName}-archive/.{appName}/**}、{@code ~/.claudefoo/.claude/**}）文本上仍通过
 *       {@code startsWith} ⇒ 仍获相等档（偏离 CC）。本批照 CC 改回含尾分隔符 ⇒ 碰撞形转 Ask；
 *       <br><b>[批 2026-09-22「发钥匙」· 复验者实测补录 / 批 2026-09-23 修订]</b>
 *       <b>尾斜杠形</b> {@code ~/.{appName}/}（不带 {@code /**}）<b>仍穿透</b>：它经
 *       {@link #ruleDirectoryRoot} 的「剥尾分隔符」归一到 {@code <home>/.{appName}}，与危险根
 *       {@link #isSameDir} 相等，<b>且</b>其文本 {@code startsWith} 含尾分隔符的锚 ⇒ 走「自有根档」
 *       放行。⛔ <b>裸目录形</b> {@code ~/.{appName}}（无尾斜杠）<b>本批起转 Ask</b>（照 CC：
 *       CC 的比较实参含尾斜杠，裸目录形通不过）—— 见 (a3)/(a4) 的成对用例；
 *       <b>谁不能穿透</b>：①非 SESSION 源规则（USER_SETTINGS / PROJECT_SETTINGS / LOCAL_SETTINGS /
 *       FLAG_SETTINGS / POLICY_SETTINGS / CLI_ARG / COMMAND —— 含 {@code projectSettings}
 *       可被恶意仓库控制，故排除）；②无 {@code ruleContent} 的整工具 session 规则；
 *       ③{@code acceptEdits} 模式（步骤 3）；④「工作目录内」；⑤1.5 内部可编辑路径白名单；
 *       ⑥1.7 第 1 道（可疑 Windows 模式，{@code classifierApprovable=false}）与第 2 道
 *       （Claude 配置文件）——这两道<b>不可</b>被任何用户规则穿透；
 *       ⑦比危险根更宽的批准（{@code ~/**}、{@code /**}、{@code <repo>/**}）与等于<b>非</b>
 *       自有配置根的批准（{@code <repo>/.git/**}、{@code <repo>/.vscode/**}、
 *       {@code <repo>/.idea/**}）；⑧<b>非锚形</b>规则文本（{@code //c/Users/x/.{appName}/**}、
 *       {@code <home>/Downloads/.{appName}/**} 等全绝对路径形 —— 本批用户裁定，与 CC 同构）；
 *       ⑨UNC 危险根（恒不可穿透）；⑩<b>[批 2026-09-23 · 锚含尾分隔符]</b> <b>前缀碰撞形</b>
 *       （{@code ~/.{appName}foo/.{appName}/**}、{@code ~/.{appName}-archive/.{appName}/**}、
 *       {@code ~/.claudefoo/.claude/**} —— 规则文本与锚<b>共享前缀但非以锚（含尾分隔符）开头</b>）
 *       以及<b>裸目录形</b> {@code ~/.{appName}}（无尾斜杠）—— 该两形在上一批（锚去尾分隔符）
 *       曾被误收，本批照 CC 拒收（落 1.7 Ask）。</li>
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
 *       '~/.{appName}/skills/{name}/**' 被消费侧拒收 ⇒ 会话授权永不生效」的前缀漂移缺陷。
 *       <br><b>[批 2026-09-22「发钥匙」]</b> 接受集<b>再补两个本仓自有根前缀</b>：
 *       '/.{appName}/' 与 '~/.{appName}/'（CC 那两个 {@code .claude} 常量的 {@code .{appName}}
 *       对应物，派生自 {@link PermissionUpdates#projectSelfFolderPattern()} /
 *       {@link PermissionUpdates#globalSelfFolderPattern()} 的 slice(0,-2) ⇒ 与产出侧单点同源）。
 *       与 CC 的两个 {@code .claude} 前缀<b>并存</b>（后者不删：{@link #skillScopeRoots} 仍含
 *       '.claude/skills' 两条根，产出侧仍会写 '…/.claude/skills/{name}/**'）。</li>
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
     * 「锚形规则」的 CC 分支前缀（<b>含尾分隔符</b> · 与 1.6 同一常量派生）=
     * {@link #CLAUDE_FOLDER_ROOT_PREFIX} = {@code '/.claude/'}。
     *
     * <p>用途 = 1.7 第 3 道穿透门「相等档」的收窄判据（见 {@link #isSelfRootAnchorRule}）：
     * 只在规则文本以<b>锚点前缀</b>开头时才认「恰为自有配置根」。本常量<b>不去尾分隔符</b> ——
     * 逐字节等于 CC 的比较实参本尊（{@code ruleContent.startsWith(
     * CLAUDE_FOLDER_PERMISSION_PATTERN.slice(0, -2))}；{@code '/.claude/**'} 的
     * {@code slice(0,-2)} 结果含尾斜杠 = {@code '/.claude/'}；{@code filesystem.ts:1284}）。
     * ⛔ 刻意不另抄字面量：本常量与 1.6 接受集的同名一项同源于
     * {@link #CLAUDE_FOLDER_PERMISSION_PATTERN} 的 {@code slice(0,-2)}（见
     * {@link #folderRootPrefix}），杜绝「前缀漂移」。
     *
     * <p>[批 2026-09-23 · 锚改含尾分隔符] 上一批（{@code anchor-narrow}）在此处用
     * {@link #stripTrailingSlashes} 去掉了尾分隔符 ⇒ <b>前缀碰撞形</b>（{@code ~/.{appName}foo/…}、
     * {@code ~/.{appName}-archive/…}、{@code ~/.claudefoo/…}）文本上仍通过 {@code startsWith}
     * ⇒ 仍获相等档 = 偏离 CC 的「同名目录 elsewhere」嵌套残留变体。本批照 CC 改回含尾分隔符
     * ⇒ 碰撞形转 Ask；代价 = 裸目录形 {@code ~/.{appName}}（无尾斜杠）转 Ask —— 而
     * <b>这正是 CC 的行为</b>（CC 实参含尾分隔符，裸目录形通不过）。
     */
    private static final String CLAUDE_FOLDER_ANCHOR_PREFIX = CLAUDE_FOLDER_ROOT_PREFIX;

    /**
     * 「锚形规则」的全局 CC 分支前缀（<b>含尾分隔符</b> · 与 1.6 同一常量派生）=
     * {@link #GLOBAL_CLAUDE_FOLDER_ROOT_PREFIX} = {@code '~/.claude/'}（CC 实参
     * {@code GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN.slice(0, -2)}，{@code filesystem.ts:1286}）。
     */
    private static final String GLOBAL_CLAUDE_FOLDER_ANCHOR_PREFIX =
        GLOBAL_CLAUDE_FOLDER_ROOT_PREFIX;

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
        // ⚠️ 本仓 1.7 第 3 道「危险文件/目录」内有一个<b>用户明确批准的会话规则</b>穿透门
        //    （CC 无对应：CC 只把 .claude 判危险，且其 1.6 用 .claude 前缀范围校验兜住；
        //      本仓 .{appName} 覆盖面更广 ⇒ 照抄前缀校验会让 ~/.nexusai/** 永久不可授权）。
        // expanded 透传：1.7 的 generateSuggestions 回落需要已展开绝对路径
        // （CC 传原始 path，但其 getDirectoryForPath 内部自行 expandPath；本仓
        //  PermissionUpdates.getDirectoryForPath 反过来要求入参已展开，见其 javadoc）。
        // permCtx / cwdStr 透传：1.7 第 3 道「危险文件/目录」内的用户批准穿透门
        // （isCoveredByUserApprovedSessionRule）需要读 alwaysAllowRules[SESSION] 并按 cwd
        // root-relative 匹配（与步骤 4 同一匹配基准 ⇒ 穿透成立则步骤 4 必然命中同一规则）。
        PermissionResult safety = checkPathSafetyForAutoEdit(
            pathsToCheck, path, expanded, ctx, permCtx, cwdStr);
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
        // [GAP-3] 对齐 CC 写侧档位二选一（permissionOptions.tsx:105-150）：
        //   目标在自有根（~/.{appName} / <cwd>/.{appName}）内 → 专用档
        //   addRules(Edit, '~/.{appName}/**' | '/.{appName}/**', allow, session)（替换通用档）；
        //   否则回落 generateSuggestions write 分支（filesystem.ts:1448-1463）：
        //   shouldSuggestAcceptEdits（default/plan mode）→ SetMode(acceptEdits)；
        //   工作目录外 → AddDirectories(getPathsForPermissionCheck(dirPath))。
        //   旧实现空 suggestions → 用户"始终允许"时无建议；补齐 write/create 建议。
        List<PermissionUpdate> writeSuggestions = PermissionUpdates.writeAskSuggestions(
            expanded, permCtx.mode(), !inWorkingDir, cwdStr);
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
     * <b>或</b>以本仓自有根文件夹级前缀 '/.{appName}/' 或 '~/.{appName}/' 开头
     * （{@link PermissionUpdates#projectSelfFolderPattern()} /
     * {@link PermissionUpdates#globalSelfFolderPattern()} 的 slice(0,-2)，产出侧单点同源）、
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
        // 本仓自有根（.{appName}）两个「文件夹级」前缀 · 动态（appName 运行时可变）——
        //   与产出侧 PermissionUpdates 的 pattern 单点同源（CC FileEditTool/constants.ts:5/:8
        //   两个 .claude 常量的 .{appName} 对应物，前缀 = pattern.slice(0,-2)）。
        //   [批 2026-09-22「发钥匙」] 补上消费侧：此前 1.6 只收 '/.claude/'、'~/.claude/' 与
        //   skill scope 前缀 ⇒ 弹窗新档产出的 '~/.{appName}/**' / '/.{appName}/**' 结构上被拒
        //   ⇒ 用户点了专用档也只落 1.7 Ask（产出侧与消费侧不同源 = 「有按钮没用」）。
        String projectSelfRootPrefix = folderRootPrefix(PermissionUpdates.projectSelfFolderPattern());
        String globalSelfRootPrefix = folderRootPrefix(PermissionUpdates.globalSelfFolderPattern());
        boolean selfRootPrefix = content != null
            && (content.startsWith(projectSelfRootPrefix)
                || content.startsWith(globalSelfRootPrefix));
        // 接受集 = CC 两个「文件夹级」前缀（filesystem.ts:1281-1290）
        //   ∪ 本仓自有根两个「文件夹级」前缀（.{appName} 对应物，见上）
        //   ∪ 与产出侧 getClaudeSkillScope 同源的 skill scope 前缀（修前缀漂移）。
        // ⛔ 刻意不对 CC 那两个前缀做「skills 级收窄」：CC 接受 '/.claude/' 本身，收窄成
        //   '/.claude/skills/' 会把 '/.claude/agents/**' 这类会话授权从 1.6 放行降级为 1.7 ask，
        //   属偏离 CC 的行为回退（规则七：显式择优，不折中调和）。
        // ⛔ 保留 '.claude' 系前缀（本批不加不改）：skillScopeRoots 的 project/global 两条根仍是
        //   '.claude/skills'（CC mirror，只读兼容）⇒ 产出侧 getClaudeSkillScope 仍会产出
        //   '/.claude/skills/{name}/**' / '~/.claude/skills/{name}/**'，删掉这两条前缀会让既有
        //   skill 会话授权在 1.6 结构上被拒（制造新的同源漂移）。
        boolean scopeOk = content != null
            && (content.startsWith(CLAUDE_FOLDER_ROOT_PREFIX)
                || content.startsWith(GLOBAL_CLAUDE_FOLDER_ROOT_PREFIX)
                || selfRootPrefix
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
        if (selfRootPrefix) {
            if (log.isInfoEnabled()) {
                // [SELF_ROOT_16_ALLOW] = ASCII 计数锚（验收 `grep -ac` 用；控制台非 UTF-8 时
                //   中文被转码，光靠中文串数不准）。计数 = 1.6 收下的「.{appName} 系规则」条数。
                log.info("[WritePermissionChecker] 1.6 自有根（.{appName}）会话授权命中 → allow"
                        + " [SELF_ROOT_16_ALLOW]: rule={} path={}",
                    RuleQuery.ruleToString(rule), path);
            }
        } else if (log.isInfoEnabled()) {
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
     *   <li>可疑 Windows 路径模式 → ask（classifierApprovable=false）—— <b>不可被用户规则穿透</b></li>
     *   <li>Claude 配置文件（.claude/settings.json / settings.local.json /
     *       {cwd}/.claude/{commands,agents,skills} 内）→ ask（classifierApprovable=true）——
     *       <b>不可被用户规则穿透</b></li>
     *   <li>危险文件/目录（.git/.vscode/.idea/.claude/.{appName} + shell/安全敏感文件）→ ask
     *       （classifierApprovable=true）——<b>唯一可被「用户明确批准的会话规则」穿透的一道</b>
     *       （见 {@link #isCoveredByUserApprovedSessionRule}；本仓 1.7 相对 CC 的顺序偏离，
     *       理由见类 javadoc）</li>
     * </ol>
     *
     * @param pathsToCheck 展开路径集合（original + symlink 全路径）
     * @param rawPath      原始 input 路径（skill scope 判定 + 消息展示；getClaudeSkillScope 内部自行 expandPath）
     * @param expanded     已展开绝对路径（回落的 generateSuggestions 用；⛔ 不可传 rawPath，
     *                     见 {@link #safetyAskSuggestions} 的「第一实参」说明）
     * @param ctx          工具调用上下文（{cwd}/.claude/{commands,agents,skills} 判定 + 建议生成用）
     * @param permCtx      权限上下文（第 3 道的用户批准穿透门读 {@code alwaysAllowRules[SESSION]}）
     * @param cwd          校验基准 cwd（穿透门的 root-relative 匹配锚，与步骤 4 同源）
     * @return Ask 或 null（全部通过）
     */
    private PermissionResult checkPathSafetyForAutoEdit(
            List<String> pathsToCheck, String rawPath, String expanded, ToolUseContext ctx,
            ToolPermissionContext permCtx, String cwd) {
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
            String dangerousRoot = dangerousRootForAutoEdit(pathToCheck);
            if (dangerousRoot == null) {
                continue;
            }
            // ── [本批修复 · 1.7 第 3 道唯一穿透门] 用户在本会话明确批准过该路径 ⇒ 本档 ask 让位 ──
            // 病根（实机取证）：本仓把整个配置主根 .{appName} 判为危险目录 ⇒ ~/.nexusai/** 下
            //   非 memory / 非 skill 的路径结构性只走本档 Ask ⇒ 步骤 4（edit allow rule）
            //   永不可达（生产日志实测 "edit allow rule 命中" = 0 次）⇒ 用户批准过的规则永不生效。
            // 处理：本档 ask 让位（continue），由后续步骤裁决 —— 因为穿透门与步骤 4 用同一匹配基准
            //   （同一 SESSION 规则、同一 rawPath/pathToCheck、同一 cwd），穿透成立则步骤 4 必然
            //   命中同一规则并返回 Allow(Rule)，可观测性由步骤 4 既有 INFO 日志（"edit allow rule 命中"）
            //   承担；若穿透成立而步骤 4 未命中（镜像/symlink 目标路径情形），则落到步骤 5 兜底 Ask
            //   —— fail-closed，不会静默放行。
            // ⛔ 仅本档可被穿透；第 1 道（可疑 Windows，classifierApprovable=false，反绕过护栏）
            //   与第 2 道（Claude 配置文件）不得被任何用户规则穿透。
            if (isCoveredByUserApprovedSessionRule(permCtx, pathToCheck, cwd, dangerousRoot)) {
                if (log.isInfoEnabled()) {
                    // [USER_APPROVED_PENETRATION] = ASCII 计数锚（验收 `grep -ac` 用；控制台非 UTF-8
                    //   时中文会被转码，光靠中文串数不准）。
                    log.info("[WritePermissionChecker] 1.7 危险文件/目录 ask 被用户已批准会话规则穿透"
                            + " [USER_APPROVED_PENETRATION]（让位给步骤 4 裁定）: path={} pathToCheck={}",
                        rawPath, pathToCheck);
                }
                continue;
            }
            if (log.isInfoEnabled()) {
                log.info("[WritePermissionChecker] 危险文件/目录 → ask: path={} pathToCheck={}",
                    rawPath, pathToCheck);
            }
            return new PermissionResult.Ask(
                "Claude 请求编辑 " + rawPath + "，属敏感文件",
                new PermissionDecisionReason.SafetyCheck("Path is a sensitive file", true),
                safetySuggestions, rawPath, null, null, false, null, List.of());
        }
        return null;
    }

    /**
     * 1.7 第 3 道（危险文件/目录）的<b>唯一穿透门</b> · 判据 = 「用户在本会话明确批准过的、
     * 带路径内容的 Edit allow 规则覆盖该路径」。
     *
     * <h2>为什么是「SESSION 桶 + 带 ruleContent」这两条（对齐 CC 的判据来源）</h2>
     * <p>CC 的同类机制是 1.6（CC filesystem.ts:1252-1272）：它把查询<b>收窄到 session 桶</b>，
     * 原注释（CC :1254-1261）写明理由是「We only allow this for session-level rules to prevent
     * users from accidentally permanently granting broad access to their .claude/ folder」——
     * 即「用户在该次交互中明确授予」才是可穿透安全检查的授权语义。本门沿用同一判据，
     * 并额外要求 {@code ruleContent} 非空（整工具裸规则 {@code Edit} 不构成「批准了<b>这条路径</b>」，
     * 与本仓既有 content-rule 匹配口径一致：{@code RuleQuery.getEditRuleByContentsForPath}
     * 本就跳过 {@code ruleContent == null} 的规则）。</p>
     *
     * <h2>⛔ 本门<b>不是</b>「宽松档」（不得被误当冗余删掉或扩大）</h2>
     * <p>刻意<b>不</b>接受下列来源/形态（它们都「看起来像已授权」但不是用户的明确路径批准）：
     * <ul>
     *   <li>非 SESSION 源（USER_SETTINGS / PROJECT_SETTINGS / LOCAL_SETTINGS / FLAG_SETTINGS /
     *       POLICY_SETTINGS / CLI_ARG / COMMAND）：既非「本次交互批准」，且 {@code projectSettings}
     *       位于被编辑的仓库内 —— 恶意仓库可自带 allow 规则借本门解锁自己仓库里的
     *       {@code .git/config} 写入（CC memdir/paths.ts:172-177 同类推理已在本仓登记）。</li>
     *   <li>{@code '..'} 规则内容（CC 1.6 同一护栏，防 {@code ~/.nexusai/../**} 形逃逸）。</li>
     *   <li>{@code acceptEdits} 模式 / 工作目录内 / 1.5 内部白名单：这三档是<b>模式与位置</b>，
     *       与「用户批准了哪条路径」无关（本批用户约束 a 明文禁止其穿透 1.7）。</li>
     * </ul>
     *
     * <h2>与 1.6 的关系（本门不取代 1.6，也不动 1.6）</h2>
     * <p>1.6 先于本门执行，处理「session 规则 + {@code .claude} / skill scope 前缀范围校验」；
     * 本门处理 1.6 <b>拒收</b>的那些 session 规则（如 {@code ~/.nexusai/teams/**} —— 前缀不在
     * 1.6 接受集内）。二者都返回 Allow 且都以同一规则为 reason ⇒ 对 1.6 已放行的路径
     * <b>行为不变</b>（1.6 仍先命中并打它自己的日志）。
     *
     * <h2>匹配基准必须与步骤 4 一致（否则穿透让位会变成静默降级）</h2>
     * <p>本门与步骤 4 同用 {@link RuleQuery#getEditRuleByContentsForPath}
     * （同一 SESSION 桶 / 同一 {@code pathToCheck} 或 rawPath / 同一 {@code cwd}）。
     * 差异仅一处且方向安全：本门遍历 {@code pathsToCheck}（含 symlink 目标），
     * 步骤 4 只匹配 rawPath ⇒ symlink 目标被批准而 raw 路径未批准时，穿透成立但步骤 4 不命中
     * ⇒ 落步骤 5 兜底 Ask（<b>不会</b>放宽）。</p>
     *
     * <h2>护栏②（上一批新增，本批按用户裁定修订为「自有根档」）</h2>
     * <p>光有「SESSION + 带 ruleContent + 不含 '..'」还不够：一条<b>宽范围</b>批准
     * （{@code ~/**}、{@code /**}、{@code <repo>/**}）会把「危险目录本身」一并授出
     * （连带 {@code hooks/}、{@code <repo>/.git/**}）—— 那是「批准了比危险根更宽的范围」。
     * 故增加：<b>批准规则的目录根必须落在触发本次判定的危险根之内，或恰为自有配置根本身
     * 且规则文本为「锚形」</b>（{@link #isApprovalScopeAllowed}{@code (ruleDirectoryRoot,
     * ruleContent, dangerousRoot)} + {@link #isSelfRootAnchorRule}；后者为用户 2026-09-23
     * 裁定「照 CC 收窄」的落点）。</p>
     *
     * <p><b>本批修订（user 裁定 2026-09-22）</b>：上一批只认「严格在内」，于是
     * {@code ~/.{appName}/**}（= CC 的 {@code ~/.claude/**}）被护栏② 挡下。用户口径原话：
     * 「CC 里 {@code ~/.claude/**} 能穿透，到我们这 就是 {@code ~/.nexusai} 能穿透。
     * {@code .claude} 对应就是我们的 {@code .nexusai}」，被问「照 CC 要推翻一条既有护栏，
     * 确认放宽吗」时答「<b>放宽到整个自有根（照 CC）</b>」。核对 CC 真源该放宽成立：
     * CC {@code filesystem.ts:1281-1290} 的范围校验接受 {@code '~/.claude/**'}（前缀
     * {@code slice(0,-2)} = {@code '~/.claude/'}，<b>含尾斜杠</b>）并<b>先于</b> 1.7 返回 allow
     * ⇒ CC 里「整个自有根」的会话批准确实穿透安全检查。</p>
     *
     * <p>⛔ 仍未放宽（本批刻意保留）：①比危险根<b>更宽</b>的范围（祖先：{@code ~/**}、
     * {@code /**}、{@code <repo>/**}）；②等于<b>非自有配置根</b>的危险根（{@code <repo>/.git/**}、
     * {@code <repo>/.vscode/**}、{@code <repo>/.idea/**} —— 这些根不是「自有配置根」，
     * 批它们本身即等于批一个我们不拥有的配置面）；③UNC 危险根（{@code \\server\share\...}）
     * 结构性恒不可穿透（{@link #dangerousRootForAutoEdit} 对 UNC 返回路径本身，本批额外显式
     * 排除，保住「UNC 恒危险」的纵深防御不变量）。</p>
     *
     * @param permCtx      权限上下文（读 {@code alwaysAllowRules[SESSION]}）
     * @param pathToCheck  待判定路径（展开路径集合中的一条；与步骤 4 同一 root-relative 匹配口径）
     * @param cwd          校验基准 cwd（root-relative 匹配锚）
     * @param dangerousRoot 本 pathToCheck 触发的危险根（{@link #dangerousRootForAutoEdit}，非 null）
     * @return true = 用户在本会话明确批准过该路径（本档 ask 让位）
     */
    private static boolean isCoveredByUserApprovedSessionRule(
            ToolPermissionContext permCtx, String pathToCheck, String cwd, String dangerousRoot) {
        if (permCtx == null || pathToCheck == null || pathToCheck.isBlank()) {
            return false;
        }
        Set<PermissionRule> sessionRules = permCtx.alwaysAllowRules().get(PermissionRuleSource.SESSION);
        if (sessionRules == null || sessionRules.isEmpty()) {
            return false;
        }
        // session-only 上下文（与 1.6 同形：只保留 session 桶，杜绝其他来源借道）
        ToolPermissionContext sessionOnly = new ToolPermissionContext(
            permCtx.mode(),
            Map.of(PermissionRuleSource.SESSION, sessionRules),
            Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
        PermissionRule rule = RuleQuery.getEditRuleByContentsForPath(
            sessionOnly, pathToCheck, PermissionBehavior.ALLOW, cwd);
        if (rule == null) {
            return false;
        }
        // getEditRuleByContentsForPath 已跳过 ruleContent==null；此处再显式判一次（防上游松绑）
        String content = rule.ruleValue().ruleContent();
        if (content == null || content.isBlank()) {
            return false;
        }
        // 护栏①：'..' 拒绝（CC 1.6 同一护栏，防 '../' 逃逸出批准范围）
        if (content.contains("..")) {
            if (log.isInfoEnabled()) {
                log.info("[WritePermissionChecker] 用户批准穿透门拒绝含 '..' 的会话规则"
                        + " [PENETRATION_REJECTED:dotdot]: content={} pathToCheck={}",
                    content, pathToCheck);
            }
            return false;
        }
        // 护栏②（本批按用户裁定修订）：批准范围必须「落在危险根之内」或「恰为自有配置根本身」，
        //   且（本批新增收窄）后者要求规则文本是<b>锚形</b>。
        //   · 严格在内（isStrictlyInside）→ 放行（上一批行为，不变）；
        //   · 等于自有配置根且规则文本锚形（~/.{appName}/** 、/.{appName}/** 、~/.claude/** 系）
        //     → 放行（用户 2026-09-22 裁定「照 CC：~/.claude/** ⇒ ~/.nexusai/**」+ 2026-09-23
        //     裁定「照 CC 收窄相等档，只对锚形规则生效」）；
        //   · 比危险根更宽（祖先：~/**、/**、<repo>/**）、等于<b>非</b>自有配置根（<repo>/.git/**）
        //     或<b>规则文本非锚形</b>（<home>/Downloads/.{appName}/** 等全绝对路径形）→ 仍拒
        //     （见 javadoc「护栏②」节）。
        String ruleDir = ruleDirectoryRoot(content, cwd);
        if (!isApprovalScopeAllowed(ruleDir, content, dangerousRoot)) {
            if (log.isInfoEnabled()) {
                log.info("[WritePermissionChecker] 用户批准穿透门拒绝（批准范围比危险根更宽/未进入危险根"
                        + "/规则文本非锚形，护栏②） [PENETRATION_REJECTED:scope]:"
                        + " content={} ruleDir={} anchorRule={} dangerousRoot={} pathToCheck={}",
                    content, ruleDir, isSelfRootAnchorRule(content), dangerousRoot, pathToCheck);
            }
            return false;
        }
        if (log.isInfoEnabled()) {
            log.info("[WritePermissionChecker] 用户批准穿透门命中（护栏①②均过）: rule={} ruleDir={}"
                    + " dangerousRoot={} pathToCheck={}",
                RuleQuery.ruleToString(rule), ruleDir, dangerousRoot, pathToCheck);
        }
        return true;
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
     * 会话授权模式 → 「文件夹级」前缀 · 逐字节等价 CC 的 {@code pattern.slice(0, -2)}
     * （{@code filesystem.ts:1284-1287} 对两个 {@code .claude} 常量做 slice）：
     * {@code '/.claude/**' → '/.claude/'}、{@code '~/.claude/**' → '~/.claude/'}。
     *
     * <p>本仓自有根（{@code .{appName}}）的两个前缀经本方法从
     * {@link PermissionUpdates#projectSelfFolderPattern()} /
     * {@link PermissionUpdates#globalSelfFolderPattern()} 派生 ⇒ 与产出侧<b>单点同源</b>
     * （⛔ 不得在本类另抄一份 {@code "/.nexusai/"} 字面量：appName 可变，抄了必漂）。
     *
     * @param pattern 完整模式（{@code <前缀>/**}；null/过短 → 空串）
     * @return {@code <前缀>/}
     */
    private static String folderRootPrefix(String pattern) {
        if (pattern == null || pattern.length() < 3) {
            return "";
        }
        return pattern.substring(0, pattern.length() - 2);
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
        List<PermissionUpdate> fallback = PermissionUpdates.writeAskSuggestions(
            expanded, ctx.permissionContext().mode(), outsideWorkingDir, cwd);
        if (log.isDebugEnabled()) {
            log.debug("[WritePermissionChecker] 1.7 safety ask 非 skill 路径，回落写侧档位（自有设置专用档 或 generateSuggestions）: path={} mode={} outsideWorkingDir={} 建议数={}",
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
        return dangerousRootForAutoEdit(expanded) != null;
    }

    /**
     * 「命中危险判定的那个根」· {@link #isDangerousFilePathToAutoEdit} 的取值化形态。
     *
     * <p><b>WHY 需要根而不只是布尔</b>：1.7 第 3 道的用户批准穿透门必须能回答「用户批准的范围
     * 是否已经<b>进入</b>这个危险目录/文件本身，或<b>就是</b>这个自有配置根」—— 若只判布尔，
     * `~/**`、`<repo>/**` 这类「批准了比危险根更宽的范围」会一并穿透
     * （`<repo>/**` 会连带放开 `<repo>/.git/**` 的写入）。有了根，判据可收敛为
     * 「批准目录必须<b>落在该根之内</b>，或<b>恰为自有配置根</b>」
     * （见 {@link #isApprovalScopeAllowed}；后者为 2026-09-22 用户裁定「照 CC 放宽到整个自有根」）。
     *
     * <p>返回值形态（POSIX 归一，供前缀比较）：
     * <ul>
     *   <li>目录段命中（DANGEROUS_DIRECTORIES ∪ 动态 {@code .{appName}}）→ 到该段为止的目录；</li>
     *   <li>危险文件名命中（DANGEROUS_FILES）→ <b>该文件的父目录</b>（于是「批准这个文件本身」
     *       算严格在内、而「批准它所在目录」不算）；</li>
     *   <li>UNC（{@code \\} / {@code //} 开头）→ 返回路径本身 ⇒ 任何批准范围都<b>不可能</b>
     *       严格落在其下 ⇒ 结构性不可穿透（fail-closed，对齐「UNC 恒危险」的纵深防御）。</li>
     * </ul>
     *
     * @param expanded 待判定路径（原始或 symlink 展开形）
     * @return 危险根（POSIX 归一）；不危险 → null
     */
    private static String dangerousRootForAutoEdit(String expanded) {
        if (expanded == null || expanded.isBlank()) {
            return null;
        }
        // UNC 路径（CC :442-444，纵深防御）：根 = 路径本身 ⇒ 批准范围无法严格进入 ⇒ 恒不可穿透
        if (expanded.startsWith("\\\\") || expanded.startsWith("//")) {
            return toPosix(expanded);
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
            // 到该段为止的目录（POSIX 归一；Windows 盘符段如 'C:' 原样保留）
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k <= i; k++) {
                if (k > 0) {
                    sb.append('/');
                }
                sb.append(segments[k]);
            }
            return sb.toString();
        }
        if (segments.length > 0) {
            String fileName = segments[segments.length - 1];
            for (String f : DANGEROUS_FILES) {
                if (f.equalsIgnoreCase(fileName)) {
                    // 危险文件 → 根取父目录；无父目录（裸文件名，如相对路径 ".bashrc"）→ 取文件名本身
                    //   （保持与重构前 isDangerousFilePathToAutoEdit 同判：裸文件名同样危险；
                    //    取文件名本身 ⇒ 需批准范围严格在「该文件之下」⇒ 实际上不可穿透，fail-closed）
                    if (segments.length == 1) {
                        return toPosix(fileName);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < segments.length - 1; k++) {
                        if (k > 0) {
                            sb.append('/');
                        }
                        sb.append(segments[k]);
                    }
                    return sb.toString();
                }
            }
        }
        return null;
    }

    /**
     * 穿透门护栏②的<b>批准范围判据</b>（本批按用户 2026-09-23 裁定收窄）· 与
     * {@link #isStrictlyInside} 的分工：本方法回答「这条批准的范围算不算已经进入/就是危险根」，
     * {@link #isStrictlyInside} 只回答「是否严格在内」。
     *
     * <p>三层判定（顺序即放宽程度，由紧到松）：
     * <ol>
     *   <li>{@link #isStrictlyInside}{@code (ruleDir, dangerousRoot)} → true 直接放行（上一批行为）；</li>
     *   <li><b>自有根档</b>：{@code ruleDir} 与 {@code dangerousRoot} 指向同一目录，该危险根末段为
     *       自有配置根段，<b>且<u>本批新增</u>该批准规则的<u>文本</u>是「锚形」</b>
     *       （{@link #isSelfRootAnchorRule}）→ 放行；</li>
     *   <li>其余（祖先 / 跨根 / 非自有配置根的相等 / <b>非锚形规则</b> / UNC 危险根）→ 拒绝。</li>
     * </ol>
     *
     * <h2>为什么「相等」必须限定在自有配置根（而不是放宽成普遍相等）</h2>
     * <p>放宽的理由只有一条：照 CC 的 {@code ~/.claude/**} 让<b>自己的配置主根</b>可被
     * 会话批准穿透（用户裁定）。{@code <repo>/.git/**}、{@code <repo>/.vscode/**}、
     * {@code <repo>/.idea/**} 这些危险根<b>不是</b>自有配置根 —— 批它们本身等于批一个
     * 我们并不拥有、且 CC 也不认的配置面（CC 1.6 只接受 {@code .claude} 系前缀），故仍按
     * fail-closed 拒绝（判别器：{@code <repo>/.git/**} 仍 Ask，见
     * {@code WritePermissionCheckerUserApprovedPenetrationTest#gitRootWildcard_stillAsks}）。</p>
     *
     * <h2>本批收窄（用户 2026-09-23 裁定：「照 CC 收窄（推荐）」）</h2>
     * <p>被斟酌的裁定问句原话：「1.7 穿透门的 {@code isSelfConfigRoot} 只取路径末段
     * （CC 的 1.6 是文本前缀锚）—— 照 CC 就该收窄。你选？」⇒ 答「照 CC 收窄（推荐）」。</p>
     * <p>收窄前的洞：相等档只问「<b>危险根</b>末段是不是 {@code .claude} / {@code .{appName}}」，
     * <b>不看批准的锚点</b> ⇒ 任意位置的同名目录（{@code <home>/Downloads/.{appName}}、
     * {@code D:/scratch/.{appName}}）都算「自有配置根」而获相等档放行。CC 的对应判据是
     * <b>规则文本的锚定前缀</b>（{@code ruleContent.startsWith('~/.claude/') ||
     * startsWith('/.claude/')}，filesystem.ts:1281-1290）⇒ CC 里写全绝对路径的
     * {@code <home>/Downloads/.claude/**} <b>不</b>通过范围校验（CC 的 1.7 也没有穿透门
     * —— filesystem.ts:1302-1308 三道安全检查后直接 ask ⇒ 该形态在 CC 里恒 Ask）。</p>
     * <p>故本批把「危险根末段」判据换成<b>规则文本的锚前缀判据</b>
     * （{@link #isSelfRootAnchorRule}，其四项前缀与 1.6 那四个「文件夹级」前缀<b>逐字节同源</b>
     * —— 同一批常量 / 同一派生方法，且<b>均含尾分隔符</b>，见该方法 javadoc 的「与 1.6 的同源边界」节），
     * 保留
     * {@link #isSelfConfigRoot}（危险根末段）作<b>纵深防御</b>层 —— 它在本批的锚前缀判据下
     * 已被蕴含（锚形规则 + {@link #isSameDir} 相等 ⇒ 危险根必为那个锚点目录），保留只为
     * 边界兜底与可读性，⛔ 不得单独用它当「自有根」判据（那正是本批消除的失效面）。</p>
     * <p>⚠️ 本批的必然代价（照 CC 的固有结果，非本仓新增洞）：<b>全绝对路径形</b>的
     * 「自有根相等档」批准一并失效（如 {@code //c/Users/x/.{appName}/**}），落 1.7 Ask ——
     * 与 CC 同构（CC 1.6 拒收、1.7 无穿透门）。用户诉求（弹窗「编辑自有设置」档产出的
     * {@code ~/.{appName}/**} / {@code /.{appName}/**} 锚形规则仍 Allow）由 1.6 承担，
     * 不受本收窄影响（见 {@code WritePermissionCheckerUserApprovedPenetrationTest} 的
     * 锚形可达性对照用例）。</p>
     *
     * <h2>UNC 为什么显式排除</h2>
     * <p>{@link #dangerousRootForAutoEdit} 对 UNC 返回<b>路径本身</b>（而非目录），并声明
     * 「任何批准范围都不可能严格落在其下 ⇒ 结构性不可穿透」。若这里不排除，一条
     * {@code Edit(\\\\server\\share\\.nexusai/**)} 会让末段命中自有根段而<b>相等放行</b>，
     * 推翻该不变量 ⇒ 本方法对 {@code //} 开头的危险根一律拒绝（fail-closed）。</p>
     *
     * @param ruleDir       批准规则的目录根（{@link #ruleDirectoryRoot} 产出，POSIX 归一；可 null）
     * @param ruleContent   命中规则的 ruleContent（本批新增：锚形判据必须看<b>规则文本</b>，
     *                      {@link #ruleDirectoryRoot} 归一后的 {@code ruleDir} 已丢失 {@code ~} 等锚信息；可 null）
     * @param dangerousRoot 触发本次判定的危险根（{@link #dangerousRootForAutoEdit}，非 null）
     * @return true = 该批准范围足以穿透本档 ask
     */
    private static boolean isApprovalScopeAllowed(
            String ruleDir, String ruleContent, String dangerousRoot) {
        if (ruleDir == null || dangerousRoot == null || dangerousRoot.isBlank()) {
            return false;
        }
        if (isStrictlyInside(ruleDir, dangerousRoot)) {
            return true;
        }
        // UNC 危险根：恒不可穿透（保住 dangerousRootForAutoEdit 的 UNC 不变量）
        if (dangerousRoot.startsWith("//")) {
            return false;
        }
        if (!isSameDir(ruleDir, dangerousRoot) || !isSelfConfigRoot(dangerousRoot)) {
            return false;
        }
        // 本批收窄：相等档只对「锚形规则」生效（规则文本必须以自有根锚点前缀开头）
        if (!isSelfRootAnchorRule(ruleContent)) {
            if (log.isInfoEnabled()) {
                // [SELF_ROOT_ANCHOR_REJECTED] = ASCII 计数锚（本批收窄的可观测点；grep -ac 用）
                log.info("[WritePermissionChecker] 护栏② 相等档拒绝：规则文本非锚形"
                        + " [SELF_ROOT_ANCHOR_REJECTED]: ruleContent={} ruleDir={} dangerousRoot={}",
                    ruleContent, ruleDir, dangerousRoot);
            }
            return false;
        }
        if (log.isInfoEnabled()) {
            // [SELF_ROOT_RELAXATION] = ASCII 计数锚（验收 grep -ac 用）
            log.info("[WritePermissionChecker] 护栏② 自有根档放宽命中 [SELF_ROOT_RELAXATION]:"
                    + " ruleContent={} ruleDir={} dangerousRoot={}",
                ruleContent, ruleDir, dangerousRoot);
        }
        return true;
    }

    /**
     * 「锚形规则」判据（本批新增 · 用户 2026-09-23 裁定「照 CC 收窄」）。
     *
     * <p>语义 = 规则文本是否以<b>自有根锚点前缀</b>开头（{@code startsWith}，<b>非</b>
     * {@code equals}、<b>非</b>末段比较）—— 对齐 CC 的
     * {@code ruleContent.startsWith(CLAUDE_FOLDER_PERMISSION_PATTERN.slice(0, -2))}
     * （filesystem.ts:1281-1290 的 1.6 范围校验）。四个锚<b>均含尾分隔符</b>，且是
     * {@link #checkClaudeFolderSessionAllow}（1.6）同名四项的<b>逐字节同一批常量 / 同一派生方法</b>
     * （{@link #folderRootPrefix}）⇒ 同一来源、同一边界，防前缀漂移：
     * <ul>
     *   <li>{@code ~/.{appName}/} 与 {@code /.{appName}/}：本仓自有根两个锚，派生自产出侧
     *       {@link PermissionUpdates#globalSelfFolderPattern()} /
     *       {@link PermissionUpdates#projectSelfFolderPattern()}（{@code '~/.{appName}/**'} /
     *       {@code '/.{appName}/**'}）的 {@code slice(0,-2)}（保留尾分隔符）；</li>
     *   <li>{@code ~/.claude/} 与 {@code /.claude/}：CC 两个锚
     *       （{@link #GLOBAL_CLAUDE_FOLDER_ANCHOR_PREFIX} / {@link #CLAUDE_FOLDER_ANCHOR_PREFIX}，
     *       逐字节 = CC 的 {@code slice(0,-2)} 实参；保留依据 = 1.6 也接受这两个 {@code .claude}
     *       前缀，且 {@link #skillScopeRoots} 仍产出 {@code '~/.claude/skills/{name}/**'} ⇒
     *       收窄掉会让该形态的相等档行为与 1.6 不同源）。</li>
     * </ul>
     *
     * <h2>与 1.6 的同源边界（措辞按复验意见改准）</h2>
     * <p>同源的是<b>前缀字符串本身</b>（四项逐字节相同、同一批常量与派生方法）；<b>不同源的是
     * 两侧的额外条件</b>：1.6 另要求 {@code endsWith("/**")} 且不含 {@code ..}（故「锚形但不以
     * {@code /**} 结尾」的写法 1.6 拒收、本判据收下 —— 这正是相等档在“发钥匙”后仍可达的原因），
     * 本判据则另由 {@link #isApprovalScopeAllowed} 要求 {@code ruleDir} 与危险根
     * {@link #isSameDir} 相等、且危险根末段命中自有配置根段。上一批「锚去尾分隔符」时两侧前缀
     * 边界<b>并不同源</b>（1.6 含尾斜杠、1.7 不含）；本批改回含尾分隔符后四项前缀
     * <b>边界一致</b>。</p>
     *
     * <p><b>[批 2026-09-23 · 锚改含尾分隔符，照 CC]</b> 上一批的锚<b>去掉了</b>尾分隔符
     * （{@code '~/.{appName}'} 而非 {@code '~/.{appName}/'}）—— 那是上一批派单书的字面写法，
     * 但<b>偏离 CC 实参</b>（CC 的 {@code slice(0,-2)} 含尾斜杠）。后果 = <b>前缀碰撞形</b>
     * {@code ~/.{appName}foo/.{appName}/**}、{@code ~/.{appName}-archive/.{appName}/**}、
     * {@code ~/.claudefoo/.claude/**} 文本上仍 {@code startsWith('~/.{appName}')} ⇒ 仍获相等档
     * （= 「同名目录 elsewhere」病的嵌套残留变体，偏离 CC）。本批照 CC 改回含尾分隔符
     * ⇒ 碰撞形转 Ask。必然代价（CC 同形的固有结果）：<b>裸目录形</b> {@code ~/.{appName}}
     * （<b>无</b>尾斜杠）通不过 ⇒ 转 Ask（= 用例 (a3)）；而<b>尾斜杠形</b>
     * {@code ~/.{appName}/}（无 glob）仍 {@code startsWith} 含尾斜杠的锚 ⇒ 仍 Allow
     * （= 用例 (a4)，见 {@code WritePermissionCheckerUserApprovedPenetrationTest}）。</p>
     *
     * <h2>[登记 · 本批实测] 相等档在收窄后是否还可达 —— <b>可达，但对钥匙批产出的规则零贡献</b></h2>
     * <p><b>实测判据（非推断）</b>：跑
     * {@code mvn -o -Dtest=WritePermissionCheckerUserApprovedPenetrationTest test}，从 surefire 的
     * {@code system-out} 里按本类三个 ASCII 计数锚计数（原始输出见该批验收记录）。
     * <b>数字口径 = 本批（2026-09-23 锚含尾分隔符）改后的最终形态，同一命令同一类</b>：</p>
     * <ul>
     *   <li>{@code [SELF_ROOT_16_ALLOW]}（1.6 路线）= <b>12</b> 次 / 去重 <b>7</b> 种，命中规则的文本
     *       <b>全部</b>是锚形（去重实测：{@code ~/.nexusai/**}, {@code /.nexusai/**},
     *       {@code ~/.nexusai/teams/proj-a/**}, {@code ~/.nexusai/skills/tbox-generator/**},
     *       {@code ~/.nexusai/projects/<slug>/memory/**}，外加两条「尾部通配重复」的等价写法）
     *       ⇒ <b>「发钥匙」批产出的两种锚形规则（{@code ~/.{appName}/**}, {@code /.{appName}/**}）
     *       根本到不了 1.7</b>（1.6 先返回 Allow）—— 用户诉求由 1.6 承担，与本档无关。
     *       （基线 = 11：本批新增的可达性对照 (d) 增了 1 次 1.6 路线命中。）</li>
     *   <li>{@code [SELF_ROOT_RELAXATION]}（相等档命中）= <b>6</b> 次，规则文本<b>全部</b>是
     *       「锚形但 1.6 拒收」的写法。按<b>不同规则形态</b>去重 = <b>3</b> 种：
     *       {@code ~/.nexusai/} 后接『{@code /**} 再接 {@code /*}』（「尾部通配重复」形）、
     *       {@code ~/.claude/*}、{@code ~/.nexusai/}（尾斜杠形）。<b>触发面确实进一步缩小</b>：基线（上一批，锚去尾分隔符）
     *       的去重形态是 <b>4</b> 种，多出的那一种就是<b>裸目录形 {@code ~/.nexusai}</b> —— 本批它
     *       已从相等档跌到 {@code [SELF_ROOT_ANCHOR_REJECTED]}（见下）。（原始计数 6 &gt; 基线的 5
     *       只因本批新增用例多跑了几遍尾斜杠形，与触发面无关。）</li>
     *   <li>{@code [SELF_ROOT_ANCHOR_REJECTED]}（锚前缀拒收）= <b>7</b> 次 / 去重 <b>7</b> 种：
     *       {@code //c/Users/WIN/.claude/**}、{@code <repo绝对>/.nexusai/**}、
     *       {@code //c/Users/WIN/Downloads/.nexusai/**}（基线 3 种，全绝对路径 / 同名目录 elsewhere）
     *       —— 本批新增 4 种：{@code ~/.{appName}foo/.{appName}/**}、{@code ~/.{appName}-archive/.{appName}/**}、
     *       {@code ~/.claudefoo/.claude/**}（<b>前缀碰撞形</b>）与 {@code ~/.{appName}}（<b>裸目录形</b>）
     *       ⇒ 本批「锚含尾分隔符」<b>确实生效</b>（碰撞形与裸目录形都从相等档跌到本锚）。</li>
     * </ul>
     * <p><b>⚠️ 该计数锚同时是可命中性证据</b>：{@code [SELF_ROOT_ANCHOR_REJECTED]} 在
     * {@link #isApprovalScopeAllowed} 内、<b>晚于</b> {@code isSameDir(ruleDir, dangerousRoot) &&
     * isSelfConfigRoot(dangerousRoot)} 两关之后打印 ⇒ 它出现即证明该规则<b>已在匹配层命中</b>且
     * 规则根<b>已等于</b>危险根 ⇒ 上面的 Ask 不是「规则没命中」的假绿。</p>
     * <p><b>结论</b>：相等档<b>并非死代码</b>（仍被「锚形但不以 {@code /**} 结尾」的写法触发），
     * 但<b>对用户诉求（弹窗专用档产出的锚形规则）零贡献</b> —— 那些规则由 1.6 更早放行。
     * 即：若只为「用户批准过的自有根写入」这一目的，相等档是冗余的；它今天的实际作用仅剩
     * 「尾斜杠形 / 非目录通配结尾的锚形规则仍能穿透」这一兼容面（裸目录形本批已移除）。</p>
     * <p>⛔ <b>登记项，不自行处置</b>：是否撤除相等档（或整个 1.7 穿透门）属<b>独立裁定</b>
     * —— 本批只报告 + 登记，⛔ 不得据此删除 {@link #isApprovalScopeAllowed} 的相等档分支。
     * （撤除会连带改变裸目录形等既有行为，需用户另行确认。）</p>
     *
     * @param ruleContent 命中规则的 ruleContent（可 null → false，fail-closed）
     * @return true = 规则文本以自有根锚点前缀开头
     */
    private static boolean isSelfRootAnchorRule(String ruleContent) {
        if (ruleContent == null) {
            return false;
        }
        String c = ruleContent.trim();
        return c.startsWith(CLAUDE_FOLDER_ANCHOR_PREFIX)
            || c.startsWith(GLOBAL_CLAUDE_FOLDER_ANCHOR_PREFIX)
            || c.startsWith(folderRootPrefix(PermissionUpdates.projectSelfFolderPattern()))
            || c.startsWith(folderRootPrefix(PermissionUpdates.globalSelfFolderPattern()));
    }

    /**
     * 危险根是否为「自有配置根」（末段 = {@code .claude} 或 {@code .{appName}}）。
     * 与 {@link #isDangerousDirectorySegment} 同源口径（大小写不敏感），只取<b>末段</b>：
     * {@code C:/Users/x/.nexusai} → true；{@code D:/repo/.git} → false。
     *
     * <p><b>⚠️ 本方法单独用是「只取末段」判据 —— ⛔ 不得作为相等档的唯一条件</b>
     * （批 2026-09-22「发钥匙」补录的后果，批 2026-09-23 已收窄）。历史形态：相等档 = 本方法
     * 单独判定 ⇒ 任何末段名为 {@code .{appName}} / {@code .claude} 的危险根都被当自有根，
     * 例如 {@code <home>/Downloads/.{appName}}、{@code D:/scratch/.{appName}} ⇒ 一条全绝对路径的
     * 会话规则即可获相等档放行（比 CC 宽一格：CC 的 1.6 是锚定文本前缀，该形态通不过）。</p>
     *
     * <p>本批起本方法只作 {@link #isApprovalScopeAllowed} 的<b>纵深防御</b>层：真正的收窄
     * 判据是 {@link #isSelfRootAnchorRule}（规则文本锚前缀）。在本批形态下本层已被蕴含
     * —— 「锚形规则」经 {@link #ruleDirectoryRoot} 解析出的 {@code ruleDir} 必以该锚点目录为根，
     * 再与 {@code dangerousRoot} {@link #isSameDir} 相等 ⇒ 该危险根就是那个锚点目录。保留本层
     * 是为边界兜底（例如 1.6 接受集与 1.7 锚集若将来漂移时仍 fail-closed），代价为零。</p>
     */
    private static boolean isSelfConfigRoot(String dangerousRoot) {
        int idx = Math.max(dangerousRoot.lastIndexOf('/'), dangerousRoot.lastIndexOf('\\'));
        String last = idx >= 0 ? dangerousRoot.substring(idx + 1) : dangerousRoot;
        return last.equalsIgnoreCase(".claude")
            || last.equalsIgnoreCase(NexusaiPaths.getProjectDirName());
    }

    /**
     * 两个 POSIX 归一目录是否指向同一目录（去尾分隔符 + Windows 大小写不敏感，
     * 与 {@link #isStrictlyInside} 同口径）。空串/null → false（fail-closed）。
     */
    private static boolean isSameDir(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = stripTrailingSlashes(toPosix(a));
        String y = stripTrailingSlashes(toPosix(b));
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win");
        return windows
            ? x.equalsIgnoreCase(y)
            : x.equals(y);
    }

    /**
     * 批准范围是否<b>严格落在</b>危险根之下 · 穿透门护栏②（严格档）。
     *
     * <p>语义：{@code child == parent} → <b>false</b>（「批准整个危险目录本身」不算进入，
     * 是否放行交给 {@link #isApprovalScopeAllowed} 的「自有根档」判定）；
     * {@code child} 是 {@code parent} 的祖先 → false；只认 {@code parent + '/'} 前缀。
     * Windows 大小写不敏感（与 {@code isDangerousDirectorySegment} 同口径），其余平台敏感。
     *
     * @param child  批准规则的目录根（POSIX 归一）
     * @param parent 触发本次判定的危险根（POSIX 归一）
     * @return true = 严格在内（可穿透本档 ask）
     */
    private static boolean isStrictlyInside(String child, String parent) {
        if (child == null || parent == null) {
            return false;
        }
        String c = stripTrailingSlashes(toPosix(child));
        String p = stripTrailingSlashes(toPosix(parent));
        if (c.isEmpty() || p.isEmpty() || c.equals(p)) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win");
        String cc = windows ? c.toLowerCase(java.util.Locale.ROOT) : c;
        String pp = windows ? p.toLowerCase(java.util.Locale.ROOT) : p;
        return cc.startsWith(pp + "/");
    }

    /** 去尾部 '/' 与 '\\'（{@code "a/b///"} → {@code "a/b"}）。 */
    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '/' || s.charAt(end - 1) == '\\')) {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 批准规则的<b>目录根</b>（POSIX 归一绝对形）· 供 {@link #isStrictlyInside} 与危险根比较。
     *
     * <p>解析口径对齐 {@code RuleQuery.matchesPathRuleRootRelative} 的根锚定分支（⛔ 只覆盖
     * 解析根这一步，不重做 glob 匹配 —— 匹配已由 {@link RuleQuery#getEditRuleByContentsForPath}
     * 完成，本方法只为「批准范围是否已进入危险目录」这一个安全判据服务）：
     * <ul>
     *   <li>剥尾分隔符 + <b>循环</b>剥尾 {@code '/**'} 直到稳定（目录级授权的目录部分）；</li>
     *   <li><b>仍含 glob 元字符（{@code * ? [ ] { }}</b>）→ 截到「第一个元字符前最后一个分隔符」
     *       的字面前缀（保证返回值与元字符无关；元字符落在根段 → null，fail-closed）；</li>
     *   <li>{@code ~/…} 与裸 {@code ~} → {@code user.home}（CC :893-898 家目录根）；</li>
     *   <li>{@code //c/…} → {@code C:/…}（CC :867-887 Windows 盘符根）；{@code //…} 其余 → 文件系统根；</li>
     *   <li>绝对（{@code /…} 或 {@code C:…}）→ 原样；</li>
     *   <li>其余（相对）→ 相对 {@code cwd}（CC :906-916 root=cwd）。</li>
     * </ul>
     *
     * @param ruleContent 命中规则的 ruleContent（非空，调用方已判）
     * @param cwd         root-relative 匹配基准（可为 null → 相对规则无法定位 ⇒ 返回 null，fail-closed）
     * @return POSIX 归一的目录根；无法解析 → null
     */
    private static String ruleDirectoryRoot(String ruleContent, String cwd) {
        String p = ruleContent == null ? "" : ruleContent.trim();
        // ① 循环剥尾部 '/**' <b>直到稳定</b>（本批修复：原先只剥一次 ⇒ '~/.{appName}/**/**'
        //    剥后残留 '**'，靠 isStrictlyInside 的纯字符串前缀判定逃过护栏②，把整个自有根放行）。
        while (true) {
            p = stripTrailingSlashes(p);
            if (!p.endsWith("/**")) {
                break;
            }
            p = p.substring(0, p.length() - 3);
        }
        if (p.isEmpty()) {
            return null;
        }
        // ② 仍含 glob 元字符 ⇒ 截到「第一个元字符之前最后一个分隔符」为止的**字面前缀**
        //    （本批修复：护栏② 必须与 glob 元字符无关）。这一步是<b>缩短</b>（取前缀），
        //    故不会新造成「严格在内」判定（前缀若在危险根内，原串必也在内）—— 只会新造成
        //    「等于危险根」，而相等是否放行由 isApprovalScopeAllowed 的自有根档裁决。
        //    例：'~/.{appName}/**/*' 与 '~/.{appName}/*/**' → 都归一到 '~/.{appName}'
        //    ⇒ 与 '~/.{appName}/**' 同档（用户裁定档）。
        //    元字符落在根段（前面没有分隔符）⇒ 无法定位目录 ⇒ null（fail-closed）。
        int meta = indexOfGlobMeta(p);
        if (meta >= 0) {
            int sep = Math.max(p.lastIndexOf('/', meta), p.lastIndexOf('\\', meta));
            if (sep <= 0) {
                if (log.isInfoEnabled()) {
                    log.info("[WritePermissionChecker] 批准规则目录根无法定位（glob 元字符落在根段）"
                            + " [RULE_ROOT_UNRESOLVED:glob]: ruleContent={}", ruleContent);
                }
                return null;
            }
            p = p.substring(0, sep);
            if (p.isEmpty()) {
                return null;
            }
        }
        // ③ 裸 '~'（如 '~/**' 剥完剩 '~'）→ user.home 根
        if (p.equals("~")) {
            String homeOnly = stripTrailingSlashes(toPosix(System.getProperty("user.home", "")));
            return homeOnly.isEmpty() ? null : homeOnly;
        }
        if (p.startsWith("~/") || p.startsWith("~\\")) {
            String home = stripTrailingSlashes(toPosix(System.getProperty("user.home", "")));
            if (home.isEmpty()) {
                return null;
            }
            return home + "/" + stripLeadingSlashes(toPosix(p.substring(2)));
        }
        if (p.startsWith("//")) {
            String withoutDoubleSlash = p.substring(1);
            if (withoutDoubleSlash.matches("^/[A-Za-z]/.*")) {
                return withoutDoubleSlash.substring(1, 2).toUpperCase(java.util.Locale.ROOT)
                    + ":" + toPosix(withoutDoubleSlash.substring(2));
            }
            return toPosix(withoutDoubleSlash);
        }
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
            return toPosix(p);
        }
        if (cwd == null || cwd.isBlank()) {
            return null;
        }
        return stripTrailingSlashes(toPosix(cwd)) + "/" + stripLeadingSlashes(toPosix(p));
    }

    /**
     * glob 元字符集（{@code * ? [ ] { }}）· 对齐 {@code PathValidation.GLOB_PATTERN_REGEX}
     * （CC {@code pathValidation.ts:25}）的字符类。
     *
     * <p>用途：{@link #ruleDirectoryRoot} 必须返回一个与元字符无关的<b>目录</b>，
     * 否则护栏② 会退化成「纯字符串前缀判定」（本批修复的洞）。Windows 合法文件名理论上可含
     * {@code [ ] { }}，但这里一律视为元字符 ⇒ 该目录只可能被<b>保守拒绝</b>（fail-closed），
     * 不会误放行。</p>
     */
    private static final String GLOB_META_CHARS = "*?[]{}";

    /** 返回首个 glob 元字符的下标；无 → -1。 */
    private static int indexOfGlobMeta(String p) {
        for (int i = 0; i < p.length(); i++) {
            if (GLOB_META_CHARS.indexOf(p.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** 去前导 '/' 与 '\\'。 */
    private static String stripLeadingSlashes(String s) {
        int start = 0;
        while (start < s.length() && (s.charAt(start) == '/' || s.charAt(start) == '\\')) {
            start++;
        }
        return s.substring(start);
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
