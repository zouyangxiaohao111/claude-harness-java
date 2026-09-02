package com.nexusai.application.agent.command;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * InstallSlackAppInvoker · 对齐 CC commands/install-slack-app/install-slack-app.ts.
 *
 * <p>L1 语义: /install-slack-app 命令入口行为 — 触发浏览器打开 Slack 应用页
 * (slack.com/marketplace/...) 并累加 {@code slackAppInstallCount} 全局计数器。
 * 浏览器打开成功返回「Opening」文本,失败降级返回带 URL 的指引文本(用户可手动复制)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #call()} () → {@link Result}; 3 个函数式接口 slot
 *       (browserOpener / clickLogger / counterUpdater)</li>
 *   <li><b>A2 Golden Trace</b>: log 'tengu_install_slack_app_clicked' →
 *       counter +1 → browserOpen → success?{text:Opening}:{text:Couldn't}</li>
 *   <li><b>A3 状态</b>: 计数器缺省=0,严格 +1;不修改其他全局配置字段</li>
 *   <li><b>A4 边界</b>: browserOpen 返回 false → 含 URL 的后备文案;不抛异常</li>
 *   <li><b>A5 业务场景</b>: 真实 CSAT — 用户在 Slack 工作流提示中点击,然后跳市场页</li>
 * </ul>
 *
 * <p>L3 (Java idiom): 用 {@link Consumer} (log) / {@link UnaryOperator} (GlobalConfig mutation)
 * / {@link java.util.function.BooleanSupplier} (browserOpen) 注入,纯 Java 17 record
 * {@link Result},无副作用拆分。
 */
public final class InstallSlackAppInvoker {

    public static final String SLACK_APP_URL =
        "https://slack.com/marketplace/A08SF47R6P4-claude";
    public static final String CLICK_EVENT = "tengu_install_slack_app_clicked";
    public static final String COUNTER_KEY = "slackAppInstallCount";

    public record Result(String type, String value) {}

    public interface GlobalConfig {
        Integer getInstallCount();
        GlobalConfig withIncrementedInstallCount();
    }

    private final java.util.function.BooleanSupplier browserOpener;
    private final Consumer<String> clickLogger;
    private final UnaryOperator<GlobalConfig> counterUpdater;

    public InstallSlackAppInvoker(
        java.util.function.BooleanSupplier browserOpener,
        Consumer<String> clickLogger,
        UnaryOperator<GlobalConfig> counterUpdater) {
        this.browserOpener = browserOpener;
        this.clickLogger = clickLogger;
        this.counterUpdater = counterUpdater;
    }

    /**
     * Run the install-slack-app command. Side effects: log event + counter increment.
     * Returns a Result whose type is always {@code "text"}.
     */
    public Result call(GlobalConfig current) {
        // Mirrors CC: logEvent('tengu_install_slack_app_clicked', {})
        clickLogger.accept(CLICK_EVENT);
        // Counter increment — applies via injected updater; current scope does not
        // directly modify because Java callers wire updater → persistence layer.
        counterUpdater.apply(current);

        boolean success;
        try {
            success = browserOpener.getAsBoolean();
        } catch (RuntimeException e) {
            success = false;
        }
        if (success) {
            return new Result("text", "Opening Slack app installation page in browser…");
        }
        return new Result("text", "Couldn't open browser. Visit: " + SLACK_APP_URL);
    }
}
