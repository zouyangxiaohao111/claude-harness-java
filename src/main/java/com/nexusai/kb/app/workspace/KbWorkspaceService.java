package com.nexusai.kb.app.workspace;

import com.nexusai.domain.project.ProjectService;
import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识库工作区项目 · 把 {@link KbWorkspacePaths} 解析出的目录注册成一条项目。
 *
 * <h2>为什么不新建表</h2>
 * 项目本来就是「一条目录」（{@code ProjectService.register} 以 path 判重，V69 注释
 * 「对齐 CC 项目=目录」）。工作区只需把那条目录注册进 {@code projects} 表，会话就能拿它的
 * id 当 {@code mainProjectId}。再立一张「kb 工作区」表等于把同一份事实存两处。
 *
 * <h2>幂等</h2>
 * 完全依赖 {@code ProjectService.register} 已有的 path 幂等（同 path ⇒ 复用既有行不重复
 * insert）。本类不另做缓存：目录名固定，重复调用只是一次 `SELECT ... WHERE path=?`。
 */
@Service
public class KbWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(KbWorkspaceService.class);

    /**
     * 工作区项目的显示名。
     *
     * <p>左栏会按项目分组展示，用户在会话列表里看到的就是这个名字。
     * 固定常量而非取目录名（目录名是 {@code kb}，单独看没有含义）。
     */
    static final String PROJECT_NAME = "知识库";

    private final ProjectService projectService;

    public KbWorkspaceService(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 解析工作区目录并注册/复用对应项目。
     *
     * @return 工作区项目（每次调用都重新解析目录 —— 目录可能随安装位置变化，
     *         而 {@code register} 的 path 幂等保证不会重复建行）
     * @throws IllegalStateException 三级目录全部创建失败
     */
    public ProjectDto resolveProject() {
        KbWorkspacePaths.Resolved resolved = KbWorkspacePaths.resolve();
        String path = resolved.path().toString().replace('\\', '/');
        ProjectDto project = projectService.register(new ProjectCreateRequest(PROJECT_NAME, path));
        log.info("[KB工作区] 项目就绪 id={} name={} path={}（来源={}）",
            project.id(), project.name(), project.path(), resolved.tier());
        return project;
    }
}
