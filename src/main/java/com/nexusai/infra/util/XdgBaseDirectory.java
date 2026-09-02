package com.nexusai.infra.util;

import java.util.Map;
import java.util.function.Supplier;

/**
 * XdgBaseDirectory · 对齐 CC utils/xdg.ts.
 *
 * <p>L1 语义: XDG Base Directory 解析 — 默认 ~/.local/state, ~/.cache, ~/.local/share + ~/.local/bin。
 * env var override:XDG_STATE_HOME / XDG_CACHE_HOME / XDG_DATA_HOME。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 静态方法 (getXDGStateHome/CacheHome/DataHome/UserBinDir) + Options record</li>
 *   <li><b>A2 Golden Trace</b>: 缺 env→~/.local/state;XDG_STATE_HOME override → 直接用;homedir 也可注入</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output;无副作用</li>
 *   <li><b>A4 边界</b>: null home → '/.local/state';null env → empty</li>
 *   <li><b>A5 业务场景</b>: native installer 写入 ~/.local/bin/claude (user bin dir);~/.local/state 存 session 状态</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS process.env + os.homedir → Java Supplier&lt;Map&gt; + Supplier&lt;String&gt; (caller wired);
 * TS ?? ?? → Java Objects.requireNonNullElse + null check;
 * TS join + path.sep → Java File.separator + path.join。
 */
public final class XdgBaseDirectory {

    public record Options(Map<String, String> env, String homedir) {}

    private static final String DEFAULT_HOME_SUFFIX = "/.local";

    private XdgBaseDirectory() {}

    public static String getXDGStateHome(Options opts) {
        String home = resolveHome(opts);
        if (opts != null && opts.env() != null) {
            String v = opts.env().get("XDG_STATE_HOME");
            if (v != null && !v.isEmpty()) return v;
        }
        return home.equals("/") ? "/.local/state" : home + DEFAULT_HOME_SUFFIX + "/state";
    }

    public static String getXDGCacheHome(Options opts) {
        String home = resolveHome(opts);
        if (opts != null && opts.env() != null) {
            String v = opts.env().get("XDG_CACHE_HOME");
            if (v != null && !v.isEmpty()) return v;
        }
        return home.equals("/") ? "/.cache" : home + "/.cache";
    }

    public static String getXDGDataHome(Options opts) {
        String home = resolveHome(opts);
        if (opts != null && opts.env() != null) {
            String v = opts.env().get("XDG_DATA_HOME");
            if (v != null && !v.isEmpty()) return v;
        }
        return home.equals("/") ? "/.local/share" : home + DEFAULT_HOME_SUFFIX + "/share";
    }

    public static String getUserBinDir(Options opts) {
        String home = resolveHome(opts);
        return home.equals("/") ? "/.local/bin" : home + DEFAULT_HOME_SUFFIX + "/bin";
    }

    private static String resolveHome(Options opts) {
        if (opts == null || opts.homedir() == null || opts.homedir().isEmpty()) {
            return "/";
        }
        return opts.homedir();
    }
}
