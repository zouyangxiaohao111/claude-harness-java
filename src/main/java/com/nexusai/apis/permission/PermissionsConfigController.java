package com.nexusai.apis.permission;

import com.nexusai.application.agent.permission.PermissionConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * bypassPermissions 数据库开关 REST 管理端点 · 对齐 CC Statsig org 门
 * {@code 'tengu_disable_bypass_permissions_mode'}（permissionSetup.ts:701 / :934 / :1374）。
 *
 * <h2>为什么存在（方案 A，用户拍板）</h2>
 * <p>本项目无 Statsig infra，CC 生产恒 {@code false}。数据库存开关 + 启动读一次 + 登录重读，
 * 本端点提供运行时读 / 写（写库 + 刷新缓存），替代 CC Statsig 后台配置面。
 *
 * <h2>端点</h2>
 * <ul>
 *   <li>{@code GET  /api/v1/permissions/bypass-config} —— 当前开关状态（读缓存，不查 DB）</li>
 *   <li>{@code PUT  /api/v1/permissions/bypass-config/disable} —— 禁用 bypassPermissions（写库 + refresh）</li>
 *   <li>{@code PUT  /api/v1/permissions/bypass-config/enable} —— 启用 bypassPermissions（写库 + refresh）</li>
 * </ul>
 *
 * <p>路径约定：沿用本仓 {@code /api/v1/...} 规范（任务草案 {@code /api/admin/permissions} 全仓无
 * {@code /api/admin} 命名空间，按 CLAUDE.md 规则十一对齐现有 {@code /api/v1/permissions/*} 惯例）。
 */
@RestController
@RequestMapping("/api/v1/permissions/bypass-config")
public class PermissionsConfigController {

    private static final Logger log = LoggerFactory.getLogger(PermissionsConfigController.class);

    @Autowired
    private PermissionConfigProvider permissionConfigProvider;

    /** 当前开关状态（读缓存）。 */
    @GetMapping
    public Map<String, Object> get() {
        boolean disabled = permissionConfigProvider.isBypassPermissionsDisabled();
        log.info("[PermissionsConfigController] 查询 bypassPermissions 禁用开关：disableBypassPermissions={}", disabled);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disableBypassPermissions", disabled);
        return body;
    }

    /** 禁用 bypassPermissions（写库 + refresh）。 */
    @PutMapping("/disable")
    public Map<String, Object> disable() {
        permissionConfigProvider.setDisableBypassPermissions(true);
        log.warn("[PermissionsConfigController] 已禁用 bypassPermissions（对齐 CC tengu_disable_bypass_permissions_mode=true）");
        return Map.of("disableBypassPermissions", true);
    }

    /** 启用 bypassPermissions（写库 + refresh）。 */
    @PutMapping("/enable")
    public Map<String, Object> enable() {
        permissionConfigProvider.setDisableBypassPermissions(false);
        log.info("[PermissionsConfigController] 已启用 bypassPermissions（对齐 CC tengu_disable_bypass_permissions_mode=false）");
        return Map.of("disableBypassPermissions", false);
    }
}
