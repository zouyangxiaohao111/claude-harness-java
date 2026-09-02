package com.nexusai.apis.session;

import com.nexusai.application.agent.permission.PermissionConfigProvider;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.domain.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Session REST 端点 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    @Autowired private SessionService sessionService;

    /**
     * [IMP-1 R4] bypassPermissions 数据库开关登录重读 · 对齐 CC {@code /login} 后
     * {@code resetBypassPermissionsCheck()}（bypassPermissionsKillswitch.ts:53）。
     * 方案 A：数据库存开关，启动读一次 + 登录重读。会话创建 = 新会话/新账号边界，
     * 在此 refresh() 使 org 门（{@code tengu_disable_bypass_permissions_mode}）随新账号生效。
     * {@code @Autowired(required=false)}：非 Spring 单测无 bean → null → 跳过。
     */
    @Autowired(required = false)
    private PermissionConfigProvider permissionConfigProvider;

    /**
     * [WF-8 · DEL-AM-05] bypassPermissions killswitch · 对齐 CC
     * {@code bypassPermissionsKillswitch.ts:53 resetBypassPermissionsCheck()}（/login 后复位
     * run-once 旗标，使门检随新 org 重新执行）。
     * {@code @Autowired(required=false)}：非 Spring 单测无 bean → null → 跳过。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.BypassPermissionsKillswitch bypassPermissionsKillswitch;

    @GetMapping
    public List<SessionDto> list() {
        return sessionService.list();
    }

    @GetMapping("/{id}")
    public SessionDto get(@PathVariable String id) {
        return sessionService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto create(@RequestBody SessionCreateRequest req) {
        // [IMP-1 R4] 会话创建（新账号/新会话边界）→ 重读 bypassPermissions 开关（对齐 CC /login 后
        // resetBypassPermissionsCheck 语义）。provider 未注入（单测）→ 跳过。
        if (permissionConfigProvider != null) {
            permissionConfigProvider.refresh();
        }
        // [WF-8 · DEL-AM-05] /login（会话创建边界）后复位 killswitch run-once 旗标 · 对齐 CC
        // bypassPermissionsKillswitch.ts:53 resetBypassPermissionsCheck()（login.tsx:43-50），
        // 使 LlmAgentLoop 的 checkAndDisableBypassPermissionsIfNeeded 随新 org 重新执行门检。
        // 与上方 permissionConfigProvider.refresh()（重读 DB org 门值）互补：二者缺一不可——
        // 仅复位旗标 → 门检用陈旧门值；仅重读门值 → run-once 旗标仍阻断重检。
        // （原 IMP-MV2-40 登记"机制不同待修"的 △-3 已由本次合入的 permissions_v3 补齐。）
        if (bypassPermissionsKillswitch != null) {
            bypassPermissionsKillswitch.resetBypassPermissionsCheck();
        }
        return sessionService.create(req);
    }

    @PatchMapping("/{id}")
    public SessionDto update(@PathVariable String id,
                             @RequestBody SessionUpdateRequest req) {
        return sessionService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        sessionService.delete(id);
    }
}
