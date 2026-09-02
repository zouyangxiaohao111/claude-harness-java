package com.nexusai.infra.util;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * UndercoverCheck · 对齐 CC utils/undercover.ts.
 *
 * <p>L1 语义: undercover 模式检测 — ant 用户公开 repo 时附加 safety instructions + strip attribution。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 静态方法 (isUndercover + getUndercoverInstructions + shouldShowUndercoverAutoNotice) + UserTypeSupplier</li>
 *   <li><b>A2 Golden Trace</b>: USER_TYPE=ant + CLAUDE_CODE_UNDERCOVER=1 → true;USER_TYPE=ant + repo internal → false;USER_TYPE!=ant → false;getUndercoverInstructions ant→模板 + 非 ant→''</li>
 *   <li><b>A3 纯函数</b>: 注入式 supplier (testable)</li>
 *   <li><b>A4 边界</b>: USER_TYPE null→false;repo class null→true (保守 default)</li>
 *   <li><b>A5 业务场景</b>: ant user push to public GitHub → commit prompt 添加 UNDERCOVER MODE + strip Co-Authored-By</li>
 * </ul>
 *
 * <p>L3 升级: TS process.env 索引 → Java injected Supplier;
 * TS string.startsWith → Java startsWith;
 * TS function feature gate → Java Predicate injected.
 */
public final class UndercoverCheck {

    public static final String USER_TYPE_ANT = "ant";
    public static final String REPO_CLASS_INTERNAL = "internal";

    public static final String UNDERCOVER_INSTRUCTIONS =
        "## UNDERCOVER MODE — CRITICAL\n\n"
        + "You are operating UNDERCOVER in a PUBLIC/OPEN-SOURCE repository. Your commit\n"
        + "messages, PR titles, and PR bodies MUST NOT contain ANY Anthropic-internal\n"
        + "information. Do not blow your cover.\n\n"
        + "NEVER include in commit messages or PR descriptions:\n"
        + "- Internal model codenames (animal names like Capybara, Tengu, etc.)\n"
        + "- Unreleased model version numbers (e.g., opus-4-7, sonnet-4-8)\n"
        + "- Internal repo or project names (e.g., claude-cli-internal, anthropics/…)\n"
        + "- Internal tooling, Slack channels, or short links (e.g., go/cc, #claude-code-…)\n"
        + "- The phrase \"NexusAI\" or any mention that you are an AI\n"
        + "- Any hint of what model or version you are\n"
        + "- Co-Authored-By lines or any other attribution\n\n"
        + "Write commit messages as a human developer would — describe only what the code\n"
        + "change does.\n\n"
        + "GOOD:\n"
        + "- \"Fix race condition in file watcher initialization\"\n"
        + "- \"Add support for custom CA certs\"\n"
        + "- \"Refactor parser for better error messages\"\n\n"
        + "BAD (never write these):\n"
        + "- \"Fix bug found while testing with Claude Capybara\"\n"
        + "- \"1-shotted by claude-opus-4-6\"\n"
        + "- \"Generated with NexusAI\"\n"
        + "- \"Co-Authored-By: Claude Opus 4.6 <…>\"\n";

    private UndercoverCheck() {}

    public static boolean isUndercover(
        String userType,
        BooleanSupplier undercoverEnvSupplier,
        Supplier<String> repoClassSupplier) {
        if (!USER_TYPE_ANT.equals(userType)) return false;
        if (undercoverEnvSupplier != null && undercoverEnvSupplier.getAsBoolean()) return true;
        // Auto: active unless repo is positively 'internal'
        return repoClassSupplier == null
            || !REPO_CLASS_INTERNAL.equals(repoClassSupplier.get());
    }

    public static String getUndercoverInstructions(String userType) {
        if (!USER_TYPE_ANT.equals(userType)) return "";
        return UNDERCOVER_INSTRUCTIONS;
    }

    public static boolean shouldShowUndercoverAutoNotice(
        String userType,
        BooleanSupplier undercoverEnvSupplier,
        Supplier<String> repoClassSupplier,
        BooleanSupplier hasSeenNoticeSupplier) {
        if (!USER_TYPE_ANT.equals(userType)) return false;
        if (undercoverEnvSupplier != null && undercoverEnvSupplier.getAsBoolean()) return false;
        if (!isUndercover(userType, undercoverEnvSupplier, repoClassSupplier)) return false;
        if (hasSeenNoticeSupplier != null && hasSeenNoticeSupplier.getAsBoolean()) return false;
        return true;
    }
}
