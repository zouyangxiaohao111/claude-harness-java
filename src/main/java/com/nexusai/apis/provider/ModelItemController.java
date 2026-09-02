package com.nexusai.apis.provider;

import com.nexusai.model.provider.dto.*;
import com.nexusai.domain.provider.ModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Model 独立端点（/api/v1/models/{id}）· 对齐前端既有契约
 * （前端 updateModel/removeModel 用 /models/{id}，不嵌套 provider）。
 * 复用 {@link ModelService} 独立 getById/update/delete（不校验 provider 存在）。
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelItemController {

    @Autowired private ModelService modelService;

    @GetMapping("/{id}")
    public ModelDto get(@PathVariable String id) { return modelService.getById(id); }

    @PatchMapping("/{id}")
    public ModelDto update(@PathVariable String id, @RequestBody ModelUpdateRequest req) {
        return modelService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { modelService.delete(id); }
}
