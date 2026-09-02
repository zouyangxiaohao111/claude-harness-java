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
}
