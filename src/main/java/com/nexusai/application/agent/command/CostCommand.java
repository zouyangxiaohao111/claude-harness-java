package com.nexusai.application.agent.command;

import java.util.function.Supplier;

/**
 * Cost 展示命令 · 对齐 CC commands/cost/cost.ts:6-24 call.
 *
 * <p>L1 语义: Claude.ai 订阅用户 → 显示 subscription/overage 文案 + (ant 内部) 总费用;
 *            普通用户 → 仅显示总费用 (CC formatTotalCost).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `execute(SubscriptionEnv) → CommandResult` 签名</li>
 *   <li><b>A2 Golden Trace</b>: 订阅用户 overage → "...switch you back..."; 订阅用户 normal → "...subscription..."; 普通 → formatTotalCost</li>
 *   <li><b>A3</b>: ant-only block 仅 USER_TYPE=ant 时追加 [ANT-ONLY] 行</li>
 *   <li><b>A4</b>: totalCost() supplier 抛错 → catch 返回 "[error: ...]" 文案</li>
 *   <li><b>A5</b>: 真实场景 — 订阅 + overage + ant → "...switch you back...\\n\\n[ANT-ONLY]..."</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Supplier&lt;String&gt; totalCost 注入 (CC formatTotalCost); env flag 注入 (CC USER_TYPE 全局读).
 */
public class CostCommand {

    public record SubscriptionEnv(
        boolean isClaudeAISubscriber,
        boolean isUsingOverage,
        boolean isAntUser,
        Supplier<String> totalCost
    ) {}

    public record CommandResult(String type, String value) {
        public static CommandResult text(String value) { return new CommandResult("text", value); }
    }

    public CommandResult execute(SubscriptionEnv env) {
        String value;
        if (env.isClaudeAISubscriber()) {
            value = env.isUsingOverage()
                ? "You are currently using your overages to power your NexusAI usage. " +
                  "We will automatically switch you back to your subscription rate limits when they reset"
                : "You are currently using your subscription to power your NexusAI usage";
            if (env.isAntUser()) {
                String cost = formatCost(env);
                value += "\n\n[ANT-ONLY] Showing cost anyway:\n " + cost;
            }
        } else {
            value = formatCost(env);
        }
        return CommandResult.text(value);
    }

    /** 工具: 调 totalCost supplier, 失败 fallback "[error: ...]". */
    private static String formatCost(SubscriptionEnv env) {
        try {
            return env.totalCost().get();
        } catch (Exception e) {
            return "[error: " + e.getMessage() + "]";
        }
    }
}