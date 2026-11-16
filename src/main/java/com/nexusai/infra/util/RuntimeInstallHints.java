package com.nexusai.infra.util;

import java.util.Locale;

/**
 * Node / Python / uv 的安装提示（跨平台）。
 *
 * <p><b>只给提示，不代为安装</b>：这里返回的是可复制的安装命令与官方下载页，
 * 产品不会静默下载或执行安装程序。
 *
 * <p><b>平台判定</b>：{@code os.name} 前缀 → windows / macos / linux。
 * Linux 的包管理器差异很大（apt / dnf / pacman），这里给 Debian 系命令作默认，
 * 同时总是给出官方下载页，用户可按发行版自行选择。
 */
public final class RuntimeInstallHints {

    /** 安装提示：{@code command} 可为空串（未知运行时）。 */
    public record Hint(String command, String url) {
    }

    private static final String NODE_URL = "https://nodejs.org/en/download";
    private static final String PYTHON_URL = "https://www.python.org/downloads/";
    private static final String UV_URL = "https://docs.astral.sh/uv/getting-started/installation/";

    private RuntimeInstallHints() {
    }

    /**
     * 取指定运行时的安装提示。
     *
     * @param runtime node / python / uv（其它值返回空提示）
     * @return 安装命令 + 官方下载页
     */
    public static Hint of(String runtime) {
        String family = osFamily();
        return switch (runtime) {
            case "node" -> switch (family) {
                case "windows" -> new Hint("winget install OpenJS.NodeJS.LTS", NODE_URL);
                case "macos" -> new Hint("brew install node", NODE_URL);
                default -> new Hint("sudo apt install nodejs npm", NODE_URL);
            };
            case "python" -> switch (family) {
                case "windows" -> new Hint("winget install Python.Python.3.12", PYTHON_URL);
                case "macos" -> new Hint("brew install python@3.12", PYTHON_URL);
                default -> new Hint("sudo apt install python3 python3-pip", PYTHON_URL);
            };
            case "uv" -> switch (family) {
                case "windows" -> new Hint(
                    "powershell -c \"irm https://astral.sh/uv/install.ps1 | iex\"", UV_URL);
                default -> new Hint("curl -LsSf https://astral.sh/uv/install.sh | sh", UV_URL);
            };
            default -> new Hint("", "");
        };
    }

    /** 平台族：windows / macos / linux。 */
    public static String osFamily() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }
}
