package com.nexusai.infra.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ExecutableResolver · Windows 裸命令名 → 真实可执行文件路径解析。
 *
 * <p><b>WHY</b>：Java {@link ProcessBuilder} 不走 shell，也不认 Windows {@code PATHEXT}。
 * 传 {@code "codegraph"} 时它只按该字面名查找可执行文件，而 npm 全局安装产出的是
 * {@code codegraph.cmd}（无扩展名的那个文件是 sh 脚本，Windows 不能执行）⇒
 * {@code CreateProcess error=2, 系统找不到指定的文件}。实测：{@link ProcessBuilder}
 * 对 <b>.cmd 完整路径</b>可以正常启动（无需包 {@code cmd /c}），所以只需把裸名解析成
 * 真实文件路径。
 *
 * <p>同族先例：{@code PluginInstaller.ProcessBuilderNpmRunner.resolveNpmExecutable()}（npm 专用）。
 * 本类是其通用化版本，供 MCP / LSP 等以用户配置命令启动子进程的场景复用。
 *
 * <p>L2 契约：
 * <ul>
 *   <li>含路径分隔符的 {@code command}（绝对/相对路径）→ 原样返回，不改写</li>
 *   <li>裸名且当前为 Windows → 在 {@code PATH} 各目录中按 {@code PATHEXT} 顺序探测，
 *       命中即返回完整路径；{@code PATHEXT} 缺失时用 Windows 默认
 *       {@code .COM;.EXE;.BAT;.CMD}</li>
 *   <li>裸名且非 Windows → 原样返回（Unix 由内核按 PATH 解析）</li>
 *   <li>全部探测失败 → 原样返回传入的 {@code command}（失败信息仍由
 *       {@link ProcessBuilder} 给出，保持既有错误形态）</li>
 * </ul>
 *
 * <p>L3（可测性）：探测逻辑全部经 {@code fileExists} 谓词注入，纯函数可单测，
 * 不依赖真实文件系统。
 */
public final class ExecutableResolver {

    /** Windows {@code PATHEXT} 缺省值（与 cmd.exe 内置默认一致）。 */
    private static final String DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD";

    private ExecutableResolver() {
    }

    /**
     * 生产入口：按当前进程环境解析。
     *
     * @param command 用户配置的命令（裸名或路径）
     * @return 可直接交给 {@link ProcessBuilder} 的命令（Windows 裸名会变成带扩展名的完整路径）
     */
    public static String resolve(String command) {
        return resolve(command, System.getProperty("os.name", ""), System.getenv(),
            p -> Files.isRegularFile(p));
    }

    /**
     * 可注入版本（测试用）。
     *
     * @param command    原始命令
     * @param osName     {@code os.name}（判断是否 Windows）
     * @param env        环境变量（读 {@code PATH} / {@code PATHEXT}）
     * @param fileExists 文件存在性谓词
     * @return 解析结果；无法解析时原样返回 {@code command}
     */
    public static String resolve(String command, String osName, Map<String, String> env,
                                 java.util.function.Predicate<Path> fileExists) {
        if (command == null || command.isBlank()) {
            return command;
        }
        // 已带路径分隔符 ⇒ 调用方给了路径，不做任何改写（含相对路径与 UNC）
        if (command.indexOf('/') >= 0 || command.indexOf('\\') >= 0) {
            return command;
        }
        if (!isWindows(osName)) {
            return command;
        }
        String pathEnv = envGet(env, "PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return command;
        }
        List<String> candidates = candidateNames(command, envGet(env, "PATHEXT"));
        for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String name : candidates) {
                try {
                    Path p = Paths.get(dir).resolve(name);
                    if (fileExists.test(p)) {
                        return p.toString();
                    }
                } catch (RuntimeException ignored) {
                    // 非法路径（含通配符等）⇒ 跳过该目录项
                }
            }
        }
        return command;
    }

    /**
     * 生成探测候选名序列（Windows 语义，对齐 cmd.exe）：
     * <ul>
     *   <li>已带扩展名 → 只探测原名</li>
     *   <li>无扩展名 → <b>只按 {@code PATHEXT} 扩展名探测，不探测原名</b>。
     *       WHY 必须排除原名：npm 全局安装同时在同目录产出无扩展名的 POSIX sh 脚本
     *       （{@code codegraph}）与 {@code codegraph.cmd}。若把原名排首位，"命中"的是
     *       sh 脚本，Windows 无法执行 → {@code CreateProcess error=193 不是有效的 Win32
     *       应用程序}（实测）。cmd.exe 本身也从不执行无扩展名文件。</li>
     * </ul>
     */
    static List<String> candidateNames(String command, String pathext) {
        List<String> names = new ArrayList<>(5);
        if (hasExtension(command)) {
            names.add(command);
            return names;
        }
        String ext = (pathext == null || pathext.isBlank()) ? DEFAULT_PATHEXT : pathext;
        for (String e : ext.split(";")) {
            String t = e.trim();
            if (t.isEmpty()) {
                continue;
            }
            names.add(command + t);
            // PATHEXT 条目大小写不定（常见全大写）；补小写变体便于注入式
            // 谓词（区分大小写）的单测，真实文件系统不区分大小写故无副作用
            String lower = t.toLowerCase(Locale.ROOT);
            if (!lower.equals(t)) {
                names.add(command + lower);
            }
        }
        return names;
    }

    private static boolean hasExtension(String name) {
        int dot = name.lastIndexOf('.');
        int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > sep + 1;
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("win");
    }

    private static String envGet(Map<String, String> env, String key) {
        if (env == null || key == null) {
            return null;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }
}
