package com.nexusai.application.project;

import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.project.dto.ProjectBindRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionFileMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.model.session.dto.SessionGroup;
import com.nexusai.model.provider.dto.ModelTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 跨聚合用例（application 层）：绑定/解绑 Project 到 Session。
 *
 * <p>涉及 2 个聚合：
 * <ul>
 *   <li>Project（写 project.bound 标志）</li>
 *   <li>Session（写 session.mainProjectId）</li>
 * </ul>
 *
 * 严格 DDD 拆：domain.project.ProjectService 只管 Project 自身 CRUD，
 * domain.session.SessionService 只管 Session 自身 CRUD；这个跨聚合 join
 * 放在 application 层做。
 */
@Service
public class ProjectSessionBindingService {

    private static final Logger log = LoggerFactory.getLogger(ProjectSessionBindingService.class);

    @Autowired private SessionMapper sessionMapper;
    @Autowired private ProjectMapper projectMapper;
    // [T8/D6] 项目级 .claude → .nexusai 一次性导入器（首个绑定会话触发 · 幂等不覆盖）
    @Autowired private ClaudeToNexusaiMigrator claudeToNexusaiMigrator;

    // [IMPL-10] DEL-CCE-04: hookRegistry（CwdChanged 发射）字段已删除（伪事件发射随删除）。


    /**
     * 绑定 project 到 session：校验两边都存在，更新 session.mainProjectId + 标记 project.bound=true。
     * 返回最新的 SessionDto（controller 透传给前端）。
     */
    public SessionDto bind(String sessionId, ProjectBindRequest req) {
        String projectId = req.projectId();
        SessionRecord s = sessionMapper.selectOneById(sessionId);
        if (s == null) throw new NotFoundException("Session " + sessionId + " not found");
        ProjectRecord p = projectMapper.selectOneById(projectId);
        if (p == null) throw new NotFoundException("Project " + projectId + " not found");

        // [T8/D6] 首个绑定会话判定（bound 由 false/null → true）——触发项目级 .claude → .nexusai 一次性导入
        boolean firstBind = !Boolean.TRUE.equals(p.getBound());

        s.setMainProjectId(projectId);
        s.setUpdatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        sessionMapper.update(s);

        p.setBound(Boolean.TRUE);
        projectMapper.update(p);

        // [IMPL-10] DEL-CCE-04: CwdChanged 伪事件发射已删除 — old_cwd 取自已覆盖为 projectId
        //   的 mainProjectId，恒自等值（EV-CCE-032）；CC CwdChanged 仅真实 cwd 切换触发。

        // [IMP-B] OPD-SPR-03: 绑定成功 → 冻结会话级 projectRoot（首写胜，rebind 不覆盖已冻结值）
        SessionProjectRoot.setForSession(sessionId, p.getPath());

        // [T8/D6] 首个绑定会话 → 项目级 .claude 白名单一次性导入 .nexusai/（幂等不覆盖；@Async 不阻塞绑定响应）
        if (firstBind) {
            triggerMigrateOnce(p.getPath());
        }

        return toSessionDto(s);
    }

    /**
     * 解绑：清空 session.mainProjectId（注意 MyBatis-Flex 默认 update 会忽略 null 字段，
     * 这里显式传 false 把 null 写回 DB）。
     */
    public void unbind(String sessionId) {
        SessionRecord s = sessionMapper.selectOneById(sessionId);
        if (s == null) throw new NotFoundException("Session " + sessionId + " not found");
        s.setMainProjectId(null);
        s.setUpdatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        sessionMapper.update(s, false);

        // [IMP-B] OPD-SPR-03: 解绑成功 → 解除会话冻结（unbind 清空后可再绑定）
        SessionProjectRoot.clearSession(sessionId);
    }

    // ============== helpers ==============

    /**
     * [T8/D6] 触发项目级 .claude → .nexusai 一次性导入（首个绑定会话 · 幂等）。
     * <p>路径无效 / Path 解析异常 / 导入内部异常一律不抛（@Async 内部已 catch），
     * 保证绑定响应不被迁移流程破坏。
     *
     * @param projectPath 绑定项目根路径串（ProjectRecord.path）
     */
    private void triggerMigrateOnce(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("[ClaudeToNexusaiMigrator] 绑定项目 path 为空，跳过 .claude → .nexusai 一次性导入");
            return;
        }
        try {
            claudeToNexusaiMigrator.migrateOnce(Path.of(projectPath));
        } catch (Exception e) {
            // 防御：Path.of 非法路径等在调用线程抛（@Async 参数在调用方线程求值）——不阻断绑定响应
            log.warn("[ClaudeToNexusaiMigrator] 触发一次性导入失败（不阻断绑定）: path={}", projectPath, e);
        }
    }

    /**
     * Session → SessionDto 转换。
     * <p>本转换逻辑本来在 ProjectController 临时用，但因为 service 拆后 controller
     * 不再直接引 SessionMapper，所以搬到 application service 里。
     * 未来若 domain.session 有 SessionDtoConverter 可改为调用它。
     */
    private static SessionDto toSessionDto(SessionRecord s) {
        return new SessionDto(
            s.getId(),
            s.getModelTag() != null ? ModelTag.valueOf(s.getModelTag()) : null,
            s.getModelName(),
            s.getTitle(),
            s.getTime(),
            s.getSessionGroup() != null ? SessionGroup.valueOf(s.getSessionGroup()) : null,
            s.getTabId(),
            s.getMainProjectId(),
            s.getMessageCount(),
            parseDateTime(s.getCreatedAt()),
            parseDateTime(s.getUpdatedAt()),
            s.getEffortLevel(),
            // [V32] ultracode 会话级开关透出（0/1 → Boolean）
            s.getUltracodeEnabled() != null ? s.getUltracodeEnabled() != 0 : null,
            // [V33] bare 会话级开关透出（0/1 → Boolean）
            s.getBareMode() != null ? s.getBareMode() != 0 : null,
            // [P2] team_context 列解析态透出（复用 SessionService.parseTeamContext 单点解析）
            SessionService.parseTeamContext(s.getTeamContext()),
            // [R3] todos 列解析态透出（复用 SessionService.parseTodos 单点解析）
            SessionService.parseTodos(s.getTodos()),
            // [V44] 会话级权限模式覆盖透出（V44 列 permission_mode；null = 未覆盖 → 回落全局）
            s.getPermissionMode(),
            // [V48] 会话累计花费 + token 汇总（前端 footer 展示 · 复用 SessionService 汇总）
            s.getTotalCostYuan(),
            SessionService.sumTokensFromModelUsage(s.getModelUsageJson()),
            // [SP-03] 会话指定主线程 agent 透出（V58 列 main_thread_agent；null = 未指定，agent 分支休眠）
            s.getMainThreadAgent()
        );
    }

    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return OffsetDateTime.parse(s); } catch (Exception e) { return null; }
    }
}
