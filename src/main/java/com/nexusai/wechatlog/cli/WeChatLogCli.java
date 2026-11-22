package com.nexusai.wechatlog.cli;

import com.nexusai.wechatlog.crypto.CipherSuite;
import com.nexusai.wechatlog.crypto.KeyMatcher;
import com.nexusai.wechatlog.key.KeyExtractorFactory;
import com.nexusai.wechatlog.key.WeChatKeyExtractor;
import com.nexusai.wechatlog.locate.WeChatAccount;
import com.nexusai.wechatlog.locate.WeChatDataLocator;
import com.nexusai.wechatlog.model.WeChatMessage;
import com.nexusai.wechatlog.model.WeChatMessageType;
import com.nexusai.wechatlog.store.DatabaseDecryptor;
import com.nexusai.wechatlog.store.MessageReader;
import com.nexusai.wechatlog.service.WeChatIncrementalSync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信聊天记录读取的独立命令行入口（不依赖 Spring）。
 *
 * <h2>为什么单独一个 CLI</h2>
 * 这条链路里有「弹系统授权框 + 挂 lldb」这种交互式步骤，放在 Spring 启动流程里
 * 很难验证。独立 CLI 让「取密钥 → 解密 → 读库」可以被一条命令跑通并看到每一步的输出，
 * 是排障与首次验证的正确形态。
 *
 * <h2>命令</h2>
 * <pre>
 * probe                          列出账号、加密库、WAL（不需要提权）
 * key    &lt;wxid&gt; [seconds]        取密钥候选，并用 HMAC 校验归属（需要提权）
 * sync   &lt;wxid&gt; [outRoot]        完整链路：取密钥 → 解密 → 读消息
 * search &lt;wxid&gt; &lt;keyword&gt;        在已解密产物里搜关键词
 * </pre>
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp target/classes:&lt;deps&gt; com.nexusai.wechatlog.cli.WeChatLogCli probe
 * </pre>
 */
public final class WeChatLogCli {

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        try {
            switch (args[0]) {
                case "probe" -> probe();
                case "key" -> key(args);
                case "sync" -> sync(args);
                case "search" -> search(args);
                case "incr" -> incr(args);
                case "index" -> indexOnly(args);
                default -> {
                    System.err.println("未知命令: " + args[0]);
                    usage();
                }
            }
        } catch (Exception e) {
            System.err.println("\n[失败] " + e.getMessage());
            if (e instanceof WeChatKeyExtractor.KeyExtractionException ke) {
                System.err.println("\n[怎么做] " + ke.remedy());
            }
            if (System.getenv("WXCLI_DEBUG") != null) {
                e.printStackTrace();
            }
        }
    }

    private static void usage() {
        System.out.println("""
                微信聊天记录读取 CLI

                  probe                        列出账号 / 加密库 / WAL（不需要提权）
                  key    <wxid> [seconds]      取密钥并校验归属（会弹系统授权框）
                  sync   <wxid> [outRoot]      完整链路：取密钥 → 解密 → 读消息
                  search <wxid> <keyword>      在已解密产物里搜关键词
                  incr   <wxid>                增量同步（只重解有变化的库，无需提权）
                  index  <wxid>                重写 AI 可感知的 README.md 与 INDEX.json

                提示：取密钥前需要
                  1) SIP 已关闭（csrutil status 应为 disabled）
                  2) 微信已 ad-hoc 重签名（codesign -dvvv 应显示 flags=0x2(adhoc)）
                  3) 微信正在运行并已登录
                """);
    }

    // ==================================================================
    // probe：不需要提权，先确认目录与库都能找到
    // ==================================================================
    private static void probe() {
        Path root = WeChatDataLocator.defaultXwechatFilesRoot();
        System.out.println("xwechat_files 根目录: " + root);
        if (root == null) {
            System.out.println("❌ 未找到。请确认微信已安装并登录过。");
            return;
        }
        List<WeChatAccount> accounts = WeChatDataLocator.discoverAccounts();
        System.out.println("发现账号: " + accounts.size() + " 个\n");

        for (WeChatAccount a : accounts) {
            List<Path> dbs = a.encryptedDatabases();
            System.out.printf("账号 %-26s v%d  加密库 %2d 个  %s%n",
                    a.wxid(), a.version(), dbs.size(), a.accountDir());
            // 统计有多少库有 WAL（WAL 里可能有最新消息）
            long withWal = dbs.stream()
                    .filter(d -> Files.isRegularFile(WeChatDataLocator.walFileFor(d)))
                    .count();
            System.out.printf("   其中 %d 个库带 WAL（里面是尚未落盘的最新消息）%n", withWal);
            // 挑 message 库看大小
            dbs.stream()
                    .filter(d -> d.getFileName().toString().startsWith("message_"))
                    .limit(3)
                    .forEach(d -> {
                        try {
                            System.out.printf("   %-18s %,12d 字节  %,6d 页%n",
                                    d.getFileName(), Files.size(d),
                                    Files.size(d) / 4096);
                        } catch (Exception ignored) {
                            // 读不到大小不影响探查
                        }
                    });
            System.out.println();
        }
    }

    // ==================================================================
    // key：取密钥 + 校验归属
    // ==================================================================
    private static void key(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: key <wxid> [seconds]");
            return;
        }
        String wxid = args[1];
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 60;

        WeChatAccount account = requireAccount(wxid);
        List<Path> dbs = account.encryptedDatabases();
        System.out.println("账号 " + account.wxid() + "，加密库 " + dbs.size() + " 个");

        System.out.println("\n⚠️  接下来会弹出系统管理员授权框，请输入本机密码并点「好」。");
        System.out.println("    然后在 " + seconds + " 秒内，在微信里点开几个会话并滚动聊天记录。\n");

        WeChatKeyExtractor extractor = KeyExtractorFactory.create(seconds, null, null);
        List<byte[]> candidates = extractor.extract(account.accountDir());
        System.out.println("\n抓到 " + candidates.size() + " 个不同的 32 字节候选");

        // 校验阶段可能耗时（passphrase 派生约 0.7s/次）。用行缓冲输出进度，
        // 否则用户会以为卡死（真实事故：83 秒无输出）。
        List<KeyMatcher.Match> matches = KeyMatcher.matchAll(dbs, candidates,
                (stage, done, total, detail) -> {
                    if ("raw校验".equals(stage)) {
                        // raw 阶段很快，每 5 个报一次就够
                        if (done % 5 == 0 || done == total) {
                            System.err.printf("\r  校验中 %d/%d …", done, total);
                        }
                    } else {
                        System.err.printf("\r  派生中 %d/%d（每个约 0.7s）：%s%30s",
                                done, total, detail, "");
                    }
                });
        System.err.println();
        System.out.println("匹配结果: " + matches.size() + " / " + dbs.size() + " 个库\n");

        if (matches.isEmpty()) {
            System.out.println("❌ 没有任何候选能通过 HMAC 校验。");
            System.out.println("   可能原因：抓取期间没有触发解密（请在微信里滚动聊天记录后重试）");
            return;
        }
        for (KeyMatcher.Match m : matches) {
            System.out.printf("  ✅ %-24s suite=%-10s 来源=%s%n",
                    m.databasePath().getFileName(), m.suite(), m.source());
        }
        Set<String> missing = new LinkedHashSet<>();
        for (Path d : dbs) {
            boolean hit = matches.stream()
                    .anyMatch(m -> m.databasePath().equals(d));
            if (!hit) {
                missing.add(d.getFileName().toString());
            }
        }
        if (!missing.isEmpty()) {
            System.out.println("\n未匹配到密钥的库（" + missing.size() + " 个）:");
            missing.stream().limit(10).forEach(n -> System.out.println("  · " + n));
            if (missing.size() > 10) {
                System.out.println("  … 还有 " + (missing.size() - 10) + " 个");
            }
            System.out.println("  说明：这些库可能在本轮没被微信访问，滚动更多会话可覆盖。");
        }
    }

    // ==================================================================
    // sync：完整链路
    // ==================================================================
    private static void sync(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: sync <wxid> [outRoot]");
            return;
        }
        String wxid = args[1];
        Path outRoot = args.length > 2
                ? Path.of(args[2])
                : Path.of(System.getProperty("user.home"), ".nexusai", "wechat-log");

        WeChatAccount account = requireAccount(wxid);
        Path workDir = outRoot.resolve(safe(wxid));
        List<Path> dbs = account.encryptedDatabases();
        System.out.println("账号 " + wxid + " → " + workDir);
        System.out.println("加密库 " + dbs.size() + " 个\n");

        // 1) 密钥缓存
        Path keyFile = outRoot.resolve(".keys");
        List<byte[]> candidates = loadKeys(keyFile);
        List<KeyMatcher.Match> matches = candidates.isEmpty()
                ? List.of() : KeyMatcher.matchAll(dbs, candidates);

        if (matches.isEmpty()) {
            System.out.println("缓存密钥不可用，开始取密钥（会弹授权框）…");
            WeChatKeyExtractor extractor = KeyExtractorFactory.create(60, null, null);
            candidates = extractor.extract(account.accountDir());
            saveKeys(keyFile, candidates);
            matches = KeyMatcher.matchAll(dbs, candidates);
            if (matches.isEmpty()) {
                System.out.println("❌ 取到 " + candidates.size()
                        + " 个候选，但都不匹配任何库。");
                return;
            }
        } else {
            System.out.println("使用缓存密钥，匹配 " + matches.size() + " 个库");
        }

        // 2) 解密
        System.out.println("\n开始解密…");
        List<DatabaseDecryptor.DecryptResult> decrypted =
                DatabaseDecryptor.decryptAccount(account.accountDir(), workDir, matches);
        System.out.println("解密完成 " + decrypted.size() + " 个库");
        int walFrames = decrypted.stream().mapToInt(DatabaseDecryptor.DecryptResult::walFrames).sum();
        if (walFrames > 0) {
            System.out.println("WAL 合并共应用 " + walFrames + " 帧（这部分是上游工具会丢的最新消息）");
        }

        // 3) 读消息
        System.out.println("\n读取消息…");
        List<WeChatMessage> all = new ArrayList<>();
        for (DatabaseDecryptor.DecryptResult r : decrypted) {
            String n = r.output().getFileName().toString();
            if (n.startsWith("message_") || n.startsWith("msg_")) {
                all.addAll(MessageReader.readShard(r.output()));
            }
        }
        System.out.println("\n共读出 " + all.size() + " 条消息");

        if (!all.isEmpty()) {
            Map<String, Integer> byConv = new LinkedHashMap<>();
            for (WeChatMessage m : all) {
                byConv.merge(m.conversationId(), 1, Integer::sum);
            }
            System.out.println("覆盖 " + byConv.size() + " 个会话，消息最多的前 10 个:");
            byConv.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10)
                    .forEach(e -> System.out.printf("  %-30s %6d 条%n",
                            e.getKey(), e.getValue()));

            System.out.println("\n最近 5 条消息预览:");
            all.stream()
                    .sorted((a, b) -> Long.compare(b.createTime(), a.createTime()))
                    .limit(5)
                    .forEach(m -> System.out.printf("  [%s] %s | %s: %s%n",
                            java.time.Instant.ofEpochSecond(m.createTime())
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .format(java.time.format.DateTimeFormatter
                                            .ofPattern("MM-dd HH:mm")),
                            shortId(m.conversationId()),
                            m.sender() == null ? "?" : m.sender(),
                            truncate(m.content(), 60)));
        }
    }

    // ==================================================================
    // search：在已解密产物里搜
    // ==================================================================
    // ==================================================================
    // incr：增量同步（不需要提权，用缓存密钥）
    // ==================================================================
    private static void incr(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: incr <wxid> [--force]");
            return;
        }
        String wxid = args[1];
        boolean force = args.length > 2 && "--force".equals(args[2]);
        var account = requireAccount(wxid);

        var sync = WeChatIncrementalSync.withDefaults();
        var outcome = sync.syncIncremental(account, force);

        System.out.println("账号 " + wxid);
        System.out.println("  重解 " + outcome.changedDbs() + " 个库（未变化 "
                + outcome.unchangedDbs() + " 个）");
        System.out.println("  读出 " + outcome.messages().size() + " 条消息");
        if (!outcome.skippedNoKey().isEmpty()) {
            System.out.println("  缺密钥 " + outcome.skippedNoKey().size() + " 个库: "
                    + outcome.skippedNoKey().stream().limit(8).toList());
        }
        System.out.println("  " + outcome.note());

        // 重写 AI 索引（用全量消息，保证 README 里数字是最新的）
        if (outcome.hasChanges()) {
            var all = readAllDecrypted(wxid);
            sync.writeAiIndex(account, all);
            System.out.println("\n已更新 " + syncRoot() + "/README.md 与 INDEX.json");
            System.out.println("全量共 " + all.size() + " 条 / "
                    + all.stream().map(m -> m.conversationId()).distinct().count() + " 个会话");
        }
    }

    // ==================================================================
    // index：只重写 AI 索引文件
    // ==================================================================
    private static void indexOnly(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: index <wxid>");
            return;
        }
        String wxid = args[1];
        var account = requireAccount(wxid);
        var all = readAllDecrypted(wxid);
        WeChatIncrementalSync.withDefaults().writeAiIndex(account, all);
        System.out.println("已写出 " + syncRoot() + "/README.md 与 INDEX.md");
        System.out.println("全量 " + all.size() + " 条 / "
                + all.stream().map(m -> m.conversationId()).distinct().count() + " 个会话");
    }

    /** 读该账号已解密产物里的全部消息。 */
    private static List<WeChatMessage> readAllDecrypted(String wxid) throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".nexusai", "wechat-log",
                safe(wxid), "db_storage", "message");
        List<WeChatMessage> all = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return all;
        }
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("message_") && n.endsWith(".db");
                }).sorted().toList()) {
                all.addAll(MessageReader.readShard(f));
            }
        }
        return all;
    }

    private static String syncRoot() {
        return Path.of(System.getProperty("user.home"), ".nexusai", "wechat-log").toString();
    }

    private static void search(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: search <wxid> <keyword>");
            return;
        }
        String wxid = args[1];
        String kw = args[2];
        Path workDir = Path.of(System.getProperty("user.home"),
                ".nexusai", "wechat-log", safe(wxid));
        if (!Files.isDirectory(workDir)) {
            System.out.println("❌ 该账号还没有解密产物，先跑 sync");
            return;
        }
        int hits = 0;
        // ⚠️ 深度必须是 4 而不是 2：明文库在 <workDir>/db_storage/message/*.db
        // （workDir → db_storage → message → 文件 共 3 层，留 4 冗余）。
        // 写成 2 会静默搜到 0 条 —— 表现成「数据库坏了」，其实只是没遍历到。
        try (var stream = Files.walk(workDir, 4)) {
            for (Path f : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("message_")
                              && p.getFileName().toString().endsWith(".db"))
                    .sorted().toList()) {
                for (WeChatMessage m : MessageReader.readShard(f)) {
                    if (m.content() != null
                            && m.content().toLowerCase().contains(kw.toLowerCase())) {
                        hits++;
                        System.out.printf("[%s] %s | %s: %s%n",
                                java.time.Instant.ofEpochSecond(m.createTime())
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter
                                                .ofPattern("yyyy-MM-dd HH:mm")),
                                shortId(m.conversationId()),
                                m.sender(),
                                truncate(m.content(), 100));
                        if (hits >= 50) {
                            System.out.println("… 已显示 50 条，停止");
                            return;
                        }
                    }
                }
            }
        }
        System.out.println("共 " + hits + " 条命中");
    }

    // ==================================================================

    private static WeChatAccount requireAccount(String wxid) {
        for (WeChatAccount a : WeChatDataLocator.discoverAccounts()) {
            if (a.wxid().equals(wxid)) {
                return a;
            }
        }
        List<String> known = WeChatDataLocator.discoverAccounts().stream()
                .map(WeChatAccount::wxid).toList();
        throw new IllegalArgumentException(
                "找不到账号 " + wxid + "；可用账号: " + known);
    }

    private static List<byte[]> loadKeys(Path f) throws Exception {
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<byte[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
            String h = line.trim().toLowerCase();
            if (h.length() == 64 && h.matches("[0-9a-f]+")) {
                byte[] b = new byte[32];
                for (int i = 0; i < 32; i++) {
                    b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
                }
                out.add(b);
            }
        }
        return out;
    }

    private static void saveKeys(Path f, List<byte[]> keys) throws Exception {
        if (keys.isEmpty()) {
            return;
        }
        Files.createDirectories(f.getParent());
        StringBuilder sb = new StringBuilder();
        for (byte[] k : keys) {
            for (byte b : k) {
                sb.append(String.format("%02x", b));
            }
            sb.append('\n');
        }
        Files.writeString(f, sb.toString());
        try {
            Files.setPosixFilePermissions(f,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // 非 POSIX 系统忽略
        }
        System.out.println("密钥候选已缓存到 " + f);
    }

    private static String safe(String wxid) {
        return wxid.replaceAll("[^A-Za-z0-9_.@-]", "_");
    }

    private static String shortId(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() <= 18 ? id : id.substring(0, 18) + "…";
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
