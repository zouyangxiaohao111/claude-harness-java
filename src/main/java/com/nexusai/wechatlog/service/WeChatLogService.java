package com.nexusai.wechatlog.service;

import com.nexusai.wechatlog.crypto.KeyMatcher;
import com.nexusai.wechatlog.key.KeyExtractorFactory;
import com.nexusai.wechatlog.key.WeChatKeyExtractor;
import com.nexusai.wechatlog.key.elevation.WeChatKeyExtractionMessages;
import com.nexusai.wechatlog.key.elevation.WeChatKeyExtractionSupport;
import com.nexusai.wechatlog.locate.WeChatAccount;
import com.nexusai.wechatlog.locate.WeChatDataLocator;
import com.nexusai.wechatlog.model.WeChatMessage;
import com.nexusai.wechatlog.model.WeChatMessageType;
import com.nexusai.wechatlog.store.DatabaseDecryptor;
import com.nexusai.wechatlog.store.MessageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信本地聊天记录的读取服务（不依赖 Spring，便于单测与 CLI 复用）。
 *
 * <h2>整体链路</h2>
 * <pre>
 * ① 定位账号目录           WeChatDataLocator
 * ② 取密钥（需提权）        WeChatKeyExtractor（macOS lldb / Win 内存扫描 / Linux gdb）
 * ③ 密钥归属（HMAC 校验）   KeyMatcher  ← 把「来源不明的 32 字节」变成「某库可用的 key」
 * ④ 解密落盘 + WAL 合并     DatabaseDecryptor / WalMerger
 * ⑤ 读明文库               MessageReader
 * </pre>
 *
 * <h2>⚠️ 密钥复用与刷新</h2>
 * 取密钥要提权，不能让定时任务每次都弹密码框。所以：
 * <ul>
 *   <li>首次成功取到后，把<b>密钥候选</b>写入本地文件（权限 600），后续直接复用；</li>
 *   <li>每次同步先拿缓存候选去校验库的第 1 页；<b>校验失败</b>说明微信换了密钥（重新登录/
 *       换账号），此时才重新提权取一次。</li>
 * </ul>
 * 这样正常运行时完全不打扰用户。
 */
@Component
public class WeChatLogService {

    private static final Logger log = LoggerFactory.getLogger(WeChatLogService.class);

    /** 产物根目录（明文库）。 */
    private final Path workRoot;

    /** 密钥缓存文件。 */
    private final Path keyCacheFile;

    /** 抓取时长（仅断点类实现使用）。 */
    private final int captureSeconds;

    private final String linuxHookOffset;
    private final String linuxSymbol;

    /** 密钥归属判定（可注入，便于单测缓存命中/失效分支）。 */
    private final java.util.function.BiFunction<List<Path>, List<byte[]>, List<KeyMatcher.Match>> keyMatcher;

    /** 取密钥决策层（普通权限优先；提权仅用于打开进程失败）。 */
    private final WeChatKeyExtractionSupport keyExtraction;

    public WeChatLogService(){
        this(defaultWorkRoot(), defaultKeyCache(), 60, null, null);
    }

    public WeChatLogService(Path workRoot, Path keyCacheFile,
                            int captureSeconds, String linuxHookOffset, String linuxSymbol) {
        this(workRoot, keyCacheFile, captureSeconds, linuxHookOffset, linuxSymbol,
                KeyMatcher::matchAll,
                new WeChatKeyExtractionSupport(
                        () -> KeyExtractorFactory.create(captureSeconds, linuxHookOffset, linuxSymbol)));
    }

    /** 测试用：注入归属判定与取密钥决策层。 */
    WeChatLogService(Path workRoot, Path keyCacheFile,
                     int captureSeconds, String linuxHookOffset, String linuxSymbol,
                     java.util.function.BiFunction<List<Path>, List<byte[]>, List<KeyMatcher.Match>> keyMatcher,
                     WeChatKeyExtractionSupport keyExtraction) {
        this.workRoot = workRoot;
        this.keyCacheFile = keyCacheFile;
        this.captureSeconds = captureSeconds;
        this.linuxHookOffset = linuxHookOffset;
        this.linuxSymbol = linuxSymbol;
        this.keyMatcher = keyMatcher;
        this.keyExtraction = keyExtraction;
    }

    /** 默认产物目录：{@code ~/.nexusai/wechat-log}。 */
    public static Path defaultWorkRoot() {
        return Paths.get(System.getProperty("user.home"), ".nexusai", "wechat-log");
    }

    /** 默认密钥缓存：{@code ~/.nexusai/wechat-log/.keys}。 */
    public static Path defaultKeyCache() {
        return defaultWorkRoot().resolve(".keys");
    }

    /** 便捷构造：用默认目录。 */
    public static WeChatLogService withDefaults() {
        return new WeChatLogService(defaultWorkRoot(), defaultKeyCache(), 60, null, null);
    }

    // ==================================================================
    // 发现
    // ==================================================================

    /** 当前机器上所有可用的微信账号。 */
    public List<WeChatAccount> discoverAccounts() {
        return WeChatDataLocator.discoverAccounts();
    }

    /** 按 wxid 找账号；找不到返回 null。 */
    public WeChatAccount findAccount(String wxid) {
        for (WeChatAccount a : discoverAccounts()) {
            if (a.wxid().equals(wxid)) {
                return a;
            }
        }
        return null;
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    /**
     * 同步一个账号：取密钥 → 解密 → 读出全部消息。
     *
     * @param wxid          账号 wxid
     * @param allowElevation 是否允许触发提权（定时任务应传 false 以免弹密码框）
     * @return 同步结果
     */
    public SyncResult sync(String wxid, boolean allowElevation) {
        WeChatAccount account = findAccount(wxid);
        if (account == null) {
            // 不猜账号：明确告知有哪些可选项
            List<String> known = discoverAccounts().stream().map(WeChatAccount::wxid).toList();
            throw new IllegalStateException(
                    "找不到账号 " + wxid + "。当前可用账号: " + known);
        }
        return sync(account, allowElevation);
    }

    /** 同步指定账号。 */
    public SyncResult sync(WeChatAccount account, boolean allowElevation) {
        Path workDir = workRoot.resolve(safeName(account.wxid()));

        // 1. 列出待解密的库
        List<Path> dbs = WeChatDataLocator.listEncryptedDatabases(account.accountDir());
        if (dbs.isEmpty()) {
            log.info("账号 {} 没有加密数据库（可能已解密或尚未登录过）", account.wxid());
            return new SyncResult(account.wxid(), 0, 0, 0, List.of(), "没有加密数据库");
        }

        // 2. 取密钥：先缓存；缓存失效才取（普通权限优先，只有权限失败才提权）
        List<KeyMatcher.Match> matches = resolveMatches(dbs, account.accountDir(), allowElevation);

        log.info("账号 {}: {} 个库中匹配到 {} 个密钥", account.wxid(), dbs.size(), matches.size());

        // 3. 解密
        List<DatabaseDecryptor.DecryptResult> decrypted =
                DatabaseDecryptor.decryptAccount(account.accountDir(), workDir, matches);

        // 4. 读消息
        List<WeChatMessage> messages = new ArrayList<>();
        for (DatabaseDecryptor.DecryptResult r : decrypted) {
            String name = r.output().getFileName().toString();
            // 只读 message_* 分片（其它库没有 Msg_ 表，读了也是空，但省时间）
            if (!name.startsWith("message_") && !name.startsWith("msg_")) {
                continue;
            }
            messages.addAll(MessageReader.readShard(r.output()));
        }
        messages.sort(Comparator.comparingLong(WeChatMessage::createTime));

        log.info("账号 {} 同步完成: 解密 {} 个库，读出 {} 条消息",
                account.wxid(), decrypted.size(), messages.size());

        return new SyncResult(account.wxid(), dbs.size(), decrypted.size(),
                messages.size(), messages,
                "匹配到 " + matches.size() + " 个密钥");
    }

    /**
     * 解析密钥归属（缓存命中 ⇒ 不取密钥；缓存失效 ⇒ 取一次）。
     *
     * <p><b>先校验后落盘</b>：候选必须先通过 HMAC 校验才写入 {@code .keys}。
     * 旧实现在校验之前就整文件覆盖写缓存 —— 一批解不开任何库的候选也会被缓存下来，
     * 让之后的增量同步反复读到坏密钥。
     */
    List<KeyMatcher.Match> resolveMatches(List<Path> dbs, Path accountDir, boolean allowElevation) {
        List<byte[]> cached = loadCachedKeys();
        List<KeyMatcher.Match> matches = keyMatcher.apply(dbs, cached);
        if (!matches.isEmpty()) {
            return matches;
        }

        if (!allowElevation) {
            throw new IllegalStateException(
                    "密钥缓存已失效，需要重新取密钥。请在设置页点一次「同步聊天记录」"
                            + "（会按需请求一次管理员授权，点「是」即可）；定时任务不会自动提权。");
        }

        log.info("缓存密钥无效，开始取密钥（普通权限优先，仅在打开进程失败时提权）");
        List<byte[]> candidates = keyExtraction.extract(accountDir, true);
        if (candidates == null || candidates.isEmpty()) {
            throw WeChatKeyExtractionMessages.noCandidates();
        }

        matches = keyMatcher.apply(dbs, candidates);
        if (matches.isEmpty()) {
            // ⚠️ 不落盘：坏候选会卡死后续增量同步
            throw WeChatKeyExtractionMessages.candidatesNotMatch(candidates.size());
        }
        saveCachedKeys(candidates);
        return matches;
    }

    // ==================================================================
    // 查询（供工具调用）
    // ==================================================================

    /**
     * 在已解密产物上做关键词搜索。
     *
     * <p>⚠️ 这是<b>扫明文库</b>而不是查索引表。索引由上层
     * （{@code WeChatMessageIndexer}）维护；本方法用于「索引没有覆盖到」时的兜底，
     * 以及首次同步后的即时验证。
     */
    public List<WeChatMessage> searchPlaintext(String wxid, String keyword,
                                               int limit) {
        Path workDir = workRoot.resolve(safeName(wxid));
        if (!Files.isDirectory(workDir)) {
            return List.of();
        }
        List<WeChatMessage> hits = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.toLowerCase();

        try (var stream = Files.walk(workDir, 4)) {
            // ⚠️ 产物保留源目录结构：明文库在 <workDir>/db_storage/message/ 下，
            // 不是直接躺在 workDir 里（我一开始就在这里找错了，导致读不到消息）。
            List<Path> shards = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("message_") && n.endsWith(".db");
                    })
                    .sorted()
                    .toList();

            for (Path shard : shards) {
                for (WeChatMessage m : MessageReader.readShard(shard)) {
                    if (kw.isEmpty()
                            || (m.content() != null && m.content().toLowerCase().contains(kw))) {
                        hits.add(m);
                        if (hits.size() >= limit) {
                            return hits;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("搜索明文库失败", e);
        }
        return hits;
    }

    /** 已同步的账号 → 消息条数（供 UI 展示状态）。 */
    public Map<String, Integer> syncStatus() {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!Files.isDirectory(workRoot)) {
            return out;
        }
        try (var dirs = Files.list(workRoot)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                int count = 0;
                // 递归找 db_storage/message/*.db（保留源目录结构）
                try (var files = Files.walk(d, 4)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        String n = f.getFileName().toString();
                        if (n.startsWith("message_") && n.endsWith(".db")) {
                            count += MessageReader.readShard(f).size();
                        }
                    }
                }
                out.put(d.getFileName().toString(), count);
            }
        } catch (IOException e) {
            log.warn("统计同步状态失败", e);
        }
        return out;
    }

    // ==================================================================
    // 密钥缓存
    // ==================================================================

    /**
     * 读缓存的密钥候选。
     *
     * <p>文件格式：每行一个 64 字符十六进制。只存<b>候选</b>，不标「哪个库用哪把」——
     * 归属每次重新用 HMAC 判定，因为微信换密钥后归属会整体改变。
     */
    List<byte[]> loadCachedKeys() {
        if (!Files.isRegularFile(keyCacheFile)) {
            return List.of();
        }
        try {
            List<byte[]> out = new ArrayList<>();
            for (String line : Files.readAllLines(keyCacheFile)) {
                String h = line.trim().toLowerCase();
                if (h.length() == 64 && h.matches("[0-9a-f]+")) {
                    out.add(hexToBytes(h));
                }
            }
            log.debug("从缓存读到 {} 个密钥候选", out.size());
            return out;
        } catch (IOException e) {
            log.warn("读取密钥缓存失败，将重新取密钥", e);
            return List.of();
        }
    }

    /**
     * 写入密钥缓存：<b>与旧候选合并 + 原子写</b>。
     *
     * <p>合并是为了不让某个账号本轮取到的候选把其它账号仍有效的候选覆盖掉；
     * 原子写（tmp + move）是为了避免半写文件被下一轮当成有效缓存。
     */
    void saveCachedKeys(List<byte[]> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(keyCacheFile.getParent());
            java.util.LinkedHashSet<String> hexes = new java.util.LinkedHashSet<>();
            for (byte[] old : loadCachedKeys()) {
                hexes.add(bytesToHex(old));
            }
            for (byte[] c : candidates) {
                if (c != null && c.length == 32) {
                    hexes.add(bytesToHex(c));
                }
            }
            List<String> kept = hexes.stream().limit(64).toList();
            StringBuilder sb = new StringBuilder();
            for (String h : kept) {
                sb.append(h).append('\n');
            }

            Path tmp = keyCacheFile.resolveSibling(keyCacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, keyCacheFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, keyCacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(keyCacheFile);
            log.info("已缓存 {} 个密钥候选到 {}（合并旧候选）", kept.size(), keyCacheFile);
        } catch (IOException e) {
            // 缓存失败不致命：下次只是会再取一次
            log.warn("写入密钥缓存失败（下次同步需要重新取密钥）", e);
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            var perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception e) {
            // 非 POSIX 文件系统（Windows）忽略；NTFS 权限由上层目录控制
            log.debug("设置密钥文件权限失败（非 POSIX 系统可忽略）: {}", e.getMessage());
        }
    }

    // ==================================================================

    /**
     * 同步结果。
     *
     * @param wxid        账号
     * @param totalDbs    账号下加密库总数
     * @param decrypted   成功解密的库数
     * @param messageCount 读出的消息条数
     * @param messages    消息（首次同步时可能较大，调用方可忽略）
     * @param note        说明
     */
    public record SyncResult(String wxid, int totalDbs, int decrypted,
                             int messageCount, List<WeChatMessage> messages, String note) {
        public boolean success() {
            return decrypted > 0;
        }
    }

    /** 账号 wxid 用作目录名时的安全化（wxid 本身只含字母数字下划线，这里加一道保险）。 */
    public static String safeName(String wxid) {
        return wxid.replaceAll("[^A-Za-z0-9_.@-]", "_");
    }

    static byte[] hexToBytes(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
