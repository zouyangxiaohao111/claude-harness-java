package com.nexusai.apis.mcp;

import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpImportRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import com.nexusai.domain.mcp.McpServerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MCP Server REST 端点 · 对齐 CC {@code claude mcp} 命令族。
 *
 * <p>AC-1 方案 A 契约（mcp-add-align plan §1.2）：REST add（POST /api/v1/mcp）= 完整校验链
 * a~g → 写配置源文件（project → .mcp.json 原子写 / user → .nexusai.json 顶层）+ 同步 upsert DB
 * （Q-09=C 运行时源即时生效）；REST delete（DELETE /{id}）= 删 DB + OAuth 凭据清理 + 同步移除
 * 配置源条目。前端 G5（add/remove/列表/失败提示）全部由本控制器承载。
 *
 * <p>错误契约（前端 G5-4 消费，GlobalExceptionHandler 转 RFC 7807 Problem，detail 即 CC 逐字文案）：
 * ValidationException → 400、ConflictException → 409、NotFoundException → 404。
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    @Autowired private McpServerService mcpServerService;

    /** G5-1 列表：返回扩展 DTO（远程 server 反解 url/headers/oauth，供前端回显）。 */
    @GetMapping
    public List<McpServerDto> list() {
        List<McpServerDto> dtos = mcpServerService.listAll();
        if (log.isDebugEnabled()) {
            log.debug("[McpServerController] GET /api/v1/mcp → {} servers", dtos.size());
        }
        return dtos;
    }

    @GetMapping("/{id}")
    public McpServerDto get(@PathVariable String id) {
        McpServerDto dto = mcpServerService.getById(id);
        if (log.isDebugEnabled()) {
            log.debug("[McpServerController] GET /api/v1/mcp/{} → server={}", id, dto.name());
        }
        return dto;
    }

    /**
     * G5-2 add · 对齐 CC {@code claude mcp add}（addCommand.ts:81-279 → addMcpConfig
     * config.ts:625-761）：完整校验链 → 按 scope 写配置源文件 → 同步 upsert DB → 返回扩展 DTO
     * （含 scope/filePath/warnings，供前端展示「已写入 <path>」+ 非阻断警告）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpServerDto create(@Valid @RequestBody McpCreateRequest req) {
        if (log.isDebugEnabled()) {
            log.debug("[McpServerController] POST /api/v1/mcp 创建请求 name={} type={} scope={}",
                req.name(), req.type(), req.scope());
        }
        McpServerDto dto = mcpServerService.create(req);
        log.info("[McpServerController] 已创建 MCP server name={} id={} scope={} type={} filePath={}",
            dto.name(), dto.id(), dto.scope(), dto.type(), dto.filePath());
        return dto;
    }

    /**
     * G5-2/编辑 · 对齐 CC updateMcpServer：复用校验链（重复检查排除自身）→ 更新 DB →
     * 同步写回配置源条目（含重命名移除旧名）→ enabled 级联 stop / 自动 start 既有逻辑保留。
     */
    @PatchMapping("/{id}")
    public McpServerDto update(@PathVariable String id,
                               @RequestBody McpCreateRequest req) {
        if (log.isDebugEnabled()) {
            log.debug("[McpServerController] PATCH /api/v1/mcp/{} 更新请求 name={} type={} scope={}",
                id, req.name(), req.type(), req.scope());
        }
        McpServerDto dto = mcpServerService.update(id, req);
        log.info("[McpServerController] 已更新 MCP server id={} name={} scope={}",
            id, dto.name(), dto.scope());
        return dto;
    }

    /**
     * G5-3 remove · 对齐 CC removeMcpConfig：删 DB 行 + OAuth 凭据清理（CE-13）→ 同步移除
     * 配置源条目（project+user best-effort，不存在 no-op）。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        if (log.isDebugEnabled()) {
            log.debug("[McpServerController] DELETE /api/v1/mcp/{} 删除请求", id);
        }
        mcpServerService.delete(id);
        log.info("[McpServerController] 已删除 MCP server id={}（DB + 配置源已同步）", id);
    }

    @PostMapping("/{id}/test")
    public TestConnectionResponse test(@PathVariable String id) {
        return mcpServerService.test(id);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<McpServerDto> start(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mcpServerService.start(id));
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<McpServerDto> stop(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mcpServerService.stop(id));
    }

    /**
     * T5: .mcp.json 导入 → DB 写回（Q-09=C 导入入口；运行时仍从 DB 读）。
     * files = scope → 文件路径（project/user/local/... 多 scope 合并，同名字 local 胜）。
     * 导入的 pending server 由前端经 /{id}/approve|reject 审批（FM-1）。
     */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.OK)
    public McpServerService.McpServerImportResult importServers(
            @RequestBody McpImportRequest req) {
        if (req == null || req.files() == null || req.files().isEmpty()) {
            throw new com.nexusai.infra.exception.ValidationException("files (scope → .mcp.json path) required");
        }
        McpServerService.McpServerImportResult result = mcpServerService.importFromMcpJson(req.files());
        log.info("[McpServerController] 导入完成 imported={} blocked={} suppressed={}",
            result.imported(), result.blocked(), result.suppressed());
        return result;
    }

    /** T7: 审批通过（pending → approved + enabled）· Q-25 状态机。 */
    @PostMapping("/{id}/approve")
    public McpServerDto approve(@PathVariable String id) {
        return mcpServerService.approve(id);
    }

    /** T7: 审批拒绝（→ rejected + enabled=false）。 */
    @PostMapping("/{id}/reject")
    public McpServerDto reject(@PathVariable String id) {
        return mcpServerService.reject(id);
    }
}
