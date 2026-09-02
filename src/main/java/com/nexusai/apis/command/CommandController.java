package com.nexusai.apis.command;

import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.command.EffortCommand;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.skill.BuiltInCommands;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.ResumeAgentResult;
import com.nexusai.application.agent.subagent.ResumeService;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.command.ClientEnv;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.dto.BuiltInCommandDto;
import com.nexusai.model.command.dto.CommandDto;
import com.nexusai.model.command.dto.CreateCommandRequest;
import com.nexusai.model.command.dto.EffortExecuteRequest;
import com.nexusai.model.command.dto.ResumeExecuteRequest;
import com.nexusai.model.command.dto.UpdateCommandRequest;
import com.nexusai.domain.command.CommandService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command REST 端点 · 对齐 CC commands.ts getCommands() / findCommand()
 *
 * <h2>路由</h2>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/command</td><td>列出所有命令（?reload=true 重新扫描）</td></tr>
 *   <tr><td>GET</td><td>/api/command/{id}</td><td>获取单个命令详情</td></tr>
 *   <tr><td>POST</td><td>/api/command</td><td>创建新命令</td></tr>
 *   <tr><td>PATCH</td><td>/api/command/{id}</td><td>更新命令</td></tr>
 *   <tr><td>PATCH</td><td>/api/command/{id}/toggle</td><td>翻转 enabled</td></tr>
 *   <tr><td>DELETE</td><td>/api/command/{id}</td><td>删除命令（非 builtin/bundled）</td></tr>
 *   <tr><td>POST</td><td>/api/command/reload</td><td>手动重新扫描文件系统</td></tr>
 *   <tr><td>GET</td><td>/api/command/builtins</td><td>列出 COMMANDS 内置命令（DEC-9，React 命令源）</td></tr>
 *   <tr><td>POST</td><td>/api/command/builtins/{name}/execute</td><td>按 name/alias 解析内置命令执行入口
 *       （DEC-9 薄触发：返回命令元数据，React 自行触发 web 行为）</td></tr>
 *   <tr><td>POST</td><td>/api/command/builtins/effort/execute</td><td>[E2] /effort 后端真实执行
 *       （字面路径优先于 {name} 路径变量；R2 会话级：写当前会话 sessions.effort_level + 会话
 *       AgentState.effortValue + env 覆盖检测，返回 {message, effortValue}）</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/command")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    @Autowired private CommandService commandService;
    /** DEC-8: client-env 命令过滤链（SkillRegistry.filterByClientEnv）· 无循环依赖（SkillRegistry 依赖
     *  SkillsLoader/McpServerService/DynamicSkillsManager，均不依赖 controller）。 */
    @Autowired private SkillRegistry skillRegistry;

    /**
     * [IMP-SP-07] 会话级主 AgentState 注册表 · /clear 失效接线：按 MDC sessionId 解析主会话
     * AgentState → {@code systemPromptSectionCache().clear()}（对齐 CC clearSystemPromptSections
     * systemPromptSections.ts:65-68 的 /clear 触发）。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → 失效 debug skip
     * （保测试兼容，见 CommandControllerBuiltInCommandsTest）。
     */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * [RES-④] /resume 后端执行器 · 重建 resume 后端核心（CC resumeAgentBackground）。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → resume 分支
     * fail loud（见 {@link #executeResume}），非 resume 分支不受影响（保测试兼容）。
     */
    @Autowired(required = false)
    private ResumeService resumeService;

    /**
     * [E2] /effort 后端执行器 · 对齐 CC commands/effort/effort.tsx（R2 会话级：写当前会话
     * sessions.effort_level + 会话 AgentState.effortValue + env 覆盖检测）。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → effort 分支
     * fail loud（见 {@link #executeEffortBuiltin}），非 effort 分支不受影响（保测试兼容）。
     */
    @Autowired(required = false)
    private EffortCommand effortCommand;

    /**
     * [OPD-TP-19] 后台任务框架服务 · /clear preservedAgentIds 接线：按活跃后台任务计算保留的
     * agentId 集合 → {@code AgentState.clearInvokedSkills(preserved)}（对齐 CC conversation.ts:93
     * 语义，后台化会话不受 /clear 影响）。
     * {@code @Autowired(required=false)}：plain JUnit 缺省 null → 保留逻辑 debug skip（保测试兼容）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.tasks.TaskFrameworkService taskFrameworkService;

    /**
     * [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION feature 门 · /clear resetPromptCacheBreakDetection
     * 的 gatedBy 懒建源（对齐 AnthropicSdkProvider:1375-1391 模式）。reset 本身<b>不经</b> feature
     * 门控（CC caches.ts:63 无 feature 检查）——默认关时 PREVIOUS 恒空（record/check no-op 不写），
     * reset 无实际效果，行为不变；feature 开后 /clear 全清 PREVIOUS（含主会话 key）。
     * {@code @Autowired(required=false)}：plain JUnit 缺省 null → gatedBy 得 enabled=false 实例
     * （cleanup/reset 不经 enabled 检查仍真实执行，对齐 CC 模块级函数语义）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.loop.FeatureFlags featureFlags;

    /**
     * [IMP-SP2-08] 懒建 PromptCacheBreakDetection 实例 · PREVIOUS 为类级静态 Map
     * （CC previousStateBySource 模块级），任意实例共享同一状态。
     */
    private volatile com.nexusai.application.agent.lsp.PromptCacheBreakDetection promptCacheBreak;
    /**

     * [IMP2-02 r1（CC caches.ts:84）] claudemd 引擎 · /clear session_start reason 补偿：
     * {@code runPostCompactCleanup()} 内部置 {@code nextEagerLoadReason='compact'}（压缩语义），
     * 但 /clear 由 {@code clearSessionCaches} 触发、非压缩事件 —— 必须覆盖回 'session_start'，
     * 否则下轮 getMemoryFiles 缓存 miss 时 InstructionsLoaded hook 以 load_reason='compact'
     * 发射（CC caches.ts:80-84 注释显式防错；HookMatcherEngine 按 load_reason 匹配 hook
     * 使偏差可见）。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → debug skip
     * （保测试兼容，见 ManualCacheClearCcIntegrationTest）。
     */
    @Autowired(required = false)
    private ClaudemdEngine claudemdEngine;

    /**
     * [IMP-LL-02 · OPD-WF4-LC-03] SessionStart hooks 注册表 · /clear 前端触发对齐
     * （CC conversation.ts:245 {@code processSessionStartHooks('clear')}）。
     *
     * <p>web 后端「clear 会话」由前端 POST {@code /api/command/builtins/clear/execute} 触发
     * （本 controller /clear 分支），与 CC 会话清空（conversation.ts:245）对应 —— 在清空时点
     * 发射 SessionStart(source='clear') hook，让按 source 匹配的 hook 规则（HookMatcherEngine
     * SESSION_START → matchQuery=source，HookMatcherEngine:235）真实生效。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → /clear 分支
     * 跳过 hook 发射（保测试兼容，见 CommandControllerBuiltInCommandsTest / ManualCacheClearCcIntegrationTest）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.hook.HookRegistry hookRegistry;

    /**
     * [C-方案3][DEC-C-03] SubagentTool 引用 · /clear 时清 per-cwd registry 缓存。
     *
     * <p>对齐 CC caches.ts:138 clearAgentDefinitionsCache（loadAgentsDir.ts:395-398）——
     * Java 侧 /clear 等价清理须清 agent-defs 两层缓存（loadAgentsDir.clearCache 文件发现层 +
     * SubagentTool.clearRegistryCache per-cwd 组装层），否则磁盘 agent 变更在 /clear 后不可见。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → 跳过（保测试兼容）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.tool.impl.SubagentTool subagentTool;

    /**
     * [G16②] WebFetch 工具 bean · /clear 缓存清理接线（clearWebFetchCache，对齐 CC
     * commands/clear/caches.ts:130 WebFetchTool/utils.ts:80-83）。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → 跳过（保测试兼容）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.tool.impl.WebFetchTool webFetchTool;

    /**
     * [G16②] ToolSearch 工具 bean · /clear 描述缓存清理接线（clearToolSearchDescriptionCache，
     * 对齐 CC commands/clear/caches.ts:134-138 ToolSearchTool.ts:102-105）。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → 跳过（保测试兼容）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.tool.impl.ToolSearchTool toolSearchTool;

    /**
     * 列出所有命令 · DEC-8 前端环境声明过滤。
     *
     * <p>接收 {@code X-Client-Env} 请求头（react|mobile）透传到 {@link SkillRegistry#filterByClientEnv}
     * （CC meetsAvailabilityRequirement commands.ts:417-443 的 web 扩展镜像）：按声明环境过滤
     * availability 不匹配的命令；无环境头（或未知值）→ 原样放行（web 兼容，向后兼容不破）。
     *
     * <p>⚠ 内部链 getAllCommands 认证门控（auth 态）与 REST 链 client-env 门控（请求头）是
     * 双门控共存（信号源不同），见 SkillRegistry.filterByClientEnv JavaDoc。
     */
    @GetMapping
    public List<CommandDto> list(
        @RequestParam(value = "reload", defaultValue = "false") boolean reload,
        @RequestHeader(value = "X-Client-Env", required = false) String clientEnvHeader) {
        if (reload) commandService.rescanFromFilesystem();
        ClientEnv clientEnv = ClientEnv.fromHeader(clientEnvHeader);
        List<Command> domain = mergedSkillCommands();
        List<Command> filtered = skillRegistry.filterByClientEnv(domain, clientEnv);
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] list: 环境声明={} (X-Client-Env header='{}')，过滤前 {} 个 → 过滤后 {} 个 (DEC-8 client-env 门控，CC commands.ts:417-443)",
                clientEnv, clientEnvHeader, domain.size(), filtered.size());
        }
        return commandService.toDtos(filtered);
    }

    /**
     * 合并内存真实技能源（SkillRegistry 权威）+ DB/磁盘 ghost 补缺 · 按 name 去重。
     *
     * <p>WHY (联调三问题·skill 根因): REST 端点此前只读 {@code commandService.listAllDomain()}
     * （DB command 表 + ${nexusai.home}/skills 磁盘目录 —— dev 下 0 行/空目录）→ GET /api/command
     * 恒空。真实技能（bundled 内存注册 + ~/.claude/skills 用户技能）全在内存 {@link SkillRegistry}
     * bean（{@code getAllCommands()} 内部已按 availability+enabled 过滤，对齐 CC commands.ts:484）——
     * 合并进 REST 数据源：SkillRegistry 先入 map（同名权威胜出，对齐 CC getCommands 单一真源），
     * {@code listAllDomain()} 再 putIfAbsent（DB/磁盘 ghost 补缺不覆盖）。仍走
     * {@code filterByClientEnv}（X-Client-Env 头门控，DEC-8）→ toDtos。
     *
     * @return 按 name 去重后的领域命令列表（SkillRegistry 优先）
     */
    private List<Command> mergedSkillCommands() {
        List<Command> registryCommands = skillRegistry.getAllCommands();
        Map<String, Command> byName = new LinkedHashMap<>();
        for (Command c : registryCommands) {
            byName.putIfAbsent(c.getName(), c);
        }
        for (Command c : commandService.listAllDomain()) {
            byName.putIfAbsent(c.getName(), c);
        }
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] list: 合并数据源 SkillRegistry {} 个 + DB/磁盘 ghost 补缺 → 共 {} 个（同名 SkillRegistry 权威，CC getCommands 单一真源；联调三问题·skill 根因修复）",
                registryCommands.size(), byName.size());
        }
        return List.copyOf(byName.values());
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
                             @RequestBody UpdateCommandRequest req) {
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

    /**
     * 列出 COMMANDS 内置命令 · DEC-9（对齐 CC commands.ts:258 COMMANDS 数组，web 端子集
     * clear/compact/config/help/init/memory/model/output-style/resume/session）。
     *
     * <p>React 命令源：经 {@link BuiltInCommands#getAll()} 取内置命令，先过 DEC-8 client-env
     * 一致性过滤（{@link SkillRegistry#filterByClientEnv}，与 list 端点同链）再转
     * {@link BuiltInCommandDto}（补 {@link CommandDto} 缺 type 的 gap —— React 需 type 区分
     * prompt/local/local-jsx 渲染/触发）。内置命令 availability=null（universal）恒放行，
     * client-env 过滤对内置命令为恒真。
     *
     * <p>⚠ 路由冲突锁定：本字面路径 {@code /builtins} 优先于 {@code GET /api/command/{id}}
     * 路径变量（Spring literal-priority）；由 CommandControllerBuiltInCommandsTest 显式断言，
     * 防止未来 {@code {id}} 语义漂移吞掉本端点。
     *
     * @return 内置命令 DTO 列表（含 isHidden 命令如 output-style；React 自行过滤隐藏项）
     */
    @GetMapping("/builtins")
    public List<BuiltInCommandDto> listBuiltins(
        @RequestHeader(value = "X-Client-Env", required = false) String clientEnvHeader) {
        ClientEnv clientEnv = ClientEnv.fromHeader(clientEnvHeader);
        List<Command> domain = BuiltInCommands.getAll();
        List<Command> filtered = skillRegistry.filterByClientEnv(domain, clientEnv);
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] listBuiltins: 内置命令 {} 个 → client-env 过滤后 {} 个 (环境={}，DEC-9 COMMANDS 源 + DEC-8 一致性过滤)",
                domain.size(), filtered.size(), clientEnv);
        }
        return filtered.stream().map(BuiltInCommandDto::from).toList();
    }

    /**
     * 内置命令执行入口 · DEC-9（薄触发，不复制 CC TUI 分发逻辑）。
     *
     * <p>按 name/alias 解析内置命令：{@link BuiltInCommands#findByName} 去前导 '/' 后三维匹配
     * （name/aliases，对齐 CC findCommand commands.ts:688-698）。未知名 → {@link NotFoundException}
     * → 404（REST 语义，区别于 CC getCommand 抛 ReferenceError 列出全部命令 —— G-2 已登记）。
     * 命中 → 返回 {@link BuiltInCommandDto} 命令元数据，<b>不执行后端副作用</b>（web 无终端，
     * compact/config 的 TUI 分发由 React 拿到 type/name 后自行触发 web 行为 —— DEC-9
     * concern 边界）。
     *
     * <p><b>[IMP2-02 r1] clear 例外分支</b>：/clear 与 resume 同属后端真实执行分支 —— 触发
     * 会话级缓存清理链（invalidateSystemPromptSections + runPostCompactCleanup +
     * resetGetMemoryFilesCache('session_start') + clearInvokedSkillsPreservingBackgrounded，
     * 对齐 CC clearSessionCaches caches.ts:47-144）。「不执行后端副作用」不适用 clear。
     *
     * <p><b>[RES-④] resume 例外分支</b>：用户拍板 resume 走后端真实重建（CC resumeAgentBackground），
     * 前端 POST {@code /builtins/resume/execute} + 请求体 {agentId, prompt} → 返回
     * {@link ResumeAgentResult}（agentId/description/outputFile，前端凭 outputFile 轮询任务输出）。
     * 返回类型统一 {@link Object}（resume → ResumeAgentResult；其余 → BuiltInCommandDto 元数据）。
     *
     * @param name    内置命令名（可为 '/clear' 形式，前导 '/' 自动剥除）
     * @param request resume 请求体（仅 resume 分支消费；其余命令恒 null/忽略）
     * @return resume → {@link ResumeAgentResult}；其余 → 命令元数据 {@link BuiltInCommandDto}
     */
    @PostMapping("/builtins/{name}/execute")
    public Object executeBuiltin(@PathVariable String name,
                                 @RequestBody(required = false) ResumeExecuteRequest request) {
        Command hit = BuiltInCommands.findByName(name);
        if (hit == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] executeBuiltin({}) 未命中 → 404（内置命令按 name/alias 解析，DEC-9）", name);
            }
            // [ALIGN-VERIFY-1 G-2] CC getCommand 未命中抛 ReferenceError 并列出全部可用命令
            //   （commands.ts:704-726：按 getCommandName 排序、aliases 标注）—— 404 消息镜像该清单，
            //   fail-loud + 可用清单双语义对齐；REST 层以 NotFoundException/404 表达（无 JS 异常面）。
            throw new NotFoundException("Built-in command '" + name + "' not found. Available commands: "
                + BuiltInCommands.getAll().stream()
                    .map(c -> c.getAliases() != null && !c.getAliases().isEmpty()
                        ? c.getName() + " (aliases: " + String.join(", ", c.getAliases()) + ")"
                        : c.getName())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] executeBuiltin({}) 命中内置命令 {} (type={})",
                name, hit.getName(), hit.getType());
        }
        // [RES-④] /resume 例外分支：真实后端重建（用户拍板），其余命令保持 DEC-9 薄触发
        if ("resume".equals(hit.getName())) {
            return executeResume(request);
        }
        // [IMP-SP-07] /clear 失效接线：clear 命令触发会话级 system prompt section 缓存失效
        if ("clear".equals(hit.getName())) {
            invalidateSystemPromptSections("executeBuiltin(/clear)");
            // [C-方案3][DEC-C-03] /clear 等价清理 agent-defs 缓存 · 对齐 CC caches.ts:138
            //   clearAgentDefinitionsCache（loadAgentsDir.ts:395-398）→ Java 侧两层成对清：
            //   loadAgentsDir.clearCache（文件发现层 LOAD_CACHE + MarkdownConfigLoader memoize）
            //   + SubagentTool.clearRegistryCache（per-cwd 组装层），否则磁盘 agent 变更 /clear 后不可见。
            com.nexusai.application.agent.subagent.loadAgentsDir.clearCache();
            com.nexusai.application.agent.tool.impl.SubagentTool st = this.subagentTool;
            if (st != null) {
                st.clearRegistryCache();
            }
            // [IMP2-02（S-13/OD-20）] /clear 等价清理 · 对齐 CC caches.ts:74
            // runPostCompactCleanup() 无参调用 → querySource=undefined → main-thread gate=TRUE
            // 全执行：resetMicrocompactState + resetContextCollapse + clearAllProviderCaches +
            // resetGetMemoryFilesCache('compact') + clearSystemPromptSections +
            // clearClassifierApprovals + clearSpeculativeChecks（postCompactCleanup.ts:31-77）。
            // 既有 invalidateSystemPromptSections 保留（序列内 :62 同操作，幂等双清，未入删除清单）。
            PostCompactCleanup.runPostCompactCleanup();
            // [MG-6 · A2-4 补充登记 6] removeSessionState 接线 · /clear 会话结束钩子 → 释放 SESSION_STATES 桶
            //   （决策登记 6 A2-4：CC 进程随会话结束退出无泄漏；Java 多会话常驻 JVM，/clear 清空会话时点
            //   由外层调 removeSessionState 移除该会话 cached-MC 桶，防 SESSION_STATES 内存累积。
            //   resetMicrocompactState（PostCompactCleanup 序列内 :41）只复位桶内容不移桶；此处整桶移除，
            //   下轮 currentSessionState() computeIfAbsent 懒建新桶，语义等价 CC reset 后新 turn。）
            MicroCompactor.removeSessionState(com.nexusai.common.RequestContext.sessionId());
            // [IMP2-02 r1（CC caches.ts:80-84）] session_start reason 补偿：/clear 由
            // clearSessionCaches 触发，非压缩事件 —— runPostCompactCleanup 内部置的
            // 'compact' 必须覆盖回 'session_start'，否则下轮 getMemoryFiles 缓存 miss 时
            // InstructionsLoaded hook 以 load_reason='compact' 发射（CC 显式防错注释；
            // HookMatcherEngine 按 load_reason 匹配 hook 使偏差可见）。
            ClaudemdEngine claudemd = claudemdEngine;
            if (claudemd != null) {
                claudemd.resetGetMemoryFilesCache("session_start");
            } else if (log.isDebugEnabled()) {
                log.debug("[CommandController] executeBuiltin(/clear): resetGetMemoryFilesCache('session_start') "
                    + "跳过：ClaudemdEngine 未接线（对齐 CC caches.ts:84，保测试兼容）");
            }
            // [OPD-TP-19] /clear preservedAgentIds 接线：对齐 CC conversation.ts:93 —— 后台化
            // 任务（isBackgrounded != false）的 agentId 保留，invokedSkills 只清主会话/null-agent；
            // [IMP-SP2-08] 无 preserved 时内嵌 resetPromptCacheBreakDetection（CC caches.ts:63）。
            clearInvokedSkillsPreservingBackgrounded("executeBuiltin(/clear)");
            // [G16② OPD-TR-H1-05 关闭] /clear 缓存清理 · 对齐 CC commands/clear/caches.ts:130-138：
            //   clearWebFetchCache（WebFetchTool/utils.ts:80-83，url/domain 双缓存）
            //   + clearToolSearchDescriptionCache（ToolSearchTool.ts:102-105，描述缓存 + 基线）。
            //   二者均 @Component bean，plain JUnit 缺省 null → 跳过（保测试兼容）。
            if (webFetchTool != null) {
                webFetchTool.clearWebFetchCache();
            }
            if (toolSearchTool != null) {
                toolSearchTool.clearToolSearchDescriptionCache();
            }
            // [IMP-E4-06 · E4-XP-W67-01] /clear 前端触发 → 先 SESSION_END(reason='clear') hook
            //   · 对齐 CC conversation.ts:69 executeSessionEndHooks('clear')（清空会话时点，SessionEnd
            //     先于 SessionStart 发射；CC :245 processSessionStartHooks('clear') 在后）。
            //   sessionId 取 MDC（RequestContext）；SESSION_END 在 CC_APP_STATE_PRESENT_EVENTS
            //   （IMP-HR-07 R-2）→ 会话运行中 session function/command hooks 参与；配置 matcher='clear'
            //   的 SessionEnd hook 按 reason 匹配真实触发（HookMatcherEngine:333）。
            if (hookRegistry != null) {
                try {
                    String sid = com.nexusai.common.RequestContext.sessionId();
                    hookRegistry.executeSessionEndHooks(
                        sid, null, com.nexusai.application.agent.permission.hook.ExitReasons.CLEAR, null);
                    if (log.isDebugEnabled()) {
                        log.debug("[CommandController] executeBuiltin(/clear): SessionEnd(reason=clear) 已发射 sessionId={}（对齐 CC conversation.ts:69）",
                            sid);
                    }
                } catch (Exception e) {
                    log.warn("[CommandController] executeBuiltin(/clear): SessionEnd(reason=clear) 发射失败: {}",
                        e.getMessage());
                }
            }
            // [IMP-LL-02 · OPD-WF4-LC-03] /clear 前端触发 → SessionStart(source='clear') hook
            //   · 对齐 CC conversation.ts:245 processSessionStartHooks('clear')（清空会话时点发射）。
            //   sessionId 取 MDC（RequestContext），agentId=null（主线程，CC agent_type 未传），
            //   source='clear' → HookMatcherEngine SESSION_START 按 data.source 匹配
            //   （HookMatcherEngine:235），配置 matcher='clear' 的 SessionStart hook 真实触发。
            //   返回 hook 消息通道为 CC setMessages 的 UI 语义，web 端无法回填前端消息列表 →
            //   仅 fire side-effect（hook 执行 + watchPaths/遥测等），message 返回登记为受控残留。
            if (hookRegistry != null) {
                try {
                    String sid = com.nexusai.common.RequestContext.sessionId();
                    com.nexusai.application.agent.permission.hook.HookEvent clearEvent =
                        com.nexusai.application.agent.permission.hook.HookEvent.sessionStart(
                            sid, null, "clear", null, null);
                    hookRegistry.executeEvent(clearEvent);
                    if (log.isDebugEnabled()) {
                        log.debug("[CommandController] executeBuiltin(/clear): SessionStart(source=clear) 已发射 sessionId={}（对齐 CC conversation.ts:245）",
                            sid);
                    }
                } catch (Exception e) {
                    log.warn("[CommandController] executeBuiltin(/clear): SessionStart(source=clear) 发射失败: {}",
                        e.getMessage());
                }
            }
        }
        return BuiltInCommandDto.from(hit);
    }

    /**
     * [E2] /effort 后端真实执行 · 对齐 CC commands/effort/effort.tsx（DEC-9 concern 例外：
     * 非薄触发 —— R2 会话级：effort 需写当前会话 sessions.effort_level + 会话 AgentState.effortValue，
     * web 端无 TUI setAppState 等价，故后端执行）。
     *
     * <p>路由 literal-priority 锁定：本字面路径 {@code /builtins/effort/execute} 优先于
     * {@code /builtins/{name}/execute} 路径变量（Spring literal > path variable，同
     * {@code GET /api/command/builtins} vs {@code /{id}} 既有模式）——/effort 请求恒命中本端点。
     *
     * <p>请求体 {@code {args: "low"}}（{@link EffortExecuteRequest}）；缺省/空 → 显示当前档位。
     * 返回 {@code EffortCommand.EffortCommandResult}（{message, effortValue}，effortValue 为
     * 会话写入值，可 null）。
     *
     * @param request 请求体（可 null → args 空 = 显示当前档位）
     * @return {@link EffortCommand.EffortCommandResult}
     */
    @PostMapping("/builtins/effort/execute")
    public EffortCommand.EffortCommandResult executeEffortBuiltin(
            @RequestBody(required = false) EffortExecuteRequest request) {
        if (effortCommand == null) {
            log.error("[CommandController] executeEffortBuiltin: EffortCommand 未接线 → 拒绝执行（fail loud）");
            throw new IllegalStateException("EffortCommand not wired into CommandController");
        }
        String args = request != null ? request.args() : null;
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] executeEffortBuiltin: args='{}' → EffortCommand.handle（对齐 CC effort.tsx call）",
                args);
        }
        return effortCommand.handle(args);
    }

    /**
     * [RES-④] /resume 后端重建分支 · 对齐 CC resumeAgentBackground（resumeAgent.ts:42-265）。
     *
     * <p>解析 {agentId, prompt} → 经 {@link ResumeService#resumeAgentBackground} 读 transcript →
     * 三层过滤 → agent 解析/fork 父提示继承 → 异步续跑 → 返回 {agentId, description, outputFile}。
     *
     * <p>校验：resumeService 未接线 → IllegalStateException（fail loud）；agentId 缺失/空白 →
     * {@link ValidationException}（400）；MDC 无 sessionId → IllegalStateException（无会话上下文）。
     *
     * <p><b>[R-A] agentId 不拒收语义</b>（对齐 CC asAgentId，ids.ts:31-33 纯 cast）：agentId 即 string，
     * 非 UUID 不拒收 —— a+16hex（{@link AgentContext#createAgentId} 产物）经 S-12 pack 桥接受，任意
     * 其他字符串同样不拒收，最终由 ResumeService 双键查 transcript miss → {@link NotFoundException}（404，
     * 等价 CC resumeAgent.ts:67-69 getAgentTranscript miss → throw）。
     *
     * @param request resume 请求体（{@code agentId} 必填，{@code prompt} 可空）
     * @return {@link ResumeAgentResult}
     */
    private Object executeResume(ResumeExecuteRequest request) {
        if (resumeService == null) {
            log.error("[CommandController] executeResume: ResumeService 未接线 → 拒绝执行（fail loud）");
            throw new IllegalStateException("ResumeService not wired into CommandController");
        }
        if (request == null || request.agentId() == null || request.agentId().isBlank()) {
            throw new ValidationException("resume 请求体需携带 agentId");
        }
        // [R-A] 对齐 CC asAgentId（ids.ts:31-33 纯 cast，不校验格式）：agentId 即 string，去掉
        //   UUID.fromString 拒收。a+16hex（AgentContext.createAgentId 产物，D18/B2 拍板，'a'+16 hex）
        //   经 S-12 pack 桥 → UUID(msb,0)，ResumeService.unpackAgentId 还原 a+16hex 新格式键查找
        //   （ResumeService.java:136-144 双键：a+16hex 优先，UUID-string 兜底）。
        UUID agentId = toResumeAgentId(request.agentId());
        String sessionIdStr = RequestContext.sessionId();
        if (sessionIdStr == null) {
            log.warn("[CommandController] executeResume: MDC 无 sessionId（无会话上下文）");
            throw new IllegalStateException("无会话上下文 (sessionId)");
        }
        // [session-id-short] MDC sessionId 已 short，直传 String（UUID.fromString 对 sess-xxx 抛 IAE 硬边界删除）
        String sessionId = sessionIdStr;
        String prompt = request.prompt() != null ? request.prompt() : "";
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] executeResume: agentId={} rawAgentId={} prompt.length={} sessionId={}",
                agentId, request.agentId(), prompt.length(), sessionId);
        }
        // [R-A6 · A-6 决策（WF-G-UN-1 收口）] web resume 链路主会话 AgentState 注册性确认：
        //   resume 链依赖主会话 AgentState.currentModel()（CC resumeAgent.ts:131 options.mainLoopModel
        //   等价 —— ResumeService.rebuildForkParentSystemPrompt 模型现算 + SubagentTool.executeResumeAsync
        //   resolveParentModel 父模型继承，LlmAgentLoop.run() 模型解析后 setCurrentModel + register）。
        //   AgentState 未注册 / currentModel 不可得 → fail loud 抛错（对齐 A-6「currentModel() 不可得时
        //   fail loud 抛异常」；不伪造字节）。
        if (sessionAgentStateRegistry == null) {
            log.error("[CommandController] executeResume: SessionAgentStateRegistry 未接线 → 拒绝执行（fail loud，A-6）");
            throw new IllegalStateException(
                "SessionAgentStateRegistry not wired into CommandController; cannot resolve main session currentModel");
        }
        AgentState mainState = sessionAgentStateRegistry.get(sessionId);
        String currentModel = (mainState != null) ? mainState.currentModel() : null;
        if (currentModel == null || currentModel.isBlank()) {
            log.error("[CommandController] executeResume: 主会话 {} 无活跃 AgentState 或 currentModel 不可得 "
                + "→ 拒绝执行（fail loud，对齐 CC resumeAgent.ts:131 options.mainLoopModel，A-6）", sessionId);
            throw new IllegalStateException(
                "主会话 AgentState.currentModel() 不可得，无法执行 resume（A-6）");
        }
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] executeResume: 主会话 {} AgentState 已注册, currentModel={} "
                + "（对齐 CC resumeAgent.ts:131 options.mainLoopModel）", sessionId, currentModel);
        }
        // [R2-DELETE] 注入缝已按 CC 直接传参对齐移除：web 端点父 live ContentReplacementState 不可得
        //   （主循环 live state 在 AgentLoopContext.SessionState，不在 web 端点可达域）→ 传 null
        //   = CC :1006 reconstructForSubagentResume 对 null parent 返回 undefined（feature off，受控残留）。
        ResumeAgentResult result = resumeService.resumeAgentBackground(
            agentId, prompt, ResumeService.resolveSessionDir(sessionId.toString()), sessionId, null);
        if (log.isDebugEnabled()) {
            log.debug("[CommandController] executeResume: 已返回 ResumeAgentResult agentId={} outputFile={}",
                result.agentId(), result.outputFile());
        }
        return result;
    }

    /**
     * resume 请求 agentId → ResumeService 入参 UUID · 对齐 CC {@code asAgentId}（ids.ts:31-33 纯 cast）。
     *
     * <p><b>不拒收语义</b>（CC agentId 即 string，非 UUID 也不拒绝）：
     * <ul>
     *   <li>合法 UUID（legacy 格式）→ 直接透传（ResumeService 的 UUID-string fallback 兼容 pre-migration
     *       transcript，ResumeService.java:141/:144）</li>
     *   <li>a+16hex（{@link AgentContext#createAgentId} 产物，'a'+16 hex）→ {@link AgentContext#packAgentId}
     *       打包进 mostSigBits（S-12 桥），ResumeService {@link AgentContext#unpackAgentId} 还原 a+16hex 键
     *       （新格式 transcript 主键，ResumeService.java:136-140）</li>
     *   <li>任意其他字符串 → 不拒收：映射 {@code UUID(0L,0L)}，ResumeService 双键查 transcript 均 miss →
     *       {@link NotFoundException}（404），等价 CC getAgentTranscript miss → throw（resumeAgent.ts:67-69）</li>
     * </ul>
     *
     * @param agentId resume 请求 agentId（非 null / 非 blank，调用方已校验）
     * @return ResumeService 入参 UUID
     */
    private static UUID toResumeAgentId(String agentId) {
        try {
            return UUID.fromString(agentId);
        } catch (IllegalArgumentException uuidRejected) {
            // 非 UUID → 按 CC asAgentId 不拒收：优先 a+16hex pack（'a'+16 hex → mostSigBits）
            try {
                return AgentContext.packAgentId(agentId);
            } catch (NumberFormatException hexRejected) {
                // 任意非 hex 字符串（如 teammate 名）→ 不拒收，映射零 UUID → 下游 transcript miss → 404
                return new UUID(0L, 0L);
            }
        }
    }

    /**
     * [IMP-SP-07] /clear 失效接线 · 对齐 CC {@code clearSystemPromptSections} 的 /clear 触发点
     * （systemPromptSections.ts:65-68，经 clearConversation → clearSessionCaches → runPostCompactCleanup）。
     *
     * <p>经 {@link RequestContext#sessionId()}（MDC）解析当前会话 UUID → {@link SessionAgentStateRegistry#get}
     * → {@link AgentState#systemPromptSectionCache()#clear()}。会话缺失 / 解析失败 → debug skip
     * （保测试兼容）；registry 未接线（plain JUnit）→ debug skip。
     *
     * @param trigger 触发源描述（日志定位用）
     */
    private void invalidateSystemPromptSections(String trigger) {
        if (sessionAgentStateRegistry == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: SessionAgentStateRegistry 未接线 → 失效跳过", trigger);
            }
            return;
        }
        String sessionIdStr = RequestContext.sessionId();
        if (sessionIdStr == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: MDC 无 sessionId（无会话上下文）→ 失效跳过", trigger);
            }
            return;
        }
        // [session-id-short] MDC sessionId 已 short 直键 registry（UUID.fromString 硬边界删除）
        AgentState state = sessionAgentStateRegistry.get(sessionIdStr);
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: 会话 {} 无活跃 AgentState → 失效跳过", trigger, sessionIdStr);
            }
            return;
        }
        state.systemPromptSectionCache().clear();
        log.info("[CommandController] {}: 会话 {} 的 system prompt section 缓存已失效（对齐 CC clearSystemPromptSections）",
            trigger, sessionIdStr);
    }

    /**
     * [OPD-TP-19] /clear preservedAgentIds 接线 · 对齐 CC conversation.ts:93-127
     * {@code preservedAgentIds} + caches.ts:117 {@code clearInvokedSkills(preservedAgentIds)}。
     *
     * <p><b>语义（CC 实际源码行为）</b>：
     * <ol>
     *   <li>遍历任务表，{@code isBackgrounded === false}（已前台化）任务不保留（conversation.ts:94-98
     *       shouldKillTask）；后台化 local_agent 任务取其 {@code agentId}，in_process_teammate 取
     *       {@code identity.agentId}（conversation.ts:99-106）</li>
     *   <li>[IMP-SP2-08] {@code preserved} 为空（无后台化任务）→ {@code resetPromptCacheBreakDetection()}
     *       （CC caches.ts:63 {@code if (!hasPreserved)} 门控，无 feature 检查）——保留集合只依赖任务表，
     *       与会话上下文无关，故在会话解析前计算</li>
     *   <li>{@code clearInvokedSkills(preserved)}：空集 → 全清；非空 → 保留
     *       {@code agentId ∈ preserved} 的条目，删 null-agent 与未保留条目（state.ts:1543-1555，
     *       Java AgentState.clearInvokedSkills 同语义，AgentState.java:870-885）</li>
     * </ol>
     *
     * <p>主会话后台化任务（Ctrl+B / POST background）agentId = taskId UUID 视图（s 前缀 9 字符）
     * —— 若本任务在 store 中为 RUNNING 且 isBackgrounded=true，则其 invokedSkills 跨 /clear 存活。
     *
     * @param trigger 触发源描述（日志定位用）
     */
    private void clearInvokedSkillsPreservingBackgrounded(String trigger) {
        if (taskFrameworkService == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: TaskFrameworkService 未接线 → preservedAgentIds 跳过", trigger);
            }
            return;
        }
        // CC conversation.ts:93-106 —— 保留后台化任务 agentId；isBackgrounded===false 前台化任务不保留。
        // 保留集合只依赖任务表（与会话上下文无关，CC conversation.ts:93 同样全局遍历）→ 先算。
        java.util.Set<UUID> preserved = new java.util.HashSet<>();
        for (com.nexusai.application.agent.tasks.BackgroundTask task : taskFrameworkService.listAll()) {
            if (!task.isBackgrounded()) {
                continue; // CC shouldKillTask（:94-98）
            }
            if (task.agentId() != null) {
                // local_agent / in_process_teammate 均为 agentId 归属（CC :99-106；Java taskId===agentId）
                preserved.add(task.agentId());
            }
        }
        // [IMP-SP2-08] !hasPreserved 门控（CC caches.ts:63）：无后台化任务保留 → 清空 PREVIOUS。
        //   reset 不经 feature 门控（caches.ts:63 无 feature 检查）；默认关时 PREVIOUS 恒空 → no-op。
        if (preserved.isEmpty()) {
            promptCacheBreakDetector().resetPromptCacheBreakDetection();
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: 无 preserved agentId → resetPromptCacheBreakDetection（对齐 CC caches.ts:63）", trigger);
            }
        }
        if (sessionAgentStateRegistry == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: SessionAgentStateRegistry 未接线 → preservedAgentIds 跳过", trigger);
            }
            return;
        }
        String sessionIdStr = RequestContext.sessionId();
        if (sessionIdStr == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: MDC 无 sessionId（无会话上下文）→ preservedAgentIds 跳过", trigger);
            }
            return;
        }
        // [session-id-short] MDC sessionId 已 short 直键 registry（UUID.fromString 硬边界删除）
        AgentState state = sessionAgentStateRegistry.get(sessionIdStr);
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("[CommandController] {}: 会话 {} 无活跃 AgentState → preservedAgentIds 跳过", trigger, sessionIdStr);
            }
            return;
        }
        state.clearInvokedSkills(preserved);
        log.info("[CommandController] {}: 会话 {} 的 invokedSkills 已清理（preserved 后台化 agent {} 个，对齐 CC conversation.ts:93）",
            trigger, sessionIdStr, preserved.size());
    }

    /**
     * [IMP-SP2-08] 懒建 PromptCacheBreakDetection · 对齐 AnthropicSdkProvider:1375-1391 模式
     * （synchronized 双检单飞）；{@code gatedBy(featureFlags)}：featureFlags null / flag 关 →
     * enabled=false 实例。reset/cleanup 不经 enabled 检查（CC promptCacheBreakDetection.ts:704-706
     * 无检查，模块级 Map 操作）→ 默认关时调用仍是真实 clear（PREVIOUS 恒空则 no-op，行为不变）。
     */
    private com.nexusai.application.agent.lsp.PromptCacheBreakDetection promptCacheBreakDetector() {
        com.nexusai.application.agent.lsp.PromptCacheBreakDetection d = promptCacheBreak;
        if (d == null) {
            synchronized (this) {
                if (promptCacheBreak == null) {
                    promptCacheBreak =
                        com.nexusai.application.agent.lsp.PromptCacheBreakDetection.gatedBy(featureFlags);
                }
                d = promptCacheBreak;
            }
        }
        return d;
    }
}
