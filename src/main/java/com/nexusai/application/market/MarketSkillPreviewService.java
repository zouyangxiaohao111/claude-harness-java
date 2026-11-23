package com.nexusai.application.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.plugin.MarketplaceHttpDownloader;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.market.dto.MarketSkillPreviewDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 市场技能预览（[2026-11-23]）· 从 marketplace 的 plugin.zip 取 {@code skills/<slug>/SKILL.md}。
 *
 * <p>复用 {@link MarketplaceHttpDownloader}（下载+解压）；内存缓存（TTL 10 分钟、最多 100 条），
 * 正文与图标都有大小上限（512KB / 128KB）。⛔ 不写 {@code ~/.nexusai/mcp.json}，临时目录用完即删。
 */
@Service
public class MarketSkillPreviewService {

    private static final Logger log = LoggerFactory.getLogger(MarketSkillPreviewService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final long MAX_CONTENT_BYTES = 512 * 1024L;
    private static final long MAX_ICON_BYTES = 128 * 1024L;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int CACHE_MAX = 100;

    private final MarketplaceHttpDownloader downloader;
    private final String base;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public MarketSkillPreviewService(
            @Value("${nexusai.market.minio.endpoint:http://192.168.20.125:9000}") String endpoint,
            @Value("${nexusai.market.minio.bucket:nexusai}") String bucket,
            @Value("${nexusai.market.skill-preview-base:}") String previewBase) {
        this(new MarketplaceHttpDownloader(), resolveBase(endpoint, bucket, previewBase));
    }

    /** 测试注入：自定义 downloader + base（本地假市场）。 */
    MarketSkillPreviewService(MarketplaceHttpDownloader downloader, String base) {
        this.downloader = downloader;
        this.base = stripTrailingSlash(base);
    }

    /**
     * 预览技能正文。
     *
     * @param name    技能名（marketplace entry.name）
     * @param version 版本（可空；空则读 marketplace.json）
     * @param source  清单里的 source（可空；如 {@code skills/foo/0.1.0}）
     * @param url     完整 plugin.zip URL（可空；host 必须与市场同源）
     */
    public MarketSkillPreviewDto preview(String name, String version, String source, String url) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new ValidationException("技能名非法（只允许字母数字._-）：" + name);
        }
        String zipUrl;
        String resolvedVersion = version;
        if (url != null && !url.isBlank()) {
            zipUrl = assertAllowedUrl(url);
        } else if (source != null && !source.isBlank()) {
            zipUrl = base + "/" + stripSlashes(source) + "/plugin.zip";
        } else if (version != null && !version.isBlank()) {
            zipUrl = base + "/skills/" + name + "/" + version + "/plugin.zip";
        } else {
            ManifestHit hit = resolveFromManifest(name);
            zipUrl = hit.url();
            resolvedVersion = hit.version();
        }
        CacheEntry cached = cache.get(zipUrl);
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_TTL_MS) {
            MarketSkillPreviewDto d = cached.dto;
            return new MarketSkillPreviewDto(d.name(), resolvedVersion != null ? resolvedVersion : d.version(),
                    d.slug(), d.markdown(), d.frontmatter(), d.iconDataUrl(), d.contentLength(), true);
        }
        MarketSkillPreviewDto dto = downloadAndParse(name, resolvedVersion, zipUrl);
        cache.put(zipUrl, new CacheEntry(dto, System.currentTimeMillis()));
        pruneCache();
        return dto;
    }

    private MarketSkillPreviewDto downloadAndParse(String name, String version, String zipUrl) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("nexusai-skill-preview-");
            downloader.downloadArchive(zipUrl, null, dir.toString());
            Path skillMd = findSkillMd(dir, name);
            if (skillMd == null) {
                throw new BadGatewayException("插件包内未找到 skills/*/SKILL.md");
            }
            if (Files.size(skillMd) > MAX_CONTENT_BYTES) {
                throw new ValidationException("SKILL.md 超过 512KB 上限");
            }
            String markdown = Files.readString(skillMd, StandardCharsets.UTF_8);
            String slug = skillMd.getParent().getFileName().toString();
            ParseSkillFrontmatter.ParsedMarkdown parsed =
                    new ParseSkillFrontmatter().parseFrontmatter(markdown, zipUrl);
            String icon = findIconDataUrl(dir, skillMd.getParent());
            log.info("[MarketSkillPreview] name={} version={} slug={} len={} icon={}",
                    name, version, slug, markdown.getBytes(StandardCharsets.UTF_8).length, icon != null);
            return new MarketSkillPreviewDto(name, version, slug, markdown,
                    parsed.frontmatter() == null ? Map.of() : parsed.frontmatter(),
                    icon, markdown.getBytes(StandardCharsets.UTF_8).length, false);
        } catch (ValidationException | NotFoundException | BadGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new BadGatewayException("市场技能预览失败：" + e.getMessage(), e);
        } finally {
            if (dir != null) {
                deleteQuietly(dir);
            }
        }
    }

    private static Path findSkillMd(Path dir, String name) {
        try (var stream = Files.walk(dir, 5)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> "SKILL.md".equalsIgnoreCase(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            Path fallback = null;
            for (Path p : candidates) {
                Path parent = p.getParent();
                String slug = parent == null || parent.getFileName() == null
                        ? "" : parent.getFileName().toString();
                if (".claude-plugin".equalsIgnoreCase(slug)) {
                    continue;
                }
                if (fallback == null) {
                    fallback = p;
                }
                if (slug.equals(name) || slug.endsWith("-" + name) || name.endsWith(slug)) {
                    return p;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String findIconDataUrl(Path dir, Path skillDir) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(dir.resolve("icon.svg"));
        candidates.add(dir.resolve("icon.png"));
        if (skillDir != null) {
            candidates.add(skillDir.resolve("icon.svg"));
            candidates.add(skillDir.resolve("icon.png"));
        }
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                if (Files.size(p) > MAX_ICON_BYTES) {
                    continue;
                }
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                String mime = name.endsWith(".svg") ? "image/svg+xml" : "image/png";
                return "data:" + mime + ";base64," + Base64.getEncoder()
                        .encodeToString(Files.readAllBytes(p));
            } catch (Exception ignored) {
                // 图标失败不阻断预览
            }
        }
        return null;
    }

    private ManifestHit resolveFromManifest(String name) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("nexusai-market-manifest-");
            String path = downloader.downloadMarketplace(base + "/marketplace.json", null,
                    tmp.toString());
            JsonNode root = JSON.readTree(Files.readString(Path.of(path), StandardCharsets.UTF_8));
            JsonNode arr = root.has("plugins") ? root.get("plugins") : root.path("data").get("plugins");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (!name.equals(n.path("name").asText(""))) {
                        continue;
                    }
                    String version = n.path("version").asText(null);
                    String source = n.path("source").asText(null);
                    if (source == null || source.isBlank()) {
                        String category = n.path("category").asText("skill");
                        String dirName = switch (category) {
                            case "expert" -> "experts";
                            case "connector" -> "connectors";
                            default -> "skills";
                        };
                        source = dirName + "/" + name + "/" + version;
                    }
                    return new ManifestHit(base + "/" + stripSlashes(source) + "/plugin.zip", version);
                }
            }
            throw new NotFoundException("市场清单中没有技能：" + name);
        } catch (NotFoundException | ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new BadGatewayException("读取市场清单失败：" + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
        }
    }

    private String assertAllowedUrl(String url) {
        try {
            URI u = URI.create(url);
            URI b = URI.create(base);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new ValidationException("plugin.zip URL 必须是 http(s)");
            }
            if (u.getHost() == null || !u.getHost().equalsIgnoreCase(b.getHost())) {
                throw new ValidationException("plugin.zip URL 必须与市场同源");
            }
            return url;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("plugin.zip URL 非法：" + url);
        }
    }

    private static String resolveBase(String endpoint, String bucket, String previewBase) {
        if (previewBase != null && !previewBase.isBlank()) {
            return stripTrailingSlash(previewBase.trim());
        }
        return stripTrailingSlash(endpoint) + "/" + bucket + "/marketplace";
    }

    private static String stripTrailingSlash(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String stripSlashes(String s) {
        String v = s == null ? "" : s.trim();
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private void pruneCache() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().at > CACHE_TTL_MS);
        if (cache.size() <= CACHE_MAX) {
            return;
        }
        List<String> keys = new ArrayList<>(cache.keySet());
        keys.sort(Comparator.comparingLong(k -> cache.get(k).at));
        for (String k : keys) {
            if (cache.size() <= CACHE_MAX) {
                break;
            }
            cache.remove(k);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private record CacheEntry(MarketSkillPreviewDto dto, long at) {
    }

    private record ManifestHit(String url, String version) {
    }
}
