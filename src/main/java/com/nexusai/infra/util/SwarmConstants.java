package com.nexusai.infra.util;

import java.util.function.IntSupplier;

/**
 * SwarmConstants · 对齐 CC utils/swarm/constants.ts.
 */
public final class SwarmConstants {

    public static final String TEAM_LEAD_NAME = "team-lead";
    public static final String SWARM_SESSION_NAME = "claude-swarm";
    public static final String SWARM_VIEW_WINDOW_NAME = "swarm-view";
    public static final String HIDDEN_SESSION_NAME = "claude-hidden";

    public static final String TEAMMATE_COMMAND_ENV_VAR = "CLAUDE_CODE_TEAMMATE_COMMAND";
    public static final String TEAMMATE_COLOR_ENV_VAR = "CLAUDE_CODE_AGENT_COLOR";
    public static final String PLAN_MODE_REQUIRED_ENV_VAR = "CLAUDE_CODE_PLAN_MODE_REQUIRED";

    private SwarmConstants() {}

    /** Get socket name for external swarm sessions. Includes PID for isolation. */
    public static String getSwarmSocketName(IntSupplier pidSupplier) {
        int pid = pidSupplier == null ? 0 : pidSupplier.getAsInt();
        return "claude-swarm-" + pid;
    }
}
