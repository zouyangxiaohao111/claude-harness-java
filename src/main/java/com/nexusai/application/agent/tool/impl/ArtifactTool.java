package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.session.SessionArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Artifact（本地版）· 语义对齐 claude.exe 2.1.328 的 {@code ArtifactTool}，发布目标改为本地。
 *
 * <h2>CC 原语义</h2>
 * <p>CC 的 Artifact 把 HTML/文件发布到 Anthropic 托管 frame 服务（{@code /api/frame/upload}、
 * {@code /api/frame/deploy/prepare}），返回 {@code slug}/{@code share_url}；产物类型包括
 * slide deck / presentation / visual design / data dashboard。
 *
 * <h2>本仓映射（2026-11-19 用户裁定）</h2>
 * <ul>
 *   <li>不发布到托管：写入会话交付物目录
 *       {@link SessionArtifactService#deliverablesDir(String)}（{@code artifacts/{session}/deliverables}）。</li>
 *   <li>返回 status / local_path / artifact_type；created vs updated 由目标文件是否已存在决定。</li>
 *   <li>产物类型白名单：slide_deck / presentation / visual_design / data_dashboard / page。</li>
 *   <li><b>2026-11-19 用户裁定：已接线 Spring</b>（{@code @Component}，自动进工具池）；
 *       协调者模式仍不直接发放（协调者把发布委派给 worker，worker 白名单已含 Artifact）。</li>
 * </ul>
 *
 * <p>入参避免数组：单文件发布 = {@code path}（相对 deliverables）+ {@code content} +
 * {@code type} + 可选 {@code slug}。
 */
@Component
public class ArtifactTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ArtifactTool.class);

    public static final String NAME = ToolNameConstants.ARTIFACT_TOOL_NAME;

    /** 产物类型白名单（CC 文案列举的四类 + 通用 page）。 */
    public static final Set<String> ARTIFACT_TYPES = Set.of(
        "slide_deck", "presentation", "visual_design", "data_dashboard", "page");

    private static final String DEFAULT_PATH = "index.html";

    /** 默认根 = 会话 deliverables 目录；测试可注入临时根。 */
    private final Path rootOverride;

    public ArtifactTool() {
        this(null);
    }

    /** 测试注入构造器（不依赖 Spring / 数据根）。 */
    public ArtifactTool(Path rootOverride) {
        this.rootOverride = rootOverride;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Publish an HTML artifact page for the user to read or share. Use it for reports, "
            + "slide decks, presentations, visual designs, and data dashboards. The artifact is stored "
            + "in this session's deliverables directory and appears in the artifacts panel; "
            + "do not write a .md file as the deliverable when an HTML page fits.";
    }

    @Override
    public String prompt() {
        return description();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description",
            "Path of the artifact file relative to the deliverables directory, e.g. 'report.html' "
                + "or 'deck/index.html'. Defaults to '<slug>/index.html', or 'index.html' without a slug.");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "The artifact file content (normally a complete HTML document).");

        ObjectNode type = props.putObject("type");
        type.put("type", "string");
        ArrayNode typeEnum = JsonNodeFactory.instance.arrayNode();
        typeEnum.add("slide_deck").add("presentation").add("visual_design").add("data_dashboard").add("page");
        type.set("enum", typeEnum);
        type.put("description", "Artifact type: slide_deck, presentation, visual_design, data_dashboard, page.");

        ObjectNode slug = props.putObject("slug");
        slug.put("type", "string");
        slug.put("description", "Optional kebab-case slug for the artifact; used as the default subdirectory.");

        ArrayNode required = schema.putArray("required");
        required.add("content");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        if (input == null || !input.hasNonNull("content")) {
            return ToolResult.error(call.id(), "Missing required parameter: content");
        }
        String content = input.get("content").asText();
        if (content.isBlank()) {
            return ToolResult.error(call.id(), "content must not be empty");
        }
        String type = input.path("type").asText("page").trim();
        if (!ARTIFACT_TYPES.contains(type)) {
            return ToolResult.error(call.id(),
                "Unknown artifact type '" + type + "'; expected one of " + ARTIFACT_TYPES);
        }
        String slug = input.path("slug").asText("").trim();
        String rawPath = input.path("path").asText("").trim();
        if (rawPath.isEmpty()) {
            rawPath = (slug.isEmpty() ? "" : slug + "/") + DEFAULT_PATH;
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        Path base;
        if (rootOverride != null) {
            base = rootOverride;
        } else if (sessionId != null && !sessionId.isBlank()) {
            base = SessionArtifactService.deliverablesDir(sessionId);
        } else {
            return ToolResult.error(call.id(), "Artifact requires a session id (or an injected root)");
        }
        base = base.toAbsolutePath().normalize();
        Path target = base.resolve(rawPath).normalize();
        if (!target.startsWith(base)) {
            return ToolResult.error(call.id(), "Path escapes the deliverables directory: " + rawPath);
        }

        boolean existed = Files.exists(target);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[Artifact] 本地发布失败 target={}: {}", target, e.getMessage());
            return ToolResult.error(call.id(), "Failed to write artifact: " + e.getMessage());
        }

        StringBuilder out = new StringBuilder()
            .append("status: ").append(existed ? "updated" : "created").append('\n')
            .append("local_path: ").append(target).append('\n')
            .append("artifact_type: ").append(type);
        if (!slug.isEmpty()) {
            out.append('\n').append("slug: ").append(slug);
        }
        if (log.isInfoEnabled()) {
            log.info("[Artifact] 本地发布 {} type={} session={} path={}",
                existed ? "updated" : "created", type, sessionId, target);
        }
        return ToolResult.success(call.id(), out.toString());
    }
}
