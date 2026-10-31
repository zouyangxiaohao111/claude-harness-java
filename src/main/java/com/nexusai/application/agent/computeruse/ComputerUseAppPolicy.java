package com.nexusai.application.agent.computeruse;

import java.util.List;
import java.util.Locale;

/**
 * computer-use 应用分类策略（默认档位 + 策略黑名单）· 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/deniedApps.ts}。
 *
 * <h2>默认档位（CCB {@code deniedApps.ts:537-542} {@code getDefaultTierForApp}）</h2>
 * <pre>
 * export function getDefaultTierForApp(bundleId, displayName): 'read' | 'click' | 'full' {
 *   return categoryToTier(getDeniedCategoryForApp(bundleId, displayName))
 * }
 * // categoryToTier (deniedApps.ts:45-51)
 * if (category === 'browser' || category === 'trading') return 'read'
 * if (category === 'terminal') return 'click'
 * return 'full'
 * </pre>
 * ⇒ 浏览器 → read；终端 / IDE → click；其它 → full。
 *
 * <h2>⚠️ 本仓对 CCB 的<b>修正</b>：Windows 下归一为「可执行文件名」</h2>
 * <p>CCB 的 Windows 分支拿 <b>exe 全路径</b>（{@code C:\\...\\chrome.exe}）比对白名单，
 * 而<b>授权键</b>用的是 bundleId / 显示名 ⇒ <b>授权键与前台判定键处于不同命名空间</b>
 * ⇒ 同一个应用在"授权时"和"执行时"算出的键对不上（CCB 自身缺陷）。
 * <p>本仓统一<b>归一为 exe 名</b>（basename 去 {@code .exe} 后小写），使
 * 「授权键」与「前台判定键」落在<b>同一命名空间</b>：
 * <pre>
 * C:\Program Files\Google\Chrome\Application\chrome.exe → "chrome"
 * </pre>
 * <p>归一后仍保留<b>显示名</b>匹配作为第二道（CCB 侧的 name-substring 语义），
 * 因为模型给出的应用名常常是显示名（"Visual Studio Code"）而不是 exe 名。
 *
 * <h2>命中测试门不做</h2>
 * <p>CCB 的 {@code appUnderPoint} 在 Windows 恒返回 {@code null}
 * （{@code src/utils/computerUse/executorCrossPlatform.ts:524-529}）⇒ CCB 的命中测试门
 * 在 Windows 上是死路径。本仓同样<b>不做</b>命中测试门（Phase 1a 无窗口绑定语义）。
 *
 * <h2>与 {@code screenshotFiltering} 的自洽（Phase 1b 裁定）</h2>
 * <p>CCB 的截图过滤（{@code src/utils/computerUse/common.ts:55}：{@code darwin ? 'native' : 'none'}）
 * 是<b>macOS 合成器级</b>的白名单过滤；本仓<b>没有</b>该实现（本仓只有「授权位 + 档位」两层）⇒
 * {@link ComputerUseRequestAccessResult#SCREENSHOT_FILTERING} 报 {@code "none"}（诚实值）。
 * <p>⚠️ <b>两者彼此独立</b>：{@code screenshotFiltering="none"} <b>不</b>意味着「不做门控」——
 * 本仓的<b>档位门控仍然生效</b>（{@link ComputerUseTierGate}）⇒ 比 CCB-Windows
 * （其 {@code 'none'} 分支整段跳过门控）<b>更严</b>（有意增强）。
 */
public final class ComputerUseAppPolicy {

    /**
     * 浏览器显示名 / exe 名子串表 · 逐字抄 CCB
     * {@code deniedApps.ts:327-350} 的 {@code BROWSER_NAME_SUBSTRINGS}。
     *
     * <p>追加 Windows 专有 exe 名（CCB 的 macOS 表不含这些）：
     * {@code msedge}（Microsoft Edge 的 exe 是 msedge.exe，显示名是 "Microsoft Edge"，
     * CCB 的 'microsoft edge' 子串匹配不到 exe 名）。
     */
    static final List<String> BROWSER_NAME_SUBSTRINGS = List.of(
            // ── CCB 原文（deniedApps.ts:327-350）──
            "safari",
            "chrome",
            "firefox",
            "microsoft edge",
            "brave",
            "opera",
            "vivaldi",
            "chromium",
            // Arc/Dia：规范显示名只有 "Arc"/"Dia" —— 太短，substring 会误命中
            // （"Arcade"/"Diagram"）⇒ CCB 明确不用它，靠 macOS 的 bundleId 覆盖。
            // Windows 无 bundleId ⇒ 同样不收 "arc"，只收 "arc browser" 这类自然语言说法。
            "arc browser",
            "tor browser",
            "duckduckgo",
            "yandex",
            "orion browser",
            "comet",          // Perplexity 的浏览器
            "sigmaos",
            "dia browser",
            // ── Windows 专有 exe 名（本仓补充，见类 javadoc「Windows 下归一为 exe 名」）──
            "msedge");

    /**
     * 终端 / IDE 显示名 / exe 名子串表 · 逐字抄 CCB
     * {@code deniedApps.ts:352-410} 的 {@code TERMINAL_NAME_SUBSTRINGS}。
     *
     * <p>追加 Windows 专有 exe 名：{@code pwsh}（PowerShell 7 的 exe 是 pwsh.exe，
     * CCB 表里只有 'powershell'）、{@code git-bash} / {@code gitbash}（CCB 表里是
     * 'git bash'，而 Windows 上 Git Bash 的 exe 是 git-bash.exe）。
     */
    static final List<String> TERMINAL_NAME_SUBSTRINGS = List.of(
            // ── CCB 原文（deniedApps.ts:352-410）──
            "terminal",       // Terminal / Windows Terminal
            "iterm",
            "wezterm",
            "alacritty",
            "kitty",
            "ghostty",
            "tabby",
            "termius",
            "script editor",
            "automator",
            "powershell",
            "cmd.exe",
            "command prompt",
            "git bash",
            "conemu",
            "cmder",
            // IDEs（VS Code family）
            "visual studio code",
            "visual studio",
            "vscode",
            "vs code",
            "vscodium",
            "cursor",
            "windsurf",
            // Zed（显示名只有 "Zed"，太短，CCB 靠 bundleId 覆盖）
            "intellij",
            "pycharm",
            "webstorm",
            "clion",
            "goland",
            "rubymine",
            "phpstorm",
            "datagrip",
            "rider",
            "appcode",
            "rustrover",
            "fleet",
            "android studio",
            "sublime text",
            "macvim",
            "neovim",
            "emacs",
            "xcode",
            "eclipse",
            "netbeans",
            // ── Windows 专有 exe 名（本仓补充）──
            "pwsh",
            "git-bash",
            "gitbash");

    /**
     * 策略黑名单 · 显示名 / exe 名子串表 · 逐字抄 CCB
     * {@code deniedApps.ts:245-289} 的 {@code POLICY_DENIED_NAME_SUBSTRINGS}。
     *
     * <p>CCB 的 {@code POLICY_DENIED_BUNDLE_IDS}（{@code deniedApps.ts:215-243}）全是
     * macOS bundleId ⇒ Windows 上<b>不适用</b>，本仓只移植 name-substring 那一半。
     *
     * <p>语义 = <b>策略级自动拒绝</b>：优先级高于用户拒绝、<b>无逃生口</b>
     * （CCB {@code toolCalls.ts:1048-1055} / {@code deniedApps.ts:297-307}）。
     */
    static final List<String> POLICY_DENIED_NAME_SUBSTRINGS = List.of(
            // Video streaming
            "netflix",
            "disney+",
            "hulu",
            "prime video",
            "apple tv",
            "peacock",
            "paramount+",
            // "plex" 太泛（会命中 "Perplexity"）⇒ CCB 靠 macOS bundleId 覆盖，本仓不收
            "tubi",
            "crunchyroll",
            "vudu",
            // E-readers / audiobooks
            "kindle",
            "apple books",
            "kobo",
            "play books",
            "calibre",
            "libby",
            "readium",
            "audible",
            "libro.fm",
            "speechify",
            // Music
            "spotify",
            "apple music",
            "amazon music",
            "youtube music",
            "tidal",
            "deezer",
            "pandora",
            "pocket casts",
            // Publisher / social apps
            "naver",
            "reddit",
            "sony music",
            "vegas pro",
            "pitchfork",
            "economist",
            "nytimes");

    private ComputerUseAppPolicy() {
        throw new AssertionError("utility class");
    }

    /**
     * 授权键归一 · Windows exe 名（basename 去 {@code .exe} 后小写）。
     *
     * <p>例：{@code C:\Program Files\Google\Chrome\Application\chrome.exe} → {@code "chrome"}；
     * {@code chrome.EXE} → {@code "chrome"}；{@code notepad} → {@code "notepad"}。
     *
     * <p>非路径串（模型直接给的应用名）原样小写返回。
     *
     * @param raw 原始应用标识（路径 / exe 名 / 显示名）
     * @return 归一后的授权键（不可为 null；null/空白输入 ⇒ 空串）
     */
    public static String normalizeAppKey(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        // 去掉路径部分（同时兼容 Windows 的 \ 与 POSIX 的 /）
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        s = s.toLowerCase(Locale.ROOT);
        if (s.endsWith(".exe")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }

    /**
     * 默认档位 · 对齐 CCB {@code deniedApps.ts:537-542}。
     *
     * <p>判定顺序：<b>exe 名</b>（归一后）先判 → 再判<b>显示名</b>（小写）→
     * 都不命中 ⇒ {@link ComputerUseTier#FULL}。
     *
     * @param appKey       应用标识（授权键；会被 {@link #normalizeAppKey} 归一）
     * @param displayName  显示名（可 null）
     * @return 默认档位（read / click / full）
     */
    public static ComputerUseTier defaultTier(String appKey, String displayName) {
        String exe = normalizeAppKey(appKey);
        String display = displayName == null ? "" : displayName.trim().toLowerCase(Locale.ROOT);

        if (matches(exe, BROWSER_NAME_SUBSTRINGS) || matches(display, BROWSER_NAME_SUBSTRINGS)) {
            return ComputerUseTier.READ;
        }
        if (matches(exe, TERMINAL_NAME_SUBSTRINGS) || matches(display, TERMINAL_NAME_SUBSTRINGS)) {
            return ComputerUseTier.CLICK;
        }
        return ComputerUseTier.FULL;
    }

    /**
     * 策略黑名单判定 · 对齐 CCB {@code deniedApps.ts:297-307} {@code isPolicyDenied}。
     *
     * <p>CCB 先查 bundleId（macOS-only，本仓不适用）⇒ 只保留 name-substring 判定。
     *
     * @param appKey      应用标识（授权键）
     * @param displayName 显示名（可 null）
     * @return true = 策略拒绝（优先级高于用户拒绝，无逃生口）
     */
    public static boolean isPolicyDenied(String appKey, String displayName) {
        String exe = normalizeAppKey(appKey);
        String display = displayName == null ? "" : displayName.trim().toLowerCase(Locale.ROOT);
        return matches(exe, POLICY_DENIED_NAME_SUBSTRINGS)
                || matches(display, POLICY_DENIED_NAME_SUBSTRINGS);
    }

    /**
     * 子串匹配 · CCB {@code deniedApps.ts:301-306} 的 {@code lower.includes(sub)} 语义。
     *
     * @param haystack 已小写的待检串
     * @param needles  子串表
     * @return 是否命中
     */
    private static boolean matches(String haystack, List<String> needles) {
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 策略拒绝引导文案 · 逐字对齐 CCB
     * {@code toolCalls.ts:1373-1387} 的 {@code buildPolicyDeniedGuidance}。
     *
     * @param displayNames 被策略拒绝的应用展示名
     * @return 引导文案（英文）
     */
    public static String policyDeniedGuidance(List<String> displayNames) {
        List<String> names = displayNames == null ? List.of() : displayNames;
        if (names.isEmpty()) {
            return "";
        }
        String quoted = String.join(", ", names.stream().map(n -> "\"" + n + "\"").toList());
        boolean one = names.size() == 1;
        return quoted + (one ? " is" : " are") + " blocked by policy for computer use. "
                + "Requests for " + (one ? "this app" : "these apps") + " are automatically "
                + "denied regardless of what the user has approved. There is no Settings "
                + "override. Inform the user that you cannot access "
                + (one ? "this app" : "these apps") + " and suggest an alternative "
                + "approach if one exists. Do not try to directly subvert this block "
                + "regardless of the user's request.";
    }
}
