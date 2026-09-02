package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin Schemas · 对齐 CC utils/plugins/schemas.ts (1681 行).
 *
 * <p>FIX-PLUGIN-SCHEMA: 简化版 plugin/marketplace JSON schema 校验.
 *
 * <p>L1 行为: 给定 plugin manifest JSON 字符串, 验证必填字段 (name/version/description/main).
 */
@Component
public class PluginSchemas {

    public enum Field { NAME, VERSION, DESCRIPTION, MAIN, MARKETPLACE, COMMANDS, HOOKS }

    public record ValidationResult(boolean valid, String error, Field missingField) {}

    private static final Map<Field, Boolean> REQUIRED = Map.of(
        Field.NAME, true,
        Field.VERSION, true,
        Field.DESCRIPTION, true,
        Field.MAIN, true,
        Field.MARKETPLACE, false,
        Field.COMMANDS, false,
        Field.HOOKS, false
    );

    private final Map<String, ValidationResult> cache = new ConcurrentHashMap<>();

    public ValidationResult validate(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            return new ValidationResult(false, "manifest is blank", Field.NAME);
        }
        for (Field f : REQUIRED.keySet()) {
            if (REQUIRED.get(f) && !manifestJson.contains("\"" + fieldName(f) + "\"")) {
                ValidationResult result = new ValidationResult(false, "missing field: " + f, f);
                cache.put(manifestJson.hashCode() + ":" + f, result);
                return result;
            }
        }
        return new ValidationResult(true, "valid", null);
    }

    private static String fieldName(Field f) {
        return switch (f) {
            case NAME -> "name";
            case VERSION -> "version";
            case DESCRIPTION -> "description";
            case MAIN -> "main";
            case MARKETPLACE -> "marketplace";
            case COMMANDS -> "commands";
            case HOOKS -> "hooks";
        };
    }

    // ════════════════════════════════════════════════════════════════════
    // Marketplace 配置层 schema · 对齐 CC schemas.ts:1592-1629 / :906-1030
    // ════════════════════════════════════════════════════════════════════

    /**
     * Marketplace 来源（判别联合）· CC original: {@code MarketplaceSource}
     * （schemas.ts:1648-1650，schema 定义 :906-1030）。
     *
     * <p>CC {@code z.discriminatedUnion('source', [...])} → Java sealed interface + Jackson
     * {@code @JsonTypeInfo(property="source")} 多态：序列化/反序列化均以 JSON 的
     * {@code "source"} 字段作为判别键，字段名与 CC 逐项一致。
     *
     * <p>可选字段为 null 即 JSON 缺省（{@code @JsonInclude(NON_NULL)}），对齐 zod optional。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "source")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = MarketplaceSource.Url.class, name = "url"),
        @JsonSubTypes.Type(value = MarketplaceSource.Github.class, name = "github"),
        @JsonSubTypes.Type(value = MarketplaceSource.Git.class, name = "git"),
        @JsonSubTypes.Type(value = MarketplaceSource.Npm.class, name = "npm"),
        @JsonSubTypes.Type(value = MarketplaceSource.File.class, name = "file"),
        @JsonSubTypes.Type(value = MarketplaceSource.Directory.class, name = "directory"),
        @JsonSubTypes.Type(value = MarketplaceSource.HostPattern.class, name = "hostPattern"),
        @JsonSubTypes.Type(value = MarketplaceSource.PathPattern.class, name = "pathPattern"),
        @JsonSubTypes.Type(value = MarketplaceSource.Settings.class, name = "settings"),
    })
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public sealed interface MarketplaceSource
        permits MarketplaceSource.Url, MarketplaceSource.Github, MarketplaceSource.Git,
                MarketplaceSource.Npm, MarketplaceSource.File, MarketplaceSource.Directory,
                MarketplaceSource.HostPattern, MarketplaceSource.PathPattern,
                MarketplaceSource.Settings {

        /** CC :908-915 — {@code { source:'url', url, headers? }} 直接 URL 到 marketplace.json. */
        record Url(
            @JsonProperty("url") String url,
            @JsonProperty("headers") Map<String, String> headers) implements MarketplaceSource {}

        /** CC :916-940 — {@code { source:'github', repo, ref?, path?, sparsePaths? }} GitHub owner/repo. */
        record Github(
            @JsonProperty("repo") String repo,
            @JsonProperty("ref") String ref,
            @JsonProperty("path") String path,
            @JsonProperty("sparsePaths") List<String> sparsePaths) implements MarketplaceSource {}

        /** CC :941-972 — {@code { source:'git', url, ref?, path?, sparsePaths? }} 完整 git 仓库 URL. */
        record Git(
            @JsonProperty("url") String url,
            @JsonProperty("ref") String ref,
            @JsonProperty("path") String path,
            @JsonProperty("sparsePaths") List<String> sparsePaths) implements MarketplaceSource {}

        /** CC :973-978 — {@code { source:'npm', package }} 含 marketplace.json 的 npm 包. */
        record Npm(
            @JsonProperty("package") String npmPackage) implements MarketplaceSource {}

        /** CC :979-982 — {@code { source:'file', path }} 本地 marketplace.json 文件路径. */
        record File(
            @JsonProperty("path") String path) implements MarketplaceSource {}

        /** CC :983-988 — {@code { source:'directory', path }} 含 .claude-plugin/marketplace.json 的本地目录. */
        record Directory(
            @JsonProperty("path") String path) implements MarketplaceSource {}

        /** CC :989-999 — {@code { source:'hostPattern', hostPattern }} 匹配来源 host 的正则. */
        record HostPattern(
            @JsonProperty("hostPattern") String hostPattern) implements MarketplaceSource {}

        /** CC :1000-1010 — {@code { source:'pathPattern', pathPattern }} 匹配 file/directory path 的正则. */
        record PathPattern(
            @JsonProperty("pathPattern") String pathPattern) implements MarketplaceSource {}

        /**
         * CC :1012-1033 — {@code { source:'settings', name }} 内联声明在 settings.json 的 marketplace.
         *
         * <p>Java 侧仅建模 {@code name} 用于 refresh 的 settings 源跳过判定（refreshMarketplace:2385-2390）；
         * {@code plugins} 内联数组由 MPL3/MPL8（reconciler）消费，本层不建模。
         */
        record Settings(
            @JsonProperty("name") String name) implements MarketplaceSource {}
    }

    /**
     * known_marketplaces.json 单条目 · CC original: {@code KnownMarketplace}
     * （schemas.ts:1592-1610 KnownMarketplaceSchema）。
     *
     * <p>字段逐项对齐：{@code source}（必填）+ {@code installLocation}（必填，本地缓存路径）+
     * {@code lastUpdated}（必填，ISO 8601）+ {@code autoUpdate}（可选）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnownMarketplace(
        MarketplaceSource source,
        String installLocation,
        String lastUpdated,
        Boolean autoUpdate) {

        public KnownMarketplace {
            if (installLocation == null || installLocation.isBlank()) {
                throw new IllegalArgumentException("KnownMarketplace.installLocation 不能为空");
            }
            if (lastUpdated == null || lastUpdated.isBlank()) {
                throw new IllegalArgumentException("KnownMarketplace.lastUpdated 不能为空");
            }
            if (source == null) {
                throw new IllegalArgumentException("KnownMarketplace.source 不能为空");
            }
        }
    }

    /**
     * 校验整个 known_marketplaces.json（Map&lt;name, KnownMarketplace&gt;）·
     * CC original: {@code KnownMarketplacesFileSchema}（schemas.ts:1624-1629
     * {@code z.record(z.string(), KnownMarketplaceSchema())}）。
     *
     * @param config 解析后的文件内容
     * @return 校验结果；entry 级必填缺失/非法 → valid=false + 具体错误
     */
    public static ValidationResult validateKnownMarketplaceFile(Map<String, KnownMarketplace> config) {
        if (config == null) {
            return new ValidationResult(false, "known_marketplaces.json 内容为 null", null);
        }
        for (Map.Entry<String, KnownMarketplace> e : config.entrySet()) {
            String name = e.getKey();
            KnownMarketplace entry = e.getValue();
            if (entry == null) {
                return new ValidationResult(false, "marketplace[" + name + "] 为 null", Field.MARKETPLACE);
            }
            if (entry.source() == null) {
                return new ValidationResult(false, "marketplace[" + name + "].source 缺失", Field.MARKETPLACE);
            }
            if (entry.installLocation() == null || entry.installLocation().isBlank()) {
                return new ValidationResult(false, "marketplace[" + name + "].installLocation 缺失", Field.MARKETPLACE);
            }
            if (entry.lastUpdated() == null || entry.lastUpdated().isBlank()) {
                return new ValidationResult(false, "marketplace[" + name + "].lastUpdated 缺失", Field.MARKETPLACE);
            }
        }
        return new ValidationResult(true, "valid", null);
    }

    /**
     * CC {@code ALLOWED_OFFICIAL_MARKETPLACE_NAMES}（schemas.ts:19-28）· 官方/保留 marketplace 名。
     * 名单内市场无显式 autoUpdate 时默认启用自动更新（NO_AUTO_UPDATE 名单除外）。
     */
    public static final Set<String> ALLOWED_OFFICIAL_MARKETPLACE_NAMES = Set.of(
        "claude-code-marketplace", "claude-code-plugins", "claude-plugins-official",
        "anthropic-marketplace", "anthropic-plugins", "agent-skills",
        "life-sciences", "knowledge-work-plugins");

    /** CC {@code NO_AUTO_UPDATE_OFFICIAL_MARKETPLACES}（schemas.ts:35）· 官方名但默认不自动更新的市场。 */
    public static final Set<String> NO_AUTO_UPDATE_OFFICIAL_MARKETPLACES = Set.of("knowledge-work-plugins");

    /**
     * 检查 marketplace 是否启用自动更新 · CC {@code isMarketplaceAutoUpdate}（schemas.ts:48-58）。
     *
     * <p>显式 {@code autoUpdate} 字段优先；未设置（null）时官方名单内市场默认 true
     * （{@link #NO_AUTO_UPDATE_OFFICIAL_MARKETPLACES} 名单如 knowledge-work-plugins 除外），
     * 其余默认 false。官方市场经 OfficialMarketplace 自动安装后写 autoUpdate=null →
     * 依赖本默认语义进入 autoupdate（否则生产特性空转）。
     *
     * @param marketplaceName 市场名（CC 内部 lower-case 归一）
     * @param entry           known_marketplaces.json 条目（autoUpdate 可空）
     * @return 是否启用自动更新
     */
    public static boolean isMarketplaceAutoUpdate(String marketplaceName, KnownMarketplace entry) {
        Boolean explicit = entry == null ? null : entry.autoUpdate();
        if (explicit != null) {
            return explicit;
        }
        String normalized = marketplaceName == null ? "" : marketplaceName.toLowerCase(Locale.ROOT);
        return ALLOWED_OFFICIAL_MARKETPLACE_NAMES.contains(normalized)
            && !NO_AUTO_UPDATE_OFFICIAL_MARKETPLACES.contains(normalized);
    }

    /** CC {@code OFFICIAL_GITHUB_ORG}（schemas.ts:30-33）· 官方 marketplace 的 GitHub 组织。 */
    public static final String OFFICIAL_GITHUB_ORG = "anthropics";

    /**
     * 校验保留官方名 marketplace 的源是否来自 Anthropic 官方 GitHub 组织 · CC {@code validateOfficialNameSource}
     * （schemas.ts:119-157）。
     *
     * <p>保留名（{@link #ALLOWED_OFFICIAL_MARKETPLACE_NAMES}）的 marketplace 只能从 {@code anthropics/}
     * GitHub 组织安装：
     * <ul>
     *   <li>github 源：repo 前缀须为 {@code anthropics/}（schemas.ts:125-133）；</li>
     *   <li>git 源：URL 须含 {@code github.com/anthropics/}（HTTPS）或 {@code git@github.com:anthropics/}（SSH）
     *       （schemas.ts:135-147）；</li>
     *   <li>其余源类型（url/npm/file/directory 等）：保留名一律拒绝（schemas.ts:149-153）。</li>
     * </ul>
     *
     * @param name   marketplace 名（未归一，方法内部 lower-case）
     * @param source 声明的源
     * @return null=校验通过；否则为拒绝消息（中文指引，对齐 CC 英文语义）
     */
    public static String validateOfficialNameSource(String name, MarketplaceSource source) {
        String normalizedName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!ALLOWED_OFFICIAL_MARKETPLACE_NAMES.contains(normalizedName)) {
            return null; // 非保留名，无需源校验（schemas.ts:123-124）
        }
        if (source instanceof MarketplaceSource.Github github) {
            String repo = github.repo() == null ? "" : github.repo().toLowerCase(Locale.ROOT);
            if (!repo.startsWith(OFFICIAL_GITHUB_ORG + "/")) {
                return "The name '" + name + "' is reserved for official Anthropic marketplaces. "
                    + "Only repositories from 'github.com/" + OFFICIAL_GITHUB_ORG + "/' can use this name.";
            }
            return null; // 保留名 + 官方 GitHub 源 → 通过（schemas.ts:127-133）
        }
        if (source instanceof MarketplaceSource.Git git) {
            String url = git.url() == null ? "" : git.url().toLowerCase(Locale.ROOT);
            boolean isHttpsAnthropics = url.contains("github.com/" + OFFICIAL_GITHUB_ORG + "/");
            boolean isSshAnthropics = url.contains("git@github.com:" + OFFICIAL_GITHUB_ORG + "/");
            if (isHttpsAnthropics || isSshAnthropics) {
                return null; // 保留名 + 官方 git URL → 通过（schemas.ts:138-147）
            }
            return "The name '" + name + "' is reserved for official Anthropic marketplaces. "
                + "Only repositories from 'github.com/" + OFFICIAL_GITHUB_ORG + "/' can use this name.";
        }
        return "The name '" + name + "' is reserved for official Anthropic marketplaces and can only be "
            + "used with GitHub sources from the '" + OFFICIAL_GITHUB_ORG + "' organization.";
    }
}