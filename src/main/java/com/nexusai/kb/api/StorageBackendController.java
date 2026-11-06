package com.nexusai.kb.api;

import com.nexusai.kb.app.config.KbStorageBackendService;
import com.nexusai.kb.app.config.KbStorageBackendTypes;
import com.nexusai.kb.dto.config.StorageBackendDto;
import com.nexusai.kb.dto.config.StorageBackendRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 存储后端端点（批次 W8）· 9 个 · 对齐源侧 {@code /storage-backends}。
 *
 * <p>⚠️ 字面量路由（{@code /types}、{@code /test}）声明在变量路由之前，
 * 与 {@link VectorStoreController} 同款理由。
 */
@RestController
@RequestMapping("/api/v1/storage-backends")
public class StorageBackendController {

    @Autowired private KbStorageBackendService service;

    /** 存储类型元数据。 */
    @GetMapping("/types")
    public Map<String, Object> types() {
        return Map.of("success", true, "data", KbStorageBackendTypes.all());
    }

    /** 连通性测试（新建场景）。 */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody StorageBackendRequest req) {
        return toTestResponse(service.test(req.provider(), req.config()));
    }

    @GetMapping
    public List<StorageBackendDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StorageBackendDto create(@RequestBody StorageBackendRequest req) {
        return service.create(req.name(), req.provider(), req.config());
    }

    @GetMapping("/{id}")
    public StorageBackendDto get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public StorageBackendDto update(@PathVariable String id, @RequestBody StorageBackendRequest req) {
        return service.update(id, req.name(), req.provider(), req.config(), req.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** 连通性测试（已存场景）。 */
    @PostMapping("/{id}/test")
    public Map<String, Object> testById(@PathVariable String id) {
        return toTestResponse(service.testById(id));
    }

    /** 设为默认存储后端。 */
    @PutMapping("/{id}/default")
    public StorageBackendDto setDefault(@PathVariable String id) {
        return service.setDefault(id);
    }

    private static Map<String, Object> toTestResponse(KbStorageBackendService.TestResult r) {
        return Map.of(
            "success", true,
            "data", Map.of(
                "provider", r.provider(),
                "reachable", r.reachable(),
                "message", r.message(),
                "server_version", r.serverVersion() == null ? "" : r.serverVersion()));
    }
}
