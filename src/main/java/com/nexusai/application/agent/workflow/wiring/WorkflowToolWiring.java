package com.nexusai.application.agent.workflow.wiring;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.WorkflowTool;
import com.nexusai.application.agent.workflow.WorkflowServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorkflowTool 装配门 · CC original: {@code wiring.ts}
 * (Open-ClaudeCode/src/workflow/wiring.ts:1-66)。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 行为意图）</b>：CC 中
 * {@code tools.ts:129-133 createWorkflowToolCore()}（feature-gated）在<b>模块加载期</b>被调用，
 * 若立即解析 ports 会触发 service 实例化 → 模块级副作用（getProjectRoot 等）在 bootstrap
 * 完成前拿到错误路径（wiring.ts:14-19 注释）。故 ports 解析<b>懒到第一次真实方法调用</b>
 * （wiring.ts:23-28 descriptor 缓存）。Tool 对象本身经 {@code createWorkflowToolCore} 缓存为
 * <b>单例</b>（wiring.ts:59-65：tools.ts 注册与 PermissionRequest 按引用匹配需同一实例）。
 *
 * <p><b>双单例缓存</b>（wiring.ts:60-65）：
 * <ol>
 *   <li>{@link #createWorkflowToolCore} 进程级 cached Tool（double-checked locking）。</li>
 *   <li>{@link LazyDescriptorWorkflowTool#descriptor()} 实例级 cachedDescriptor（懒解析
 *       service.ports() → 缓存 WorkflowTool）。</li>
 * </ol>
 *
 * <p><b>Java 落点说明</b>：本类提供 CC {@code createWorkflowToolCore} 等价单例访问器。
 * 现有注册点 {@code ToolRegistrationConfig}（:260-261）仍以 {@code WorkflowTool::new} 每次新建；
 * 后续接线可改为经 {@link #createWorkflowToolCore(FeatureFlags)} 共享同一实例（引用匹配）。
 * 真正执行时的 service 解析仍由 {@link WorkflowTool#execute} 内完成（fail-loud 不变式保留在
 * execute 路径，见 WorkflowServiceImpl.getWorkflowService 抛 IllegalStateException）。
 */
public final class WorkflowToolWiring {

    private static final Logger log = LoggerFactory.getLogger(WorkflowToolWiring.class);

    /** 进程级单例缓存 · CC original: {@code cached} (wiring.ts:60)。 */
    private static volatile Tool cached;

    private WorkflowToolWiring() {
    }

    /**
     * 单例 Tool 访问器 · CC original: {@code createWorkflowToolCore()} (wiring.ts:62-65)。
     * 默认 FeatureFlags（ALL_DISABLED）；首次调用后缓存，后续调用返回同一实例。
     *
     * @return 进程级 WorkflowTool 单例
     */
    public static Tool createWorkflowToolCore() {
        return createWorkflowToolCore(null);
    }

    /**
     * 单例 Tool 访问器（带 feature 门控）· CC original: {@code createWorkflowToolCore()}
     * (wiring.ts:62-65)。首次调用以传入 flags 构建并缓存；后续调用返回既有实例（flags 不再生效，
     * 与 CC 模块级缓存一致——首次构建即定型）。
     *
     * @param featureFlags WORKFLOW_SCRIPTS 门控 flags（null → ALL_DISABLED）
     * @return 进程级 WorkflowTool 单例
     */
    public static Tool createWorkflowToolCore(FeatureFlags featureFlags) {
        Tool t = cached;
        if (t == null) {
            synchronized (WorkflowToolWiring.class) {
                t = cached;
                if (t == null) {
                    t = buildWorkflowTool(featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED);
                    cached = t;
                    if (log.isDebugEnabled()) {
                        log.debug("WorkflowToolWiring 首次构建单例 Tool（wiring.ts:62-65）");
                    }
                }
            }
        }
        return t;
    }

    /**
     * 构建 Tool · CC original: {@code buildWorkflowTool()} (wiring.ts:21-57)。
     * Java 侧"descriptor" = {@link WorkflowTool}（已直接实现 Tool 接口），经懒包装延迟构建。
     */
    private static Tool buildWorkflowTool(FeatureFlags featureFlags) {
        return new LazyDescriptorWorkflowTool(featureFlags);
    }

    /**
     * 测试清理：重置进程单例缓存 · 对齐 CC {@code __resetWorkflowServiceForTests()}
     * (service.ts:321-323) 同型（防跨用例污染）。
     */
    static void resetForTests() {
        synchronized (WorkflowToolWiring.class) {
            cached = null;
        }
    }

    /**
     * 懒 descriptor 包装 · CC original: wiring.ts:22-29（descriptor 闭包 + cachedDescriptor）。
     *
     * <p>{@code descriptor()} 首次调用懒解析 {@code service.ports()}（wiring.ts:25-26）并缓存
     * WorkflowTool；此后命中缓存。ports 解析失败（Spring 未就绪）时不阻断——真正服务解析在
     * {@link WorkflowTool#execute} 内完成，本处仅作"首次真实方法调用才初始化"的懒语义对齐。
     */
    static final class LazyDescriptorWorkflowTool implements Tool {

        private final FeatureFlags featureFlags;
        /** descriptor 缓存 · CC original: cachedDescriptor (wiring.ts:22)。 */
        private volatile WorkflowTool cachedDescriptor;

        LazyDescriptorWorkflowTool(FeatureFlags featureFlags) {
            this.featureFlags = featureFlags;
        }

        /** 懒解析 descriptor · CC original: wiring.ts:23-28。 */
        private WorkflowTool descriptor() {
            WorkflowTool d = cachedDescriptor;
            if (d == null) {
                synchronized (this) {
                    d = cachedDescriptor;
                    if (d == null) {
                        // wiring.ts:25-26 懒解析 service.ports()（触发进程单例就绪检查）
                        try {
                            WorkflowServiceImpl.getWorkflowService().ports();
                        } catch (IllegalStateException e) {
                            // Spring 未装配（测试/启动早期）：延后解析，不阻断 descriptor 构建
                            //（真正解析在 WorkflowTool.execute 内 fail-loud）
                            if (log.isDebugEnabled()) {
                                log.debug("WorkflowToolWiring: 服务未就绪，ports 解析延后（wiring.ts:25 懒语义）：{}",
                                        e.getMessage());
                            }
                        }
                        d = new WorkflowTool(featureFlags);
                        cachedDescriptor = d;
                    }
                }
            }
            return d;
        }

        @Override
        public String name() {
            return WorkflowTool.NAME;
        }

        @Override
        public String description() {
            return descriptor().description();
        }

        @Override
        public JsonNode inputSchema() {
            return descriptor().inputSchema();
        }

        @Override
        public boolean isEnabled() {
            return descriptor().isEnabled();
        }

        @Override
        public boolean isReadOnly(JsonNode input) {
            return descriptor().isReadOnly(input);
        }

        @Override
        public boolean isConcurrencySafe(JsonNode input) {
            return descriptor().isConcurrencySafe(input);
        }

        @Override
        public String renderToolUseMessage(JsonNode input) {
            return descriptor().renderToolUseMessage(input);
        }

        @Override
        public UnknownKeysPolicy unknownKeysPolicy() {
            return descriptor().unknownKeysPolicy();
        }

        @Override
        public AgentToolResult<?> execute(ToolUseBlock call) {
            return descriptor().execute(call);
        }

        @Override
        public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
            return descriptor().execute(call, ctx);
        }
    }
}
