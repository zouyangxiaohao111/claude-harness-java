package com.nexusai.domain.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.plugin.MarketplaceHttpDownloader;
import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.market.dto.ExternalMarketDto;
import com.nexusai.model.market.dto.ExternalPluginDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部插件市场（自建 MinIO / 静态 HTTP）解析服务 · {@code GET /api/market/external}。
 *
 * <p><b>WHY 单独成类</b>（用户 2026-10-26 裁定「删除 workbuddy 市场源」）：本能力原本挂在
 * {@code WorkbuddyMarketService} 里，但它是建科数字插件市场（MinIO）的数据源，与腾讯 workbuddy
 * 无关。腾讯源删除后保留本类，避免误删建科市场的解析链路。
 *
 * <p>下载 url（zip 或 marketplace.json）→ 解析 {@code marketplace.json}（含扩展字段
 * displayName/description/createdAt）→ 映射为扁平插件列表（category 前端分流到专家/技能/连接器）。
 * 下载后清理临时目录；失败抛 {@link BadGatewayException}（502 中文，fail-loud）。
 */
@Service
public class ExternalMarketService {

    private static final Logger log = LoggerFactory.getLogger(ExternalMarketService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 外部市场下载器（zip 或 marketplace.json）。 */
    private final MarketplaceHttpDownloader httpDownloader = new MarketplaceHttpDownloader();

    /**
     * 解析外部插件市场。
     *
     * @param url marketplace URL（zip 或 marketplace.json）
     * @return 市场元信息 + 插件列表
     * @throws BadGatewayException 下载/解析失败（fail-loud，502 中文）
     */
    public ExternalMarketDto listExternalMarket(String url) {
        if (url == null || url.isBlank()) {
            throw new ValidationException("外部市场 url 不能为空");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("nexusai-ext-market-");
            String mp = httpDownloader.downloadMarketplace(url, null, tmp.toString());
            JsonNode root = JSON.readTree(Files.readString(Path.of(mp), StandardCharsets.UTF_8));
            List<ExternalPluginDto> plugins = new ArrayList<>();
            for (JsonNode n : nodeList(root, "plugins")) {
                String name = text(n.get("name"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                // [2026-11-01 v4] 开发者：marketplace.json 可写 author（字符串或 {name,email,verified,avatarUrl} 对象）。
                //   缺省 → 后端填 DEFAULT_AUTHOR_NAME（用户拍板），保证前端「按 author.name 分组」永远有键。
                JsonNode authorNode = n.get("author");
                String authorName = authorName(authorNode);
                String authorEmail = authorEmail(authorNode);
                Boolean authorVerified = authorVerified(authorNode);
                String authorAvatarUrl = authorAvatarUrl(authorNode);
                plugins.add(new ExternalPluginDto(
                    name,
                    text(n.get("displayName")),
                    text(n.get("description")),
                    text(n.get("version")),
                    text(n.get("category")),
                    strList(n.get("tags")),
                    text(n.get("createdAt")),
                    // [2026-11-01 v4] 统计：原始整数；缺失保持 null（前端不显示该统计，不显示 0）
                    longOrNull(n.get("useCount")),
                    longOrNull(n.get("downloadCount")),
                    authorName,
                    authorVerified,
                    authorAvatarUrl,
                    authorEmail));
            }

            // [2026-11-01 v4 · 用户拍板方案 B] 统计数字来自市场侧的 stats.json（安装/召唤时 +1）。
            //   它和 marketplace.json 是两个文件：marketplace.json 描述「有什么」，stats.json 记录「用了多少」。
            //   ⚠️ best-effort：拉不到 / 解析失败 → 保持字段 null（界面不显示统计），绝不阻断市场列表。
            JsonNode statsRoot = fetchStats(root, url, tmp);
            if (statsRoot != null) {
                plugins = mergeStats(plugins, statsRoot);
            }
            if (log.isInfoEnabled()) {
                log.info("[ExternalMarket] 外部市场解析 name={} plugins={} 个（url={}）",
                    text(root.get("name")), plugins.size(), truncate(url, 120));
            }
            return new ExternalMarketDto(
                text(root.get("name")),
                ownerText(root.get("owner")),
                text(root.get("description")),
                plugins);
        } catch (IOException e) {
            log.error("[ExternalMarket] 外部市场拉取失败 url={}: {}", truncate(url, 120), e.getMessage());
            throw new BadGatewayException("外部市场拉取失败（" + e.getMessage() + "）", e);
        } finally {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // JsonNode 小工具
    // ────────────────────────────────────────────────────────────────────────

    /** 提取列表数组：兼容 {data:{field:[...]}} 与 {field:[...]} 两层；缺失/非数组 → 空列表 + warn。 */
    private static List<JsonNode> nodeList(JsonNode root, String field) {
        JsonNode data = root == null ? null : root.path("data");
        JsonNode arr = null;
        if (data != null && data.isObject() && data.has(field)) {
            arr = data.get(field);
        } else if (root != null && root.has(field)) {
            arr = root.get(field);
        }
        if (arr == null || !arr.isArray()) {
            log.warn("[ExternalMarket] 响应结构异常（未找到 data.{} 数组），按空列表处理 root={}",
                field, root == null ? "null" : truncate(root.toString(), 200));
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (n != null && !n.isNull() && n.isObject()) {
                out.add(n);
            }
        }
        return out;
    }

    /** owner 兼容字符串与对象（官方/外部市场常写 {"name": "..."}）；取展示名。 */
    private static String ownerText(JsonNode owner) {
        if (owner != null && owner.isObject()) {
            return text(owner.get("name"));
        }
        return text(owner);
    }

    // ────────────────────────────────────────────────────────────────────────
    // [2026-11-01 v4] 统计与开发者字段解析
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 开发者名：兼容 {@code "author": "Acme"} 与 {@code "author": {"name": "Acme"}} 两种写法。
     *
     * <p><b>缺省兜底到 {@link ExternalPluginDto#DEFAULT_AUTHOR_NAME}</b>（用户拍板）——
     * ⛔ 不要在缺省时返回 null 让前端兜底：作者名参与「按作者分组」，
     * 空值与默认值会在聚合时裂成两组。
     */
    private static String authorName(JsonNode author) {
        String name = null;
        if (author != null && author.isObject()) {
            name = text(author.get("name"));
        } else {
            name = text(author);
        }
        return name != null ? name : ExternalPluginDto.DEFAULT_AUTHOR_NAME;
    }

    /** 开发者邮箱（可选 · 仅对象形态有）。 */
    private static String authorEmail(JsonNode author) {
        return author != null && author.isObject() ? text(author.get("email")) : null;
    }

    /** 是否认证发布者（可选 · 缺省 false，⛔ 不返回 null —— 前端据 true 显示认证图标）。 */
    private static Boolean authorVerified(JsonNode author) {
        if (author != null && author.isObject()) {
            JsonNode v = author.get("verified");
            if (v != null && v.isBoolean()) {
                return v.asBoolean();
            }
        }
        return Boolean.FALSE;
    }

    /** 开发者头像 URL（可选 · 缺省 null → 前端用首字母色块）。 */
    private static String authorAvatarUrl(JsonNode author) {
        return author != null && author.isObject() ? text(author.get("avatarUrl")) : null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // [2026-11-01 v4 · 方案 B] 市场统计（stats.json）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 拉取市场侧 {@code stats.json}（best-effort · 失败返回 null）。
     *
     * <p>文件位置约定：与 marketplace.json <b>同目录</b>（同一 MinIO 前缀）。
     * 结构：{@code {"docx": {"use": 6156400, "download": 232200}, ...}}，
     * 也兼容包一层 {@code {"stats": {...}}}。
     *
     * <p><b>为什么 best-effort</b>：统计是「锦上添花」，缺了只是界面不显示数字；
     * 若让它阻断市场列表，等于「统计服务没上线 ⇒ 技能市场整个打不开」，代价远大于收益。
     */
    private JsonNode fetchStats(JsonNode marketRoot, String url, Path tmp) {
        // 优先：包内已带 stats.json（zip 形态时会一起解压到 tmp）
        try {
            for (Path p : Files.walk(tmp, 3).toList()) {
                if (p.getFileName() != null && "stats.json".equals(p.getFileName().toString())) {
                    JsonNode node = JSON.readTree(Files.readString(p, StandardCharsets.UTF_8));
                    if (log.isInfoEnabled()) {
                        log.info("[ExternalMarket] 使用包内 stats.json: {}", p);
                    }
                    return unwrapStats(node);
                }
            }
        } catch (IOException e) {
            log.warn("[ExternalMarket] 读取包内 stats.json 失败（按无统计处理）: {}", e.getMessage());
        }

        // 其次：同目录 stats.json（marketplace.json 换成 stats.json 再拉一次）
        String statsUrl = siblingStatsUrl(url);
        if (statsUrl == null) {
            return null;
        }
        Path statsTmp = null;
        try {
            statsTmp = Files.createTempDirectory("nexusai-market-stats-");
            String downloaded = httpDownloader.downloadMarketplace(statsUrl, null, statsTmp.toString());
            JsonNode node = JSON.readTree(Files.readString(Path.of(downloaded), StandardCharsets.UTF_8));
            if (log.isInfoEnabled()) {
                log.info("[ExternalMarket] 已拉取市场统计 stats.json（{} 条目）", unwrapStats(node).size());
            }
            return unwrapStats(node);
        } catch (Exception e) {
            // 统计文件缺失是常态（市场侧还没开始上报）⇒ info 而不是 error
            if (log.isInfoEnabled()) {
                log.info("[ExternalMarket] 市场未提供 stats.json（按无统计处理）: {} · {}", statsUrl, e.getMessage());
            }
            return null;
        } finally {
            if (statsTmp != null) {
                deleteQuietly(statsTmp);
            }
        }
    }

    /** marketplace.json 的 URL → 同目录 stats.json 的 URL（无法推导返回 null）。 */
    private static String siblingStatsUrl(String marketUrl) {
        if (marketUrl == null) {
            return null;
        }
        int idx = marketUrl.lastIndexOf('/');
        if (idx < 0 || idx == marketUrl.length() - 1) {
            return null;
        }
        return marketUrl.substring(0, idx + 1) + "stats.json";
    }

    /** 兼容 {@code {"stats": {...}}} 与直接 {@code {...}} 两种顶层结构。 */
    private static JsonNode unwrapStats(JsonNode node) {
        if (node != null && node.isObject() && node.has("stats") && node.get("stats").isObject()) {
            return node.get("stats");
        }
        return node != null && node.isObject() ? node : JSON.createObjectNode();
    }

    /**
     * 把统计合并进插件列表（按 name 匹配；无统计的条目保持原样，字段仍为 null）。
     *
     * <p>每个条目的统计支持两种写法：{@code {"use":N,"download":N}} 或
     * {@code {"useCount":N,"downloadCount":N}}（兼容两种习惯）。
     */
    private static List<ExternalPluginDto> mergeStats(List<ExternalPluginDto> plugins, JsonNode stats) {
        if (stats == null || !stats.isObject() || stats.isEmpty()) {
            return plugins;
        }
        List<ExternalPluginDto> out = new ArrayList<>(plugins.size());
        for (ExternalPluginDto p : plugins) {
            JsonNode s = stats.get(p.name());
            if (s == null || !s.isObject()) {
                out.add(p);
                continue;
            }
            Long use = p.useCount() != null ? p.useCount() : longOrNull(firstPresent(s, "use", "useCount"));
            Long download = p.downloadCount() != null
                ? p.downloadCount()
                : longOrNull(firstPresent(s, "download", "downloadCount"));
            out.add(new ExternalPluginDto(
                p.name(), p.displayName(), p.description(), p.version(), p.category(),
                p.tags(), p.createdAt(), use, download,
                p.authorName(), p.authorVerified(), p.authorAvatarUrl(), p.authorEmail()));
        }
        if (log.isDebugEnabled()) {
            log.debug("[ExternalMarket] 统计合并完成：{} 个插件（其中 {} 个有使用量）",
                out.size(), out.stream().filter(x -> x.useCount() != null).count());
        }
        return out;
    }

    private static JsonNode firstPresent(JsonNode obj, String... keys) {
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static void deleteQuietly(Path dir) {
        try {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * 统计数值：只接受非负整数；缺失/非法 → {@code null}。
     *
     * <p>⛔ <b>不把缺失当 0</b>：「0 次使用」与「市场没有这项统计」是两回事，
     * 前者应显示 0，后者应整项不显示。
     */
    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            long v = node.asLong();
            return v < 0 ? null : v;
        }
        // 兼容字符串数字（部分静态站生成器会把数字写成字符串）
        String s = text(node);
        if (s == null) {
            return null;
        }
        try {
            long v = Long.parseLong(s);
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            log.warn("[ExternalMarket] 统计值非数字，按无数据处理: [{}]", s);
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isValueNode()) {
            return null;
        }
        String s = node.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<String> strList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (n != null && !n.isNull() && n.isValueNode()) {
                String s = n.asText();
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
