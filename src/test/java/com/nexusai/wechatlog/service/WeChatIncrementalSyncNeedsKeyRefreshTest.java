package com.nexusai.wechatlog.service;

import com.nexusai.wechatlog.locate.WeChatAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量同步的「要不要重新取密钥」判据。
 *
 * <p>旧实现靠 note 子串「没有缓存密钥」判断，导致「缓存非空但全部失配」时
 * 直接返回 0 条、永远修不好。现在改为结构化 {@code needsKeyRefresh}。
 */
class WeChatIncrementalSyncNeedsKeyRefreshTest {

    static final class Fixture {
        final Path root;
        final Path workRoot;
        final Path keyCache;
        final Path acct;
        final Path db;
        final WeChatAccount account;

        Fixture() throws IOException {
            root = Files.createTempDirectory("nexusai-incr-");
            workRoot = Files.createDirectories(root.resolve("work"));
            keyCache = workRoot.resolve(".keys");
            acct = Files.createDirectories(root.resolve("wxid_test"));
            Path storage = Files.createDirectories(acct.resolve("db_storage"));
            db = storage.resolve("x.db");
            // 5000 字节全零：满足「>=4096 且头不是 SQLite」的最小加密库判定
            Files.write(db, new byte[5000]);
            account = new WeChatAccount(root.resolve("xwechat_files"), acct, "wxid_test", 4);
        }

        WeChatIncrementalSync sync() {
            return new WeChatIncrementalSync(workRoot, keyCache);
        }
    }

    @Test
    @DisplayName("没有缓存密钥 ⇒ needsKeyRefresh=true")
    void noCacheNeedsRefresh() throws IOException {
        Fixture f = new Fixture();
        var out = f.sync().syncIncremental(f.account, false);
        assertTrue(out.needsKeyRefresh());
        assertTrue(out.note().contains("没有缓存密钥"));
    }

    @Test
    @DisplayName("缓存非空但全部失配 ⇒ needsKeyRefresh=true（旧实现会静默 0 条）")
    void badCacheNeedsRefresh() throws IOException {
        Fixture f = new Fixture();
        Files.writeString(f.keyCache, "11".repeat(32) + "\n");

        var out = f.sync().syncIncremental(f.account, false);

        assertTrue(out.needsKeyRefresh(), "坏缓存必须触发重新取密钥");
        assertTrue(out.note().contains("匹配不到密钥"), out.note());
    }

    @Test
    @DisplayName("无变化 ⇒ needsKeyRefresh=false")
    void noChangesNeedsNoRefresh() throws IOException {
        Fixture f = new Fixture();
        Files.writeString(f.keyCache, "11".repeat(32) + "\n");

        Path state = f.workRoot.resolve("wxid_test").resolve(".sync-state");
        Files.createDirectories(state.getParent());
        String rel = f.acct.relativize(f.db).toString();
        String enc = WeChatIncrementalSync.fingerprint(f.db).encode();
        Files.writeString(state, rel + "=" + enc + "\n");

        var out = f.sync().syncIncremental(f.account, false);

        assertFalse(out.needsKeyRefresh());
        assertEquals("无变化", out.note());
    }
}
