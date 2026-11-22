package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.ChatModelTypes;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ListModels（本仓扩展；CC 无对应工具）· 列出可传给 {@code SessionCreate.model} 的会话模型。
 *
 * <p><b>WHY 必须有</b>：{@code SessionCreate.model} 要求精确的 {@code provider/modelName}，而模型
 * 名称（{@code deepseek/deepseek-flash} / {@code OpenAI/gpt-6.1-sol}）不能靠猜；本仓也没有别的
 * 工具向模型暴露模型清单（{@code Config} 的 {@code model} setting 是「当前会话主模型」且值脱敏不回显）。
 *
 * <p><b>可用口径</b>（与前端同源，见 {@link ChatModelTypes}）：
 * {@code provider.enabled && model.enabled && type ∈ {chat, text, vision, multimodal}}（空 type 兼容）。
 * embedding / rerank / image / image_generation / audio / moderation 归入「不可用」组；
 * 默认只给数量，{@code include_unavailable=true} 才逐条列出原因。
 *
 * <p><b>档位标注</b>：读 {@code settings} 的 11 个档位字段（{@code ModelTierRole}），
 * 每个值都走 {@link ModelNameResolver#resolve} 反查模型行，再按 {@code models.id} 归组
 * —— <b>⛔ 不做字符串比较</b>（settings 存全名/裸名/带 {@code [1m]} 后缀的都有）。
 * 解析失败的档位写进 notes，不静默吞掉。
 *
 * <p><b>输出必须是 String</b>（同 {@code ListImChatsTool} 注释）：落库链路只对 String data 生成
 * content，Map 会落成空 content。
 *
 * <p><b>能力门</b>：与 {@code SessionCreate} 同门（跨会话投递开关）——它主要为建 peer 选模型服务。
 */
@Component
public class ListModelsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListModelsTool.class);

    public static final String NAME = ToolNameConstants.LIST_MODELS_TOOL_NAME;

    /** 输出预算：行数 / 字符数。模型量通常 &lt; 50，超限只截断并说明。 */
    static final int MAX_LINES = 200;
    static final int MAX_CHARS = 20_000;

    /** settings 单例行主键（与 ChatService.SETTINGS_SINGLETON_ID 同值）。 */
    static final int SETTINGS_SINGLETON_ID = 1;

    /** 档位展示顺序（与 {@code ModelTierRole} 的 wire 名一一对应）。 */
    static final List<String> TIER_ORDER = List.of(
        "main", "fast", "strong", "medium", "weak", "subagent",
        "fallback", "multimodal", "tts", "asr", "classifier");

    @Autowired(required = false)
    private ModelMapper modelMapper;

    @Autowired(required = false)
    private ProviderMapper providerMapper;

    @Autowired(required = false)
    private SettingsMapper settingsMapper;

    /** 档位原始值 → 模型行的解析（生产走 {@link ModelNameResolver}；测试可替换）。 */
    @FunctionalInterface
    interface TierModelResolver {
        ModelRecord resolve(String raw);
    }

    private TierModelResolver tierModelResolver;

    public void setModelMapper(ModelMapper mapper) {
        this.modelMapper = mapper;
    }

    public void setProviderMapper(ProviderMapper mapper) {
        this.providerMapper = mapper;
    }

    public void setSettingsMapper(SettingsMapper mapper) {
        this.settingsMapper = mapper;
    }

    /** 测试注入：绕过真实 DB 解析，直接给「档位值 → 模型行」。 */
    void setTierModelResolverForTest(TierModelResolver resolver) {
        this.tierModelResolver = resolver;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "List models that can run a session, as exact provider/modelName strings, "
            + "with type, context window and bound settings tiers.";
    }

    /**
     * 工具提示词（中文对照 · 仅供人读，不发给模型）。
     *
     * <p>{@link #prompt()} 的返回值只放英文（模型可见）；本注释逐段保留中文对照。
     *
     * <pre>
     * 列出本机上能跑会话的模型，每条给出可精确复制的 provider/modelName。
     *
     * 当需要用不同模型建同级会话时，先调用它：把打印出来的字符串原样传给 SessionCreate 的 model。
     * 主清单只列具备对话能力的模型；embedding、rerank、image、audio、moderation 标注为不可用。
     *
     * 每行展示模型类型、展示 tag、上下文窗口，以及它绑定的设置档位
     * （main、fast、strong、medium、weak、subagent、fallback、multimodal、tts、asr、classifier）。
     * 标了 [default] 的是全局配置的主模型。
     *
     * 传 include_unavailable=true 可一并列出被排除的模型及原因
     * （类型不符 / 模型停用 / provider 停用）。
     * 不要编造模型名；原样复制一行。
     * </pre>
     */
    @Override
    public String prompt() {
        return "List the models on this machine that can run a session, as exact provider/modelName strings.\n"
            + "\n"
            + "Call it before SessionCreate when you need a different model: pass the printed string\n"
            + "verbatim as `model`. Only conversation-capable models appear in the main list;\n"
            + "embedding, rerank, image, audio and moderation models are reported as not usable.\n"
            + "\n"
            + "Each row shows the model type, its display tag, the context window, and the settings tiers\n"
            + "it is bound to (main, fast, strong, medium, weak, subagent, fallback, multimodal, tts,\n"
            + "asr, classifier). A model marked [default] is the globally configured main model.\n"
            + "\n"
            + "Pass include_unavailable=true to also list the excluded models and why they cannot run a\n"
            + "session (wrong type, model disabled, or provider disabled).\n"
            + "Never invent a model name; copy a row exactly.";
    }

    @Override
    public String searchHint() {
        return "list models available for a session";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("include_unavailable")
            .put("type", "boolean")
            .put("description", "Default false. When true, also list non-conversation models "
                + "(embedding, rerank, image, audio, moderation) and models whose provider or "
                + "model row is disabled, with the reason.");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 与 SessionCreate 同门：跨会话投递开着才有意义。 */
    @Override
    public boolean isEnabled() {
        return TaskSystemConfig.isMessagingBeyondOwnAgentsEnabled();
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        if (modelMapper == null || providerMapper == null) {
            return ToolResult.error(call.id(),
                "ListModels is not available: model registry is not wired.");
        }
        boolean includeUnavailable = call != null && call.input() != null
            && call.input().path("include_unavailable").asBoolean(false);

        List<ModelRecord> models;
        Map<String, ProviderRecord> providerById = new HashMap<>();
        try {
            models = modelMapper.selectAll();
            List<ProviderRecord> providers = providerMapper.selectAll();
            if (providers != null) {
                for (ProviderRecord p : providers) {
                    if (p != null && p.getId() != null) {
                        providerById.put(p.getId(), p);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[ListModels] 读取模型/提供商失败: {}", e.toString());
            return ToolResult.error(call.id(), "ListModels failed: could not read models: " + e);
        }

        List<String> notes = new ArrayList<>();
        Map<String, List<String>> tiersByModelId = resolveTierBindings(notes);
        String defaultModelId = mainTierModelId(tiersByModelId);

        List<Row> rows = new ArrayList<>();
        if (models != null) {
            for (ModelRecord m : models) {
                if (m == null) {
                    continue;
                }
                ProviderRecord p = m.getProviderId() == null ? null : providerById.get(m.getProviderId());
                boolean usable = p != null
                    && Boolean.TRUE.equals(p.getEnabled())
                    && Boolean.TRUE.equals(m.getEnabled())
                    && ChatModelTypes.isConversationModel(m);
                rows.add(new Row(fullName(p, m), m, p, usable));
            }
        }
        rows.sort(Comparator.comparing(r -> r.fullName().toLowerCase(Locale.ROOT)));

        List<String> available = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Row row : rows) {
            if (row.usable()) {
                available.add(formatAvailable(row.model(), row.fullName(), tiersByModelId, defaultModelId));
            } else {
                unavailable.add(formatUnavailable(row.model(), row.fullName(), row.provider()));
            }
        }

        String text = render(available, unavailable, includeUnavailable, notes);
        if (log.isDebugEnabled()) {
            log.debug("[ListModels] models={} available={} unavailable={} includeUnavailable={} "
                    + "tiers={} outputChars={}",
                rows.size(), available.size(), unavailable.size(), includeUnavailable,
                tiersByModelId.size(), text.length());
        }
        return ToolResult.success(call.id(), text);
    }

    // ─────────────────────────── 渲染 ───────────────────────────

    private String render(List<String> available, List<String> unavailable,
                          boolean includeUnavailable, List<String> notes) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Available session models (pass the exact provider/modelName as SessionCreate `model`):\n");

        int lines = 0;
        boolean truncated = false;
        for (String line : available) {
            if (lines >= MAX_LINES || sb.length() + line.length() + 1 > MAX_CHARS) {
                truncated = true;
                break;
            }
            sb.append(line).append('\n');
            lines++;
        }
        if (available.isEmpty()) {
            sb.append("  (none)\n");
        }
        if (truncated) {
            sb.append("  (").append(available.size() - lines).append(" more models not shown)\n");
        }

        sb.append('\n')
            .append("Other models not usable for sessions: ").append(unavailable.size()).append('\n');
        if (unavailable.isEmpty()) {
            // 没有就什么都不写，避免噪音。
        } else if (!includeUnavailable) {
            sb.append("  (pass include_unavailable=true to list them)\n");
        } else {
            int shown = 0;
            for (String line : unavailable) {
                if (shown >= MAX_LINES || sb.length() + line.length() + 1 > MAX_CHARS) {
                    break;
                }
                sb.append(line).append('\n');
                shown++;
            }
            if (shown < unavailable.size()) {
                sb.append("  (").append(unavailable.size() - shown).append(" more models not shown)\n");
            }
        }

        if (!notes.isEmpty()) {
            sb.append('\n').append("Notes:\n");
            for (String note : notes) {
                sb.append("  - ").append(note).append('\n');
            }
        }
        return sb.toString();
    }

    private static String formatAvailable(ModelRecord m, String fullName,
                                          Map<String, List<String>> tiersByModelId,
                                          String defaultModelId) {
        StringBuilder sb = new StringBuilder("  - ").append(fullName);
        if (m.getId() != null && m.getId().equals(defaultModelId)) {
            sb.append(" [default]");
        }
        sb.append(" (").append(typeOf(m));
        if (m.getTag() != null && !m.getTag().isBlank()) {
            sb.append(", tag ").append(m.getTag());
        }
        Integer ctx = m.getMaxContextTokens();
        if (ctx != null && ctx > 0) {
            sb.append(", ctx ").append(ctx);
        }
        List<String> tiers = m.getId() == null ? null : tiersByModelId.get(m.getId());
        if (tiers != null && !tiers.isEmpty()) {
            sb.append("; tiers: ").append(String.join(", ", tiers));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String formatUnavailable(ModelRecord m, String fullName, ProviderRecord p) {
        String reason;
        if (p == null) {
            reason = "provider missing";
        } else if (!Boolean.TRUE.equals(p.getEnabled())) {
            reason = "provider disabled";
        } else if (!Boolean.TRUE.equals(m.getEnabled())) {
            reason = "model disabled";
        } else {
            reason = "not conversation-capable";
        }
        return "  - " + fullName + " (" + typeOf(m) + ") — " + reason;
    }

    private static String typeOf(ModelRecord m) {
        String type = m.getType();
        return (type == null || type.isBlank()) ? "unknown" : type.trim();
    }

    private static String fullName(ProviderRecord p, ModelRecord m) {
        String name = m.getName() == null ? "" : m.getName();
        if (name.isBlank()) {
            return "";
        }
        // 模型名本身含 '/'（Bedrock ARN / Azure 部署 ID）→ 不拼前缀（同 ModelNameResolver#toFullName）。
        if (name.indexOf('/') >= 0) {
            return name;
        }
        String providerName = p == null ? null : p.getName();
        if (providerName == null || providerName.isBlank() || providerName.indexOf('/') >= 0) {
            return name;
        }
        return providerName + "/" + name;
    }

    // ─────────────────────────── 档位 ───────────────────────────

    /**
     * settings 11 个档位 → 模型行 ID → 档位名列表。
     *
     * <p>每个值都走解析器（生产为 {@link ModelNameResolver#resolve}），不做字符串比较：
     * settings 可能存全名、裸名或带 {@code [1m]} 后缀的名字。
     */
    private Map<String, List<String>> resolveTierBindings(List<String> notes) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        SettingsRecord settings = readSettings(notes);
        if (settings == null) {
            return out;
        }
        TierModelResolver resolver = tierModelResolver != null
            ? tierModelResolver
            : raw -> ModelNameResolver.resolve(modelMapper, providerMapper, raw);

        Map<String, String> rawByTier = new LinkedHashMap<>();
        rawByTier.put("main", settings.getMainModelName());
        rawByTier.put("fast", settings.getFastModelName());
        rawByTier.put("strong", settings.getStrongModelName());
        rawByTier.put("medium", settings.getMediumModelName());
        rawByTier.put("weak", settings.getWeakModelName());
        rawByTier.put("subagent", settings.getSubagentModelName());
        rawByTier.put("fallback", settings.getFallbackModelName());
        rawByTier.put("multimodal", settings.getMultimodalModelName());
        rawByTier.put("tts", settings.getTtsModelName());
        rawByTier.put("asr", settings.getAsrModelName());
        rawByTier.put("classifier", settings.getClassifierModel());

        for (String tier : TIER_ORDER) {
            String raw = rawByTier.get(tier);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            ModelRecord hit;
            try {
                hit = resolver.resolve(raw.trim());
            } catch (RuntimeException e) {
                notes.add("tier \"" + tier + "\" could not be resolved: " + raw.trim()
                    + " (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            if (hit == null || hit.getId() == null) {
                notes.add("tier \"" + tier + "\" could not be resolved: " + raw.trim());
                continue;
            }
            out.computeIfAbsent(hit.getId(), k -> new ArrayList<>()).add(tier);
        }
        if (log.isDebugEnabled()) {
            log.debug("[ListModels] 档位绑定: 已解析 {} 个模型, notes={}", out.size(), notes);
        }
        return out;
    }

    private SettingsRecord readSettings(List<String> notes) {
        if (settingsMapper == null) {
            notes.add("settings are not readable; tier bindings are omitted");
            return null;
        }
        try {
            SettingsRecord s = settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
            if (s == null) {
                notes.add("settings row is missing; tier bindings are omitted");
            }
            return s;
        } catch (RuntimeException e) {
            notes.add("settings could not be read: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String mainTierModelId(Map<String, List<String>> tiersByModelId) {
        for (Map.Entry<String, List<String>> e : tiersByModelId.entrySet()) {
            if (e.getValue() != null && e.getValue().contains("main")) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 一行模型的渲染素材（先把名称算好，排序/分组都用它）。 */
    private record Row(String fullName, ModelRecord model, ProviderRecord provider, boolean usable) {
    }
}
