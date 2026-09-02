package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li><b>阶段 4 单调用点</b>：{@code executeExtractMemoriesAndAutoDream(} 全文件恰好 1 处
 *       （in-loop stop hook 评估段，:5205）；</li>
 *   <li><b>§14 分支无阶段 4</b>：两个 {@code executeStopHooksCollecting} 调用点之间的 §14
 *       区间（后调用点 = 循环退出后异常路径 :5497）不含阶段 4 调用——§14 仅执行 hooks；</li>
 *   <li><b>in-loop 分支含阶段 4</b>：第一个 executeStopHooksCollecting 调用点（:5222）之前
 *       的 in-loop 区间含阶段 4（CC stopHooks.ts:149 fire-and-forget 在 hook 执行前）；</li>
 *   <li><b>s09 不双触发</b>：退出处理段注释「不再重复调用 executeExtractMemoriesAndAutoDream
 *       （避免双触发）」在文件尾部（s09 区域）存在。</li>
 * </ol>
 */
@DisplayName("[E4-4] 阶段 4 §14 互斥性：逐分支单调用点 + 无双触发")
class E4Phase4Section14MutualExclusionTest {

    private static final Path LAL = Path.of(
        "F:/nexusai-backend/.worktrees/impl-mv2-h/src/main/java/com/nexusai/application/agent/LlmAgentLoop.java");

    @Test
    @DisplayName("阶段 4 全文件恰好一个调用点（in-loop）；§14 分支无阶段 4；s09 不双触发")
    void phase4_singleCallSite_section14Excluded() throws Exception {
        List<String> lines = Files.readAllLines(LAL);

        // 1) 阶段 4 单调用点
        List<Integer> phase4Sites = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("executeExtractMemoriesAndAutoDream(")) {
                phase4Sites.add(i + 1);
            }
        }
        assertThat(phase4Sites)
            .as("阶段 4 调用点恰好 1 处（in-loop :5205；s09 注释提及处不算调用）")
            .hasSize(1);

        // 2) executeStopHooksCollecting 两处（in-loop :5222 与 §14 :5497）
        List<Integer> hookSites = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("ctx.hookRegistry().executeStopHooksCollecting(")) {
                hookSites.add(i + 1);
            }
        }
        assertThat(hookSites).as("两处 stop hook 评估（in-loop + §14）").hasSize(2);
        int inLoopHook = hookSites.get(0);
        int section14Hook = hookSites.get(1);

        // 3) in-loop 分支：阶段 4 在 hook 执行前（CC stopHooks.ts:149 fire-and-forget 前置）
        int phase4Line = phase4Sites.get(0);
        assertThat(phase4Line)
            .as("阶段 4 位于 in-loop hook 评估之前（CC fire-and-forget 在 hook 执行前）")
            .isLessThan(inLoopHook);
        // 4) §14 分支：两 hook 评估点之间 + §14 之后均无阶段 4 调用（互斥）
        assertThat(phase4Line)
            .as("阶段 4 不在 §14 区间内（§14 仅执行 hooks，不触发 extract/dream）")
            .isLessThan(section14Hook);
        boolean phase4InSection14 = false;
        for (int i = section14Hook - 1; i < lines.size(); i++) {
            if (lines.get(i).contains("executeExtractMemoriesAndAutoDream(")) {
                phase4InSection14 = true;
                break;
            }
        }
        assertThat(phase4InSection14)
            .as("§14 段及其后（s09）无阶段 4 调用——互斥成立，无双触发")
            .isFalse();

        // 5) s09 双触发回避注释存在（设计意图文档化；"不再重复调用" 与 "executeExtractMemories..." 分处相邻行）
        boolean s09Comment = false;
        for (int i = 0; i < lines.size() - 1; i++) {
            String joined = lines.get(i) + "\n" + lines.get(i + 1);
            if (joined.contains("不再重复调用") && joined.contains("executeExtractMemoriesAndAutoDream")) {
                s09Comment = true;
                break;
            }
        }
        assertThat(s09Comment).as("s09 注释声明不重复调用（避免双触发）").isTrue();
    }
}
