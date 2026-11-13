package com.nexusai.kb.app.workspace;

import com.nexusai.common.NexusaiDataRoots;
import com.nexusai.domain.project.ProjectService;
import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * 知识库「新对话」工作区项目 · 把 {@link NexusaiDataRoots#chatRoot()} 注册成一条项目。
 *
 * <h2>为什么不新建表</h2>
 * 项目本来就是「一条目录」（{@code ProjectService.register} 以 path 判重，V69 注释
 * 「对齐 CC 项目=目录」）。工作区只需把那条目录注册进 {@code projects} 表，会话就能拿它的
 * id 当 {@code mainProjectId}。再立一张「kb 工作区」表等于把同一份事实存两处。
 *
 * <h2>为什么目录是 {@code <数据根>/chat}</h2>
 * 数据根 = {@code <安装目录>/data/workspace/<用户>}（configHome 兜底，见
 * {@link NexusaiDataRoots}）。改造前两处面板取 {@code projectApi.list()[0]} ——
 * 项目列表无 ORDER BY、按 rowid 返回 ⇒ 会话被记到用户自己的仓库名下，cwd / 记忆 / 产物
 * 全部落到仓库里。固定到数据根下的 {@code chat} 子目录即可与用户项目隔离。
 *
 * <h2>幂等</h2>
 * 完全依赖 {@code ProjectService.register} 已有的 path 幂等（同 path ⇒ 复用既有行不重复
 * insert）。本类不另做缓存：目录名固定，重复调用只是一次 {@code SELECT ... WHERE path=?}。
 */
@Service
public class KbWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(KbWorkspaceService.class);

    /**
     * 工作区项目的显示名。
     *
     * <p>左栏会按项目分组展示，用户在会话列表里看到的就是这个名字。
     * 固定常量而非取目录名（目录名是 {@code chat}，单独看没有含义）。
     */
    static final String PROJECT_NAME = "知识库对话";

    private final ProjectService projectService;

    public KbWorkspaceService(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 解析工作区目录并注册/复用对应项目。
     *
     * @return 工作区项目（每次调用都重新解析目录 —— 目录可能随安装位置变化，
     *         而 {@code register} 的 path 幂等保证不会重复建行）
     * @throws IllegalStateException 目录创建失败
     */
    public ProjectDto resolveProject() {
        Path dir = NexusaiDataRoots.chatRoot();
        String path = dir.toString().replace('\\', '/');
        ProjectDto project = projectService.register(new ProjectCreateRequest(PROJECT_NAME, path));
        log.info("[KB工作区] 项目就绪 id={} name={} path={}（数据根={}）",
            project.id(), project.name(), project.path(), NexusaiDataRoots.dataRoot());
        return project;
    }
}
