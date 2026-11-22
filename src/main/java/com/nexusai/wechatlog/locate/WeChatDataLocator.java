package com.nexusai.wechatlog.locate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 定位微信的数据目录，三平台统一入口。
 *
 * <h2>三平台的根目录候选（实测 + 上游互证）</h2>
 * <table>
 *   <tr><th>平台</th><th>{@code xwechat_files} 位置</th><th>来源</th></tr>
 *   <tr><td>macOS</td>
 *       <td>{@code ~/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files}</td>
 *       <td>本机实测存在，5 个账号目录</td></tr>
 *   <tr><td>Windows</td>
 *       <td>{@code %USERPROFILE%\Documents\xwechat_files}</td>
 *       <td>chatlog {@code wechat.go} 默认值 + rmqg {@code detect.py}</td></tr>
 *   <tr><td>Linux</td>
 *       <td>{@code ~/.xwechat/xwechat_files}（另有若干候选）</td>
 *       <td>wcdb-key-tool {@code auto_detect_db_dir}</td></tr>
 * </table>
 *
 * <h2>⚠️ 不猜账号（遵循「不用启发式兜底猜身份」）</h2>
 * 多账号机器上「哪个是当前登录账号」不能靠「目录新旧」或「名字像不像」来猜。
 * 本类的 {@link #discoverAccounts()} 返回<b>全部</b>账号，由调用方（用户选择或登录态
 * 探测）决定用哪个。
 */
public final class WeChatDataLocator {

    private static final Logger log = LoggerFactory.getLogger(WeChatDataLocator.class);

    private WeChatDataLocator() {
    }

    /**
     * 当前平台默认的 {@code xwechat_files} 根目录，不存在则返回 null。
     */
    public static Path defaultXwechatFilesRoot() {
        Path configured = configuredRoot();
        if (configured != null && !Files.isDirectory(configured)) {
            // 配置错了要让人看见，否则用户会以为「设置了但没生效」
            log.warn("配置的微信数据根目录不存在: {}", configured);
        }
        for (Path candidate : rootCandidates()) {
            if (Files.isDirectory(candidate)) {
                log.debug("找到 xwechat_files 根目录: {}", candidate);
                return candidate;
            }
        }
        log.debug("未找到 xwechat_files 根目录，已尝试: {}", rootCandidates());
        return null;
    }

    /**
     * 显式指定数据根目录（用户在设置里选过，或启动参数传入）。
     *
     * <p>为什么不靠自动发现：微信 4.x 允许把存储位置改到任意盘符，而该路径
     * <b>不写注册表</b>（本机实测 {@code HKCU\Software\Tencent\Weixin} 只有 InstallPath，
     * FileSavePath 为空；{@code %APPDATA%\Tencent\xwechat\config} 里也没有明文路径）。
     * ⇒ 猜不出来就只能让用户指一次，然后由本方法交给 {@link #defaultXwechatFilesRoot()} 优先使用。
     */
    private static final String ROOT_PROPERTY = "nexusai.wechatlog.root";

    /**
     * 用户选过的目录落在这里 —— 只写属性的话重启就丢，
     * 用户每次开应用都得重新指一次，等于没记住。
     */
    private static final Path ROOT_FILE = Paths.get(System.getProperty("user.home"),
            ".nexusai", "wechat-log", ".root");

    /** 运行时指定（null 表示未指定）。 */
    private static volatile Path configuredRoot;

    /** 是否已尝试读过 {@link #ROOT_FILE}。 */
    private static volatile boolean rootFileLoaded;

    /**
     * 指定数据根目录（传 null 清除）。
     *
     * <p>同时写系统属性并落盘，便于同一进程内其它组件按属性读取、重启后仍然有效。
     */
    public static synchronized void setConfiguredRoot(Path root) {
        configuredRoot = root;
        if (root == null) {
            System.clearProperty(ROOT_PROPERTY);
            try {
                Files.deleteIfExists(ROOT_FILE);
            } catch (IOException e) {
                log.warn("清除微信数据根目录配置失败: {}", e.getMessage());
            }
            return;
        }
        System.setProperty(ROOT_PROPERTY, root.toString());
        try {
            Files.createDirectories(ROOT_FILE.getParent());
            Files.writeString(ROOT_FILE, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("保存微信数据根目录失败（下次启动需重新指定）: {}", e.getMessage());
        }
        log.info("微信数据根目录已设置为: {}", root);
    }

    /** 当前显式指定的根目录（未指定返回 null）。 */
    public static synchronized Path configuredRoot() {
        if (configuredRoot != null) {
            return configuredRoot;
        }
        String prop = System.getProperty(ROOT_PROPERTY);
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("NEXUSAI_WECHAT_ROOT");
        }
        if (prop != null && !prop.isBlank()) {
            configuredRoot = Paths.get(prop.trim());
            return configuredRoot;
        }
        if (!rootFileLoaded) {
            rootFileLoaded = true;
            if (Files.isRegularFile(ROOT_FILE)) {
                try {
                    String saved = Files.readString(ROOT_FILE, StandardCharsets.UTF_8).trim();
                    if (!saved.isEmpty()) {
                        configuredRoot = Paths.get(saved);
                    }
                } catch (IOException e) {
                    log.warn("读取微信数据根目录配置失败: {}", e.getMessage());
                }
            }
        }
        return configuredRoot;
    }

    /** 按平台列出候选根目录（顺序即优先级）。 */
    public static List<Path> rootCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        List<Path> out = new ArrayList<>();

        // 0. 显式配置优先于一切平台默认值
        Path configured = configuredRoot();
        if (configured != null) {
            out.add(configured);
        }

        if (os.contains("mac")) {
            out.add(Paths.get(home,
                    "Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files"));
            // 少数版本/内测包用 xWeChat 容器名
            out.add(Paths.get(home,
                    "Library/Containers/com.tencent.xWeChat/Data/Documents/xwechat_files"));
        } else if (os.contains("win")) {
            String userProfile = System.getenv("USERPROFILE");
            Path base = (userProfile != null && !userProfile.isBlank())
                    ? Paths.get(userProfile) : Paths.get(home);
            out.add(base.resolve("Documents").resolve("xwechat_files"));
            out.add(base.resolve("xwechat_files"));
        } else {
            out.add(Paths.get(home, ".xwechat", "xwechat_files"));
            out.add(Paths.get(home, "Documents", "xwechat_files"));
            // 部分发行版走 XDG 目录
            out.add(Paths.get(home, ".local", "share", "xwechat", "xwechat_files"));
        }
        return out;
    }

    /**
     * 枚举某根目录下所有账号。
     *
     * <p>判定依据：子目录下存在 {@code db_storage/}（v4）或 {@code Message/}（v3）。
     * <b>不</b>按目录名过滤成「看着像 wxid 的那个」，因为账号目录名可能是别名
     * （本机实测有 {@code jsmm12345_3626} 这种非 wxid 形态）。
     */
    public static List<WeChatAccount> discoverAccounts(Path xwechatFilesRoot) {
        List<WeChatAccount> out = new ArrayList<>();
        if (xwechatFilesRoot == null || !Files.isDirectory(xwechatFilesRoot)) {
            return out;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(xwechatFilesRoot)) {
            for (Path sub : dirs) {
                if (!Files.isDirectory(sub)) {
                    continue;
                }
                String name = sub.getFileName().toString();
                // 跳过微信自己用的非账号目录
                if (isSystemDir(name)) {
                    continue;
                }

                WeChatAccount account = toAccount(xwechatFilesRoot, sub, name);
                if (account != null) {
                    out.add(account);
                }
            }
        } catch (IOException e) {
            log.warn("枚举账号目录失败: {}", xwechatFilesRoot, e);
        }
        out.sort(Comparator.comparing(a -> a.wxid()));
        return out;
    }

    /** 便捷：在当前平台默认根目录下枚举账号。 */
    public static List<WeChatAccount> discoverAccounts() {
        return discoverAccounts(defaultXwechatFilesRoot());
    }

    private static WeChatAccount toAccount(Path root, Path dir, String dirName) {
        boolean v4 = Files.isDirectory(dir.resolve("db_storage"));
        boolean v3 = Files.isDirectory(dir.resolve("Message"));
        if (!v4 && !v3) {
            return null;
        }
        // v4 优先：同一账号可能同时留有 v3 老库
        int version = v4 ? 4 : 3;
        String wxid = stripHashSuffix(dirName);
        return new WeChatAccount(root, dir, wxid, version);
    }

    /**
     * 去掉微信追加在账号目录名尾部的 {@code _<hexhash>}。
     *
     * <p>实测形态：{@code wxid_<id>_9e7e} → {@code wxid_<id>}；
     * {@code <自定义号>_3626} → {@code <自定义号>}。
     *
     * <p>⚠️ 只剥「下划线 + 4 位以上十六进制」这一种明显形态，避免把 wxid 本身里的
     * 下划线误伤（例如 {@code wxid_test_abc123xyz}：末段含非十六进制字符，不会被剥）。
     */
    static String stripHashSuffix(String dirName) {
        int idx = dirName.lastIndexOf('_');
        if (idx <= 0 || idx == dirName.length() - 1) {
            return dirName;
        }
        String suffix = dirName.substring(idx + 1);
        if (suffix.length() >= 4 && suffix.matches("[0-9a-fA-F]+")) {
            return dirName.substring(0, idx);
        }
        return dirName;
    }

    private static boolean isSystemDir(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "all_users", "temp", "backup", "old_backup", "unknown_wechat",
                 "applet", "crash_info", "xweb" -> true;
            default -> false;
        };
    }

    /**
     * 列出账号目录下所有<b>加密</b>的 {@code .db}（递归）。
     *
     * <p>判定：前 15 字节不是 {@code SQLite format 3}。明文库（已解密产物或本就不加密的）
     * 不进入结果 —— 它们不需要密钥，混进来只会让候选匹配白白多跑一遍。
     *
     * <p>⚠️ 跳过 WAL 与 material 文件：WAL 是附加数据，不是独立数据库。
     */
    public static List<Path> listEncryptedDatabases(Path accountDir) {
        List<Path> out = new ArrayList<>();
        if (accountDir == null || !Files.isDirectory(accountDir)) {
            return out;
        }
        try (var stream = Files.walk(accountDir, 4)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".db");
                    })
                    .filter(WeChatDataLocator::isEncrypted)
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            log.warn("遍历数据库文件失败: {}", accountDir, e);
        }
        return out;
    }

    /** 判断一个文件是否为 SQLCipher 密文库。 */
    public static boolean isEncrypted(Path dbFile) {
        try {
            if (Files.size(dbFile) < 4096) {
                return false;
            }
            byte[] head = new byte[15];
            try (var in = Files.newInputStream(dbFile)) {
                if (in.readNBytes(head, 0, 15) < 15) {
                    return false;
                }
            }
            return !"SQLite format 3".equals(new String(head, java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 找某个库的 WAL 文件（可能不存在）。
     *
     * <p><b>为什么必须关心 WAL</b>：本机实测 {@code message_0.db-wal} 有 4MB，
     * 里面是尚未 checkpoint 的最新消息。只解主库会漏掉最近的聊天记录 ——
     * 而两个上游实现（chatlog、wechat-mac-export）都没有处理这一点。
     */
    public static Path walFileFor(Path dbFile) {
        return dbFile.resolveSibling(dbFile.getFileName() + "-wal");
    }
}
