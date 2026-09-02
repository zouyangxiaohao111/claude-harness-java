package com.nexusai.apis.project;

import com.nexusai.application.project.ProjectSessionBindingService;
import com.nexusai.domain.project.ProjectService;
import com.nexusai.model.project.dto.FileContentDto;
import com.nexusai.model.project.dto.FileNodeDto;
import com.nexusai.model.project.dto.ProjectBindRequest;
import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import com.nexusai.model.session.dto.SessionDto;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Project REST 端点 — 按 DDD 拆：单聚合 CRUD 走 ProjectService，跨 project+session 走 ProjectSessionBindingService。 */
@RestController
public class ProjectController {

    @Autowired private ProjectService projectService;
    @Autowired private ProjectSessionBindingService projectSessionBindingService;

    // ============== /api/v1/projects (单聚合) ==============

    @GetMapping("/api/v1/projects")
    public List<ProjectDto> list() { return projectService.listAll(); }

    @GetMapping("/api/v1/projects/{id}")
    public ProjectDto get(@PathVariable String id) { return projectService.getById(id); }

    @PostMapping("/api/v1/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDto register(@Valid @RequestBody ProjectCreateRequest req) {
        return projectService.register(req);
    }

    @DeleteMapping("/api/v1/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id) { projectService.remove(id); }

    /** 项目文件树（IDE 项目结构视图 · git ls-files 组装）。 */
    @GetMapping("/api/v1/projects/{id}/files")
    public List<FileNodeDto> files(@PathVariable String id) { return projectService.listFiles(id); }

    /** 项目文件内容（前端文件树点击查看真实内容 · 防路径穿越）。 */
    @GetMapping("/api/v1/projects/{id}/file")
    public FileContentDto file(@PathVariable String id, @RequestParam String path) {
        return projectService.readFile(id, path);
    }

    /** 项目文件写入（Monaco 编辑保存 · body 复用 FileContentDto.content · 防路径穿越同 readFile）。 */
    @PutMapping("/api/v1/projects/{id}/file")
    public FileContentDto write(@PathVariable String id, @RequestParam String path,
                                @RequestBody FileContentDto req) {
        return projectService.writeFile(id, path, req.content());
    }

    // ============== /api/v1/sessions/{sessionId}/project (跨聚合) ==============

    @PutMapping("/api/v1/sessions/{sessionId}/project")
    public SessionDto bind(@PathVariable String sessionId,
                           @Valid @RequestBody ProjectBindRequest req) {
        return projectSessionBindingService.bind(sessionId, req);
    }

    @DeleteMapping("/api/v1/sessions/{sessionId}/project")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable String sessionId) {
        projectSessionBindingService.unbind(sessionId);
    }
}
