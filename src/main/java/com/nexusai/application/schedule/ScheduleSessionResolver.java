package com.nexusai.application.schedule;

import com.nexusai.common.NexusaiDataRoots;
import com.nexusai.domain.project.ProjectService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.SessionMode;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 定时任务 fire 前解析「本轮用哪个会话」（V306）。
 *
 * <h2>为什么单独一个类</h2>
 * 四种形态的分流逻辑（尤其新建会话 + 回填）会污染 {@code AgentJob.fire} 的主编排链。
 * 收在这里，{@code AgentJob} 只调一行，`CronIdleExecutor` <b>零改动</b>
 * （它拿到的 `QueueItem.sessionId` 已经是本轮该用的会话）。
 *
 * <h2>四种形态</h2>
 * <table border="1">
 *   <tr><th>形态</th><th>本方法返回</th><th>副作用</th></tr>
 *   <tr><td>{@link SessionMode#REUSE}</td><td>任务绑定的会话（存活时）；否则降级为新建</td>
 *       <td>无</td></tr>
 *   <tr><td>{@link SessionMode#NEW_EACH}</td><td><b>每次新建</b>的会话 id</td><td>插入 sessions 行</td></tr>
 *   <tr><td>{@link SessionMode#NEW_ONCE}</td><td>已有的专属会话；不存在则新建</td>
 *       <td>首次新建后<b>回填</b> {@code schedules.session_id}</td></tr>
 *   <tr><td>{@link SessionMode#NONE}</td><td>{@code null}</td><td>无（不产生会话）</td></tr>
 * </table>
 *
 * <h2>降级规则（用户裁定）</h2>
 * {@code REUSE} / {@code NEW_ONCE} 绑定的会话若已被删除，**降级为 NEW_EACH**
 * （新建一个），而不是退回「无会话」—— 否则会变成「跑了完全没痕迹」，
 * 而 headless 路径不读历史、不写 transcript、事后无从查。
 *
 * <h2>为什么新建的会话落在 cron 目录</h2>
 * {@code SessionService.create} 的 {@code mainProjectId} 是硬校验，必须给一个已注册项目。
 * 让这些自动会话落到用户正在用的仓库里会让 cwd / 记忆 / 产物全部跑偏
 * （知识库弹窗 {@code projectApi.list()[0]} 踩过同款）。所以用
 * {@link NexusaiDataRoots#cronRoot()} 这条固定归属。
 *
 * <p><b>失败语义</b>：任何一步失败（目录建不出来 / 项目注册失败 / 建会话失败）都返回
 * {@code null} 并 ERROR 留痕 —— <b>不阻断 fire</b>。任务照常执行（headless），
 * 只是没有 transcript；运行记录会记下 {@code sessionId=null} 供排查。
 */
@Service
public class ScheduleSessionResolver {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSessionResolver.class);

    /** 会话标题时间格式（左栏能一眼看出是哪次执行）。 */
    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 自动会话的标题前缀 —— 左栏 cron 分组里可辨识。 */
    private static final String AUTO_TITLE_PREFIX = "cron · ";

    @Autowired(required = false) private SessionService sessionService;
    @Autowired(required = false) private ProjectService projectService;
    @Autowired(required = false) private SessionMapper sessionMapper;
    @Autowired(required = false) private ScheduleMapper scheduleMapper;
    /** [V330] 新会话执行：绑定选中聊天 + 写 Brief/无人值守。 */
    @Autowired(required = false) private com.nexusai.domain.im_channel.ImChatService imChatService;
    @Autowired(required = false) private com.nexusai.domain.session.SessionModeService sessionModeService;

    /**
     * 解析本轮该用的会话 id。
     *
     * @param dto     任务 DTO（含 sessionMode / sessionId / boundProject / name）
     * @param trigger 触发来源（仅用于日志）
     * @return 本轮会话 id；{@code null} = 本轮不产生会话（D 形态，或新建失败降级）
     */
    public String resolve(ScheduleDto dto, String trigger) {
        if (dto == null) return null;
        SessionMode mode = dto.sessionMode() == null ? SessionMode.DEFAULT : dto.sessionMode();

        switch (mode) {
            case NONE -> {
                log.info("[ScheduleSession] 形态 NONE（不绑会话）⇒ 本轮 headless trigger={} id={}",
                    trigger, dto.id());
                return null;
            }
            case REUSE -> {
                String bound = dto.sessionId();
                if (bound != null && !bound.isBlank() && isAlive(bound)) {
                    return bound;
                }
                // 绑定的会话没了 ⇒ 降级新建（不是退回无痕 headless）
                log.info("[ScheduleSession] 形态 REUSE 但绑定会话不存在（session={}）⇒ 降级新建 "
                    + "trigger={} id={}", bound, trigger, dto.id());
                return createAutoSession(dto, trigger, /* backfillToSchedule */ false, mode);
            }
            case NEW_EACH -> {
                return createAutoSession(dto, trigger, /* backfillToSchedule */ false, mode);
            }
            case NEW_ONCE -> {
                String pinned = dto.sessionId();
                if (pinned != null && !pinned.isBlank() && isAlive(pinned)) {
                    return pinned;   // 专属会话已建立且存活 ⇒ 一直用它
                }
                log.info("[ScheduleSession] 形态 NEW_ONCE：专属会话不存在（session={}）⇒ 首次新建 "
                    + "trigger={} id={}", pinned, trigger, dto.id());
                return createAutoSession(dto, trigger, /* backfillToSchedule */ true, mode);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 新建一个自动会话（落 cron 目录），可选回填到任务行（C 形态）。
     *
     * @param backfill true = 把新会话 id 写回 {@code schedules.session_id}（C 形态首次触发）
     * @return 新会话 id；任一步失败 → {@code null}（不阻断 fire）
     */
    private String createAutoSession(ScheduleDto dto, String trigger, boolean backfill, SessionMode mode) {
        if (sessionService == null || projectService == null) {
            log.error("[ScheduleSession] SessionService/ProjectService 未注入 ⇒ 无法新建会话，"
                + "本轮 headless（id={} mode={}）", dto.id(), mode);
            return null;
        }
        try {
            // 1) 项目锚 = cron 目录（不存在则现建；建不出来抛 IllegalStateException）
            String cronRoot = NexusaiDataRoots.cronRoot().toString();

            // 2) 目录 → project id。ProjectService.register 按 path 幂等（同路径复用），
            //    所以每次 fire 调都不会产生重复项目。
            ProjectDto project = projectService.register(
                new ProjectCreateRequest("cron", cronRoot));

            // 3) 建会话。title 带任务名 + 时间 ⇒ 左栏可辨识是哪次执行。
            String title = AUTO_TITLE_PREFIX + dto.name() + " · "
                + OffsetDateTime.now().format(TITLE_TIME);
            SessionDto created = sessionService.create(new SessionCreateRequest(
                title,
                null,           // model tag 缺省
                null,           // modelName：null ⇒ 读时按 settings 解析（会话 override 留空）
                project.id(),   // mainProjectId（硬校验）
                null));         // bareMode：不设，回落全局

            log.info("[ScheduleSession] 已新建自动会话 session={} project={} cronRoot={} "
                    + "mode={} trigger={} schedule={}",
                created.id(), project.id(), cronRoot, mode, trigger, dto.id());

            // 4) V317：把任务指定的「专家」写进新会话行（sessions.main_thread_agent）
            //    —— 读侧 LlmAgentLoop.buildEffectivePromptOptions 据此产出 mainThreadAgentDefinition
            //    并替换系统提示词。⛔ 不做「轮次覆盖」（RunRequest.withMainThreadAgent）：
            //    新会话没有用户既有设置可被污染，直接写列即可。
            applyMainThreadAgent(created.id(), dto.mainThreadAgent());

            // 4.5) [V330 · 第3类] 新会话执行：绑定选中聊天 + 把 Brief/无人值守写在新会话自身。
            //      ⛔ 不要求渠道的形态（REUSE 降级新建）跳过，保持既有行为。
            if (mode == SessionMode.NEW_EACH || mode == SessionMode.NEW_ONCE) {
                if (!bindChannelsAndApplyMode(dto, created.id())) {
                    cleanupNewSession(created.id());
                    log.error("[ScheduleSession] 新会话执行前置失败 ⇒ 删除本次新建会话，"
                        + "本轮不执行 schedule={} session={}", dto.id(), created.id());
                    return null;
                }
            }

            // 5) C 形态：把专属会话 id 回填到任务行（之后每次 fire 都用它）
            if (backfill && scheduleMapper != null) {
                // ⚠️ 必须用 updateByQuery + ignoreNulls=true：BaseMapper.update(entity) 会把
                //    patch 里没设的字段写成 null（把整行清空）。这里只打算改 session_id 一列。
                ScheduleRecord patch = new ScheduleRecord();
                patch.setSessionId(created.id());
                scheduleMapper.updateByQuery(patch, true,
                    com.mybatisflex.core.query.QueryWrapper.create().eq("id", dto.id()));
                log.info("[ScheduleSession] NEW_ONCE 专属会话已回填 schedule={} session={}",
                    dto.id(), created.id());
            }
            return created.id();
        } catch (Exception e) {
            // 不阻断 fire：任务照常执行（headless），只是没有 transcript。
            // 运行记录里 sessionId 会是 null，配合本条 ERROR 可定位。
            log.error("[ScheduleSession] 新建自动会话失败 ⇒ 本轮 headless（id={} mode={}）: {}",
                dto.id(), mode, e.toString(), e);
            return null;
        }
    }

    /**
     * [V330 · 第3类] 把任务选中的聊天接到新会话，并把 Brief/无人值守写进新会话自身。
     *
     * <p>失败即整体失败：已绑定的聊天逐个解绑，调用方随后删除本次新建会话并放弃本轮执行
     * （⛔ 不允许留下「无人值守但无渠道」的壳会话，也不静默继续跑）。
     *
     * @param dto       任务 DTO（notifyChats = im_chat.id）
     * @param sessionId 本次新建的会话 id
     * @return true = 全部绑定成功且模式已落库
     */
    private boolean bindChannelsAndApplyMode(ScheduleDto dto, String sessionId) {
        if (imChatService == null || sessionModeService == null) {
            log.error("[ScheduleSession] ImChatService/SessionModeService 未注入 ⇒ 无法绑定渠道"
                + "（schedule={}）", dto.id());
            return false;
        }
        List<String> chatIds = dto.notifyChats();
        if (chatIds == null || chatIds.isEmpty()) {
            log.error("[ScheduleSession] 新会话执行缺少渠道（schedule={}）⇒ 放弃本轮", dto.id());
            return false;
        }
        List<String> bound = new java.util.ArrayList<>();
        try {
            for (String chatId : chatIds) {
                if (chatId == null || chatId.isBlank()) {
                    continue;
                }
                imChatService.bind(chatId, sessionId, "auto");
                bound.add(chatId);
            }
            // 开无人值守 = Brief on + IM 组 on + Ask 剔除（写在新会话自身，不继承来源会话）
            sessionModeService.setUnattended(sessionId, true);
            return true;
        } catch (RuntimeException e) {
            log.error("[ScheduleSession] 绑定渠道/模式失败（回滚本次绑定）session={} chats={} err={}",
                sessionId, bound, e.toString());
            for (String chatId : bound) {
                try {
                    imChatService.unbind(chatId);
                } catch (RuntimeException rollbackError) {
                    log.warn("[ScheduleSession] 回滚解绑失败 chat={} err={}",
                        chatId, rollbackError.toString());
                }
            }
            return false;
        }
    }

    /** 前置失败时删除本次刚建会话（无消息）；删除失败只记录，不掩盖失败终态。 */
    private void cleanupNewSession(String sessionId) {
        if (sessionService == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sessionService.delete(sessionId);
            log.info("[ScheduleSession] 已删除前置失败的新建会话 session={}", sessionId);
        } catch (RuntimeException e) {
            log.error("[ScheduleSession] 删除前置失败的新建会话失败 session={} err={}",
                sessionId, e.toString());
        }
    }

    /**
     * V317 · 把「专家」（主线程 agent）写进新建会话行（{@code sessions.main_thread_agent}）。
     *
     * <p><b>为什么写会话列</b>：读侧（{@code LlmAgentLoop.buildEffectivePromptOptions}）只认会话列
     * ⇒ 这是唯一能让整轮对话由指定专家驱动的落点。⛔ 不做轮次覆盖（用户已否）。
     *
     * <p><b>为什么用 {@code updateByQuery(patch, true, …)} 而不是 {@code update(entity)}</b>：
     * {@code update(entity)} 是整行写（未设字段会被写成 null，把整行清空）；
     * {@code updateByQuery(patch, ignoreNulls=true, …)} 只写 patch 里非 null 的列。
     *
     * <p>空/空白 = 不指定 ⇒ **跳过写**（会话行保持 null = 默认人格）。
     */
    private void applyMainThreadAgent(String sessionId, String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return;   // 不指定 ⇒ 不写（保持 null）
        }
        if (sessionMapper == null) {
            log.warn("[ScheduleSession] SessionMapper 未注入 ⇒ 专家 {} 未写入会话 {}（本轮回落默认人格）",
                agentType, sessionId);
            return;
        }
        String value = agentType.trim();
        SessionRecord patch = new SessionRecord();
        patch.setMainThreadAgent(value);
        sessionMapper.updateByQuery(patch, true,
            com.mybatisflex.core.query.QueryWrapper.create().eq("id", sessionId));
        log.info("[ScheduleSession] 专家已写入会话 session={} mainThreadAgent={}", sessionId, value);
    }

    /**
     * 会话是否仍然存在（DB 行存在性）—— 「已关」的唯一判据。
     *
     * <p>⛔ 不是「进程没在跑」（{@code RUNNING_SESSIONS} 只表在飞的 agent_loop，闲置会话仍算存活），
     * 也不是「切走了 / 重启过」。唯一让本方法返回 false 的是用户在左栏删掉了该会话
     * （{@code SessionService.delete} 删行）。
     */
    private boolean isAlive(String sessionId) {
        if (sessionMapper == null) {
            // 未注入（非 Spring 夹具）→ fail-open 视为存活，不改变既有测试行为
            return true;
        }
        SessionRecord r = sessionMapper.selectOneById(sessionId);
        return r != null;
    }

    /** 供测试断言用：当前解析器是否具备建会话能力。 */
    boolean canCreateSessions() {
        return sessionService != null && projectService != null;
    }

    /** 列出本轮通知目标（V306）· 供 AgentJob 透传（解析失败按空处理）。 */
    public List<String> notifyChatsOf(ScheduleDto dto) {
        return dto == null || dto.notifyChats() == null ? List.of() : dto.notifyChats();
    }
}
