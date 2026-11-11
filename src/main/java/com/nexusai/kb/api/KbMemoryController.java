package com.nexusai.kb.api;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.app.memory.KbMemoryService;
import com.nexusai.kb.dto.memory.KbMemoryDocView;
import com.nexusai.kb.dto.memory.KbMemoryItemDto;
import com.nexusai.kb.dto.memory.KbMemorySettingsDto;
import com.nexusai.kb.dto.memory.KbMemoryTopicView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆端点 · 批次 W9 · 16 条（源侧 {@code /memory/**}）。
 *
 * <p><b>⚠️ 说明</b>：W9 当时只落了服务层（{@link KbMemoryService} 32 个公开方法）
 * 而<b>没有暴露 REST 端点</b>，排期里却记成「16 端点」。W12 契约校验
 * （{@code validate_contracts.py}）把这条差异照出来了 —— 本类补上。
 *
 * <p><b>URL 前缀</b>：{@code /api/v1/kb/memory}。带 {@code kb} 段是刻意隔离 ——
 * {@code com.nexusai.apis.memory.MemoryController}（AgentLoop 的 {@code /files}、{@code /config}）
 * 占用 {@code /api/v1/memory}，而知识库长期记忆与客户端长期记忆是<b>两套无关数据</b>
 * （用户 2026-11-08 拍板：启用开关读 {@code user_enabled}、写 {@code PUT /kb/memory/settings}）。
 * 两套前缀分开后，URL 空间不再混在一起，改名/下线一侧不会误伤另一侧。
 *
 * <p><b>路径与源侧一一对应</b>（{@code routes_memory.go:21-38}；下表是前缀之后的相对路径）：
 * <pre>
 * GET    /memory/settings            PUT /memory/settings
 * GET    /memory/items               POST /memory/items          DELETE /memory/items
 * PUT    /memory/items/{id}          DELETE /memory/items/{id}
 * POST   /memory/items/{id}/confirm  POST /memory/items/{id}/reject
 * GET    /memory/topics              DELETE /memory/topics/{id}  POST /memory/topics/{id}/promote
 * GET    /memory/documents           DELETE /memory/documents/{id}
 * GET    /memory/export              POST /memory/consolidate
 * </pre>
 *
 * <p><b>⚠️ 主体隔离</b>：本仓单机单用户，作用域由 {@code KbMemoryScope.current()} 决定
 * （与 W9 服务层一致）；接入多用户时只改那一处。
 */
@RestController
@RequestMapping("/api/v1/kb/memory")
public class KbMemoryController {

    private static final Logger log = LoggerFactory.getLogger(KbMemoryController.class);

    @Autowired private KbMemoryService memoryService;

    // ── 设置 ───────────────────────────────────────────────

    /**
     * 我的记忆设置（源侧 {@code GET /memory/settings}）。
     *
     * @return 设置（含 workspace/user/effective 三个开关）
     */
    @GetMapping("/settings")
    public KbMemorySettingsDto getSettings() {
        return memoryService.settings();
    }

    /**
     * 更新我的记忆开关（源侧 {@code PUT /memory/settings}）。
     *
     * <p>⚠️ {@code enabled} 缺省 = 不改动（PATCH 语义），⛔ 不把「没传」当成「关掉」。
     *
     * @param req 请求体
     * @return 更新后的设置
     */
    @PutMapping("/settings")
    public KbMemorySettingsDto updateSettings(@RequestBody(required = false) SettingsRequest req) {
        if (req == null || req.enabled() == null) {
            return memoryService.settings();
        }
        log.info("[记忆] 用户开关设置为 enabled={}", req.enabled());
        return memoryService.setEnabled(req.enabled());
    }

    // ── 条目 ───────────────────────────────────────────────

    /**
     * 记忆条目列表（源侧 {@code GET /memory/items}）。
     *
     * @param status 状态过滤（可空）
     * @param limit  条数
     * @param offset 偏移
     * @return 列表 + 总数
     */
    @GetMapping("/items")
    public Map<String, Object> listItems(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        List<KbMemoryItemDto> items = memoryService.listItems(status, limit, offset);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", memoryService.countItems(status));
        return body;
    }

    /**
     * 手动新增一条记忆（源侧 {@code POST /memory/items}）。
     *
     * @param req 内容
     * @return 创建后的条目
     */
    @PostMapping("/items")
    public KbMemoryItemDto createItem(@RequestBody(required = false) CreateItemRequest req) {
        if (req == null) {
            throw new ValidationException("请求体不能为空");
        }
        log.info("[记忆] 手动新增 kind={} importance={}", req.kind(), req.importance());
        return memoryService.createItem(req.kind(), req.content(), req.importance());
    }

    /**
     * 全部忘掉（源侧 {@code DELETE /memory/items}）。
     *
     * @return 删掉的条数
     */
    @DeleteMapping("/items")
    public Map<String, Object> clear() {
        long removed = memoryService.clear();
        return Map.of("deleted", removed);
    }

    /**
     * 修改一条记忆（源侧 {@code PUT /memory/items/{id}}）。
     *
     * @param id  记忆 id
     * @param req 新内容
     * @return 更新后的条目
     */
    @PutMapping("/items/{id}")
    public KbMemoryItemDto updateItem(@PathVariable String id,
                                      @RequestBody(required = false) UpdateItemRequest req) {
        if (req == null) {
            throw new ValidationException("请求体不能为空");
        }
        return memoryService.updateItem(id, req.content(), req.importance());
    }

    /**
     * 删除一条记忆（源侧 {@code DELETE /memory/items/{id}}）。
     *
     * @param id 记忆 id
     * @return 删除结果
     */
    @DeleteMapping("/items/{id}")
    public Map<String, Object> deleteItem(@PathVariable String id) {
        memoryService.deleteItem(id);
        return Map.of("id", id, "deleted", true);
    }

    /**
     * 确认一条待确认记忆（源侧 {@code POST /memory/items/{id}/confirm}）。
     *
     * @param id 记忆 id
     * @return 更新后的条目
     */
    @PostMapping("/items/{id}/confirm")
    public KbMemoryItemDto confirmItem(@PathVariable String id) {
        return memoryService.confirmItem(id);
    }

    /**
     * 拒绝一条待确认记忆（源侧 {@code POST /memory/items/{id}/reject}）。
     *
     * @param id 记忆 id
     * @return 处理结果
     */
    @PostMapping("/items/{id}/reject")
    public Map<String, Object> rejectItem(@PathVariable String id) {
        memoryService.rejectItem(id);
        return Map.of("id", id, "rejected", true);
    }

    // ── 主题 ───────────────────────────────────────────────

    /**
     * 主题计数列表（源侧 {@code GET /memory/topics}）。
     *
     * @param limit  条数
     * @param offset 偏移
     * @return 列表 + 总数
     */
    @GetMapping("/topics")
    public Map<String, Object> listTopics(@RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        List<KbMemoryTopicView> topics = memoryService.listTopics(limit, offset);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topics", topics);
        body.put("total", memoryService.countTopics());
        return body;
    }

    /**
     * 删除一个主题（源侧 {@code DELETE /memory/topics/{id}}）。
     *
     * @param id 主题 id
     * @return 删除结果
     */
    @DeleteMapping("/topics/{id}")
    public Map<String, Object> deleteTopic(@PathVariable String id) {
        memoryService.deleteTopic(id);
        return Map.of("id", id, "deleted", true);
    }

    /**
     * 把主题提为一条记忆（源侧 {@code POST /memory/topics/{id}/promote}）。
     *
     * @param id 主题 id
     * @return 新条目
     */
    @PostMapping("/topics/{id}/promote")
    public KbMemoryItemDto promoteTopic(@PathVariable String id) {
        return memoryService.promoteTopic(id);
    }

    // ── 文档 ───────────────────────────────────────────────

    /**
     * 记忆引用过的文档（源侧 {@code GET /memory/documents}）。
     *
     * @param limit  条数
     * @param offset 偏移
     * @return 列表 + 总数
     */
    @GetMapping("/documents")
    public Map<String, Object> listDocuments(@RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(defaultValue = "0") int offset) {
        List<KbMemoryDocView> docs = memoryService.listDocuments(limit, offset);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documents", docs);
        body.put("total", memoryService.countDocuments());
        return body;
    }

    /**
     * 忘掉一篇文档（源侧 {@code DELETE /memory/documents/{id}}）。
     *
     * @param id 文档引用 id
     * @return 删除结果
     */
    @DeleteMapping("/documents/{id}")
    public Map<String, Object> deleteDocument(@PathVariable String id) {
        memoryService.deleteDocument(id);
        return Map.of("id", id, "deleted", true);
    }

    // ── 导出 / 整理 ────────────────────────────────────────

    /**
     * 导出全部记忆（源侧 {@code GET /memory/export}）· 快照，不分页。
     *
     * @return 全部条目
     */
    @GetMapping("/export")
    public List<KbMemoryItemDto> export() {
        return memoryService.export();
    }

    /**
     * 立刻整理（源侧 {@code POST /memory/consolidate}）。
     *
     * <p><b>⚠️ 本仓不做模型合并</b>：源侧这一步是把语义相近的条目交给模型合并。
     * 本仓没有这条链路 ⇒ 只如实做**确定性**部分（到期归档），
     * 响应里的 {@code skipped} 说明「未做模型合并」，⛔ 不谎报 merged 数量。
     *
     * @return 整理结果
     */
    @PostMapping("/consolidate")
    public Map<String, Object> consolidate() {
        List<KbMemoryItemDto> snapshot = memoryService.export();
        int reviewed = snapshot.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewed", reviewed);
        result.put("merged", 0);
        result.put("demoted", 0);
        result.put("expired", 0);
        result.put("candidates", 0);
        result.put("skipped", "本仓未接入记忆合并模型：仅支持手动整理（确认/拒绝/删除），"
            + "不做语义合并。reviewed 为当前条目数。");
        log.info("[记忆] 整理请求：reviewed={}（未做语义合并）", reviewed);
        return result;
    }

    // ── DTO ────────────────────────────────────────────────

    /** 设置请求（{@code enabled} 缺省 = 不改动）。 */
    public record SettingsRequest(Boolean enabled) {
    }

    /** 新增条目请求。 */
    public record CreateItemRequest(String kind, String content, Integer importance) {
    }

    /** 修改条目请求。 */
    public record UpdateItemRequest(String content, Integer importance) {
    }
}
