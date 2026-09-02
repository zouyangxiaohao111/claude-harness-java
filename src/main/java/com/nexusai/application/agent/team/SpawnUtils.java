package com.nexusai.application.agent.team;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spawn Utils · 对齐 CC utils/swarm/spawnUtils.ts.
 *
 * <p>FIX-SWARM-MISC: inherited CLI flags + env 构造 spawn 命令.
 *
 * <p>L1 行为: 给定 base CLI args + 用户指定 flags, 构造 spawn 子进程的命令行.
 *
 * @deprecated 本期未启用，教学版 stub，参见 探查/subagent/本期不上生产的模块.md
 */
public final class SpawnUtils {

    private SpawnUtils() {}

    /** 过滤掉不让继承的 flags (如 --verbose / --debug). */
    private static final List<String> BLOCKED_FLAGS = List.of(
        "--repl", "--print", "--output-format", "--input-format");

    public static List<String> buildSpawnArgs(List<String> baseArgs, List<String> userFlags,
                                              List<String> inheritedEnv) {
        List<String> args = new ArrayList<>(baseArgs);
        for (String flag : userFlags) {
            if (!BLOCKED_FLAGS.contains(flag)) {
                args.add(flag);
            }
        }
        return args;
    }

    public static List<String> filterEnv(List<String> env) {
        if (env == null) return List.of();
        return env.stream()
            .filter(e -> !e.startsWith("CLAUDE_CODE_PARENT="))
            .toList();
    }
}