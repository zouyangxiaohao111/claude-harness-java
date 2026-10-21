package com.nexusai.application.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 市场上架的核心判定单测。
 *
 * <p>验证意图（规则九）：这三条是「市场上架后的管理问题」的支点 ——
 * 版本号怎么涨、指纹准不准、包内容对不对。任何一条错了都会表现为
 * 「明明更新了市场却说没变」或「装着旧版本找不到回滚点」，而这在 UI 上不可见。
 */
class MarketPublishServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void version_prefers_manual_then_patch_bump() {
        // 用户裁定：手填优先；不填默认 +1
        assertEquals("0.2.0", MarketPublishService.resolveVersion("0.2.0", "0.1.3"), "手填版本必须原样使用");
        assertEquals("0.1.4", MarketPublishService.resolveVersion(null, "0.1.3"), "不填 → patch +1");
        assertEquals("0.1.4", MarketPublishService.resolveVersion("  ", "0.1.3"), "空白等同不填");
        assertEquals("0.1.0", MarketPublishService.resolveVersion(null, null), "首次上架 → 0.1.0");
        assertEquals("0.1.0", MarketPublishService.resolveVersion(null, "abc"), "非语义化版本 → 回落 0.1.0");
    }

    @Test
    void fingerprint_is_stable_across_repackaging_but_changes_with_content() throws Exception {
        Path dir = tempDir.resolve("skill-a");
        Files.createDirectories(dir.resolve("references"));
        Files.writeString(dir.resolve("SKILL.md"), "# A\n");
        Files.writeString(dir.resolve("references/r.md"), "ref");

        String first = MarketPublishService.fingerprint(dir);
        // 仅触碰 mtime 不改内容 → 指纹必须不变（否则每次上架都会误判为「有更新」）
        Thread.sleep(1100);
        Files.setLastModifiedTime(dir.resolve("SKILL.md"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        String second = MarketPublishService.fingerprint(dir);
        assertEquals(first, second, "同样内容 + 不同 mtime 必须得到同一指纹");

        Files.writeString(dir.resolve("SKILL.md"), "# A changed\n");
        assertNotEquals(first, MarketPublishService.fingerprint(dir), "内容变了指纹必须变");
    }

    @Test
    void zip_excludes_self_and_uses_forward_slash_paths() throws Exception {
        Path dir = tempDir.resolve("skill-b");
        Files.createDirectories(dir.resolve("skills/demo"));
        Files.writeString(dir.resolve("skills/demo/SKILL.md"), "# demo");
        Files.writeString(dir.resolve("plugin.zip"), "旧包，不得被打进新包");

        byte[] zip = MarketPublishService.buildZip(dir);
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        assertTrue(names.contains("skills/demo/SKILL.md"), "必须包含技能正文，实得：" + names);
        assertFalse(names.contains("plugin.zip"), "不得把已存在的 plugin.zip 打进包里（会自我嵌套膨胀）");
        assertTrue(names.stream().noneMatch(n -> n.contains("\\")), "zip 内路径必须用 / 分隔");

        // 同内容重复打包 → 字节相同（entry 时间戳已固定），这样「上传后重算」不会误判变更
        assertEquals(new String(zip, StandardCharsets.ISO_8859_1),
            new String(MarketPublishService.buildZip(dir), StandardCharsets.ISO_8859_1));
    }
}
