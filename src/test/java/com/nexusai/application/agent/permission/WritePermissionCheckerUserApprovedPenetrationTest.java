package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【本批修复 · 1.7 危险目录 ask 排在用户批准规则之前】{@link WritePermissionChecker} 的
 * 顺序缺陷与唯一穿透门。
 *
 * <h2>被钉住的缺陷（用户原话）</h2>
 * <p>「你在弹窗里对某些路径点『始终允许』，不生效 —— 因为『危险目录先问一次』这一步，
 * 排在你批准过的规则之前，结构上根本到不了你的规则。」
 *
 * <h2>结构根因（读码 + 生产日志双证）</h2>
 * <ol>
 *   <li>{@code check()} 顺序：deny → 1.5 → 1.6 → <b>1.7</b> → 2 → 3 → <b>步骤 4（edit allow rule）</b>
 *       → 5 ⇒ 步骤 4 在 1.7 <b>之后</b>。</li>
 *   <li>1.7 第 3 道 {@link WritePermissionChecker} 的 {@code isDangerousFilePathToAutoEdit} 把
 *       <b>段名 == {@link NexusaiPaths#getProjectDirName()}（{@code .nexusai}）</b>一律判为危险目录
 *       ⇒ {@code ~/.{appName}/**} 下非 memory / 非 skill 的路径<b>结构性只走 Ask</b>
 *       ⇒ 步骤 4 永不可达 ⇒ 用户批准过的规则永不生效。</li>
 *   <li>生产日志实测（{@code ~/.nexusai/logs/backend.log}）：{@code "edit allow rule 命中"} 全日志
 *       <b>0 次</b>（步骤 4 从未执行过），而 {@code "危险文件/目录 → ask"} 2634 次。</li>
 * </ol>
 *
 * <h2>修复形态（为什么是「在 1.7 第 3 道内让位」而不是「把步骤 4 整块前移」）</h2>
 * <ul>
 *   <li>约束（a）只让「用户明确批准过」这一支穿透 ⇒ 判据 = {@code alwaysAllowRules[SESSION]}
 *       中带非空 {@code ruleContent} 的 Edit allow 规则（整工具裸规则不算「批准了这条路径」）；</li>
 *   <li>约束（b）不删 1.7：只让<b>第 3 道</b>在「用户批准过该路径」时让位，第 1 道
 *       （可疑 Windows，反绕过护栏）与第 2 道（Claude 配置文件）<b>仍不可穿透</b>；</li>
 *   <li>约束（c）不动 1.6：1.6 先执行且只接受 {@code .claude} / skill scope 前缀 —— 本门处理它
 *       <b>拒收</b>的 session 规则，两条都返回 Allow ⇒ 对 1.6 已放行路径行为不变
 *       （见 {@link #skillScopeRule_stillAllowedVia16_behaviorUnchanged}）。</li>
 * </ul>
 *
 * <h2>本测试的「扰动生效」自证（为什么每条正例都不是恒绿）</h2>
 * <ul>
 *   <li>{@link #memoryPath_withSessionApprovedRule_returnsAllow}（正例）与
 *       {@link #memoryPath_withoutAnyRule_returnsAsk}（反面对照，输入逐字节相同、只少一条规则）
 *       成对 ⇒ 若穿透门被抹掉，正例立刻转红；若 1.7 危险判定被整体拆掉（放宽过头），
 *       反例转红。</li>
 *   <li>反例同时证明<b>正例不是被 1.5 auto-mem carve-out 兜住的假绿</b>：本测试用
 *       {@code new WritePermissionChecker()}（{@code autoMemPaths == null} ⇒
 *       {@code PathValidationEnv.withAutoMem(null)} 原样返回 ⇒ {@code autoMemBaseDir == null}
 *       ⇒ {@code isAutoMemPath} 恒 false ⇒ 1.5 结构性不可能命中）。反例落在 1.7 Ask
 *       即证明该路径<b>确实走到了 1.7</b>。</li>
 * </ul>
 *
 * <h2>本批（2026-09-22）按用户裁定的两处修订</h2>
 * <ol>
 *   <li><b>放宽到整个自有根（照 CC）</b>：上一批护栏② 只认「严格落在危险根之下」，故
 *       {@code ~/.{appName}/**}（= CC 的 {@code ~/.claude/**}）被拒。用户裁定原话：
 *       「CC 里 {@code ~/.claude/**} 能穿透，到我们这 就是 {@code ~/.nexusai} 能穿透。
 *       {@code .claude} 对应就是我们的 {@code .nexusai}」；被问「照 CC 要推翻一条既有护栏，
 *       确认放宽吗」时答「<b>放宽到整个自有根（照 CC）</b>」。CC 真源支持：{@code filesystem.ts:1281-1290}
 *       的 1.6 范围校验接受 {@code '~/.claude/**'} 且先于 1.7 返回 allow。
 *       落点 = {@code WritePermissionChecker#isApprovalScopeAllowed} 的「自有根档」
 *       （规则根 == 危险根 且危险根末段为 {@code .claude} 或 {@code .{appName}}）。</li>
 *   <li><b>堵护栏② 的 glob 元字符洞</b>：{@code ruleDirectoryRoot} 原先只剥一次尾部
 *       {@code '/**'}，且 {@code isStrictlyInside} 是纯字符串前缀判定 ⇒ 双尾通配等写法绕过
 *       护栏②。现改为「循环剥 + 元字符截断到目录边界」。判别器 = (c1)/(c2)。</li>
 * </ol>
 *
 * <h2>[本批 2026-09-23] 按用户裁定收窄相等档：只对「锚形规则」生效</h2>
 * <p><b>用户裁定原话（2026-09-23）</b>：被问「1.7 穿透门的 {@code isSelfConfigRoot} 只取路径末段
 * （CC 的 1.6 是文本前缀锚）—— 照 CC 就该收窄。你选？」⇒ 答「<b>照 CC 收窄（推荐）</b>」。
 * （同批另一问「同一规则下 {@code ~/.{appName}/settings.json} 与可疑 Windows 尾点路径也会被放行，
 * CC 也是，接受吗？」⇒ 答「接受（照 CC，推荐）」⇒ 该行为保持，本批不动。）</p>
 * <p><b>收窄前的洞</b>：相等档只问「<b>危险根末段</b>是不是 {@code .claude} / {@code .{appName}}」，
 * 不看批准规则的锚点 ⇒ 任意位置的<b>同名目录</b>（{@code <home>/Downloads/.{appName}}、
 * {@code D:/scratch/.{appName}}）也获相等档放行（见改写后的 (f)）。</p>
 * <p><b>落点</b> = {@code WritePermissionChecker#isSelfRootAnchorRule}（规则<b>文本</b>的
 * {@code startsWith} 锚前缀判据，四项前缀<b>均含尾分隔符</b>且与 1.6 那四个「文件夹级」前缀
 * 逐字节同源：{@code ~/.{appName}/} / {@code /.{appName}/} / {@code ~/.claude/} / {@code /.claude/}），
 * CC 真源 = {@code filesystem.ts:1281-1290}
 * （{@code ruleContent.startsWith(CLAUDE_FOLDER_PERMISSION_PATTERN.slice(0, -2))}，<b>文本前缀锚</b>，
 * 实参<b>含尾斜杠</b>；且 CC 的 1.7 无穿透门 ⇒ 全绝对路径形在 CC 里恒 Ask）。</p>
 * <p><b>必然代价（照 CC 的固有结果，⛔ 不许静默改断言）</b>：全绝对路径形的「自有根相等档」一并
 * 失效 —— (a2)（{@code //d/…/.{appName}/**}）与 (e2)/(e2b)/(e1) 的规则形态因此改写为
 * <b>锚形但 1.6 拒收</b>的形式（见类 javadoc ⑦ 的说明）。用户诉求（弹窗「编辑自有设置」档产出的
 * {@code ~/.{appName}/**} / {@code /.{appName}/**} ⇒ 自有根下路径仍 Allow）由 1.6 承担，
 * 本批新增 (b1)(b2) 可达性对照钉住。</p>
 *
 * <h2>放宽后的完整边界（哪些写法能穿透 / 不能）</h2>
 * <ul>
 *   <li><b>能穿透</b>（SESSION + Edit + 带 ruleContent + 无 '..' + 匹配该路径 + 护栏② 自有根档）：
 *       规则根<b>恰为</b>自有配置根<b>且规则文本为「锚形」</b>（{@code ~/.{appName}/**},
 *       {@code /.{appName}/**}, {@code ~/.claude/**}, {@code /.claude/**} —— 本批用户 2026-09-23
 *       裁定「照 CC 收窄」，见 {@code WritePermissionChecker#isSelfRootAnchorRule}）或<b>严格在其内</b>
 *       （{@code ~/.{appName}/teams/x/**}，锚形与否无关）；等价 glob 写法（尾部 {@code /**} 重复、
 *       {@code /**} 与 {@code /*} 组合）归一后同档；
 *       <b>[批 2026-09-22 补录 / 2026-09-23 修订]</b> <b>尾斜杠形</b> {@code ~/.{appName}/}（无 glob）
 *       仍穿透（见 (a4)）；而<b>裸目录形</b> {@code ~/.{appName}}（<b>无</b>尾斜杠）本批起
 *       <b>不</b>再穿透（见 (a3)）—— 锚形判据的比较前缀<b>含</b>尾分隔符（照 CC 的
 *       {@code slice(0,-2)} 实参）。</li>
 *   <li><b>不能穿透</b>：①非 SESSION 源；②无 ruleContent 的整工具规则；③含 '..'；
 *       ④比危险根更宽（{@code ~/**}, {@code /**}, {@code <repo>/**}）——
 *       见 {@code WritePermissionCheckerTest#broaderThanDangerousRoot_stillSafetyAsk}；
 *       ⑤等于<b>非</b>自有配置根的危险根（{@code <repo>/.git/**}、{@code <repo>/.vscode/**}、
 *       {@code <repo>/.idea/**}）—— 见 (c2) 对照组；
 *       <b>⑤b [用户 2026-09-23 裁定]</b> <b>非锚形</b>规则文本 —— 规则根虽恰为
 *       自有配置根、但文本不以锚点前缀开头（全绝对路径形 {@code //c/Users/x/.{appName}/**}、
 *       {@code <home>/Downloads/.{appName}/**}）⇒ 护栏② 拒收（见 (f)/(a2)/(d)）；
 *       <b>⑤c [批 2026-09-23 · 锚含尾分隔符]</b> <b>前缀碰撞形</b>
 *       （{@code ~/.{appName}foo/.{appName}/**}、{@code ~/.{appName}-archive/.{appName}/**}、
 *       {@code ~/.claudefoo/.claude/**}）与<b>裸目录形</b> {@code ~/.{appName}}（无尾斜杠）——
 *       文本与锚<b>共享前缀</b>但非以锚（含尾分隔符）开头 ⇒ 同样拒收（见 (c)/(a3)）；
 *       ⑥UNC 危险根；
 *       ⑦1.7 第 1 道（可疑 Windows，classifierApprovable=false）与第 2 道（Claude 配置文件，
 *       {@code settings.json} 等）—— 见 (e1)/(e2)。
 *       ⚠️ <b>[批 2026-09-22 澄清 + 本批修订]</b> ⑦ 只对「<b>能到得了 1.7</b> 的规则」成立：
 *       <b>锚形</b>（{@code ~/.{appName}/**} / {@code /.{appName}/**}）自「发钥匙」批起被 1.6 收下
 *       ⇒ 1.6 先返回 allow，根本走不到 1.7 第 1/2 道（见 (e1b)/(e2b-2)，CC 1.6 同样早于 1.7）。
 *       故 (e1)/(e2)/(e2b) 测试第 1/2 道时改用<b>锚形但 1.6 拒收</b>的规则
 *       （锚点前缀 + 不在末尾收束为「目录通配符」的写法：{@code ~/.{appName}} 后接 {@code **}
 *       再接 {@code /*}、{@code ~/.claude/*}、{@code ~/.{appName}/*} 等，精确字面量见各用例头注 ——
 *       1.6 范围校验要求规则以 {@code /**} 结尾 ⇒ 这些写法被拒收 ⇒ 落 1.7）—— ⛔ 本批前它们
 *       用的是<b>绝对盘符形</b>，而绝对盘符形自本批起在护栏② 就被拒（非锚形），
 *       到不了第 1/2 道的对照条件 ⇒ 那是「反面对照走不到被守护路径」的陷阱，故一并改写。</li>
 * </ul>
 *
 * <h2>⚠️ 产出侧缺口（本批已闭环 —— 上一批登记的「另登记」项）</h2>
 * <p>本测试证明的是「<b>规则已存在时</b>穿透门生效」。上一批登记：弹窗对非 skill 的
 * {@code ~/.{appName}/**} 路径<b>不产出</b> addRules 档（走 {@code generateSuggestions} ⇒
 * {@code SetMode(acceptEdits)} + {@code AddDirectories}）⇒ 生产环境下「用户点第三档」生不出
 * 本门要求的 SESSION Edit 规则。
 * <br><b>[批 2026-09-22「发钥匙」已修]</b> 产出侧补齐 + 消费侧同源：
 * {@code PermissionUpdates#selfConfigRootRuleSuggestion} 产出
 * {@code addRules(Edit, '~/.{appName}/**' | '/.{appName}/**', allow, session)}（照 CC
 * {@code permissionOptions.tsx:105-113} + {@code usePermissionHandler.ts:104-129}），
 * 且 1.6 接受集新增同源的两个前缀（见 {@link WritePermissionChecker} 类 javadoc）。
 * 端到端链条由 {@code SelfConfigRootPermissionKeyEndToEndTest} 覆盖（本类仍只覆盖「规则已存在」）。
 * <br><b>CC 真源取证（读码非推断）</b>：CC 的「始终允许」并非只产
 * {@code SetMode}+{@code AddDirectories} —— {@code FilePermissionDialog} 对
 * {@code ~/.claude} 或 {@code <cwd>/.claude} 内的写操作会<b>替换</b>会话档为
 * 「Yes, and allow Claude to edit its own settings for this session」
 * （{@code permissionOptions.tsx:105-113}），其 handler 产出
 * {@code addRules(Edit, ruleContent='~/.claude/**' 或 '/.claude/**', behavior=allow, session)}
 * （{@code usePermissionHandler.ts:105-128}，pattern 常量见
 * {@code tools/FileEditTool/constants.ts:5,8}），而 CC 的 1.6 正是接受这两个前缀
 * （{@code filesystem.ts:1281-1290}）⇒ <b>CC 的「钥匙」是这条路径级 Edit allow 规则</b>。
 * {@code AddDirectories} 只写 {@code additionalWorkingDirectories}
 * （{@code PermissionUpdate.ts:122-137}），而 {@code checkPathSafetyForAutoEdit(path, pathsToCheck)}
 * 根本不读上下文（{@code filesystem.ts:620-665}）⇒ <b>AddDirectories 不会让 1.7 让路</b>。</p>
 */
@DisplayName("[本批] WritePermissionChecker · 1.7 危险目录 ask 的唯一用户批准穿透门")
class WritePermissionCheckerUserApprovedPenetrationTest {

    // ── [S2 · F-09/F-20] 夹具 DB 姿态显式声明（本夹具不接 DB 回源）──
    //   不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛。
    //   见 SessionProjectRootTestSupport 类 javadoc。
    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /** 动态项目目录名（{@code .nexusai}）—— 从生产同源取，⛔ 不写死字面量。 */
    private static String appDir() {
        return NexusaiPaths.getProjectDirName();
    }

    private static String home() {
        return System.getProperty("user.home");
    }

    /** 项目 slug（对齐 {@code AutoMemPaths.sanitizePath} 的形态：非 ASCII 字母数字 → '-'）。 */
    private static final String SLUG = "D--code-ai-project-nexusai";

    /**
     * 本批目标路径形状 · 对齐生产日志实测的那类路径
     * （{@code C:\Users\WIN\.nexusai\projects\D-----AI-----\memory\MEMORY.md}）：
     * 落在<b>整个配置主根</b>之下 ⇒ 段名命中 {@link NexusaiPaths#getProjectDirName()}
     * ⇒ 1.7 第 3 道判危险目录。
     */
    private static String memoryFilePath() {
        return Paths.get(home(), appDir(), "projects", SLUG, "memory", "MEMORY.md").toString();
    }

    /** 用户对 memory 目录的会话级批准（{@code ~/} 根锚定，与步骤 4 同一 root-relative 口径）。 */
    private static String memoryDirRuleContent() {
        return "~/" + appDir() + "/projects/" + SLUG + "/memory/**";
    }

    /** 13 参工厂：显式 effectiveCwd（工作目录与目标路径刻意互不包含）。 */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        return ToolUseContext.of(UUID.randomUUID(), sessionId, permCtx.mode(),
            List.of(), "", AbortController.NOOP, List.of(), permCtx, permCtx.mode(),
            Map.of(), false, "", effectiveCwd);
    }

    private static PermissionRule rule(
            PermissionRuleSource source, PermissionBehavior behavior, String content) {
        return new PermissionRule(source, behavior, PermissionRuleValue.withContent("Edit", content));
    }

    /** 工作目录（与 memory 路径互不包含；无 8.3 短名/ADS 等可疑模式）。 */
    private static Path cwdDir() {
        return Paths.get("target", "pal-approved-cwd-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 绝对目录规则 · 对齐 {@code WritePermissionCheckerTest#toGlob}（root-relative glob）：
     * 绝对路径加 {@code //} 前缀 → 文件系统根锚定（Windows 盘符形 {@code //c/...}）。
     */
    private static String toGlob(Path dir) {
        String abs = dir.toAbsolutePath().toString();
        if (abs.matches("^[a-zA-Z]:.*")) {
            return "//" + abs.substring(0, 1).toLowerCase() + abs.substring(2).replace('\\', '/') + "/**";
        }
        return "//" + abs.replace('\\', '/') + "/**";
    }

    private static PermissionResult check(
            ToolPermissionContext permCtx, Path effectiveCwd, String filePath) {
        WritePermissionChecker checker = new WritePermissionChecker();
        Tool tool = new ReadFileTool(new PathGuard(Paths.get("target")));
        return checker.check(tool, input(filePath), ctx(permCtx, effectiveCwd));
    }

    // ──────────────────────────────────────────────────────────────────────
    // (a)(b) 主用例 + 反面对照：~/.{appName}/** 非 skill 路径
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) memory 路径 + 会话级用户批准规则 ⇒ Allow（不是 Ask）")
    void memoryPath_withSessionApprovedRule_returnsAllow() {
        String file = memoryFilePath();
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, memoryDirRuleContent()))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), file);

        assertThat(result)
            .as("用户在本会话明确批准过该路径 ⇒ 1.7 危险目录 ask 让位、步骤 4 命中同一规则 → Allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("reason 必须是命中的规则（Rule），证明 Allow 来自步骤 4 而非某个宽松档")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("(b) 同一 memory 路径、没有任何规则 ⇒ 仍 Ask（1.7 保护未被拆掉）")
    void memoryPath_withoutAnyRule_returnsAsk() {
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result)
            .as("没批准过 ⇒ 必须仍然 Ask（同时证明该路径确实走到 1.7，正例不是被 1.5 兜住的假绿）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("必须是 1.7 第 3 道（危险文件/目录）的 SafetyCheck，不是兜底 ask")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    @Test
    @DisplayName("(a2) 只批准了别的前缀（不覆盖本路径）⇒ 仍 Ask")
    void memoryPath_withNonCoveringSessionRule_stillAsks() {
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    "~/" + appDir() + "/projects/" + SLUG + "/tool-results/**"))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result)
            .as("批准范围不覆盖 memory/ ⇒ 穿透门不得命中")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (d) 约束 (a) 护栏：宽松档不得穿透 1.7
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d1) 工作目录内 + acceptEdits 模式、但用户没批准过 ⇒ 仍 Ask（宽松档不穿透）")
    void insideWorkingDirWithAcceptEdits_withoutApproval_stillAsks() {
        // 工作目录本身带 .{appName} 段 ⇒ 目标路径既在「工作目录内」又命中「危险目录」
        Path cwd = Paths.get(home(), appDir(), "teams", "proj-a");
        String file = cwd.resolve("write.json").toString();
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.ACCEPT_EDITS, Map.of(), Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwd, file);

        assertThat(result)
            .as("路径在工作目录内 + acceptEdits ⇒ 步骤 3 本会 Allow，但 1.7 先执行且不可被"
                + "『工作目录内 / acceptEdits』这类宽松档穿透 ⇒ 必须 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("(d2) 非 SESSION 源的同内容规则（userSettings）⇒ 仍 Ask（只认用户会话内明确批准）")
    void sameRuleFromNonSessionSource_stillAsks() {
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.USER_SETTINGS,
                Set.of(rule(PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW,
                    memoryDirRuleContent()))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result)
            .as("穿透门只认 SESSION 桶（用户本次交互明确授予）⇒ 配置文件来源的规则不得穿透 1.7")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("(d3) 整工具 SESSION 规则（无 ruleContent）⇒ 仍 Ask（不构成『批准了这条路径』）")
    void wholeToolSessionRule_stillAsks() {
        PermissionRule wholeTool = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Edit"));
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION, Set.of(wholeTool)),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result)
            .as("无 ruleContent 的整工具规则不得穿透 1.7（否则等于把整个配置主根一次性放开）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("(d4) SESSION 规则内容含 '..' ⇒ 仍 Ask（逃逸护栏）")
    void sessionRuleWithDotDot_stillAsks() {
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    "~/" + appDir() + "/../" + appDir() + "/projects/" + SLUG + "/memory/**"))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result)
            .as("'..' 规则内容必须被穿透门拒绝（对齐 1.6 的同一护栏）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 护栏②：批准范围必须严格落在危险根之下（由集合级基线差分逼出的真实回归）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) 裸自有根通配 ~/.{appName}/**（整个自有根）⇒ Allow"
        + "（依用户 2026-09-22 裁定：照 CC '~/.claude/**' 放宽到整个自有根）")
    void bareConfigHomeWildcard_penetratesByUserRuling_allow() {
        // 本用例由上一批的 (d5) bareConfigHomeWildcard_stillAsks 【改写】而来（旧断言 Ask）。
        // 用户 2026-09-22 裁定原话：「CC 里 `~/.claude/**` 能穿透，到我们这 就是 `~/.nexusai`
        // 能穿透。`.claude` 对应就是我们的 `.nexusai`」；被问「照 CC 要推翻一条既有护栏，确认放宽吗」
        // 时答「放宽到整个自有根（照 CC）」。同批把 WritePermissionCheckerTest 的旧护栏用例
        // nexusaiRootWildcard_stillSafetyAsk 一并按裁定改写（见该用例头注）。
        // 代价（用户已知情）：hooks/ 等自有根下的路径也一并放行 —— 但 settings.json 等
        // 仍被 1.7 第 2 道「Claude 配置文件」拦（见 (e2)，那道不被任何用户规则穿透）。
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    "~/" + appDir() + "/**"))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result)
            .as("自有根档放宽：『批准整个自有配置根』= CC 的 '~/.claude/**' ⇒ 穿透 1.7 第 3 道")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("Allow 来自步骤 4 命中的那条会话规则（Rule），非宽松档")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("(a2) [本批 2026-09-23 收窄] 仓库内自有根的**全绝对路径**规则形"
        + "（{@code //d/…/.{appName}/**}）⇒ 不再获相等档 ⇒ Ask"
        + "（依用户裁定照 CC 收窄：CC 的 1.6 是文本前缀锚，该形态在 CC 里恒 Ask）")
    void repoLocalSelfRootAbsoluteForm_noLongerPenetrates_ask() {
        // ── 本用例由「发钥匙」批的 repoLocalConfigRootWildcard_penetratesByUserRuling_allow【改写】而来 ──
        // 旧断言 Allow（当时判据 = 「危险根末段 = 自有根段且规则根恰为该根」，不看规则文本锚）。
        // ⛔ 不是删除、不是静默改断言：用户 2026-09-23 裁定「照 CC 收窄」推翻了该形态，
        //   故按裁定改写为钉【新行为】。锚形（'/.{appName}/**'）仍 Allow —— 见 (b2) 可达性对照。
        // 代价（用户已知情，属照 CC 的固有结果）：绝对盘符形是本批前**唯一**能到 1.7 的
        //   「自有根相等档」写法（锚形走 1.6），故本形失效后 1.7 相等档只剩「锚形但 1.6 拒收」
        //   的写法可达（见 (e1)/(e2b)/(a3)(a4)）—— 本批「相等档是否已冗余」的实测判据即此。
        Path repo = cwdDir();
        // ⚠️ appDir() 自带前导点（'.nexusai'）——⛔ 不要再加 '.'（否则得到 '..nexusai'：
        //   路径不再是危险根 ⇒ 1.7 不触发 ⇒ 用例会变成「规则没命中危险档」的假绿）。
        String file = repo.toAbsolutePath().resolve(appDir())
            .resolve("teams").resolve("proj-a").resolve("write.json").toString();
        // 前置自证：无任何规则时该路径必须落 1.7 第 3 道 Ask（证明它确实在危险根下）
        PermissionResult noRule = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), repo, file);
        assertThat(noRule)
            .as("前置：<repo>/.{appName}/teams/... 必须命中 1.7 第 3 道（否则本用例无意义）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) noRule).reason())
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);

        String ruleContent = toGlob(repo).replaceFirst("/\\*\\*$", "") + "/" + appDir() + "/**";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleContent))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, repo, file);

        assertThat(result)
            .as("全绝对路径形 '" + ruleContent + "' 非锚形 ⇒ 护栏② 相等档拒收（本批收窄）⇒ 仍 Ask；"
                + "锚形 '/.{appName}/**' 才是本档的正解（见 (b2)）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("仍是 1.7 第 3 道 SafetyCheck（被护栏② 拒收后回落到危险档 ask，不是别的分支）")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (b1)(b2) [本批 2026-09-23] 收窄后的可达性对照：锚形规则仍 Allow
    //    —— 本批的定义性约束（用户诉求）：弹窗「编辑自有设置」档产出的两种锚形规则
    //       （'~/.{appName}/**' 与 '/.{appName}/**'）⇒ 自有根下路径的写请求 ⇒ 必须仍 Allow。
    //       两条都走 1.6（锚形被 1.6 接受集收下，早于 1.7）—— 走 1.6 还是 1.7 都算通过。
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(b1) 锚形 '~/.{appName}/**'（家锚）⇒ Allow（可达性对照，防「收窄误伤用户诉求」）")
    void globalSelfRootAnchorForm_stillAllow_reachabilityControl() {
        // 双证结构（防恒绿）：同输入【无规则】⇒ Ask（证明确实走到 1.7 危险档），
        //   【加锚形规则】⇒ Allow（证明该规则能到达放行出口）。二者只差一条规则。
        String file = memoryFilePath();
        PermissionResult noRule = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(noRule)
            .as("对照：同目标无规则 ⇒ Ask（本用例的 Allow 不是恒绿）")
            .isInstanceOf(PermissionResult.Ask.class);

        String ruleContent = "~/" + appDir() + "/**";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleContent))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), file);

        assertThat(result)
            .as("家锚锚形规则 '" + ruleContent + "' ⇒ 仍 Allow（用户诉求：弹窗专用档产出的正是这一形）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("(b2) 锚形 '/.{appName}/**'（项目锚，cwd 为仓库根）⇒ Allow（可达性对照）")
    void projectSelfRootAnchorForm_stillAllow_reachabilityControl() {
        // 项目锚形 '/' 的根由**规则来源**决定（session 源 ⇒ cwd，RuleQuery.rootPathForSource
        // filesystem.ts:899-905）⇒ cwd 必须取仓库根，目标取其下 .{appName}。
        // 1.6 接受集含 '/.{appName}/' 前缀 ⇒ 该形在 1.6 即返回 Allow（早于 1.7）。
        // ⚠️ 本用例必须用**绝对** cwd：RuleQuery.resolveEffectiveCwd 原样采用入参 cwd（不绝对化），
        //   而 '/…' 单斜杠前缀的根 = 该 cwd ⇒ 相对 cwd 会让 root-relative 匹配落空（实测：
        //   相对 cwd 时本形 Ask）。生产 cwd 是会话项目根（绝对），故这里用 toAbsolutePath() 对齐。
        Path repo = cwdDir().toAbsolutePath();
        String file = repo.resolve(appDir())
            .resolve("teams").resolve("proj-a").resolve("write.json").toString();

        PermissionResult noRule = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), repo, file);
        assertThat(noRule)
            .as("对照：同目标无规则 ⇒ Ask（防恒绿）")
            .isInstanceOf(PermissionResult.Ask.class);

        String ruleContent = "/" + appDir() + "/**";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleContent))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, repo, file);

        assertThat(result)
            .as("项目锚锚形规则 '" + ruleContent + "' ⇒ 仍 Allow（1.6 收下 '/.{appName}/' 前缀）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c) 护栏② 的 glob 元字符洞（本批修复）：等价写法必须与规范写法【同档】
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c1) glob 等价写法 ~/.{appName}/**/** 、/**/* 、/*/** ⇒ 与 (a) 同档（都 Allow）")
    void selfRootGlobEquivalentForms_allow() {
        // 病根（上一批复验点名）：ruleDirectoryRoot 只剥【一次】尾部 '/**'，且 isStrictlyInside 是
        //   纯字符串前缀判定、不拒 glob 元字符 ⇒ '~/.{appName}/**/**' 剥一次后残留 '**'，
        //   仍以 '<根>/' 前缀通过 → 绕过护栏②把整个自有根放行。
        // 本批修复：规则根先【循环】剥 '/**' 直到稳定，再把残留的 glob 元字符截到目录边界 ⇒
        //   三种写法全部归一到 '~/.{appName}'，与规范写法 '~/.{appName}/**' 同档（本轮同档 = Allow，
        //   因为 (a) 已按用户裁定放宽）。注意：修复前它们【也是 Allow】，但走的是「前缀判定漏洞」；
        //   修复后走的是「自有根档」—— 判据从字符串巧合变成显式归一（洞对自有根不再有额外授权面）。
        //   ⛔ 真正会被洞扩大授权面的是【非自有根】：见 (c2)，那里修复前 Allow / 修复后 Ask。
        for (String form : new String[] {
                "~/" + appDir() + "/**/**",
                "~/" + appDir() + "/**/*",
                "~/" + appDir() + "/*/**" }) {
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT,
                Map.of(PermissionRuleSource.SESSION,
                    Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, form))),
                Map.of(), Map.of(), Map.of());

            PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

            assertThat(result)
                .as("等价写法 '" + form + "' 必须与规范写法 '~/.{appName}/**' 同档（Allow），"
                    + "否则护栏② 仍靠字符串巧合而不是显式归一")
                .isInstanceOf(PermissionResult.Allow.class);
            assertThat(((PermissionResult.Allow) result).reason())
                .isInstanceOf(PermissionDecisionReason.Rule.class);
        }
    }

    @Test
    @DisplayName("(c2) 非自有根的等价写法 <repo>/.git/**/** 等 ⇒ 与 <repo>/.git/** 同档（都 Ask）")
    void nonSelfRootGlobEquivalentForms_stillAsk() {
        // 洞的真实授权面在这里：'<repo>/.git/**'（恰为危险根、但【非】自有配置根）修复前后都 Ask；
        //   而 '<repo>/.git/**/**' 修复前被剥成 '<repo>/.git/**' → 以 '<repo>/.git/' 前缀通过
        //   护栏② ⇒ Allow（= 洞）。修复后循环剥 + 元字符截断 ⇒ 归一到 '<repo>/.git' ⇒ 仍 Ask。
        // 本用例是「洞已堵」的判别器（变异实验② 的红点）。
        // ⚠️ 目标必须取 .git 下的【多段】子路径（objects/ab/cdef0123，相对 .git 3 段）：
        //   glob 语义下 '<repo>/.git/**/**' / '<repo>/.git/**/*' / '<repo>/.git/*/**' 都要求
        //   尾段【≥2 段】（实测：对单段目标 config 三种写法全部 nomatch ⇒ 若目标取 config，
        //   修复前的 Allow 根本不会发生，用例会变成「规则没命中」的恒绿 —— 反面对照输入
        //   走不到被守护路径的陷阱）。
        Path repo = cwdDir();
        String file = repo.toAbsolutePath().resolve(".git").resolve("objects")
            .resolve("ab").resolve("cdef0123").toString();
        String[] equivalents = {
            repoGitGlob(repo, "**/**"),
            repoGitGlob(repo, "**/*"),
            repoGitGlob(repo, "*/**")
        };

        // 对照组：规范写法 '<repo>/.git/**' 本身（恰为危险根但非自有配置根）⇒ Ask
        ToolPermissionContext baselineCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    repoGitGlob(repo, "**")))),
            Map.of(), Map.of(), Map.of());
        assertThat(check(baselineCtx, repo, file))
            .as("对照组：'<repo>/.git/**' 是【非】自有配置根的相等档 ⇒ 仍 Ask（本批未放宽这一档）")
            .isInstanceOf(PermissionResult.Ask.class);

        for (String form : equivalents) {
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT,
                Map.of(PermissionRuleSource.SESSION,
                    Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, form))),
                Map.of(), Map.of(), Map.of());

            assertThat(check(permCtx, repo, file))
                .as("等价写法 '" + form + "' 必须与 '<repo>/.git/**' 同档（Ask）—— 不得靠字符串前缀"
                    + "绕过护栏②把 <repo>/.git/ 整根授出")
                .isInstanceOf(PermissionResult.Ask.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (d) 来源护栏：只认 SESSION 桶
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d2b) 同内容规则来自 PROJECT_SETTINGS（仓库可控）⇒ 仍 Ask")
    void sameRuleFromProjectSettings_stillAsks() {
        // WHY：projectSettings 文件位于【被编辑的仓库内】⇒ 恶意仓库可自带 allow 规则。穿透门只认
        //   SESSION 桶（用户本次交互明确授予），故仓库可控来源一律不穿透（含本批放宽的自有根档）。
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.PROJECT_SETTINGS,
                Set.of(rule(PermissionRuleSource.PROJECT_SETTINGS, PermissionBehavior.ALLOW,
                    "~/" + appDir() + "/**"))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result)
            .as("非 SESSION 源（PROJECT_SETTINGS）即便内容 = 自有根通配也不得穿透 1.7")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (e) 1.7 第 1/2 道：任何用户规则都不穿透（含本批放宽的自有根档）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(e1) 1.7 第 1 道（可疑 Windows 模式）优先于第 3 道 ⇒ 自有根规则也不穿透 ⇒ Ask")
    void suspiciousWindowsPattern_beatsPenetrationGate_stillAsks() {
        // WHY：1.7 三道检查顺序固定（CC :630-661），第 1 道 classifierApprovable=false 是反绕过护栏。
        //   目标路径在自有根之下（否则第 1 道也不会被触发到同一目标上），且会话规则是 (a) 已放宽的
        //   自有根通配 ⇒ 若顺序错了（第 3 道先判并让位）就会 Allow。判据 = 仍 Ask 且 classifierApprovable=false。
        // ⚠️ [批 2026-09-22「发钥匙」规则形态改写 + 本批 2026-09-23 再次改写] 原用例的规则内容
        //   = '~/.{appName}/**'（前缀形）。「发钥匙」批让 1.6 收下 '.{appName}/' 前缀后，前缀形
        //   **到不了 1.7**（1.6 先放行，见 (e1b)）⇒ 当时改用**绝对盘符形** '//c/.../.{appName}/**'。
        //   ⚠️ 本批（用户 2026-09-23 裁定照 CC 收窄相等档）后，绝对盘符形在**护栏② 就被拒**
        //   （非锚形）⇒ 它到不了第 1 道的对照条件，本用例会退化成「反正都被拒」的恒绿
        //   （＝反面对照走不到被守护路径的陷阱）⇒ 现改用**锚形但 1.6 拒收**的
        //   '~/.{appName}/**/*'（锚形 ⇒ 过护栏② 相等档；不以 '/**' 结尾 ⇒ 1.6 范围校验拒收
        //   ⇒ 落 1.7）⇒ 「第 1 道优先于第 3 道」这个断言重新变得可证伪。
        String file = Paths.get(home(), appDir(), "teams", "proj-a", "notes.md.").toString(); // 尾点 → 可疑
        String ruleContent = "~/" + appDir() + "/**/*";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleContent))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), file);

        assertThat(result)
            .as("可疑 Windows 模式（尾点）优先于穿透门 ⇒ 即便批准了整个自有根也不得放行"
                + "（规则=" + ruleContent + "，锚形但 1.6 拒收 ⇒ 落 1.7；护栏② 本可放行 ⇒ "
                + "Ask 只可能来自第 1 道）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("必须是第 1 道（classifierApprovable=false），不是第 3 道/兜底")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        assertThat(((PermissionDecisionReason.SafetyCheck) ((PermissionResult.Ask) result).reason())
            .classifierApprovable())
            .as("第 1 道 classifierApprovable=false（不可被分类器/用户规则放行）")
            .isFalse();
    }

    @Test
    @DisplayName("(e1b) [批 2026-09-22「发钥匙」] 1.6 前缀形规则先于 1.7 放行 ⇒ 可疑 Windows 目标也 Allow"
        + "（CC 1.6 本就在 1.7 之前，本批起对 .{appName} 前缀形才真正可达）")
    void selfRootPrefixRule_precedesSafetyVia16_allow() {
        // WHY：这是本批把 '.{appName}/' 前缀加进 1.6 接受集的**必然推论**，必须显式钉住而不是留给读者
        //   去猜 —— CC 真源：filesystem.ts 的 1.6（:1252-1300）在 1.7（:1302）**之前**，且 1.6 命中即
        //   返回 {behavior:'allow'}（:1291-1298），注释原文：「This allows session-level permissions to
        //   bypass the safety blocks for .claude/」（:1253）。即 CC 里一条会话级 '~/.claude/**' 授权
        //   同样会让「可疑 Windows 模式」与「Claude 配置文件」两道检查一并让路。
        //   本仓本批前该现象<b>结构性不可达</b>（1.6 只收 '.claude/' 系前缀）⇒ (e1)/(e2b)① 的 Ask 是
        //   那条不可达性的产物；本批起前缀形可达 1.6 ⇒ 改为按 CC 行为钉住 Allow。
        //   ⚠️ 授权面：本条规则只能由用户点弹窗「编辑配置目录」档产出（SESSION + 带 ruleContent +
        //   需用户点击），不是静默授予。按用户 2026-09-22 裁定「照 CC 放宽到整个自有根」。
        String file = Paths.get(home(), appDir(), "teams", "proj-a", "notes.md.").toString(); // 尾点 → 可疑
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                    "~/" + appDir() + "/**"))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), file);

        assertThat(result)
            .as("1.6 前缀形命中 ⇒ 先于 1.7 返回 Allow（对齐 CC 1.6 :1252-1300 早于 :1302）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("Allow 来自命中的会话规则（1.6 的 Rule），不是宽松档")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("(e2) 1.7 第 2 道（Claude 配置文件）优先于第 3 道 ⇒ Ask"
        + "（对照：(e2b) 同一规则对非配置文件 ⇒ Allow，证明规则确实命中）")
    void claudeConfigFile_beatsPenetrationGate_stillAsks() {
        // WHY：这是本批放宽【代价】的边界 —— 自有根下 hooks/ 等一并放行，但 settings.json /
        //   settings.local.json 类配置文件仍由第 2 道拦（先于第 3 道，且第 2 道不可被任何用户规则穿透）。
        // ⚠️ [本批 2026-09-23 规则形态改写] 规则用 '~/.claude/*'（锚形但**不以 '/**' 结尾**）：
        //   · 不能再用 '~/.claude/**'（1.6 直接放行 ⇒ 到不了第 2 道，测不出第 2 道）；
        //   · 不能再用盘符绝对形 '//c/.../.claude/**'（本批起护栏② 判非锚形即拒 ⇒ 同样到不了
        //     第 2 道的对照条件，且会让「同规则对非配置文件 ⇒ Allow」的对照恒红）；
        //   · '~/.claude/*' 三条件同时成立：锚形（过护栏② 相等档）+ 规则根归一到 <home>/.claude
        //     （== 危险根）+ 1.6 范围校验拒收（不以 '/**' 结尾）⇒ 落 1.7，第 2 道先于第 3 道可测。
        String ruleContent = "~/.claude/*";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleContent))),
            Map.of(), Map.of(), Map.of());

        PermissionResult configFile = check(permCtx, cwdDir(),
            Paths.get(home(), ".claude", "settings.json").toString());
        assertThat(configFile)
            .as("1.7 第 2 道（Claude 配置文件）优先于第 3 道穿透门 ⇒ 规则再宽也不得放行")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) configFile).reason())
            .as("必须是第 2 道 SafetyCheck（classifierApprovable=true）")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);

        // 可达性对照：同规则、同 .claude 根下但【非】配置文件 ⇒ 第 2 道不命中 → 第 3 道：批准范围
        //   = 危险根本身且规则文本锚形 ⇒ 让位 → 步骤 4 命中 ⇒ Allow。证明规则/路径确实匹配，
        //   故上面的 Ask 是第 2 道顺序护栏的结果，不是「规则没命中」的假绿。
        PermissionResult plainFile = check(permCtx, cwdDir(),
            Paths.get(home(), ".claude", "notes-" + UUID.randomUUID().toString().substring(0, 6) + ".md").toString());
        assertThat(plainFile)
            .as("可达性对照：同规则对 .claude 下非配置文件 ⇒ Allow（规则命中，且相等档对锚形规则仍放开）")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("(e2b) 放宽到自有根后：~/.{appName}/settings.json 仍 Ask（第 2 道），"
        + "而 ~/.{appName}/hooks/*.sh 一并放行（用户已知情的代价）")
    void selfRootRelaxationCost_andSettingsStillGuarded() {
        // WHY：本批放宽的【代价边界】必须钉住 —— 用户裁定「放宽到整个自有根」，其代价是 hooks/ 等
        //   自有根下非配置路径一并放行；但 settings.json / settings.local.json 仍由 1.7 第 2 道拦
        //   （WritePermissionChecker#isClaudeConfigFilePath 的两条 endsWith 含 {appName} 版：
        //    '<sep>.{appName}<sep>settings.json' / 'settings.local.json'，见该类 :992-995）。
        //   本用例同时是「放宽没有把自有配置面整块交出去」的判别器。
        // ⚠️ [本批 2026-09-23 规则形态改写] 两条断言各用一条规则（原为同一条绝对盘符形）：
        //   ① '~/.{appName}/*'（锚形但**不以 '/**' 结尾**）⇒ 过护栏② 相等档 + 1.6 拒收 ⇒ 落 1.7
        //      ⇒ settings.json 命中第 2 道（该规则若不含第 2 道本会 Allow ⇒ Ask 只可能来自第 2 道）；
        //   ② '~/.{appName}/**/*'（锚形但 1.6 拒收，规则根归一到 <home>/.{appName}）⇒ 过护栏② 相等档
        //      ⇒ hooks/ 下脚本 Allow。
        //   ⛔ 不能再用盘符绝对形：本批起它非锚形 ⇒ 护栏② 即拒 ⇒ ① 的 Ask 不再是第 2 道（变恒绿），
        //      ② 也会从 Allow 变 Ask（对照恒红）——那正是「反面对照走不到被守护路径」的陷阱。
        String settingsRuleContent = "~/" + appDir() + "/*";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, settingsRuleContent))),
            Map.of(), Map.of(), Map.of());

        // ① 自有根下的 settings.json ⇒ 第 2 道 Ask（不可被任何用户规则穿透）
        PermissionResult settingsFile = check(permCtx, cwdDir(),
            Paths.get(home(), appDir(), "settings.json").toString());
        assertThat(settingsFile)
            .as("自有根的 settings.json 是 1.7 第 2 道「Claude 配置文件」（含 .{appName} carve-out）⇒ 仍 Ask"
                + "（规则=" + settingsRuleContent + "，锚形 ⇒ 过护栏②；不以 '/**' 结尾 ⇒ 1.6 拒收 ⇒ 落 1.7）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) settingsFile).reason())
            .as("必须是第 2 道 SafetyCheck（classifierApprovable=true），不是第 3 道/兜底")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);

        // ② 自有根下的 hooks/*.sh ⇒ 非配置文件 ⇒ 第 3 道被自有根档穿透 ⇒ Allow（= 用户已知情接受的代价）
        String hooksRuleContent = "~/" + appDir() + "/**/*";
        ToolPermissionContext hooksCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, hooksRuleContent))),
            Map.of(), Map.of(), Map.of());
        PermissionResult hooksFile = check(hooksCtx, cwdDir(),
            Paths.get(home(), appDir(), "hooks", "post-" + UUID.randomUUID().toString().substring(0, 6) + ".sh")
                .toString());
        assertThat(hooksFile)
            .as("锚形规则 '" + hooksRuleContent + "' 对 hooks/ 下脚本 ⇒ Allow"
                + "（自有根相等档对锚形规则仍放开 ⇒ 非配置面一并放行，用户已知情）")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("(e2b-2) [批 2026-09-22「发钥匙」] 1.6 前缀形 '~/.{appName}/**' ⇒ settings.json 一并 Allow"
        + "（CC 同构：1.6 早于 1.7；本批前结构性不可达）")
    void selfRootPrefixRule_alsoAllowsSettingsFile_via16() {
        // WHY 必须显式钉住（⛔ 不能只改 (e2b)/(e1) 的规则形态把这件事藏起来）：
        //   把 '.{appName}/' 加进 1.6 接受集 ⇒ 一条 '~/.{appName}/**' 会话规则对**整个自有根**
        //   （含 settings.json / settings.local.json）在 1.6 即返回 Allow，1.7 第 2 道不再有机会判。
        //   CC 行为同构（读码取证）：CC 1.6 接受 '~/.claude/' 前缀（:1284-1287）并在 :1291-1298 直接
        //   return allow，而 1.7（:1302）在其后 ⇒ CC 里同一档位（弹窗文案就是「allow Claude to edit
        //   its own settings for this session」）同样放行 '~/.claude/settings.json'。这是该档位的
        //   **设计意图**（「编辑自己的设置」），不是缺陷；代价 = 该 SESSION 内自有根配置面敞开。
        //   ⚠️ 触发条件（给用户看的）：用户在某次弹窗里点了「编辑配置目录」档；规则 destination=session
        //   ⇒ 不落盘、只活到本会话结束。
        String ruleContent = "~/" + appDir() + "/**";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleContent))),
            Map.of(), Map.of(), Map.of());

        PermissionResult settingsFile = check(permCtx, cwdDir(),
            Paths.get(home(), appDir(), "settings.json").toString());
        assertThat(settingsFile)
            .as("前缀形规则命中 1.6 ⇒ 先于 1.7 第 2 道返回 Allow（对齐 CC 1.6 早于 1.7）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) settingsFile).reason())
            .as("Allow 来自 1.6 命中的那条会话规则")
            .isInstanceOf(PermissionDecisionReason.Rule.class);

        // 可达性对照（防假绿）：同规则对自有根下**非**配置文件 ⇒ 同样 Allow（证明规则确实匹配）
        PermissionResult hooksFile = check(permCtx, cwdDir(),
            Paths.get(home(), appDir(), "hooks", "post-" + UUID.randomUUID().toString().substring(0, 6) + ".sh")
                .toString());
        assertThat(hooksFile)
            .as("对照：同规则对 hooks/ 脚本 ⇒ Allow")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("(a3) [批 2026-09-23 · 锚含尾分隔符] 裸目录形 '~/.{appName}'（**无**尾斜杠）⇒ Ask"
        + "（依用户 2026-09-23 照 CC 收窄：CC 的比较实参含尾分隔符，裸目录形通不过）")
    void selfRootBareDirForm_nowAsks_ccAligned() {
        // ── 本用例由「发钥匙」批 selfRootBareDirAndTrailingSlashForms_alsoPenetrate 的
        //    【裸目录形那一半】改写而来（旧断言 Allow）。⛔ 不是删除、不是静默改断言：这是
        //    用户 2026-09-23 裁定「照 CC 收窄」的**收尾** —— 上一批的锚去掉了尾分隔符
        //    （'~/.{appName}'），而 CC 的比较实参 ruleContent.startsWith(
        //    CLAUDE_FOLDER_PERMISSION_PATTERN.slice(0, -2)) **含尾斜杠**
        //    （'/.claude/**' 剔末两字符 ⇒ '/.claude/'；filesystem.ts:1284 +
        //    tools/FileEditTool/constants.ts:5）⇒ 裸目录形在 CC 里通不过范围校验，
        //    且 CC 的 1.7 无穿透门（:1302-1308 三道检查后直接 ask）⇒ 该形在 CC 里恒 Ask。
        //    本批起与 CC 同构（拒收，落 1.7 Ask）。
        // 【可达性对照（防「规则没命中」的假绿）】同一目标、只把规则文本换成**尾斜杠形**
        //    '~/.{appName}/' ⇒ Allow（见 (a4)）：二者经 ruleDirectoryRoot 归一到**同一目录根**
        //    （都剥尾分隔符）、只差规则文本的尾斜杠 ⇒ 下一条的 Ask 只可能来自本批的
        //    「锚含尾分隔符」判据，而不是「规则没命中」。
        String file = memoryFilePath();

        String trailingSlashForm = "~/" + appDir() + "/";
        PermissionResult slashAllowed = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, trailingSlashForm))),
            Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(slashAllowed)
            .as("成对对照：同目标 + 尾斜杠形 '" + trailingSlashForm + "' ⇒ Allow（规则在匹配层确实命中、"
                + "相等档对锚形可达）⇒ 下面的 Ask 不是「规则没命中」")
            .isInstanceOf(PermissionResult.Allow.class);

        String bareDirForm = "~/" + appDir();
        PermissionResult bareAsked = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, bareDirForm))),
            Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(bareAsked)
            .as("裸目录形 '" + bareDirForm + "'（无尾斜杠）非锚形（锚含尾分隔符）⇒ 护栏② 相等档拒收 ⇒ Ask"
                + "（照 CC：CC 实参含尾斜杠 ⇒ 裸目录形通不过）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) bareAsked).reason())
            .as("仍是 1.7 第 3 道 SafetyCheck（护栏② 拒收后回落到危险档 ask）")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    @Test
    @DisplayName("(a4) 尾斜杠形 '~/.{appName}/'（无 glob）⇒ 仍 Allow"
        + "（startsWith 含尾分隔符锚的必然推论；1.6 拒收〔非 '/**' 结尾〕⇒ 走 1.7 相等档）")
    void selfRootTrailingSlashForm_stillAllows() {
        // WHY 它仍 Allow 而 (a3) 不 Allow：本批的锚**含**尾分隔符（'~/.{appName}/'）。
        //   '~/.{appName}/' 以该锚开头 ⇒ startsWith 成立 ⇒ 锚形；(a3) 的 '~/.{appName}' 缺尾斜杠
        //   ⇒ 不以含尾斜杠的锚开头 ⇒ 非锚形。这是「照 CC 收窄」的**必然推论**，不是本仓新判据。
        //   该形经 ruleDirectoryRoot 剥尾分隔符后与危险根 isSameDir 相等 ⇒ 走「自有根档」。
        //   它也是 1.6 拒收的写法（不以 '/**' 结尾）⇒ 本用例同时钉住「1.7 相等档在收窄后仍活着」。
        String file = memoryFilePath();

        PermissionResult noRule = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(noRule)
            .as("对照：同目标无任何规则 ⇒ Ask（本用例的 Allow 不是恒绿）")
            .isInstanceOf(PermissionResult.Ask.class);

        String trailingSlashForm = "~/" + appDir() + "/";
        PermissionResult result = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, trailingSlashForm))),
            Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(result)
            .as("尾斜杠形 '" + trailingSlashForm + "' 以含尾分隔符的锚开头 ⇒ 锚形 ⇒ 自有根相等档 ⇒ Allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c)/(d) [批 2026-09-23 · 本批要闭合的面] 前缀碰撞反例 + 可达性对照
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) [批 2026-09-23 · 本批要闭合的面] 前缀碰撞形 ⇒ Ask"
        + "（规则文本与锚共享前缀，但**非**以锚<含尾分隔符>开头 —— 上一批锚去尾分隔符时曾误 Allow）")
    void prefixCollisionForms_nowAsk_ccAligned() {
        // 上一批（锚去尾分隔符）的残留洞：锚 = '~/.{appName}' ⇒
        //   '~/.{appName}foo/.{appName}/**' 文本上仍 startsWith('~/.{appName}') ⇒
        //   **非**自有根位置的同名目录也获相等档 = 「同名目录 elsewhere」病的**嵌套残留变体**
        //   （(f) 钉的是非嵌套形：'<home>/Downloads/.{appName}/**'）。
        // 本批把锚改回含尾分隔符（逐字节 = CC 的 slice(0,-2) 实参）⇒ 碰撞形文本不再以锚开头
        //   ⇒ 护栏② 相等档拒收 ⇒ 落 1.7 第 3 道 Ask（照 CC）。
        // ⚠️ 可达性说明（⛔ 不是「规则没命中」的假绿）：本组每一形的规则根都归一到
        //   '<home>/…/<碰撞段>/.{appName}' == 危险根（其末段命中自有根段 ⇒ isSelfConfigRoot 为 true）
        //   ⇒ 唯一挡住它的就是锚前缀判据；其「规则确实命中」由**变异实验**证伪性自证 ——
        //   把锚改回去尾分隔符后本用例转红（变 Allow），而两次运行只差锚常量。
        record Case(String label, String ruleContent, String file) { }
        String collidedDir = appDir() + "foo";
        String archivedDir = appDir() + "-archive";
        List<Case> cases = List.of(
            new Case("① 同段内共享前缀 + 额外字符（'~/.{appName}foo/…'）",
                "~/" + collidedDir + "/" + appDir() + "/**",
                Paths.get(home(), collidedDir, appDir(),
                    "notes-" + UUID.randomUUID().toString().substring(0, 6) + ".md").toString()),
            new Case("② 同段内共享前缀 + '-' 后缀（'~/.{appName}-archive/…'）",
                "~/" + archivedDir + "/" + appDir() + "/**",
                Paths.get(home(), archivedDir, appDir(),
                    "notes-" + UUID.randomUUID().toString().substring(0, 6) + ".md").toString()),
            new Case("③ `.claude` 系碰撞（'~/.claudefoo/.claude/**'）",
                "~/.claudefoo/.claude/**",
                Paths.get(home(), ".claudefoo", ".claude",
                    "notes-" + UUID.randomUUID().toString().substring(0, 6) + ".md").toString()));
        for (Case c : cases) {
            PermissionResult noRule = check(ToolPermissionContext.of(
                PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), cwdDir(), c.file());
            assertThat(noRule)
                .as(c.label() + "：前置（无规则）'" + c.file() + "' ⇒ Ask（证明该路径确在危险根下）")
                .isInstanceOf(PermissionResult.Ask.class);

            PermissionResult result = check(ToolPermissionContext.of(
                PermissionMode.DEFAULT,
                Map.of(PermissionRuleSource.SESSION,
                    Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, c.ruleContent()))),
                Map.of(), Map.of(), Map.of()), cwdDir(), c.file());
            assertThat(result)
                .as(c.label() + "：碰撞规则 '" + c.ruleContent() + "' 非锚形 ⇒ 护栏② 相等档拒收 ⇒ Ask"
                    + "（上一批锚去尾分隔符时此形曾 Allow —— 本批要闭合的面）")
                .isInstanceOf(PermissionResult.Ask.class);
            assertThat(((PermissionResult.Ask) result).reason())
                .as(c.label() + "：仍是 1.7 第 3 道 SafetyCheck（护栏② 拒收后回落到危险档 ask）")
                .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
        }
    }

    @Test
    @DisplayName("(d) 可达性对照（防恒绿）：同目标【无规则】⇒ Ask；锚形 '~/.{appName}/**' ⇒ Allow（1.6 路线）；"
        + "锚形但 1.6 拒收的 '~/.{appName}/' ⇒ Allow（1.7 相等档仍活着）")
    void reachabilityControl_noRuleAsks_bothAnchorRoutesAllow() {
        // WHY：本批把锚收窄成「含尾分隔符」后，(a3) 与 (c) 由 Allow 转 Ask。必须同时钉住
        //   **两条放行路线都还活着**，否则「Ask」可能被读成「门死了/规则不匹配」的假绿。
        //   路线① 1.6：锚形 '~/.{appName}/**'（以 '/**' 结尾 ⇒ 被 1.6 接受集收下，早于 1.7）；
        //   路线② 1.7 相等档：'~/.{appName}/'（锚形但 1.6 拒收〔非 '/**' 结尾〕⇒ 落 1.7 ⇒ 相等档）。
        //   二者都 Allow ⇒ (a3)/(c) 的 Ask 是**判据结果**而非「结构不可达」。
        String file = memoryFilePath();

        PermissionResult noRule = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(noRule)
            .as("对照：同目标无任何规则 ⇒ Ask（两条 Allow 都不是恒绿）")
            .isInstanceOf(PermissionResult.Ask.class);

        String via16 = "~/" + appDir() + "/**";
        PermissionResult route16 = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, via16))),
            Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(route16)
            .as("路线①（用户诉求）：锚形 '" + via16 + "' ⇒ Allow（1.6 接受集收下，早于 1.7）")
            .isInstanceOf(PermissionResult.Allow.class);

        String via17 = "~/" + appDir() + "/";
        PermissionResult route17 = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, via17))),
            Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(route17)
            .as("路线②（相等档仍活着）：锚形但 1.6 拒收的 '" + via17 + "' ⇒ Allow（1.7 相等档）"
                + "⇒ (a3)/(c) 的 Ask 是判据结果，不是「门死了」")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) route17).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("(e3) 整工具 SESSION 规则（无 ruleContent）即便在自有根档也不穿透 ⇒ 仍 Ask")
    void wholeToolSessionRule_selfRootDefaultMode_stillAsks() {
        // 与 (d3) 同判据的补充：本批放宽的是【范围判据（护栏②）】，不是【规则形态判据】——
        //   无 ruleContent 的整工具规则仍不构成「批准了这条路径」。
        PermissionRule wholeTool = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Edit"));
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION, Set.of(wholeTool)),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), memoryFilePath());

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
    }

    /** '{@code <repo>/.git/<suffix>}'（POSIX + 盘符根锚定形，与 {@link #toGlob} 同风格）。 */
    private static String repoGitGlob(Path repo, String suffix) {
        return toGlob(repo).replaceFirst("/\\*\\*$", "") + "/.git/" + suffix;
    }

    @Test
    @DisplayName("(d6) 仓库级通配 <repo>/** 不得放开 <repo>/.git/config ⇒ 仍 Ask")
    void repoWildcard_doesNotUnlockGitDir_stillAsks() {
        Path cwd = cwdDir();
        String file = cwd.resolve(".git").resolve("config").toAbsolutePath().toString();
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, toGlob(cwd)))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwd, file);

        assertThat(result)
            .as("批准范围（<repo>）在危险根（<repo>/.git）之外 ⇒ 护栏②拒绝穿透 ⇒ 仍 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("(d6 可达性对照) 同一 <repo>/** 规则对工作目录内普通文件 ⇒ Allow（证明规则在匹配层是命中的）")
    void repoWildcard_reachabilityControl_plainFile_allowed() {
        Path cwd = cwdDir();
        String plain = cwd.resolve("notes.md").toAbsolutePath().toString();
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, toGlob(cwd)))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwd, plain);

        assertThat(result)
            .as("普通路径（不命中 1.7 危险档）走步骤 4 ⇒ Allow。本用例是 (d6) 的可达性对照：证明"
                + "该规则/该 cwd 形态确实能被 RuleQuery 匹配到，(d6) 的 Ask 不是『规则压根没命中』的假绿")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c) 1.6（skill scope）行为不变
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) skill 路径 + 会话规则 ⇒ 仍 Allow（1.6 先命中，skill scope 前缀集未动）")
    void skillScopeRule_stillAllowedVia16_behaviorUnchanged() {
        String skillFile = Paths.get(home(), appDir(), "skills", "tbox-generator", "scripts", "cluster.py")
            .toString();
        String skillRule = "~/" + appDir() + "/skills/tbox-generator/**";
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, skillRule))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), skillFile);

        assertThat(result)
            .as("1.6 的 skill scope 接受集含 '~/.{appName}/skills/' ⇒ 仍 Allow"
                + "（本批只**新增**自有根两个前缀，skill scope 前缀集与护栏均未动）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (f) [本批 2026-09-23 收窄] 末段同名的**非**自有根目录不再获相等档
    //     —— 用户裁定「照 CC 收窄」的判别器
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(f) 末段同名的**非**自有根目录（<home>/Downloads/.{appName}）全绝对路径规则 ⇒ Ask"
        + "（依用户 2026-09-23 裁定照 CC 收窄：CC 的 1.6 是文本前缀锚，该形态在 CC 里恒 Ask）")
    void sameNameDir_elsewhereIsNoLongerSelfRoot_ask() {
        // ── 本用例由「发钥匙」批的 sameNameDir_elsewhereIsAlsoTreatedAsSelfRoot_allow【改写】而来 ──
        // 旧断言 Allow（旧判据：危险根末段命中自有根段即放行，不看锚点）。
        // ⛔ 不是删除、不是静默改断言：用户 2026-09-23 裁定「照 CC 收窄」推翻了该行为，故改写为钉【新行为】。
        //
        // 【旧洞（收窄前）】isSelfConfigRoot 只比**末段**（.claude / .{appName}），不看锚点 ⇒
        //   任意位置的同名目录都获「自有根档」放宽（"任意位置的同名目录也算我的配置根"）。
        // 【本批判据】WritePermissionChecker#isSelfRootAnchorRule —— 规则**文本**必须以锚点前缀
        //   （'~/.{appName}' / '/.{appName}' / '~/.claude' / '/.claude'）开头。
        // 【CC 真源（读码取证）】CC 的 1.6 范围校验是**锚定的文本前缀**（ruleContent 必须以
        //   '~/.claude' 或 '/.claude' 开头，filesystem.ts:1284-1287）⇒ CC 里写全绝对路径的
        //   '<home>/Downloads/.claude/**' **不**通过范围校验；且 CC 的 1.7 无穿透门
        //   （filesystem.ts:1302-1308 三道检查后直接 ask）⇒ 该形态在 CC 里恒 Ask。本批起与 CC 同构。
        Path dir = Paths.get(home(), "Downloads", appDir());
        String file = dir.resolve("notes-" + UUID.randomUUID().toString().substring(0, 6) + ".md").toString();
        String ruleContent = toGlob(dir);
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleContent))),
            Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), file);

        assertThat(result)
            .as("危险根 '" + dir + "' 的末段虽命中自有根段，但规则文本 '" + ruleContent + "' 非锚形"
                + " ⇒ 护栏② 相等档拒收 ⇒ Ask（照 CC）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("仍是 1.7 第 3 道 SafetyCheck（护栏② 拒收后回落到危险档 ask）")
            .isInstanceOf(PermissionDecisionReason.SafetyCheck.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c) [本批 2026-09-23] 严格在内档行为不变（收窄只动「相等档」，不动严格在内档）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) 严格在内档不变：'~/.{appName}/teams/x/**'（锚形）与 `<home>/.{appName}/teams/x/**`"
        + "（全绝对路径形）⇒ 都仍 Allow（本批只收窄相等档）")
    void strictlyInsideTier_unchanged_allow() {
        // WHY 必须钉住：本批收窄的是「相等档（规则根 == 危险根）」，而「严格在内档
        //   （isStrictlyInside，规则根在危险根之下）」**不受锚形判据约束** —— 锚形与否都能穿透。
        //   两条通道都必须仍是 Allow，否则就是把收窄做过了头（把「批准了自有根的子目录」也拦掉）。
        // 通道①：锚形（'~/.{appName}/teams/proj-a/**' → 1.6 先命中；1.6 接受 '~/.{appName}/' 前缀）
        // 通道②：全绝对路径形（1.6 拒收 ⇒ 到 1.7 ⇒ 严格在内档放行 —— 证明该档**未**被锚形判据收窄）
        String file = Paths.get(home(), appDir(), "teams", "proj-a", "write.json").toString();

        PermissionResult noRule = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(noRule)
            .as("前置/对照：同目标无规则 ⇒ Ask")
            .isInstanceOf(PermissionResult.Ask.class);

        String anchorRule = "~/" + appDir() + "/teams/proj-a/**";
        ToolPermissionContext anchorCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, anchorRule))),
            Map.of(), Map.of(), Map.of());
        assertThat(check(anchorCtx, cwdDir(), file))
            .as("通道①锚形严格在内 '" + anchorRule + "' ⇒ Allow（1.6 先命中；行为不变）")
            .isInstanceOf(PermissionResult.Allow.class);

        String absoluteRule = toGlob(Paths.get(home(), appDir(), "teams", "proj-a"));
        ToolPermissionContext absoluteCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, absoluteRule))),
            Map.of(), Map.of(), Map.of());
        PermissionResult absoluteResult = check(absoluteCtx, cwdDir(), file);
        assertThat(absoluteResult)
            .as("通道②全绝对路径形 '" + absoluteRule + "'（1.6 拒收）⇒ 1.7 严格在内档 ⇒ 仍 Allow；"
                + "本批的锚形判据**只**作用于相等档，不得收紧严格在内档")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) absoluteResult).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (d) [本批 2026-09-23] `.claude` 系锚的行为明确化
    //     —— 保留依据：1.6 也接受 '.claude' 前缀（且 skillScopeRoots 仍产出 '…/.claude/skills/…'）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d) `.claude` 系：锚形 '~/.claude/**' ⇒ Allow（1.6）；全绝对路径形 ⇒ Ask（本批收窄同样适用）")
    void claudeFolderFamily_anchorAllows_absoluteAsks() {
        // WHY（第 1 项「是否保留 .claude 系的相等档」的判定依据）：保留 —— ①1.6 接受集本就含
        //   '/.claude/' 与 '~/.claude/'（与 '~/.{appName}/' 并存，见 WritePermissionChecker 类 javadoc
        //   的 1.6 接受集），把 .claude 系排除在锚集外会让「同一形态在 1.6 放行、在 1.7 相等档却被拒」
        //   = 新的同源漂移；②skillScopeRoots 仍产出 '…/.claude/skills/{name}/**'（只读兼容）。
        //   ⛔ 本批对 .claude 系既不放宽也不额外收窄：它跟着同一套锚前缀判据走。
        String file = Paths.get(home(), ".claude", "notes-" + UUID.randomUUID().toString().substring(0, 6) + ".md")
            .toString();

        PermissionResult noRule = check(ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of()), cwdDir(), file);
        assertThat(noRule).as("对照：无规则 ⇒ Ask").isInstanceOf(PermissionResult.Ask.class);

        // ① 锚形 '~/.claude/**' ⇒ 1.6 收下 '~/.claude/' 前缀 ⇒ Allow（CC 同构：filesystem.ts:1284-1287）
        String anchorRule = "~/.claude/**";
        ToolPermissionContext anchorCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, anchorRule))),
            Map.of(), Map.of(), Map.of());
        assertThat(check(anchorCtx, cwdDir(), file))
            .as("锚形 '" + anchorRule + "' ⇒ Allow（1.6；与 '.claude' 系消费侧同源）")
            .isInstanceOf(PermissionResult.Allow.class);

        // ② 全绝对路径形 '//c/…/.claude/**' ⇒ 非锚形 ⇒ 护栏② 拒收 ⇒ Ask（与 .{appName} 系同判据）
        String absoluteRule = toGlob(Paths.get(home(), ".claude"));
        ToolPermissionContext absoluteCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(PermissionRuleSource.SESSION,
                Set.of(rule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, absoluteRule))),
            Map.of(), Map.of(), Map.of());
        assertThat(check(absoluteCtx, cwdDir(), file))
            .as("全绝对路径形 '" + absoluteRule + "' 非锚形 ⇒ Ask（本批收窄对 .claude 系同样生效）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("(c2) skill 路径 + 无任何规则 ⇒ 仍 Ask（1.7 对 skill 路径的保护未变）")
    void skillPath_withoutAnyRule_stillAsks() {
        String skillFile = Paths.get(home(), appDir(), "skills", "tbox-generator", "scripts", "cluster.py")
            .toString();
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());

        PermissionResult result = check(permCtx, cwdDir(), skillFile);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
    }
}
