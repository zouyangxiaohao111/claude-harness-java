package com.nexusai.wechatlog.service;

import com.nexusai.wechatlog.crypto.KeyMatcher;
import com.nexusai.wechatlog.locate.WeChatAccount;
import com.nexusai.wechatlog.locate.WeChatDataLocator;
import com.nexusai.wechatlog.model.WeChatMessage;
import com.nexusai.wechatlog.store.DatabaseDecryptor;
import com.nexusai.wechatlog.store.MessageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量同步 + 生成「AI 可感知」的产物目录。
 *
 * <h2>三个必须解决的问题（用户 2026-11-08 提出）</h2>
 * <ol>
 *   <li><b>AI 怎么知道有这个目录</b> → 在产物根写 {@code README.md} + {@code INDEX.json}，
 *       并在固定位置放一份「指针文件」，AI 通过读文件就能发现数据；</li>
 *   <li><b>会不会定时刷新</b> → {@link WeChatLogSyncScheduler} 用 Quartz 定时调本类；</li>
 *   <li><b>微信今天多发了消息怎么增量</b> → 用<b>源库 (size, mtime, WAL size)</b> 做变更判据，
 *       只重解变化的库；消息层用 {@code (conversation_id, server_id, sort_seq)} 去重。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么增量判据用「大小 + mtime」而不是消息时间</h2>
 * 微信写入是 WAL 模式：新消息先落到 {@code -wal}，主库可能长时间不变。
 * 所以判据必须包含 <b>WAL 的大小与 mtime</b>，否则会漏掉最新消息
 * （本机实测：主库 21:05 未变，但 WAL 有 4MB 且在写）。
 *
 * <h2>⚠️ 增量做到什么粒度</h2>
 * <b>文件级</b>增量：某个库变了就整个重解该库（几 MB～几十 MB，很快）。
 * <b>不做</b>页级增量（要维护页级校验状态，收益远小于复杂度）。
 * 消息层去重由调用方（索引表唯一键）保证。
 */
public class WeChatIncrementalSync {

    private static final Logger log = LoggerFactory.getLogger(WeChatIncrementalSync.class);

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path workRoot;
    private final Path keyCacheFile;

    public WeChatIncrementalSync(Path workRoot, Path keyCacheFile) {
        this.workRoot = workRoot;
        this.keyCacheFile = keyCacheFile;
    }

    public static WeChatIncrementalSync withDefaults() {
        return new WeChatIncrementalSync(
                WeChatLogService.defaultWorkRoot(), WeChatLogService.defaultKeyCache());
    }

    // ==================================================================
    // 变更检测
    // ==================================================================

    /**
     * 源库的变更指纹。
     *
     * @param size  主库字节数
     * @param mtime 主库修改时间（毫秒）
     * @param walSize  WAL 字节数（0 表示没有 WAL）
     * @param walMtime WAL 修改时间（毫秒）
     */
    public record Fingerprint(long size, long mtime, long walSize, long walMtime) {
        /**
         * 编码为变更判据字符串。
         *
         * <p>⚠️ mtime 用<b>秒</b>而不是毫秒：`touch`/`cp -p`/某些备份工具只保留秒级精度，
         * 用毫秒会让「恢复原时间」这类操作看起来仍是「变了」，白白触发一次全库重解
         * （实测踩到：touch -r 恢复后仍被判为有变化）。
         * 微信真正写库时 mtime 精确到毫秒，但秒级足够判断「同一秒内有写入」，
         * 而同一秒内再次写入会改变<b>大小</b>，仍能被检出。
         */
        String encode() {
            return size + ":" + (mtime / 1000) + ":" + walSize + ":" + (walMtime / 1000);
        }

        static Fingerprint decode(String s) {
            String[] p = s.split(":");
            if (p.length != 4) {
                return null;
            }
            try {
                return new Fingerprint(Long.parseLong(p[0]), Long.parseLong(p[1]),
                        Long.parseLong(p[2]), Long.parseLong(p[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** 读某个源库的当前指纹；读不到返回 null。 */
    public static Fingerprint fingerprint(Path srcDb) {
        try {
            if (!Files.isRegularFile(srcDb)) {
                return null;
            }
            long size = Files.size(srcDb);
            long mtime = Files.getLastModifiedTime(srcDb).toMillis();
            Path wal = WeChatDataLocator.walFileFor(srcDb);
            long walSize = Files.isRegularFile(wal) ? Files.size(wal) : 0;
            long walMtime = Files.isRegularFile(wal) ? Files.getLastModifiedTime(wal).toMillis() : 0;
            return new Fingerprint(size, mtime, walSize, walMtime);
        } catch (IOException e) {
            return null;
        }
    }

    // ==================================================================
    // 增量同步
    // ==================================================================

    /**
     * 同步结果。
     *
     * @param wxid          账号
     * @param changedDbs    有变化并已重解的库数
     * @param unchangedDbs  未变化而跳过的库数
     * @param messages      本轮从<b>已重解</b>的库里读出的消息（调用方据此去重写入）
     * @param skippedNoKey  因缺少密钥而跳过的库名
     * @param needsKeyRefresh 缓存密钥为空或全部失配，调用方应转全量同步（必要时提权）
     */
    public record SyncOutcome(String wxid, int changedDbs, int unchangedDbs,
                              List<WeChatMessage> messages, List<String> skippedNoKey,
                              String note, boolean needsKeyRefresh) {
        public boolean hasChanges() {
            return changedDbs > 0;
        }
    }

    /**
     * 对一个账号做增量同步。
     *
     * @param account 账号
     * @param force   true=忽略指纹，全量重解
     */
    public SyncOutcome syncIncremental(WeChatAccount account, boolean force) {
        Path accountWork = workRoot.resolve(WeChatLogService.safeName(account.wxid()));
        Path stateFile = accountWork.resolve(".sync-state");

        // 1. 读上次的状态
        Map<String, String> previous = readState(stateFile);
        Map<String, String> current = new LinkedHashMap<>();

        // 2. 候选密钥（只用缓存，绝不在定时任务里提权）
        List<byte[]> candidates = loadCachedKeys();
        if (candidates.isEmpty()) {
            return new SyncOutcome(account.wxid(), 0, 0, List.of(), List.of(),
                    "没有缓存密钥，需要用户手动执行一次 sync 取密钥", true);
        }

        // 3. 逐个库判断变更
        List<Path> all = WeChatDataLocator.listEncryptedDatabases(account.accountDir());
        List<Path> changed = new ArrayList<>();
        List<String> skippedNoKey = new ArrayList<>();
        int unchanged = 0;

        for (Path db : all) {
            Fingerprint fp = fingerprint(db);
            if (fp == null) {
                continue;
            }
            String rel = account.accountDir().relativize(db).toString();
            current.put(rel, fp.encode());

            boolean same = !force && fp.encode().equals(previous.get(rel));
            if (same) {
                unchanged++;
                continue;
            }
            changed.add(db);
        }

        if (changed.isEmpty()) {
            writeState(stateFile, current);
            log.info("账号 {} 无变化（{} 个库均未修改）", account.wxid(), unchanged);
            return new SyncOutcome(account.wxid(), 0, unchanged, List.of(), List.of(),
                    "无变化", false);
        }

        // 4. 只对变化的库做密钥匹配 + 解密
        List<KeyMatcher.Match> matches = KeyMatcher.matchAll(changed, candidates);
        if (matches.isEmpty()) {
            return new SyncOutcome(account.wxid(), 0, unchanged, List.of(), List.of(),
                    "变化的库都匹配不到密钥（可能需要重新取密钥）", true);
        }

        List<DatabaseDecryptor.DecryptResult> decrypted =
                DatabaseDecryptor.decryptAccount(account.accountDir(), accountWork, matches);

        // 5. 读消息（只读 message_*）
        List<WeChatMessage> messages = new ArrayList<>();
        for (DatabaseDecryptor.DecryptResult r : decrypted) {
            String n = r.output().getFileName().toString();
            if (n.startsWith("message_") && n.endsWith(".db")) {
                messages.addAll(MessageReader.readShard(r.output()));
            }
        }
        messages.sort(Comparator.comparingLong(WeChatMessage::createTime));

        // 6. 记录哪些库没密钥（供用户知道缺什么）
        for (Path db : changed) {
            boolean hit = matches.stream().anyMatch(m -> m.databasePath().equals(db));
            if (!hit) {
                skippedNoKey.add(db.getFileName().toString());
            }
        }

        writeState(stateFile, current);
        log.info("账号 {} 增量同步完成: 重解 {} 个库（跳过 {} 个未变化），读出 {} 条消息",
                account.wxid(), decrypted.size(), unchanged, messages.size());

        return new SyncOutcome(account.wxid(), changed.size(), unchanged, messages,
                skippedNoKey, "重解 " + decrypted.size() + " 个库", false);
    }

    // ==================================================================
    // AI 可感知的产物目录
    // ==================================================================

    /**
     * 写出「AI 可感知」的入口文件。
     *
     * <h2>AI 怎么发现这里</h2>
     * 三层保障：
     * <ol>
     *   <li>{@code ~/.nexusai/wechat-log/README.md} —— 人/AI 读一眼就知道有什么；
     *   <li>{@code ~/.nexusai/wechat-log/INDEX.json} —— 机器可解析（账号、会话、条数、时间）；
     *   <li>AI 工具 {@code wechat_search_messages} / {@code wechat_list_conversations} /
     *       {@code wechat_read_conversation} 直接查索引表，无需读文件。
     * </ol>
     *
     * @param account  账号
     * @param messages 全量消息（用于统计）
     */
    public void writeAiIndex(WeChatAccount account, List<WeChatMessage> messages) {
        try {
            Files.createDirectories(workRoot);

            // 读 session.db 取「最后发言人 + 摘要」——群名存在 contact.db（可能没密钥），
            // 但这两项通常足以让人认出是哪个群。
            Map<String, MessageReader.SessionInfo> sessions = loadSessions(account);
            // 真实群名来自 contact.db（remark 优先，其次 nick_name）
            Map<String, String> displayNames = loadContactDisplayNames(account);

            // 按会话聚合
            Map<String, List<WeChatMessage>> byConv = new LinkedHashMap<>();
            for (WeChatMessage m : messages) {
                byConv.computeIfAbsent(m.conversationId(), k -> new ArrayList<>()).add(m);
            }
            long now = System.currentTimeMillis() / 1000;
            long d7 = now - 7L * 86400;

            // ---- INDEX.json ----
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"generated_at\": \"").append(TS.format(Instant.now())).append("\",\n");
            json.append("  \"account\": \"").append(account.wxid()).append("\",\n");
            json.append("  \"decrypted_root\": \"").append(accountWorkOf(account)).append("\",\n");
            json.append("  \"total_messages\": ").append(messages.size()).append(",\n");
            json.append("  \"total_conversations\": ").append(byConv.size()).append(",\n");

            List<Map.Entry<String, List<WeChatMessage>>> sorted = new ArrayList<>(byConv.entrySet());
            sorted.sort((a, b) -> Long.compare(lastTime(b.getValue()), lastTime(a.getValue())));

            json.append("  \"conversations\": [\n");
            for (int i = 0; i < sorted.size(); i++) {
                var e = sorted.get(i);
                long last = lastTime(e.getValue());
                long count7 = e.getValue().stream().filter(m -> m.createTime() >= d7).count();
                boolean group = e.getKey().endsWith("@chatroom");
                var si = sessions.get(e.getKey());
                json.append("    {\"id\": \"").append(esc(e.getKey())).append("\", ")
                        .append("\"type\": \"").append(group ? "group" : "direct").append("\", ")
                        .append("\"label\": \"").append(esc(labelOf(e.getKey(), displayNames, sessions))).append("\", ")
                        .append("\"messages\": ").append(e.getValue().size()).append(", ")
                        .append("\"last7d\": ").append(count7).append(", ")
                        .append("\"last_time\": \"").append(last > 0 ? TS.format(
                                Instant.ofEpochSecond(last)) : "").append("\"}");
                json.append(i < sorted.size() - 1 ? ",\n" : "\n");
            }
            json.append("  ]\n}\n");
            Files.writeString(workRoot.resolve("INDEX.json"), json.toString(),
                    StandardCharsets.UTF_8);

            // ---- README.md ----
            StringBuilder md = new StringBuilder();
            md.append("# 微信聊天记录（本机解密产物）\n\n");
            md.append("> 由 NexusAI 生成。**只读**，请勿手工修改。\n\n");
            md.append("## 这是什么\n\n");
            md.append("从本机微信客户端解密出的聊天记录索引，供 AI 检索。\n");
            md.append("原始消息完整存储在 `").append(accountWorkOf(account))
                    .append("/db_storage/message/*.db`（明文 SQLite）。\n\n");

            md.append("## 目录结构\n\n```\n");
            md.append(workRoot).append("/\n");
            md.append("├── README.md          ← 本文件\n");
            md.append("├── INDEX.json         ← 机器可读索引（会话/条数/最近时间）\n");
            md.append("├── .keys              ← 密钥候选（600 权限，勿泄露）\n");
            md.append("└── ").append(WeChatLogService.safeName(account.wxid())).append("/\n");
            md.append("    ├── .sync-state    ← 增量同步指纹（勿删，删了会全量重解）\n");
            md.append("    └── db_storage/message/*.db   ← 明文聊天记录\n");
            md.append("```\n\n");

            md.append("## 怎么查\n\n");
            md.append("- **AI 工具**（推荐）：`wechat_search_messages` / `wechat_list_conversations` / `wechat_read_conversation`\n");
            md.append("- **命令行**：`sqlite3 <上面的 .db> \"SELECT ... FROM Msg_<md5>\"`\n\n");

            md.append("## 当前数据\n\n");
            md.append("| 项 | 值 |\n|---|---|\n");
            md.append("| 账号 | `").append(account.wxid()).append("` |\n");
            md.append("| 生成时间 | ").append(TS.format(Instant.now())).append(" |\n");
            md.append("| 消息总数 | ").append(messages.size()).append(" |\n");
            md.append("| 会话数 | ").append(byConv.size()).append(" |\n");
            long recent = messages.stream().filter(m -> m.createTime() >= d7).count();
            md.append("| 近 7 天消息 | ").append(recent).append(" |\n\n");

            md.append("## 近 7 天有对话的会话\n\n");
            md.append("| 类型 | 会话 ID | 可识别标签（最后发言人+摘要） | 近7天 | 累计 | 最近 |\n");
            md.append("|---|---|---|---|---|---|\n");
            for (var e : sorted) {
                long count7 = e.getValue().stream().filter(m -> m.createTime() >= d7).count();
                if (count7 == 0) {
                    continue;
                }
                long last = lastTime(e.getValue());
                var si = sessions.get(e.getKey());
                md.append("| ").append(e.getKey().endsWith("@chatroom") ? "群" : "单聊")
                        .append(" | `").append(e.getKey()).append("` | ")
                        .append(esc(labelOf(e.getKey(), displayNames, sessions))).append(" | ")
                        .append(count7).append(" | ").append(e.getValue().size()).append(" | ")
                        .append(last > 0 ? TS.format(Instant.ofEpochSecond(last)) : "")
                        .append(" |\n");
            }
            md.append('\n');

            md.append("## ⚠️ 已知限制\n\n");
            md.append("1. **群名来源**：优先取 `contact.db` 的 remark/nick_name（真实群名）；\n");
            md.append("   若 `contact.db` 未解密，则回落到「最后发言人 + 最近消息摘要」。\n");
            md.append("2. **可能有库缺失**：抓取期间未被微信访问的库拿不到密钥。\n");
            md.append("   见 `.sync-state` 里记录的库列表与下方「缺少密钥的库」。\n");
            md.append("3. **微信更新后需要重新取密钥**（版本变化会改变密钥派生方式）。\n\n");

            List<String> noKey = listDatabasesWithoutKey(account);
            if (!noKey.isEmpty()) {
                md.append("### 缺少密钥的库（").append(noKey.size()).append(" 个）\n\n");
                noKey.stream().limit(20).forEach(n -> md.append("- `").append(n).append("`\n"));
                if (noKey.size() > 20) {
                    md.append("- … 还有 ").append(noKey.size() - 20).append(" 个\n");
                }
                md.append('\n');
            }

            Files.writeString(workRoot.resolve("README.md"), md.toString(),
                    StandardCharsets.UTF_8);

            log.info("已写出 AI 可感知索引: {} 与 {}",
                    workRoot.resolve("README.md"), workRoot.resolve("INDEX.json"));
        } catch (IOException e) {
            log.warn("写出 AI 索引失败（不影响已解密的数据）", e);
        }
    }


    /**
     * 会话的展示名。
     *
     * <p>优先级：{@code contact.db} 的 remark/nick_name（真实群名）
     * > session 的「最后发言人 + 摘要」> 空。
     */
    private String labelOf(String convId,
                           Map<String, String> displayNames,
                           Map<String, MessageReader.SessionInfo> sessions) {
        String real = displayNames.get(convId);
        if (real != null && !real.isBlank()) {
            return real;
        }
        var si = sessions.get(convId);
        return si == null ? "" : si.recognisableLabel();
    }
    /**
     * 读已解密的 {@code contact.db}，拿到「会话 id → 真实展示名（群名/备注/昵称）」。
     */
    private Map<String, String> loadContactDisplayNames(WeChatAccount account) {
        Path contactDb = workRoot.resolve(WeChatLogService.safeName(account.wxid()))
                .resolve("db_storage").resolve("contact").resolve("contact.db");
        if (!Files.isRegularFile(contactDb)) {
            log.debug("没有已解密的 contact.db，群名将回落到 session 标签");
            return Map.of();
        }
        return MessageReader.readContactDisplayNames(contactDb);
    }

    /**
     * 读已解密的 {@code session.db}，拿到「会话 → 最后发言人 + 摘要」。
     *
     * <p>为什么需要它：群名在 {@code contact.db}（可能没密钥），但 session 表里的
     * {@code last_sender_display_name} + {@code summary} 组合通常足以让人认出是哪个群。
     */
    private Map<String, MessageReader.SessionInfo> loadSessions(WeChatAccount account) {
        Map<String, MessageReader.SessionInfo> out = new LinkedHashMap<>();
        Path sessionDb = workRoot.resolve(WeChatLogService.safeName(account.wxid()))
                .resolve("db_storage").resolve("session").resolve("session.db");
        if (!Files.isRegularFile(sessionDb)) {
            log.debug("没有已解密的 session.db，群将只显示 ID");
            return out;
        }
        try {
            for (var si : MessageReader.readSessions(sessionDb)) {
                if (si.username() != null) {
                    out.put(si.username(), si);
                }
            }
            log.debug("从 session.db 读到 {} 个会话标签", out.size());
        } catch (Exception e) {
            log.debug("读 session.db 失败（不影响主流程）: {}", e.getMessage());
        }
        return out;
    }

    /** 列出当前缺密钥（即没解出明文）的库。 */
    private List<String> listDatabasesWithoutKey(WeChatAccount account) {
        List<String> out = new ArrayList<>();
        Path accountWork = workRoot.resolve(WeChatLogService.safeName(account.wxid()));
        for (Path db : WeChatDataLocator.listEncryptedDatabases(account.accountDir())) {
            Path plain = accountWork.resolve(account.accountDir().relativize(db));
            if (!Files.isRegularFile(plain)) {
                out.add(db.getFileName().toString());
            }
        }
        return out;
    }

    private String accountWorkOf(WeChatAccount account) {
        return workRoot.resolve(WeChatLogService.safeName(account.wxid())).toString();
    }

    private static long lastTime(List<WeChatMessage> msgs) {
        return msgs.stream().mapToLong(WeChatMessage::createTime).max().orElse(0);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================================================================
    // 状态文件
    // ==================================================================

    private Map<String, String> readState(Path f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (IOException e) {
            log.debug("读同步状态失败，将全量重解: {}", e.getMessage());
        }
        return out;
    }

    private void writeState(Path f, Map<String, String> state) {
        try {
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# 源库变更指纹（relpath=size:mtime:walSize:walMtime）\n");
            state.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("写同步状态失败（下次会全量重解）: {}", e.getMessage());
        }
    }

    private List<byte[]> loadCachedKeys() {
        if (!Files.isRegularFile(keyCacheFile)) {
            return List.of();
        }
        try {
            List<byte[]> out = new ArrayList<>();
            for (String line : Files.readAllLines(keyCacheFile, StandardCharsets.UTF_8)) {
                String h = line.trim().toLowerCase();
                if (h.length() == 64 && h.matches("[0-9a-f]+")) {
                    out.add(WeChatLogService.hexToBytes(h));
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }
}
