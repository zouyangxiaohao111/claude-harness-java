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
        // 构建垃圾：不得发布（2026-10-26 用户实测包里带进了 scripts/__pycache__）
        Files.createDirectories(dir.resolve("scripts/__pycache__"));
        Files.writeString(dir.resolve("scripts/__pycache__/x.cpython-312.pyc"), "pyc");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), "git");
        Files.createDirectories(dir.resolve("node_modules/pkg"));
        Files.writeString(dir.resolve("node_modules/pkg/index.js"), "js");
        Files.writeString(dir.resolve(".DS_Store"), "ds");

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
        assertTrue(names.stream().noneMatch(n -> n.contains("__pycache__")), "__pycache__ 不得发布：" + names);
        assertTrue(names.stream().noneMatch(n -> n.startsWith(".git/")), ".git 不得发布：" + names);
        assertTrue(names.stream().noneMatch(n -> n.startsWith("node_modules/")), "node_modules 不得发布：" + names);
        assertFalse(names.contains(".DS_Store"), ".DS_Store 不得发布");

        // 同内容重复打包 → 字节相同（entry 时间戳已固定），这样「上传后重算」不会误判变更
        assertEquals(new String(zip, StandardCharsets.ISO_8859_1),
            new String(MarketPublishService.buildZip(dir), StandardCharsets.ISO_8859_1));
    }

    @Test
    void bareSkillPackage_getsGeneratedPluginManifest_withVersion() throws Exception {
        // WHY：裸技能包没有 plugin.json，安装目录无法自描述 name/version（用户 2026-10-26 反馈）。
        Path dir = tempDir.resolve("skill-c");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# C\n");

        byte[] zip = MarketPublishService.buildZip(dir, "skill-c", "0.1.1", "测试技能");
        String manifest = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (MarketPublishService.GENERATED_MANIFEST_PATH.equals(e.getName())) {
                    manifest = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(manifest != null, "裸技能包必须自动补 .claude-plugin/plugin.json");
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(manifest);
        assertEquals("skill-c", n.path("name").asText());
        assertEquals("0.1.1", n.path("version").asText());
        assertEquals("测试技能", n.path("description").asText());
        assertEquals(MarketPublishService.GENERATED_MARKER_VALUE,
            n.path(MarketPublishService.GENERATED_MARKER_FIELD).asText());
    }

    @Test
    void userManifest_versionInZipFollowsPublishedVersion() throws Exception {
        // WHY：专家包自带 plugin.json 时，安装链按包内 version 登记。
        // 手填新版本上传若不同步包内版本，本地版本永远落后于市场 ⇒ 界面反复提示「更新」。
        Path dir = tempDir.resolve("expert-a");
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.createDirectories(dir.resolve("agents"));
        Files.writeString(dir.resolve("agents/a.md"), "# a\n");
        Path manifestFile = dir.resolve(".claude-plugin/plugin.json");
        Files.writeString(manifestFile, "{\"name\":\"expert-a\",\"version\":\"0.1.4\",\"main\":\"agents/a.md\"}");

        String manifest = readZipEntry(MarketPublishService.buildZip(dir, "expert-a", "1.1.0", null),
            ".claude-plugin/plugin.json");
        com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(manifest);
        assertEquals("1.1.0", n.path("version").asText(), "zip 内清单版本必须与发行版本一致");
        assertEquals("agents/a.md", n.path("main").asText(), "其余字段必须原样保留");
        assertTrue(Files.readString(manifestFile).contains("0.1.4"), "不能改写磁盘上的源文件");
    }

    @Test
    void productFingerprint_matchesInstalledDirectory_afterVersionOverride() throws Exception {
        // WHY：客户端用「市场清单指纹 vs 本地安装目录指纹」判定更新。
        // 两者若不同口径（包内 version 被改成发行版本，清单一侧却没算这个改动），
        // 用户装完仍会看到「可更新」，点多少次都消不掉。
        Path dir = tempDir.resolve("expert-b");
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
            "{\"name\":\"expert-b\",\"version\":\"0.1.4\",\"main\":\"agents/b.md\"}");
        Files.createDirectories(dir.resolve("agents"));
        Files.writeString(dir.resolve("agents/b.md"), "# b\n");

        byte[] zip = MarketPublishService.buildZip(dir, "expert-b", "1.1.0", null);
        Path installed = tempDir.resolve("installed-b");
        unzip(zip, installed);

        assertEquals(MarketPublishService.productFingerprint(dir, "1.1.0"),
            MarketPublishService.fingerprint(installed),
            "市场清单指纹必须等于安装目录自算指纹");

        // 包内版本本来就一致 → 不改写（重新序列化会改变字节，老条目会被误判「内容变了」）
        assertEquals(MarketPublishService.fingerprint(dir), MarketPublishService.productFingerprint(dir, "0.1.4"),
            "包内版本与发行版本一致时指纹必须与源目录原样一致");
    }

    @Test
    void fingerprint_ignoresAutoGeneratedManifest_butIncludesUserManifest() throws Exception {
        Path dir = tempDir.resolve("skill-d");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# D\n");
        String base = MarketPublishService.fingerprint(dir);

        // 自动生成清单（带 marker）不参与指纹 —— 否则市场包/本地安装目录指纹永远不等
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
            "{\"name\":\"skill-d\",\"version\":\"0.1.1\",\""
                + MarketPublishService.GENERATED_MARKER_FIELD + "\":\""
                + MarketPublishService.GENERATED_MARKER_VALUE + "\"}");
        assertEquals(base, MarketPublishService.fingerprint(dir), "自动生成清单必须不参与指纹");

        // 用户自己写的 plugin.json（无 marker）是内容的一部分，必须参与指纹
        Path userDir = tempDir.resolve("skill-e");
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("SKILL.md"), "# D\n");
        String userBase = MarketPublishService.fingerprint(userDir);
        Files.createDirectories(userDir.resolve(".claude-plugin"));
        Files.writeString(userDir.resolve(".claude-plugin/plugin.json"), "{\"name\":\"skill-e\"}");
        assertNotEquals(userBase, MarketPublishService.fingerprint(userDir), "用户 plugin.json 必须参与指纹");
    }

    /** 解压到目录（模拟安装链落盘）。 */
    private static void unzip(byte[] zip, Path target) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                Path out = target.resolve(e.getName());
                Files.createDirectories(out.getParent());
                Files.write(out, zis.readAllBytes());
            }
        }
    }

    /** 读取 zip 内指定条目（不存在 → null）。 */
    private static String readZipEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (name.equals(e.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
