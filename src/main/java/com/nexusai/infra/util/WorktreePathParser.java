package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * WorktreePathParser · 对齐 CC utils/getWorktreePaths.ts:41-69.
 *
 * <p>L1 语义: 解析 {@code git worktree list --porcelain} 输出,提取按当前 worktree 优先 + 字典序的路径列表。
 * 纯函数 — 不执行任何 I/O。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #parse(String, String)} (porcelainOutput, cwd) → {@code List<String>}</li>
 *   <li><b>A2 Golden Trace</b>: 首行 {@code worktree /path};cwd 匹配 → 当前优先 + 字典序其他;空/无 cwd → 字典序排</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: 空 output → [];cwd=null → 字典序排 (无优先);单 worktree → 1 entry</li>
 *   <li><b>A5 业务场景</b>: 主线程从 git 获取所有 worktree 路径,排当前 worktree 优先</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS string.split('\n') + filter+map → Java String.split + stream;
 * TS NFC normalize → Java Normalizer.normalize (NFC);
 * TS localeCompare → Java Collator + Comparator。
 */
public final class WorktreePathParser {

    private static final char SEP = java.io.File.separatorChar;

    private WorktreePathParser() {}

    /**
     * Parse porcelain output of {@code git worktree list --porcelain}.
     *
     * @param porcelain stdout output
     * @param cwd current working directory; null skips current-worktree priority logic
     * @return ordered list: current worktree first (if matched), others alphabetized
     */
    public static List<String> parse(String porcelain, String cwd) {
        if (porcelain == null || porcelain.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> paths = new ArrayList<>();
        for (String line : porcelain.split("\n")) {
            String prefix = "worktree ";
            if (line.startsWith(prefix)) {
                String path = line.substring(prefix.length());
                paths.add(java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC));
            }
        }
        String current = null;
        if (cwd != null) {
            for (String p : paths) {
                if (cwd.equals(p) || cwd.startsWith(p + SEP)) {
                    current = p;
                    break;
                }
            }
        }
        List<String> others = new ArrayList<>();
        for (String p : paths) {
            if (!p.equals(current)) others.add(p);
        }
        others.sort(Comparator.naturalOrder());
        List<String> result = new ArrayList<>();
        if (current != null) result.add(current);
        result.addAll(others);
        return result;
    }
}
