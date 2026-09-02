package com.nexusai.apis.skill;

import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.domain.command.CommandService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.dto.CommandDto;
import com.nexusai.model.command.dto.CreateCommandRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill REST 端点（已废弃）· 向后兼容旧 API，内部委托给 {@link CommandService}
 *
 * <p>新代码请使用 {@link com.nexusai.apis.command.CommandController} (/api/command)。
 *
 * @deprecated 使用 {@link com.nexusai.apis.command.CommandController} 替代
 */
@Deprecated
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    @Autowired private CommandService commandService;

    /** [联调三问题·skill 根因] 内存真实技能源（bundled 注册 + ~/.claude/skills）——合并进 REST 数据源。 */
    @Autowired private SkillRegistry skillRegistry;

    /**
     * 列出所有技能（合并内存真实技能源 + DB/磁盘 ghost 补缺）· 前端 useSkills 实际消费端点。
     *
     * <p>WHY (联调三问题·skill 根因): 旧实现只读 {@code commandService.listAllDomain()}（DB command
     * 表 0 行 + ${nexusai.home}/skills 空目录）→ GET /api/v1/skills 恒返回 [] → SkillsPanel 渲染
     * 『暂无技能』。真实技能全在内存 {@link SkillRegistry} bean（getAllCommands 内部已按
     * availability+enabled 过滤，CC commands.ts:484）——本端点合并两者：SkillRegistry 先入 map
     * （同名权威胜出），listAllDomain() 再 putIfAbsent（DB/磁盘 ghost 补缺不覆盖），经
     * {@code CommandService.toDtos} 转换。
     *
     * @param reload true=重新扫描文件系统
     * @return 合并去重后的技能 DTO 列表
     */
    @GetMapping
    public List<CommandDto> list(@RequestParam(value = "reload", defaultValue = "false") boolean reload) {
        if (reload) commandService.rescanFromFilesystem();
        List<Command> registryCommands = skillRegistry.getAllCommands();
        Map<String, Command> byName = new LinkedHashMap<>();
        for (Command c : registryCommands) {
            byName.putIfAbsent(c.getName(), c);
        }
        for (Command c : commandService.listAllDomain()) {
            byName.putIfAbsent(c.getName(), c);
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillController] GET /api/v1/skills: SkillRegistry {} 个 + DB/磁盘 ghost 补缺 → 共 {} 个（同名 SkillRegistry 权威，CC getCommands 单一真源；联调三问题·skill 根因修复）",
                registryCommands.size(), byName.size());
        }
        return commandService.toDtos(List.copyOf(byName.values()));
    }

    @GetMapping("/{id}")
    public CommandDto get(@PathVariable String id) {
        return commandService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommandDto create(@Valid @RequestBody CreateCommandRequest req) {
        return commandService.create(req);
    }

    @PatchMapping("/{id}")
    public CommandDto update(@PathVariable String id,
                             @RequestBody com.nexusai.model.command.dto.UpdateCommandRequest req) {
        return commandService.update(id, req);
    }

    @PatchMapping("/{id}/toggle")
    public CommandDto toggle(@PathVariable String id) {
        return commandService.toggleEnabled(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        commandService.delete(id);
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        int n = commandService.rescanFromFilesystem();
        return Map.of("synced", n);
    }
}
