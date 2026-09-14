package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⭐ <b>[F-24 · 归一化单点守卫]</b> —— 原「差异 B 取证装置」，Step 3 后翻转/退役为
 * <b>单一链的归一化单点守卫</b>。
 *
 * <h2>沿革（类名保留为历史 · ⚠️ 名字里的「Divergence」是 Step 3 之前的事）</h2>
 * <ol>
 *   <li><b>Step 2（2026-09-14）</b>：本类对照<b>两条</b> DB 链冻结的 projectRoot 是否同一 ——
 *       链 1 = {@link SessionProjectRoot} 的冻结表回源解析器（生产体 =
 *       {@code ToolRegistrationConfig#sessionProjectRootResolver}）；链 2 = B′ 兜底
 *       （{@code LlmAgentLoop.tryResolveBoundProjectFromDb}）。Step 2 落地「归一化下沉到回源器」后
 *       断言翻转为「已收敛」。</li>
 *   <li><b>Step 3（2026-09-14 · 用户裁定「删，合并成一个」）</b>：链 2 <b>本体已删</b>
 *       （{@code LlmAgentLoop} 的 5 个失败出口改走 {@link SessionProjectRoot#lookup(String)}）
 *       ⇒ 本类<b>不再有「两链」可对照</b>。故：
 *       <ul>
 *         <li>读数 1/3 的断言从「两链相等」改为「冻结值 = realpath 归一值」（对同一 DB 值只剩
 *             一个真值，等价且更强 —— 直指 Step 2 那条归一化调用本身）；</li>
 *         <li>读数 2 去掉链 2 侧断言（链已不存在），保留「不给绑定 + slug 差异已消除」；</li>
 *         <li><b>读数 5 退役</b>（原测「首写胜下两链谁先跑终态相同」）—— 只剩一条写入路径，
 *             「顺序」这个维度消失；改为<b>幂等 + 会话内不重查</b>（F1 冻结语义 + 单一写入路径）。</li>
 *       </ul>
 *       ⛔ 读数 1/3/6 仍<b>不是</b>不变量守卫的替代品：它们是决策/验收读数，类名保留 {@code Experiment}。</li>
 * </ol>
 *
 * <h2>本类现守的<b>唯一</b>不变量</h2>
 * <p>回源器返回 {@code bound} 前必须做 {@link CwdResolution#normalizeCwd}（realpath + NFC）——
 * 否则冻结值 ≠ realpath，同一 DB 值的 {@code projects/<slug>} 会落到与 cwd 域不同的目录。
 *
 * <p><b>RED（反向实验 · 有鉴别力）</b>：去掉回源器里的 {@code CwdResolution.normalizeCwd} 调用
 * （恢复「返回 DB 原值」）⇒ 读数 1（正斜杠）/ 读数 3（symlink）/ 读数 5（幂等值）全部翻红。
 *
 * <p>真正的生产不变量守卫仍是 {@code SessionProjectRootValidityParityTest}（判据唯一性）。
 */
@DisplayName("[F-24 归一化单点] 唯一链冻结的 projectRoot 必须 = realpath 归一值（Step 3 后 B′ 已删）")
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
    // 唯一链的真驱动（走生产 bean，不重写判据）
    // ══════════════════════════════════════════════════════════════════════

    // ── mapper 打桩（链经真实生产查询逻辑） ──

    private static SessionMapper sessionMapperFor(String sid) {
        SessionRecord s = new SessionRecord();
        s.setId(sid);
        s.setMainProjectId("proj-diverge");
        SessionMapper m = mock(SessionMapper.class);
        when(m.selectOneById(sid)).thenReturn(s);
        return m;
    }

    private static ProjectMapper projectMapperFor(String dbPath) {
        ProjectRecord p = new ProjectRecord();
        p.setId("proj-diverge");
        p.setPath(dbPath);
        ProjectMapper m = mock(ProjectMapper.class);
        when(m.selectOneById("proj-diverge")).thenReturn(p);
        return m;
    }

    /**
     * 唯一链：驱动<b>真实生产回源器</b> {@code ToolRegistrationConfig#sessionProjectRootResolver}
     * （{@code @Bean} 方法；会把 Lookup 版解析器注册进 {@link SessionProjectRoot}）⇒
     * {@code lookup} miss → {@code refillFromDb} 冻结。返回冻结值（可能 null）。
     *
     * <p>⚠️ <b>装置教训（本类实测踩到，必须保留）</b>：归一化就在这个生产 bean 里，所以
     * <b>不能</b>用「测试自造 {@code s -> Lookup.bound(dbPath)}」代替 —— 那样测的是测试自己的
     * lambda，Step 2 无论是否正确都会「看起来没生效」。（首次实现即如此，4 条读数全红。）
     */
    private static String freezeViaProductionResolver(String sid, String dbPath) {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        new com.nexusai.application.agent.config.ToolRegistrationConfig()
            .sessionProjectRootResolver(sessionMapperFor(sid), projectMapperFor(dbPath));
        return SessionProjectRoot.lookup(sid).projectRoot();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 1：斜杠方向（ProjectService 存正斜杠 / 归一后为原生分隔符 realpath）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 生产可达性依据（代码事实）：{@code ProjectService.normalizeProjectPath} =
     * {@code Path.of(p).toAbsolutePath().normalize().toString().replace('\\','/')} ⇒
     * <b>不 realpath、不 NFC、恒正斜杠</b>（Windows 下本读数 100% 触发）。
     */
    @Test
    @DisplayName("读数1：DB 存正斜杠 ⇒ 唯一链冻结值 = realpath（归一化仍在生产 bean 里）")
    void reading1_slashDirection() {
        Path real;
        try {
            real = Files.createDirectories(tempDir.resolve("demo-project")).toRealPath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String dbValue = real.toString().replace('\\', '/');   // ← ProjectService 的存储形态

        String frozen = freezeViaProductionResolver(SID + "-c1", dbValue);
        System.out.println("[Step3 读数1] DB 值    = " + dbValue);
        System.out.println("[Step3 读数1] 冻结值  = " + frozen);

        assertThat(frozen)
            .as("归一化是生产 bean 的责任：去掉回源器里的 normalizeCwd ⇒ 冻结值回到正斜杠形态 ⇒ 本断言红")
            .isEqualTo(real.toString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 2：non-NFC 目录名
    // ══════════════════════════════════════════════════════════════════════

    /** NFD 形态的目录名（"cafe" + U+0301 组合尖音符）。 */
    private static final String NFD_DIR_NAME = "cafe\u0301-nfd";

    @Test
    @DisplayName("读数2：non-NFC 目录名 ⇒ 链不给出可用绑定，且 slug 差异已消除")
    void reading2_nonNfcName() {
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

        String frozen = freezeViaProductionResolver(SID + "-nfc-c1", dbValue);

        String nfcForm = Normalizer.normalize(dbValue, Normalizer.Form.NFC);
        System.out.println("[Step3 读数2] DB 值(NFD)    = " + dbValue);
        System.out.println("[Step3 读数2] NFC 形态      = " + nfcForm);
        System.out.println("[Step3 读数2] 冻结值        = " + frozen);
        System.out.println("[Step3 读数2] NFD 目录存在?  = " + Files.isDirectory(Path.of(dbValue)));
        System.out.println("[Step3 读数2] NFC 形态存在?  = " + Files.isDirectory(Path.of(nfcForm)));

        assertThat(frozen)
            .as("归一化后目录无效（NFC 形态在磁盘上不存在）⇒ 不给出绑定（Step 2 前=会冻结 NFD 原值）")
            .isNull();
        assertThat(AutoMemPaths.sanitizePath(nfcForm))
            .as("slug 差异本身已被消掉（冻结/消费两形态都只用归一形态）")
            .isNotEqualTo(AutoMemPaths.sanitizePath(dbValue));

        // ⚠️ 本读数的**后果登记**（不是断言一致就没事）：NFD 项目在归一化下沉后对
        //   cwd 域 = fail-loud「有会话但绑定失效」。但该情形**归一化下沉前也已经是坏的** ——
        //   见读数 6 的静态复现（旧 L2 分支会返回一个**不存在**的 NFC 形态路径）。
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 3：符号链接
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读数3：路径含 symlink/junction ⇒ 唯一链冻结 realpath（slug 差异已消除）")
    void reading3_symlinkComponent() {
        Path target;
        Path link;
        try {
            target = Files.createDirectories(tempDir.resolve("real-target")).toRealPath();
            link = tempDir.resolve("link-to-target");
            if (!tryCreateLink(link, target)) {
                Assumptions.abort("本机无权限创建 symlink/junction（需开发者模式或 mklink /J）⇒ 读数 3 不可构造");
                return;
            }
            Assumptions.assumeTrue(
                !link.toRealPath().toString().equals(link.toString()),
                "link 未被解析（非真 reparse point）⇒ 本读数无产点（跳过而非假绿）");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String dbValue = link.toString().replace('\\', '/');

        String frozen = freezeViaProductionResolver(SID + "-sym-c1", dbValue);
        System.out.println("[Step3 读数3] DB 值(link) = " + dbValue);
        System.out.println("[Step3 读数3] 冻结值      = " + frozen);

        assertThat(frozen)
            .as("冻结值必须是 realpath（去掉回源器里的 normalizeCwd ⇒ 冻结 link 路径 ⇒ 本断言红，"
                + "且 slug 落到两个不同目录）")
            .isEqualTo(target.toString());
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
    void reading4_cwdDomainIsImmune() {
        Path real;
        try {
            real = Files.createDirectories(tempDir.resolve("converge-demo")).toRealPath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String dbValue = real.toString().replace('\\', '/');
        String nfcRaw = Normalizer.normalize(dbValue, Normalizer.Form.NFC);

        assertThat(CwdResolution.normalizeCwd(dbValue))
            .as("CwdResolution 消费点自身 realpath+NFC ⇒ 两形态收敛 ⇒ 差异只在**读 frozen 原始字符串**"
                + "的消费方（slug/缓存键）可见 —— 这解释了为何该缺陷长期未被发现")
            .isEqualTo(CwdResolution.normalizeCwd(nfcRaw));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 5（Step 3 重写）：单一写入路径 + F1 会话内不重查
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 原读数 5 测「首写胜下两链谁先跑终态相同」—— Step 3 后链 2 已删，<b>「顺序」维度消失</b>。
     * 重写为单一链下仍成立的两条性质：
     * <ol>
     *   <li><b>幂等</b>：同一 sid 反复驱动，冻结值恒 = realpath 归一值（无第二条写入路径能改写它）；</li>
     *   <li><b>会话内不重查</b>（F1）：第二次驱动<b>不再查 DB</b>（resolver 只被调用 1 次）。</li>
     * </ol>
     * <b>RED（反向实验）</b>：去掉回源器里的 {@code normalizeCwd} ⇒ 冻结值变非归一形态 ⇒ 本读数红；
     * 去掉 {@link SessionProjectRoot#lookup(String)} 的冻结表 cache-hit 短路 ⇒ 计数变 2 ⇒ 本读数红。
     */
    @Test
    @DisplayName("读数5：单一链下冻结幂等 + 会话内不重查（原「两链顺序」读数随 B′ 删除退役）")
    void reading5_singleChainFreezeIsIdempotent_andNotRequeried() {
        Path real;
        try {
            real = Files.createDirectories(tempDir.resolve("order-demo")).toRealPath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String dbValue = real.toString().replace('\\', '/');
        String sid = SID + "-single";

        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
        AtomicInteger sessionQueries = new AtomicInteger();
        SessionMapper sm = mock(SessionMapper.class);
        when(sm.selectOneById(sid)).thenAnswer(inv -> {
            sessionQueries.incrementAndGet();
            SessionRecord s = new SessionRecord();
            s.setId(sid);
            s.setMainProjectId("proj-diverge");
            return s;
        });
        new com.nexusai.application.agent.config.ToolRegistrationConfig()
            .sessionProjectRootResolver(sm, projectMapperFor(dbValue));

        String first = SessionProjectRoot.lookup(sid).projectRoot();
        String second = SessionProjectRoot.lookup(sid).projectRoot();

        System.out.println("[Step3 读数5] 首次冻结 = " + first);
        System.out.println("[Step3 读数5] 再次读取 = " + second);
        System.out.println("[Step3 读数5] 查询会话次数 = " + sessionQueries.get());

        assertThat(first).as("首次驱动 = 唯一链回源 + 归一 + 冻结").isEqualTo(real.toString());
        assertThat(second).as("第二次 = 冻结表命中，值不变（单一写入路径 ⇒ 无第二种终态）").isEqualTo(first);
        assertThat(sessionQueries.get()).as("F1：冻结命中后不得重查 DB（resolver 只被调用 1 次）").isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 读数 6：NFD 情形的**分类**（归一化下沉是「把静默坏值变显式失败」还是「引入新错」）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 归一化生效前 {@code getCwd} 的 L2 分支（代码事实静态复现，无需回滚代码）：
     * <pre>
     *   if (boundProject != null) {
     *       if (isValidDirectory(boundProject))   // ← 对 NFD 原值 = true（目录确实存在）
     *           return normalizeCwd(boundProject); // ← 但返回的是 NFC 形态 = **磁盘上不存在**
     *   }
     * </pre>
     * ⇒ 对「NFD 名登记的项目」，归一化生效前 cwd 域会**返回一个不存在的路径**（比 fail-loud 更坏：
     * 下游文件工具随后 ENOENT）。归一化下沉把这一情形改为「归一化后无效 ⇒ 不给出绑定」= 显式失败。
     * <p>⇒ 分类结论：这是<b>把静默坏值变成显式失败</b>，不是归一化引入的新错。
     */
    @Test
    @DisplayName("读数6：NFD 项目在归一化生效前 cwd 域返回的是**不存在的路径**（静态复现旧 L2 分支）")
    void reading6_nfdWasAlreadyBrokenBeforeNormalization() {
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

        System.out.println("[Step3 读数6] raw          = " + raw);
        System.out.println("[Step3 读数6] normalizeCwd = " + normalized);
        System.out.println("[Step3 读数6] raw 是目录?   = " + CwdResolution.isValidDirectory(raw));
        System.out.println("[Step3 读数6] 归一值是目录? = " + CwdResolution.isValidDirectory(normalized));

        // 旧 L2 分支会「进来」（原值有效）……
        assertThat(CwdResolution.isValidDirectory(raw))
            .as("旧 L2 的 isValidDirectory(原值) = true ⇒ 旧代码会走进该分支并直接返回 normalizeCwd(原值)")
            .isTrue();
        // ……但返回的归一值在磁盘上不存在 ⇒ 旧行为 = 返回坏路径（静默）
        assertThat(CwdResolution.isValidDirectory(normalized))
            .as("⭐ 归一值在磁盘上不存在 ⇒ 归一化生效前 cwd 域返回的是**坏路径**（下游 ENOENT），"
                + "归一下沉改为「不给出绑定」= fail-loud ⇒ 是修坏值不是引入新错")
            .isFalse();
    }
}
