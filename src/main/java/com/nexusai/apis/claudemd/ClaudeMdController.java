package com.nexusai.apis.claudemd;

import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * claude-md 记忆引擎 Web 等价 REST 载体 · 对齐 CC {@code utils/claudemd.ts}（OPD-CM5-F-09 /
 * IMP-F1-7 · FR-7 前端审批通道）。
 *
 * <p><b>CC 真源</b>：CC 无 REPL 审批 REST 通道——审批态直接落 {@code config.hasClaudeMdExternalIncludesApproved}
 * （config.ts:115，缺省 false :146），由 {@code getMemoryFiles} 消费：
 * {@code includeExternal = forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved || false}
 * （claudemd.ts:798-801）。Java 无 getCurrentProjectConfig 概念 → ClaudemdEngine 以注入式
 * Supplier 装配缝承接（ClaudemdEngine:144-154/:281-288）。<b>本控制器即生产注入点</b>（F1-7
 * 缺口「hasClaudeMdExternalIncludesApproved/WarningShown 无生产注入」闭环）——前端审批对话框
 * 接受/拒绝后 POST 本端点置位。
 *
 * <p><b>缓存失效</b>：{@code getMemoryFiles} memoize 缓存 keyed on {@code forceIncludeExternal}
 * （ClaudemdEngine:382-384，CC lodash memoize claudemd.ts:790）——审批态翻转后若不失效，
 * 主路径 {@code getMemoryFiles(false)} 仍返回旧列表（不含新批准的外部 @include）。对齐 CC
 * {@code clearMemoryFileCaches}「purely for correctness … settings sync」（claudemd.ts:1110-1122）
 * 语义：置位后调用 {@link ClaudemdEngine#clearMemoryFileCaches()} 失效（不触发 InstructionsLoaded
 * hook，纯正确性失效）。
 *
 * <p><b>失败语义</b>：请求体缺失 / {@code approved} 缺失 → 400（ValidationException，审批态
 * 二值契约，拒绝隐式缺省）；claudemd 引擎未接线 → 500 fail loud（无静默降级，对齐
 * ExtractMemoriesController.resolveMemoryStorage 同语义）。
 *
 * <p><b>鉴权说明</b>：与同级只读载体 {@code /api/v1/context/analyze} 一致，未纳入
 * {@code BearerTokenAuthFilterConfig} 白名单（无鉴权过滤）——若安全姿态要求保护，需同步登记
 * 白名单（align away-summary/dream 先例）。
 */
@RestController
@RequestMapping("/api/v1/claude-md")
public class ClaudeMdController {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdController.class);

    /** claude-md 记忆引擎 · @Bean 自动装配（ToolRegistrationConfig.claudemdEngine），
     *  required=false 容错单测反射注入；null → fail loud（resolveEngine）。 */
    @Autowired(required = false)
    private ClaudemdEngine claudemdEngine;

    /** 外部 include 审批态 · CC original: {@code config.hasClaudeMdExternalIncludesApproved}
     *  （config.ts:115，缺省 false :146）。本控制器持有 + 以 {@code () -> this.externalIncludesApproved}
     *  Supplier 注册进引擎（ClaudemdEngine:286），生产注入点。 */
    private volatile boolean externalIncludesApproved = false;

    /** 外部 include 警告已示标志 · CC original: {@code config.hasClaudeMdExternalIncludesWarningShown}
     *  （config.ts:116，缺省 false :147）。本控制器持有 + 以 {@code () -> this.externalIncludesWarningShown}
     *  Supplier 注册进引擎（ClaudemdEngine:296）。CC Dialog onDone 批准/拒绝**均**置 true
     *  （config.ts:123-131）——拒绝后 {@code shouldShowClaudeMdExternalIncludesWarning} 返回 false（不再弹窗）。 */
    private volatile boolean externalIncludesWarningShown = false;

    /**
     * 前端审批对话框审批外部 @include · POST /api/v1/claude-md/include-approval。
     *
     * <p>流程: 校验 {@code approved} 二值 → 更新本控制器持有态 → 以 {@code () -> approved}
     * Supplier 注入引擎（CC claudemd.ts:798-801 includeExternal 门控）→
     * {@link ClaudemdEngine#clearMemoryFileCaches()} 失效 memoize 缓存（CC settings-sync 正确性
     * 失效，claudemd.ts:1110-1122）→ 回显 {@code {approved}}。
     *
     * @param request POST JSON 请求体（{@code { "approved": boolean }}，必填）
     * @return 200 回显审批态 {@code {approved: boolean}}
     */
    @PostMapping(value = "/include-approval", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IncludeApprovalResponse> includeApproval(
            @RequestBody IncludeApprovalRequest request) {
        if (request == null || request.approved() == null) {
            log.warn("[ClaudeMdController] /include-approval 拒绝：请求体/approved 缺失（审批态二值契约，"
                + "拒绝隐式缺省）→ 400");
            throw new ValidationException("approved is required (include-approval)");
        }
        boolean approved = request.approved();
        ClaudemdEngine engine = resolveEngine();
        this.externalIncludesApproved = approved;
        // CC claudemd.ts:798-801 includeExternal = forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved
        engine.setHasClaudeMdExternalIncludesApproved(() -> this.externalIncludesApproved);
        // CC Dialog onDone 批准/拒绝均置 WarningShown=true（config.ts:123-131）——拒绝后不再弹窗；
        // shouldShowClaudeMdExternalIncludesWarning 因 warningShown=true 返回 false（claudemd.ts:1423-1426）
        this.externalIncludesWarningShown = true;
        engine.setHasClaudeMdExternalIncludesWarningShown(() -> this.externalIncludesWarningShown);
        // CC claudemd.ts:1119-1122 clearMemoryFileCaches（settings sync 纯正确性失效）——
        // 否则 memoize 的 getMemoryFiles(false) 缓存不含新批准的外部 @include
        engine.clearMemoryFileCaches();
        if (log.isInfoEnabled()) {
            log.info("[ClaudeMdController] /include-approval 审批态更新: approved={}（CC config.ts:115 "
                + "hasClaudeMdExternalIncludesApproved，claudemd.ts:798-801 includeExternal 门控；"
                + "WarningShown=true 置位对齐 CC Dialog onDone config.ts:123-131；getMemoryFiles 缓存已失效）",
                approved);
        }
        return ResponseEntity.ok(new IncludeApprovalResponse(approved));
    }

    /**
     * 查询 CLAUDE.md 外部 @import 审批状态 · GET /api/v1/claude-md/include-status。
     *
     * <p>前端启动/加载上下文时主动 GET 查询（用户拍板仅 GET，不用 STOMP 推送），判断
     * 「CLAUDE.md 外部 @import 是否待审批并弹窗」。对齐 CC 启动时同步检测
     * {@code shouldShowClaudeMdExternalIncludesWarning()}（interactiveHelpers.tsx:164 →
     * ClaudeMdExternalIncludesDialog）+ {@code getExternalClaudeMdIncludes(await getMemoryFiles(true))}
     * （interactiveHelpers.tsx:165）。
     *
     * <p><b>语义</b>：
     * <ul>
     *   <li>{@code needsApproval} = 引擎 {@code shouldShowClaudeMdExternalIncludesWarning()}——
     *       未审批 && 未示警 && 有外部 include → true（claudemd.ts:1420-1430）。</li>
     *   <li>{@code files} = 引擎 {@code getExternalClaudeMdIncludes(getMemoryFiles(true))}——forceIncludeExternal=true
     *       探测外部 include（不受审批门控）；{@code getMemoryFiles(true)} 与 shouldShow 内部调用同参共享
     *       memoize 缓存，不重复 IO。</li>
     * </ul>
     *
     * <p><b>失败语义</b>：claudemd 引擎未接线 → 500 fail loud（与 include-approval 一致，无静默降级）。
     *
     * @return 200 {@link IncludeStatusResponse}
     */
    @GetMapping(value = "/include-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public IncludeStatusResponse includeStatus() {
        ClaudemdEngine engine = resolveEngine();
        // 对齐 CC shouldShowClaudeMdExternalIncludesWarning（interactiveHelpers.tsx:164 + claudemd.ts:1422-1429）
        boolean needsApproval = engine.shouldShowClaudeMdExternalIncludesWarning();
        // 对齐 CC getExternalClaudeMdIncludes(await getMemoryFiles(true))（interactiveHelpers.tsx:165）——
        // forceIncludeExternal=true 探测外部 include，不受审批门控；getMemoryFiles(true) memoize 与
        // shouldShow 内部调用同参共享缓存，不重复 IO
        List<String> files = engine.getExternalClaudeMdIncludes(engine.getMemoryFiles(true))
            .stream().map(ClaudemdEngine.ExternalClaudeMdInclude::path).toList();
        if (log.isInfoEnabled()) {
            log.info("[ClaudeMdController] GET /include-status: needsApproval={} externalIncludeFiles={}",
                needsApproval, files.size());
        }
        return new IncludeStatusResponse(needsApproval, files);
    }

    /**
     * 解析 claude-md 引擎 · 未接线 → 500（fail loud：审批态不落地 = 功能无效，无静默降级）。
     */
    private ClaudemdEngine resolveEngine() {
        ClaudemdEngine engine = claudemdEngine;
        if (engine == null) {
            log.error("[ClaudeMdController] claudemdEngine 未接线 → /include-approval / /include-status 不可用（fail loud）");
            throw new IllegalStateException(
                "claudemdEngine not wired (ClaudeMdController /include-approval|/include-status unavailable)");
        }
        return engine;
    }

    /**
     * /include-approval 请求体 · CC original: {@code config.hasClaudeMdExternalIncludesApproved}
     * （config.ts:115 布尔审批态）。前端审批对话框接受 → true，拒绝 → false。
     *
     * @param approved 外部 @include 是否获准（必填；null → 400）
     */
    public record IncludeApprovalRequest(Boolean approved) {}

    /**
     * /include-approval 响应 · 回显已落地的审批态（前端确认对话框关闭后刷新门控展示）。
     *
     * @param approved 已注册进引擎的审批态
     */
    public record IncludeApprovalResponse(boolean approved) {}

    /**
     * /include-status 响应 · 前端判断「CLAUDE.md 外部 @import 是否待审批并弹窗」。
     *
     * @param needsApproval 是否需审批（存在外部 include 且未审批且未示警；CC shouldShowClaudeMdExternalIncludesWarning）
     * @param files         外部 @import 文件绝对路径列表（CC getExternalClaudeMdIncludes，不受审批门控）
     */
    public record IncludeStatusResponse(boolean needsApproval, List<String> files) {}
}
