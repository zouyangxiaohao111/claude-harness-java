package com.nexusai.domain.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.mcp.ChannelNotificationGate;
import com.nexusai.application.agent.mcp.EnvExpansion;
import com.nexusai.application.agent.mcp.McpConfigLoader;
import com.nexusai.application.agent.mcp.McpServerUtils;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.mcp.config.McpConfigAddValidator;
import com.nexusai.application.agent.mcp.config.McpConfigDedup;
import com.nexusai.application.agent.mcp.config.McpConfigFileWriter;
import com.nexusai.application.agent.mcp.McpOAuth;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.application.agent.mcp.config.McpConfigPolicy;
import com.nexusai.application.agent.mcp.config.McpJsonConfigParser;
import com.nexusai.application.agent.mcp.config.McpProperties;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.mcp.McpServer;
import com.nexusai.model.mcp.dto.McpCreateRequest;
import com.nexusai.model.mcp.dto.McpOAuthRequest;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.mcp.dto.McpStatus;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * MCP Server 业务逻辑（v2：start/stop/test 为 no-op）
 *
 * <p>Phase 0-2 ready, real connect/reconnect requires MCP protocol implementation
 * (out of scope for now). 三个端点保留：{@code POST /mcp/{id}/start},
 * {@code POST /mcp/{id}/stop}, {@code POST /mcp/{id}/test} — 全部 200/202，
 * 仅记录 TODO 警告。后续 v2 接入 MCP 协议时，把这几个方法体替换为真实
 * （stdin/stdout JSON-RPC）连接管理。
 *
 * <p>CRUD（list/get/create/update/delete）保持不变。
 *
 * <p>DDD 分层：只持有 domain POJO（{@link McpServer}），mapper 返回的
 * {@link McpServerRecord} 通过 {@code toDomain()} / {@code fromDomain()} 互转。
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    /** oauth env 镜像序列化（applyServerConfig 写 {@code __mcp_oauth__} / toDto 反解）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private McpServerMapper mcpServerMapper;
    @Autowired private McpTransportFactory mcpTransportFactory;
    /**
     * [S03 CE-13] MCP OAuth 凭据清理 · 对齐 CC remove 命令语义（mcp.tsx:80-83
     * clearServerTokensFromLocalStorage + clearMcpClientConfig —— 删除本地凭据，非服务端
     * revoke）。delete() 时按 serverKey 清 {@code mcp_oauth_tokens} 行 + 预配置
     * client_secret。required=false 容错：测试直构未装配 → null → 清理跳过（best-effort）。
     */
    @Autowired(required = false)
    private McpOAuthTokenService mcpOAuthTokenService;
    @Autowired private McpToolPool mcpToolPool;

    /** T4 策略源：nexusai.mcp.policy.*（yml，Java 对 CC policySettings 的模拟）。缺省 null = 全放行。 */
    @Autowired(required = false)
    private McpProperties mcpProperties;

    /**
     * [mcp-add 校验链 a~g] CC addMcpConfig（config.ts:625-761）落地 · create/update 前置校验
     * （名字正则/保留名/enterprise/schema/denylist/allowlist/重复）。生产接线 fail-loud
     * （@Autowired required=true）：装配失败即启动失败，不静默跳过校验（规则十二）。
     */
    @Autowired private McpConfigAddValidator addValidator;

    /**
     * [mcp-add DB 唯一源] 用户拍板（2026-08-30）：MCP 写只写 DB、读只读 DB，双写已删除。
     * 本字段仅剩 {@code describeMcpConfigFilePath} 一个使用点（create/update 响应 DTO 的
     * filePath 展示，scope 从 DB 记录取）。.mcp.json 仅手动 import 入口（importFromMcpJson
     * 经 McpConfigLoader/McpJsonConfigParser 直接读文件，不经本类）。
     */
    @Autowired private McpConfigFileWriter configFileWriter;

    /**
     * [mcp-add 校验链 b] computer-use 保留名 feature 门控 · 对齐 CC feature('CHICAGO_MCP')
     * （config.ts:641-648，生产默认 false）。需开启经 {@link #setComputerUseReservedGate}
     * 由 Spring 配置源注入；未注入 → 恒 false（nexusai-in-chrome 恒拦不受此门控影响）。
     */
    private BooleanSupplier computerUseReservedGate = () -> false;

    /**
     * 注入 computer-use 保留名 feature 门控 · CC original: feature('CHICAGO_MCP') config.ts:641。
     *
     * <p>POJO 兼容：未注入（null）→ 恒 false（对齐 CC 生产默认关）。
     *
     * @param gate CHICAGO_MCP 开关供应；null 视为恒 false
     */
    public void setComputerUseReservedGate(BooleanSupplier gate) {
        this.computerUseReservedGate = gate != null ? gate : () -> false;
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] 注入 computer-use 保留名门控 (注入后当前={})",
                this.computerUseReservedGate.getAsBoolean());
        }
    }

    /**
     * [mcp-add xaa fail-fast] XAA feature 门控 · 对齐 CC {@code isXaaEnabled()} =
     * {@code isEnvTruthy(process.env.CLAUDE_CODE_ENABLE_XAA)}（xaaIdpLogin.ts:32-33，env
     * 未设 → false = 生产默认关）。{@link #validateXaaFailFast} 缺省读 {@code System.getenv}
     * 等价；需注入/测试替换经 {@link #setXaaEnabledGate}。
     */
    private BooleanSupplier xaaEnabledGate = () -> xaaEnabledFromEnv();

    /** 注入 XAA feature 门控（CC isXaaEnabled 等价）；null → 回退读 CLAUDE_CODE_ENABLE_XAA env。 */
    public void setXaaEnabledGate(BooleanSupplier gate) {
        this.xaaEnabledGate = gate != null ? gate : () -> xaaEnabledFromEnv();
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] 注入 XAA feature 门控 (注入后当前={})",
                this.xaaEnabledGate.getAsBoolean());
        }
    }

    /**
     * CC isEnvTruthy（envUtils.ts:32-37）：{@code ['1','true','yes','on']} 命中为 true。
     * env 未设/空 → false（生产默认关）。
     */
    private static boolean xaaEnabledFromEnv() {
        return isEnvTruthy(System.getenv("CLAUDE_CODE_ENABLE_XAA"));
    }

    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String s = value.trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    /**
     * [impl-I-3 rework #1] channel 门控 bean（@Component）· 供 {@link #start()} 接线
     * {@code setAllowedChannelsSupplier}（session --channels 注入点）与 gate 数据流日志。
     *
     * <p>生产接线 fail-loud：本字段 @Autowired(required=true) → Spring 上下文装配失败即启动失败
     * （不静默 skip），满足 doc 风险表「新增 @Autowired 字段 + 装配失败显式 fail-loud」。
     */
    @Autowired private ChannelNotificationGate channelNotificationGate;

    /**
     * [S07] 会话态 --channels 白名单数据源 · 对齐 CC {@code getAllowedChannels()}
     * （bootstrap/state.ts:1676-1682，进程内会话白名单）· 供 {@link #start()}/
     * {@link #startEnabledBatch()} 注入 {@link ChannelNotificationGate#setAllowedChannelsSupplier}
     * 真实会话态 supplier（替换恒空 {@code List::of} fail-closed 接线）。
     *
     * <p>生产接线 fail-loud：本字段 @Autowired(required=true) → Spring 上下文装配失败即启动失败
     * （不静默 skip），与 {@link #channelNotificationGate} 同模式；测试直构按需
     * ReflectionTestUtils.setField 注入（见 S07 concerns #2）。
     */
    @Autowired private com.nexusai.application.agent.mcp.ChannelSessionAllowlist channelSessionAllowlist;

    /**
     * [S08 F2 接线] 插件 MCP 集成（F1-F5）· 供 {@link #start()}/{@link #startEnabledBatch()}
     * 注入真实 pluginSource 解析器（CC mcpPluginIntegration.ts:354 addPluginScopeToServers
     * 注入 name@marketplace）→ gate 门序[4] marketplace 校验（McpToolPool:1359）可消费。
     *
     * <p>required=false 容错：未装配（测试直构/插件域未启用）→ resolver 恒 null →
     * plugin-kind entry 对 gate fail-closed（对齐 CC pluginSource undefined fail），不破坏
     * 既有测试直构。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.plugin.PluginMcpIntegration pluginMcpIntegration;

    /**
     * P3-5: skill-search 索引清除契约宿主 · CC original: {@code clearSkillIndexCache}
     * （useManageMCPConnections.ts:27-30 {@code feature('EXPERIMENTAL_SKILL_SEARCH') ?
     * require('../skillSearch/localSearch.js').clearSkillIndexCache : undefined}）。
     *
     * <p>required=false 容错：SkillDiscoveryPrefetch 为 POJO（非 @Component/@Bean），生产
     * 注入不到 → null → {@link #start()} 接线 no-op（对齐 CC flag-off 时 clearSkillIndexCache
     * 为 undefined、调用点 {@code ?.()} 短路）。组合根职责 = 把 {@code clearSkillIndexCache()}
     * 委托为 {@link McpToolPool#setSkillIndexClearer} 的 Runnable（镜像现有
     * {@code setMcpSkillsGate + setSkillPoolRefresher} 接线处）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.skill.SkillDiscoveryPrefetch skillDiscoveryPrefetch;

    /**
     * [gap31] channelPermissions 全局开关 · CC original: isChannelPermissionRelayEnabled()
     * （channelPermissions.ts:36-38，GrowthBook 'tengu_harbor_permissions' 默认 false）。
     * {@code toDto} 填 {@code channelPermissions}（全局 feature 值透出，非 per-server 声明）。
     * required=false 容错：测试直构未装配 → null → 默认 false（对齐 ChannelPermissionFeature 无
     * Spring 缺省 false，channelPermissions.ts:36-38）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.ChannelPermissionFeature channelPermissionFeature;

    /**
     * s19-P1-6: 当前活跃的 MCP 工具池 · 对齐 CC tools.ts:345 assembleToolPool.
     *
     * <p>真实 MCP 协议上线后, 此 Map 由 MCP server 上线/下线事件驱动
     * (JsonRpcMcpClient.tools/list 解析结果写入此处). v1 阶段 (start/stop no-op)
     * 由测试/外部代码手动 register/unregister.
     *
     * <p>[impl-I-4 F2 rework] 原 {@link LinkedHashMap}（非线程安全）→ {@link ConcurrentHashMap}：
     * {@code startEnabledBatch} 批连接（local/remote 3/20 线程池）的 onConnectionAttempt 回调
     * 并发调 {@link #addMcpTool} put 此 Map → LinkedHashMap 数据竞态。改并发 Map 后无丢条目；
     * getCurrentTools 消费方仅遍历（池语义，顺序无契约）。
     */
    private final Map<String, Tool> mcpTools = new ConcurrentHashMap<>();

    /**
     * P2-13: MCP skill 命令列表 · 对齐 CC AppState.mcp.commands 中 loadedFrom==='mcp' 的产物
     * （commands.ts:551-556 getMcpSkillCommands 消费的纯过滤源）。
     *
     * <p>由 {@link #start} 经 {@link McpToolPool#fetchMcpSkills} 生产（skill:// 资源发现，
     * CC mcpSkills.ts fetchMcpSkillsForClient 对齐）。旧 X23 fetchCommands 桥接已删除
     * （prompts/list 命令非 CC skill，见 deleteList）。
     * {@link #getMcpSkillCommands()} 只做纯过滤器，不再生产。
     */
    private final List<Command> mcpSkillCommands = new ArrayList<>();

    /**
     * 拍板#2: MCP prompt 命令池（fetchCommands 产物）· 对齐 CC {@code AppState.mcp.commands}
     * 的 prompts 段（client.ts:2054-2095 {@code fetchCommandsForClient}，source='mcp' 无
     * loadedFrom —— 普通 MCP prompt 非 skill，CC commands.ts:551-556 会排除）。
     *
     * <p>由 {@link #refreshMcpPromptCommands} 生产（fetchCommands 产物 → prompts/list），
     * {@link #getMcpPromptCommandsForSearch()} 只做搜索视图过滤器（含 MCP prompt，无 loadedFrom）。
     * 消费方 {@link com.nexusai.application.agent.skill.SkillRegistry#findCommandIncludingMcp}
     * 经 thread-in 合并（拍板#2：搜索基座含 MCP prompt）。
     */
    private final List<Command> mcpPromptCommands = new ArrayList<>();

    public List<McpServerDto> listAll() {
        // DB 唯一源（用户拍板 2026-08-30）：读只读 DB，oauth 权威在 DB env 镜像键，无文件兜底
        return mcpServerMapper.selectAll().stream()
            .map(r -> toDto(r.toDomain())).toList();
    }

    public McpServerDto getById(String id) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        return toDto(r.toDomain());
    }

    /**
     * REST add · 对齐 CC {@code claude mcp add}（addCommand.ts:81-279 → addMcpConfig config.ts:625-761）。
     *
     * <p>DB 唯一源（用户拍板 2026-08-30）：完整校验链 a~g → 直接 upsert DB（含 scope 列 V59），
     * 不再写配置源文件（双写已删）。读侧 list/get 也只读 DB，oauth 权威在 DB env 镜像键。
     */
    public McpServerDto create(McpCreateRequest req) {
        // 0. scope / transport 解析（CC ensureConfigScope / ensureTransport，utils.ts:292-314）
        String scope = addValidator.ensureConfigScope(req.scope());
        String type = addValidator.ensureTransport(req.type());
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] create 开始 name={} scope={} type={}", req.name(), scope, type);
        }
        // 0.5 XAA add-time fail-fast（CC addCommand.ts:103-122，先于三分发构造）
        validateXaaFailFast(req);

        // 1. 三分发构造 serverConfig（CC addCommand.ts:147-274）+ 非阻断警告（stdio URL 误用等）
        BuildResult built = buildServerConfig(req, type);
        Map<String, Object> config = built.config();

        // 2. 校验链 a~g（addMcpConfig config.ts:625-710，顺序严格一致）
        addValidator.validateName(req.name());
        addValidator.validateReserved(req.name(), computerUseReservedGate.getAsBoolean());
        addValidator.validateEnterprise();
        config = addValidator.validateSchema(config);
        addValidator.validatePolicy(req.name(), config);
        // 去重源 = DB 唯一（existingDb 同名行即唯一权威）；fileServers 参数传空 Map.of()
        // （DB 唯一源改造后无文件读侧）
        McpServerRecord existingDb = mcpServerMapper.selectOneByName(req.name());
        addValidator.checkDuplicate(req.name(), scope, Map.of(), existingDb);

        // 3. DB 唯一源：直接 upsert DB（scope 列 V59 持久化来源 scope）
        boolean enabled = req.enabled() == null ? Boolean.TRUE : req.enabled();
        upsertServer(req.name(), config, scope, "approved", enabled);
        log.info("[McpServerService] create 完成 server={} scope={} type={}（已 upsert DB，DB 唯一源）",
            req.name(), scope, type);

        // 4. clientSecret → keychain（仅 sse/http + clientId 同时存在；CC addCommand.ts:168-183，
        //    不进 config/文件）
        saveClientSecretIfNeeded(req, config);

        // 5. 返回扩展 DTO（scope 从 DB 记录取——DB 唯一源；filePath/warnings 供前端 G5 展示）
        McpServerRecord saved = mcpServerMapper.selectOneByName(req.name());
        McpServer savedDomain = saved.toDomain();
        return withAddMeta(toDto(savedDomain), config, savedDomain, built.warnings());
    }

    public McpServerDto update(String id, McpCreateRequest req) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        McpServer s = r.toDomain();
        String oldName = s.getName();
        String scope = addValidator.ensureConfigScope(req.scope());
        String type = (req.type() == null || req.type().isBlank())
            ? (s.getType() == null ? "stdio" : s.getType())
            : addValidator.ensureTransport(req.type());

        // XAA add-time fail-fast（CC addCommand.ts:103-122，先于三分发构造）
        validateXaaFailFast(req);

        // 非 REST 可写类型（sse-ide/ws-ide/ws/sdk/claudeai-proxy，仅 import 可产生）：DB 无
        // ideName/id 等完整字段，无法重构配置做 schema 校验/写回 .mcp.json（残缺 config 会
        // 覆盖文件条目）→ 保持既有 DB-only 更新语义（不触发校验链、不写配置源）。
        if (!McpConfigAddValidator.TRANSPORTS.contains(type)) {
            return updateLegacy(id, req, s);
        }

        String newName = req.name() == null ? oldName : req.name();
        boolean nameChanged = !newName.equals(oldName);

        // 校验链 a~g（重复检查排除自身；plan §2.11 item 2）
        addValidator.validateName(newName);
        addValidator.validateReserved(newName, computerUseReservedGate.getAsBoolean());
        addValidator.validateEnterprise();
        Map<String, Object> config = buildConfigForUpdate(s, req, type);
        config = addValidator.validateSchema(config);
        addValidator.validatePolicy(newName, config);
        // 去重源 = DB 唯一（同名行即唯一权威）；fileServers 参数传空 Map.of()（DB 唯一源无文件读侧）
        if (nameChanged) {
            McpServerRecord dbDup = mcpServerMapper.selectOneByName(newName);
            addValidator.checkDuplicate(newName, scope, Map.of(), dbDup);
        } else {
            addValidator.checkDuplicate(newName, scope, Map.of(), null);
        }
        // PATCH 未带 oauth 时保留 DB env 镜像已有 oauth（DB 唯一源，oauth 权威在 env 镜像键，
        // 避免覆盖丢字段；不再从 .mcp.json 文件条目读）
        if (!"stdio".equals(type) && req.oauth() == null && !nameChanged) {
            Map<String, Object> existingOauth = oauthFromEnv(deserializeEnv(s.getEnv()));
            if (existingOauth != null && !existingOauth.isEmpty()) {
                config.put("oauth", existingOauth);
            }
        }

        // 应用更新 → DB（改名直接改既有行；scope 列 V59 持久化）
        s.setName(newName);
        s.setType(type);
        s.setScope(scope);
        applyServerConfig(s, config);
        boolean enabled = req.enabled() != null ? req.enabled() : Boolean.TRUE.equals(s.getEnabled());
        s.setEnabled(enabled);

        // T6: PATCH enabled=false 对 running server → 级联 stop（既有逻辑保留）
        if (Boolean.FALSE.equals(enabled) && McpStatus.running.name().equals(s.getStatus())) {
            log.info("[McpServerService] PATCH enabled=false 级联停止 server={}", newName);
            stop(id);                       // teardown + 置 stopped + update
            s.setStatus(McpStatus.stopped.name());  // 内存对齐，下方 update 落库
        }

        mcpServerMapper.update(McpServerRecord.fromDomain(s));
        // DB 唯一源：不写配置源文件（putServer/removeServerExcept 已删，双写已去，scope 由列 V59 承载）
        saveClientSecretIfNeeded(req, config);
        log.info("[McpServerService] update 完成 server={} scope={} nameChanged={}", newName, scope, nameChanged);

        // [AM-CC-20260825] update 改 type/command 后断开旧 transport：McpToolPool 复用
        //   activeTransports 旧实例（改 type=sse→http 后仍走 SseMcpTransport，2026-08-25 联调实测），
        //   显式 teardown → 下次 assembleToolPool 用新配置重建连接（对齐 CC 配置变更即生效）
        if (McpStatus.running.name().equals(s.getStatus())) {
            log.info("[McpServerService] update 触发 transport 重建 server={}（type/command 变更生效）", newName);
            mcpToolPool.teardown(newName);
        }

        // [S06] enable 自动重连（既有逻辑保留）
        if (Boolean.TRUE.equals(enabled) && !McpStatus.running.name().equals(s.getStatus())) {
            log.info("[McpServerService] PATCH enabled=true 触发自动 start server={} "
                    + "（对齐 CC toggleMcpServer enable 分支）", newName);
            return start(id);
        }
        return withAddMeta(toDto(s), config, s, List.of());
    }

    /**
     * 非 REST 可写类型（sse-ide/ws-ide/ws/sdk/claudeai-proxy）的既有 DB-only 更新语义
     * （不含 mcp-add 校验链、不写配置源文件——残缺 config 覆盖 .mcp.json 会丢字段；
     * DB 唯一源改造后本来就只写 DB）。
     */
    private McpServerDto updateLegacy(String id, McpCreateRequest req, McpServer s) {
        if (req.name() != null) s.setName(req.name());
        if (req.command() != null) s.setCommand(req.command());
        if (req.args() != null) s.setArgs(serializeArgs(req.args()));
        if (req.env() != null) s.setEnv(serializeEnv(req.env()));
        if (req.enabled() != null) s.setEnabled(req.enabled());
        if (req.type() != null && !req.type().isBlank()) s.setType(req.type());
        if (req.scope() != null && !req.scope().isBlank()) s.setScope(req.scope());

        // T6: PATCH enabled=false 对 running server → 级联 stop（既有逻辑保留）
        if (Boolean.FALSE.equals(req.enabled()) && McpStatus.running.name().equals(s.getStatus())) {
            log.info("[McpServerService] PATCH enabled=false 级联停止 server={}（legacy 非 REST 类型）", s.getName());
            stop(id);                       // teardown + 置 stopped + update
            s.setStatus(McpStatus.stopped.name());  // 内存对齐，下方 update 落库
        }

        mcpServerMapper.update(McpServerRecord.fromDomain(s));

        // [S06] enable 自动重连（既有逻辑保留）
        if (Boolean.TRUE.equals(req.enabled())
                && !McpStatus.running.name().equals(s.getStatus())) {
            return start(id);
        }
        return toDto(s);
    }

    public void delete(String id) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        mcpServerMapper.deleteById(id);
        // [S03 CE-13] 删除 DB 行后清理 OAuth 凭据（对齐 CC mcp.tsx:80-83 remove 命令
        // clearServerTokensFromLocalStorage + clearMcpClientConfig 语义——删除本地凭据，
        // 非服务端 revoke）。best-effort 不抛（CC 清理路径无异常传播契约）。
        cleanupOAuthCredentials(r);
        // DB 唯一源：不写配置源文件（removeServerBestEffort 已删；.mcp.json 仅手动 import 入口）
        log.info("[McpServerService] delete 完成 server={}（DB 唯一源已删除）", r.getName());
    }

    /**
     * [S03 CE-13] OAuth 凭据清理 · serverKey 计算与 {@code McpAuthHeaderProvider.serverKey}
     * 完全同键（McpOAuth.getServerKey(name, type, command, headers-as-env)，远程 server 的
     * headers 承载于 env 列），否则清理错行。失败仅 log（best-effort，不阻断删除主流程）。
     */
    private void cleanupOAuthCredentials(McpServerRecord r) {
        if (mcpOAuthTokenService == null) {
            return;
        }
        try {
            // env 可能含 __mcp_oauth__ 镜像保留键 → 按 headers 语义剥除（否则 serverKey 与
            // saveClientSecret / McpAuthHeaderProvider.serverKey 不同键，清理错行）
            Map<String, String> headers = McpOAuth.headersOnly(deserializeEnv(r.getEnv()));
            String serverKey = McpOAuth.getServerKey(r.getName(), r.getType(), r.getCommand(),
                headers);
            mcpOAuthTokenService.delete(serverKey);
            mcpOAuthTokenService.clearClientSecret(serverKey);
            log.info("[McpServerService] delete 后已清理 OAuth 凭据 server={} serverKey={}（CE-13）",
                r.getName(), serverKey);
        } catch (Exception e) {
            log.warn("[McpServerService] delete OAuth 凭据清理失败（best-effort）server={}: {}",
                r.getName(), e.getMessage());
        }
    }
    // ============== T7 审批状态机（Q-25：pending→确认→启用/拒绝） ==============

    /** 审批通过 · CC 语义：approved → enabled=true（对齐 getProjectMcpServerStatus approved 分支）。 */
    public McpServerDto approve(String id) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        McpServer s = r.toDomain();
        s.setApprovalStatus("approved");
        s.setEnabled(Boolean.TRUE);
        mcpServerMapper.update(McpServerRecord.fromDomain(s));
        log.info("[McpServerService] approved server={}", s.getName());
        return toDto(s);
    }

    /** 审批拒绝 · CC 语义：rejected → enabled=false（对齐 disabledMcpjsonServers 命中分支）。 */
    public McpServerDto reject(String id) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        McpServer s = r.toDomain();
        s.setApprovalStatus("rejected");
        s.setEnabled(Boolean.FALSE);
        mcpServerMapper.update(McpServerRecord.fromDomain(s));
        log.info("[McpServerService] rejected server={}", s.getName());
        return toDto(s);
    }

    /**
     * T6: enabled 启停判定 · 对齐 CC isMcpServerDisabled（config.ts:1528-1536）。
     * 供 T3 去重（claudeai connector 仅 enabled manual 抑制）与 T7 审批流转复用。
     */
    public boolean isMcpServerDisabled(String name) {
        if (name == null) return true;
        McpServerRecord r = mcpServerMapper.selectOneByName(name);
        if (r == null) return true;
        return !Boolean.TRUE.equals(r.getEnabled());
    }

    // ============== T5 .mcp.json 导入 → DB 写回（Q-09=C：DB 唯一运行时源） ==============

    /** 导入结果 record. */
    public record McpServerImportResult(int imported, List<String> blocked, List<String> suppressed) {}

    /**
     * .mcp.json 导入管线 · 对齐 CC getClaudeCodeMcpConfigs（config.ts:1071-1251）：
     * parse（T2）→ scope 合并（T1，local 最高）→ 去重（T3，existing DB 为 manual 目标）
     * → 策略过滤（T4，sdk 豁免）→ 审批初始态判定（T7 getProjectMcpServerStatus）
     * → 按 name upsert（name UNIQUE，重复导入覆盖不新增）。
     *
     * <p>Q-09=C：.mcp.json 仅导入入口；LlmAgentLoop/McpToolPool 继续从 DB 读。
     *
     * @param scopeToFilePath scope → .mcp.json 路径（多 scope 合并，同名字 local 胜）
     * @param policy          策略（缺省 null = 全放行，对齐 CC config.ts:427-429）
     * @param projectSettings 审批字段（getProjectMcpServerStatus 输入）
     */
    /** REST 导入便捷重载：策略取 Spring 配置源（mcpProperties.policy()），审批缺省 pending。 */
    public McpServerImportResult importFromMcpJson(Map<String, String> scopeToFilePath) {
        McpProperties.Policy policy = mcpProperties == null ? null : mcpProperties.policy();
        return importFromMcpJson(scopeToFilePath, policy,
            new McpServerUtils.ProjectSettings(List.of(), List.of(), false),
            false, false, true);
    }

    public McpServerImportResult importFromMcpJson(Map<String, String> scopeToFilePath,
            McpProperties.Policy policy,
            McpServerUtils.ProjectSettings projectSettings,
            boolean skipDangerousModePermissionPrompt,
            boolean nonInteractiveSession,
            boolean projectSettingsEnabled) {
        if (scopeToFilePath == null || scopeToFilePath.isEmpty()) {
            return new McpServerImportResult(0, List.of(), List.of());
        }

        // 1+2. parse + scope 合并（T1 loadAllMcpServers：user→project→local，local 最后写 = 最高；
        //     [IMP-E2 S-2] dynamic/claudeai 不再落入最终合并集——仅作 CC 去重目标，EV-E3-011）。
        //     enterprise 用 Optional supplier（RES-02：对齐 CC doesEnterpriseMcpConfigExist
        //     config!==null，空文件也算存在 → 独占短路）
        McpConfigLoader loader = new McpConfigLoader(
            enterpriseScopeSupplier(scopeToFilePath),
            scopeSupplier(scopeToFilePath, "user"),
            scopeSupplier(scopeToFilePath, "project"),
            scopeSupplier(scopeToFilePath, "local"),
            scopeSupplier(scopeToFilePath, "dynamic"),
            scopeSupplier(scopeToFilePath, "claudeai"));
        Map<String, Map<String, Object>> merged = loader.loadAllMcpServers();
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] import parse+merge servers={} scopes={}", merged.size(), scopeToFilePath.keySet());
        }

        // 3. 去重（T3）：导入 server 作为新配置，existing DB 为 manual 目标（manual 优先）。
        Map<String, Map<String, Object>> existingDb = existingServersAsConfigs();
        McpConfigDedup.DedupResult deduped = McpConfigDedup.dedupPluginMcpServers(merged, existingDb);

        // 4. 策略过滤（T4，sdk 豁免）
        McpConfigPolicy.FilterResult filtered = McpConfigPolicy.filterMcpServersByPolicy(deduped.servers(), policy);

        // 5. 审批初始态（T7 getProjectMcpServerStatus）+ 6. upsert（带来源 scope——DB 唯一源：
        //    McpConfigLoader.addScopeToServers 已给每 server 标来源 scope，合并后保留最高优先级
        //    胜出者 local>project>user；enterprise 独占短路时全部为 enterprise）
        int imported = 0;
        for (Map.Entry<String, Map<String, Object>> e : filtered.allowed().entrySet()) {
            String name = e.getKey();
            String sourceScope = e.getValue().get("scope") instanceof String sc && !sc.isBlank()
                ? sc : "project";
            String status = McpServerUtils.getProjectMcpServerStatus(name, projectSettings,
                skipDangerousModePermissionPrompt, nonInteractiveSession, projectSettingsEnabled);
            boolean enabled = "approved".equals(status);
            upsertServer(name, e.getValue(), sourceScope, status, enabled);
            imported++;
        }
        List<String> suppressed = deduped.suppressed().stream()
            .map(McpConfigDedup.Suppressed::name).toList();
        log.info("[McpServerService] import completed imported={} blocked={} suppressed={}",
            imported, filtered.blocked(), suppressed);
        return new McpServerImportResult(imported, filtered.blocked(), suppressed);
    }

    /** scope supplier：把解析后的 scoped servers 提供给 McpConfigLoader（无 scope 字段）。 */
    private Supplier<Map<String, Map<String, Object>>> scopeSupplier(
            Map<String, String> scopeToFilePath, String scope) {
        String filePath = scopeToFilePath.get(scope);
        if (filePath == null || filePath.isEmpty()) {
            return Map::of;
        }
        return () -> {
            McpJsonConfigParser.ParseResult parsed = McpJsonConfigParser.parseMcpConfigFromFilePath(
                filePath, true, scope, new EnvExpansion(), this::readMcpConfigFile);
            List<McpJsonConfigParser.ParseError> fatal = parsed.errors().stream()
                .filter(err -> "fatal".equals(err.severity())).toList();
            if (!fatal.isEmpty()) {
                String msg = fatal.stream().map(McpJsonConfigParser.ParseError::message)
                    .distinct().collect(Collectors.joining("; "));
                throw new ConflictException("MCP config import failed for " + filePath + ": " + msg);
            }
            return stripScope(parsed.servers());
        };
    }

    /**
     * enterprise scope supplier · 对齐 CC doesEnterpriseMcpConfigExist（config.ts:1470-1477）.
     *
     * <p>{@code Optional.empty()} = enterprise config null（文件缺失 / 解析失败 / 非法 JSON）
     * → McpConfigLoader 不短路，其余 scope 正常合并（对齐 CC getClaudeCodeMcpConfigs
     * config.ts:1098+ 静默走非 enterprise 路径）；{@code Optional.of(map)} = config 存在
     * （map 可能空，空也独占短路，CC config:null 语义）。enterprise fatal 不抛
     * ConflictException（与其它 scope 的导入阻断语义不同——enterprise 缺失/非法
     * 不阻断导入，CC doesEnterpriseMcpConfigExist=false 即不短路）。 */
    private Supplier<Optional<Map<String, Map<String, Object>>>> enterpriseScopeSupplier(
            Map<String, String> scopeToFilePath) {
        String filePath = scopeToFilePath.get("enterprise");
        if (filePath == null || filePath.isEmpty()) {
            return Optional::empty;
        }
        return () -> {
            McpJsonConfigParser.ParseResult parsed = McpJsonConfigParser.parseMcpConfigFromFilePath(
                filePath, true, "enterprise", new EnvExpansion(), this::readMcpConfigFile);
            List<McpJsonConfigParser.ParseError> fatal = parsed.errors().stream()
                .filter(err -> "fatal".equals(err.severity())).toList();
            if (!fatal.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[McpServerService] enterprise MCP config 解析失败 file={} → Optional.empty()（CC doesEnterpriseMcpConfigExist=false，不短路不阻断）", filePath);
                }
                return Optional.empty();
            }
            return Optional.of(stripScope(parsed.servers()));
        };
    }

    /** .mcp.json 文件读取（IO 注入，ENOENT 透传 NoSuchFileException）。 */
    private String readMcpConfigFile(String p) throws java.io.IOException {
        try {
            return Files.readString(Path.of(p));
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 去掉 scope 字段，McpConfigLoader.addScopeToServers 会重新加。 */
    private Map<String, Map<String, Object>> stripScope(
            Map<String, Map<String, Object>> servers) {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : servers.entrySet()) {
            Map<String, Object> copy = new LinkedHashMap<>(e.getValue());
            copy.remove("scope");
            raw.put(e.getKey(), copy);
        }
        return raw;
    }

    /** existing DB servers → config Map（manual 去重目标）。 */
    private Map<String, Map<String, Object>> existingServersAsConfigs() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (McpServerRecord r : mcpServerMapper.selectAll()) {
            McpServer s = r.toDomain();
            result.put(s.getName(), serverConfig(s));
        }
        return result;
    }

    /**
     * [MCP-I-9 Q-32] 按名解析 MCP server 配置 · 对齐 CC getMcpConfigByName（config.ts:1033）
     * + runAgent.ts:140-151。string-ref 子代理 mcpServers（仅 name）→ 查 DB（Q-09=C 唯一运行时源）。
     *
     * <p>config 形态与 {@link #existingServersAsConfigs()} 同构（type/command/url/args），供
     * SubagentExecutor 构 {@code McpServerSpec}（stdio→command+args；远程→command 列存 url）。
     *
     * @param name MCP server 名
     * @return 命中 → config Map；未命中/禁用 → empty（对齐 CC getMcpConfigByName 未找到返 null）
     */
    public java.util.Optional<Map<String, Object>> getServerConfigByName(String name) {
        if (name == null || name.isBlank()) {
            return java.util.Optional.empty();
        }
        McpServerRecord r = mcpServerMapper.selectOneByName(name);
        if (r == null || !Boolean.TRUE.equals(r.getEnabled())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(serverConfig(r.toDomain()));
    }

    /** 单 server → config Map（stdio→command+args；远程→command 列存 url）。 */
    private Map<String, Object> serverConfig(McpServer s) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        String type = s.getType() == null ? "stdio" : s.getType();
        cfg.put("type", type);
        if ("stdio".equals(type)) {
            if (s.getCommand() != null) cfg.put("command", s.getCommand());
            List<String> args = deserializeArgs(s.getArgs());
            if (args != null) cfg.put("args", args);
        } else {
            // 远程：command 列存 url（现有 Java 契约 HttpMcpTransport 读 command() 当 url）
            if (s.getCommand() != null) cfg.put("url", s.getCommand());
        }
        return cfg;
    }

    /** 按 name upsert（name UNIQUE；重复导入覆盖更新不新增；scope 列 V59 持久化来源 scope）。 */
    private void upsertServer(String name, Map<String, Object> config, String scope,
            String approvalStatus, boolean enabled) {
        McpServerRecord existing = mcpServerMapper.selectOneByName(name);
        McpServer s;
        if (existing != null) {
            s = existing.toDomain();
        } else {
            s = new McpServer();
            s.setId(generateId("mcp"));
            s.setName(name);
            s.setStatus(McpStatus.stopped.name());
            s.setCreatedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        String type = String.valueOf(config.getOrDefault("type", "stdio"));
        s.setType(type);
        s.setScope(scope);
        s.setApprovalStatus(approvalStatus);
        s.setEnabled(enabled);
        s.setLastError(null);
        applyServerConfig(s, config);
        if (existing != null) {
            mcpServerMapper.update(McpServerRecord.fromDomain(s));
        } else {
            mcpServerMapper.insert(McpServerRecord.fromDomain(s));
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] upsert server={} type={} approval={} enabled={}",
                name, type, approvalStatus, enabled);
        }
    }

    // ============== mcp-add 对齐（CC claude mcp add 三分发 addCommand.ts:147-274 + AC-1 双写） ==============

    /** 三分发产物：config（写配置源 + 落 DB）+ 非阻断警告（stdio URL 误用 / OAuth flag for stdio）。 */
    private record BuildResult(Map<String, Object> config, List<String> warnings) {}

    /**
     * 按 transport 三分发构造 serverConfig（CC addCommand.ts:147-274）：
     * sse/http → {type, url, headers, oauth}；stdio → {type, command, args, env}。
     * 附 CC 命令层的两非致命 stderr 警告（URL-as-command / OAuth-flag-for-stdio）→ warnings[]。
     */
    private BuildResult buildServerConfig(McpCreateRequest req, String type) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> config = new LinkedHashMap<>();
        if ("sse".equals(type) || "http".equals(type)) {
            if (req.url() == null || req.url().isBlank()) {
                throw new ValidationException(
                    "Error: URL is required for " + ("sse".equals(type) ? "SSE" : "HTTP") + " transport.");
            }
            config.put("type", type);
            config.put("url", req.url());
            if (req.headers() != null && !req.headers().isEmpty()) {
                config.put("headers", req.headers());
            }
            Map<String, Object> oauth = buildOAuth(req.oauth());
            if (oauth != null) {
                config.put("oauth", oauth);
            }
        } else {
            // stdio（CC addCommand.ts:239-274）
            if (req.command() == null || req.command().isBlank()) {
                throw new ValidationException(
                    "Error: Command is required when server name is provided.\nUsage: claude mcp add <name> <command> [args...]");
            }
            config.put("type", "stdio");
            config.put("command", req.command());
            config.put("args", req.args() != null ? req.args() : List.of());
            if (req.env() != null && !req.env().isEmpty()) {
                config.put("env", req.env());
            }
            // OAuth flag 误用警告（CC addCommand.ts:240-249）
            if (hasOAuthFlags(req)) {
                warnings.add("Warning: --client-id, --client-secret, --callback-port, and --xaa "
                    + "are only supported for HTTP/SSE transports and will be ignored for stdio.");
            }
            // URL-as-command 警告（未显式 transport + looksLikeUrl，CC addCommand.ts:251-262）
            if ((req.type() == null || req.type().isBlank()) && looksLikeUrl(req.command())) {
                warnings.add("\nWarning: The command \"" + req.command() + "\" looks like a URL, "
                    + "but is being interpreted as a stdio server as --transport was not specified.\n"
                    + "If this is an HTTP server, use: claude mcp add --transport http "
                    + req.name() + " " + req.command() + "\n"
                    + "If this is an SSE server, use: claude mcp add --transport sse "
                    + req.name() + " " + req.command());
            }
        }
        return new BuildResult(config, warnings);
    }

    /** PATCH 合并配置：req 覆盖既有字段（未提供字段保留既有值）· 供 update 校验 + 文件写回。 */
    private Map<String, Object> buildConfigForUpdate(McpServer s, McpCreateRequest req, String type) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", type);
        if ("stdio".equals(type)) {
            config.put("command", req.command() != null ? req.command() : s.getCommand());
            if (req.args() != null) {
                config.put("args", req.args());
            } else if (s.getArgs() != null) {
                config.put("args", deserializeArgs(s.getArgs()));
            }
            if (req.env() != null) {
                config.put("env", req.env());
            } else if (s.getEnv() != null) {
                config.put("env", deserializeEnv(s.getEnv()));
            }
        } else {
            config.put("url", req.url() != null ? req.url() : s.getCommand());
            Map<String, String> headers;
            if (req.headers() != null) {
                headers = req.headers();
            } else {
                headers = headersFromEnv(deserializeEnv(s.getEnv()));
            }
            if (headers != null && !headers.isEmpty()) {
                config.put("headers", headers);
            }
            Map<String, Object> oauth = buildOAuth(req.oauth());
            if (oauth != null) {
                config.put("oauth", oauth);
            }
        }
        return config;
    }

    /**
     * [mcp-add xaa fail-fast] create/update 前置（CC addCommand.ts:103-122）：
     * xaa=true 时强制 feature 门（CLAUDE_CODE_ENABLE_XAA）+ clientId/clientSecret/
     * settings.xaaIdp 存在性校验，缺则 ValidationException（文案逐字对齐 CC）。
     * Java 无 settings.xaaIdp 基础设施（Q-07 已删 Xaa/XaaIdpLogin）→ xaaIdpConfigured 恒 false。
     */
    private void validateXaaFailFast(McpCreateRequest req) {
        McpOAuthRequest oauth = req.oauth();
        Boolean xaa = oauth != null ? oauth.xaa() : null;
        if (!Boolean.TRUE.equals(xaa)) {
            return;
        }
        String clientId = oauth != null ? oauth.clientId() : null;
        boolean hasClientId = clientId != null && !clientId.isBlank();
        boolean hasClientSecret = req.clientSecret() != null && !req.clientSecret().isBlank();
        addValidator.validateXaaFailFast(xaa, xaaEnabledGate.getAsBoolean(), hasClientId,
            hasClientSecret, false);
    }

    /** OAuth 组构（CC addCommand.ts:159-166/:205-212）：clientId/callbackPort/xaa 任一有值才产出。 */
    private Map<String, Object> buildOAuth(McpOAuthRequest oauthReq) {
        if (oauthReq == null) {
            return null;
        }
        boolean hasClientId = oauthReq.clientId() != null && !oauthReq.clientId().isBlank();
        Integer port = parseCallbackPort(oauthReq.callbackPort());
        boolean xaa = Boolean.TRUE.equals(oauthReq.xaa());
        // CC addCommand.ts:159-166 oauth falsy 门：parseInt("0")=0 与 parseInt("-0")=-0 均为 falsy →
        // 外层门短路（无 clientId/xaa 时 oauth=undefined）且展开省略 callbackPort（有 clientId 时）。
        if (!hasClientId && (port == null || port == 0) && !xaa) {
            return null;
        }
        Map<String, Object> oauth = new LinkedHashMap<>();
        if (hasClientId) {
            oauth.put("clientId", oauthReq.clientId());
        }
        if (port != null && port != 0) {
            oauth.put("callbackPort", port);
        }
        if (oauthReq.authServerMetadataUrl() != null && !oauthReq.authServerMetadataUrl().isBlank()) {
            oauth.put("authServerMetadataUrl", oauthReq.authServerMetadataUrl());
        }
        if (xaa) {
            oauth.put("xaa", true);
        }
        return oauth;
    }

    /**
     * JS-like parseInt（CC addCommand.ts:156-158 parseInt(options.callbackPort, 10)）：
     * 解析最长合法整数前缀（"3.5" → 3、"12abc" → 12）；无数字前缀（"abc"）→ NaN 语义 → 丢弃
     * （门禁修正 1：纯字母串静默置空不报错）；负值/小数截断留给 schema 的 int positive 报错。
     *
     * <p>返回 0 与 {@code "-0"}（Java int 无 -0 表示，parse 得 0）：0 在 CC 中为 falsy，由
     * {@link #buildOAuth} 的 falsy 门处理为「缺失」，<b>不会</b>进入 schema 报错（见该处）。
     */
    private Integer parseCallbackPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int i = 0;
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        long value = 0;
        boolean anyDigit = false;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            value = value * 10 + (s.charAt(i) - '0');
            anyDigit = true;
            i++;
        }
        if (!anyDigit) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] callbackPort 无数字前缀 → NaN 丢弃 raw={}", raw);
            }
            return null;
        }
        if (value > Integer.MAX_VALUE) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] callbackPort 溢出 → 丢弃 raw={}", raw);
            }
            return null;
        }
        return (int) (neg ? -value : value);
    }

    /** clientSecret → keychain（McpOAuthTokenService，CC keychain 等价物）· 仅 sse/http + clientId 同时存在。 */
    private void saveClientSecretIfNeeded(McpCreateRequest req, Map<String, Object> config) {
        if (mcpOAuthTokenService == null || req.clientSecret() == null || req.clientSecret().isBlank()) {
            return;
        }
        if (req.oauth() == null || req.oauth().clientId() == null || req.oauth().clientId().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] clientSecret 无 clientId → 忽略（CC addCommand.ts:168-171 语义）");
            }
            return;
        }
        String type = String.valueOf(config.getOrDefault("type", "stdio"));
        if (!"sse".equals(type) && !"http".equals(type)) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] clientSecret 对 stdio 忽略（CC addCommand.ts:240-249 警告语义）");
            }
            return;
        }
        String url = config.get("url") == null ? "" : String.valueOf(config.get("url"));
        Map<String, String> headers = headersFromConfig(config);
        String serverKey = McpOAuth.getServerKey(req.name(), type, url, headers);
        mcpOAuthTokenService.saveClientSecret(serverKey, req.clientSecret());
        log.info("[McpServerService] clientSecret 已落 keychain server={} serverKey={}（不落 config/文件）",
            req.name(), serverKey);
    }

    /** config → entity 字段（stdio command/args/env；远程 url→command 列、headers→env 列）。 */
    private void applyServerConfig(McpServer s, Map<String, Object> config) {
        String type = String.valueOf(config.getOrDefault("type", "stdio"));
        if ("stdio".equals(type)) {
            Object cmd = config.get("command");
            s.setCommand(cmd == null ? null : String.valueOf(cmd));
            Object args = config.get("args");
            if (args instanceof List<?> list) {
                s.setArgs(serializeArgs(list.stream().map(String::valueOf).toList()));
            } else {
                s.setArgs(null);
            }
            Object env = config.get("env");
            if (env instanceof Map<?, ?> m) {
                Map<String, String> envMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    envMap.put(String.valueOf(en.getKey()), en.getValue() == null ? "" : String.valueOf(en.getValue()));
                }
                s.setEnv(serializeEnv(envMap));
            } else {
                s.setEnv(null);
            }
        } else {
            // 远程：url 存入 command 列（Java 现有契约），headers 存入 env 保留；
            // oauth 序列化入 env 保留键 __mcp_oauth__（DB 唯一源：env 镜像即 oauth 唯一权威，
            // list/get 经 oauthFromEnv 反解同键，不再依赖 .mcp.json 文件条目）
            Object url = config.get("url");
            s.setCommand(url == null ? null : String.valueOf(url));
            s.setArgs(null);
            Map<String, String> envMap = new LinkedHashMap<>();
            Object headers = config.get("headers");
            if (headers instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> en : m.entrySet()) {
                    envMap.put(String.valueOf(en.getKey()), en.getValue() == null ? "" : String.valueOf(en.getValue()));
                }
            }
            Object oauth = config.get("oauth");
            if (oauth instanceof Map<?, ?> om && !om.isEmpty()) {
                try {
                    // base64 编码：JSON 含逗号/冒号/引号，而 serializeEnv/deserializeEnv 是 naive
                    // 格式（按 ',' 与 ':' 切分），裸 JSON 值会被切碎无法反解；base64 无这些分隔符
                    String json = JSON.writeValueAsString(toStringObjectMap(om));
                    envMap.put(McpOAuth.ENV_OAUTH_MIRROR_KEY,
                        Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
                } catch (JsonProcessingException e) {
                    // oauth 值为简单类型（clientId/callbackPort/authServerMetadataUrl/xaa），序列化
                    // 不应失败；DB 唯一源（oauth 权威在 env 镜像键），镜像失败不阻断 upsert
                    // （best-effort），仅 fail-loud 日志
                    log.error("[McpServerService] oauth 序列化入 env 镜像失败 server={}（DB 唯一源，镜像失败则 oauth 不可回读）: {}",
                        s.getName(), e.getMessage(), e);
                }
            }
            s.setEnv(envMap.isEmpty() ? null : serializeEnv(envMap));
        }
    }

    /** create/update 响应扩展：url/headers/oauth 取 config；scope 从 saved DB 记录取（DB 唯一源，
     *  非入参 scope）；filePath 由 describeMcpConfigFilePath 描述（.mcp.json 仅 import 入口展示）。 */
    private McpServerDto withAddMeta(McpServerDto base, Map<String, Object> config, McpServer saved,
            List<String> warnings) {
        String scope = saved.getScope();
        String type = String.valueOf(config.getOrDefault("type", "stdio"));
        boolean remote = !"stdio".equals(type);
        String url = remote && config.get("url") != null ? String.valueOf(config.get("url")) : null;
        Map<String, String> headers = remote ? headersFromConfig(config) : null;
        Map<String, Object> oauth = config.get("oauth") instanceof Map<?, ?> om
            ? toStringObjectMap(om) : null;
        String filePath = configFileWriter.describeMcpConfigFilePath(scope, CwdResolution.getCwd());
        return new McpServerDto(base.id(), base.name(), base.command(), base.args(), base.env(),
            base.status(), base.lastError(), base.enabled(), base.createdAt(), base.type(),
            base.approvalStatus(), base.userFacingName(), base.channelPermissions(),
            url, headers, oauth, scope, filePath, warnings);
    }

    private static Map<String, String> headersFromConfig(Map<String, Object> config) {
        if (config.get("headers") instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            return out;
        }
        return Map.of();
    }

    private static Map<String, String> headersFromEnv(Map<String, String> env) {
        // 剥除 __mcp_oauth__ 镜像保留键（McpOAuth.headersOnly）：env 同时承载 headers + oauth 镜像，
        // headers 语义视图必须只含真实 headers（否则 serverKey/OAuth 流会把保留键当 header 消费）
        return McpOAuth.headersOnly(env);
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** DB env 镜像键 {@code __mcp_oauth__} 反解 → oauth Map（applyServerConfig 写入的 base64(JSON)）。 */
    private static Map<String, Object> oauthFromEnv(Map<String, String> env) {
        if (env == null) {
            return null;
        }
        String raw = env.get(McpOAuth.ENV_OAUTH_MIRROR_KEY);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            JsonNode node = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (node != null && node.isObject()) {
                return JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
            }
            return null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] __mcp_oauth__ 镜像反解失败 → null（DB 唯一源，无文件兜底）: {}",
                    e.getMessage());
            }
            return null;
        }
    }

    private static boolean hasOAuthFlags(McpCreateRequest req) {
        return req.clientSecret() != null || (req.oauth() != null
            && (req.oauth().clientId() != null || req.oauth().callbackPort() != null
                || Boolean.TRUE.equals(req.oauth().xaa())));
    }

    /** CC looksLikeUrl（addCommand.ts:128-133）。 */
    private static boolean looksLikeUrl(String command) {
        if (command == null) {
            return false;
        }
        return command.startsWith("http://") || command.startsWith("https://")
            || command.startsWith("localhost") || command.endsWith("/sse") || command.endsWith("/mcp");
    }

    /**
     * [S08 F2] pluginSource 解析器装配 · 供 {@link #start()}/{@link #startEnabledBatch()}
     * 注入 {@link McpToolPool#setPluginSourceResolver}。
     *
     * <p>未装配 PluginMcpIntegration（测试直构/插件域禁用）→ 恒 null → gate 门序[4] 对
     * plugin-kind entry fail-closed（对齐 CC pluginSource undefined fail，McpToolPool:345-349）。
     */
    public static java.util.function.Function<String, String> pluginSourceResolver(
            com.nexusai.application.agent.plugin.PluginMcpIntegration integration) {
        return name -> integration == null ? null : integration.pluginSourceFor(name);
    }

    /**
     * [S08 F3 供给] 插件 MCP servers 落库 · 对齐 CC config.ts 全链（Q-09=C：DB 唯一运行时源）。
     *
     * <p>消费 {@link PluginMcpIntegration#extractMcpServersFromPlugins} 产物（scoped
     * {@code plugin:name:server}，scope=dynamic），逐项 upsert：enabled=true、approval=approved
     * （插件 MCP 供给即视为已审批 —— CC 插件加载链无审批门，审批门仅 .mcp.json 管理面）。
     *
     * @param scopedServers scoped server 名 → 落库信息（含 DB 形态 config Map）
     */
    public void upsertPluginMcpServers(
            Map<String, com.nexusai.application.agent.plugin.PluginMcpIntegration.ScopedMcpServerInfo> scopedServers) {
        if (scopedServers == null) {
            return;
        }
        int upserted = 0;
        for (Map.Entry<String, com.nexusai.application.agent.plugin.PluginMcpIntegration.ScopedMcpServerInfo> e
                : scopedServers.entrySet()) {
            Map<String, Object> config = e.getValue().config();
            if (config == null || config.isEmpty()) {
                continue;
            }
            upsertServer(e.getKey(), config, "dynamic", "approved", true);
            upserted++;
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] 插件 MCP servers 落库 {} 个（Q-09=C DB 唯一运行时源）", upserted);
        }
    }

    // ============== start / stop / test ==============

    public McpServerDto start(String id) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        McpServer s = r.toDomain();
        // T6/T7 启停门控：pending 审批未过 → 拒绝；enabled=false → 拒绝（409，全局 handler 转 Conflict）。
        // pending 语义对齐 CC getProjectMcpServerStatus pending（utils.ts:351-406，前端弹窗确认前不可用）。
        if ("pending".equals(s.getApprovalStatus())) {
            log.warn("[McpServerService] start rejected server={}: pending approval", s.getName());
            throw new ConflictException("MCP server " + s.getName()
                + " is pending approval; cannot start. Approve it first.");
        }
        if (!Boolean.TRUE.equals(s.getEnabled())) {
            log.warn("[McpServerService] start rejected server={}: disabled", s.getName());
            throw new ConflictException("MCP server " + s.getName() + " is disabled; cannot start");
        }
        try {
            // P2-15: 每次启动注入 MCP_SKILLS 门控 + skill 池刷新回调（对齐 CC
            // feature('MCP_SKILLS') useManageMCPConnections.ts:718 + updateServer :731-738；
            // 与 P2-9 ToolRegistrationConfig 的 mcpServerService.setMcpSkillsGate 同源）。
            mcpToolPool.setMcpSkillsGate(mcpSkillsGate);
            // 拍板#2: 刷新回调覆盖 skills + prompts 两池（CC :731-738 updateServer({commands})）
            mcpToolPool.setSkillPoolRefresher(this::refreshMcpCommands);
            // 拍板#2: prompts/list_changed → 刷新 prompt 池（CC :688-691 updateServer({commands})）
            mcpToolPool.setPromptPoolRefresher(this::refreshMcpPromptCommands);
            // S04 (B4): tools/list_changed → 刷新 LLM 工具池（CC :656 updateServer({...client, tools: newTools})）
            mcpToolPool.setToolsPoolRefresher(this::refreshMcpTools);
            // [S3 needs-auth] OAuth 成功后真实工具替换回调（对齐 CC McpAuthTool.ts:140-161 setAppState 前缀替换）
            mcpToolPool.setMcpAuthToolSwapHandler(this::replaceServerToolsAfterAuth);
            // P3-5: 接线 skill-search 索引清除器（组合根 · 镜像 CC feature('EXPERIMENTAL_SKILL_SEARCH')
            //   useManageMCPConnections.ts:27-30 require-based 间接）——把 SkillDiscoveryPrefetch
            //   clearSkillIndexCache 委托为 Runnable；prefetch 未注入（生产默认）→ no-op。
            mcpToolPool.setSkillIndexClearer(skillDiscoveryPrefetch != null
                ? skillDiscoveryPrefetch::clearSkillIndexCache
                : null);
            // [S08 F2 接线] channel 生产链路：注入真实 pluginSource 解析器（serverName →
            // name@marketplace）。CC mcpPluginIntegration.ts:354 addPluginScopeToServers 注入
            // pluginSource（gate 门序[4] marketplace 校验消费，McpToolPool:1359）；Java 侧经
            // PluginMcpIntegration 运行期注册表供给；未装配（null）→ 恒 null → plugin-kind
            // entry 对 gate fail-closed（对齐 CC pluginSource undefined fail），不产生安全绕过。
            mcpToolPool.setPluginSourceResolver(pluginSourceResolver(pluginMcpIntegration));
            // [S07] session --channels 真实会话态注入：allowedChannelsSupplier = 当前请求会话
            // （RequestContext MDC）白名单数据源（ChannelSessionAllowlist，CC getAllowedChannels
            // state.ts:1676-1682 + setAllowedChannels state.ts:1680 的 --channels 等价物）。
            // 无会话/无白名单 → 空表 fail-closed（gate 门序[3 session] 恒 SESSION skip，
            // 安全默认与 CC「server 必须显式列入 --channels 才注册 handler」一致，channelNotification.ts:247-257）。
            channelNotificationGate.setAllowedChannelsSupplier(channelSessionAllowlist.currentRequestSupplier());
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] start 注入 allowedChannelsSupplier=真实会话态(ChannelSessionAllowlist): " +
                        "gate 门序[3 session] 按当前请求会话白名单判定（无会话/无白名单 → 空表 fail-closed）");
            }
            log.info("[McpServerService] start 接线 channel 生产链路：pluginSourceResolver={} " +
                    "allowedChannelsSupplier=真实会话态(ChannelSessionAllowlist) 已注入（McpToolPool @Autowired 接 " +
                    "ChannelNotification+ChannelNotificationGate，fail-loud）",
                pluginMcpIntegration == null ? "恒 null（PluginMcpIntegration 未装配）" : "PluginMcpIntegration");
            // P2-15: 重连不读旧 fetch 缓存（对齐 CC onclose :1389-1396 清缓存 ——
            // 断开/重连创建新连接对象，不清则下轮 fetch 返回旧连接陈旧结果）。
            mcpToolPool.invalidateFetchCaches(s.getName());
            List<McpToolPool.McpToolEntry> entries = mcpToolPool.assembleToolPool(s.getName(), transportConfig(s));
            entries.forEach(entry -> addMcpTool(entry.tool()));
            // P2-13: MCP skill 命令经 skill:// 资源发现生产
            // （对齐 CC getMcpToolsCommandsAndResources 四路并行 mcpSkills 支路 client.ts:2344-2356；
            //  skill:// 过滤 + write-once registry + fetchMcpSkillsForClient 对齐）
            // 拍板#2: 同时落库 fetchCommands 产物（prompts，无 loadedFrom）
            refreshMcpCommands(s.getName());
            s.setStatus(McpStatus.running.name());
            // [AM-CC-20260825] 默认 update(entity) ignoreNulls=true 跳过 null → last_error 残留
            //   （前端刷新仍显示旧 500，2026-08-25 实测）。空串（非 null）确保默认 update 写 DB 清；
            //   消费侧按 blank 判空（toDto 透出，前端 if(lastError) 空串不显示）。
            s.setLastError("");
            mcpServerMapper.update(McpServerRecord.fromDomain(s));
            log.info("[McpServerService] started server={} registeredTools={} poolSize={}",
                s.getName(), entries.size(), mcpToolCount());
            return toDto(s);
        } catch (Exception e) {
            s.setStatus(McpStatus.error.name());
            s.setLastError(e.getMessage());
            mcpServerMapper.update(McpServerRecord.fromDomain(s));
            log.error("[McpServerService] start failed server={}: {}", s.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to start MCP server " + s.getName(), e);
        }
    }

    public McpServerDto stop(String id) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        McpServer s = r.toDomain();
        try {
            mcpToolPool.teardown(s.getName());
            // [impl-I-4 F4 rework] 裸拼 `"mcp__"+name+"__"` → McpStringUtils.getMcpPrefix（规范化）：
            // server 名含空格/点/大写时注册名是规范化名（mcp__my_server__...），原始名裸拼前缀
            // 匹配不到 → 工具删不掉。refreshMcpSkillCommands 已用 getMcpPrefix，此处统一（对齐 T9）。
            String prefix = McpStringUtils.getMcpPrefix(s.getName());
            new ArrayList<>(mcpTools.keySet()).stream()
                .filter(name -> name.startsWith(prefix)).forEach(this::removeMcpTool);
            // P2-15: 断开清 skill 池（对齐 CC onclose :1393 清 fetchMcpSkillsForClient 缓存，
            // 停服后 getMcpSkillCommands() 立即返回空——SkillRegistry 的 MCP 技能即时消失）
            mcpSkillCommands.removeIf(cmd -> cmd != null && cmd.getName() != null
                && cmd.getName().startsWith(prefix));
            // 拍板#2: 断开同步清 prompt 命令池（fetchCommands 产物，CC onclose 清
            // fetchCommandsForClient 缓存 :1393；停服后 getMcpPromptCommandsForSearch 立即空）
            mcpPromptCommands.removeIf(cmd -> cmd != null && cmd.getName() != null
                && cmd.getName().startsWith(prefix));
            s.setStatus(McpStatus.stopped.name());
            s.setLastError("");  // [AM-CC-20260825] 同 start：ignoreNulls 跳过 null → 空串写 DB 清残留
            mcpServerMapper.update(McpServerRecord.fromDomain(s));
            log.info("[McpServerService] stopped server={} poolSize={}", s.getName(), mcpToolCount());
            return toDto(s);
        } catch (Exception e) {
            log.error("[McpServerService] stop failed server={}: {}", s.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to stop MCP server " + s.getName(), e);
        }
    }

    public TestConnectionResponse test(String id) {
        McpServerRecord r = mcpServerMapper.selectOneById(id);
        if (r == null) throw new NotFoundException("MCP server " + id + " not found");
        McpServer s = r.toDomain();
        McpTransport.TransportConfig config = transportConfig(s);
        McpTransport transport = mcpTransportFactory.create(config);
        long startedAt = System.nanoTime();
        try {
            transport.start(config);
            transport.sendRequest("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "nexusai-mcp-client", "version", "1.0.0")
            )).join();
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("[McpServerService] test succeeded server={} latencyMs={}", s.getName(), latencyMs);
            return new TestConnectionResponse(true, latencyMs, "ok", null);
        } catch (Exception e) {
            log.error("[McpServerService] test failed server={}: {}", s.getName(), e.getMessage(), e);
            throw new RuntimeException("Failed to test MCP server " + s.getName(), e);
        } finally {
            transport.close();
        }
    }

    // ============== [impl-I-4 T2/T3] 批连接 + 启动预取（CC getMcpToolsCommandsAndResources + prefetchAllMcpResources） ==============

    /**
     * 批连接全部 enabled server · 对齐 CC {@code getMcpToolsCommandsAndResources}
     * （client.ts:2226-2403）：local/remote 分组并发（McpToolPool 内 pMap slot 释放），
     * 逐 server 注册工具 + 刷新 skill 命令（复用 {@link #start} 内接线）。
     *
     * <p>disabled / pending 审批 server 预滤（对齐 CC isMcpServerDisabled 分区 :2244-2254）。
     * 单 server 失败 fail-soft（批连接空回调 → 不阻断其它 server）。
     *
     * @return 全部 server 处理完成后的 CompletableFuture（T3 启动预取异步 join）
     */
    public CompletableFuture<Void> startEnabledBatch() {
        // 复用 start() 内接线（P2-15 skill 门控 + P3-5 索引清除 + I-3 channel fail-closed）
        mcpToolPool.setMcpSkillsGate(mcpSkillsGate);
        // 拍板#2: 刷新回调覆盖 skills + prompts 两池（CC :731-738 updateServer({commands})）
        mcpToolPool.setSkillPoolRefresher(this::refreshMcpCommands);
        // 拍板#2: prompts/list_changed → 刷新 prompt 池（CC :688-691 updateServer({commands})）
        mcpToolPool.setPromptPoolRefresher(this::refreshMcpPromptCommands);
        // S04 (B4): tools/list_changed → 刷新 LLM 工具池（CC :656 updateServer({...client, tools: newTools})）
        mcpToolPool.setToolsPoolRefresher(this::refreshMcpTools);
        mcpToolPool.setSkillIndexClearer(skillDiscoveryPrefetch != null
            ? skillDiscoveryPrefetch::clearSkillIndexCache
            : null);
        // [S08 F2 接线] 真实 pluginSource 解析器（同 start()：PluginMcpIntegration 注册表，
        // 未装配 → 恒 null fail-closed，对齐 CC pluginSource undefined）
        mcpToolPool.setPluginSourceResolver(pluginSourceResolver(pluginMcpIntegration));
        // [S3 needs-auth] OAuth 成功后真实工具替换回调（对齐 CC McpAuthTool.ts:140-161 setAppState 前缀替换）
        mcpToolPool.setMcpAuthToolSwapHandler(this::replaceServerToolsAfterAuth);
        // [S07] 同 start()：真实会话态 allowedChannelsSupplier（无会话 → 空表 fail-closed）
        channelNotificationGate.setAllowedChannelsSupplier(channelSessionAllowlist.currentRequestSupplier());
        List<McpToolPool.McpServerConfigEntry> entries = new ArrayList<>();
        for (McpServerRecord r : mcpServerMapper.selectAll()) {
            McpServer s = r.toDomain();
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;              // enabled 门控（T6）
            if ("pending".equals(s.getApprovalStatus())) continue;           // 审批门控（T7）
            entries.add(new McpToolPool.McpServerConfigEntry(s.getName(), transportConfig(s)));
        }
        if (entries.isEmpty()) {
            log.info("[McpServerService] startEnabledBatch 无 enabled server，跳过");
            return CompletableFuture.completedFuture(null);
        }
        log.info("[McpServerService] startEnabledBatch 启动 {} 个 enabled server", entries.size());
        return mcpToolPool.getMcpToolsCommandsAndResources(entries,
            (name, config, tools, commands, resources) -> {
                // 注册工具（对齐 CC onConnectionAttempt → updateServer tools）
                tools.forEach(entry -> addMcpTool(entry.tool()));
                // 拍板#2: fetchCommands 产物落库（prompts）+ fetchMcpSkills 产物（skills）双池刷新
                // （CC onConnectionAttempt → updateServer({commands: [...mcpPrompts, ...mcpSkills]})，
                //  不再只 log commands.size()）
                refreshMcpCommands(name);
                // DB 状态 running（对齐 start() 末尾）
                McpServerRecord rec = mcpServerMapper.selectOneByName(name);
                if (rec != null) {
                    McpServer dom = rec.toDomain();
                    dom.setStatus(McpStatus.running.name());
                    dom.setLastError(null);
                    mcpServerMapper.update(McpServerRecord.fromDomain(dom));
                }
                log.info("[McpServerService] 批连接已注册 server={} tools={} commands={}",
                    name, tools.size(), commands.size());
            });
    }

    // ============== s19-P1-6: MCP tool pool (assemble_tool_pool) ==============

    /**
     * 注册一个 MCP 工具到当前活跃池 · 对齐 CC client.ts 工具发现 (tools/list 解析后注册).
     *
     * <p>同名工具会覆盖旧 entry (MCP server 重连场景). 返回是否成功注册.
     *
     * @param tool MCP 工具 (fully-qualified name mcp__{server}__{tool}; McpToolStub 已删 MCP-I-9 T6)
     * @return true = 新注册或覆盖; false = 参数为 null
     */
    public boolean addMcpTool(Tool tool) {
        if (tool == null) return false;
        String name = tool.name();
        boolean replaced = mcpTools.containsKey(name);
        mcpTools.put(name, tool);
        if (replaced) {
            log.info("[McpServerService] MCP tool '{}' replaced", name);
        } else {
            log.info("[McpServerService] MCP tool '{}' added (pool size now {})", name, mcpTools.size());
        }
        return true;
    }

    /**
     * 注销一个 MCP 工具 (MCP server 下线场景) · 对齐 CC client.ts cleanup().
     *
     * @param name 工具名
     * @return true = 成功移除; false = 工具不存在或参数为 null
     */
    public boolean removeMcpTool(String name) {
        if (name == null) return false;
        Tool removed = mcpTools.remove(name);
        if (removed != null) {
            log.info("[McpServerService] MCP tool '{}' removed (pool size now {})", name, mcpTools.size());
            return true;
        }
        return false;
    }

    /**
     * [S3 needs-auth] OAuth 认证成功后替换 server 工具池 · 对齐 CC McpAuthTool.ts:140-161
     * setAppState 前缀替换（{@code reject(prev.tools, t => t.name?.startsWith(prefix)) +
     * result.tools}）：移除 {@code mcp__<server>__*}（含伪工具 mcp__<server>__authenticate），
     * 追加重连后的真实工具，并回写 DB 状态 running。
     *
     * <p>由 {@link McpToolPool#setMcpAuthToolSwapHandler} 注入，McpToolPool.reconnectServerForAuth
     * 在 OAuth 完成后调用。
     *
     * @param serverName MCP server 名
     * @param entries    OAuth 后重连拉取的真实工具注册项
     */
    private void replaceServerToolsAfterAuth(String serverName, List<McpToolPool.McpToolEntry> entries) {
        String prefix = McpStringUtils.getMcpPrefix(serverName);
        new ArrayList<>(mcpTools.keySet()).stream()
            .filter(name -> name.startsWith(prefix)).forEach(this::removeMcpTool);
        entries.forEach(entry -> addMcpTool(entry.tool()));
        McpServerRecord rec = mcpServerMapper.selectOneByName(serverName);
        if (rec != null) {
            McpServer dom = rec.toDomain();
            dom.setStatus(McpStatus.running.name());
            dom.setLastError(null);
            mcpServerMapper.update(McpServerRecord.fromDomain(dom));
        }
        log.info("[McpServerService] OAuth 认证成功，已替换 server={} 真实工具={}", serverName, entries.size());
    }

    /**
     * s19-P1-6: 获取当前活跃 MCP 工具列表 · LlmAgentLoop 每轮 turn 顶部调用此方法刷新 pool.
     *
     * <p>对齐 CC tools.ts:345 assembleToolPool: 每轮 LLM 调用前从所有 MCP
     * server 重新收集 tools, 替换同名旧 entry.
     *
     * <p>S04 (B4): 返回按名排序的确定性快照（MCP 分区排序落点 · 对齐 CC assembleToolPool
     * 的 {@code allowedMcpTools.sort(byName)}，tools.ts:362-364）——原
     * {@code ConcurrentHashMap.values()} 顺序跨 JVM 非确定，导致 MCP 分区 schema 顺序非确定、
     * prompt-cache 键不稳定。池语义无顺序契约（字段 Javadoc :136 自证），消费方仅遍历。
     *
     * <p>返回空 list (无 MCP 上线) 时, LlmAgentLoop assembleToolPool 跳过 MCP 刷新,
     * builtin 工具不变.
     *
     * @return 当前 MCP 工具列表 (按名排序, 不可变 snapshot)
     */
    public List<Tool> getCurrentTools() {
        if (mcpTools.isEmpty()) return List.of();
        return mcpTools.values().stream()
            .sorted(java.util.Comparator.comparing(Tool::name))
            .toList();
    }

    /** 当前 MCP 工具数量 (测试用 + 监控). */
    public int mcpToolCount() {
        return mcpTools.size();
    }

    /**
     * [RES-L2 · C8] 获取指定 MCP server 的 instructions · 对齐 CC {@code ConnectedMCPServer.instructions}
     * (types.ts:189) + {@code client.getInstructions()} (client.ts:1160).
     *
     * <p>委托 {@link McpToolPool#getServerInstructions}（initialize 握手时提取并截断存储）。
     * 由 {@code LlmAgentLoop.buildMcpClients} 消费，将真实 instructions 填入
     * {@code McpClientRuntime.instructions}（[IMP-E1 DC-2] McpServerInfo 收敛 2 字段后，
     * instructions 由 mcpClients map 值承载），最终由 {@code mcp_instructions} 动态 section
     * (prompts.ts:579-604) 输出到 system prompt.
     *
     * @param serverName MCP server 名
     * @return instructions 字符串; 未连接 / 无 instructions 时返回 null
     */
    public String getServerInstructions(String serverName) {
        return mcpToolPool.getServerInstructions(serverName);
    }

    // ============== s19-P1-7: getMcpSkillCommands (CC commands.ts:547-559) ==============

    /**
     * MCP_SKILLS feature 门控供应 · CC original: {@code feature('MCP_SKILLS')} commands.ts:550/:558。
     *
     * <p>CC 生产 bundle 将该编译期常量折叠为 false（mcpSkills.ts DCE，探查-skill.md §2.1 concern #23），
     * 故 Java 默认 {@code () -> false} 对齐 CC 生产默认关（P1-9，2026-08-16 拍板：Java 默认关）。
     * 需启用时经 {@link #setMcpSkillsGate} 由 Spring 配置源注入（nexusai.skill.features.mcp-skills，
     * application.yml 默认 false）。gate 关 → {@link #getMcpSkillCommands()} 返回空
     * （CC :558 {@code return []}）。
     */
    private BooleanSupplier mcpSkillsGate = () -> false;

    /**
     * 注入 MCP_SKILLS 门控供应 · CC original: {@code feature('MCP_SKILLS')} commands.ts:550。
     * Spring 配置源接线（ToolRegistrationConfig {@code mcpServerService.setMcpSkillsGate}）。
     *
     * <p>POJO 兼容：未注入（null）→ 默认 false（P1-9，对齐 CC 生产默认关）。
     *
     * @param mcpSkillsGate MCP_SKILLS 开关供应；null 视为恒 false
     */
    public void setMcpSkillsGate(BooleanSupplier mcpSkillsGate) {
        this.mcpSkillsGate = mcpSkillsGate != null ? mcpSkillsGate : () -> false;
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] 注入 MCP_SKILLS 门控供应 (gate 注入后当前={})",
                this.mcpSkillsGate.getAsBoolean());
        }
    }

    /**
     * P1-17/X23: 获取 MCP 提供的 skill 类命令 · 对齐 CC {@code commands.ts:547-559 getMcpSkillCommands()}.
     *
     * <p>CC 真源（自验）：
     * <pre>
     * export function getMcpSkillCommands(mcpCommands: readonly Command[]): readonly Command[] {
     *   if (feature('MCP_SKILLS')) {
     *     return mcpCommands.filter(cmd =&gt;
     *       cmd.type === 'prompt' &amp;&amp; cmd.loadedFrom === 'mcp' &amp;&amp; !cmd.disableModelInvocation)
     *   }
     *   return []
     * }
     * </pre>
     *
     * <p>CC 端是<b>纯过滤器</b>（消费已构建的 Command[]，不生产）。Java 端对齐：
     * <ul>
     *   <li>feature('MCP_SKILLS') → {@link #mcpSkillsGate}（默认 false 对齐 CC 生产，Spring 配置源可开，P1-9）</li>
     *   <li>type === 'prompt' → {@code "prompt".equals(cmd.getType())}</li>
     *   <li>loadedFrom === 'mcp' → {@code CommandLoadedFrom.MCP.equals(cmd.getLoadedFrom())}
     *       （P2-21：独立 loadedFrom 字段判别，CC commands.ts:554；旧 source==MCP 会把
     *       JsonRpcMcpClient prompts（source='mcp' 但无 loadedFrom，client.ts:2072）误当 skill ——
     *       CC utils.ts:82-93 以 loadedFrom==='mcp' 区分 skill 与 prompt）</li>
     *   <li>!disableModelInvocation → {@code !Boolean.TRUE.equals(cmd.getDisableModelInvocation())}</li>
     * </ul>
     *
     * <p>P2-9 分离语义（CC commands.ts:541-546 「These live outside getCommands() so callers
     * that need MCP skills in their skill index thread them through separately」）：本方法是 MCP 技能
     * 的唯一出口（纯过滤入口），不生产——生产由 {@link #refreshMcpSkillCommands}（skill:// 资源发现，
     * CC fetchMcpSkillsForClient 对齐）承担并写入 {@link #mcpSkillCommands}；消费方
     * （SkillRegistry#findCommandIncludingMcp / getModelInvocableCommandsForListing）经 thread-in
     * 合并，不再并入 getAllCommands。
     *
     * @return MCP skill 命令列表（不可变）；gate 关 / 无 MCP skill 命令 / 全被过滤时为空 list
     */
    public List<Command> getMcpSkillCommands() {
        // CC commands.ts:550 if (feature('MCP_SKILLS')) { ... } :558 return []
        if (!mcpSkillsGate.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] getMcpSkillCommands 门控 MCP_SKILLS=关 → 返回空（CC commands.ts:558 return []）");
            }
            return List.of();
        }
        List<Command> result = mcpSkillCommands.stream()
            .filter(cmd -> cmd != null)
            // CC filter #1: cmd.type === 'prompt'
            .filter(cmd -> "prompt".equals(cmd.getType()))
            // CC filter #2: cmd.loadedFrom === 'mcp'（commands.ts:554；P2-21 独立 loadedFrom 判别）
            .filter(cmd -> CommandLoadedFrom.MCP.equals(cmd.getLoadedFrom()))
            // CC filter #3: !cmd.disableModelInvocation
            .filter(cmd -> !Boolean.TRUE.equals(cmd.getDisableModelInvocation()))
            .toList();
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] getMcpSkillCommands -> {} commands (skillPool={})",
                result.size(), mcpSkillCommands.size());
        }
        return result;
    }

    /**
     * SkillTool 搜索基座视图 · CC original: {@code SkillTool.getAllCommands(context)}
     * （SkillTool.ts:81-94）{@code context.getAppState().mcp.commands.filter(
     * cmd => cmd.type === 'prompt' && cmd.loadedFrom === 'mcp')}。
     *
     * <p>S3 修正（R2B-DEC-9，探查-skill-tool.md S3/§10 R1）：与 {@link #getMcpSkillCommands()}
     * （listing 视图，CC commands.ts:547-559）的差异在<b>无 {@code !disableModelInvocation} 过滤</b>
     * —— CC SkillTool 搜索基座不过滤 disableModelInvocation，技能保持可达，validateInput 命中后按
     * {@code foundCommand.disableModelInvocation} 返回 errorCode 4（SkillTool.ts:412-418）。若预过滤
     * （旧行为），技能不可达 → errorCode 2「Unknown skill」（:406-407），错误码语义偏移。
     * 消费方仅 {@link com.nexusai.application.agent.skill.SkillRegistry#findCommandIncludingMcp}
     * （SkillTool 的 validateInput/checkPermissions/call 三处搜索基座，SkillTool.ts:399-402/:446-447/:615-616）。
     *
     * <p>MCP_SKILLS gate 行为与 {@link #getMcpSkillCommands()} 一致（gate 关 → 空：CC getAllCommands
     * 的 mcpSkills 源为空 → 退化为纯本地）。
     *
     * @return MCP skill 命令列表（含 disableModelInvocation=true；不含 type!=prompt / loadedFrom!=mcp）；
     *         gate 关 / 无 MCP skill 命令时为空 list
     */
    public List<Command> getMcpSkillCommandsForSearch() {
        if (!mcpSkillsGate.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] getMcpSkillCommandsForSearch 门控 MCP_SKILLS=关 → 返回空（CC SkillTool.ts:81-94 源为空）");
            }
            return List.of();
        }
        List<Command> result = mcpSkillCommands.stream()
            .filter(cmd -> cmd != null)
            // CC SkillTool.ts:89 filter: cmd.type === 'prompt' && cmd.loadedFrom === 'mcp'
            .filter(cmd -> "prompt".equals(cmd.getType()))
            .filter(cmd -> CommandLoadedFrom.MCP.equals(cmd.getLoadedFrom()))
            // S3: 无 !disableModelInvocation 过滤（CC SkillTool.ts:81-94；errorCode 4 后置 :412-418）
            .toList();
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] getMcpSkillCommandsForSearch -> {} commands (skillPool={})",
                result.size(), mcpSkillCommands.size());
        }
        return result;
    }

    // ============== P2-13: skill:// 资源发现生产 MCP skill 命令 (CC fetchMcpSkillsForClient) ==============

    /**
     * 刷新单个 server 的 MCP skill 命令池 · 对齐 CC onConnectionAttempt 覆盖语义
     * （client.ts:2179 {@code commands = [...mcpCommands, ...mcpSkills]}）。
     *
     * <p>P2-13 语义：重写为 skill:// 资源发现生产（CC client.ts:2174-2176
     * {@code feature('MCP_SKILLS') && supportsResources ? fetchMcpSkillsForClient!(client) : []}，
     * resources capability 由 {@link McpToolPool#fetchMcpSkills} 内部判）。
     * 已删除旧 X23 桥接（fetchResources 预热冗余 + prompts/list→skill 错误提升——CC
     * fetchCommandsForClient client.ts:2054-2095 产物 source='mcp' 但无 loadedFrom='mcp'，
     * getMcpSkillCommands commands.ts:551-556 会排除——CC 中 prompts 不是 skill，
     * 只有 skill:// 资源才是）。
     */
    private void refreshMcpSkillCommands(String serverName) {
        // 重连/重启场景：先移除该 server 的旧 skill 命令（对齐 CC connection attempt 覆盖）
        String prefix = McpStringUtils.getMcpPrefix(serverName);
        mcpSkillCommands.removeIf(cmd -> cmd != null && cmd.getName() != null
            && cmd.getName().startsWith(prefix));

        // CC client.ts:2174-2176 feature('MCP_SKILLS') && supportsResources 门控生产
        if (mcpSkillsGate.getAsBoolean()) {
            List<Command> skills = mcpToolPool.fetchMcpSkills(serverName);
            mcpSkillCommands.addAll(skills);
            if (log.isDebugEnabled()) {
                log.debug("[McpServerService] 从 skill:// 资源发现 MCP 技能 server={} skills={}",
                    serverName, skills.size());
            }
        }
    }

    /**
     * 刷新单个 server 的 MCP 命令池（skills + prompts）· 对齐 CC onConnectionAttempt 覆盖语义
     * （client.ts:2179 {@code commands = [...mcpCommands, ...mcpSkills]} —— mcpSkills 段经
     * {@link #refreshMcpSkillCommands}，mcpCommands 段经 {@link #refreshMcpPromptCommands}）。
     *
     * <p>拍板#2（FIX-A2）：CC {@code AppState.mcp.commands} 同时承载 prompts（fetchCommands
     * 产物）与 skills（fetchMcpSkills 产物），Java 端两池分别落库。本方法作为
     * {@link McpToolPool#setSkillPoolRefresher} 的组合回调，使 resources/list_changed
     * 通知（McpToolPool.handleResourcesListChanged :2439）与单/批连接路径均刷新两池。
     */
    private void refreshMcpCommands(String serverName) {
        refreshMcpPromptCommands(serverName);
        refreshMcpSkillCommands(serverName);
    }

    /**
     * S04 (B4): 刷新单个 server 的 LLM 工具池 · 对齐 CC updateServer 全状态刷新
     * （useManageMCPConnections.ts:656 tools/list_changed 处理器 {@code updateServer({...client,
     * tools: newTools})}；前缀组替换 :255-258 {@code [...reject(mcp.tools, t =>
     * t.name?.startsWith(prefix)), ...tools]}）。
     *
     * <p>由 {@link McpToolPool#setToolsPoolRefresher} 注入（McpToolPool.handleToolsListChanged
     * 已删 toolsCache，此处 {@link McpToolPool#fetchTools} 缓存命中直接取新集合）。
     *
     * @param serverName MCP server 名
     */
    private void refreshMcpTools(String serverName) {
        // 重连/重启场景：先移除该 server 的旧工具（对齐 CC updateServer reject 旧前缀组 :255-258）
        String prefix = McpStringUtils.getMcpPrefix(serverName);
        new ArrayList<>(mcpTools.keySet()).stream()
            .filter(name -> name.startsWith(prefix)).forEach(this::removeMcpTool);

        // CC :655-656 fetchToolsForClient(client) + updateServer({...client, tools: newTools})
        List<McpToolPool.McpToolEntry> entries = mcpToolPool.fetchTools(serverName);
        entries.forEach(entry -> addMcpTool(entry.tool()));
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] tools/list_changed 重建 LLM 工具池 server={} tools={}",
                serverName, entries.size());
        }
    }

    // ============== 拍板#2: prompts/list 生产 MCP prompt 命令 (CC fetchCommandsForClient) ==============

    /**
     * 刷新单个 server 的 MCP prompt 命令池 · 对齐 CC onConnectionAttempt 覆盖语义
     * （client.ts:2179 {@code commands = [...mcpCommands, ...mcpSkills]} 中 mcpCommands 段）。
     *
     * <p>拍板#2（FIX-A2）：CC {@code fetchCommandsForClient} 产物（client.ts:2054-2095，
     * source='mcp' 无 loadedFrom）入 {@code AppState.mcp.commands} 可作 slash 命令；
     * Java 端不再只在 {@code startEnabledBatch} onConnectionAttempt 只 log
     * {@code commands.size()}，而落库到 {@link #mcpPromptCommands}。
     *
     * <p>与 {@link #refreshMcpSkillCommands} 的差异：<b>不 gate</b>——CC client.ts:2173
     * {@code fetchCommandsForClient(client)} 恒执行（仅 fetchMcpSkillsForClient 受
     * {@code feature('MCP_SKILLS')} 门控，:2174-2176）。
     */
    private void refreshMcpPromptCommands(String serverName) {
        // 重连/重启场景：先移除该 server 的旧 prompt 命令（对齐 CC connection attempt 覆盖）
        String prefix = McpStringUtils.getMcpPrefix(serverName);
        mcpPromptCommands.removeIf(cmd -> cmd != null && cmd.getName() != null
            && cmd.getName().startsWith(prefix));

        // CC client.ts:2173 fetchCommandsForClient 恒执行（无 gate）
        List<Command> prompts = mcpToolPool.fetchCommands(serverName);
        mcpPromptCommands.addAll(prompts);
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] 从 prompts/list 落库 MCP prompt 命令 server={} prompts={}",
                serverName, prompts.size());
        }
    }

    /**
     * 拍板#2: MCP prompt 命令搜索视图（无 loadedFrom）· CC original:
     * {@code AppState.mcp.commands} 中 fetchCommandsForClient 产物段（client.ts:2054-2095）。
     *
     * <p>搜索基座消费方 {@link com.nexusai.application.agent.skill.SkillRegistry#findCommandIncludingMcp}
     * 经 thread-in 合并（拍板#2：搜索基座含 MCP prompt，无 loadedFrom）。与
     * {@link #getMcpSkillCommandsForSearch()}（loadedFrom='mcp' 技能搜索视图）并存——
     * prompts 与 skills 是两独立池，拍板#2 要求 findCommandIncludingMcp 两者都可达。
     *
     * <p><b>不 gate</b>：CC fetchCommandsForClient 恒执行（client.ts:2173），prompt 命令
     * 始终在 AppState.mcp.commands（仅 mcpSkills 受 MCP_SKILLS 门控）。
     *
     * @return MCP prompt 命令列表（type=prompt，无 loadedFrom）；无 prompt 命令时为空 list
     */
    public List<Command> getMcpPromptCommandsForSearch() {
        List<Command> result = mcpPromptCommands.stream()
            .filter(cmd -> cmd != null)
            // CC :2057 type: 'prompt'（fetchCommandsForClient 产物恒 prompt 型）
            .filter(cmd -> "prompt".equals(cmd.getType()))
            .toList();
        if (log.isDebugEnabled()) {
            log.debug("[McpServerService] getMcpPromptCommandsForSearch -> {} commands (promptPool={})",
                result.size(), mcpPromptCommands.size());
        }
        return result;
    }

    // ============== helpers ==============

    private McpTransport.TransportConfig transportConfig(McpServer server) {
        List<String> args = deserializeArgs(server.getArgs());
        return new McpTransport.TransportConfig(
            server.getCommand(), args == null ? List.of() : args,
            deserializeEnv(server.getEnv()), null,
            server.getName(),  // [Session H P2-5] serverName · 401 → McpAuthError 降级目标标识
            server.getType()); // [MCP-I-1 T8] type · 显式传输类型（工厂按 type 分发，不再 URL 前缀推断）
    }

    /**
     * DTO 组装 · 远程 server 反解 url（command 列）/ headers（env 列，剥 {@code __mcp_oauth__}）；
     * oauth 权威在 DB env 镜像键反解（applyServerConfig 写入 {@code __mcp_oauth__}，DB 唯一源，
     * 无 scope 文件兜底）；scope 从 DB scope 列取（V59）。
     */
    private McpServerDto toDto(McpServer s) {
        String type = s.getType() == null ? "stdio" : s.getType();
        boolean remote = !"stdio".equals(type);
        String url = null;
        Map<String, String> headers = null;
        Map<String, Object> oauth = null;
        Map<String, String> dtoEnv = null;
        if (remote) {
            url = s.getCommand();
            Map<String, String> env = deserializeEnv(s.getEnv());
            if (env != null && !env.isEmpty()) {
                headers = headersFromEnv(env);
                // DB 唯一源：oauth 权威在 env 镜像键（无文件兜底分支）
                oauth = oauthFromEnv(env);
            }
            // DTO env 剥除 __mcp_oauth__ 内部镜像键（headers/oauth 由独立字段承载，API 不暴露 DB 内部键）
            dtoEnv = env == null ? null : McpOAuth.headersOnly(env);
        } else {
            dtoEnv = deserializeEnv(s.getEnv());
        }
        return new McpServerDto(
            s.getId(),
            s.getName(),
            s.getCommand(),
            deserializeArgs(s.getArgs()),
            dtoEnv,
            s.getStatus() != null ? McpStatus.valueOf(s.getStatus()) : McpStatus.stopped,
            s.getLastError(),
            Boolean.TRUE.equals(s.getEnabled()),
            parseDateTime(s.getCreatedAt()),
            s.getType(),
            s.getApprovalStatus(),
            // [gap31] userFacingName = server 名（CC client.ts:1972-1976 的 client.name 等价）
            s.getName(),
            // [gap31] channelPermissions = 全局 feature 值（CC channelPermissions.ts:36-38，
            //   GrowthBook 'tengu_harbor_permissions' 默认 false）；未装配 → 默认 false
            channelPermissionFeature != null && channelPermissionFeature.isEnabled(),
            url,
            headers,
            oauth,
            s.getScope(),  // DB 唯一源：scope 列（V59）
            null,          // filePath（create/update 响应经 withAddMeta 填）
            null           // warnings（create/update 响应经 withAddMeta 填）
        );
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String serializeArgs(List<String> args) {
        if (args == null || args.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String a : args) {
            if (!first) sb.append(",");
            sb.append("\"").append(a == null ? "" : a.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            first = false;
        }
        return sb.append("]").toString();
    }

    private static List<String> deserializeArgs(String json) {
        if (json == null || json.isBlank()) return null;
        String s = json.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        if (s.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim().replaceAll("^\"|\"$", "");
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    private static String serializeEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":\"")
              .append(e.getValue() == null ? "" : e.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            first = false;
        }
        return sb.append("}").toString();
    }

    private static Map<String, String> deserializeEnv(String json) {
        if (json == null || json.isBlank()) return null;
        String s = json.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1);
        if (s.isBlank()) return new HashMap<>();
        Map<String, String> result = new HashMap<>();
        for (String pair : s.split(",")) {
            int idx = pair.indexOf(':');
            if (idx < 0) continue;
            String key = pair.substring(0, idx).trim().replaceAll("^\"|\"$", "");
            String val = pair.substring(idx + 1).trim().replaceAll("^\"|\"$", "");
            if (!key.isEmpty()) result.put(key, val);
        }
        return result;
    }

    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
