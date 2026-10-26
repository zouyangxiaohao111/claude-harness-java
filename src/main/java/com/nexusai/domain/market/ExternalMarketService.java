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
                plugins.add(new ExternalPluginDto(
                    name,
                    text(n.get("displayName")),
                    text(n.get("description")),
                    text(n.get("version")),
                    text(n.get("category")),
                    strList(n.get("tags")),
                    text(n.get("createdAt"))));
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
                try {
                    Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                } catch (IOException ignored) {
                    // best-effort
                }
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
