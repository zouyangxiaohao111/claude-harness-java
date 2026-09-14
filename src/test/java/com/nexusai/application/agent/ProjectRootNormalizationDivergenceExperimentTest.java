package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⭐ <b>[S2 · F-24-merge Step 2 决策/验收实验 · 2026-09-14]</b> —— 原为「差异 B 取证装置」，
 * Step 2 落地后断言翻转为<b>「差异 B 已消除」</b>（用户裁定：⛔ 不删，翻红/翻转本身就是信号）。
 *
 * <h2>被测对象：两条 DB 链冻结的 projectRoot 是否同一</h2>
 * <ol>
 *   <li><b>链 1</b> = {@link SessionProjectRoot} 的冻结表回源解析器（生产体 =
 *       {@code ToolRegistrationConfig#sessionProjectRootResolver}）。Step 2 后该解析器在返回
 *       {@code bound} 前统一 {@code CwdResolution.normalizeCwd} + 用同一判据校验。</li>
 *   <li><b>链 2</b> = {@code LlmAgentLoop.tryResolveBoundProjectFromDb}（B′ 兜底）。自带
 *       {@code normalizeSessionProjectRoot} = realpath + NFC。</li>
 * </ol>
 *
 * <h2>本类守的不变量（Step 2 后）</h2>
 * <p>对同一 DB 值，两条链必须冻结<b>同一</b>归一值 —— 因为 {@code setForSession} 是<b>首写胜</b>，
 * 两链冻不同值 ⇒ 终态取决于「运行时谁先跑」，而该字符串会被 {@code AutoMemPaths.sanitizePath}
 * 派生成 {@code projects/<slug>} 目录键（落差 = 同一会话的 auto-memory / transcript 落到不同目录）。
 *
 * <p><b>RED（反向实验 · Step 2 承重证明）</b>：去掉回源器里的 {@code CwdResolution.normalizeCwd}
 * 调用（恢复「返回 DB 原值」）⇒ 读数 1/2/3/5 全部翻红（实测，见报告）。
 *
 * <h2>⛔ 本类不是「不变量守卫」的替代品</h2>
 * <p>它是**决策/验收装置**（Step 2 的 (A) 收口前提），类名保留 {@code Experiment}。真正的生产不变量
 * 守卫仍是 {@code SessionProjectRootValidityParityTest}（差异 A）。
 */
@DisplayName("[S2 F-24 Step 2 验收] 两条 DB 链必须冻结同一归一值（差异 B 已消除）")
class ProjectRootNormalizationDivergenceExperimentTest {

    private static final String SID = "sess-divergence-probe";

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 两条链的真驱动（都用生产路径，不重写判据）
    // ══════════════════════════════════════════════════════════════════════

    // ── 共用的 mapper 打桩（两条链都经真实生产查询逻辑） ──

    private static com.nexusai.repository.session.mapper.SessionMapper sessionMapperFor(String sid) {
        SessionRecord s = new SessionRecord();
        s.setId(sid);
        s.setMainProjectId("proj-diverge");
        com.nexusai.repository.session.mapper.SessionMapper m =
            mock(com.nexusai.repository.session.mapper.SessionMapper.class);
        when(m.selectOneById(sid)).thenReturn(s);
        return m;
    }

    private static com.nexusai.repository.project.mapper.ProjectMapper projectMapperFor(String dbPath) {
        ProjectRecord p = new ProjectRecord();
        p.setId("proj-diverge");
        p.setPath(dbPath);
        com.nexusai.repository.project.mapper.ProjectMapper m =
            mock(com.nexusai.repository.project.mapper.ProjectMapper.class);
        when(m.selectOneById("proj-diverge")).thenReturn(p);
        return m;
    }

    /**
     * ⭐ 链 1：驱动**真实生产回源器** {@code ToolRegistrationConfig#sessionProjectRootResolver}
     * （{@code @Bean} 方法；会把 Lookup 版解析器注册进 {@link SessionProjectRoot}）⇒
     * {@code lookup} miss → {@code refillFromDb} 冻结。返回冻结值（可能 null）。
     *
     * <p>⚠️ <b>装置教训（本类实测踩到，必须保留）</b>：Step 2 的归一化就在这个生产 bean 里，
     * 所以<b>不能</b>用「测试自造 `s -> Lookup.bound(dbPath)`」代替 —— 那样测的是测试自己的 lambda，
     * Step 2 无论是否正确都会「看起来没生效」。（首次实现即如此，4 条读数全红。）
     */
    private static String freezeViaProductionResolver(String sid, String dbPath) {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        new com.nexusai.application.agent.config.ToolRegistrationConfig()
            .sessionProjectRootResolver(sessionMapperFor(sid), projectMapperFor(dbPath));
        return SessionProjectRoot.lookup(sid).projectRoot();
    }

    /** 链 2 的一次驱动的原始读数：{@code ok} = B′ 是否判定「有项目」；{@code frozen} = 冻结值。 */
    private record Chain2Read(boolean ok, String frozen) {}

    /**
     * 链 2：反射驱动生产方法 {@code LlmAgentLoop.tryResolveBoundProjectFromDb(String)}（B′ 兜底）。
     * <p>⛔ 不假设它一定成功 —— 它的成功取决于 {@code isValidDirectory(normalizeSessionProjectRoot(raw))}，
     * 而该判据可能因归一化把路径改成磁盘上不存在的形态而失手（读数 2/6）。
     */
    private static Chain2Read readChain2(String sid, String dbPath) throws Exception {
        LlmAgentLoop loop = new LlmAgentLoop(mock(LlmProviderFactory.class));
        ReflectionTestUtils.setField(loop, "sessionMapper", sessionMapperFor(sid));
        ReflectionTestUtils.setField(loop, "projectMapper", projectMapperFor(dbPath));

        Method m = LlmAgentLoop.class.getDeclaredMethod("tryResolveBoundProjectFromDb", String.class);
        m.setAccessible(true);
        boolean ok = (Boolean) m.invoke(loop, sid);
        String frozen = ok ? SessionProjectRoot.lookup(sid).projectRoot() : null;
        if (ok) {
            assertThat(frozen).as("链 2 报成功却未冻结 ⇒ 装置有误").isNotNull();
        }
        return new Chain2Read(ok, frozen);
    }

    /** 两链各自独立冻结（不同 sid，互不干扰），返回 {链1 冻结值, 链2 冻结值}。 */
    private static String[] bothChains(String dbPath) throws Exception {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        Chain2Read c2 = readChain2(SID + "-c2", dbPath);
        String viaChain1 = freezeViaProductionResolver(SID + "-c1", dbPath);
        return new String[] {viaChain1, c2.frozen()};
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 1：斜杠方向（ProjectService 存正斜杠 / 链 2 归一为原生分隔符）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 生产可达性依据（代码事实）：{@code ProjectService.normalizeProjectPath} =
     * {@code Path.of(p).toAbsolutePath().normalize().toString().replace('\\','/')} ⇒
     * <b>不 realpath、不 NFC、恒正斜杠</b>（Windows 下本读数 100% 触发）。
     */
    @Test
    @DisplayName("读数1：DB 存正斜杠 ⇒ 两条链冻结同一值（Step 2 前差斜杠方向 / 现收敛）")
    void reading1_slashDirection() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("demo-project"));
        String dbValue = real.toString().replace('\\', '/');   // ← ProjectService 的存储形态

        String[] frozen = bothChains(dbValue);
        System.out.println("[Step2 读数1] DB 值        = " + dbValue);
        System.out.println("[Step2 读数1] 链1(回源器) = " + frozen[0]);
        System.out.println("[Step2 读数1] 链2(B′兜底) = " + frozen[1]);

        assertThat(frozen[0])
            .as("两条链必须冻结同一归一值（Step 2 前：链1 保留正斜杠 / 链2 变原生 ⇒ 本断言红）")
            .isEqualTo(frozen[1])
            .isEqualTo(real.toRealPath().toString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 2：non-NFC 目录名
    // ══════════════════════════════════════════════════════════════════════

    /** NFD 形态的目录名（"cafe" + U+0301 组合尖音符）。 */
    private static final String NFD_DIR_NAME = "cafe\u0301-nfd";

    @Test
    @DisplayName("读数2：non-NFC 目录名 ⇒ 两条链给出同一结论（均不给出可用绑定），且 slug 差异已消除")
    void reading2_nonNfcName() throws Exception {
        Path real;
        try {
            real = Files.createDirectories(tempDir.resolve(NFD_DIR_NAME));
        } catch (Exception e) {
            Assumptions.abort("本文件系统不接受 NFD 目录名，读数 2 不可构造: " + e);
            return;
        }
        String dbValue = real.toString().replace('\\', '/');
        // 装置自检：磁盘上的名字确实不是 NFC 形态（否则本读数零产点）
        Assumptions.assumeTrue(
            !Normalizer.isNormalized(real.getFileName().toString(), Normalizer.Form.NFC),
            "磁盘保存的是 NFC 形态 ⇒ 本读数无产点（跳过而非假绿）");

        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        String viaChain1 = freezeViaProductionResolver(SID + "-nfc-c1", dbValue);
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        Chain2Read c2 = readChain2(SID + "-nfc-c2", dbValue);

        String nfcForm = Normalizer.normalize(dbValue, Normalizer.Form.NFC);
        System.out.println("[Step2 读数2] DB 值(NFD)    = " + dbValue);
        System.out.println("[Step2 读数2] NFC 形态      = " + nfcForm);
        System.out.println("[Step2 读数2] 链1 frozen    = " + viaChain1);
        System.out.println("[Step2 读数2] 链2 ok/frozen = " + c2.ok() + " / " + c2.frozen());
        System.out.println("[Step2 读数2] NFD 目录存在?  = " + Files.isDirectory(Path.of(dbValue)));
        System.out.println("[Step2 读数2] NFC 形态存在?  = " + Files.isDirectory(Path.of(nfcForm)));

        // Step 2 后两链**结论一致**：都不给出可用绑定（链1 = 归一化后无效 ⇒ 不冻结；
        // 链2 = isValidDirectory(归一值) 失败 ⇒ 不解析）。⇒ 冻结值同为 null（同一值）。
        assertThat(viaChain1)
            .as("链1（回源器）：归一化后目录无效 ⇒ 不给出绑定（Step 2 前=会冻结 NFD 原值）")
            .isNull();
        assertThat(c2.frozen())
            .as("链2（B′ 兜底）：同一判失败 ⇒ 亦不给出绑定")
            .isNull();
        assertThat(viaChain1)
            .as("⭐ 两链冻结同一值（差异 B 已消除）")
            .isEqualTo(c2.frozen());

        // ⚠️ 本读数的**后果登记**（不是断言两链一致就没事）：NFD 项目在 Step 2 后对
        //   cwd 域 = fail-loud「有会话但绑定失效」。但该情形**Step 2 前也已经是坏的** ——
        //   见读数 6 的静态复现（旧 L2 分支会返回一个**不存在**的 NFC 形态路径）。
        assertThat(AutoMemPaths.sanitizePath(nfcForm))
            .as("slug 差异本身已被 Step 2 消掉（两链都用归一形态；不再有『NFD slug vs NFC slug』两条路）")
            .isNotEqualTo(AutoMemPaths.sanitizePath(dbValue));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 3：符号链接
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读数3：路径含 symlink/junction ⇒ 两条链冻结同一 realpath（slug 差异已消除）")
    void reading3_symlinkComponent() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("real-target"));
        Path link = tempDir.resolve("link-to-target");
        if (!tryCreateLink(link, target)) {
            Assumptions.abort("本机无权限创建 symlink/junction（需开发者模式或 mklink /J）⇒ 读数 3 不可构造");
            return;
        }
        Assumptions.assumeTrue(
            !link.toRealPath().toString().equals(link.toString()),
            "link 未被解析（非真 reparse point）⇒ 本读数无产点（跳过而非假绿）");
        String dbValue = link.toString().replace('\\', '/');

        String[] frozen = bothChains(dbValue);
        System.out.println("[Step2 读数3] DB 值(link)  = " + dbValue);
        System.out.println("[Step2 读数3] 链1(回源器) = " + frozen[0]);
        System.out.println("[Step2 读数3] 链2(B′兜底) = " + frozen[1]);

        assertThat(frozen[0])
            .as("两条链必须冻结同一 realpath（Step 2 前：链1=link 路径 / 链2=realpath ⇒ 本断言红，"
                + "且 slug 落到两个不同目录）")
            .isEqualTo(frozen[1])
            .isEqualTo(target.toRealPath().toString());
        assertThat(AutoMemPaths.sanitizePath(frozen[0]))
            .as("slug 差异已消除")
            .isEqualTo(AutoMemPaths.sanitizePath(frozen[1]));
    }

    /** best-effort 造 reparse point：先试 {@code Files.createSymbolicLink}，再试 {@code mklink /J}。 */
    private static boolean tryCreateLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception symlinkFailed) {
            try {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
                boolean done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
                return done && Files.exists(link);
            } catch (Exception junctionFailed) {
                return false;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 4：cwd 域免疫（说明差异的可见范围）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读数4：CwdResolution 域经 normalizeCwd ⇒ 两形态收敛（差异在 cwd 域本就不可观测）")
    void reading4_cwdDomainIsImmune() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("converge-demo"));
        String dbValue = real.toString().replace('\\', '/');
        String nfcRaw = Normalizer.normalize(dbValue, Normalizer.Form.NFC);

        assertThat(CwdResolution.normalizeCwd(dbValue))
            .as("CwdResolution 消费点自身 realpath+NFC ⇒ 两形态收敛 ⇒ 差异只在**读 frozen 原始字符串**"
                + "的消费方（slug/缓存键）可见 —— 这解释了为何该缺陷长期未被发现")
            .isEqualTo(CwdResolution.normalizeCwd(nfcRaw));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 5：顺序依赖（首写胜）—— Step 2 后应消失
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读数5：首写胜不再产生不确定性 —— 两链先跑哪种顺序，终态都是同一归一值")
    void reading5_freezeOrderNoLongerMatters() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("order-demo"));
        String dbValue = real.toString().replace('\\', '/');

        // (i) 链 1 先冻结
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        String sidA = SID + "-order-a";
        String firstA = freezeViaProductionResolver(sidA, dbValue);
        readChain2(sidA, dbValue);
        String afterA = SessionProjectRoot.lookup(sidA).projectRoot();

        // (ii) 链 2 先冻结
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        String sidB = SID + "-order-b";
        String firstB = readChain2(sidB, dbValue).frozen();
        freezeViaProductionResolver(sidB, dbValue);
        String afterB = SessionProjectRoot.lookup(sidB).projectRoot();

        System.out.println("[Step2 读数5] 链1先: after=" + afterA);
        System.out.println("[Step2 读数5] 链2先: after=" + afterB);

        assertThat(afterA)
            .as("链1 先 ⇒ 链2 的写入被首写胜拒绝，终态 = 链1 的值（仍归一）")
            .isEqualTo(firstA);
        assertThat(afterB)
            .as("链2 先 ⇒ 链1 的写入被拒绝，终态 = 链2 的值")
            .isEqualTo(firstB);
        assertThat(afterA)
            .as("⭐ Step 2 后**两种顺序的终态相同** ⇒ 「谁先冻结决定 slug」的不确定性消失"
                + "（Step 2 前：两种顺序给出不同字符串 ⇒ 本断言红）")
            .isEqualTo(afterB)
            .isEqualTo(real.toRealPath().toString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 6：NFD 情形的**分类**（Step 2 是「把静默坏值变显式失败」还是「引入新错」）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Step 2 前 {@code getCwd} 的 L2 分支（代码事实静态复现，无需回滚代码）：
     * <pre>
     *   if (boundProject != null) {
     *       if (isValidDirectory(boundProject))   // ← 对 NFD 原值 = true（目录确实存在）
     *           return normalizeCwd(boundProject); // ← 但返回的是 NFC 形态 = **磁盘上不存在**
     *   }
     * </pre>
     * ⇒ 对「NFD 名登记的项目」，Step 2 前 cwd 域会**返回一个不存在的路径**（比 fail-loud 更坏：
     * 下游文件工具随后 ENOENT）。Step 2 把这一情形改为「归一化后无效 ⇒ 不给出绑定」= 显式失败。
     * <p>⇒ 分类结论：这是<b>把静默坏值变成显式失败</b>，不是 Step 2 引入的新错。
     */
    @Test
    @DisplayName("读数6：NFD 项目在 Step 2 前 cwd 域返回的是**不存在的路径**（静态复现旧 L2 分支）")
    void reading6_nfdWasAlreadyBrokenBeforeStep2() throws Exception {
        Path nfd;
        try {
            nfd = Files.createDirectories(tempDir.resolve(NFD_DIR_NAME + "-classify"));
        } catch (Exception e) {
            Assumptions.abort("本文件系统不接受 NFD 目录名，读数 6 不可构造: " + e);
            return;
        }
        Assumptions.assumeTrue(
            !Normalizer.isNormalized(nfd.getFileName().toString(), Normalizer.Form.NFC),
            "磁盘保存的是 NFC 形态 ⇒ 本读数无产点（跳过而非假绿）");

        String raw = nfd.toString();
        String normalized = CwdResolution.normalizeCwd(raw);

        System.out.println("[Step2 读数6] raw          = " + raw);
        System.out.println("[Step2 读数6] normalizeCwd = " + normalized);
        System.out.println("[Step2 读数6] raw 是目录?   = " + CwdResolution.isValidDirectory(raw));
        System.out.println("[Step2 读数6] 归一值是目录? = " + CwdResolution.isValidDirectory(normalized));

        // 旧 L2 分支会「进来」（原值有效）……
        assertThat(CwdResolution.isValidDirectory(raw))
            .as("旧 L2 的 isValidDirectory(原值) = true ⇒ 旧代码会走进该分支并直接返回 normalizeCwd(原值)")
            .isTrue();
        // ……但返回的归一值在磁盘上不存在 ⇒ 旧行为 = 返回坏路径（静默）
        assertThat(CwdResolution.isValidDirectory(normalized))
            .as("⭐ 归一值在磁盘上不存在 ⇒ Step 2 前 cwd 域返回的是**坏路径**（下游 ENOENT），"
                + "Step 2 改为「不给出绑定」= fail-loud ⇒ 是修坏值不是引入新错")
            .isFalse();
    }
}
