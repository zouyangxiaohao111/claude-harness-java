package com.nexusai.kb.app.workspace;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

/**
 * 知识库工作区目录解析 · 三段降级，<b>每一段以「目录建得出来」为通过条件</b>。
 *
 * <h2>为什么需要它</h2>
 * 知识库助手 / 问答面板需要一条<b>专用会话</b>（不碰用户正在聊的会话）。会话的
 * {@code mainProjectId} 是 {@code @NotBlank}（{@code SessionCreateRequest:27}），必须给一个真项目；
 * 而项目 = 一条目录（{@code ProjectService.register} 以 path 判重）。
 *
 * <p>改造前两处面板都取 {@code projectApi.list()[0]} —— 而 {@code ProjectService.listAll()}
 * 是 {@code selectAll()} 无 {@code ORDER BY}，SQLite 按 rowid 返回 ⇒ 本机 rowid=1 恰好是
 * {@code D:/code/ai_project/nexusai}（用户自己的仓库），于是助手会话被记在用户仓库名下，
 * 其 cwd / 记忆 / 产物全部落到仓库里。这就是「助手默认绑定 NexusAI 会话」的真身。
 *
 * <h2>降级链（顺序不可调）</h2>
 * <table border="1">
 *   <tr><th>级</th><th>来源</th><th>候选路径</th><th>通过条件</th></tr>
 *   <tr><td>1</td><td>环境变量 {@value #INSTALL_DIR_ENV}</td>
 *       <td>{@code <env>/workspace/kb}</td><td>可创建</td></tr>
 *   <tr><td>2</td><td>安装目录探测（打包后才有意义）</td>
 *       <td>{@code <install>/workspace/kb}</td><td>可创建</td></tr>
 *   <tr><td>3</td><td>{@link NexusaiPaths#getAppConfigHomeDir()}</td>
 *       <td>{@code <configHome>/workspace/kb}</td><td>可创建（失败则抛）</td></tr>
 * </table>
 *
 * <h2>为什么第 2 级的判据是「目录名 backend 且同级有 jre/」</h2>
 * 与 {@code front/src-tauri/src/backend.rs:332-345} {@code resolve_backend_dir} 逐条同款：
 * 打包后随包资源是 {@code <install>/backend/}，内含 {@code jre/} 与 {@code nexusai-backend.jar}。
 * 本类所在 classpath 条目的目录，打包后就是那个 {@code backend/} ⇒ 取 parent = 安装目录。
 *
 * <p>⚠️ <b>dev 下自然不成立</b>：{@code target/classes} 目录名不是 {@code backend}、也没有
 * {@code jre/} ⇒ 跳过第 2 级，落到第 3 级 configHome。这是刻意的——开发机上「安装目录」
 * 这个概念不存在，硬凑一个只会把工作区建到仓库里。
 *
 * <h2>⚠️ 与 configHome 红线的关系</h2>
 * {@code AutoMemPaths.java:485} 禁止把 configHome <b>本身</b>当项目根（用 {@code isSameDirectory}
 * 相等判定）。本类第 3 级产出的是 {@code <configHome>/workspace/kb}，是<b>子目录</b>而非 configHome
 * 本身 ⇒ 不触发该红线。
 */
public final class KbWorkspacePaths {

    private static final Logger log = LoggerFactory.getLogger(KbWorkspacePaths.class);

    /** 安装目录环境变量名（由安装器写入）。 */
    public static final String INSTALL_DIR_ENV = "NEXUSAI_INSTALL_DIR";

    /** 工作区目录名（安装目录 / configHome 下的同名子目录）。 */
    static final String WORKSPACE_DIR_NAME = "workspace";

    /** 知识库工作区目录名。 */
    static final String KB_DIR_NAME = "kb";

    /** 打包后随包后端资源目录名（{@code resolve_backend_dir} 同款判据）。 */
    static final String BACKEND_DIR_NAME = "backend";

    /** 随包裁剪 JRE 目录名（第 2 级的判别标志）。 */
    static final String JRE_DIR_NAME = "jre";

    private KbWorkspacePaths() {
        // 静态工具类
    }

    /** 解析结果的来源分级（日志与单测判据都用它）。 */
    public enum Tier {
        /** 环境变量 {@value #INSTALL_DIR_ENV} 指定。 */
        INSTALL_DIR_ENV,
        /** 从 classpath 位置反推出安装目录。 */
        INSTALL_DIR_DETECTED,
        /** 兜底：配置自有根下的 workspace/kb。 */
        CONFIG_HOME
    }

    /**
     * 解析结果。
     *
     * @param path 已创建成功的绝对路径（{@code workspace/kb} 本体）
     * @param tier 命中的降级级次
     */
    public record Resolved(Path path, Tier tier) {
    }

    /**
     * 生产入口：按真实环境变量 + 真实 classpath 位置解析。
     *
     * @return 解析结果（第 3 级失败会抛 {@link IllegalStateException}）
     */
    public static Resolved resolve() {
        return resolve(System::getenv, classpathCandidates());
    }

    /**
     * 可注入版本（单测用）。
     *
     * @param env        环境变量读取器（生产传 {@code System::getenv}）
     * @param candidates classpath 候选条目（打包后 = {@code <install>/backend/nexusai-backend.jar}；
     *                   dev = {@code target/classes}；空列表 = 全部不可用）
     * @return 解析结果
     */
    static Resolved resolve(Function<String, String> env, java.util.List<Path> candidates) {
        // 第 1 级：安装器写入的环境变量
        String raw = env == null ? null : env.apply(INSTALL_DIR_ENV);
        if (raw != null && !raw.isBlank()) {
            Path candidate = workspaceKb(Paths.get(raw.trim()));
            if (tryCreate(candidate, Tier.INSTALL_DIR_ENV)) {
                return new Resolved(candidate, Tier.INSTALL_DIR_ENV);
            }
            log.warn("[KB工作区] 环境变量 {}={} 指向的目录不可用（建不出来），降级到安装目录探测",
                INSTALL_DIR_ENV, raw.trim());
        }

        // 第 2 级：从 classpath 反推安装目录（逐个候选试，取第一个成立的）
        for (Path entry : candidates == null ? java.util.List.<Path>of() : candidates) {
            Path installDir = detectInstallDir(entry);
            if (installDir == null) {
                continue;
            }
            Path candidate = workspaceKb(installDir);
            if (tryCreate(candidate, Tier.INSTALL_DIR_DETECTED)) {
                return new Resolved(candidate, Tier.INSTALL_DIR_DETECTED);
            }
            log.warn("[KB工作区] 探测到的安装目录 {} 下工作区建不出来，继续下一候选", installDir);
        }

        // 第 3 级：配置自有根（兜底，失败即抛）
        Path candidate = workspaceKb(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        if (!tryCreate(candidate, Tier.CONFIG_HOME)) {
            throw new IllegalStateException(
                "知识库工作区目录无法创建：" + candidate
                    + "（已尝试 环境变量 " + INSTALL_DIR_ENV + " / 安装目录探测 / 配置根 三级）");
        }
        return new Resolved(candidate, Tier.CONFIG_HOME);
    }

    /**
     * 从 classpath 条目反推安装目录。
     *
     * @param classpathEntry classpath 条目：打包后 = {@code <install>/backend/nexusai-backend.jar}；
     *                       dev = {@code backend/target/classes} 目录
     * @return 安装目录；不满足打包布局 ⇒ {@code null}
     */
    static Path detectInstallDir(Path classpathEntry) {
        if (classpathEntry == null) {
            return null;
        }
        Path p = classpathEntry.toAbsolutePath().normalize();
        // 条目可能是 jar 文件（打包后）或 classes 目录（dev）——统一取到「所在目录」
        Path dir = Files.isDirectory(p) ? p : p.getParent();
        if (dir == null) {
            return null;
        }
        Path fileName = dir.getFileName();
        // 目录名必须是 backend（dev 下是 classes ⇒ 直接放弃，⛔ 不去仓库里建工作区）
        if (fileName == null || !BACKEND_DIR_NAME.equals(fileName.toString())) {
            return null;
        }
        // 同级必须有裁剪 JRE —— 这是「随包资源」与「随手一个叫 backend 的目录」的判别标志
        if (!Files.isDirectory(dir.resolve(JRE_DIR_NAME))) {
            log.debug("[KB工作区] {} 名为 backend 但同级无 {}/，不认作安装目录", dir, JRE_DIR_NAME);
            return null;
        }
        return dir.getParent();
    }

    /**
     * 列出所有可用来反推安装目录的 classpath 条目（按可信度排序，已去重）。
     *
     * <h2>为什么需要多个来源</h2>
     * Spring Boot fat jar 下 {@code CodeSource.getLocation()} 给出的是
     * {@code jar:nested:file:/…/nexusai-backend.jar!/BOOT-INF/classes!/} 这类
     * <b>非文件系统 URL</b> —— 直接 {@code Paths.get(uri)} 必抛异常。实测（3499 真机）
     * 因此第 2 级探测<b>从未命中</b>，一路落到 configHome，等于该级形同虚设。
     *
     * <p>而 fat jar 启动时 {@code java.class.path} 恰好<b>就是 jar 本体路径</b>
     * （启动器只把那一份 jar 塞进 classpath），是最可靠的一路。
     *
     * @return 候选条目（可能为空，调用方逐个试 {@link #detectInstallDir}）
     */
    static java.util.List<Path> classpathCandidates() {
        java.util.LinkedHashSet<Path> out = new java.util.LinkedHashSet<>();
        // 来源 1：java.class.path（fat jar 下 = jar 本体；dev 下 = target/classes + 依赖 jar）
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                try {
                    Path p = Paths.get(entry);
                    if (Files.exists(p)) {
                        out.add(p.toAbsolutePath().normalize());
                    }
                } catch (RuntimeException e) {
                    log.debug("[KB工作区] classpath 条目解析失败，跳过：{} 原因={}", entry, e.toString());
                }
            }
        }
        // 来源 2：本类 CodeSource（dev 下这次就是 target/classes，比 source 1 更精确）
        Path fromCodeSource = codeSourceEntry();
        if (fromCodeSource != null) {
            out.add(fromCodeSource);
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * 把 {@code CodeSource.getLocation()} 的 URL 字面量转成文件系统路径。
     *
     * <p>兼容三种形态（真机实测的第一个是 fat jar 下的常态）：
     * <ol>
     *   <li>{@code jar:nested:file:/a/b/nexusai-backend.jar!/BOOT-INF/classes!/} ⇒ {@code /a/b/nexusai-backend.jar}</li>
     *   <li>{@code jar:file:/a/b/x.jar!/} ⇒ {@code /a/b/x.jar}</li>
     *   <li>{@code file:/a/b/target/classes/} ⇒ {@code /a/b/target/classes}</li>
     * </ol>
     * Windows 盘符形态 {@code file:/D:/x} 去前缀后是 {@code /D:/x} ⇒ 必须去掉前导斜杠。
     *
     * @param url CodeSource location 的字符串形态
     * @return 文件系统路径；无法识别 ⇒ {@code null}
     */
    static Path codeSourceUrlToPath(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String s = url.trim();
        // 截到第一个 '!' 之前 —— jar 内路径对定位安装目录没有意义。
        // ⚠️ 两种分隔符都要认：普通 jar 是 "!/"，Spring Boot 嵌套形态是 "/!"（实测
        // jar:nested:file:/a/b.jar/!BOOT-INF/classes/!/ —— 只找 "!/" 会漏掉它）。
        int bangSlash = s.indexOf("!/");
        int slashBang = s.indexOf("/!");
        int bang = bangSlash < 0 ? slashBang
            : (slashBang < 0 ? bangSlash : Math.min(bangSlash, slashBang));
        if (bang >= 0) {
            s = s.substring(0, bang);
        }
        // 去掉 scheme 前缀（可能叠加 jar:nested: 两层）
        int fileIdx = s.lastIndexOf("file:");
        if (fileIdx >= 0) {
            s = s.substring(fileIdx + "file:".length());
        } else if (s.contains(":")) {
            // 非 file scheme（http 等）⇒ 不是本地安装目录
            return null;
        }
        if (s.isBlank()) {
            return null;
        }
        try {
            s = java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.debug("[KB工作区] URL 解码失败，按原文使用：{} 原因={}", s, e.toString());
        }
        // Windows 盘符形态 file:/D:/x ⇒ /D:/x，去掉那个多余的前导斜杠
        if (s.length() > 2 && s.charAt(0) == '/' && s.charAt(2) == ':') {
            s = s.substring(1);
        }
        try {
            return Paths.get(s).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.debug("[KB工作区] URL 转路径失败：{} 原因={}", s, e.toString());
            return null;
        }
    }

    /**
     * 拼接 {@code <base>/workspace/kb}。
     *
     * @param base 安装目录或配置根
     * @return 绝对归一化路径
     */
    private static Path workspaceKb(Path base) {
        return base.resolve(WORKSPACE_DIR_NAME).resolve(KB_DIR_NAME)
            .toAbsolutePath().normalize();
    }

    /**
     * 尝试创建目录（含父级）。
     *
     * @param candidate 待创建目录
     * @param tier      用于日志的级次
     * @return 创建成功（或已存在）⇒ true
     */
    private static boolean tryCreate(Path candidate, Tier tier) {
        try {
            Files.createDirectories(candidate);
            log.info("[KB工作区] 采用第 {} 级目录：{}", tier, candidate);
            return true;
        } catch (IOException | SecurityException e) {
            // 建不出来（如 Program Files 需要管理员权限 / 盘符不存在）⇒ 交给下一级
            log.warn("[KB工作区] 第 {} 级目录创建失败：{} 原因={}", tier, candidate, e.toString());
            return false;
        }
    }

    /**
     * 本类 CodeSource 指向的文件系统条目（dev = {@code target/classes}；普通 jar = jar 文件）。
     *
     * @return 条目；取不到 / 非文件系统 URL ⇒ {@code null}
     */
    private static Path codeSourceEntry() {
        try {
            java.security.CodeSource cs = KbWorkspacePaths.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            return codeSourceUrlToPath(cs.getLocation().toString());
        } catch (Exception e) {
            log.debug("[KB工作区] 无法定位 classpath 来源（跳过该候选）原因={}", e.toString());
            return null;
        }
    }
}
