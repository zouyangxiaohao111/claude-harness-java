package com.nexusai.apis.provider;

import com.nexusai.model.provider.dto.*;
import com.nexusai.domain.provider.ModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Model REST 端点（嵌套在 Provider 下） */
@RestController
@RequestMapping("/api/v1/providers/{providerId}/models")
public class ModelController {

    @Autowired private ModelService modelService;

    @GetMapping
    public List<ModelDto> list(@PathVariable String providerId) { return modelService.listByProvider(providerId); }

    @GetMapping("/{id}")
    public ModelDto get(@PathVariable String providerId, @PathVariable String id) { return modelService.getById(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelDto create(@PathVariable String providerId, @RequestBody ModelCreateRequest req) {
        return modelService.create(providerId, req);
    }

    @PatchMapping("/{id}")
    public ModelDto update(@PathVariable String providerId, @PathVariable String id, @RequestBody ModelUpdateRequest req) {
        return modelService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String providerId, @PathVariable String id) { modelService.delete(id); }
}
