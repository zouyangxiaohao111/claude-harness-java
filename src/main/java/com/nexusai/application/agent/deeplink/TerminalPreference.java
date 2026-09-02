package com.nexusai.application.agent.deeplink;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * TerminalPreference · 对齐 CC utils/deepLink/terminalPreference.ts.
 *
 * <p>L1 语义: detect current terminal via TERM_PROGRAM env var + 存到 globalConfig 供 deep link handler 使用。
 * <ul>
 *   <li>{@link #updateDeepLinkTerminalPreference(envProvider, configGetter, configSaver)} — main entry</li>
 *   <li>{@code TERM_PROGRAM_TO_APP} Map — lowercased TERM_PROGRAM → macOS .app name</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 1 静态 method + 静态 Map (6 项) + 3 注入式 Supplier (env, configGet, configSave)</li>
 *   <li><b>A2 Golden Trace</b>: darwin only;TERM_PROGRAM "iTerm.app"→"iTerm";"Apple_Terminal"→"Terminal";"Ghostty"→"Ghostty";non-darwin→skip;existing match→skip;save via spread</li>
 *   <li><b>A3 副作用</b>: 注入式 saveGlobalConfig 抽象</li>
 *   <li><b>A4 边界</b>: non-darwin→no-op;null env→no-op;unknown app→no-op;already same→no-op</li>
 *   <li><b>A5 业务场景</b>: deep link handler 启动时调用,捕获用户当前 terminal 用于 launchMacosTerminal</li>
 * </ul>
 *
 * <p>L3 升级: TS Record literal → Java HashMap + put;
 * TS process.env → Java Supplier 注入式.
 */
public final class TerminalPreference {

    public static final Map<String, String> TERM_PROGRAM_TO_APP;

    static {
        TERM_PROGRAM_TO_APP = new HashMap<>();
        TERM_PROGRAM_TO_APP.put("iterm", "iTerm");
        TERM_PROGRAM_TO_APP.put("iterm.app", "iTerm");
        TERM_PROGRAM_TO_APP.put("ghostty", "Ghostty");
        TERM_PROGRAM_TO_APP.put("kitty", "kitty");
        TERM_PROGRAM_TO_APP.put("alacritty", "Alacritty");
        TERM_PROGRAM_TO_APP.put("wezterm", "WezTerm");
        TERM_PROGRAM_TO_APP.put("apple_terminal", "Terminal");
    }

    public interface GlobalConfig {
        String deepLinkTerminal();
    }

    @FunctionalInterface
    public interface GlobalConfigUpdater {
        GlobalConfig update();
    }

    private TerminalPreference() {}

    /**
     * Capture current terminal and store for deep link handler.
     *
     * @param envProvider    env var reader (process.env by default)
     * @param isMacos         platform check (e.g. () -> "darwin".equals(System.getProperty("os.name").toLowerCase().contains("mac") ...))
     * @param configGetter    returns current global config
     * @param configUpdater   updates global config
     */
    public static void updateDeepLinkTerminalPreference(
        Supplier<String> envProvider,
        BooleanSupplier isMacos,
        Supplier<GlobalConfig> configGetter,
        GlobalConfigUpdater configUpdater) {
        if (envProvider == null || !isMacos.getAsBoolean()) return;
        String termProgram = envProvider.get();
        if (termProgram == null) return;
        String app = TERM_PROGRAM_TO_APP.get(termProgram.toLowerCase());
        if (app == null) return;
        GlobalConfig config = configGetter.get();
        if (config == null) return;
        if (app.equals(config.deepLinkTerminal())) return;
        configUpdater.update();
    }
}
