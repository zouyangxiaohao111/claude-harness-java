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
 * 内容指纹未变 → 判定「无变化」，不建新版本；但<b>手填了与当前版本不同的版本号时视为明确发版</b>，
 * 即使指纹相同也建新版本（版本号不参与指纹，否则「只改版本号」永远发不出去）。
 *
 * <p><b>回滚</b>：只改清单指向（version/source/fingerprint），**不删任何历史包**
 * （用户裁定：历史全部保留，管理页可视化回滚）。
 *
 * <p><b>指纹口径</b>：按「排序后的相对路径 + 文件字节」逐文件算 SHA-256，
 * <b>不</b>用 zip 字节 —— zip 条目自带 mtime/压缩实现差异，同样内容重打包会得到不同 hash。
 *
 * <p><b>包规范（2026-10-26 用户裁定）</b>：
 * <ul>
 *   <li>裸技能包（源目录没有 {@code plugin.json}）在 zip 内自动补
 *       {@code .claude-plugin/plugin.json}（name/version/description + generatedBy 标记），
 *       让安装目录自描述版本；生成文件不参与指纹（否则市场/本地指纹永远不等 ⇒ 永远显示「可更新」）。</li>
 *   <li>打包与指纹统一排除 {@code __pycache__}/.git/node_modules/.DS_Store/*.pyc，
 *       避免把构建垃圾发布出去。</li>
 * </ul>
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

    /** 自动生成的插件清单（仅当源目录没有用户 plugin.json 时写入 zip）。 */
    static final String GENERATED_MANIFEST_PATH = ".claude-plugin/plugin.json";
    static final String GENERATED_MARKER_FIELD = "generatedBy";
    static final String GENERATED_MARKER_VALUE = "nexusai-publish";

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
            ObjectNode manifest = readManifest();
            ArrayNode plugins = (ArrayNode) manifest.withArray("plugins");
            ObjectNode existing = findEntry(plugins, dirName);
            String currentVersion = existing != null ? existing.path("version").asText("0.1.0") : null;

            // 开发者（市场卡片「开发者」名）：可手填；留空时新条目默认 zjky、已有条目保留原值。
            String requestedAuthor = req.author() == null ? "" : req.author().trim();
            String existingAuthor = existing == null ? null : existing.path("author").asText("zjky");
            String author = requestedAuthor.isEmpty()
                ? (existingAuthor == null ? "zjky" : existingAuthor)
                : requestedAuthor;

            // 手填版本被视为「明确要发这个版本」：即使内容指纹相同也建新版本。
            // 指纹判据只认内容，而版本号在裸技能/自动生成 plugin.json 的包里根本不参与指纹，
            // 因此过去「只改版本号重新上架」必然被判 unchanged（用户 2026-11-15 反馈）。
            String requestedVersion = req.version() == null ? null : req.version().trim();
            boolean explicitNewVersion = requestedVersion != null && !requestedVersion.isEmpty()
                && !requestedVersion.equals(currentVersion);

            // 指纹按「发布产物」口径算（zip 内用户清单的 version 会被覆盖成发行版本），
            // 与安装目录自算的指纹同口径；对比旧条目时用旧条目的 version 参与覆盖，
            // 否则「包内 version 落后于清单」的历史条目会永远被判有变化。
            String fingerprint = productFingerprint(dir, currentVersion);
            if (existing != null && !explicitNewVersion
                    && fingerprint.equals(existing.path("fingerprint").asText(""))) {
                // 只改开发者时内容指纹不变：若不在这里落库，「已有条目改作者」永远被判 unchanged 而改不掉。
                // 作者只属于清单元数据，不改变包内容，因此不新建版本。
                if (!author.equals(existingAuthor)) {
                    existing.put("author", author);
                    existing.put("updatedAt", ISO.format(Instant.now()));
                    writeManifest(manifest);
                    log.info("[MarketPublish] 内容未变，仅更新开发者 plugin={} author={}", dirName, author);
                    return PublishResult.ok(dirName, currentVersion, existing.path("source").asText(""),
                        fingerprint, 0);
                }
                log.info("[MarketPublish] 无变化，跳过上架 plugin={} version={} fingerprint={}",
                    dirName, currentVersion, shortFp(fingerprint));
                return PublishResult.unchanged(dirName, currentVersion, fingerprint);
            }

            String version = resolveVersion(req.version(), currentVersion);
            // 版本确定后再打包：裸技能包需要把 version 写进自动生成的 plugin.json
            String publishedFingerprint = productFingerprint(dir, version);
            byte[] zip = buildZip(dir, dirName, version, req.description());
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
            entry.put("author", author);
            entry.put("category", category);
            ArrayNode tags = entry.putArray("tags");
            List<String> tagList = req.tags() != null && !req.tags().isEmpty()
                ? req.tags()
                : List.of(category);
            tagList.forEach(tags::add);
            entry.put("source", source);
            entry.put("strict", false);
            entry.withArray("dependencies");
            entry.put("fingerprint", publishedFingerprint);
            entry.put("publishedAt", now);
            ArrayNode versions = entry.withArray("versions");
            ObjectNode v = versions.addObject();
            v.put("version", version);
            v.put("source", source);
            v.put("fingerprint", publishedFingerprint);
            v.put("publishedAt", now);
            v.put("size", zip.length);

            writeManifest(manifest);
            log.info("[MarketPublish] 上架成功 plugin={} version={} source={} bytes={} fingerprint={}",
                dirName, version, source, zip.length, shortFp(publishedFingerprint));
            return PublishResult.ok(dirName, version, source, publishedFingerprint, zip.length);
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

    /** 打包（兼容旧调用）：不带自动 plugin.json。 */
    static byte[] buildZip(Path dir) throws IOException {
        return buildZip(dir, null, null, null);
    }

    /**
     * 打包：排除构建垃圾 / 旧包 / 自动生成清单；源目录没有用户 plugin.json 时补一份
     * {@code .claude-plugin/plugin.json}（name/version/description + generatedBy 标记）。
     */
    static byte[] buildZip(Path dir, String pluginName, String version, String description) throws IOException {
        List<Path> files = publishableFiles(dir);
        // 版本以市场清单为准：zip 内的用户 plugin.json 副本把 version 覆盖成发行版本（不动磁盘源文件）。
        // 否则安装链按包内 version 登记，本地版本永远落后于市场 ⇒ 界面反复提示「更新」。
        java.util.Map<String, byte[]> versionOverrides = manifestVersionOverrides(dir, version);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Path f : files) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                byte[] bytes = versionOverrides.containsKey(rel)
                    ? versionOverrides.get(rel) : Files.readAllBytes(f);
                ZipEntry entry = new ZipEntry(rel);
                entry.setTime(0L); // 固定时间戳：同内容重复打包得到同一份 zip 字节
                zos.putNextEntry(entry);
                zos.write(bytes);
                zos.closeEntry();
            }
            if (pluginName != null && !pluginName.isBlank() && version != null && !version.isBlank()
                    && !hasUserPluginManifest(dir)) {
                ObjectNode generated = JSON.createObjectNode();
                generated.put("name", pluginName);
                generated.put("version", version);
                if (description != null && !description.isBlank()) {
                    generated.put("description", description);
                }
                generated.put(GENERATED_MARKER_FIELD, GENERATED_MARKER_VALUE);
                ZipEntry entry = new ZipEntry(GENERATED_MANIFEST_PATH);
                entry.setTime(0L);
                zos.putNextEntry(entry);
                zos.write(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(generated));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * 用户自带 plugin.json 的版本覆盖表（相对路径 → 改写后的字节）。
     *
     * <p>只改 zip 内的副本，源文件保持原样；JSON 非法时跳过该文件（不拦上架）。
     */
    private static java.util.Map<String, byte[]> manifestVersionOverrides(Path dir, String version) throws IOException {
        if (version == null || version.isBlank()) {
            return java.util.Map.of();
        }
        java.util.Map<String, byte[]> out = new java.util.HashMap<>();
        for (String rel : List.of(GENERATED_MANIFEST_PATH, "plugin.json")) {
            Path f = dir.resolve(rel);
            if (!Files.isRegularFile(f) || isGeneratedManifest(dir, f)) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(Files.readAllBytes(f));
                if (node == null || !node.isObject()) {
                    continue;
                }
                // 版本本来就一致 → 保留原字节：重新序列化会改变字节，让老条目误判「内容变了」
                if (version.equals(node.path("version").asText(""))) {
                    continue;
                }
                ((ObjectNode) node).put("version", version);
                out.put(rel, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
            } catch (Exception e) {
                log.warn("[MarketPublish] 包内清单无法解析，保留原版本号 path={}: {}", f, e.getMessage());
            }
        }
        return out;
    }

    /** 发布产物指纹：zip 内用户清单 version 会被打成发行版本，与安装目录自算的指纹同口径。 */
    static String productFingerprint(Path dir, String version) throws IOException {
        return fingerprint(dir, manifestVersionOverrides(dir, version));
    }

    /** 内容指纹：排序后的「相对路径 + 文件字节」逐个喂进 SHA-256（不依赖 zip 元数据）。 */
    public static String fingerprint(Path dir) throws IOException {
        return fingerprint(dir, java.util.Map.of());
    }

    /** 指纹主体：overrides = zip 内被改写的相对路径 → 字节（未命中的文件读磁盘原字节）。 */
    private static String fingerprint(Path dir, java.util.Map<String, byte[]> overrides) throws IOException {
        List<Path> files = publishableFiles(dir);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (Path f : files) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            md.update(rel.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(overrides.containsKey(rel) ? overrides.get(rel) : Files.readAllBytes(f));
            md.update((byte) 0);
        }
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /** 打包/指纹共用的文件集：排序 + 排除构建垃圾 / 旧包 / 自动生成清单。 */
    private static List<Path> publishableFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                .filter(p -> isPublishable(dir, p))
                .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                .toList();
        }
    }

    /** 是否进入包/指纹：排除 .git、node_modules、__pycache__、.DS_Store、*.pyc、旧 plugin.zip。 */
    private static boolean isPublishable(Path root, Path file) {
        Path relPath = root.relativize(file);
        for (Path seg : relPath) {
            String s = seg.toString();
            if (s.equals(".git") || s.equals("node_modules") || s.equals("__pycache__") || s.equals(".DS_Store")) {
                return false;
            }
        }
        String rel = relPath.toString().replace('\\', '/');
        if (rel.equals("plugin.zip") || rel.endsWith(".pyc")) {
            return false;
        }
        return !isGeneratedManifest(root, file);
    }

    /** 源目录是否已有用户自己的 plugin.json（自动生成的标记清单不算，会用新版本重新生成）。 */
    private static boolean hasUserPluginManifest(Path dir) {
        for (String rel : List.of(GENERATED_MANIFEST_PATH, "plugin.json")) {
            Path f = dir.resolve(rel);
            if (Files.isRegularFile(f) && !isGeneratedManifest(dir, f)) {
                return true;
            }
        }
        return false;
    }

    /** 自动生成的清单带 generatedBy 标记；指纹必须忽略它，否则市场/本地指纹永远不等。 */
    private static boolean isGeneratedManifest(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        if (!GENERATED_MANIFEST_PATH.equals(rel)) {
            return false;
        }
        try {
            JsonNode n = JSON.readTree(Files.readAllBytes(file));
            return GENERATED_MARKER_VALUE.equals(n.path(GENERATED_MARKER_FIELD).asText());
        } catch (Exception e) {
            return false;
        }
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
    /**
     * 上架请求。
     *
     * @param author 市场卡片「开发者」名（可选）：留空时新条目默认 {@code zjky}、已有条目保留原值
     */
    public record PublishRequest(String sourcePath, String name, String displayName,
                                 String description, String category, List<String> tags, String version,
                                 String author) {
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
            return new PublishResult("unchanged",
                "内容与当前版本 " + version + " 完全一致，未创建新版本；如需发新版本请填写新的版本号",
                name, version, null, fp, 0);
        }

        static PublishResult fail(String message) {
            return new PublishResult("failed", message, null, null, null, null, 0);
        }
    }
}
