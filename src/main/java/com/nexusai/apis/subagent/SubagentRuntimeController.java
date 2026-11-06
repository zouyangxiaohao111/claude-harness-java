package com.nexusai.apis.subagent;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeCatalog;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.domain.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理外部运行时 · 探测接口。
 *
 * <p><b>为什么要这个接口</b>（用户 2026-11-04：「像 opendesign 那样自动感知，前端页面里可配置」）：
 * 用户装没装 {@code claude} / {@code codex} / {@code opencode} / {@code pi} 只有后端知道
 * （PATH 在服务进程里）。前端不能自己猜，必须来问这里。
 *
 * <p><b>返回两块，别混为一谈</b>：
 * <ul>
 *   <li>{@code runtimes} —— 内置预设 + 本机探测（「这台机器支持哪些 CLI」）</li>
 *   <li>{@code agents} —— 本会话**实际可派发的 agentType**（含用户写的 md profile）。
 *       这才是「默认运行时」该选的粒度：内置 {@code claude-external} 与用户自己写的
 *       {@code claude-via-proxy}（同 runtime 不同 bin）必须能并列选。</li>
 * </ul>
 *
 * <p>⛔ 探测只查文件是否存在，**不执行二进制** —— 执行 {@code --version} 会触发登录提示等副作用。
 */
@RestController
@RequestMapping("/api/v1/subagents/runtimes")
public class SubagentRuntimeController {

    private static final Logger log = LoggerFactory.getLogger(SubagentRuntimeController.class);

    private final SettingsService settingsService;
    private final SubagentTool subagentTool;

    public SubagentRuntimeController(SettingsService settingsService,
                                     @Lazy SubagentTool subagentTool) {
        this.settingsService = settingsService;
        this.subagentTool = subagentTool;
    }

    /**
     * GET /api/v1/subagents/runtimes[?sessionId=...]
     *
     * <p>返回：① 内置运行时清单 + 本机探测结果 ② 本会话可派发的外部 agent 类型 ③ 当前默认配置。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String sessionId) {
        List<Map<String, Object>> runtimes = ExternalRuntimeCatalog.detect();
        List<Map<String, Object>> agents = collectExternalAgents(sessionId);

        String defaultRuntime = null;
        String defaultAgentType = null;
        Boolean enabled = null;
        try {
            var dto = settingsService.get();
            if (dto != null) {
                defaultRuntime = dto.subagentDefaultRuntime();
                enabled = dto.subagentExternalEnabled();
            }
        } catch (Exception e) {
            // 读设置失败不能把整张清单拖垮：清单本身是静态数据 + PATH 探测
            log.warn("[SubagentRuntimeController] 读子代理运行时设置失败（清单照常返回）: {}", e.toString());
        }
        // 兼容旧值：老配置里存的是 runtime id，新语义存 agentType —— 两种都认
        defaultAgentType = defaultRuntime;

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("defaultRuntime", defaultRuntime);
        current.put("defaultAgentType", defaultAgentType);
        current.put("externalEnabled", enabled);
        current.put("configured", defaultRuntime != null || enabled != null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runtimes", runtimes);
        resp.put("agents", agents);
        resp.put("current", current);
        resp.put("availableCount", runtimes.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("available")))
                .count());
        return resp;
    }

    /**
     * 本会话里**所有外部运行时 agent**（含预设生成的与用户 md 写的）及其可派发状态。
     *
     * <p>每项都标 {@code source}：{@code preset} = 内置模板，{@code custom} = 用户在 md 里配的。
     * 前端据此把「内置」与「我的配置」分组显示 —— 用户能一眼看出哪条是自己加的。
     */
    private List<Map<String, Object>> collectExternalAgents(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            if (subagentTool == null) return out;
            var registry = subagentTool.agentRegistry(sessionId);
            if (registry == null) return out;
            for (AgentDefinition def : registry.listAgents()) {
                if (!(def instanceof AgentDefinition.ExternalAgentDefinition ext)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("agentType", ext.agentType());
                row.put("runtime", ext.runtime());
                row.put("description", ext.whenToUse());
                row.put("streamFormat", ext.streamFormat());
                // 只写 runtime ⇒ 用内置预设；写了 args/bin 才算用户自定义
                row.put("source", (!ext.args().isEmpty() || ext.bin().isPresent()) ? "custom" : "preset");
                row.put("available", com.nexusai.application.agent.subagent.external
                        .ExternalRuntimeSpec.tryResolveBin(ext).isPresent());
                out.add(row);
            }
        } catch (Exception e) {
            log.warn("[SubagentRuntimeController] 枚举外部 agent 失败（runtimes 清单照常返回）: {}", e.toString());
        }
        return out;
    }
}
