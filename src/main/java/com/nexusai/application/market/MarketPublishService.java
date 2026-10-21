package com.nexusai.application.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 插件市场上架 / 版本管理 / 回滚。
 *
 * <p><b>管理问题的由来</b>（用户 2026-10-21）：同一个技能内容更新后，指纹会变，
 * 而市场清单原先只按 {@code name} 存一条、{@code source} 指向固定版本目录 ——
 * 版本一多就分不清「当前是哪个版本、旧版在哪、能不能回滚」。本服务把三条信息一起落进清单：
 * {@code version}（当前生效版本）、{@code fingerprint}（内容指纹）、{@code versions[]}（全部历史）。
 *
 * <p><b>版本策略</b>（用户裁定）：上架时手填版本；不填则对当前版本 patch +1（0.1.0 → 0.1.1）。
 * 内容指纹未变 → 直接判定「无变化」，不建新版本。
 *
 * <p><b>回滚</b>：只改清单指向（version/source/fingerprint），**不删任何历史包**
 * （用户裁定：历史全部保留，管理页可视化回滚）。
 *
 * <p><b>指纹口径</b>：按「排序后的相对路径 + 文件字节」逐文件算 SHA-256，
 * <b>不</b>用 zip 字节 —— zip 条目自带 mtime/压缩实现差异，同样内容重打包会得到不同 hash。
 */
@Service
public class MarketPublishService {

    private static final Logger log = LoggerFactory.getLogger(MarketPublishService.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 桶内前缀（与 marketplace/ 源目录同名 · publish_marketplace.py 的 KEY_PREFIX 一致）。 */
    private static final String KEY_PREFIX = "marketplace";
    private static final String MANIFEST_KEY = KEY_PREFIX + "/marketplace.json";
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final MinioUploader uploader;

    /**
     * 构造：MinIO 连接取 yml/环境变量，缺省即当前内网市场（与 publish_marketplace.py 同源）。
     */
    public MarketPublishService(
            @Value("${nexusai.market.minio.endpoint:http://192.168.20.125:9000}") String endpoint,
            @Value("${nexusai.market.minio.bucket:nexusai}") String bucket,
            @Value("${nexusai.market.minio.access-key:XSnCKcUT4Z5M2BbYIRQP}") String accessKey,
            @Value("${nexusai.market.minio.secret-key:UbeHwkVka2CGm0mvp51ADYIKppkr9GS2gadE8EFf}") String secretKey,
            @Value("${nexusai.market.minio.region:us-east-1}") String region) {
        this.uploader = new MinioUploader(endpoint, bucket, accessKey, secretKey, region);
    }

    // ── 上架 ───────────────────────────────────────────────────────────────

    /**
     * 上架一个本地技能/专家目录。
     *
     * @param req 上架请求
     * @return 结果（状态 + 生效版本 + 指纹）
     */
    public PublishResult publish(PublishRequest req) {
        if (req == null || req.sourcePath() == null || req.sourcePath().isBlank()) {
            return PublishResult.fail("sourcePath 不能为空");
        }
        Path dir = Path.of(req.sourcePath().trim());
        if (!Files.isDirectory(dir)) {
            return PublishResult.fail("目录不存在：" + dir);
        }
        String category = (req.category() == null || req.category().isBlank()) ? "skill" : req.category().trim();
        String dirName = req.name() != null && !req.name().isBlank() ? req.name().trim() : dir.getFileName().toString();
        String problem = validatePluginDir(dir);
        if (problem != null) {
            return PublishResult.fail(problem);
        }

        try {
            byte[] zip = buildZip(dir);
            String fingerprint = fingerprint(dir);
            ObjectNode manifest = readManifest();
            ArrayNode plugins = (ArrayNode) manifest.withArray("plugins");
            ObjectNode existing = findEntry(plugins, dirName);

            if (existing != null && fingerprint.equals(existing.path("fingerprint").asText(""))) {
                log.info("[MarketPublish] 无变化，跳过上架 plugin={} fingerprint={}", dirName, shortFp(fingerprint));
                return PublishResult.unchanged(dirName, existing.path("version").asText(), fingerprint);
            }

            String currentVersion = existing != null ? existing.path("version").asText("0.1.0") : null;
            String version = resolveVersion(req.version(), currentVersion);
            String source = categoryDir(category) + "/" + dirName + "/" + version;
            String now = ISO.format(Instant.now());

            // 先传包，再更新清单：清单先改而包传失败会让市场指向不存在的对象
            uploader.upload(KEY_PREFIX + "/" + source + "/plugin.zip", zip, "application/zip");

            ObjectNode entry = existing != null ? existing : plugins.addObject();
            entry.put("name", dirName);
            entry.put("displayName", blankTo(req.displayName(), existing == null ? dirName : existing.path("displayName").asText(dirName)));
            entry.put("description", blankTo(req.description(), existing == null ? "" : existing.path("description").asText("")));
            entry.put("version", version);
            entry.put("createdAt", existing == null ? now : existing.path("createdAt").asText(now));
            entry.put("updatedAt", now);
            entry.put("author", existing == null ? "zjky" : existing.path("author").asText("zjky"));
            entry.put("category", category);
            ArrayNode tags = entry.putArray("tags");
            List<String> tagList = req.tags() != null && !req.tags().isEmpty()
                ? req.tags()
                : List.of(category);
            tagList.forEach(tags::add);
            entry.put("source", source);
            entry.put("strict", false);
            entry.withArray("dependencies");
            entry.put("fingerprint", fingerprint);
            entry.put("publishedAt", now);
            ArrayNode versions = entry.withArray("versions");
            ObjectNode v = versions.addObject();
            v.put("version", version);
            v.put("source", source);
            v.put("fingerprint", fingerprint);
            v.put("publishedAt", now);
            v.put("size", zip.length);

            writeManifest(manifest);
            log.info("[MarketPublish] 上架成功 plugin={} version={} source={} bytes={} fingerprint={}",
                dirName, version, source, zip.length, shortFp(fingerprint));
            return PublishResult.ok(dirName, version, source, fingerprint, zip.length);
        } catch (IOException e) {
            log.error("[MarketPublish] 上架失败 plugin={}: {}", dirName, e.getMessage());
            return PublishResult.fail("上架失败：" + e.getMessage());
        }
    }

    // ── 管理视图 ───────────────────────────────────────────────────────────

    /** 市场清单（管理页数据源）· 原样返回，前端按 name 展示版本历史。 */
    public JsonNode manage() throws IOException {
        return readManifest();
    }

    // ── 回滚 ───────────────────────────────────────────────────────────────

    /**
     * 回滚到指定历史版本：只把清单的 version/source/fingerprint 指回旧版本，不删任何包。
     *
     * @param name    插件名
     * @param version 目标版本
     */
    public PublishResult rollback(String name, String version) {
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            return PublishResult.fail("name / version 不能为空");
        }
        try {
            ObjectNode manifest = readManifest();
            ArrayNode plugins = (ArrayNode) manifest.withArray("plugins");
            ObjectNode entry = findEntry(plugins, name.trim());
            if (entry == null) {
                return PublishResult.fail("市场中没有插件：" + name);
            }
            ObjectNode target = null;
            for (JsonNode v : entry.path("versions")) {
                if (version.trim().equals(v.path("version").asText(""))) {
                    target = (ObjectNode) v;
                    break;
                }
            }
            if (target == null) {
                return PublishResult.fail("没有该历史版本：" + version);
            }
            String now = ISO.format(Instant.now());
            entry.put("version", target.path("version").asText());
            entry.put("source", target.path("source").asText());
            entry.put("fingerprint", target.path("fingerprint").asText());
            entry.put("updatedAt", now);
            writeManifest(manifest);
            log.info("[MarketPublish] 回滚成功 plugin={} version={} source={}",
                name, version, target.path("source").asText());
            return PublishResult.ok(name.trim(), version.trim(), target.path("source").asText(),
                target.path("fingerprint").asText(), target.path("size").asLong(0));
        } catch (IOException e) {
            log.error("[MarketPublish] 回滚失败 plugin={} version={}: {}", name, version, e.getMessage());
            return PublishResult.fail("回滚失败：" + e.getMessage());
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /** 目录合法性：至少要有 SKILL.md / plugin.json / agents 之一，否则装上去也是空壳。 */
    private static String validatePluginDir(Path dir) {
        boolean ok = Files.isRegularFile(dir.resolve("SKILL.md"))
            || Files.isRegularFile(dir.resolve(".claude-plugin").resolve("plugin.json"))
            || Files.isRegularFile(dir.resolve("plugin.json"))
            || Files.isDirectory(dir.resolve("agents"))
            || Files.isDirectory(dir.resolve("skills"));
        return ok ? null : "目录里既没有 SKILL.md，也没有 plugin.json / agents/ / skills/，不是可上架的插件目录";
    }

    /** 分类 → 目录名（清单 source 前缀，与既有清单 `skills/...`、`experts/...` 对齐）。 */
    private static String categoryDir(String category) {
        return switch (category) {
            case "expert" -> "experts";
            case "connector" -> "connectors";
            default -> "skills";
        };
    }

    /** 打包：目录下全部文件（排除 plugin.zip），相对路径，按名排序保证稳定。 */
    static byte[] buildZip(Path dir) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(p -> !"plugin.zip".equals(p.getFileName().toString()))
                .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                .toList();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Path f : files) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(rel);
                entry.setTime(0L); // 固定时间戳：同内容重复打包得到同一份 zip 字节
                zos.putNextEntry(entry);
                zos.write(Files.readAllBytes(f));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 内容指纹：排序后的「相对路径 + 文件字节」逐个喂进 SHA-256（不依赖 zip 元数据）。 */
    public static String fingerprint(Path dir) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(p -> !"plugin.zip".equals(p.getFileName().toString()))
                .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                .toList();
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (Path f : files) {
            md.update(dir.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Files.readAllBytes(f));
            md.update((byte) 0);
        }
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /** 版本决策：手填优先；未填 → patch +1；无历史 → 0.1.0。 */
    static String resolveVersion(String requested, String current) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        if (current == null || current.isBlank()) {
            return "0.1.0";
        }
        String[] parts = current.trim().split("\\.");
        if (parts.length != 3) {
            return "0.1.0";
        }
        try {
            int patch = Integer.parseInt(parts[2]);
            return parts[0] + "." + parts[1] + "." + (patch + 1);
        } catch (NumberFormatException e) {
            return "0.1.0";
        }
    }

    private ObjectNode readManifest() throws IOException {
        byte[] bytes;
        try {
            bytes = uploader.download(MANIFEST_KEY);
        } catch (java.io.FileNotFoundException e) {
            log.warn("[MarketPublish] 远端清单不存在，按空清单初始化：{}", MANIFEST_KEY);
            ObjectNode empty = JSON.createObjectNode();
            empty.put("name", "zjky-market");
            empty.put("version", "0.1.0");
            empty.putArray("plugins");
            return empty;
        }
        JsonNode root = JSON.readTree(bytes);
        if (root == null || !root.isObject()) {
            throw new IOException("市场清单格式非法（不是 JSON 对象）");
        }
        if (!root.has("plugins") || !root.get("plugins").isArray()) {
            ((ObjectNode) root).putArray("plugins");
        }
        return (ObjectNode) root;
    }

    private void writeManifest(ObjectNode manifest) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        uploader.upload(MANIFEST_KEY, bytes, "application/json; charset=utf-8");
    }

    private static ObjectNode findEntry(ArrayNode plugins, String name) {
        for (JsonNode p : plugins) {
            if (name.equals(p.path("name").asText(""))) {
                return (ObjectNode) p;
            }
        }
        return null;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String shortFp(String fp) {
        return fp == null || fp.length() <= 19 ? fp : fp.substring(0, 19) + "…";
    }

    // ── DTO ────────────────────────────────────────────────────────────────

    /**
     * 上架请求。
     *
     * @param sourcePath  本地目录绝对路径
     * @param name        市场条目名（空 → 取目录名）
     * @param displayName 展示名
     * @param description 描述
     * @param category    skill | expert | connector
     * @param tags        标签
     * @param version     版本（空 → 自动 patch +1）
     */
    public record PublishRequest(String sourcePath, String name, String displayName,
                                 String description, String category, List<String> tags, String version) {
    }

    /**
     * 上架/回滚结果。
     *
     * @param status    ok | unchanged | failed
     * @param message   人类可读说明
     * @param name      插件名
     * @param version   生效版本
     * @param source    市场相对目录
     * @param fingerprint 内容指纹
     * @param size      包大小（字节）
     */
    public record PublishResult(String status, String message, String name, String version,
                                String source, String fingerprint, long size) {

        static PublishResult ok(String name, String version, String source, String fp, long size) {
            return new PublishResult("ok", "上架成功", name, version, source, fp, size);
        }

        static PublishResult unchanged(String name, String version, String fp) {
            return new PublishResult("unchanged", "内容无变化，未创建新版本", name, version, null, fp, 0);
        }

        static PublishResult fail(String message) {
            return new PublishResult("failed", message, null, null, null, null, 0);
        }
    }
}
