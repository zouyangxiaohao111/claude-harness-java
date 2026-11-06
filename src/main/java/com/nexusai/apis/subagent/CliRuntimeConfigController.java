package com.nexusai.apis.subagent;

import com.nexusai.application.agent.subagent.external.ExternalRuntimeCatalog;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeEnvStore;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeProbe;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeSpec;
import com.nexusai.domain.subagent.CliRuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 CLI 运行时 · 探测 + 连接配置（对齐 OpenDesign「你的 CLI」设置页）。
 *
 * <p><b>为什么放在这里而不是 settings</b>：settings 是一张单行表（全局设置），而这里是
 * 「每个 runtime 一行」的多行配置，粒度不同。前端仍在「设置 → 环境配置」展示。
 *
 * <p><b>四个接口的分工（别混）</b>：
 * <ul>
 *   <li>{@code GET /} —— 清单。**只查文件存在性，不执行任何二进制**（快）</li>
 *   <li>{@code GET /{runtime}/version} —— 跑一次 {@code --version} 取版本号</li>
 *   <li>{@code GET /{runtime}/models} —— 跑一次模型子命令取实时列表</li>
 *   <li>{@code POST /{runtime}/test} —— 真的发一次极小提示词（**消耗一次模型调用**）</li>
 * </ul>
 * 后三个都是**用户点按钮才触发**的显式动作，⛔ 不在列表接口里静默执行。
 *
 * <p>⛔ 响应永不含明文 key，只有掩码；请求里的 key 只单向写入。
 */
@RestController
@RequestMapping("/api/v1/subagents/runtime-configs")
public class CliRuntimeConfigController {

    private static final Logger log = LoggerFactory.getLogger(CliRuntimeConfigController.class);

    private final CliRuntimeConfigService service;

    public CliRuntimeConfigController(CliRuntimeConfigService service) {
        this.service = service;
    }

    /**
     * GET /api/v1/subagents/runtime-configs
     *
     * <p>每个预设运行时 + 已保存配置 + 该 CLI 认的环境变量名 + 支持的能力位。
     * 前端据此渲染整张卡片（模型下拉、推理强度下拉、高级三项、测试按钮）。
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<CliRuntimeConfigService.View> saved = service.list();
        Map<String, String> binOverrides = new LinkedHashMap<>();
        for (CliRuntimeConfigService.View v : saved) {
            if (v.bin() != null && !v.bin().isBlank()) binOverrides.put(v.runtime(), v.bin());
        }

        for (ExternalRuntimeCatalog.RuntimePreset p : ExternalRuntimeCatalog.presets()) {
            CliRuntimeConfigService.View v = null;
            for (CliRuntimeConfigService.View s : saved) {
                if (s.runtime().equals(p.runtime())) { v = s; break; }
            }
            if (v == null) v = service.get(p.runtime());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runtime", p.runtime());
            row.put("displayName", p.displayName());
            // 列表行紧跟名称的短描述（OpenDesign: 「Codex CLI · OpenAi official CLI」）
            row.put("tagline", p.tagline());
            row.put("configurable", p.configurable());
            // 三项各自的目标环境变量名（null = 该 CLI 不认这个通用变量，前端置灰）
            row.put("configDirEnv", p.configDirEnv());
            row.put("baseUrlEnv", p.baseUrlEnv());
            row.put("apiKeyEnvs", p.apiKeyEnvs());
            row.put("binEnv", p.binEnv());

            // 已保存值（key 只有掩码）
            row.put("configDir", v.configDir());
            row.put("baseUrl", v.baseUrl());
            row.put("secrets", v.secrets());
            row.put("apiKeySet", v.apiKeySet());
            row.put("bin", v.bin());
            row.put("model", v.model());
            row.put("reasoning", v.reasoning());
            row.put("configured", v.configDir() != null || v.baseUrl() != null || v.apiKeySet()
                    || v.bin() != null || v.model() != null || v.reasoning() != null);
            row.put("updatedAt", v.updatedAt());

            // 能力位：前端按位渲染（不支持就不显示该控件，⛔ 不显示灰掉的假控件）
            row.put("supportsReasoning", p.supportsReasoning());
            row.put("reasoningOptions", p.reasoningOptions());
            row.put("reasoningStyle", p.reasoningStyle().name());
            row.put("supportsModelList", !p.listModelsArgs().isEmpty());
            row.put("fallbackModels", p.fallbackModels());

            // 可执行文件：自定义 bin 优先（用户配对了他就信他），否则 PATH 探测
            String resolved = null;
            if (v.bin() != null && !v.bin().isBlank()) {
                resolved = ExternalRuntimeSpec.resolveExecutable(v.bin().trim());
            }
            if (resolved == null) {
                for (String c : candidatesOf(p)) {
                    resolved = ExternalRuntimeSpec.resolveExecutable(c);
                    if (resolved != null) break;
                }
            }
            row.put("available", resolved != null);
            row.put("resolvedPath", resolved);
            out.add(row);
        }
        return out;
    }

    /**
     * GET /api/v1/subagents/runtime-configs/{runtime}/version
     *
     * <p>跑一次 {@code --version}。失败返回 {@code version: null}，不报错 ——
     * 「这台机器装了个探不出版本的 CLI」不该让整张卡片渲染不出来。
     */
    @GetMapping("/{runtime}/version")
    public Map<String, Object> version(@PathVariable String runtime) {
        ExternalRuntimeCatalog.RuntimePreset preset = requirePreset(runtime);
        String bin = resolveBinFor(preset);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runtime", preset.runtime());
        resp.put("available", bin != null);
        resp.put("resolvedPath", bin);
        resp.put("version", bin == null ? null : ExternalRuntimeProbe.probeVersion(bin));
        return resp;
    }

    /**
     * GET /api/v1/subagents/runtime-configs/{runtime}/models
     *
     * <p>跑该 CLI 的模型子命令取**实时列表**。拉不到就返回内置列表 + {@code live:false}，
     * 前端据此显示「内置列表」而不是「来自 CLI 的实时列表」。
     */
    @GetMapping("/{runtime}/models")
    public Map<String, Object> models(@PathVariable String runtime) {
        ExternalRuntimeCatalog.RuntimePreset preset = requirePreset(runtime);
        String bin = resolveBinFor(preset);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runtime", preset.runtime());
        resp.put("resolvedPath", bin);

        if (bin == null) {
            resp.put("live", false);
            resp.put("models", preset.fallbackModels());
            resp.put("reason", "未找到可执行文件");
            return resp;
        }
        if (preset.listModelsArgs().isEmpty()) {
            // claude 没有可枚举模型的子命令 ⇒ 直接用别名（OpenDesign 同样处理）
            resp.put("live", false);
            resp.put("models", preset.fallbackModels());
            resp.put("reason", "该 CLI 不支持枚举模型");
            return resp;
        }

        List<ExternalRuntimeProbe.ModelInfo> live = ExternalRuntimeProbe.listModels(preset, bin,
                ExternalRuntimeEnvStore.envFor(preset));
        resp.put("live", !live.isEmpty());
        if (live.isEmpty()) {
            resp.put("models", preset.fallbackModels());
            resp.put("modelInfos", fallbackModelInfos(preset));
            resp.put("reason", "拉取失败，已回落内置列表");
        } else {
            List<String> ids = new ArrayList<>(live.size());
            List<Map<String, Object>> infos = new ArrayList<>(live.size());
            for (ExternalRuntimeProbe.ModelInfo m : live) {
                ids.add(m.id());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", m.id());
                // 该模型自己支持的推理档位（为空 ⇒ 前端退回预设的全局档位或不显示）
                row.put("reasoningOptions", m.reasoningOptions());
                infos.add(row);
            }
            resp.put("models", ids);
            resp.put("modelInfos", infos);
        }
        return resp;
    }

    /**
     * POST /api/v1/subagents/runtime-configs/{runtime}/test
     *
     * <p>⚠️ 会真的发一次模型调用（成本极小，但确实消耗配额）。
     * 先用库里已保存的配置跑；所以「先保存再测试」才是正确顺序。
     */
    @PostMapping("/{runtime}/test")
    public Map<String, Object> test(@PathVariable String runtime) {
        ExternalRuntimeCatalog.RuntimePreset preset = requirePreset(runtime);
        String bin = resolveBinFor(preset);
        ExternalRuntimeProbe.TestResult result = ExternalRuntimeProbe.test(preset, bin,
                ExternalRuntimeEnvStore.envFor(preset), ExternalRuntimeEnvStore.modelFor(preset.runtime()));

        // 结果存档：用户下次打开页面能看到「上次测过、什么时候、成不成」
        try {
            service.recordTestResult(preset.runtime(), result.ok(), result.error());
        } catch (Exception e) {
            log.warn("[CliRuntimeConfig] 记录测试结果失败（不影响本次结果）: {}", e.toString());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runtime", preset.runtime());
        resp.put("ok", result.ok());
        resp.put("elapsedMs", result.elapsedMs());
        resp.put("sample", result.sample());
        resp.put("error", result.error());
        return resp;
    }

    /**
     * PUT /api/v1/subagents/runtime-configs/{runtime}
     *
     * <p>请求体各项「缺省 = 不改，空串 = 清空」。前端只在用户真的编辑了某项时才带上它，
     * 避免「打开页面点保存」把未显示的原值清掉。
     */
    @PutMapping("/{runtime}")
    public Map<String, Object> save(@PathVariable String runtime,
                                    @RequestBody SaveBody body) {
        try {
            CliRuntimeConfigService.View v = service.save(runtime,
                    new CliRuntimeConfigService.SaveRequest(
                            body == null ? null : body.configDir(),
                            body == null ? null : body.baseUrl(),
                            body == null ? null : body.bin(),
                            body == null ? null : body.model(),
                            body == null ? null : body.reasoning(),
                            body == null ? null : body.secrets()));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("runtime", v.runtime());
            resp.put("configDir", v.configDir());
            resp.put("baseUrl", v.baseUrl());
            resp.put("secrets", v.secrets());
            resp.put("apiKeySet", v.apiKeySet());
            resp.put("bin", v.bin());
            resp.put("model", v.model());
            resp.put("reasoning", v.reasoning());
            return resp;
        } catch (IllegalArgumentException e) {
            // 未知 runtime / 非法推理强度 / 非法变量名 = 用户输入问题 ⇒ 400（不是 500）
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("[CliRuntimeConfig] 保存 runtime={} 失败", runtime, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "保存失败: " + e.getMessage());
        }
    }

    /** 内置列表转成与实时列表同形的结构（档位用预设的全局档位）。 */
    private List<Map<String, Object>> fallbackModelInfos(ExternalRuntimeCatalog.RuntimePreset p) {
        List<Map<String, Object>> out = new ArrayList<>(p.fallbackModels().size());
        for (String id : p.fallbackModels()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("reasoningOptions", p.reasoningOptions());
            out.add(row);
        }
        return out;
    }

    private ExternalRuntimeCatalog.RuntimePreset requirePreset(String runtime) {
        ExternalRuntimeCatalog.RuntimePreset p = ExternalRuntimeCatalog.find(runtime);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知的外部运行时 '" + runtime + "'，可选："
                            + ExternalRuntimeCatalog.knownRuntimes());
        }
        return p;
    }

    /** 该 runtime 当前该用哪个可执行文件：设置页配的 bin 优先，否则 PATH 探测。 */
    private String resolveBinFor(ExternalRuntimeCatalog.RuntimePreset p) {
        String configured = ExternalRuntimeEnvStore.binFor(p.runtime());
        if (configured != null) {
            String resolved = ExternalRuntimeSpec.resolveExecutable(configured);
            if (resolved != null) return resolved;
            log.warn("[CliRuntimeConfig] runtime={} 配的 bin '{}' 不存在，回落 PATH 探测",
                    p.runtime(), configured);
        }
        for (String c : candidatesOf(p)) {
            String resolved = ExternalRuntimeSpec.resolveExecutable(c);
            if (resolved != null) return resolved;
        }
        return null;
    }

    private static List<String> candidatesOf(ExternalRuntimeCatalog.RuntimePreset p) {
        List<String> c = new ArrayList<>();
        if (p.bin() != null) c.add(p.bin());
        c.addAll(p.fallbackBins());
        c.add(p.runtime());
        return c;
    }

    /** 请求体 · 各项允许 null（= 不改该项）。 */
    public record SaveBody(String configDir, String baseUrl, String bin, String model,
                           String reasoning, Map<String, String> secrets) {
    }
}
