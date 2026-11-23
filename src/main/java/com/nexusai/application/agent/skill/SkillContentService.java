package com.nexusai.application.agent.skill;

import com.nexusai.domain.command.CommandService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.command.Command;
import com.nexusai.model.skill.dto.SkillContentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 本机技能正文（按 name 取 SKILL.md）· {@code GET /api/v1/skills/{name}/content}。
 *
 * <p><b>为什么不复用 GET /api/command</b>：列表一次返回全部技能正文（实测 1.1MB），
 * 详情页只需要一条；这里按 name 现取并限制 512KB。
 *
 * <p><b>路径安全</b>：name 只允许 {@code [A-Za-z0-9._-]}（禁止路径分隔符）；contentPath
 * 含 {@code ..} 或文件名不是 SKILL.md 一律拒绝；磁盘读取同样限 512KB。
 */
@Service
public class SkillContentService {

    private static final Logger log = LoggerFactory.getLogger(SkillContentService.class);
    private static final long MAX_CONTENT_BYTES = 512 * 1024L;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final SkillRegistry skillRegistry;
    private final CommandService commandService;

    public SkillContentService(SkillRegistry skillRegistry, CommandService commandService) {
        this.skillRegistry = skillRegistry;
        this.commandService = commandService;
    }

    public SkillContentDto content(String name, String sessionId) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new ValidationException("技能名非法（只允许字母数字._-，不允许路径分隔符）：" + name);
        }
        Command command = findByName(name, sessionId);
        if (command == null) {
            throw new NotFoundException("技能不存在或未安装：" + name);
        }
        String content = command.getContent();
        String contentPath = command.getContentPath();
        if ((content == null || content.isBlank()) && contentPath != null && !contentPath.isBlank()) {
            content = readFromDisk(contentPath);
        }
        if (content == null) {
            throw new NotFoundException("技能正文不可用：" + name);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new ValidationException("技能正文超过 512KB 上限：" + name);
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillContent] name={} len={} path={}", name, content.length(), contentPath);
        }
        return new SkillContentDto(name, command.getDisplayName(), command.getDescription(),
                command.getVersion(), content, contentPath, command.getBaseDir(),
                command.getWhenToUse(), command.getAllowedTools());
    }

    private Command findByName(String name, String sessionId) {
        Map<String, Command> byName = new LinkedHashMap<>();
        for (Command c : skillRegistry.getAllCommands(sessionId)) {
            byName.putIfAbsent(c.getName(), c);
        }
        Command found = byName.get(name);
        if (found != null) {
            return found;
        }
        for (Command c : commandService.listAllDomain()) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private static String readFromDisk(String contentPath) {
        if (contentPath.contains("..")) {
            throw new ValidationException("技能正文路径非法（含 ..）：" + contentPath);
        }
        Path path;
        try {
            path = Path.of(contentPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new ValidationException("技能正文路径非法：" + contentPath);
        }
        if (!"SKILL.md".equalsIgnoreCase(path.getFileName().toString())) {
            throw new ValidationException("只允许读取 SKILL.md：" + contentPath);
        }
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("技能正文文件不存在：" + contentPath);
        }
        try {
            if (Files.size(path) > MAX_CONTENT_BYTES) {
                throw new ValidationException("技能正文超过 512KB 上限：" + contentPath);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new NotFoundException("技能正文读取失败：" + e.getMessage());
        }
    }
}
