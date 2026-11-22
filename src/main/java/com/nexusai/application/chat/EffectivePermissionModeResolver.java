package com.nexusai.application.chat;

import com.nexusai.application.agent.permission.InitialPermissionModeResolver;
import com.nexusai.application.agent.permission.PermissionConfigProvider;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.source.InitialPermissionModeSource;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 「最终有效权限模式」统一解析链 · 发送方与接收方共用（2026-11-20 真实 E2E 缺陷 1）。
 *
 * <h2>为什么需要它</h2>
 * <p>发送方 attest 的模式来自 {@code LlmAgentLoop} 的完整链
 * （{@link InitialPermissionModeResolver}：per-call → 会话 override → settings 槽），
 * 而接收方注册表模式的旧实现是 {@code ChatService.resolveEffectivePermissionMode}
 * （= per-call ?? {@code sessions.permission_mode}，<b>不回退 settings</b>）。
 * 只配全局 {@code settings.permission_mode}=bypassPermissions 且会话列未写时：
 * 发送方 = {@code NON_PROMPTING}、接收方 = {@code DEFAULT}(PROMPTING) ⇒ 入站 auto 误判
 * {@code MODE_MISMATCH} 全部转 hold。本类让两侧读<b>同一解析结果</b>。
 *
 * <h2>优先级链（逐字对照 {@link InitialPermissionModeSource#resolveInput} 的注释）</h2>
 * <ol>
 *   <li>per-call（HTTP 请求体 {@code SendMessageRequest.permissionMode}；接收方恒无 ⇒ null）</li>
 *   <li>会话记录 {@code sessions.permission_mode}</li>
 *   <li>settings 槽：DB 全局 {@code settings.permission_mode} ?? 磁盘 settings.json defaultMode</li>
 *   <li>默认 {@code default}</li>
 * </ol>
 * 前两者先折叠成 CLI 槽（{@link ChatService#resolveEffectivePermissionMode}，与写
 * {@code RunRequest.permissionModeCli} 的是同一判据）；后两者由
 * {@link InitialPermissionModeSource} 提供。最终收敛复用
 * {@link InitialPermissionModeResolver}（含 disableBypassPermissionsMode 门），
 * 因此与主循环的初始模式解析同源，不另造第二套判据。
 *
 * <p><b>失败降级</b>：{@code InitialPermissionModeSource} 未注入（非 Spring 单测/裁剪环境）或
 * 读 settings 抛异常（会话项目根四态 fail-loud）⇒ 回落到 CLI 槽的值（无 per-call/会话列 →
 * {@code default}），绝不阻断发送。降级方向 = 更严格（PROMPTING），不会把非 bypass 判成 bypass。
 */
@Component
public class EffectivePermissionModeResolver {

    private static final Logger log = LoggerFactory.getLogger(EffectivePermissionModeResolver.class);

    /** 会话行读源（按 sessionId 解析；null = 未接线，只能按传入记录解析）。 */
    @Autowired(required = false)
    private SessionMapper sessionMapper;

    /** settings 槽真实输入源（DB 全局 ?? 磁盘；null = 未接线 ⇒ 只按 CLI 槽解析）。 */
    @Autowired(required = false)
    private InitialPermissionModeSource initialPermissionModeSource;

    /** bypassPermissions 组织门（与主循环同一 provider；null = 门关闭）。 */
    @Autowired(required = false)
    private PermissionConfigProvider permissionConfigProvider;

    /** classifier 门（只影响 auto ↔ default 折叠，两者同为 PROMPTING；null = 关）。 */
    @Autowired(required = false)
    private AutoModeGate autoModeGate;

    /** 遥测（CCR 忽略事件；null = 不发射）。 */
    @Autowired(required = false)
    private Telemetry telemetry;

    /** 测试/手动接线：会话读源。 */
    public void setSessionMapper(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /** 测试/手动接线：settings 槽输入源。 */
    public void setInitialPermissionModeSource(InitialPermissionModeSource source) {
        this.initialPermissionModeSource = source;
    }

    /** 测试/手动接线：bypass 组织门。 */
    public void setPermissionConfigProvider(PermissionConfigProvider provider) {
        this.permissionConfigProvider = provider;
    }

    /** 测试/手动接线：classifier 门。 */
    public void setAutoModeGate(AutoModeGate gate) {
        this.autoModeGate = gate;
    }

    /**
     * 按会话 id 解析最终有效权限模式（接收方入站判定 / 注册表登记用）。
     *
     * @param sessionId 会话 id（可 null/空白 ⇒ 跳过会话层，仅按 settings 链）
     * @param perCall   per-call 模式（接收方恒 null）
     * @return 最终有效模式（恒非 null；解析不能 → {@code DEFAULT}）
     */
    public PermissionMode resolve(String sessionId, String perCall) {
        SessionRecord session = null;
        SessionMapper mapper = sessionMapper;
        if (mapper != null && sessionId != null && !sessionId.isBlank()) {
            try {
                session = mapper.selectOneById(sessionId);
            } catch (RuntimeException e) {
                log.debug("EffectivePermissionModeResolver: 会话行读取失败（按无会话解析）session={} err={}",
                    sessionId, e.toString());
            }
        }
        return resolve(session, perCall);
    }

    /**
     * 按会话记录解析最终有效权限模式（发送/登记路径已持有记录时零额外 DB 读）。
     *
     * @param session 会话记录（可 null）
     * @param perCall per-call 模式（可 null）
     * @return 最终有效模式（恒非 null）
     */
    public PermissionMode resolve(SessionRecord session, String perCall) {
        String cliToken = ChatService.resolveEffectivePermissionMode(session, perCall);
        InitialPermissionModeSource source = initialPermissionModeSource;
        if (source == null) {
            return PermissionMode.fromString(cliToken);
        }
        String sessionId = session == null ? null : session.getId();
        InitialPermissionModeResolver.Input input = resolveInputSafely(source, sessionId, cliToken);
        try {
            return InitialPermissionModeResolver.resolve(input, config()).mode();
        } catch (RuntimeException e) {
            log.warn("EffectivePermissionModeResolver: 初始模式解析失败，回落 CLI 槽值 cli={} err={}",
                cliToken, e.toString());
            return PermissionMode.fromString(cliToken);
        }
    }

    /**
     * 纯函数形态（无 Spring 依赖、无注入门）· 只按「CLI 槽 + settings 槽 + disableBypass 门」收敛。
     *
     * <p><b>不另写优先级</b>：内部仍调 {@link InitialPermissionModeResolver#resolve}
     * （{@link InitialPermissionModeResolver.Config#defaults()}：Statsig/classifier 门关闭），
     * 所以「per-call → 会话 override → settings → default」与生产同一条链。唯一差异是
     * 组织级门（DB {@code permissionConfigProvider}）未注入 ⇒ 只剩输入里的 disableBypass。
     *
     * <p>只影响 auto ↔ default 折叠（两者同为 PROMPTING），不改变模式类判定。
     *
     * @param cliMode      CLI 槽值（= per-call ?? 会话 override，可 null/空白）
     * @param settingsMode settings 槽值（DB 全局 ?? 磁盘 defaultMode，可 null/空白）
     * @param disableBypass bypassPermissions 是否被禁用（true ⇒ 跳过 bypass，落下一层）
     * @return 最终模式（恒非 null）
     */
    public static PermissionMode resolvePure(String cliMode, String settingsMode, boolean disableBypass) {
        InitialPermissionModeResolver.Input input = new InitialPermissionModeResolver.Input(
            blankToNull(cliMode), false, blankToNull(settingsMode), disableBypass);
        return InitialPermissionModeResolver.resolve(input, InitialPermissionModeResolver.Config.defaults())
            .mode();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** settings 槽读取（会话项目根 fail-loud → 退回无会话腿，绝不阻断发送）。 */
    private InitialPermissionModeResolver.Input resolveInputSafely(
            InitialPermissionModeSource source, String sessionId, String cliToken) {
        try {
            return source.resolveInput(sessionId, cliToken, false);
        } catch (RuntimeException e) {
            log.warn("EffectivePermissionModeResolver: settings 槽读取失败（无会话腿重试）session={} err={}",
                sessionId, e.toString());
            try {
                return source.resolveInput(null, cliToken, false);
            } catch (RuntimeException e2) {
                return new InitialPermissionModeResolver.Input(cliToken, false, null, false);
            }
        }
    }

    /** 与 {@code LlmAgentLoop} 初始模式解析同一套门（结构化对齐，不另造判据）。 */
    private InitialPermissionModeResolver.Config config() {
        return new InitialPermissionModeResolver.Config(
            permissionConfigProvider != null ? permissionConfigProvider::isBypassPermissionsDisabled : null,
            autoModeGate != null ? autoModeGate::isEnabled : null,
            () -> com.nexusai.infra.util.AutoModeState.isAutoModeCircuitBroken(),
            false,
            telemetry);
    }
}
