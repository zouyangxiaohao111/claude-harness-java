package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImConfigService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImConfigEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 安全与权限配置（设计稿「IM 安全与权限」抽屉）。
 *
 * <p>单行整体读写：GET 拿全部开关，PUT 整体覆盖。
 */
@RestController
@RequestMapping("/api/v1/im/config")
public class ImConfigController {

    @Autowired private ImConfigService configService;

    /** 请求体：字段缺省 = false（前端总是全量提交全部开关）。 */
    public record ConfigRequest(
        Boolean readAll,
        Boolean autoBind,
        Boolean aiDirect,
        Boolean existingOnly,
        Boolean takeoverConfirm,
        Boolean audit,
        Boolean allowUserIdentity
    ) {}

    @GetMapping
    public ImConfigEntry get() {
        return configService.get();
    }

    @PutMapping
    public ImConfigEntry update(@RequestBody ConfigRequest req) {
        if (req == null) throw new ValidationException("请求体不能为空");
        return configService.update(new ImConfigEntry(
            Boolean.TRUE.equals(req.readAll()),
            Boolean.TRUE.equals(req.autoBind()),
            Boolean.TRUE.equals(req.aiDirect()),
            Boolean.TRUE.equals(req.existingOnly()),
            Boolean.TRUE.equals(req.takeoverConfirm()),
            Boolean.TRUE.equals(req.audit()),
            // 缺省 = false：整体覆盖语义下"没传"等于"关掉"（与其他 6 个开关一致）
            Boolean.TRUE.equals(req.allowUserIdentity()),
            null
        ));
    }
}
