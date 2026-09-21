package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.WriteFileTool;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auto-memory carve-out 的<b>路径前缀边界</b>（读侧 + 写侧）· 对齐 CC {@code isProjectDirPath}
 * （filesystem.ts:284-291）的 {@code === dir || startsWith(dir + sep)} 形态。
 *
 * <h2>病根（CC 真源对照）</h2>
 * <p>CC {@code isAutoMemPath}（memdir/paths.ts:258-262）是<b>裸</b> {@code startsWith(getAutoMemPath())}，
 * 它安全<b>只因为</b> {@code getAutoMemPath()} 带<b>尾分隔符</b>（{@code validateMemoryPath} 返回
 * {@code (normalized + sep).normalize('NFC')}，paths.ts:149；per-project 分支 {@code (… + sep).normalize('NFC')}，
 * paths.ts:230-232）—— 这是<b>契约式</b>防御，契约的承重面在<b>生产者</b>。
 *
 * <p>本仓把该契约在<b>生产者侧打断</b>了：{@code PathValidationEnv.withAutoMem} 用
 * {@code Path.of(base).normalize().toString()} 归一化，而 Java {@code Path#normalize()} <b>会吃掉尾分隔符</b>
 * ⇒ {@code autoMemBaseDir} 变成 {@code <…>/memory}（无尾分隔符）⇒ 消费者 {@code PathValidation.isAutoMemPath}
 * 的裸 {@code startsWith(baseNorm)} 退化成<b>裸前缀匹配</b>：
 * {@code <…>/memory-evil/x.md} 也 startsWith({@code <…>/memory}) ⇒ 被 carve-out 静默放行。
 *
 * <h2>WHY（规则九 · 测试验证意图，不是只验证行为）</h2>
 * <p>carve-out 的语义是「auto-memory 目录<b>及其子树</b>内的文件免交互」，⛔ 不是「名字以
 * {@code …/memory} 开头的任意目录」。前缀攻击放行的是<b>攻击者选定的相邻目录</b>（{@code memory-evil}
 * 只是示例；任何 {@code <base>*} 形态都可命中）——在写侧意味着模型可无弹窗写该目录，在读侧意味着
 * 可静默读该目录。本测试钉住「边界必须是<b>路径段</b>边界，不是<b>字符串</b>边界」这个意图。
 *
 * <h2>为什么两种输入形态都要正确（本测试的形态矩阵）</h2>
 * <p>任务要求判定「既能吃住 base 带尾分隔符，也能吃住 base 已被 normalize 掉尾分隔符」。因此每条
 * 断言都在<b>两种 env 形态</b>上跑：
 * <ol>
 *   <li>{@code BASE_WITH_SEP} = {@code <…>/memory/}（CC 契约形态，生产者已修后 {@code withAutoMem} 的产出）；</li>
 *   <li>{@code BASE_NO_SEP} = {@code <…>/memory}（契约被打断形态，修前 {@code withAutoMem} 的产出）。</li>
 * </ol>
 *
 * <h2>扰动自证 · 为什么不是恒绿（见实施报告原始输出）</h2>
 * <p>{@code BASE_NO_SEP} 那半在<b>修前</b>是<b>被放行</b>的（裸 startsWith 命中）⇒
 * {@link #prefixAttack_isNotAllowed_forBothInputForms()} 修前跑红。
 * 反向实验（把 {@code PathValidation.isAutoMemPath} 改回裸 {@code startsWith}）后<b>两种形态都转红</b>
 * （3 条：端到端写侧静默放行 + 读侧两种 base 形态）—— 因为改回的裸前缀版<b>自己又调了一次
 * {@code normalizePath(base)}</b>，把生产者刚补上的尾分隔符<b>再次吞掉</b>，故 {@code BASE_WITH_SEP} 同样退化。
 * ⇒ <b>消费者侧的边界才是承重的那一道</b>；生产者契约修复无法单独救一个会重新归一 base 的消费者。
 *
 * <p>反向对照（只撤生产者、保留消费者边界式）：安全用例全过，仅 {@link #productionEnv_viaWithAutoMem_blocksPrefixAttackAndAllowsNormalHit}
 * 里那条「{@code autoMemBaseDir} 必须带尾分隔符」的契约钉桩断言转红 ⇒ 生产者侧是<b>纵深防御 + 钉字段契约</b>，
 * 非安全必需，但后人改生产者形态会被这条测试拦住（属<b>有意</b>设计）。
 */
@DisplayName("[T3] auto-memory carve-out 前缀边界（读写双侧 · 两种 base 形态）")
class AutoMemPathPrefixBoundaryTest {

    private static final String SEP = java.io.File.separator;

    // ── 端到端用例的夹具 DB 姿态显式声明（照 WritePermissionCheckerAutoMemWriteCarveOutTest 惯例）──
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛，
    //   而 PathValidationEnv.fromToolUseContext 会对该抛 fail-closed 兜住 —— 那会测到「解析失败态」
    //   而不是「有基址态」。见 SessionProjectRootTestSupport 类 javadoc。
    @BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /** 13 参工厂：显式 effectiveCwd（= 本用例的会话项目根）。 */
    private static ToolUseContext ctx(Path effectiveCwd) {
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    /** 隔离基址：刻意<b>不</b>落在 {@code nexusaiConfigHomeDir} 下，避免 project-dir 分支遮蔽 auto-mem 分支。 */
    private static final String MEM_ROOT = "C:/mem/nexusai/projects/C--proj/memory";

    /** 无尾分隔符形态（= 修前 withAutoMem 的产出 / 契约被打断形态）。 */
    private static final String BASE_NO_SEP = MEM_ROOT;
    /** 带尾分隔符形态（= CC getAutoMemPath() 契约形态）。 */
    private static final String BASE_WITH_SEP = MEM_ROOT + SEP;
    /** 前缀攻击路径：把 base 的尾分隔符换成 {@code -evil} 再拼子文件。 */
    private static final String ATTACK = BASE_NO_SEP + "-evil" + SEP + "MEMORY.md";

    /**
     * 归一化（= 生产调用方在进入 {@code checkReadable/EditableInternalPath} 前做的那一步）。
     *
     * <p>⚠️ 注意 {@link PathValidation#isAutoMemPath} 的形参契约是「<b>已 normalize</b> 的路径」
     * （生产唯一入口 {@code checkEditable/ReadableInternalPath} 都在入口处 {@code normalizePath} 过）；
     * 直接塞带 {@code /} 的原始串会因分隔符字面量不同而假阴。本 helper 让直调断言的输入形态与生产一致。
     */
    private static String norm(String p) {
        return PathValidation.normalizePath(p);
    }

    /**
     * 直接构造 env（不经 bean/CwdResolution）· 两半 slug 刻意与 MEM_ROOT 不同根，
     * 使 auto-mem 分支是<b>唯一</b>可能命中的分支（否则 project-dir 分支会遮蔽，测试就测不到本条边界）。
     */
    private static PathValidationEnv envWithAutoMemBase(String autoMemBaseDir) {
        return new PathValidationEnv(
            "session-1", "agent-1",
            "C:/proj", "C:/proj", "C:/proj",
            "C:/Users/u/.claude",   // claudeConfigHomeDir（只读兼容根）
            "C:/cfg-home/nexusai",  // nexusaiConfigHomeDir（⛔ 与 MEM_ROOT 不同根 ⇒ project-dir 分支不遮蔽）
            false,                  // scratchpadEnabled
            "C:/tmp/claude",
            false,                  // hasAutoMemPathOverride
            autoMemBaseDir,
            null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (a) 正常命中：carve-out 仍须放行 memory 目录内的文件（防修过头把合法路径挡住）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) 正常命中：<base>/MEMORY.md 两种 base 形态下读+写都被放行（防修过头）")
    void normalHit_isAllowed_forBothInputForms() {
        // WHY：边界收紧最容易「修过头」——若有人把判定改成「必须严格以 base 开头的绝对子路径」而
        //   漏掉 equals，或误把 base 拿去比父目录，auto-memory 的正常读写会被误挡 ⇒ 记忆提取链
        //   每次落盘都要用户确认（打断 + 后台任务失败）。本用例是那条红线的哨兵。
        for (String base : new String[]{BASE_NO_SEP, BASE_WITH_SEP}) {
            String memFile = base + SEP + "MEMORY.md";
            PathValidationEnv env = envWithAutoMemBase(base);

            assertThat(PathValidation.isAutoMemPath(norm(memFile), env))
                .as("base=%s：<base>/MEMORY.md 必须在 auto-memory 目录内", base)
                .isTrue();
            assertThat(PathValidation.checkReadableInternalPath(memFile, env).allowed())
                .as("base=%s：读侧 carve-out 必须命中（CC :1716-1725）", base)
                .isTrue();
            assertThat(PathValidation.checkEditableInternalPath(memFile, env).allowed())
                .as("base=%s：写侧 carve-out 必须命中（CC :1572-1581）", base)
                .isTrue();
            assertThat(PathValidation.checkEditableInternalPath(memFile, env).decisionReason())
                .as("base=%s：reason 逐字对齐 CC", base)
                .isEqualTo(new PermissionDecisionReason.Other("auto memory files are allowed for writing"));
        }
    }

    @Test
    @DisplayName("(a′) base 目录自身（无尾分隔符写法）仍命中 = equals 分支（CC 裸 startsWith 同样命中）")
    void memoryDirItself_isAllowed_forBothInputForms() {
        // WHY：CC 的裸 startsWith 对「路径 == base 去掉尾分隔符」是命中的（startsWith 前缀即自身）。
        //   收边界时若把 equals 分支丢掉，会相对 CC 收窄一位 —— 本仓红线是「对齐收紧，⛔ 不改变
        //   carve-out 的适用语义范围」。此处钉住「目录自身仍在范围内」。
        for (String base : new String[]{BASE_NO_SEP, BASE_WITH_SEP}) {
            PathValidationEnv env = envWithAutoMemBase(base);
            assertThat(PathValidation.isAutoMemPath(norm(BASE_NO_SEP), env))
                .as("base=%s：auto-memory 目录自身必须在范围内（对齐 CC 裸 startsWith 的语义）", base)
                .isTrue();
            assertThat(PathValidation.checkEditableInternalPath(BASE_NO_SEP, env).allowed())
                .as("base=%s：写侧对目录自身同样命中", base)
                .isTrue();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (b) 前缀攻击：本任务的核心判据
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(b) 前缀攻击：<base>-evil/MEMORY.md 两种 base 形态下读+写都【不得】被放行")
    void prefixAttack_isNotAllowed_forBothInputForms() {
        // WHY：这是本任务的核心判据。carve-out 的边界必须是「路径段」边界。裸 startsWith 会放行
        //   <base>-evil/（攻击者/模型选定的任意相邻目录）⇒ 写侧静默写、读侧静默读，绕过用户确认。
        //   ⛔ 两种 base 形态都必须挡住：只挡「带尾分隔符」等于把防御押在生产者契约上（本仓已证
        //   该契约在 withAutoMem 被打断过），只挡「无尾分隔符」则 CC 契约形态反而失守。
        for (String base : new String[]{BASE_NO_SEP, BASE_WITH_SEP}) {
            PathValidationEnv env = envWithAutoMemBase(base);

            // 断言顺序刻意「先两个真实消费点、后判定 helper」：断言失败即抛出，这样「改动前跑红」的
            // 原始输出第一行就是【端到端被静默放行】这一承重事实，而不是内部 helper 的返回码。
            assertThat(PathValidation.checkReadableInternalPath(ATTACK, env).allowed())
                .as("base=%s：读侧不得被 auto-mem carve-out 静默放行（CC :1716-1725）", base)
                .isFalse();
            assertThat(PathValidation.checkEditableInternalPath(ATTACK, env).allowed())
                .as("base=%s：写侧不得被 auto-mem carve-out 静默放行（CC :1572-1581）", base)
                .isFalse();
            assertThat(PathValidation.isAutoMemPath(norm(ATTACK), env))
                .as("base=%s：<base>-evil/ 不是 auto-memory 目录的子路径（前缀必须是路径段边界）", base)
                .isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c) 生产形态：基址经 withAutoMem（真实 AutoMemPaths POJO）填入
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) 生产形态：withAutoMem 填入的基址 ⇒ 正常命中放行、前缀攻击不放行")
    void productionEnv_viaWithAutoMem_blocksPrefixAttackAndAllowsNormalHit(
            @TempDir Path memoryRoot, @TempDir Path projectRoot) {
        // WHY：上面的 (a)/(b) 是直接构造 env 的单元断言。本用例走<b>真实生产者</b>
        //   （AutoMemPaths → getAutoMemPath(会话项目根) → withAutoMem），证明修后的实际接线形态
        //   满足全部三条：正常放行 / 攻击不放行 / 且基址确实带上了尾分隔符（与 CC getAutoMemPath 契约同形）。
        Path memoryBase = memoryRoot.resolve(".nexusai");
        AutoMemPaths pojo = new AutoMemPaths(
            projectRoot::toString, memoryBase::toString, () -> null, () -> null);

        String autoMem = pojo.getAutoMemPath(projectRoot.toString());
        assertThat(autoMem)
            .as("夹具前提：getAutoMemPath 产出带唯一尾分隔符（CC paths.ts:149/230-232 契约）")
            .endsWith(SEP);

        PathValidationEnv env = new PathValidationEnv(
            "session-1", "agent-1",
            projectRoot.toString(), projectRoot.toString(), projectRoot.toString(),
            "C:/Users/u/.claude", "C:/cfg-home/nexusai",
            false, "C:/tmp/claude",
            false, null, null)
            .withAutoMem(pojo);

        assertThat(env.hasAutoMemPathOverride())
            .as("夹具前提：POJO 无 override ⇒ 写侧 carve-out 的 !hasAutoMemPathOverride 门开启")
            .isFalse();

        String memFile = Path.of(autoMem).normalize().resolve("MEMORY.md").toString();
        // 攻击路径：把 autoMemPath 的尾分隔符替换成 "-evil"
        String attack = autoMem.substring(0, autoMem.length() - 1) + "-evil" + SEP + "MEMORY.md";

        // 断言顺序：先行为（真实承重），后生产者契约（诊断性）。见 prefixAttack 的同款说明。
        assertThat(PathValidation.checkReadableInternalPath(attack, env).allowed())
            .as("生产形态：<autoMem>-evil/ 读侧不得放行")
            .isFalse();
        assertThat(PathValidation.checkEditableInternalPath(attack, env).allowed())
            .as("生产形态：<autoMem>-evil/ 写侧不得放行")
            .isFalse();
        assertThat(PathValidation.checkReadableInternalPath(memFile, env).allowed())
            .as("生产形态：正常 memory 文件读侧必须放行")
            .isTrue();
        assertThat(PathValidation.checkEditableInternalPath(memFile, env).allowed())
            .as("生产形态：正常 memory 文件写侧必须放行")
            .isTrue();
        assertThat(env.autoMemBaseDir())
            .as("修后 withAutoMem 必须保留 CC 契约的尾分隔符（否则裸 startsWith 类判定会再次退化）")
            .endsWith(SEP);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (e) 端到端写侧：前缀攻击路径不得被静默写（用户可见症状）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(e) 端到端写：WritePermissionChecker(<autoMem>-evil/MEMORY.md) ⇒ 不是 Allow（必须走用户确认）")
    void endToEndWrite_prefixAttack_isNotSilentlyAllowed(
            @TempDir Path memoryRoot, @TempDir Path projectRoot) {
        // WHY：core 层「carve-out 不命中」只是中间态。用户可见的症状是「模型静默写了未授权目录、
        //   没弹窗」⇒ 必须有一条端到端断言证明攻击路径落不到 Allow。本用例走真实 checker
        //   （WritePermissionChecker.check step 1.5 → checkEditableInternalPath），与 (b) 的 core 层
        //   断言构成「机制 + 症状」两级证据。
        Path memoryBase = memoryRoot.resolve(".nexusai");
        AutoMemPaths pojo = new AutoMemPaths(
            projectRoot::toString, memoryBase::toString, () -> null, () -> null);
        String autoMem = pojo.getAutoMemPath(projectRoot.toString());
        String attack = autoMem.substring(0, autoMem.length() - 1) + "-evil" + SEP + "MEMORY.md";

        WritePermissionChecker checker = new WritePermissionChecker();
        checker.setAutoMemPaths(pojo);
        Tool tool = new WriteFileTool(new PathGuard(projectRoot));

        PermissionResult result = checker.check(tool, input(attack), ctx(projectRoot));

        assertThat(result)
            .as("前缀攻击路径不得被 auto-mem 写 carve-out 静默放行（Allow = 未弹窗即落盘）")
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (d) 反向护栏（防放宽）：同级目录不得被放行
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d) 反向护栏：<base>/../tool-results/o.txt（同级兄弟目录）不得被放行")
    void siblingDir_isNotCarvedOut() {
        // WHY（防放宽过头）：本批只收边界、⛔ 不放宽。若有人把 base 取成 memory 的父目录（slug 目录），
        //   则同 slug 下的 tool-results / 任意文件都会被静默写 —— 即模型可无弹窗写 <memoryBase>/projects 下任意内容。
        String sibling = MEM_ROOT + SEP + ".." + SEP + "tool-results" + SEP + "o.txt";
        PathValidationEnv env = envWithAutoMemBase(BASE_WITH_SEP);

        assertThat(PathValidation.isAutoMemPath(norm(sibling), env))
            .as("normalize 后落在 auto-memory 目录之外 ⇒ 不得命中")
            .isFalse();
        assertThat(PathValidation.checkEditableInternalPath(sibling, env).allowed())
            .as("同级目录不得被 auto-mem 写 carve-out 放行（判定未放宽）")
            .isFalse();
    }
}
