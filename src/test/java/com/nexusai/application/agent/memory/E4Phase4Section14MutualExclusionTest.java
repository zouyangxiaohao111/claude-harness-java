package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4-4（IMP-MV2-25）· 阶段 4 §14 互斥性逐分支验证（E-E3-19 / OPD-MM-38）。
 *
 * <p>探查证据（raw/retrieval-inject/探查-mm-e3-wiring-loop.md §13 R4）：阶段 4（extract/dream
 * 触发）与 §14 stop hook 路径的执行互斥性未逐分支验证——「已 grep 全文件仅一个调用点，但 §14
 * 与 in-loop 两处 stop hook 评估的执行互斥性未逐分支验证」。本测试对 LlmAgentLoop.java 源码
 * 逐分支断言：
 * <ol>
 *   <li><b>阶段 4 单调用点</b>：{@code executeExtractMemoriesAndAutoDream(} 在**剥行注释后的代码行**
 *       中恰好 1 处（in-loop stop hook 评估段）；</li>
 *   <li><b>§14 分支无阶段 4</b>：两个 {@code ctx.hookRegistry().executeStopHooksCollecting(}
 *       调用点之间的 §14 区间及其后（含 s09）不含阶段 4 调用——§14/s09 不得重复触发；</li>
 *   <li><b>in-loop 分支含阶段 4</b>：阶段 4 触发点位于 in-loop stop hook 评估**之前**
 *       （CC 真源 Open-ClaudeCode/src/query/stopHooks.ts:149 fire-and-forget 在 hook 执行前）。</li>
 * </ol>
 *
 * <p><b>⛔ 能力边界（如实声明，不得据此宣称行为已验证）</b>：本类是对 14552 行源码的
 * <b>结构锚定</b>（字符串计数 + 相对顺序），<b>不是行为测试</b>——它能发现「阶段 4 调用点被删除 /
 * 被复制 / 移到 §14 之后」，但不能发现「调用点仍在原地而语义已变」。行为面由
 * {@code StopHooksPipelineTest}（阶段 4 的 agentId 门控 / 模块与运行时开关 / bareMode / memoryDir 显式跳过）
 * 与 {@code LlmAgentLoopAbnormalExitMemoryExtractSkipTest}（DEC-RV-05 排除契约）承载。
 *
 * <p><b>本次修正（三类，均为「让锚点承重」而非「让测试变绿」）</b>：
 * <ol>
 *   <li><b>文件定位不再依赖 cwd，也不再硬编码盘符</b>：原实现写死
 *       {@code F:/nexusai-backend/.worktrees/impl-mv2-h/...}（该盘/路径不存在）⇒ 恒红。
 *       现改为「由类加载位置（code source）上溯到含 {@code pom.xml} 的模块根 + 仓内相对路径」，
 *       与 CC 同性质手法（真源 claude-code-best/src/utils/model/__tests__/providerGates.test.ts:5
 *       {@code const RUNNER_ABS = resolve(__dirname, 'providerGates.runner.ts')}，框架为 bun:test）。
 *       定位失败一律 fail-loud（⛔ 不回落 cwd、不静默跳过）。</li>
 *   <li><b>改为「剥行注释后」计数</b>：原实现直接扫原始行，注释里出现同名文本会被计入调用点。
 *       剥注释后，注释里的同名文本不再冒充调用点（增益已用反向实验量化：把真实调用点整行注释掉，
 *       原始计数版仍为 1、剥注释版为 0 ⇒ 只有剥注释版能发现「调用点已被注释掉」）。</li>
 *   <li><b>删除原断言 #5「注释锁注释」</b>：它断言「一段写着『不再重复调用』的注释存在」——该断言与
 *       被测行为无因果（注释留着而调用点被复制、或注释删掉而行为不变，它都不响）⇒ 零鉴别力。
 *       其意图（s09 不双触发）由断言 #1 + #2 承接：调用点被复制到 §14/s09 会让 #1 计数 &gt; 1，
 *       并让 #2 的「§14 及其后无阶段 4」断言失败。</li>
 * </ol>
 *
 * <p><b>行号策略</b>：本类只锚「计数 + 相对顺序」，<b>不锚绝对行号</b>（LlmAgentLoop 处于重构中，
 * 行号必然漂移；原 javadoc 里的 :5205/:5222/:5497 均已漂移到 :8274/:8301/:8640）。
 */
@DisplayName("[E4-4] 阶段 4 §14 互斥性：逐分支单调用点 + 无双触发（LlmAgentLoop 源码结构锚定）")
class E4Phase4Section14MutualExclusionTest {

    /** 模块内相对路径（相对含 pom.xml 的模块根）。 */
    private static final String LAL_RELATIVE =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    /** 源码锚点：模块根（由 code source 上溯）× 相对路径。 */
    private static final Path LAL = resolveLal();

    private static Path resolveLal() {
        try {
            Path codeSource = Path.of(E4Phase4Section14MutualExclusionTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path dir = codeSource; dir != null; dir = dir.getParent()) {
                if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                    Path source = dir.resolve(LAL_RELATIVE);
                    if (!Files.isRegularFile(source)) {
                        throw new IllegalStateException(
                            "模块根 " + dir + " 内未找到被测源码 " + LAL_RELATIVE
                                + "（锚点必须 fail-loud：⛔ 不回落 cwd、不静默跳过）");
                    }
                    return source;
                }
            }
            throw new IllegalStateException("从 code source " + codeSource
                + " 上溯未找到含 pom.xml 的模块根（锚点必须 fail-loud：⛔ 不回落 cwd）");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                "无法从 code source 定位源码锚点（锚点必须 fail-loud：⛔ 不回落 cwd）", e);
        }
    }

    /**
     * 剥掉行注释（自第一个 {@code //} 起整行截断）后的代码行。
     *
     * <p>安全性为实证而非推定：LlmAgentLoop.java 内 {@code //} 均不在字符串字面量中
     * （{@code grep -c '://'} = 0；逐行「引号配对 + 首次 {@code //} 位置」检查得 odd-quote 行 = 0
     * ⇒ 不存在「字符串内部的 {@code //}」）⇒ 行级截断不会误删代码。
     */
    private static List<String> codeLines(List<String> lines) {
        List<String> code = new ArrayList<>(lines.size());
        for (String line : lines) {
            int comment = line.indexOf("//");
            code.add(comment < 0 ? line : line.substring(0, comment));
        }
        return code;
    }

    @Test
    @DisplayName("阶段 4 剥注释后全文件恰好一个调用点（in-loop）；§14 及其后无阶段 4 —— 无双触发")
    void phase4_singleCallSite_section14Excluded() throws Exception {
        List<String> code = codeLines(Files.readAllLines(LAL));

        // 1) 阶段 4 单调用点（剥行注释后计数 ⇒ 注释里的同名文本不再是调用点）
        List<Integer> phase4Sites = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).contains("executeExtractMemoriesAndAutoDream(")) {
                phase4Sites.add(i + 1);
            }
        }
        assertThat(phase4Sites)
            .as("剥注释后阶段 4 调用点恰好 1 处（in-loop；s09 注释提及处不算调用）")
            .hasSize(1);

        // 2) executeStopHooksCollecting 两处（in-loop 与 §14）
        List<Integer> hookSites = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).contains("ctx.hookRegistry().executeStopHooksCollecting(")) {
                hookSites.add(i + 1);
            }
        }
        assertThat(hookSites).as("两处 stop hook 评估（in-loop + §14）").hasSize(2);
        int inLoopHook = hookSites.get(0);
        int section14Hook = hookSites.get(1);

        // 3) in-loop 分支：阶段 4 在 hook 执行前（CC Open-ClaudeCode/src/query/stopHooks.ts:149
        //    fire-and-forget 前置）
        int phase4Line = phase4Sites.get(0);
        assertThat(phase4Line)
            .as("阶段 4 位于 in-loop hook 评估之前（CC fire-and-forget 在 hook 执行前）")
            .isLessThan(inLoopHook);
        // 4) §14 分支：阶段 4 不在 §14 及其后（互斥 —— §14/s09 仅执行 hooks，不触发 extract/dream）
        assertThat(phase4Line)
            .as("阶段 4 不在 §14 区间内（§14 仅执行 hooks，不触发 extract/dream）")
            .isLessThan(section14Hook);
        boolean phase4InSection14 = false;
        for (int i = section14Hook - 1; i < code.size(); i++) {
            if (code.get(i).contains("executeExtractMemoriesAndAutoDream(")) {
                phase4InSection14 = true;
                break;
            }
        }
        assertThat(phase4InSection14)
            .as("§14 段及其后（s09）无阶段 4 调用——互斥成立，无双触发")
            .isFalse();
    }
}
