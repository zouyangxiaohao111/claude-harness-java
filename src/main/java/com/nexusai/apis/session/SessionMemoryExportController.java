package com.nexusai.apis.session;

import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.infra.exception.ValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话记忆导出 REST 端点 · 对齐 CC {@code tools/AgentTool/agentMemory.ts} 导出面
 * （OPD-CM5-F-24 · 待前端对接.md §26）。
 *
 * <p><b>CC 真源（F24）</b>：前端有"导出会话记忆"需求——需要子代理/会话记忆入口路径，对齐 CC
 * {@code getAgentMemoryEntrypoint(agentType, scope)}（agentMemory.ts:109-114），返回
 * {@code join(getAgentMemoryDir(agentType, scope), 'MEMORY.md')}。本端点提供 REST 载体：
 * <pre>
 *   GET /api/v1/session-memory/export?agentType={type}&amp;scope={user|project|local}
 *   → {
 *       entrypoint: "&lt;memoryDir&gt;/MEMORY.md",          // getAgentMemoryEntrypoint 结果
 *       files:      [{ path: "&lt;memoryDir&gt;/MEMORY.md", content: "..." }, ...]  // 目录内 .md 文件
 *     }
 * </pre>
 *
 * <p><b>语义</b>（对齐 CC agentMemory.ts 导出面）：
 * <ul>
 *   <li><b>入口路径</b>（agentMemory.ts:109-114）——{@code getAgentMemoryDir(agentType, scope)} 解析
 *       记忆目录（user/project/local 三 scope，user=memoryBase/agent-memory/&lt;type&gt;，project=
 *       cwd/.nexusai/agent-memory/&lt;type&gt;，local=cwd/.nexusai/agent-memory-local/&lt;type&gt; 或
 *       remote mount），加 {@code MEMORY.md} 后缀。纯路径解析无 mkdir 副作用（对齐
 *       {@link AgentMemoryDirectory#getAgentMemoryEntrypoint} javadoc）。</li>
 *   <li><b>文件列表+内容</b>——导出 = 记忆目录内全部顶层 {@code .md} 文件（含入口 MEMORY.md）及其
 *       UTF-8 内容。目录内 .md 是 agent 会话期间写入的持久记忆（agentMemory.ts:169-176
 *       buildMemoryPrompt 读取的面），按路径排序（MEMORY.md 恒首位）。目录不存在 / 文件读取失败
 *       → 跳过不报错（对齐 CC claudemd.ts:402-416 {@code handleMemoryFileReadError} ENOENT/EISDIR
 *       忽略、EACCES 记遥测的容错读语义；REST 载体以 warn 日志表达）。</li>
 *   <li><b>无门控</b>——导出为只读盘面快照（对齐 {@code getAgentMemoryEntrypoint} 纯路径解析无门控；
 *       记忆是否启用由前端会话上下文决定，未写入则 files 为空、entrypoint 仍返回预期路径）。</li>
 * </ul>
 *
 * <p><b>失败处理</b>（对齐 03 §4.2「REST 端点错误 → 4xx/5xx 结构化返回」）：
 * <ul>
 *   <li>agentType 空/缺失 → {@link ValidationException}（400）；</li>
 *   <li>scope 非法（非 user/project/local）→ {@link ValidationException}（400）；</li>
 *   <li>{@link AgentMemoryDirectory} 未接线 → 500（fail loud：记忆目录解析是本端点唯一职责，
 *       无静默降级，对齐 MemoryController resolveEngine 同语义）。</li>
 * </ul>
 *
 * <p><b>鉴权</b>：{@code /api/v1/session-memory/export} 已纳入
 * {@code BearerTokenAuthFilterConfig} bearer 白名单（对齐 away-summary /dream /session-memory/config
 * 先例，前端接线即暴露须同步纳入）。
 */
@RestController
@RequestMapping("/api/v1/session-memory/export")
public class SessionMemoryExportController {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryExportController.class);

    /**
     * agent memory 目录解析器（getAgentMemoryEntrypoint）· @Bean 注册自动装配
     * （ToolRegistrationConfig.agentMemoryDirectory() 共享单例），required=false 容错单测反射注入。
     */
    @Autowired(required = false)
    private AgentMemoryDirectory agentMemoryDirectory;

    /**
     * GET /api/v1/session-memory/export · 导出会话记忆（入口路径 + 记忆文件列表及内容）。
     *
     * <p>CC original: {@code getAgentMemoryEntrypoint(agentType, scope)}（agentMemory.ts:109-114）——
     * 前端传子代理/会话的 {@code agentType}（目录名）与 {@code scope}（user/project/local），本端点
     * 解析记忆目录返回入口文件路径，并附带目录内全部 {@code .md} 文件内容供前端导出/渲染。
     *
     * <p>默认值（会话主 agent 语义）：agentType 缺省 {@code general-purpose}（CC DEFAULT_AGENT_PROMPT
     * 主 agent 类型，BuiltInAgents.GENERAL_PURPOSE）；scope 缺省 {@code user}（agent-memory 快照
     * 初始化仅 user scope，loadAgentsDir.java:299 同面）。
     *
     * @param agentType 子代理/会话 agent 类型名（目录名，冒号自动替换为横杠 · agentMemory.ts:20-22）
     * @param scope     memory scope：user / project / local（agentMemory.ts:13）
     * @return {@code {entrypoint, files: [{path, content}]}}
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionMemoryExportResponse export(
            @RequestParam(value = "agentType", defaultValue = "general-purpose") String agentType,
            @RequestParam(value = "scope", defaultValue = "user") String scope) {
        AgentMemoryDirectory dir = resolveDirectory();
        if (agentType == null || agentType.isBlank()) {
            log.warn("[SessionMemoryExportController] GET /session-memory/export: agentType 缺失/空 → 400");
            throw new ValidationException("agentType is required");
        }
        AgentMemoryDirectory.AgentMemoryScope memoryScope = AgentMemoryDirectory.fromName(scope);
        if (memoryScope == null) {
            log.warn("[SessionMemoryExportController] GET /session-memory/export: scope 非法 '{}'"
                + "（有效: user/project/local）→ 400", scope);
            throw new ValidationException("scope must be one of: user, project, local");
        }
        // CC getAgentMemoryEntrypoint（agentMemory.ts:109-114）= getAgentMemoryDir(agentType, scope)/MEMORY.md
        Path entrypoint = dir.getAgentMemoryEntrypoint(agentType, memoryScope);
        Path memoryDir = entrypoint.getParent();
        List<MemoryFileExport> files = listMemoryFiles(memoryDir);
        if (log.isInfoEnabled()) {
            log.info("[SessionMemoryExportController] GET /session-memory/export 完成: agentType={} scope={}"
                + " entrypoint={} files={}", agentType, scope, entrypoint, files.size());
        }
        return new SessionMemoryExportResponse(entrypoint.toString(), files);
    }

    /**
     * 列表并读取记忆目录内全部顶层 {@code .md} 文件内容 · 容错读（目录不存在 → 空列表）。
     *
     * <p>对齐 CC claudemd.ts:424-437 {@code safelyReadMemoryFileAsync}（ENOENT/EISDIR 忽略、
     * EACCES 记遥测）与 agentMemorySnapshot.ts:116-122 {@code hasLocalMemoryFile}
     * （dirent.isFile() 不 follow symlink → NOFOLLOW_LINKS 判别）——单文件读取失败跳过该文件
     * （warn 日志），不影响其余文件导出。
     *
     * @param memoryDir 记忆目录（{@code getAgentMemoryDir(agentType, scope)} 结果）
     * @return 按路径排序的 .md 文件列表（MEMORY.md 恒首位 · ASCII 大写 &lt; 小写）
     */
    private List<MemoryFileExport> listMemoryFiles(Path memoryDir) {
        List<MemoryFileExport> files = new ArrayList<>();
        if (memoryDir == null || !Files.isDirectory(memoryDir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(memoryDir)) {
            List<Path> mdFiles = stream
                // dirent.isFile() 不 follow symlink（agentMemorySnapshot.ts:119 → NOFOLLOW_LINKS）
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .sorted()
                .toList();
            for (Path p : mdFiles) {
                try {
                    files.add(new MemoryFileExport(p.toString(), Files.readString(p)));
                } catch (IOException e) {
                    // 单文件读取失败跳过（CC safelyReadMemoryFileAsync 容错读语义）
                    log.warn("[SessionMemoryExportController] 读取记忆文件失败，跳过: {} - {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            // 目录遍历失败（不可读等）→ 空列表（CC readdir 异常 catch → []）
            log.warn("[SessionMemoryExportController] 遍历记忆目录失败，返回空列表: {} - {}", memoryDir, e.getMessage());
        }
        return files;
    }

    /**
     * 解析 agent memory 目录解析器 · 未接线 → 500（fail loud：记忆目录解析是本端点唯一职责，
     * 无静默降级，对齐 MemoryController resolveEngine 同语义）。
     */
    private AgentMemoryDirectory resolveDirectory() {
        AgentMemoryDirectory dir = agentMemoryDirectory;
        if (dir == null) {
            log.error("[SessionMemoryExportController] agentMemoryDirectory 未接线 → 会话记忆导出不可用（fail loud）");
            throw new IllegalStateException("agentMemoryDirectory not wired (session memory export unavailable)");
        }
        return dir;
    }

    /**
     * 会话记忆导出响应 · CC original: getAgentMemoryEntrypoint 结果 + 记忆目录 .md 文件列表
     * （agentMemory.ts:109-114 + claudemd.ts:229-243 MemoryFileInfo {path, content} 形状）。
     *
     * @param entrypoint 记忆入口文件路径（{@code <memoryDir>/MEMORY.md}，CC getAgentMemoryEntrypoint）
     * @param files      记忆目录内全部顶层 .md 文件（含入口 MEMORY.md，路径排序）· {path, content}
     */
    public record SessionMemoryExportResponse(
            String entrypoint,
            List<MemoryFileExport> files) {}

    /**
     * 记忆文件导出项 · CC original: MemoryFileInfo 的 {path, content} 子形状（claudemd.ts:229-243）。
     *
     * @param path    记忆文件绝对路径
     * @param content 文件 UTF-8 内容
     */
    public record MemoryFileExport(String path, String content) {}
}
