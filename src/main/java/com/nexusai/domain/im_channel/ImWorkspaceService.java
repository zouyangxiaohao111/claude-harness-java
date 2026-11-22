package com.nexusai.domain.im_channel;

import com.nexusai.common.NexusaiDataRoots;
import com.nexusai.domain.project.ProjectService;
import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * IM 频道工作区项目 · 把 {@link NexusaiDataRoots#imRoot()} 注册成一条项目。
 *
 * <h2>为什么需要它</h2>
 * 扫描审批通过后要<b>新建一个任务</b>（用户裁定 Q2=A），而
 * {@code SessionService.create} 的 {@code mainProjectId} 是硬校验。这个 id 不能猜：
 * 猜错会让 IM 任务落到用户自己的仓库里，cwd / 记忆 / 产物全部跑偏。
 * 给 IM 一个固定归属即可隔离。
 *
 * <h2>为什么照抄 KbWorkspaceService 而不新立表</h2>
 * 本仓项目 = 一条目录（{@code ProjectService.register} 以 path 判重）。工作区只需把
 * 目录注册进 {@code projects}，会话拿它的 id 当 {@code mainProjectId} 即可；
 * 再立一张「IM 工作区」表等于把同一份事实存两处。
 *
 * <h2>目录形态</h2>
 * {@code <安装目录>/data/workspace/<NEXUSAI_USER 或 default>/im}
 * （{@link NexusaiDataRoots} 已处理「安装目录优先、configHome 兜底」与不可写降级）。
 *
 * <h2>幂等</h2>
 * 完全依赖 {@code ProjectService.register} 的 path 幂等（同 path ⇒ 复用既有行）。
 * 本类不另做缓存：目录名固定，重复调用只是一次 {@code SELECT ... WHERE path=?}。
 */
@Service
public class ImWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(ImWorkspaceService.class);

    /**
     * 工作区项目的显示名。
     *
     * <p>左栏按项目分组展示，用户在会话列表里看到的就是这个名字。
     * 固定常量而非取目录名（目录名是 {@code im}，单独看没有含义）。
     */
    public static final String PROJECT_NAME = "IM 频道";

    private final ProjectService projectService;

    public ImWorkspaceService(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 解析工作区目录并注册/复用对应项目。
     *
     * @return 工作区项目（每次调用都重新解析目录 —— 安装位置可能变化，
     *         而 {@code register} 的 path 幂等保证不会重复建行）
     * @throws IllegalStateException 目录创建失败
     */
    public ProjectDto resolveProject() {
        Path dir = NexusaiDataRoots.imRoot();
        String path = dir.toString().replace('\\', '/');
        ProjectDto project = projectService.register(new ProjectCreateRequest(PROJECT_NAME, path));
        log.info("[IM工作区] 项目就绪 id={} name={} path={}（数据根={}）",
            project.id(), project.name(), project.path(), NexusaiDataRoots.dataRoot());
        return project;
    }
}
