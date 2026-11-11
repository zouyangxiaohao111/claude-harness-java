package com.nexusai.kb.api;

import com.nexusai.kb.app.workspace.KbWorkspaceService;
import com.nexusai.model.project.dto.ProjectDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库工作区端点 · 给助手 / 问答面板提供「该挂哪条项目」的答案。
 *
 * <h2>为什么面板需要一个端点，而不是自己挑项目</h2>
 * 改造前 {@code KbAssistantPanel} / {@code KbQaPanel} 都取 {@code projectApi.list()[0]} ——
 * 而项目列表按 SQLite rowid 返回（{@code ProjectService.listAll} 无 ORDER BY），
 * 结果随手挂在用户第一个注册的仓库下。选哪条目录是<b>服务端事实</b>（取决于安装位置
 * 与配置根），不该由浏览器猜。
 *
 * <h2>路径选择</h2>
 * {@code /api/v1/kb/workspace/project}：与 {@code KbQaConfigController} 的
 * {@code /api/v1/kb/config} 同族。⛔ 不用 {@code /api/v1/kb/} 裸路径 —— 那里已被
 * {@code KbChatGatewayController} 的 {@code /kb/chat/{sid}} 占用，混在一起会让路由意图含糊。
 *
 * <p>⚠️ 本端点<b>有副作用</b>（可能创建目录 + 插入 projects 行）。用 GET 的前提是副作用
 * <b>幂等</b>：目录重复创建无变化，项目按 path 判重复用。若将来给它加上非幂等行为
 * （比如按调用次数换目录），必须改成 POST。
 */
@RestController
@RequestMapping("/api/v1/kb/workspace")
public class KbWorkspaceController {

    private final KbWorkspaceService workspaceService;

    public KbWorkspaceController(KbWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 取知识库工作区项目（不存在则按目录注册）。
     *
     * @return 工作区项目（含会话创建所需的 {@code id}）
     */
    @GetMapping("/project")
    public ProjectDto project() {
        return workspaceService.resolveProject();
    }
}
