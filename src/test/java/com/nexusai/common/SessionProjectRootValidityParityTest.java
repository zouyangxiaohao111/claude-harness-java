package com.nexusai.common;

import com.nexusai.application.agent.agent.CwdResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S2 · F-24 验证 ③ · 2026-09-14] <b>差异 A 门</b>：两份「项目根有效性判据」对同一输入必须<b>全等</b>。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：本仓有<b>两份</b>独立实现与同一 sessionId → projectRoot 链路
 * 相关（{@link SessionProjectRoot} 的冻结表回源解析器 = {@code ToolRegistrationConfig
 * #sessionProjectRootResolver}，与 {@code LlmAgentLoop.tryResolveBoundProjectFromDb} = B′ 兜底第二链，
 * 见 {@link SessionProjectRoot} 类 javadoc「残差 R-DB」）。两条链各带一份「无效项目根不得冒充项目根」
 * 判据：
 * <ul>
 *   <li>{@code SessionProjectRoot.isValidProjectRoot}（private static，{@code common}）</li>
 *   <li>{@link CwdResolution#isValidDirectory(String)}（public static，{@code application}）</li>
 * </ul>
 * 「同一能力两套判据」是本仓反复复发的病（backend/CLAUDE.md 规则七）。本用例把「两份拷贝必须同步」
 * 永久钉住：<b>一旦有人只改其中一处，本用例必红</b>。
 *
 * <p><b>⛔ 本用例不用源码字符串扫描</b>（{@code source.indexOf} 类字面断言）——本仓已多次栽在
 * 「声称守护 X、实际守不住」的假守卫上；此处改为<b>经公共行为面</b>对照：
 * {@code setForSession}（接受 ⇔ 通过内部有效性判据）↔ {@code CwdResolution.isValidDirectory}。
 *
 * <p><b>RED（反向实验 · 有鉴别力）</b>：把 {@code SessionProjectRoot.isValidProjectRoot} 的
 * {@code p.isAbsolute() && Files.isDirectory(p)} 改成只判 {@code p.isAbsolute()} ⇒ 本用例在
 * 「相对路径」与「绝对但目录不存在」两格红；把 {@code CwdResolution.isValidDirectory} 同样放宽 ⇒
 * 同样红。
 */
@DisplayName("[S2 F-24] 差异 A 门：SessionProjectRoot 有效性判据 ≡ CwdResolution.isValidDirectory")
class SessionProjectRootValidityParityTest {

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        // 回源解析器是 static 注入口：本类不注册，但保证跨类不残留（others 可能注册过）
        SessionProjectRoot.setDbResolver(null);
    }

    /**
     * 待对照的输入矩阵。
     *
     * <p><b>覆盖理由（逐格对应两条判据的一个分支）</b>：
     * <ol>
     *   <li>{@code null}/{@code ""}/{@code "   "} = 「无值」（{@code isBlank} 早返回分支）；</li>
     *   <li>{@code "relative/path"} = 相对路径（绑定「抓包流程」这类脏数据的真实形态）⇒
     *       {@code isAbsolute=false} 分叉；</li>
     *   <li>绝对但不存在 = 项目被删 / 盘符不存在 ⇒ {@code Files.isDirectory=false} 分叉；</li>
     *   <li><b>文件而非目录</b>（绝对 + 存在 + 非目录）= 两条判据最易漂移的一格；</li>
     *   <li><b>{@code "\t\n"}</b> = {@code isBlank()==true} 但非空格代理（补 {@code isBlank} 分支的第二种输入）；</li>
     *   <li>⭐ <b>{@code "C:/bad<name"}</b> 与 <b>含 NUL 的路径</b> = 令 {@code Path.of} 抛
     *       {@code InvalidPathException} ⇒ 走两条判据各自的 {@code catch → false} 分支
     *       （<b>最容易只改一处而漏改另一处</b>的分支，原矩阵完全未覆盖）；</li>
     *   <li>⭐ <b>symlink 目录</b>（best-effort）= 两条判据都应判「有效」（link 存在）
     *       —— 与差异 B 实验（读数 3）交叉印证：判据一致，差异只在**冻结字符串**；</li>
     *   <li>⭐ <b>NFD 目录的 NFC 形态</b>（best-effort）= 两条判据都应判「无效」（本 FS 无该形态）
     *       —— 与差异 B 实验（读数 2，链 2 因此直接放弃）交叉印证。</li>
     * </ol>
     * <p>⛔ 本矩阵不依赖测试执行顺序、不读源码文本。
     */
    private static java.util.List<String> matrix(Path tempDir, Path aFile, Path symlinkDir, String nfcFormOfNfd) {
        java.util.List<String> inputs = new java.util.ArrayList<>();
        inputs.add(null);
        inputs.add("");
        inputs.add("   ");
        inputs.add("\t\n");
        inputs.add("relative/path");
        inputs.add(tempDir.resolve("does-not-exist").toString());  // 绝对但不存在
        inputs.add(tempDir.toString());                            // 真目录
        inputs.add(aFile.toString());                              // 绝对存在但非目录
        inputs.add("C:/bad<name/path");                            // Path.of 抛 InvalidPathException
        inputs.add("C:/bad\u0000nul");                             // Path.of 抛 InvalidPathException（NUL）
        if (symlinkDir != null) {
            inputs.add(symlinkDir.toString());                     // link 存在 ⇒ 两判据都应 true
        }
        if (nfcFormOfNfd != null) {
            inputs.add(nfcFormOfNfd);                              // NFC 形态不存在 ⇒ 两判据都应 false
        }
        return inputs;
    }

    @Test
    @DisplayName("同一输入矩阵上：会话绑定「被接受」⇔ CwdResolution.isValidDirectory 为 true（含异常分支逐格全等）")
    void validityPredicatesAgreeOnEveryInput(@TempDir Path tempDir) throws Exception {
        Path aFile = tempDir.resolve("a-file.txt");
        Files.writeString(aFile, "x");

        // best-effort 两格（构造不出来就跳过该格，而不是假绿）
        Path symlinkDir = null;
        Path target = Files.createDirectories(tempDir.resolve("parity-target"));
        Path link = tempDir.resolve("parity-link");
        try {
            if (Files.createSymbolicLink(link, target) != null) {
                symlinkDir = link;
            }
        } catch (Exception ignored) {
            // 无权限 → 该格不进矩阵（下方 as() 会显示输入总数，读者可核对覆盖）
        }
        String nfcFormOfNfd = null;
        try {
            Path nfd = Files.createDirectories(tempDir.resolve("cafe\u0301-parity"));
            String nfdStr = nfd.toString();
            String nfc = java.text.Normalizer.normalize(nfdStr, java.text.Normalizer.Form.NFC);
            if (!nfc.equals(nfdStr)) {
                nfcFormOfNfd = nfc;
            }
        } catch (Exception ignored) {
            // 同上
        }

        java.util.List<String> inputs = matrix(tempDir, aFile, symlinkDir, nfcFormOfNfd);
        for (int i = 0; i < inputs.size(); i++) {
            String candidate = inputs.get(i);
            String sid = "sess-parity-" + i;
            // 经公共行为面观测「SessionProjectRoot 的有效性判据」：
            //   接受 ⇒ 冻结表命中 ⇒ lookup(...).projectRoot() 非 null；
            //   拒绝 ⇒ 冻结表 miss（⇒ 回源；本类未注册解析器 ⇒ resolutionFailed）⇒ projectRoot() == null。
            SessionProjectRoot.setForSession(sid, candidate);
            boolean sessionProjectRootAccept = SessionProjectRoot.lookup(sid).projectRoot() != null;

            boolean cwdResolutionAccept = CwdResolution.isValidDirectory(candidate);

            assertThat(sessionProjectRootAccept)
                .as("输入[%d/%d]=%s：两份判据必须全等（差异 A 门）。"
                    + "SessionProjectRoot 接受=%s 而 CwdResolution.isValidDirectory=%s ⇒ "
                    + "有人只改了一份拷贝（后端 CLAUDE.md 规则七：同一能力两套判据）",
                    i, inputs.size(), candidate, sessionProjectRootAccept, cwdResolutionAccept)
                .isEqualTo(cwdResolutionAccept);
        }
    }

    @Test
    @DisplayName("矩阵自证（正向对照）：真目录两判据都为 true；相对路径两判据都为 false —— 证明上一条不是「恒等 false」")
    void matrixIsDiscriminating(@TempDir Path tempDir) {
        // 若两份判据都退化成「恒 false」（例如 Files.isDirectory 被删成 false），上一条会全绿 ⇒ 恒真断言。
        // 本用例提供正向/反向两格，保证上一条的「全等」有产点。
        assertThat(CwdResolution.isValidDirectory(tempDir.toString()))
            .as("正向对照：真目录必须被判有效（否则上一条的「全等」可能来自双方恒 false）")
            .isTrue();
        SessionProjectRoot.setForSession("sess-parity-pos", tempDir.toString());
        assertThat(SessionProjectRoot.lookup("sess-parity-pos").projectRoot())
            .as("正向对照：真目录必须被 SessionProjectRoot 接受")
            .isNotNull();

        assertThat(CwdResolution.isValidDirectory("relative/path"))
            .as("反向对照：相对路径必须被判无效")
            .isFalse();
        SessionProjectRoot.setForSession("sess-parity-neg", "relative/path");
        assertThat(SessionProjectRoot.lookup("sess-parity-neg").projectRoot())
            .as("反向对照：相对路径必须被 SessionProjectRoot 拒绝")
            .isNull();
    }
}
