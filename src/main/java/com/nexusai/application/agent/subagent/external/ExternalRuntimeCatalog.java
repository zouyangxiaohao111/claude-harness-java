package com.nexusai.application.agent.subagent.external;

import com.nexusai.application.agent.subagent.AgentDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 CLI 运行时目录 · 「自动感知」的单一数据源。
 *
 * <p><b>为什么是数据而不是硬编码工厂</b>（照 OpenDesign {@code RuntimeAgentDef} 的思路）：
 * 每接一个新 CLI 只加一条 {@link RuntimePreset}，探测/建 agent/前端展示全部自动跟上。
 * ⛔ 不要为某个 CLI 写分支类。
 *
 * <p><b>与 md 定义的关系</b>：这里的内置预设 = 「装了就能用」的默认模板；
 * 用户在 {@code agents/*.md} 里写的 {@code runtime:} 会覆盖同名模板（md 优先）。
 */
public final class ExternalRuntimeCatalog {

    /**
     * 推理强度如何落到 argv（照 OpenDesign 的 agent def 逐家核实）。
     *
     * <p>⛔ 不要统一成一种写法：codex 认 {@code -c model_reasoning_effort="x"}、
     * pi 认 {@code --thinking x}、opencode 是**按模型**的 variant（{@code --variant x}），
     * claude CLI 根本没有对应开关。
     */
    public enum ReasoningStyle {
        /** 该 CLI 不支持推理强度（claude）。 */
        NONE,
        /** codex：{@code -c model_reasoning_effort="<id>"} */
        CODEX_CONFIG,
        /** pi：{@code --thinking <id>} */
        PI_THINKING,
        /** opencode：{@code --variant <id>}（仅当模型声明了该 variant） */
        OPENCODE_VARIANT
    }

    /**
     * 一个可感知的运行时预设。
     *
     * @param runtime          运行时 id（与 md 里 {@code runtime:} 对齐）
     * @param displayName      前端显示名
     * @param bin              主可执行名
     * @param fallbackBins     备用名
     * @param binEnv           自定义可执行路径的环境变量（{@code CLAUDE_BIN} / {@code CODEX_BIN}）
     * @param args             argv 模板（含 {prompt}）
     * @param streamFormat     输出协议
     * @param configDirEnv     该 CLI 认的「配置目录」环境变量（null = 不支持覆盖）
     * @param baseUrlEnv       该 CLI 认的「API base URL」环境变量（null = 按 provider 走配置文件）
     * @param apiKeyEnvs       该 CLI 认的「API key」环境变量（**有序**，可多个；空 = 用自身登录态）
     * @param fallbackModels   内置模型列表（实时拉取失败时展示）
     * @param reasoningOptions 推理强度档位 id（空 = 该 CLI 不支持）
     * @param reasoningStyle   推理强度怎么拼进 argv
     * @param listModelsArgs   实时拉取模型的子命令（空 = 不支持，只能用内置列表）
     * @param notes            给用户看的一句话说明（前端 tooltip）
     */
    public record RuntimePreset(
            String runtime,
            String displayName,
            String bin,
            List<String> fallbackBins,
            String binEnv,
            List<String> args,
            String streamFormat,
            String configDirEnv,
            String baseUrlEnv,
            List<String> apiKeyEnvs,
            List<String> fallbackModels,
            List<String> reasoningOptions,
            ReasoningStyle reasoningStyle,
            List<String> listModelsArgs,
            /**
             * 一行短描述（展示用，如「Anthropic 官方 CLI」）。
             *
             * <p>与 {@link #notes} 的区别：notes 是给 tooltip 的完整说明，
             * tagline 是列表行里紧跟在名称后面的那半句 —— 太长会把名称挤掉。
             */
            String tagline,
            String notes
    ) {
        /** 是否支持从页面覆盖连接信息。 */
        public boolean configurable() {
            return configDirEnv != null || baseUrlEnv != null
                    || !apiKeyEnvs.isEmpty() || binEnv != null;
        }

        /** 是否支持推理强度。 */
        public boolean supportsReasoning() {
            return reasoningStyle != ReasoningStyle.NONE && !reasoningOptions.isEmpty();
        }
    }

    /**
     * 内置预设表。顺序 = 前端展示顺序（常用在前）。
     *
     * <p>每条命令都是**2026-11-04 真机实测过**的形态，不是照文档猜的：
     * 参数错一个就会静默拿到空输出或挂住等 stdin。
     */
    private static final List<String> VERSION_ARGS = List.of("--version");

    private static final List<RuntimePreset> PRESETS = List.of(
        // 环境变量名全部是 2026-11-04 从本机二进制/PATH + OpenDesign agent def 双向核实的。
        // ⚠️ 每家的「key 变量名」不止一个：claude 认 ANTHROPIC_API_KEY 与 ANTHROPIC_AUTH_TOKEN。
        //   顺序与 OpenDesign 的 AGENT_CLI_ENV_KEYS 保持一致（它是 Set，这里用 List 固定展示顺序，
        //   同时决定旧单 key 列 {@code __default__} 映射到哪个变量 —— 取第一个）。
        new RuntimePreset("claude", "Claude Code", "claude", List.of("claude"),
                "CLAUDE_BIN",
                List.of("-p", "{prompt}", "--output-format", "stream-json", "--verbose"),
                "claude-stream-json",
                "CLAUDE_CONFIG_DIR", "ANTHROPIC_BASE_URL",
                List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"),
                // claude 没有可枚举的模型子命令 ⇒ 用别名（OpenDesign 同样如此）
                List.of("sonnet", "opus", "haiku"),
                List.of(),
                ReasoningStyle.NONE,
                List.of(),
                "Anthropic 官方 CLI",
                "Claude Code CLI；不配置时用你自己的 claude 登录态"),
        new RuntimePreset("codex", "Codex CLI", "codex", List.of("codex"),
                "CODEX_BIN",
                // --skip-git-repo-check：不在 git 仓库里会直接拒跑
                // --json：拿 JSONL 结构化事件（否则输出混日志文本）
                List.of("exec", "--skip-git-repo-check", "--json", "{prompt}"),
                "codex-jsonl",
                "CODEX_HOME", "OPENAI_BASE_URL",
                // 对齐 OpenDesign 的 codex 白名单（chunk-5PWCTNQA.mjs:AGENT_CLI_ENV_KEYS）：
                // 只有 CODEX_API_KEY / OPENAI_API_KEY 两个 key 变量 —— ⛔ 不列 DEEPSEEK_API_KEY。
                // 走哪家 provider 由 ~/.codex/config.toml 的 [{@code [model_providers.*] env_key}
                // 决定；本机那条指向 OPENAI_API_KEY（真机 12s 出正文）。
                List.of("CODEX_API_KEY", "OPENAI_API_KEY"),
                // 内置兜底（实时拉取失败时才用）。真机 `codex debug models` 实测：当前模型族
                // 是 gpt-6.x / gpt-5.6.x，档位 low→ultra；老版本才有 none/minimal。
                // ⚠️ 这份清单是 codex 的**内置模型目录**，与当前 provider 无关：
                //   走 DeepSeek 时只有 deepseek-flash / deepseek-v4-pro 可用，
                //   选 gpt-6.x 会被上游拒绝（The supported API model names are ...）。
                List.of("gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna",
                        "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
                        "gpt-5.5", "gpt-5.4", "o3", "o4-mini"),
                List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                ReasoningStyle.CODEX_CONFIG,
                List.of("debug", "models"),
                "OpenAI 官方 CLI",
                "Codex CLI；不配置时模型/供应商读 ~/.codex/config.toml"),
        // opencode / pi：⛔ 不提供 API key 与 base URL（用户 2026-11-04 拍板）。
        // WHY：这两家的 key 变量名与接口地址都**随 provider 变**（pi 文档列了 20+ 家：
        //   Anthropic→ANTHROPIC_API_KEY、OpenAI→OPENAI_API_KEY、DeepSeek→DEEPSEEK_API_KEY…），
        //   base URL 更不是环境变量、只存在于各自配置文件（pi: models.json / opencode: opencode.json）。
        // ⇒ 写死任何一个名字都是「页面填了但不生效」，比不给框难查得多。
        // 对齐 OpenDesign 的白名单（chunk-5PWCTNQA.mjs）：opencode 只有 OPENCODE_BIN；
        //   pi 有 PI_BIN + PI_CODING_AGENT_DIR。
        // 凭据请在这两个 CLI 内自行登录/配置。
        new RuntimePreset("opencode", "opencode", "opencode", List.of("opencode"),
                "OPENCODE_BIN",
                List.of("run", "--format", "json", "{prompt}"),
                "opencode-jsonl",
                null, null,
                List.of(),
                List.of(),
                // opencode 的 reasoning 是**模型级 variant**，不是全局档位 ⇒ 这里不预置，
                // 由实时模型列表里的 reasoningOptions 决定（前端按模型渲染）。
                List.of(),
                ReasoningStyle.OPENCODE_VARIANT,
                List.of("models", "--verbose"),
                "开源编码代理",
                "opencode CLI；凭据与接口地址跟 provider 走，请在 opencode 内登录"),
        new RuntimePreset("pi", "pi", "pi", List.of("pi"),
                "PI_BIN",
                List.of("-p", "--mode", "json", "--no-session", "{prompt}"),
                "pi-jsonl",
                "PI_CODING_AGENT_DIR", null,
                // ⛔ 不提供 key 变量（同 opencode：变量名随 provider 变，见上方说明）。
                List.of(),
                List.of("anthropic/claude-sonnet-4-5", "anthropic/claude-opus-4-5",
                        "openai/gpt-5", "openai/o4-mini",
                        "google/gemini-2.5-pro", "google/gemini-2.5-flash"),
                List.of("off", "minimal", "low", "medium", "high", "xhigh"),
                ReasoningStyle.PI_THINKING,
                List.of("--list-models"),
                "极简编码代理",
                "pi coding agent；凭据跟 provider 走，请在 pi 内 /login")
    );

    public static List<RuntimePreset> presets() {
        return PRESETS;
    }

    public static RuntimePreset find(String runtime) {
        if (runtime == null) return null;
        for (RuntimePreset p : PRESETS) {
            if (p.runtime().equalsIgnoreCase(runtime.trim())) return p;
        }
        return null;
    }

    /** 已知的全部运行时 id（错误提示用）。 */
    public static String knownRuntimes() {
        StringBuilder sb = new StringBuilder();
        for (RuntimePreset p : PRESETS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.runtime());
        }
        return sb.toString();
    }

    /**
     * 探测本机装了哪些运行时。
     *
     * <p>默认只查文件是否存在（⛔ 不执行二进制 —— 执行会触发登录/副作用）。
     * 版本号与实时模型列表需要 {@code probeVersion}/{@code listModels} 显式调用。
     *
     * @param overrides runtime → 用户配置的覆盖值（自定义 bin 优先于 PATH 探测）
     * @return 每个预设的探测结果（顺序与 {@link #presets()} 一致）
     */
    public static List<Map<String, Object>> detect(Map<String, String> overrides) {
        List<Map<String, Object>> out = new ArrayList<>(PRESETS.size());
        for (RuntimePreset p : PRESETS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runtime", p.runtime());
            row.put("displayName", p.displayName());
            row.put("notes", p.notes());
            // 覆盖能力声明：前端据此决定哪一栏可编辑
            row.put("configDirEnv", p.configDirEnv());
            row.put("baseUrlEnv", p.baseUrlEnv());
            row.put("apiKeyEnvs", p.apiKeyEnvs());
            row.put("binEnv", p.binEnv());
            row.put("configurable", p.configurable());
            row.put("supportsReasoning", p.supportsReasoning());
            row.put("reasoningOptions", p.reasoningOptions());
            row.put("reasoningStyle", p.reasoningStyle().name());
            row.put("fallbackModels", p.fallbackModels());
            row.put("supportsModelList", !p.listModelsArgs().isEmpty());

            String customBin = overrides == null ? null : overrides.get(p.runtime());
            String resolved = null;
            if (customBin != null && !customBin.isBlank()) {
                resolved = ExternalRuntimeSpec.resolveExecutable(customBin.trim());
            }
            row.put("resolvedPath", resolved);
            row.put("customBinValid", customBin == null || customBin.isBlank() || resolved != null);
            if (resolved == null) {
                for (String candidate : candidatesOf(p)) {
                    resolved = ExternalRuntimeSpec.resolveExecutable(candidate);
                    if (resolved != null) break;
                }
            }
            row.put("available", resolved != null);
            row.put("resolvedPath", resolved);
            // 只看「进程环境里有没有这个变量」——页面配的 key 另算（configured 才权威）
            row.put("apiKeyPresent", p.apiKeyEnvs().isEmpty()
                    || p.apiKeyEnvs().stream().anyMatch(ExternalRuntimeCatalog::hasEnv));
            out.add(row);
        }
        return out;
    }

    /** 兼容入口：不带用户覆盖。 */
    public static List<Map<String, Object>> detect() {
        return detect(Map.of());
    }

    /** 按预设直接生成一个可委派的 agent 定义（供「没写 md 也能用」）。 */
    public static AgentDefinition.ExternalAgentDefinition toAgentDefinition(RuntimePreset p) {
        return AgentDefinition.ExternalAgentDefinition.builder(
                        p.runtime(), p.displayName() + "（外部运行时）", "userSettings", "")
                .runtime(p.runtime())
                .bin(p.bin())
                .fallbackBins(p.fallbackBins())
                .args(p.args())
                .promptVia("argv")
                .streamFormat(p.streamFormat())
                .capabilities(new AgentDefinition.ExternalAgentDefinition.Capabilities(
                        true, false, false, 0))
                .build();
    }

    /**
     * 把用户选的推理强度拼进 argv（照 OpenDesign 各 agent def 的真实形态）。
     *
     * <p>⛔ 三种写法不能混：写错一个参数 CLI 会**静默忽略**（不是报错），
     * 用户会以为「设了 high 但没效果」。
     *
     * @param preset    运行时预设
     * @param reasoning 用户选的档位 id（"default"/空 ⇒ 不追加任何参数）
     * @return 要追加的 argv 片段；不支持或无选择时为空
     */
    public static List<String> reasoningArgs(RuntimePreset preset, String reasoning) {
        if (preset == null || reasoning == null) return List.of();
        String r = reasoning.trim();
        if (r.isEmpty() || "default".equalsIgnoreCase(r)) return List.of();
        if (preset.reasoningStyle() == ReasoningStyle.NONE) return List.of();
        // ⚠️ 不校验「是否在 preset.reasoningOptions 里」：codex 的档位是**按模型**给的
        //    （gpt-6-luna 到 max、gpt-6.1-sol 到 ultra），全局表只是兜底展示。
        //    在这里拦会让用户选中的 max/ultra 静默不生效。
        if (!r.matches("^[A-Za-z][A-Za-z0-9_-]{0,31}$")) return List.of();
        return switch (preset.reasoningStyle()) {
            case NONE -> List.of();
            case CODEX_CONFIG -> List.of("-c", "model_reasoning_effort=\"" + r + "\"");
            case PI_THINKING -> List.of("--thinking", r);
            case OPENCODE_VARIANT -> List.of("--variant", r);
        };
    }

    /**
     * 把用户选的模型拼进 argv。
     *
     * <p>各家的标志不同：claude/codex/pi 都是 {@code --model}，opencode 是 {@code -m}。
     * 用预设里的 args 模板无法表达（模型可选），所以单独提供。
     */
    public static List<String> modelArgs(RuntimePreset preset, String model) {
        if (preset == null || model == null) return List.of();
        String m = model.trim();
        if (m.isEmpty() || "default".equalsIgnoreCase(m)) return List.of();
        return switch (preset.runtime()) {
            case "opencode" -> List.of("-m", m);
            case "pi" -> List.of("--model", m);
            default -> List.of("--model", m);
        };
    }

    private static List<String> candidatesOf(RuntimePreset p) {
        List<String> c = new ArrayList<>();
        if (p.bin() != null) c.add(p.bin());
        c.addAll(p.fallbackBins());
        c.add(p.runtime());
        return c;
    }

    private static boolean hasEnv(String key) {
        if (key == null) return false;
        String v = System.getenv(key);
        return v != null && !v.isBlank();
    }

    private ExternalRuntimeCatalog() {
    }
}
