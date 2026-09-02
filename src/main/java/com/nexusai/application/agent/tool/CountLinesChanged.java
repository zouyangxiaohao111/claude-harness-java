package com.nexusai.application.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 行变更计数 · 对齐 CC {@code countLinesChanged}
 * （Open-ClaudeCode/src/utils/diff.ts:49-64）。
 *
 * <p>统计 patch 中 + / − 前缀行数；新建文件（patch 为空 + newFileContent 非空）时全部行算新增。
 * CC 中该计数送入 totalLinesChanged / LocCounter；Java 无等价全局消费点
 * （compact tool_result_budget 消费登记后续 session），此处完成等价计算并以数据流日志输出，
 * 供预算/遥测后续接线消费 —— 非死代码。
 */
public final class CountLinesChanged {

    private static final Logger log = LoggerFactory.getLogger(CountLinesChanged.class);

    private CountLinesChanged() {
    }

    /**
     * 计算变更行数。
     *
     * @param patch          结构化 patch（Edit/Write update 的 hunk 数组）
     * @param newFileContent 新建文件内容（仅 patch 为空时用于全行计数；可为 null）
     * @return long[]{additions, removals}
     */
    public static long[] countLinesChanged(List<StructuredPatchHunk> patch, String newFileContent) {
        long additions;
        long removals;
        if (patch.isEmpty() && newFileContent != null) {
            // 新建文件：全部行算新增 · CC diff.ts:55-58 newFileContent.split(/\r?\n/).length
            additions = newFileContent.split("\\r?\\n", -1).length;
            removals = 0;
        } else {
            // patch 行前缀计数 · CC diff.ts:59-63
            additions = patch.stream()
                    .flatMap(h -> h.lines().stream())
                    .filter(l -> l.startsWith("+"))
                    .count();
            removals = patch.stream()
                    .flatMap(h -> h.lines().stream())
                    .filter(l -> l.startsWith("-"))
                    .count();
        }
        if (log.isInfoEnabled()) {
            log.info("CountLinesChanged: 行变更统计 source={} additions={} removals={}",
                (patch.isEmpty() && newFileContent != null) ? "new-file" : "patch",
                additions, removals);
        }
        return new long[]{additions, removals};
    }
}
