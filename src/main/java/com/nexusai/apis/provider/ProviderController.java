package com.nexusai.apis.provider;

import com.nexusai.model.provider.dto.*;
import com.nexusai.domain.provider.ProviderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Provider REST 端点（按 openapi.yaml 规范） */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

    @Autowired private ProviderService providerService;

    @GetMapping
    public List<ProviderDto> list() { return providerService.listAll(); }

    /**
     * 内置品牌目录 + 每个品牌是否已配置（只读 · 不建记录）。
     *
     * <p>⚠️ 必须声明在 {@code /{id}} 之前也不影响匹配：Spring 的字面量路径优先级高于路径变量，
     * {@code /builtin} 不会被 {@link #get(String)} 抢走。
     */
    @GetMapping("/builtin")
    public List<ProviderService.BuiltinStatus> builtin() { return providerService.listBuiltinStatuses(); }

    @GetMapping("/{id}")
    public ProviderDto get(@PathVariable String id) { return providerService.getById(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderDto create(@RequestBody ProviderCreateRequest req) { return providerService.create(req); }

    @PatchMapping("/{id}")
    public ProviderDto update(@PathVariable String id, @RequestBody ProviderUpdateRequest req) {
        return providerService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { providerService.delete(id); }

    @PostMapping("/{id}/test")
    public TestConnectionResponse test(@PathVariable String id) { return providerService.test(id); }

    /**
     * 拉取提供商的远端模型清单（{@code GET {baseUrl}/models}）。
     *
     * <p><b>WHY 与本地已存模型列表分开</b>：{@code GET /providers/{id}} 返回的是**库里已配置**的模型
     * （本应用的持久化结果）；本端点返回的是**提供商当前支持**的清单（外部事实，供导入用）。
     * 两者语义不同 ⇒ 路径用 {@code /models/remote} 区分，避免前端误用。
     *
     * <p><b>失败不抛异常</b>（返回 {@code ok=false} + 中文原因）：拉取是「尽力而为」的辅助动作，
     * 失败时前端要能退回手动添加，而不是弹一个 5xx。
     */
    @GetMapping("/{id}/models/remote")
    public RemoteModelsResponse remoteModels(@PathVariable String id) { return providerService.fetchRemoteModels(id); }
}
