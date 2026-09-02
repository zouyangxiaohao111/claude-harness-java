package com.nexusai.apis.permission;

import com.nexusai.application.agent.permission.AutoModeDenials;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auto-mode 拒绝记录只读 REST 端点 · 对齐 CC {@code getAutoModeDenials()}
 * （utils/autoModeDenials.ts:24-26）的 Java 输出面。
 *
 * <p><b>WHY 存在</b>: CC RecentDenialsTab（/permissions 面板 "Recently denied" Tab）挂载时
 * 快照读取 {@code getAutoModeDenials()}（RecentDenialsTab.tsx:204-206）。Java store
 * （{@link AutoModeDenials}）已对齐但 {@code getAutoModeDenials()} 零消费者 → nexusai-ui
 * 无数据可复刻 RecentDenials 面板。本端点补齐只读输出面。
 *
 * <p><b>字段对齐</b>: 响应元素 = CC {@code AutoModeDenial} 类型
 * （autoModeDenials.ts:8-14）{@code {toolName, display, reason, timestamp}} + SDK 面补全字段
 * {@code toolUseId}/{@code toolInput}（GC-04 / OPD-WF7-GC-04，SDKPermissionDenialSchema
 * entrypoints/sdk/coreSchemas.ts:1399-1404 的 {@code tool_use_id}/{@code tool_input}），
 * camelCase 与 Java 约定一致。
 *
 * <p><b>SDK 面对齐（GC-04 补全，原有意保留差异已移除）</b>: CC {@code SDKPermissionDenialSchema}
 * 字段为 snake_case {@code {tool_name, tool_use_id, tool_input}}，供 SDK 消息输出
 * （QueryEngine.ts:260-267 {@code permission_denials} 数组，QueryEngine.ts:631 起每次
 * result 消息携带）。Java store 已补采集 tool_use_id/tool_input（recordAutoModeDenial 6 参，
 * 来源 ToolUseBlock.id / ToolUseBlock.input），故本端点输出完整 SDK 兼容字段
 * （toolName↔tool_name / toolUseId↔tool_use_id / toolInput↔tool_input），缺口已闭合
 * （原 EV-WF7-GC-001）。
 *
 * <p><b>语义</b>: 只读、无副作用；返回最近最多 20 条（store 已保证上限），最近拒绝在前
 * —— 与 CC {@code getAutoModeDenials()} 快照语义一致，无分页（CC 亦无分页概念）。
 */
@RestController
@RequestMapping("/api/v1/permissions/auto-mode-denials")
public class AutoModeDenialsController {

    private static final Logger log = LoggerFactory.getLogger(AutoModeDenialsController.class);

    /**
     * 读取最近 auto-mode 拒绝记录 · 对齐 CC {@code getAutoModeDenials()}.
     *
     * @return 不可变快照列表（最近在前，最多 20 条）
     */
    @GetMapping
    public List<AutoModeDenials.AutoModeDenial> get() {
        List<AutoModeDenials.AutoModeDenial> denials = AutoModeDenials.getAutoModeDenials();
        if (log.isDebugEnabled()) {
            log.debug("读取 auto-mode 拒绝记录: 返回 {} 条（MAX 20，最近在前）", denials.size());
        }
        return denials;
    }
}
