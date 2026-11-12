package com.nexusai.common;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

/**
 * 运行期数据根解析 · <b>安装目录优先，configHome 兜底</b>（用户 2026-11-12 裁定）。
 *
 * <h2>目录形态</h2>
 * <pre>
 *   &lt;install&gt;/data/workspace/&lt;NEXUSAI_USER 或 default&gt;/
 *       artifacts/  产物归档
 *       kb-storage/ 知识库原件
 *       pdf-cache/  PDF 附件缓存
 *       image-cache/ 图片附件缓存
 * </pre>
 *
 * <h2>安装目录从哪来</h2>
 * <ol>
 *   <li><b>壳注入（权威）</b>：Tauri 启动后端时把 {@code NEXUSAI_INSTALL_DIR} 放进子进程环境，
 *       值 = {@code resolve_backend_dir().parent()}（真实安装目录）。</li>
 *   <li>安装器写入的用户级环境变量：只在「不经壳直接起后端」时生效；壳注入的进程环境天然覆盖它。</li>
 *   <li>都拿不到（dev / 未安装）⇒ configHome，行为与改造前逐字节一致。</li>
 * </ol>
 *
 * <h2>为什么加 {@code data/} 一层</h2>
 * 覆盖安装 / 卸载可能清理安装目录；把用户数据收进 {@code data/} 便于安装器声明「保留/排除」，
 * 也避免与程序文件（backend、jre）混在一起。
 *
 * <h2>不可写就降级</h2>
 * 装到 {@code C:\Program Files} 时普通用户无写权限（Windows ACL，需 UAC 提权）。
 * 此时记 WARN 并回落 configHome —— 保证程序仍能启动、数据仍可用。
 *
 * <p>解析结果进程内缓存一次；读与写都经本类，避免「写新根、读老根」的半截接线。
 */
public final class NexusaiDataRoots {

    private static final Logger log = LoggerFactory.getLogger(NexusaiDataRoots.class);

    /** 安装目录环境变量（壳注入 / 安装器写入）。 */
    public static final String INSTALL_DIR_ENV = "NEXUSAI_INSTALL_DIR";

    /**
     * 用户目录名环境变量 · 账户体系接入前缺省 {@code default}。
     * 接入账户后由壳/后端写入账户标识即可，无需再改目录解析。
     */
    public static final String USER_ENV = "NEXUSAI_USER";

    /** 安装目录下的数据目录名（与程序文件隔离）。 */
    public static final String DATA_DIR_NAME = "data";

    /** 数据目录下的工作区目录名。 */
    public static final String WORKSPACE_DIR_NAME = "workspace";

    /** 取不到用户标识时的目录名。 */
    public static final String DEFAULT_USER_DIR = "default";

    /** 进程内缓存（首次解析后固定）。 */
    private static volatile Path cachedRoot;

    /**
     * 缓存对应的 configHome 字面量。
     *
     * <p>⚠️ 单测会经 {@link NexusaiPaths#setConfigHomeDirOverride} 把配置根指到临时目录；
     * 只缓存 {@link #cachedRoot} 会让「先解析一次、再 override」的用例读回旧值（写脏真实目录）。
     * 每次比对 configHome，变了就重算。
     */
    private static volatile String cachedConfigHome;

    private NexusaiDataRoots() {
        // 静态工具类
    }

    /**
     * 数据根目录（绝对路径、normalize、已创建）。
     *
     * @return {@code <install>/data/workspace/<user>} 或 configHome
     */
    public static Path dataRoot() {
        String configHome = NexusaiPaths.getAppConfigHomeDir();
        Path cached = cachedRoot;
        if (cached != null && configHome.equals(cachedConfigHome)) {
            return cached;
        }
        synchronized (NexusaiDataRoots.class) {
            if (cachedRoot == null || !configHome.equals(cachedConfigHome)) {
                cachedRoot = resolve(System::getenv, Paths.get(configHome));
                cachedConfigHome = configHome;
            }
            return cachedRoot;
        }
    }

    /**
     * 数据根下的子目录（artifacts / kb-storage / pdf-cache / image-cache …）。
     *
     * @param name 子目录名
     * @return 绝对路径（目录本身按需创建，沿用各存储原有行为）
     */
    public static Path sub(String name) {
        return dataRoot().resolve(name).toAbsolutePath().normalize();
    }

    /**
     * 可注入版本（单测用）。
     *
     * @param env        环境变量读取器（生产传 {@code System::getenv}）
     * @param configHome 配置根（生产传 {@link NexusaiPaths#getAppConfigHomeDir()}）
     * @return 已创建成功的数据根
     */
    static Path resolve(Function<String, String> env, Path configHome) {
        String install = env == null ? null : env.apply(INSTALL_DIR_ENV);
        if (install != null && !install.isBlank()) {
            Path installDir = Paths.get(install.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(installDir)) {
                log.warn("[数据根] {}={} 不是已存在的目录，降级到配置根", INSTALL_DIR_ENV, installDir);
            } else {
                Path candidate = installDir
                    .resolve(DATA_DIR_NAME)
                    .resolve(WORKSPACE_DIR_NAME)
                    .resolve(safeUserDirName(env.apply(USER_ENV)));
                if (tryCreate(candidate)) {
                    log.info("[数据根] 采用安装目录：{}（{} 指定）", candidate, INSTALL_DIR_ENV);
                    return candidate;
                }
                log.warn("[数据根] 安装目录不可写，降级到配置根：{}", candidate);
            }
        }
        Path fallback = configHome.toAbsolutePath().normalize();
        log.info("[数据根] 采用配置根：{}（未设置 {} 或安装目录不可用）", fallback, INSTALL_DIR_ENV);
        return fallback;
    }

    /**
     * 用户标识 → 文件系统安全的目录名。
     *
     * @param user 原始用户标识（null/blank → {@value #DEFAULT_USER_DIR}）
     * @return 清洗后的目录名
     */
    static String safeUserDirName(String user) {
        if (user == null || user.isBlank()) {
            return DEFAULT_USER_DIR;
        }
        // Windows 文件名非法字符 + 路径分隔符，统一替换为下划线
        String cleaned = user.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isBlank() ? DEFAULT_USER_DIR : cleaned;
    }

    /** 建目录 + 可写探测；失败返回 false（交给下一档）。 */
    static boolean tryCreate(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (IOException | SecurityException e) {
            log.warn("[数据根] 目录创建/写入探测失败：{} 原因={}", dir, e.toString());
            return false;
        }
    }

    /** 清空进程内缓存（单测用）。 */
    public static void resetForTest() {
        cachedRoot = null;
        cachedConfigHome = null;
    }
}
