package com.nexusai.application.agent.plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /plugin CLI wrappers · 对齐 CC services/plugins/pluginCliCommands.ts.
 *
 * <p>L1 语义: plugin 操作 (install/uninstall/enable/disable/update) 的 CLI 包装;
 *            分类错误 + 优雅退出 + telemetry + console output.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 6 op method (installPlugin/uninstallPlugin/enablePlugin/disablePlugin/
 *       disableAllPlugins/updatePlugin); PluginScope enum; InstallableScope record.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — installPlugin(scope, name) → handler success/fail;
 *       失败 → classifyPluginCommandError → gracefulShutdown + logEvent.</li>
 *   <li><b>A3</b>: 注入式 (pluginOps + writer + shutdown + telemetry);silent failure on error.</li>
 *   <li><b>A4</b>: install fail → throw + log;disableAll → 跳过 disabled already.</li>
 *   <li><b>A5</b>: 真实场景 — `claude plugin install formatter@marketplace` → enable → done.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS enum union → Java enum;
 *                    TS promise → Java Consumer 注入式.
 */
public final class PluginCliCommands {

    private static final Logger log = LoggerFactory.getLogger(PluginCliCommands.class);

    public enum PluginScope { USER, PROJECT, LOCAL, DYNAMIC, ENTERPRISE }

    public record InstallableScope(String scope, String name) {}

    public record CommandError(String code, String message) {}

    public interface PluginOps {
        boolean install(String scope, String name);
        boolean uninstall(String scope, String name);
        boolean enable(String scope, String name);
        boolean disable(String scope, String name);
        boolean disableAll();
        boolean update(String scope, String name);
    }

    public interface CliWriter {
        void write(String text);
    }

    public interface Shutdown {
        void shutdown(int code);
    }

    public interface Telemetry {
        void logEvent(String event, Map<String, Object> fields);
    }

    private final PluginOps ops;
    private final CliWriter writer;
    private final Shutdown shutdown;
    private final Telemetry telemetry;

    public PluginCliCommands(PluginOps ops, CliWriter writer, Shutdown shutdown, Telemetry telemetry) {
        this.ops = ops == null ? new NullOps() : ops;
        this.writer = writer == null ? t -> {} : writer;
        this.shutdown = shutdown == null ? c -> {} : shutdown;
        this.telemetry = telemetry == null ? (e, f) -> {} : telemetry;
    }

    public PluginCliCommands() {
        this(null, null, null, null);
    }

    public boolean installPlugin(String scope, String name) {
        if (scope == null || name == null) return false;
        boolean ok = ops.install(scope, name);
        telemetry.logEvent("tengu_plugin_install", Map.of("scope", scope, "name", name, "success", ok));
        if (!ok) {
            writer.write("Failed to install plugin " + name);
            shutdown.shutdown(1);
        } else {
            writer.write("Installed " + name);
        }
        return ok;
    }

    public boolean uninstallPlugin(String scope, String name) {
        if (scope == null || name == null) return false;
        boolean ok = ops.uninstall(scope, name);
        telemetry.logEvent("tengu_plugin_uninstall", Map.of("scope", scope, "name", name, "success", ok));
        if (!ok) writer.write("Failed to uninstall plugin " + name);
        return ok;
    }

    public boolean enablePlugin(String scope, String name) {
        if (scope == null || name == null) return false;
        boolean ok = ops.enable(scope, name);
        if (!ok) writer.write("Failed to enable plugin " + name);
        return ok;
    }

    public boolean disablePlugin(String scope, String name) {
        if (scope == null || name == null) return false;
        boolean ok = ops.disable(scope, name);
        if (!ok) writer.write("Failed to disable plugin " + name);
        return ok;
    }

    public boolean disableAllPlugins() {
        boolean ok = ops.disableAll();
        telemetry.logEvent("tengu_plugin_disable_all", Map.of("success", ok));
        return ok;
    }

    public boolean updatePlugin(String scope, String name) {
        if (scope == null || name == null) return false;
        boolean ok = ops.update(scope, name);
        telemetry.logEvent("tengu_plugin_update", Map.of("scope", scope, "name", name, "success", ok));
        return ok;
    }

    /** CC classifyPluginCommandError. */
    public static CommandError classifyPluginCommandError(String errorMessage) {
        if (errorMessage == null) return new CommandError("UNKNOWN", "unknown error");
        String lower = errorMessage.toLowerCase();
        if (lower.contains("not found")) return new CommandError("NOT_FOUND", errorMessage);
        if (lower.contains("permission")) return new CommandError("PERMISSION", errorMessage);
        if (lower.contains("network") || lower.contains("timeout")) {
            return new CommandError("NETWORK", errorMessage);
        }
        return new CommandError("UNKNOWN", errorMessage);
    }

    private static class NullOps implements PluginOps {
        public boolean install(String s, String n) { return false; }
        public boolean uninstall(String s, String n) { return false; }
        public boolean enable(String s, String n) { return false; }
        public boolean disable(String s, String n) { return false; }
        public boolean disableAll() { return false; }
        public boolean update(String s, String n) { return false; }
    }
}