package com.nexusai.application.agent.computeruse;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import java.util.List;

/**
 * computer-use 工具注册配置 · Phase 0 的 2 个工具
 * （{@code mcp__computer-use__screenshot} / {@code mcp__computer-use__left_click}）
 * + Phase 1a 的第 3 个（{@code mcp__computer-use__request_access}）
 * + <b>Phase 1c 的 4 个</b>（{@code type} / {@code key} / {@code wait} /
 * {@code list_granted_applications}）
 * + <b>Phase 2a 的 17 个</b>（{@code zoom} / {@code double_click} / {@code triple_click} /
 * {@code right_click} / {@code middle_click} / {@code scroll} / {@code left_click_drag} /
 * {@code mouse_move} / {@code open_application} / {@code switch_display} /
 * {@code read_clipboard} / {@code write_clipboard} / {@code cursor_position} /
 * {@code hold_key} / {@code left_mouse_down} / {@code left_mouse_up} /
 * {@code computer_batch}）
 * + <b>Phase 2b 的 11 个 Windows 专属</b>（{@code window_management} / {@code click_element} /
 * {@code type_into_element} / {@code open_terminal} / {@code bind_window} /
 * {@code activate_window} / {@code prompt_respond} / {@code status_indicator} /
 * {@code virtual_keyboard} / {@code virtual_mouse} / {@code mouse_wheel}）
 * ⇒ <b>合计 35 个</b> = CCB 全集（{@code packages/@ant/computer-use-mcp/src/tools.ts}
 * 的 {@code buildComputerUseTools} 数组，含其 {@code caps.platform === 'win32'} 条件块
 * 里的 11 个，位置紧跟 {@code open_application} 之后、{@code switch_display} 之前）。
 *
 * <p><b>平台分派（macOS 执行端）</b>：执行端按 {@link ComputerUsePlatform#detect()}
 * （{@code os.name}）选择 —— Windows ⇒ JNA 直调；macOS ⇒ 进程外
 * {@code osascript -l JavaScript} / {@code screencapture}（CCB 的 macOS 路线）；
 * 其它（Linux 等）⇒ <b>明确抛不支持</b>（fail loud，不静默降级）。
 * <br>⚠️ <b>macOS 上只注册 24 个工具</b>：那 11 个 Windows 专属工具在 macOS 上不存在
 * ⇒ 与 CCB 一致（CCB 的 {@code caps.platform === 'win32' ? [...] : []} 条件块
 * ⇒ <b>CCB 的 macOS 工具面也是 24 个</b>）。
 *
 * <p><b>注册通道</b>：与 {@code BrowserMcpToolConfig}（{@code :46-60}）同款 —— 本 {@code @Bean}
 * 返回类型是 {@code List<Tool>}（非 {@code Tool}），Spring 集合注入 {@code @Autowired List<Tool>}
 * 不展开其元素 → 由 {@link com.nexusai.application.agent.tool.ToolRegistry} 以
 * {@code @Resource(name = "computerUseTools")} 显式注入并在 {@code init()} 内
 * {@code registerAll(...)} 逐个注册。
 *
 * <p><b>总开关</b>：yml {@code nexusai.feature.computer-use}（<b>默认 false</b>）。
 * false → 返回空 List ⇒ 所有 {@code mcp__computer-use__*} 工具<b>不注册</b>
 * ⇒ 模型 tools 列表里没有它们（与 {@code BrowserMcpToolConfig} 的 {@code browser-mcp} 门同款行为）。
 *
 * <p><b>为什么默认 false</b>：computer-use 能控制用户桌面 —— 生产默认关，
 * 对齐 CC 的 {@code feature('CHICAGO_MCP')} 生产默认 false 口径。
 * （CCB 的 GrowthBook 开关默认 true，是本仓<b>有意的出包安全选择</b>，非行为缺陷。）
 *
 * <p><b>[Phase 1c] 坐标模式来自 {@link ComputerUseGates}</b>（不再是硬编码 {@code PIXELS}）：
 * 坐标模式<b>首次读取即冻结</b>（CCB {@code gates.ts:66-72}）⇒ 工具描述（构造期烤进
 * inputSchema）与 {@code scaleCoord}（执行期换算）读<b>同一个</b>值。
 */
@Configuration
public class ComputerUseToolConfig {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseToolConfig.class);

    /**
     * computer-use 总开关 · yml {@code nexusai.feature.computer-use}（默认 false）。
     */
    @Value("${nexusai.feature.computer-use:false}")
    private boolean computerUseEnabled;

    /**
     * 子开关（8 个）· <b>坐标模式</b>与 {@code mouseAnimation} 的来源。
     *
     * <p>用<b>字段注入</b>（{@code required = false}）而不是构造器参数：本类的
     * {@code computerUseTools(...)} 有多个重载被单测直接 {@code new} 出来调用
     * （见 {@code ComputerUseToolConfigTest}）⇒ 构造器注入会打破既有测试。
     * <br>null（未注入 / 单测路径）⇒ 回落 {@link ComputerUseCoordinateMode#PIXELS}。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ComputerUseGates gates;

    /**
     * 截图几何快照存储（按 sessionId 分桶）· 供点击工具按「截图时刻」换算坐标。
     *
     * @return 存储实例
     */
    @Bean
    public ComputerUseGeometryStore computerUseGeometryStore() {
        return new ComputerUseGeometryStore();
    }

    /**
     * 会话级 computer-use 授权位读写器（承载 {@code sessions.computer_use_grants} 列）。
     *
     * @param sessionMapper 会话 mapper
     * @return 授权位存储
     */
    @Bean
    public ComputerUseGrantStore computerUseGrantStore(SessionMapper sessionMapper) {
        return new ComputerUseGrantStore(sessionMapper);
    }

    /**
     * computer-use 跨会话互斥锁服务（承载 {@code computer_use_lock} 单行表）。
     *
     * <p><b>静态接线</b>：{@code StopHookPipeline.cleanupComputerUseAfterTurn} 是
     * <b>静态</b>方法（CC {@code stopHooks.ts} 的 turn-end 内联清理也是进程级语义）
     * ⇒ 通过 {@link ComputerUseLockService#install(ComputerUseLockService)} 取实例
     * （照抄本仓 {@code TaskSystemConfig.installAgentSwarmsSettingsSource} 的静态接线先例）。
     *
     * @param dataSource 数据源
     * @return 锁服务
     */
    @Bean
    public ComputerUseLockService computerUseLockService(DataSource dataSource) {
        ComputerUseLockService service = new ComputerUseLockService(dataSource, computerUseEnabled);
        ComputerUseLockService.install(service);
        return service;
    }

    /**
     * computer-use 视觉能力闸（Phase 1b · A）· DB 支撑实现（<b>实例级</b>：持有 mapper 引用）。
     *
     * <p>生产注入点：{@link #computerUseTools(ComputerUseGeometryStore, ComputerUseGrantStore,
     * ComputerUseLockService, ComputerUseVisionCapability)}。
     *
     * @param modelMapper    模型 mapper（{@code models} 表）
     * @param providerMapper 提供商 mapper（{@code providers} 表）
     * @return 视觉能力判定实例
     */
    @Bean
    public ComputerUseVisionCapability computerUseVisionCapability(
            com.nexusai.repository.provider.mapper.ModelMapper modelMapper,
            com.nexusai.repository.provider.mapper.ProviderMapper providerMapper) {
        return ComputerUseVisionCapability.db(modelMapper, providerMapper);
    }

    /**
     * 会话级「左键是否被按住」状态（Phase 2a）· <b>纯 Java、无 JNA 依赖</b>。
     *
     * <p><b>WHY 必须是单例</b>：{@code left_mouse_down} / {@code left_mouse_up} /
     * {@code mouse_move} / {@code left_click_drag} / {@code computer_batch} 五个工具
     * <b>共享</b>同一个「左键按住」标志（CCB 用模块级变量，本仓按 sessionId 分桶，
     * 见 {@link ComputerUseMouseState}）⇒ 必须是<b>同一个实例</b>，否则
     * 「按下 → 移动 → 抬起」的跨调用语义就断了。
     *
     * @return 鼠标按键状态
     */
    @Bean
    public ComputerUseMouseState computerUseMouseState() {
        return new ComputerUseMouseState();
    }

    /**
     * 会话级「当前选中的显示器」存储（Phase 2a 的 {@code switch_display} 用）。
     *
     * <p><b>纯 Java、无 JNA 依赖</b>；必须是单例（{@code switch_display} 写入、
     * {@code screenshot} 读取）。
     *
     * @return 显示器选择存储
     */
    @Bean
    public ComputerUseDisplayStore computerUseDisplayStore() {
        return new ComputerUseDisplayStore();
    }

    /**
     * 鼠标缓动器（{@code mouseAnimation} 子开关的消费者）· <b>Phase 2a 起改为「方法内构造」</b>。
     *
     * <p><b>WHY 不再是 {@code @Bean}</b>（与 Phase 1c 的写法相比是一次有意的改动）：
     * <ul>
     *   <li>Phase 1c 时它<b>没有消费者</b> ⇒ 用 {@code @Bean + @Lazy} 兜着
     *       （「开关关闭就不在启动期碰 JNA」的约定）；</li>
     *   <li>Phase 2a 起 {@code left_click_drag} / {@code computer_batch} 都要用它 ⇒
     *       若把它当 {@code @Bean} 注入进 {@link #computerUseTools}，Spring 会在
     *       <b>调用该 @Bean 方法之前</b>就把它解析出来 ⇒ <b>启动期就加载 JNA</b>
     *       ⇒ <b>破坏「开关关闭就不碰 JNA」的不变式</b>；</li>
     *   <li>⇒ 改为在 {@link #computerUseTools} 内部、<b>开关已开</b>的分支里
     *       构造（与 {@code capturer} / {@code mouse} / {@code keyboard} / {@code cursor}
     *       同一批），JNA 加载失败仍<b>fail loud</b>。</li>
     * </ul>
     *
     * @param cursor 已加载的 JNA 光标原语
     * @return 鼠标缓动器（缓动参数逐字对齐 CCB {@code src/utils/computerUse/executor.ts:277-312}）
     */
    private ComputerUseMouseAnimator createMouseAnimator(ComputerUseCursor cursor) {
        boolean animationEnabled = gates != null && gates.mouseAnimation();
        if (log.isInfoEnabled()) {
            log.info("computer-use 鼠标缓动器已就绪: mouseAnimation={}", animationEnabled);
        }
        return new ComputerUseMouseAnimator(cursor, ComputerUseSleeper.REAL, animationEnabled);
    }

    /**
     * 构建 computer-use 工具集合（<b>仅开关关闭路径</b>的兼容重载）。
     *
     * <p>开关关闭时本方法在依赖被使用前就返回空 List ⇒ 传 null 依赖是安全的。
     * 开关打开时缺依赖 ⇒ {@link IllegalStateException}（fail loud）。
     *
     * @param geometryStore 几何快照存储
     * @return 工具列表（开关关闭时为空 List）
     */
    public List<Tool> computerUseTools(ComputerUseGeometryStore geometryStore) {
        return computerUseTools(geometryStore, null, null);
    }

    /**
     * 构建 computer-use 工具集合（<b>兼容重载</b> · 无视觉能力闸）。
     *
     * <p>开关关闭时本方法在依赖被使用前就返回空 List ⇒ 传 null 依赖是安全的。
     *
     * @param geometryStore 几何快照存储
     * @param grantStore    会话授权位存储
     * @param lockService   跨会话锁服务
     * @return 工具列表（开关关闭时为空 List）
     */
    public List<Tool> computerUseTools(ComputerUseGeometryStore geometryStore,
                                       ComputerUseGrantStore grantStore,
                                       ComputerUseLockService lockService) {
        return computerUseTools(geometryStore, grantStore, lockService, null);
    }

    /**
     * [Phase 2b] computer-use <b>窗口绑定存储</b>（会话级）。
     *
     * <p>{@code bind_window} 写入、点击路由读取 ⇒ 必须是<b>同一个实例</b>
     * （与 {@link ComputerUseMouseState} / {@link ComputerUseDisplayStore} 同款约定）。
     *
     * @return 绑定存储
     */
    @Bean
    public ComputerUseWindowBindingStore computerUseWindowBindingStore() {
        return new ComputerUseWindowBindingStore();
    }

    /**
     * [Phase 1e] computer-use <b>视觉 grounding 服务</b>（降级路径的依赖）。
     *
     * <p>复用 {@code vision_analyze} 的旁路调用链（视觉模型名 → provider → 旁路单发），
     * 但带<b>专用 grounding prompt</b>（要求返回结构化 JSON 坐标）。
     *
     * @param modelConfigResolver 多模态档位模型名 + ProviderConfig 解析
     * @param llmProviderFactory  provider 工厂
     * @param sideCallClient      旁路单发客户端（调用 + 记账）
     * @return grounding 服务
     */
    @Bean
    public ComputerUseGroundingService computerUseGroundingService(
            com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver,
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            com.nexusai.application.agent.cost.LlmSideCallClient sideCallClient) {
        return new ComputerUseGroundingService(modelConfigResolver, llmProviderFactory, sideCallClient);
    }

    /**
     * 构建 computer-use 工具集合（<b>兼容重载</b> · 无视觉能力闸 / 无 grounding 服务）。
     *
     * @param geometryStore    几何快照存储
     * @param grantStore       会话授权位存储
     * @param lockService      跨会话锁服务
     * @param visionCapability 视觉能力判定（null ⇒ 不做闸）
     * @return 工具列表（开关关闭时为空 List）
     */
    public List<Tool> computerUseTools(ComputerUseGeometryStore geometryStore,
                                       ComputerUseGrantStore grantStore,
                                       ComputerUseLockService lockService,
                                       ComputerUseVisionCapability visionCapability) {
        return computerUseTools(geometryStore, grantStore, lockService, visionCapability, null);
    }

    /**
     * 构建 computer-use 工具集合（<b>兼容重载</b> · Phase 2a 前签名）。
     *
     * @param geometryStore    几何快照存储
     * @param grantStore       会话授权位存储
     * @param lockService      跨会话锁服务
     * @param visionCapability 视觉能力判定
     * @param groundingService grounding 降级服务
     * @return 工具列表（开关关闭时为空 List）
     */
    public List<Tool> computerUseTools(ComputerUseGeometryStore geometryStore,
                                       ComputerUseGrantStore grantStore,
                                       ComputerUseLockService lockService,
                                       ComputerUseVisionCapability visionCapability,
                                       ComputerUseGroundingService groundingService) {
        return computerUseTools(geometryStore, grantStore, lockService,
                visionCapability, groundingService, null, null, null);
    }

    /**
     * 构建 computer-use 工具集合。
     *
     * <p><b>工具清单 = 35 个</b>（CCB 全集，顺序逐字对齐 CCB
     * {@code packages/@ant/computer-use-mcp/src/tools.ts} 的 {@code buildComputerUseTools}
     * 数组 —— 含 Windows 专属的 11 个，其位置与 CCB 的
     * {@code caps.platform === 'win32' ? [...] : []} 条件块（{@code tools.ts:420-836}）一致：
     * {@code open_application} 之后、{@code switch_display} 之前）。
     *
     * @param geometryStore 几何快照存储
     * @param grantStore    会话授权位存储
     * @param lockService   跨会话锁服务
     * @param visionCapability 视觉能力判定（Phase 1b · A 的视觉闸；null ⇒ 不做闸）
     * @param groundingService [Phase 1e] grounding 降级服务（null ⇒ 降级路径关闭 ⇒
     *                        视觉闸判「确定不支持」时维持 fail loud 报错）
     * @param mouseState    [Phase 2a] 会话级「左键按住」状态（null ⇒ 新建一个 ⇒
     *                       仅单测兼容路径会走到；生产由 Spring 注入单例）
     * @param displayStore  [Phase 2a] 会话级「选中的显示器」存储（null ⇒ 新建一个）
     * @return 工具列表（开关关闭时为空 List）
     */
    @Bean
    public List<Tool> computerUseTools(ComputerUseGeometryStore geometryStore,
                                       ComputerUseGrantStore grantStore,
                                       ComputerUseLockService lockService,
                                       ComputerUseVisionCapability visionCapability,
                                       ComputerUseGroundingService groundingService,
                                       ComputerUseMouseState mouseState,
                                       ComputerUseDisplayStore displayStore,
                                       ComputerUseWindowBindingStore bindingStore) {
        if (!computerUseEnabled) {
            if (log.isInfoEnabled()) {
                log.info("nexusai.feature.computer-use=false → mcp__computer-use__* 工具不注册（默认关，需 yml true）");
            }
            return List.of();
        }
        if (grantStore == null || lockService == null) {
            throw new IllegalStateException(
                    "[computer-use] 开关已开但授权位存储 / 锁服务缺失（computerUseGrantStore / "
                            + "computerUseLockService 未注入）⇒ 无法构建 computer-use 工具");
        }
        // ── 平台分派（Windows / macOS / 其它 ⇒ 明确抛不支持）──
        //   WHY 不静默降级：computer-use 的每个工具都在控制用户真实桌面；
        //   在不支持的平台注册工具、却让它们在调用时才炸 ⇒ 模型拿到「有工具但用不了」的能力。
        //   ⇒ 启动期就明确失败（规则十二 · 显式失败）。
        ComputerUsePlatform platform;
        try {
            platform = resolvePlatform();
        } catch (UnsupportedOperationException e) {
            // 不支持的操作系统 ⇒ 明确抛不支持（Linux 等）
            throw e;
        } catch (Throwable t) {
            // fail loud：开关打开却装配不了执行端（Windows 侧 = JNA native 加载失败）⇒ 直接启动失败
            throw new IllegalStateException(
                    "[computer-use] 执行端装配失败（Windows 侧为 JNA native 加载失败：user32/gdi32/shell32），"
                            + "无法启用 computer-use: " + t.getMessage(), t);
        }
        platform.logResolved();
        ComputerUseScreenCapturer capturer = platform.screenCapturer();
        ComputerUseMouse mouse = platform.mouse();
        ComputerUseKeyboard keyboard = platform.keyboard();
        ComputerUseCursor cursor = platform.cursor();
        ComputerUseMouseActions mouseActions = platform.mouseActions();
        ComputerUseDisplayProvider displayProvider = platform.displayProvider();
        ComputerUseClipboard clipboard = platform.clipboard();
        ComputerUseAppLauncher appLauncher = platform.appLauncher();
        // [Phase 2a] 鼠标缓动器在此构造（而不是 @Bean）⇒ 开关关闭时<b>启动期不碰 JNA</b>
        ComputerUseMouseAnimator animator = createMouseAnimator(cursor);
        // [Phase 2a] 会话级共享状态（生产由 Spring 注入单例；null ⇒ 新建，仅兼容路径）
        ComputerUseMouseState effectiveMouseState =
                mouseState != null ? mouseState : new ComputerUseMouseState();
        ComputerUseDisplayStore effectiveDisplayStore =
                displayStore != null ? displayStore : new ComputerUseDisplayStore();
        // [Phase 1c] 坐标模式：来自子开关且【首次读取即冻结】（CCB gates.ts:66-72）
        ComputerUseCoordinateMode coordinateMode = coordinateMode();
        // [Phase 1e] grounding 降级路径只在【开关打开】时接线（默认关 ⇒ 生产语义不变）。
        //   降级路径只能产出百分比坐标 ⇒ 坐标模式强制 normalized_0_100（否则「描述说像素、
        //   实际按百分比换算」会让模型点错位置 —— CCB tools.ts:5-10 的同源不变量）。
        boolean groundingEnabled = gates != null && gates.groundingFallbackEnabled();
        ComputerUseGroundingService effectiveGrounding = groundingEnabled ? groundingService : null;
        if (groundingEnabled && coordinateMode != ComputerUseCoordinateMode.NORMALIZED_0_100) {
            log.warn("[computer-use] grounding 降级已开启 ⇒ 坐标模式强制切到 normalized_0_100"
                    + "（原={}）—— 降级路径只产出百分比坐标，描述与换算必须同源", coordinateMode.wire());
            coordinateMode = ComputerUseCoordinateMode.NORMALIZED_0_100;
        }
        ComputerUseForegroundAppResolver foregroundAppResolver = platform.foregroundAppResolver();
        // ── [Phase 2b] Windows 专属能力的依赖 ──
        // ⚠️ macOS 上这些依赖<b>不存在</b>（窗口枚举 / UIA / 浮层 / 终端都是 Windows 专属）
        //   ⇒ 只在 Windows 上构造；macOS 上恒为 null（对应的 11 个工具也不注册）。
        //   这与 CCB 一致：CCB 把这 11 个工具放在
        //   `caps.platform === 'win32' ? [...] : []` 条件块里（tools.ts:420-836）。
        ComputerUseWindowOps windowOps = null;
        ComputerUseUiaService uiaService = null;
        ComputerUseStatusIndicator statusIndicator = null;
        ComputerUseTerminalLauncher terminalLauncher = null;
        ComputerUseProcessAppResolver processAppResolver = null;
        if (platform.supportsWindowsOnlyTools()) {
            // 窗口操作（JNA 直调 user32）：枚举 / 窗口管理 / 窗口定向输入
            windowOps = new Win32WindowOps();
            // UIA（PowerShell + .NET UIAutomation 程序集）：快照 / InvokePattern / ValuePattern
            uiaService = new Win32UiaService();
            // 状态浮层（独立 PowerShell WinForms 进程）
            statusIndicator = new Win32StatusIndicator();
            // 终端启动器（ProcessBuilder 起新终端）
            terminalLauncher = new Win32TerminalLauncher();
            // pid → 应用授权键（绑定窗口的档位门控用）
            processAppResolver = new Win32ProcessAppResolver();
        }
        // 绑定窗口存储（会话级；null ⇒ 新建一个兼容路径）
        ComputerUseWindowBindingStore effectiveBindingStore =
                bindingStore != null ? bindingStore : new ComputerUseWindowBindingStore();
        // 点击路由（窗口定向 / 全局注入 + 修饰键）
        //   macOS 上 windowOps = null ⇒ 恒走全局注入（本仓 macOS 无窗口绑定语义）
        ComputerUseClickRouter clickRouter =
                new ComputerUseClickRouter(effectiveBindingStore, windowOps, keyboard);
        List<Tool> tools = new java.util.ArrayList<>();
        tools.addAll(List.of(
                // ── 顺序逐字对齐 CCB tools.ts 的 buildComputerUseTools 数组 ──

                // [Phase 1a] 授权申请工具（CCB computer-use MCP 的第一个工具）
                new ComputerUseRequestAccessTool(grantStore, lockService),
                // [Phase 1b · A] 截图工具带视觉能力闸（主模型不支持视觉 ⇒ fail loud，不发图）
                // [Phase 1e] 降级路径开启时：闸判「确定不支持」⇒ 改走 grounding（不回图）
                new ComputerUseScreenshotTool(capturer, geometryStore, visionCapability, effectiveGrounding),
                // [Phase 2a] 区域放大截图（只读；按 region 原分辨率重抓）
                new ComputerUseZoomTool(capturer, geometryStore, coordinateMode),
                // [Phase 1a] 接授权体系 ⇒ 点击工具自带档位门控（CCB runInputActionGates）
                // [Phase 1b · A] 点击工具同样带视觉能力闸（看不到图 ⇒ 坐标无来源 ⇒ 明确报错）
                // [Phase 1e] 降级路径生效时：放行 + 切百分比态换算
                // [Phase 1c] 坐标描述随【冻结的】坐标模式生成（一态一文案）
                new ComputerUseLeftClickTool(capturer, mouse, geometryStore,
                        coordinateMode, grantStore, foregroundAppResolver,
                        visionCapability, effectiveGrounding, clickRouter),
                // [Phase 2a] 点击变体（左/右/中键 × 1/2/3 次）
                new ComputerUseDoubleClickTool(mouseActions, geometryStore,
                        coordinateMode, grantStore, foregroundAppResolver),
                new ComputerUseTripleClickTool(mouseActions, geometryStore,
                        coordinateMode, grantStore, foregroundAppResolver),
                new ComputerUseRightClickTool(mouseActions, geometryStore,
                        coordinateMode, grantStore, foregroundAppResolver),
                new ComputerUseMiddleClickTool(mouseActions, geometryStore,
                        coordinateMode, grantStore, foregroundAppResolver),
                // [Phase 1c] 文本输入（KEYEVENTF_UNICODE 逐字符注入，无特殊键语义）
                new ComputerUseTypeTool(keyboard, ComputerUseSleeper.REAL, grantStore, foregroundAppResolver),
                // [Phase 1c] 按键 / 组合键（修饰键正序按下、逆序释放）
                new ComputerUseKeyTool(keyboard, ComputerUseSleeper.REAL, grantStore, foregroundAppResolver),
                // [Phase 2a] 滚轮滚动（Windows 侧忽略坐标 —— 对齐 CCB）
                new ComputerUseScrollTool(mouseActions, geometryStore, coordinateMode,
                        grantStore, foregroundAppResolver, effectiveMouseState),
                // [Phase 2a] 拖拽（按下 → 缓动移动 → 抬起；异常路径不卡键）
                new ComputerUseLeftClickDragTool(animator, geometryStore, coordinateMode,
                        grantStore, foregroundAppResolver, effectiveMouseState),
                // [Phase 2a] 纯移动鼠标（不点击）
                new ComputerUseMouseMoveTool(mouseActions, geometryStore, coordinateMode,
                        grantStore, foregroundAppResolver, effectiveMouseState),
                // [Phase 2a] 启动 / 置前应用（必须在会话白名单里；无档位门控）
                new ComputerUseOpenApplicationTool(appLauncher, grantStore, displayProvider)));
        // ── [Phase 2b] Windows 专属 11 个工具（<b>仅 Windows 注册</b>）──
        //   与 CCB 一致：CCB 把它们放在 `caps.platform === 'win32' ? [...] : []` 条件块里
        //   （packages/@ant/computer-use-mcp/src/tools.ts:420-836）
        //   ⇒ <b>CCB 的 macOS 工具面 = 24 个</b>（35 − 11）。
        if (platform.supportsWindowsOnlyTools()) {
            tools.addAll(List.of(
                //   （位置逐字对齐 CCB tools.ts:420-836 的 win32 条件块：
                //    紧跟 open_application 之后、switch_display 之前）
                // [Phase 2b] 窗口管理（ShowWindow / SetWindowPos / WM_CLOSE / 置前）
                new ComputerUseWindowManagementTool(windowOps, effectiveBindingStore),
                // [Phase 2b] UIA 元素点击 / 元素输入（InvokePattern / ValuePattern）
                new ComputerUseClickElementTool(uiaService, windowOps, effectiveBindingStore),
                new ComputerUseTypeIntoElementTool(uiaService, windowOps, effectiveBindingStore),
                // [Phase 2b] 打开终端并启动 agent CLI（绑定新终端窗口）
                new ComputerUseOpenTerminalTool(terminalLauncher, windowOps, effectiveBindingStore,
                        ComputerUseSleeper.REAL),
                // [Phase 2b] 绑定 / 解绑窗口（会话级状态 ⇒ 后续点击改走窗口定向）
                new ComputerUseBindWindowTool(windowOps, effectiveBindingStore,
                        processAppResolver, grantStore),
                // [Phase 2b] 窗口置前 + 焦点点击
                new ComputerUseActivateWindowTool(windowOps, effectiveBindingStore),
                // [Phase 2b] 终端提示应答（yes/no/enter/escape/select/type）
                new ComputerUsePromptRespondTool(windowOps, effectiveBindingStore, ComputerUseSleeper.REAL),
                // [Phase 2b] 状态浮层（显示 / 隐藏 / 状态）
                new ComputerUseStatusIndicatorTool(statusIndicator, effectiveBindingStore),
                // [Phase 2b] 窗口定向键盘 / 鼠标 / 滚轮（只打绑定窗口，不动真实外设）
                new ComputerUseVirtualKeyboardTool(windowOps, effectiveBindingStore, ComputerUseSleeper.REAL),
                new ComputerUseVirtualMouseTool(windowOps, effectiveBindingStore, ComputerUseSleeper.REAL),
                new ComputerUseMouseWheelTool(windowOps, effectiveBindingStore)));
        }
        // ── [Phase 2b] Windows 专属 11 个工具结束 ──
        tools.addAll(List.of(
                // [Phase 2a] 切换截图目标显示器（无门控）
                new ComputerUseSwitchDisplayTool(displayProvider, effectiveDisplayStore),
                // [Phase 1c] 列出已授权应用 + 授权位 + 坐标模式（只读）
                new ComputerUseListGrantedApplicationsTool(grantStore, coordinateMode),
                // [Phase 2a] 读剪贴板（clipboardRead 授权位是硬门；无前台门控）
                new ComputerUseReadClipboardTool(clipboard, grantStore),
                // [Phase 2a] 写剪贴板（clipboardWrite 授权位是硬门；无前台门控）
                new ComputerUseWriteClipboardTool(clipboard, grantStore),
                // [Phase 1c] 等待（无前台门控 —— CCB 同款）
                new ComputerUseWaitTool(ComputerUseSleeper.REAL),
                // [Phase 2a] 读光标位置（只读；无门控）
                new ComputerUseCursorPositionTool(cursor, geometryStore),
                // [Phase 2a] 按住组合键一段时间后释放（异常路径不卡键）
                new ComputerUseHoldKeyTool(keyboard, ComputerUseSleeper.REAL, grantStore, foregroundAppResolver),
                // [Phase 2a] 左键按下并保持（已按住 ⇒ 报错）
                new ComputerUseLeftMouseDownTool(cursor, grantStore, foregroundAppResolver, effectiveMouseState),
                // [Phase 2a] 左键抬起（幂等；未按住时不报错）
                new ComputerUseLeftMouseUpTool(cursor, grantStore, foregroundAppResolver, effectiveMouseState),
                // [Phase 2a] 批量动作（先全量校验 → 顺序执行 → 遇错即停）
                new ComputerUseBatchTool(keyboard, mouseActions, cursor, animator, capturer,
                        geometryStore, coordinateMode, grantStore, foregroundAppResolver,
                        effectiveMouseState, ComputerUseSleeper.REAL)));
        log.info("注册 {} 个 computer-use 工具 → ToolRegistry: {}（视觉闸={} · 坐标模式={} · grounding降级={}）",
                tools.size(), tools.stream().map(Tool::name).toList(),
                visionCapability != null ? "已接线" : "未接线", coordinateMode.wire(),
                groundingEnabled ? "已开启" : "已关闭");
        return tools;
    }

    /**
     * 平台探测（<b>可覆写</b> · 单测用）。
     *
     * <p><b>WHY 是 protected 而不是直接调 {@link ComputerUsePlatform#detect()}</b>：
     * 单测要能在<b>任意开发机</b>上断言「macOS 上的工具面 = CCB 的 macOS 工具面（24 个）」
     * ⇒ 必须能注入平台，而不是靠运行机器的 {@code os.name}。
     *
     * @return 平台实例
     */
    protected ComputerUsePlatform resolvePlatform() {
        return ComputerUsePlatform.detect();
    }

    /**
     * 冻结后的坐标模式。
     *
     * @return 坐标模式（子开关未注入 ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     */
    private ComputerUseCoordinateMode coordinateMode() {
        if (gates == null) {
            return ComputerUseCoordinateMode.PIXELS;
        }
        return gates.coordinateMode();
    }
}
