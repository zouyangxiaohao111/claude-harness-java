package com.nexusai.apis.hook;

import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HooksSettings;
import com.nexusai.common.RequestContext;
import com.nexusai.model.hook.dto.HookItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook REST 端点 · 对齐 CC {@code getAllHooks()}（hooksSettings.ts:92-161）+ 插件 hook 的
 * registeredHooks 通道（hooksConfigManager.ts:322-362）的 web 消费出口。
 *
 * <p>WHY (联调三问题·hooks 端点): 前端 {@code nexusai/src/api/hooks.ts} 调
 * {@code GET /api/v1/hooks} 展示 HookPanel 真实 hook 列表 —— 全库此前无此路由（404 属实）。
 * 本端点承载读路径：返回 {@link HookItemDto} 列表（对齐前端 HookItem，types.ts:855-865）。
 *
 * <p><b>插件 hook 合并</b>（[hooks-plugin-display]）：CC 端插件 hook 走独立 registeredHooks
 * 通道进 UI（hooksConfigManager.ts:323-345 标 source='pluginHook'+pluginName），<b>不</b>进
 * {@code getAllHooks}（hooksSettings.ts:92-161 硬编码 3 editable + session）。Java 端同理：
 * 插件 hook 元数据存于 {@link HookRegistry#getRegisteredPluginHookConfigs()}（registered
 * matcher store，pluginRoot != null），本端点把 settings 结果 <b>concat</b> 插件结果返回——
 * 前端 HookPanel 按 source=PLUGIN_HOOK + pluginName 正确渲染（对齐 CC 双通道合并语义，
 * 非恢复 DEL-CFG-B 的 getAllHooks PLUGIN_HOOK 源）。
 *
 * <p><b>sessionId 解析</b>（复用 {@code MemoryController:136-137} 模式）:
 * query {@code ?sessionId=} 非空 → 用之；否则 {@link RequestContext#sessionId()}（MDC，可 null）。
 * <ul>
 *   <li>sessionId 非空 → {@code hooksSettings.getAllHooks(sessionId)}（settings + session 合并；
 *       {@code HookRegistry.setHooksSettings} 已接线 sessionHooksProvider → SessionHookStore，
 *       满足决策 4-3『运行时会话』）</li>
 *   <li>null/blank → {@code getAllHooks()}（settings-only；allowManagedHooksOnly 守卫语义，
 *       UI 安全缺省 —— HookPanel 当前不传 sessionId 即落此路径）</li>
 * </ul>
 * 两个分支都再 concat {@link HookRegistry#getRegisteredPluginHookConfigs()}（插件 hook 展示
 * 与 sessionId 无关，CC registeredHooks 全局注册表语义）。
 *
 * <p><b>鉴权/CORS</b>: /api/v1/hooks 不在 {@code BearerTokenAuthFilterConfig:76-83} 受保护
 * 端点族内 → 无需鉴权（对齐 SettingsController/SkillController）；CORS 已由 WebConfig
 * {@code addCorsMappings('/api/**')} 覆盖。
 */
@RestController
@RequestMapping("/api/v1/hooks")
public class HookController {

    private static final Logger log = LoggerFactory.getLogger(HookController.class);

    @Autowired private HooksSettings hooksSettings;
    /** [hooks-plugin-display] 插件 hook 注册中心 · 读取 registered 插件 matcher 供展示合并。 */
    @Autowired private HookRegistry hookRegistry;

    /**
     * 获取所有 hook 列表 · 对齐 CC {@code getAllHooks()}（hooksSettings.ts:92-161）+
     * 插件 registeredHooks 通道（hooksConfigManager.ts:322-362）。
     *
     * @param sessionIdParam query {@code ?sessionId=}（可选；非空走 settings+session 合并）
     * @return HookItemDto 列表（settings + session + 插件；前端 HookItem 形状）
     */
    @GetMapping
    public List<HookItemDto> getAllHooks(
            @RequestParam(value = "sessionId", required = false) String sessionIdParam) {
        // 复用 MemoryController:136-137 模式：query ?sessionId= 优先，MDC 兜底（可 null）
        String sessionId = (sessionIdParam != null && !sessionIdParam.isBlank())
            ? sessionIdParam : RequestContext.sessionId();
        if (log.isDebugEnabled()) {
            log.debug("[HookController] GET /api/v1/hooks: sessionIdParam='{}' → 解析 sessionId={}",
                sessionIdParam, sessionId);
        }
        // settings（+ 可选 session）与插件 hooks 合并：CC 双通道（getAllHooks + registeredHooks）
        List<com.nexusai.application.agent.permission.hook.IndividualHookConfig> merged =
            new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            merged.addAll(hooksSettings.getAllHooks(sessionId));
        } else {
            merged.addAll(hooksSettings.getAllHooks());
        }
        // 插件 hook（registered matcher store，pluginRoot != null）· 对齐 CC registeredHooks 通道
        if (hookRegistry != null) {
            merged.addAll(hookRegistry.getRegisteredPluginHookConfigs());
        }
        return merged.stream()
            .map(HookItemDto::from)
            .toList();
    }
}
