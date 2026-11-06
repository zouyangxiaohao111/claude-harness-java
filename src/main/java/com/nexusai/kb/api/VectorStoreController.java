package com.nexusai.kb.api;

import com.nexusai.kb.app.config.KbVectorStoreService;
import com.nexusai.kb.app.config.KbVectorStoreTypes;
import com.nexusai.kb.dto.config.VectorStoreDto;
import com.nexusai.kb.dto.config.VectorStoreRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 向量库实例端点（批次 W8）· 8 个 · 对齐源侧 {@code /vector-stores}。
 *
 * <p><b>⭐ 路由顺序陷阱</b>：{@code /types} 与 {@code /test} 是<b>字面量</b>路径，
 * {@code /{id}} 是<b>变量</b>路径。Spring 的匹配规则是「更具体的优先」，
 * 所以 {@code GET /vector-stores/types} 不会被 {@code /{id}} 抢走；
 * 但为了不依赖这条隐式规则，这里把字面量端点声明在变量端点<b>之前</b>。
 * （源侧 Gin 也有同类问题，它的做法是在路由注册顺序上保证。）
 */
@RestController
@RequestMapping("/api/v1/vector-stores")
public class VectorStoreController {

    @Autowired private KbVectorStoreService service;

    /** 引擎类型元数据（前端下拉框 + 动态表单）。 */
    @GetMapping("/types")
    public Map<String, Object> types() {
        return Map.of("success", true, "data", KbVectorStoreTypes.all());
    }

    /** 连通性测试（新建场景：用传入配置，不落库）。 */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody VectorStoreRequest req) {
        KbVectorStoreService.TestResult r = service.test(req.engineType(), req.connectionConfig());
        return toTestResponse(r);
    }

    @GetMapping
    public List<VectorStoreDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VectorStoreDto create(@RequestBody VectorStoreRequest req) {
        return service.create(
            req.name(), req.engineType(), req.connectionConfig(), req.indexConfig());
    }

    @GetMapping("/{id}")
    public VectorStoreDto get(@PathVariable String id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public VectorStoreDto update(@PathVariable String id, @RequestBody VectorStoreRequest req) {
        return service.update(
            id, req.name(), req.engineType(), req.connectionConfig(), req.indexConfig());
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

    /**
     * 测试结果包装成源侧形状。
     *
     * <p>⚠️ 连不上时 HTTP 仍是 200 —— 「测试不通过」是<b>业务结果</b>不是<b>接口错误</b>。
     * 用 4xx 会让前端把「用户还没填对」和「服务器挂了」混为一谈。
     * ⇒ 判据放在 body 的 {@code reachable} 上。
     */
    private static Map<String, Object> toTestResponse(KbVectorStoreService.TestResult r) {
        return Map.of(
            "success", true,
            "data", Map.of(
                "engine_type", r.engineType(),
                "reachable", r.reachable(),
                "message", r.message(),
                "server_version", r.serverVersion() == null ? "" : r.serverVersion()));
    }
}
