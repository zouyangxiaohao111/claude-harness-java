package com.nexusai.domain.command;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.skill.SkillFrontmatterFields;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.command.dto.CommandDto;
import com.nexusai.model.command.dto.CreateCommandRequest;
import com.nexusai.model.command.dto.UpdateCommandRequest;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.command.mapper.CommandMapper;
import com.nexusai.repository.command.entity.CommandRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Command 业务逻辑 · 对齐 CC commands.ts:353-398 getSkills() + loadSkillsDir.ts SKILL.md 加载
 *
 * <h2>技能文件布局（对齐 CC skills/ 目录）</h2>
 * <pre>
 *   ~/.nexusai/skills/        ← NexusaiPaths.getAppConfigHomeDir()/skills（决策 D1，nexusai.home 已废弃）
 *     web-search/
 *       SKILL.md         ← YAML frontmatter + Markdown body
 *     my-skill/
 *       SKILL.md
 * </pre>
 *
 * <h2>SKILL.md 格式（对齐 CC SKILL.md YAML frontmatter）</h2>
 * <pre>
 * ---
 * name: my-skill
 * description: A skill that does X
 * allowedTools: [Bash, Read]
 * model: claude-sonnet-4-6
 * context: fork
 * agent: general-purpose
 * userInvocable: true
 * disableModelInvocation: false
 * version: "1.0"
 * ---
 *
 * # Skill Body
 * ...
 * </pre>
 *
 * <h2>DB 行为</h2>
 * - list = (DB ∪ 磁盘新增) 去重，文件系统优先
 * - 启动时 rescan：把磁盘上的 SKILL.md 同步进 DB（按 name upsert）
 * - create：先建目录+SKILL.md，再插 DB（folder 已存在 → 409）
 * - update：重新生成 SKILL.md，更新 DB
 * - delete：非 builtin/bundled 才允许；递归删除目录，再删 DB
 * - toggleEnabled：只改 DB enabled，不动文件；改后清 SkillRegistry 命令缓存（方案2，下次
 *   getAllCommands 重载读 DB enabled 生效）
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    /** 唯一 frontmatter 解析入口 · P0-6/跨模块统一（真 YAML 解析，对齐 CC frontmatterParser.ts）。 */
    private final ParseSkillFrontmatter frontmatterParser = new ParseSkillFrontmatter();

    @Autowired private CommandMapper commandMapper;

    /**
     * 方案2（用户拍板）: SkillRegistry 命令缓存刷新源 · DB enabled 变更（toggle / update
     * enabled）后清 SkillRegistry 命令缓存（{@link SkillRegistry#refreshCommandsOnly()}），
     * 下次 getAllCommands 重载读 DB enabled 生效（SkillRegistry.loadAllCommands DB 主控覆盖，
     * 方案1）。
     *
     * <p>{@code @Autowired(required=false)} 容错 POJO 测试直构（无 SkillRegistry bean →
     * null → 跳过刷新，行为不变）。
     */
    @Autowired(required = false)
    private SkillRegistry skillRegistry;

    // ============== lifecycle ==============

    @PostConstruct
    public void syncFromFilesystem() {
        try {
            int n = rescanFromFilesystem();
            if (log.isDebugEnabled()) {
                log.debug("[CommandService] Startup scan synced {} skill folder(s) from {}", n, skillsRoot());
            }
        } catch (Exception e) {
            log.error("[CommandService] Startup scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 扫描 ~/.nexusai/skills/（NexusaiPaths 根）下所有 SKILL.md → DB upsert by name · 对齐 CC loadSkillsDir.ts
     *
     * @return 同步的目录数
     */
    public int rescanFromFilesystem() {
        Path root = ensureSkillsRoot();
        int count = 0;
        try (Stream<Path> dirs = Files.list(root)) {
            Iterator<Path> it = dirs.iterator();
            while (it.hasNext()) {
                Path dir = it.next();
                if (!Files.isDirectory(dir)) continue;
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.exists(skillMd)) continue;
                try {
                    upsertFromFile(dir, skillMd);
                    count++;
                } catch (Exception e) {
                    log.warn("[CommandService] Failed to sync {}: {}", dir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[CommandService] Could not list {}: {}", root, e.getMessage());
        }
        if (log.isDebugEnabled()) {
            log.debug("[CommandService] rescanned {} skill(s) from {}", count, root);
        }
        return count;
    }

    // ============== API ==============

    /**
     * 列出所有命令的领域层列表（DB ∪ 磁盘去重）· DEC-8 client-env 过滤的输入源。
     *
     * <p>与 {@link #listAll()} 行为完全一致（listAll = listAllDomain → toDtos），仅返回领域对象
     * 而非 DTO：CommandDto 无 availability 字段（toDto 显式字段构造）、toDto 私有，controller 无法在
     * DTO 层过滤 → {@code SkillRegistry.filterByClientEnv} 必须先于 DTO 转换作用于领域层（DEC-8）。
     */
    public List<Command> listAllDomain() {
        Map<String, Command> byName = new LinkedHashMap<>();
        // 1. DB
        for (CommandRecord r : commandMapper.selectAll()) {
            Command c = r.toDomain();
            byName.put(c.getName(), c);
        }
        // 2. 磁盘：未在 DB 中的显示为虚条目
        Path root = skillsRoot();
        if (root != null && Files.exists(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    Path skillMd = dir.resolve("SKILL.md");
                    if (!Files.exists(skillMd)) return;
                    String name = dir.getFileName().toString();
                    if (byName.containsKey(name)) return;
                    Command ghost = new Command();
                    ghost.setId("cmd-fs-" + name);
                    ghost.setName(name);
                    ghost.setDescription("(unindexed) " + name);
                    ghost.setEnabled(Boolean.TRUE);
                    ghost.setBuiltin(Boolean.FALSE);
                    ghost.setSource(CommandSource.USER);
                    ghost.setBaseDir(dir.toAbsolutePath().toString());
                    ghost.setContentPath(skillMd.toAbsolutePath().toString());
                    byName.put(name, ghost);
                });
            } catch (IOException e) {
                log.warn("[CommandService] list() filesystem scan failed: {}", e.getMessage());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[CommandService] listAllDomain 汇总 {} 个命令（DB ∪ 磁盘去重，DEC-8 领域层数据源）",
                byName.size());
        }
        return List.copyOf(byName.values());
    }

    /** 列出所有命令 · DB ∪ 磁盘去重（DTO 视图，行为不变） */
    public List<CommandDto> listAll() {
        return toDtos(listAllDomain());
    }

    /**
     * 领域命令列表 → DTO 列表（委托私有 toDto）· DEC-8：controller 在 filterByClientEnv 过滤后批量转换。
     *
     * <p>DTO 映射唯一归属保持本类（避免双实现：CommandController 不内联 DTO 构造）。
     */
    public List<CommandDto> toDtos(List<Command> commands) {
        return commands.stream().map(this::toDto).toList();
    }

    public CommandDto getById(String id) {
        CommandRecord r = commandMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("Command " + id + " not found");
        return toDto(r.toDomain());
    }

    /** 按 name 查找 · 对齐 CC findCommand() */
    public CommandDto getByName(String name) {
        CommandRecord r = commandMapper.selectOneByQuery(
            QueryWrapper.create().eq("name", name));
        if (r == null) throw new NotFoundException("Command '" + name + "' not found");
        return toDto(r.toDomain());
    }

    public CommandDto create(CreateCommandRequest req) {
        String name = req.name();
        if (name == null || name.isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("Command 'name' is required");
        }
        if (commandMapper.selectOneByQuery(QueryWrapper.create().eq("name", name)) != null) {
            throw new ConflictException("Command name '" + name + "' already exists");
        }
        Path dir = ensureUniqueDir(name);

        // 1. 写 SKILL.md
        writeSkillMd(dir, name, req);

        // 2. 插 DB
        Command c = buildCommand(req, dir);
        c.setId(generateId("cmd"));
        c.setName(name);
        c.setSource(CommandSource.USER);
        c.setBuiltin(Boolean.FALSE);
        commandMapper.insert(CommandRecord.fromDomain(c));

        if (log.isDebugEnabled()) {
            log.debug("[CommandService] Created command '{}' at {}", name, dir);
        }
        return toDto(c);
    }

    public CommandDto update(String id, UpdateCommandRequest req) {
        CommandRecord rec = commandMapper.selectOneById(id);
        if (rec == null) throw new NotFoundException("Command " + id + " not found");
        Command s = rec.toDomain();

        // 方案2: 捕获 enabled 变更（req.enabled 显式提供且与旧值不同；applyRequest 前取值，
        //   s 此时尚未被 mutate —— 下方 nameChanged 块/applyRequest 只改内容/名字等字段，不动 enabled）
        boolean enabledChanged = req.enabled() != null && !req.enabled().equals(s.getEnabled());

        // 写回 SKILL.md（如果内容相关字段变化）
        Path dir = s.getBaseDir() != null ? Paths.get(s.getBaseDir()) : null;
        boolean nameChanged = req.name() != null && !req.name().equals(s.getName());
        boolean contentChanged = req.content() != null
            || req.description() != null
            || req.allowedTools() != null
            || req.model() != null
            || req.context() != null
            || req.agent() != null
            || req.paths() != null
            || req.argumentHint() != null
            || req.whenToUse() != null
            || req.version() != null
            || req.effort() != null
            || nameChanged;

        if (contentChanged && dir != null) {
            if (nameChanged) {
                // 重命名目录
                Path newDir = dir.getParent().resolve(req.name());
                try {
                    if (Files.exists(dir)) Files.move(dir, newDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to rename skill directory: " + e.getMessage(), e);
                }
                dir = newDir;
                s.setName(req.name());
                s.setBaseDir(dir.toAbsolutePath().toString());
                s.setContentPath(dir.resolve("SKILL.md").toAbsolutePath().toString());
            }
            // 重建 SKILL.md：合并原值 + 新值
            writeSkillMd(dir, s.getName(), mergeRequest(s, req));
        }

        // 更新域对象
        applyRequest(s, req);
        s.setBaseDir(dir != null ? dir.toAbsolutePath().toString() : s.getBaseDir());
        commandMapper.update(CommandRecord.fromDomain(s));

        // 方案2: DB enabled 变更 → 清 SkillRegistry 命令缓存（下次 getAllCommands 重载读 DB
        //   enabled 生效 · SkillRegistry.loadAllCommands DB 主控覆盖；未注入 SkillRegistry → 跳过）
        if (enabledChanged && skillRegistry != null) {
            skillRegistry.refreshCommandsOnly();
        }

        if (log.isDebugEnabled()) {
            log.debug("[CommandService] Updated command '{}'", s.getName());
        }
        return toDto(s);
    }

    public void delete(String id) {
        CommandRecord rec = commandMapper.selectOneById(id);
        if (rec == null) throw new NotFoundException("Command " + id + " not found");
        Command s = rec.toDomain();
        if (s.getSource() != null && s.getSource().isSystem()) {
            throw new ConflictException("Cannot delete builtin command '" + s.getName() + "'");
        }
        // 向后兼容：builtin 字段也阻止删除
        if (Boolean.TRUE.equals(s.getBuiltin())) {
            throw new ConflictException("Cannot delete builtin command '" + s.getName() + "'");
        }
        // 1. 递归删目录
        if (s.getBaseDir() != null) {
            Path dir = Paths.get(s.getBaseDir());
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException e) { log.warn("delete {} failed: {}", p, e.getMessage()); }
                        });
                } catch (IOException e) {
                    log.warn("walk {} failed: {}", dir, e.getMessage());
                }
            }
        }
        // 2. 删 DB
        commandMapper.deleteById(id);
        log.info("[CommandService] Deleted command '{}'", s.getName());
    }

    /**
     * 仅切换 enabled · 方案2（用户拍板）：DB enabled 变更 → 清 SkillRegistry 命令缓存，
     * 下次 getAllCommands 重载读 DB enabled 生效（SkillRegistry.loadAllCommands DB 主控覆盖）。
     */
    public CommandDto toggleEnabled(String id) {
        CommandRecord rec = commandMapper.selectOneById(id);
        if (rec == null) throw new NotFoundException("Command " + id + " not found");
        Command s = rec.toDomain();
        s.setEnabled(!Boolean.TRUE.equals(s.getEnabled()));
        commandMapper.update(CommandRecord.fromDomain(s));
        // 方案2: DB enabled 变更 → 清 SkillRegistry 命令缓存（下次 getAllCommands 重载读 DB
        //   enabled 生效 · SkillRegistry.loadAllCommands DB 主控覆盖；未注入 SkillRegistry → 跳过）
        if (skillRegistry != null) {
            skillRegistry.refreshCommandsOnly();
        }
        return toDto(s);
    }

    // ============== helpers: SKILL.md I/O ==============

    /** 从目录和 SKILL.md 解析并 upsert 到 DB · 对齐 CC loadSkillsDir.ts */
    private void upsertFromFile(Path dir, Path skillMd) {
        String raw;
        try {
            raw = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[CommandService] Cannot read {}: {}", skillMd, e.getMessage());
            return;
        }

        // P0-6 统一 frontmatter 解析：复用 ParseSkillFrontmatter 真 YAML 解析器（对齐 CC
        // frontmatterParser.ts:130-175），消除本类手写行解析（parseFrontmatter/parseBody）与
        // skill 包语义相悖的跨模块残留（trim/无 glob/多行）。body 不 trim（CC :145 对齐）。
        Map<String, Object> frontmatter = frontmatterParser.parse(raw);
        String body = frontmatterParser.extractBody(raw);
        String name = frontmatter.getOrDefault("name", dir.getFileName().toString()).toString();

        CommandRecord existing = commandMapper.selectOneByQuery(
            QueryWrapper.create().eq("name", name));

        if (existing == null) {
            // 新条目：插入
            Command c = new Command();
            c.setId(generateId("cmd"));
            c.setName(name);
            c.setDescription(str(frontmatter, "description", ""));
            c.setSource(CommandSource.USER);
            c.setEnabled(Boolean.TRUE);
            c.setBuiltin(Boolean.FALSE);
            c.setBaseDir(dir.toAbsolutePath().toString());
            c.setContentPath(skillMd.toAbsolutePath().toString());
            c.setContent(body);
            applyFrontmatter(c, frontmatter, body, name);
            commandMapper.insert(CommandRecord.fromDomain(c));
            log.info("[CommandService] Imported from disk: {} → {}", name, dir);
        } else {
            // 已存在：刷新
            Command c = existing.toDomain();
            c.setDescription(str(frontmatter, "description", c.getDescription()));
            c.setBaseDir(dir.toAbsolutePath().toString());
            c.setContentPath(skillMd.toAbsolutePath().toString());
            c.setContent(body);
            applyFrontmatter(c, frontmatter, body, name);
            commandMapper.update(CommandRecord.fromDomain(c));
            if (log.isDebugEnabled()) {
                log.debug("[CommandService] Refreshed from disk: {}", name);
            }
        }
    }

    /**
     * 写 SKILL.md frontmatter · P0-6 统一为 CC 标准键名（kebab-case，when_to_use snake_case），
     * 与 {@link ParseSkillFrontmatter#parseSkillFrontmatterFields}（loadSkillsDir.ts:185-265）
     * 读取键一致，保证 DB 链 round-trip 不丢字段。
     */
    private void writeSkillMd(Path dir, String name, CreateCommandRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(yamlStr(name)).append("\n");
        if (req.description() != null) sb.append("description: ").append(yamlStr(req.description())).append("\n");
        if (req.allowedTools() != null && !req.allowedTools().isEmpty())
            sb.append("allowed-tools: ").append(toYamlList(req.allowedTools())).append("\n");
        if (req.model() != null) sb.append("model: ").append(yamlStr(req.model())).append("\n");
        if (req.context() != null) sb.append("context: ").append(req.context()).append("\n");
        if (req.agent() != null) sb.append("agent: ").append(yamlStr(req.agent())).append("\n");
        if (req.userInvocable() != null) sb.append("user-invocable: ").append(req.userInvocable()).append("\n");
        if (req.disableModelInvocation() != null) sb.append("disable-model-invocation: ").append(req.disableModelInvocation()).append("\n");
        if (req.version() != null) sb.append("version: ").append(yamlStr(req.version())).append("\n");
        if (req.paths() != null && !req.paths().isEmpty())
            sb.append("paths: ").append(toYamlList(req.paths())).append("\n");
        if (req.argumentHint() != null) sb.append("argument-hint: ").append(yamlStr(req.argumentHint())).append("\n");
        if (req.whenToUse() != null) sb.append("when_to_use: ").append(yamlStr(req.whenToUse())).append("\n");
        if (req.effort() != null) sb.append("effort: ").append(yamlStr(req.effort())).append("\n");
        if (req.hooks() != null) sb.append("hooks: ").append(req.hooks()).append("\n");
        sb.append("---\n\n");
        if (req.content() != null) sb.append(req.content());

        try {
            Path skillMd = dir.resolve("SKILL.md");
            Files.writeString(skillMd, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write SKILL.md: " + e.getMessage(), e);
        }
    }

    // ============== helpers: YAML frontmatter 解析（统一复用 ParseSkillFrontmatter） ==============
    // P0-6 跨模块统一：本类手写行解析（parseFrontmatter/parseBody/parseScalarValue）已删除，
    // 全部改经 {@link #frontmatterParser}（{@link ParseSkillFrontmatter} 真 YAML 解析器，
    // 对齐 CC frontmatterParser.ts:130-175）。唯一残留差异：本 DB 链按 name upsert，
    // frontmatter.name 优先（Java 存活链语义，CC name 恒=目录名）。

    // ============== helpers: request → Command 映射 ==============

    private Command buildCommand(CreateCommandRequest req, Path dir) {
        Command c = new Command();
        c.setDescription(req.description());
        c.setContent(req.content());
        c.setAliases(req.aliases());
        c.setAllowedTools(req.allowedTools());
        c.setModel(req.model());
        c.setContext(req.context() != null ? req.context() : "inline");
        c.setAgent(req.agent());
        c.setPaths(req.paths());
        c.setVersion(req.version());
        c.setArgumentHint(req.argumentHint());
        c.setWhenToUse(req.whenToUse());
        c.setEffort(req.effort());
        c.setHooks(req.hooks());
        c.setUserInvocable(req.userInvocable() != null ? req.userInvocable() : Boolean.TRUE);
        c.setDisableModelInvocation(req.disableModelInvocation() != null ? req.disableModelInvocation() : Boolean.FALSE);
        c.setEnabled(req.enabled() != null ? req.enabled() : Boolean.TRUE);
        c.setBaseDir(dir.toAbsolutePath().toString());
        c.setContentPath(dir.resolve("SKILL.md").toAbsolutePath().toString());
        return c;
    }

    /** 用于 update：合并原值 + 新值构造临时的 CreateCommandRequest */
    private CreateCommandRequest mergeRequest(Command s, UpdateCommandRequest req) {
        return new CreateCommandRequest(
            req.name() != null ? req.name() : s.getName(),
            req.description() != null ? req.description() : s.getDescription(),
            req.content() != null ? req.content() : s.getContent(),
            req.aliases() != null ? req.aliases() : s.getAliases(),
            req.allowedTools() != null ? req.allowedTools() : s.getAllowedTools(),
            req.model() != null ? req.model() : s.getModel(),
            req.context() != null ? req.context() : s.getContext(),
            req.agent() != null ? req.agent() : s.getAgent(),
            req.paths() != null ? req.paths() : s.getPaths(),
            req.version() != null ? req.version() : s.getVersion(),
            req.argumentHint() != null ? req.argumentHint() : s.getArgumentHint(),
            req.whenToUse() != null ? req.whenToUse() : s.getWhenToUse(),
            req.effort() != null ? req.effort() : s.getEffort(),
            req.hooks() != null ? req.hooks() : s.getHooks(),
            req.userInvocable() != null ? req.userInvocable() : s.getUserInvocable(),
            req.disableModelInvocation() != null ? req.disableModelInvocation() : s.getDisableModelInvocation(),
            req.enabled() != null ? req.enabled() : s.getEnabled()
        );
    }

    private void applyRequest(Command c, UpdateCommandRequest req) {
        if (req.name() != null) c.setName(req.name());
        if (req.description() != null) c.setDescription(req.description());
        if (req.content() != null) c.setContent(req.content());
        if (req.aliases() != null) c.setAliases(req.aliases());
        if (req.allowedTools() != null) c.setAllowedTools(req.allowedTools());
        if (req.model() != null) c.setModel(req.model());
        if (req.context() != null) c.setContext(req.context());
        if (req.agent() != null) c.setAgent(req.agent());
        if (req.paths() != null) c.setPaths(req.paths());
        if (req.version() != null) c.setVersion(req.version());
        if (req.argumentHint() != null) c.setArgumentHint(req.argumentHint());
        if (req.whenToUse() != null) c.setWhenToUse(req.whenToUse());
        if (req.effort() != null) c.setEffort(req.effort());
        if (req.hooks() != null) c.setHooks(req.hooks());
        if (req.userInvocable() != null) c.setUserInvocable(req.userInvocable());
        if (req.disableModelInvocation() != null) c.setDisableModelInvocation(req.disableModelInvocation());
        if (req.enabled() != null) c.setEnabled(req.enabled());
    }

    /**
     * 应用 frontmatter 到 Command · P0-6 统一复用 {@link ParseSkillFrontmatter#parseSkillFrontmatterFields}
     * （CC loadSkillsDir.ts:185-265 16 字段 CC 真源语义），消除手写 camelCase 键映射残留。
     *
     * <p>paths（CC :182-184 caller 单独提供）保留独立处理；其余字段全部经 16 字段解析结果落位。
     * body 不 trim 传入（CC :214）。
     */
    private void applyFrontmatter(Command c, Map<String, Object> fm, String body, String name) {
        // paths —— CC :182-184 caller 单独提供 · parseSkillPaths 对齐 glob 语义
        if (fm.containsKey("paths")) {
            c.setPaths(ParseSkillFrontmatter.parseSkillPaths(fm.get("paths")));
        }
        // 16 字段统一解析（CC loadSkillsDir.ts:185-265 parseSkillFrontmatterFields）
        SkillFrontmatterFields parsed = ParseSkillFrontmatter.parseSkillFrontmatterFields(fm, body, name, "Skill");
        c.setDisplayName(parsed.displayName());
        if (parsed.description() != null) {
            c.setDescription(parsed.description());
        }
        c.setHasUserSpecifiedDescription(parsed.hasUserSpecifiedDescription());
        c.setAllowedTools(parsed.allowedTools());
        c.setModel(parsed.model());
        c.setContext(parsed.executionContext() != null ? parsed.executionContext() : "inline");
        c.setAgent(parsed.agent());
        c.setVersion(parsed.version());
        c.setArgumentHint(parsed.argumentHint());
        c.setWhenToUse(parsed.whenToUse());
        c.setEffort(parsed.effort());
        c.setHooks(parsed.hooks());
        c.setUserInvocable(parsed.userInvocable());
        c.setDisableModelInvocation(parsed.disableModelInvocation());
        c.setShell(parsed.shell());
        c.setArgNames(parsed.argumentNames());
        if (log.isDebugEnabled()) {
            log.debug("[CommandService] applyFrontmatter 16 字段解析完成: name={} desc={} (CC loadSkillsDir.ts:185-265)",
                name, parsed.description());
        }
    }

    // ============== helpers: DTO 转换 ==============

    private CommandDto toDto(Command c) {
        return new CommandDto(
            c.getId(),
            c.getName(),
            c.getDescription(),
            c.getVersion(),
            c.getSource(),
            c.getAliases(),
            c.getArgumentHint(),
            c.getWhenToUse(),
            Boolean.TRUE.equals(c.getUserInvocable()),
            Boolean.TRUE.equals(c.getDisableModelInvocation()),
            Boolean.TRUE.equals(c.getIsHidden()),
            Boolean.TRUE.equals(c.getIsSensitive()),
            Boolean.TRUE.equals(c.getImmediate()),
            c.getKind(),
            c.getContext(),
            c.getAgent(),
            c.getAllowedTools(),
            c.getModel(),
            c.getEffort(),
            c.getPaths(),
            c.getHooks(),
            c.getContent(),
            c.getContentPath(),
            c.getBaseDir(),
            c.getProgressMessage(),
            Boolean.TRUE.equals(c.getEnabled()),
            Boolean.TRUE.equals(c.getBuiltin()),
            c.getType(),
            c.getPluginInfo() != null && c.getPluginInfo().pluginManifest() != null
                ? c.getPluginInfo().pluginManifest().name()
                : null
        );
    }

    // ============== helpers: 文件路径 ==============

    private Path skillsRoot() {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), "skills");
    }

    private Path ensureSkillsRoot() {
        Path root = skillsRoot();
        try {
            if (!Files.exists(root)) Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("[CommandService] Could not create skills root {}: {}", root, e.getMessage());
        }
        return root;
    }

    private Path ensureUniqueDir(String name) {
        Path dir = ensureSkillsRoot().resolve(name);
        if (Files.exists(dir)) {
            throw new ConflictException("Skill folder '" + name + "' already exists at " + dir);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create skill dir: " + e.getMessage(), e);
        }
        return dir;
    }

    // ============== helpers: JSON/YAML 序列化 ==============

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    private static String yamlStr(String s) {
        if (s == null) return "";
        if (s.contains(":") || s.contains("#") || s.contains("\"") || s.contains("'"))
            return "\"" + s.replace("\"", "\\\"") + "\"";
        return s;
    }

    private static String toYamlList(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(yamlStr(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
