package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.compact.BoundaryReader;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ChatMessageDto.UserAttachmentInfo;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.session.SessionResumeDeserializer;

/**
 * Message 业务逻辑：
 * - listBySession：按 sessionId 查全部
 * - getById：单查
 * - createUserMessage：持久化用户消息 + 自增 sessions.messageCount
 * - delete：单删
 * - replaceSessionMessages：全量替换会话消息（partial 压缩写回）
 *
 * Phase 4 stub：不调 LLM；只持久化用户消息并返回
 * assistantMessageId = "msg-stub-pending"，streamTopic 形如
 * "/topic/sessions/{id}/stream"（会话级单 topic，对齐 CC 会话单一事件流）。
 * Phase 5 会接真实 LLM 流式。
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** structured_output 序列化/反序列化（项目惯例：各服务静态 ObjectMapper）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MessageMapper messageMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private ToolCallMapper toolCallMapper;

    /**
     * [token-compact-fix ⑤方案B] 模型表 mapper（models.max_context_tokens 窗口解析）·
     * @Autowired(required=false)：无 Spring 上下文 / mapper 缺失时静默回落 1M（对齐实时路径）。
     */
    @Autowired(required = false)
    private ModelMapper modelMapper;

    /** [token-compact-fix ⑤方案B] 提供商 mapper（模型全名感知解析）· @Autowired(required=false)。 */
    @Autowired(required = false)
    private ProviderMapper providerMapper;

    /** [token-compact-fix ⑤方案B] settings 单例行 mapper（settings.mainModelName 模型回落）·
     *  @Autowired(required=false)：无 Spring 上下文时跳过 settings 层，仅会话 override。 */
    @Autowired(required = false)
    private SettingsMapper settingsMapper;

    // ════════════════════════════════════════════════════════════════════
    // [created_at 单调分配器] per-session 单点取号 · 所有落库写路径统一入口
    // ════════════════════════════════════════════════════════════════════

    /**
     * per-session 单调 created_at 分配槽（seed = DB 现有 max created_at，之后内存自增）。
     *
     * <p><b>WHY（跨 writer 单调铁律）</b>：{@code messages.created_at} 是会话消息的<b>时间语义</b>键
     * （展示「X 分钟前」+ 本分配器取号）；<b>位置语义</b>自 V70 起归 {@code seq}
     * （{@link #listBySession} / {@link #listPageBySession} ORDER BY seq；读侧
     * {@code BoundaryReader.getMessagesAfterCompactBoundary} 按该序切片）。
     * 本分配器仍须保证 created_at 严格递增——compact 重挂 kept 段时 created_at 保持原值（时间语义），
     * 但新写入行的时间戳不得倒挂。此前两条写路径各持时间基：
     * 实时落库 {@code ChatService.persistAppendedMessage} 用 run 开始时冻结的
     * {@code baseTs.plusNanos(seq)}；compact 追加 {@link #appendPostCompactMessages} 用调用时
     * {@code OffsetDateTime.now()}。compact 发生在 run <b>中段</b> → 其 now() 恒晚于 baseTs →
     * 同 run 内 compact 之后继续 append 的 assistant/tool/后续 user 行时间戳<b>早于</b> boundary →
     * 下轮 listBySession（V70 前按 created_at 排序）顺序倒挂 → boundary 切片把「边界之后本应保留的
     * 那轮」整段剪掉（还拆散 tool_use/tool_result 配对）。本分配器以「per-session max(now, 该会话已知最大 ts + 1ns)」取号，
     * 保证<b>任何</b>新写入恒晚于该会话<b>所有</b>已有行（无论 writer 是谁、何时写）。
     *
     * <p>key = sessionId（null → "" 独立槽位，防 NPE）；value = 最近一次分配值（nanos）。
     * 首次为该 session 取号时 seed（见 {@link #seedMaxNanos}）；进程重启后 seed 重新从 DB 取
     * → 跨进程仍单调（DB max 为真源）。
     */
    private final ConcurrentHashMap<String, AtomicLong> tsAlloc = new ConcurrentHashMap<>();

    /**
     * 取该会话下一个 {@code created_at}（纳秒单调）· <b>所有写路径统一入口</b>
     * （appendMessage / appendPostCompactMessages / createUserMessage / createQueuedUserMessage /
     * appendSystemSubtypeMessage / replaceSessionMessages / ChatService 实时落库）。
     *
     * <p><b>语义</b>：seed（首次取号）= DB 该会话 {@code max(created_at)}（解析失败/空 → 0）；
     * 之后 {@code max(now, last + 1ns)}。故：
     * <ul>
     *   <li>恒 &gt; 该会话所有已有行（含时钟回拨 / DB 中未来时间戳场景）；</li>
     *   <li>同一毫秒内连续取号仍严格递增（+1ns，对齐旧 {@code base.plusNanos(seq)} 保序范式，
     *       但跨 writer 也成立）；</li>
     *   <li>调用顺序 = created_at 顺序（调用方按入参数组序逐个取号即保序）。</li>
     * </ul>
     *
     * <p>毫秒精度足够（nanos 仅用于 +1 排序）。返回带系统时区的 OffsetDateTime，落库格式
     * {@code OffsetDateTime.toString()} 与既有行同构（{@link #seedMaxNanos} 可解析回读）。
     *
     * @param sessionId 会话 ID（DB 键 "sess-xxx"；null → 独立 "" 槽位，不抛）
     * @return 该会话下一个 created_at（严格大于该会话此前所有已分配/已存在的值）
     */
    public OffsetDateTime nextCreatedAt(String sessionId) {
        long nowNanos = System.currentTimeMillis() * 1_000_000L;   // 毫秒精度即可（nanos 仅用于 +1 排序）
        AtomicLong holder = tsAlloc.computeIfAbsent(
            sessionId == null ? "" : sessionId, k -> new AtomicLong(seedMaxNanos(k)));
        long v = holder.updateAndGet(prev -> Math.max(nowNanos, prev + 1));
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] nextCreatedAt: session={} ts={}（per-session 单调分配器取号）",
                sessionId, v);
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(0, v), ZoneId.systemDefault());
    }

    /**
     * seed：DB 该会话 {@code max(created_at)} → nanos（无行 / 解析失败 / sessionId 空 → 0，
     * 交给 {@link #nextCreatedAt} 的 {@code max(now, ...)} 兜底）。
     *
     * <p>取 max 依赖 created_at TEXT 的字典序（排序口径 = 时间序，非位置序——位置语义自 V70 起归
     * {@code seq}；此处仅需「时间最大」）。写出格式统一为 {@code OffsetDateTime.toString()}，
     * 同时区下字典序 == 时间序；解析用
     * {@link OffsetDateTime#parse}，异常仅 warn 不抛（fail loud 降级，绝不让取号失败打断落库）。
     *
     * @param sessionId 会话 ID（DB 键；null/空白 → 直接 0）
     * @return DB 该会话最大 created_at 的纳秒值；无行/解析失败/入参空 → 0
     */
    private long seedMaxNanos(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        try {
            List<MessageRecord> rows = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionId).orderBy("created_at", false).limit(0, 1));
            if (rows != null && !rows.isEmpty() && rows.get(0) != null && rows.get(0).getCreatedAt() != null) {
                OffsetDateTime t = OffsetDateTime.parse(rows.get(0).getCreatedAt());
                long seeded = t.toEpochSecond() * 1_000_000_000L + t.getNano();
                if (log.isDebugEnabled()) {
                    log.debug("[MessageService] nextCreatedAt seed: session={} DB max created_at={} → {}ns",
                        sessionId, rows.get(0).getCreatedAt(), seeded);
                }
                return seeded;
            }
        } catch (Exception e) {
            log.warn("[MessageService] nextCreatedAt seed 失败(回落 now): session={} err={}",
                sessionId, e.toString());
        }
        return 0L;
    }

    // ════════════════════════════════════════════════════════════════════
    // [seq 单调排序键] 雪花取号 + per-session DB 基数 seed · 位置语义（区别于 created_at 时间语义）
    // ════════════════════════════════════════════════════════════════════

    /**
     * {@code seq}（会话内位置键）取号自增兜底槽 · <b>仅防雪花时钟回拨/异常</b>。
     *
     * <p><b>WHY（根治「created_at 既是时间又是位置」）</b>：{@code created_at} 此前兼任展示时间
     * （前端「X 分钟前」）与会话内排序位置（listBySession / listPageBySession ORDER BY created_at）。
     * compact append-only 落库需把 kept 段「重挂」到 boundary 之后（位置变化），但 kept 的真实产生
     * 时间不该被改写（展示语义）→ 同列二义必冲突。{@code seq}（V70 列）承载位置语义：读侧 ORDER BY seq，
     * created_at 回归纯时间。kept 段重挂 = 只更新 seq、created_at 保持原值。
     *
     * <p><b>why 雪花而非纯 per-session 自增</b>：纯 per-session 自增（{@code seed = MAX(seq)} + 内存自增）
     * 需每会话一个分配槽；雪花号十进制位宽大 + 全局单调，跨会话混排也不乱序。V70 存量行回填为 1..N，
     * 新雪花号恒大于它们 → 混排序仍正确。
     *
     * <p><b>⚠️ 纠正 V70 头注释的过时措辞（V70 文件按 checksum 约束<b>一字未改</b>，见 V71 头注释）</b>：
     * V70:19 原话称雪花取号「免 seed 查询 / 免 per-JVM 重复 / <b>天然多实例安全</b>」——三句都不成立：
     * <ul>
     *   <li><b>「免 seed 查询」不成立</b>：本批已补 per-session DB 基数 seed（{@link #seqFloorOf}）。
     *       「免 seed」的前提是「新号恒大于 DB 中所有已有号」，而该前提<b>只在时钟单调向前时</b>成立；</li>
     *   <li><b>「天然多实例安全」不成立</b>：见下方「多实例边界」（workerId 由 PID/MAC 派生，mod 32 撞号）。
     *       V70 头注释与本节结论自相矛盾，<b>以本节为准</b>；</li>
     *   <li><b>跨重启单调不成立</b>：hutool 雪花号 = 时间基派生。墙钟回拨（NTP 回跳 / VM 快照恢复 /
     *       手工改时间）+ 进程重启后取到的号会<b>小于</b>回拨前写入的号 → 新行排在旧行之前
     *       （{@code ORDER BY seq ASC}）→ boundary 切片把最新一轮整段剪掉（见 {@link #seqFloorOf}）。</li>
     * </ul>
     * 本批不动 V70（checksum 会让已有库起不来），纠正落在新代码 javadoc 与 V71 头注释。
     *
     * <p><b>多实例边界（不要按「天然多实例安全」理解）</b>：{@code lastSeq} 只保护<b>单进程</b>。
     * hutool 默认 {@code IdUtil} 单例的 workerId 由 {@code RuntimeUtil.getPid()} 的 hashCode
     * 取模派生、datacenterId 由 MAC 派生 —— <b>同机两个 JVM 有撞 workerId 的概率（mod 32）</b>。
     * 单实例部署下本方法返回值全局唯一且单调；多实例同库写同一会话时 seq 可能重复/回退，
     * 需显式配置 workerId（{@code IdUtil.getSnowflake(workerId, datacenterId)}）或加库侧唯一约束。
     * <b>[本批补充]</b> {@link #seqFloorOf} 的 per-session DB 基数 seed 只兜「重启 + 时钟不再向前」
     * 这一类，<b>不改变</b>本边界：多实例写同一会话仍可能撞号（本批未修，如实声明）。
     */
    private final AtomicLong lastSeq = new AtomicLong(0L);

    /**
     * 会话 ID → 该会话 seq 的 <b>DB 基数</b>（首次取号时读入的 {@code max(seq)}；0 = 无行 / 未知）。
     *
     * <p><b>WHY 与 {@link #tsAlloc} 对称</b>：{@code created_at} 早有 DB 基数 seed（{@link #seedMaxNanos}），
     * 而 {@code seq} 没有 —— 这是缺陷一的根：{@code lastSeq} 进程内从 0 起，<b>从不与 DB 的 max(seq)
     * 比较</b>，于是「重启 + 墙钟回拨」后新号低于 DB 中的旧号 → {@code ORDER BY seq ASC} 把最新写入的
     * 消息排到会话最前 → 被 {@code BoundaryReader.getMessagesAfterCompactBoundary} 当 boundary 之前的
     * 旧消息整段剪掉 → 模型看不到刚发生的这一轮，且零日志。本槽位就是补上的那个基数。
     *
     * <p><b>key = sessionId（null/空白 → "" 独立槽位，防 NPE；空 key 不查 DB 直接 0）</b>。
     * <b>每会话只查一次 DB</b>：{@link ConcurrentHashMap#computeIfAbsent} 保证该 sessionId 的 seed
     * 恰好执行一次（与 {@link #tsAlloc} 同款；value 命中后永不再回查 —— 进程内 lastSeq 此后恒高于它）。
     */
    private final ConcurrentHashMap<String, Long> seqFloor = new ConcurrentHashMap<>();

    /** 已就「seq 候选低于 DB 基数」报过 ERROR 的会话（每会话只 ERROR 一次，防持续异常刷屏）。 */
    private final Set<String> seqFloorClampedWarned = ConcurrentHashMap.newKeySet();

    /**
     * 读侧排序片段（ASC 通道）：{@code seq} 升序 + NULL 排末尾。用于 {@link #listBySession}。
     *
     * <p><b>语义选择（有意取舍）</b>：NULL = 位置未知，<b>既不冒充最新也不冒充最旧</b> —— ASC 通道下
     * NULL 落在<b>结果集末尾</b>：裸 {@code seq} 时 NULL 恒排<b>最前</b>（先于第一条真实消息）= 冒充
     * 「会话最旧」→ 会被 boundary 切片当旧消息<b>静默剪掉</b>，且挤在真实首条消息之前。故必须把 NULL
     * 推到末尾 ⇒ 不遮挡真实首条、不被静默剪掉。残留（如实声明）：ASC 侧 NULL 行位于模型上下文<b>末尾</b>，
     * 即「一条位置未知的行可能被当作最近一轮送给模型」—— 刻意取舍：排最前那一侧会被 boundary 切片静默
     * 剪掉，<b>静默丢消息比「一条脏行可见 + 有 ERROR 日志」更糟</b>。根治靠 V71 触发器挡住新 NULL（写入口），
     * 读侧本片段 + {@link #warnNullSeqIfAny} 兜存量。
     *
     * <p><b>WHY 用 {@code NULLS LAST} 而不是旧的 {@code seq IS NULL} 前置键（本批的核心，别改回去）</b>：
     * 旧写法 {@code ORDER BY seq IS NULL, seq ASC} 里 {@code seq IS NULL} 是<b>表达式、不是索引列</b>
     * → SQLite 无法用它出序，实测查询计划退化为
     * {@code SEARCH messages USING INDEX idx_messages_session_seq (session_id=?)} <b>+</b>
     * {@code USE TEMP B-TREE FOR ORDER BY}（全量临时排序，把 {@code idx_messages_session_seq} 的
     * 免排序收益全部废掉）。分页那条更亏：本可 {@code ORDER BY seq DESC LIMIT 51} 只取 51 行就停，
     * 加前置键后要先全量排序再取 51 行。
     * 而 {@code seq ASC NULLS LAST} 的排序键<b>仍是索引列 seq</b>（{@code NULLS LAST} 是 SQLite 3.30+
     * 的排序修饰符，不是表达式）→ 实测查询计划只有 index SEARCH、<b>没有</b> {@code USE TEMP B-TREE}。
     * 语义与旧写法<b>逐行等价</b>（NULL 同样落在各自结果集末尾）。
     *
     * <p><b>为什么不能直接删掉 NULL 兜底（比如裸 {@code seq}）</b>：V71 的回填只在「已升级并重启到新
     * 构建」的机器上跑过；<b>其他用户的库在升级到本构建之前仍带 NULL</b>，读侧不能假设全世界的库都干净。
     *
     * <p><b>语法位置固定</b>：SQLite 文法为 {@code expr [COLLATE 名] [ASC|DESC] [NULLS FIRST|LAST]}，
     * 即方向必须在 {@code NULLS LAST} <b>之前</b>（{@code seq NULLS LAST ASC} 是语法错误，实测
     * {@code near "ASC": syntax error}）。故本片段自带方向、调用方<b>不得</b>再叠加 {@code orderBy}。
     *
     * <p><b>public 常量</b>：真实 SQLite 引擎断言（测试）直接引用本常量组装 SQL，保证「测试断言的口径」
     * 与「生产发出的片段」同源、不会两处漂移（生产经 {@code orderByUnSafely} 原样发出：实测
     * {@code QueryWrapper.create().eq("session_id",..).orderByUnSafely("seq ASC NULLS LAST").toSQL()}
     * → {@code ... ORDER BY seq ASC NULLS LAST}，flex 不包装/不转义/不追加方向）。
     */
    public static final String SEQ_ASC_NULLS_LAST_ORDER = "seq ASC NULLS LAST";

    /**
     * 读侧排序片段（DESC 通道）：{@code seq} 降序 + NULL 排末尾。用于 {@link #listPageBySession} 尾页。
     *
     * <p><b>WHY 不是 {@code seq DESC NULLS LAST} 之外的写法</b>：DESC 通道下"排末尾"= <b>最旧</b>那头
     * —— 绝不能让 NULL 冒充「<b>最新</b>」被算进尾页（{@code LIMIT pageSize+1}）而挤掉真实刚写入的那条。
     * 裸 {@code seq DESC} 恰好把 NULL 排最后（SQLite 视 NULL 为最小）→ 语义上「裸 DESC」与「DESC NULLS
     * LAST」一致，本片段只是把该语义<b>显式钉住</b>（防将来有人把两条通道的排序片段写反）；且与 ASC 通道
     * 对称、共用同一套 NULLS LAST 机制（索引可用，见 {@link #SEQ_ASC_NULLS_LAST_ORDER} 的 WHY）。
     *
     * <p><b>与 {@link #listBySession} 的关系</b>：两条通道对 NULL 的处置一致 = <b>永不冒充最新</b>，
     * 且都<b>永不废掉</b> {@code idx_messages_session_seq}（无 TEMP B-TREE）。
     */
    public static final String SEQ_DESC_NULLS_LAST_ORDER = "seq DESC NULLS LAST";

    /**
     * 取一个「雪花候选号」（{@code IdUtil.getSnowflakeNextId()}）· {@link #nextSeq} /
     * {@link #nextSeqBlock} 共用的候选基准来源。
     *
     * <p><b>异常/时钟回拨兜底 = 纯进程内单调（{@code lastSeq + 1}），绝不再引入墙钟量纲</b>：
     * 旧实现回落 {@code System.currentTimeMillis() * 1000L}（≈1.79e15），而真实雪花号 ≈2.098e18，
     * <b>差约 1170 倍（3 个数量级）</b>。进程重启 + 墙钟回拨（NTP 回跳 / VM 快照恢复 / 手工改时间）后，
     * 这个兜底值远小于回拨前写入的雪花号 → 新行 seq &lt; 旧行 → 最新写入的消息被排到会话最前 →
     * boundary 切片整段剪掉 → 模型看不到刚发生的这一轮，且零日志。
     *
     * <p><b>为什么兜底取 {@code lastSeq + 1} 而不是抛出让上层显式处理</b>（二选一，选前者）：
     * 抛异常会把「雪花取号的一次瞬时异常」放大成「全部落库路径失败」（user 消息 / compact / 工具结果
     * 全写不进去 = 用户可见的丢消息），代价远大于「位置键退化为纯进程内序数」。而进程内序数本身是安全的
     * —— 它不含任何墙钟量纲，跨重启量纲倒挂因此不可能再发生；量纲缩水后的「值偏小」由
     * {@link #seqFloorOf} 的 DB 基数 clamp 抬到该会话已有行之上（见 {@link #clampSeqToDbFloor}）。
     * 故「兜底不炸落库」与「位置不出错」两件事由两个职责分担，而不是靠抛异常二选一。
     */
    private long snowflakeCandidate() {
        try {
            return cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        } catch (Exception e) {
            long fallback = lastSeq.get() + 1;   // 纯进程内单调：无墙钟量纲（见 javadoc）
            log.warn("[MessageService] 雪花取号异常，回落纯进程内单调兜底 lastSeq+1={}"
                    + "（不再用墙钟量纲 nowMillis*1000 —— 旧值 ≈1.79e15 与雪花号 ≈2.098e18 差 3 个数量级，"
                    + "跨重启必错序）; seq 基数由该会话 DB 基数 clamp 兜底: {}",
                fallback, e.toString());
            return fallback;
        }
    }

    /**
     * 该会话 seq 的 <b>DB 基数</b>（{@link #seedMaxSeq} 的进程内缓存）· 每会话只查一次 DB。
     *
     * @param sessionId 会话 ID（null/空白 → "" 独立槽位，seed 恒 0 不查 DB）
     * @return 该会话 {@code max(seq)}；无行 / 查询失败 / mapper 缺失 → 0（= 无基数，clamp 不介入）
     */
    private long seqFloorOf(String sessionId) {
        String key = (sessionId == null || sessionId.isBlank()) ? "" : sessionId;
        return seqFloor.computeIfAbsent(key, this::seedMaxSeq);
    }

    /**
     * seed：DB 该会话 {@code max(seq)} → long（无行 / 查询失败 / mapper 缺失 / sessionId 空 → 0）。
     *
     * <p>与 {@link #seedMaxNanos} 同款（读一行 + 取该列），差别只在列与排序：{@code ORDER BY seq DESC
     * LIMIT 1} —— SQLite 下 DESC 的 NULL 排最后，故此行是「该会话最大的<b>非 NULL</b> seq」
     * （全 NULL 会话 → 拿到 NULL 行 → 回落 0，等价无基数）。
     *
     * <p><b>查询失败 = 降级 0 但必须出声</b>：缓存 0 表示「本会话不再重试 seed」，此时跨重启基数缺位
     * （回拨后仍可能错序），故 warn 明确写出该风险，而不是静默 0。
     */
    private long seedMaxSeq(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        if (messageMapper == null) {
            // 无 mapper = 无 Spring 上下文 / 纯单测直构（对齐 tsAlloc seed 的容错面），非数据异常
            log.debug("[MessageService] seq seed 跳过（messageMapper 未注入）: session={}", sessionId);
            return 0L;
        }
        try {
            List<MessageRecord> rows = messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", sessionId).orderBy("seq", false).limit(0, 1));
            if (rows != null && !rows.isEmpty() && rows.get(0) != null && rows.get(0).getSeq() != null) {
                long seeded = rows.get(0).getSeq();
                if (log.isDebugEnabled()) {
                    log.debug("[MessageService] seq seed: session={} DB max(seq)={}（该会话取号基数）",
                        sessionId, seeded);
                }
                return seeded;
            }
        } catch (Exception e) {
            log.warn("[MessageService] seq seed 失败（该会话降级为无 DB 基数：重启+墙钟回拨时新号可能低于"
                    + " DB 已有号 → ORDER BY seq ASC 会把最新消息排到最前，请检查本地 DB 可读性）: "
                    + "session={} err={}", sessionId, e.toString());
        }
        return 0L;
    }

    /**
     * 候选号相对「该会话 DB 基数」的<b>防御性校准</b>（缺陷一第 3 条：把静默错序变成有声）。
     *
     * <p>触发条件 {@code candidate <= floor}：候选号比该会话 DB 已有行还小 —— 必然错序
     * （新行会排到旧行之前，被 boundary 切片剪掉）。真实可达路径＝雪花取号异常回落进程内序数
     * （见 {@link #snowflakeCandidate}）撞上「该会话有 V70 回填 1..N 或历史雪花号」；
     * 也覆盖「未来某天取号量纲又被改小」这类回归。
     *
     * <p><b>处置</b>：抬到 {@code floor + 1}（一个仍会被 {@link #nextSeq} 的 {@code max(candidate, prev+1)}
     * 继续往上压的下界），并 <b>ERROR 一次</b> —— 每会话只报一次（{@link #seqFloorClampedWarned}），
     * 后续同类事件降为 DEBUG（不再累计计数，只记当次的值）：本仓有「持续异常刷屏」前科（cron 刷屏根因），故不逐次 ERROR，
     * 但首次必定有声（诊断信息含 session / 候选值 / DB 基数）。
     *
     * @param sessionId 会话 ID
     * @param candidate 雪花候选号（可能已是兜底值）
     * @return {@code candidate}；若低于 DB 基数 → {@code DB max(seq) + 1}
     */
    private long clampSeqToDbFloor(String sessionId, long candidate) {
        long floor = seqFloorOf(sessionId);
        if (floor > 0 && candidate <= floor) {
            String key = (sessionId == null || sessionId.isBlank()) ? "" : sessionId;
            if (seqFloorClampedWarned.add(key)) {
                log.error("[MessageService] seq 候选号低于该会话 DB 基数（静默错序已抬升为有声）: "
                        + "session={} 候选={} DB max(seq)={} → 抬到 {}。成因＝雪花取号异常回落进程内序数 / "
                        + "墙钟回拨 / 跨进程写入；不抬升则新行会排在旧行之前（ORDER BY seq ASC），"
                        + "boundary 切片会把最新一轮整段剪掉且无日志。",
                    sessionId, candidate, floor, floor + 1);
            } else if (log.isDebugEnabled()) {
                log.debug("[MessageService] seq 候选号再次低于 DB 基数（同会话已 ERROR 过，降 DEBUG 防刷屏）: "
                        + "session={} 候选={} DB max(seq)={}", sessionId, candidate, floor);
            }
            return floor + 1;
        }
        return candidate;
    }

    /**
     * 读侧 NULL seq 兜底告警（<b>静默变有声 = 本条的核心价值</b>）：结果集里出现 seq 为 NULL 的行即
     * ERROR 一条，带会话 / 通道 / 条数 / 样本 id。
     *
     * <p>每结果集最多一条 ERROR（按会话聚合而非逐行，避免坏数据把日志刷爆）。NULL 的<b>根因入口</b>由
     * V71 的 BEFORE INSERT/UPDATE 触发器堵住；本方法是「存量坏数据 / 触发器上线前的库」的读侧兜底。
     */
    private void warnNullSeqIfAny(String sessionId, String channel, List<MessageRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int nullSeqRows = 0;
        String sampleId = null;
        for (MessageRecord r : rows) {
            if (r != null && r.getSeq() == null) {
                nullSeqRows++;
                if (sampleId == null) {
                    sampleId = r.getId();
                }
            }
        }
        if (nullSeqRows > 0) {
            log.error("[MessageService] {}: 会话 {} 的结果集里有 {} 行 seq 为 NULL（位置键未落 = 数据异常）"
                    + "，样本 id={}。这些行在 ORDER BY 里不冒充真实位置（排序键 `seq <方向> NULLS LAST` "
                    + "→ 各自结果集末尾），但位置未知本身不可接受：新写入已被 V71 触发器拦截，"
                    + "存量行请跑 V71 回填/人工补齐 seq。",
                channel, sessionId, nullSeqRows, sampleId);
        }
    }

    /**
     * 取下一个 {@code seq}（全局单调递增）· <b>单号写路径统一入口</b>。
     *
     * <p><b>语义</b>：hutool 雪花 ID（{@code IdUtil.getSnowflakeNextId()}）· 全局单调 long。异常/时钟
     * 回拨兜底取<b>纯进程内序数</b>（{@code lastSeq + 1}，<b>不再用墙钟量纲</b>，见
     * {@link #snowflakeCandidate}），并经 {@code lastSeq} 保证返回值<b>恒增</b>
     * （{@code max(candidate, prev+1)}，含回拨与重复）。调用顺序 = seq 顺序（调用方按入参数组序逐个取号即保序）。
     *
     * <p><b>[跨进程基线 seed]</b>：取号前先取「该会话 DB 基数」{@code floor = max(seq)}
     * （{@link #seqFloorOf}，每会话只查一次），并把候选号校准到 {@code >= floor + 1}
     * （{@link #clampSeqToDbFloor}，低于基数时 ERROR 一次）—— 故返回值<b>恒大于该会话 DB 中已有的
     * max(seq)</b>（进程重启 + 墙钟回拨后也不会把新消息排到旧消息之前）。
     *
     * <p><b>实现</b>：直接复用 {@link #nextSeqBlock(String, int)} 的 {@code count=1} 分支
     * （同一次 {@code updateAndGet} 取 {@code [base]}）—— 两条路径共用同一个 CAS 序列化点，
     * 语义与旧实现逐位等价（{@code base = max(clamp(候选), prev+1)} 即原来的恒增值再叠基数下界），
     * 故不存在「两套取号逻辑漂移」的风险。
     *
     * <p><b>恒增范围仅限本进程</b>（叠加「该会话 DB 基数」这条跨进程下界）：{@code lastSeq} 是进程内
     * {@code AtomicLong}，跨 JVM 不共享；多实例同库写同一会话时，因 hutool 默认 workerId（PID/MAC 派生，
     * mod 32）可能撞号，返回值不保证跨进程唯一/单调（详见 {@link #lastSeq} 的「多实例边界」）。
     *
     * @param sessionId 会话 ID（<b>参与取号</b>：用于查/缓存该会话 DB 基数 seed，见 {@link #seqFloorOf}；
     *                  null/空白 → 无基数槽位，退化为纯进程内取号）
     * @return 下一个 seq（<b>严格大于本进程</b>此前所有已分配值，且恒大于该会话 DB 已知 max(seq)；
     *         不保证跨 JVM 单调）
     */
    public long nextSeq(String sessionId) {
        return nextSeqBlock(sessionId, 1)[0];
    }

    /**
     * <b>原子批量取号</b>：一次取 {@code count} 个<b>连续且严格递增</b>的 seq
     * （{@code [base, base+1, ..., base+count-1]}），并保证整块<b>严格大于本进程此前所有已分配值</b>。
     *
     * <p><b>WHY（compact 块内不可被打断）</b>：{@link #appendPostCompactMessages} 落库时整块
     * （boundary → summary → messagesToKeep → attachments → hookResults）必须连续占据位置序。
     * 旧实现「按数组序逐个 {@link #nextSeq}」时，实时落库路径
     * （{@code ChatService.persistAppendedMessage}，在 {@code synchronized (ctx.lock)} 内）与 compact 落库
     * （{@code @Transactional}）<b>两把锁不同源</b> → 并发 append 线程可以恰好在
     * 「boundary 取到 seq=X」与「summary 取到 seq=X+2」之间插进 seq=X+1（它的 CAS 落在两次
     * {@code updateAndGet} 之间）。下轮 run 以 DB(seq) 为准恢复 → DB 序变成
     * {@code [boundary][并发行][summary][kept...]}，而内存视图是
     * {@code [boundary][summary][kept...][并发行]} → 若并发行是 tool_result 而对应 tool_use 在 kept 段，
     * 切片后 <b>tool_result 出现在 tool_use 之前</b>（provider 配对校验失败 / 被中断语义剥离），
     * 正是本批要根治的「拆散 tool_use/tool_result」同类故障。
     *
     * <p><b>为什么「一次 updateAndGet」就不可打断</b>：{@code AtomicLong.updateAndGet} 的 CAS 是本进程
     * seq 的<b>唯一序列化点</b>。本方法在<b>同一次</b> CAS 内把 {@code lastSeq} 从 {@code prev} 直接推进到
     * 块末 {@code base+count-1}，故任何并发取号（{@link #nextSeq} 或另一个 {@link #nextSeqBlock}）只可能：
     * <ul>
     *   <li>在本次 CAS <b>之前</b>成功 → 其返回值 ≤ prev → 整块之后（本方法 retry 时以观测到的
     *       {@code prev+1} 为下界，必高于它）；</li>
     *   <li>在本次 CAS <b>之后</b>成功 → 其下界为 {@code (base+count-1)+1} → 整块之后。</li>
     * </ul>
     * CAS 串行化排除了第三种可能，故<b>并发号绝不落在块内部</b>。反之「逐个取号」= {@code count} 次独立
     * CAS → 块内出现 {@code count-1} 个可被插入的窗口（这就是本方法存在的唯一理由）。
     *
     * <p><b>基准</b>：{@code base = max(clamp(雪花候选), prev + 1)} —— 雪花候选给全局（跨会话）单调基准，
     * {@code prev + 1} 保证即便雪花回拨或候选号被并发线程抢先，也不与已分配值重叠；
     * <b>{@code clamp(...)} = 相对该会话 DB 基数（{@code max(seq)}）的校准</b>
     * （{@link #clampSeqToDbFloor}）→ 整块恒落在该会话 DB 已有行<b>之后</b>（跨重启/回拨不错序）。
     * 校准在 CAS <b>之前</b>做一次（不在 lambda 内），故不受 {@code updateAndGet} 重试影响。
     *
     * <p><b>溢出兜底</b>：{@code base + count - 1} 若越过 {@code Long.MAX_VALUE} → {@code Math.addExact}
     * 抛 {@code ArithmeticException}（catch 后转 {@link IllegalStateException}）<b>显式失败</b>：
     * 不静默回退（回退会让块与已分配区间重叠 → seq 重复 → 读侧顺序不稳）。真实不可达：hutool 雪花号
     * &lt; 5e18，{@code count} 为单次 compact 批大小（数百量级），两者之和远小于
     * {@code Long.MAX_VALUE}(≈9.22e18)。异常在 lambda 内抛出 → CAS 不执行 → {@code lastSeq} 保持不变。
     *
     * <p><b>跨进程边界</b>：块内 {@code base+i} 由递增派生（非逐个雪花号），跨 JVM 时与其它进程雪花号
     * 重叠的概率高于纯雪花——但本类已声明「{@code lastSeq} 只保护单进程」（见 {@link #lastSeq}
     * 「多实例边界」），该边界不因本方法改变。
     *
     * <p><b>非 CC 对齐项，属本仓自研</b>：CC 的 transcript 是内存数组，位置 = 数组下标，
     * 不存在「DB 位置键取号」，故 CC 无对应物；本方法只服务本仓的 DB 落库顺序。
     *
     * @param sessionId 会话 ID（<b>参与取号</b>：用于查/缓存该会话 DB 基数 seed；同 {@link #nextSeq}）
     * @param count     块内元素个数（{@code <= 0} → 返回空数组，<b>不</b>消耗任何号）
     * @return 长度为 {@code count} 的连续严格递增 seq：{@code [0]} = 块首、{@code [count-1]} = 块末
     * @throws IllegalStateException seq 空间耗尽（{@code Long.MAX_VALUE} 饱和；实际不可达）
     */
    public long[] nextSeqBlock(String sessionId, int count) {
        if (count <= 0) {
            return new long[0];
        }
        // [跨进程基数] 候选号先相对该会话 DB 基数（max(seq)）校准 —— 低于基数即 ERROR 一次并抬到
        //   max(seq)+1（clampSeqToDbFloor）。放在 CAS 之外 → 每次调用恰好校准/告警一次，
        //   不受 updateAndGet 的 CAS 重试次数影响（lambda 内做会重复告警）。
        final long candidate = clampSeqToDbFloor(sessionId, snowflakeCandidate());
        final long offset = count - 1L;
        long end;
        try {
            // 唯一次 CAS：把 lastSeq 从 prev 直接推进到「块末」，整块在同一原子步内占位（见 JavaDoc 的不可打断论证）。
            end = lastSeq.updateAndGet(prev -> Math.addExact(Math.max(candidate, prev + 1), offset));
        } catch (ArithmeticException e) {
            throw new IllegalStateException(
                "[MessageService] nextSeqBlock: seq 空间耗尽（base + count - 1 越过 Long.MAX_VALUE）"
                    + " session=" + sessionId + " count=" + count + " —— 显式失败，不返回重叠号", e);
        }
        long base = end - offset;
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] nextSeqBlock: session={} count={} base={} end={}"
                    + "（一次 CAS 原子占位整块，块内不可被并发取号插入）",
                sessionId, count, base, end);
        }
        long[] block = new long[count];
        for (int i = 0; i < count; i++) {
            block[i] = base + i;
        }
        return block;
    }

    public List<ChatMessageDto> listBySession(String sessionId) {
        // 校验 session 存在
        if (sessionMapper.selectOneById(sessionId) == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        // [seq 排序键 + NULL 兜底] 位置序 = seq；NULL 行经 SEQ_ASC_NULLS_LAST_ORDER 稳定推到本结果集
        //   末尾（裸 seq ASC 下 NULL 恒排最前 → 位置未知的行冒充会话首条消息、挤进模型上下文顶部）。见该常量 javadoc。
        //   注意：方向已含在片段里（SQLite 文法要求 NULLS LAST 在方向之后），故不得再叠加 orderBy("seq", true)。
        List<MessageRecord> all = messageMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", sessionId)
                .orderByUnSafely(SEQ_ASC_NULLS_LAST_ORDER));
        warnNullSeqIfAny(sessionId, "listBySession", all);
        List<ChatMessageDto> result = new ArrayList<>(all.size());
        for (MessageRecord m : all) {
            result.add(toDto(m));
        }
        // [token-compact-fix ⑤方案B] 重拉上下文快照补算：对末条 assistant 消息挂
        //   contextTokensUsed/percentLeft/contextWindow（实时 complete 事件推这三字段，重拉丢失；
        //   DB 只存 input/output，cache 未存 → 重算值为近似）。不落库，每次重拉重算。
        applyContextSnapshotToLastAssistant(result, sessionId);
        return result;
    }

    /** [window-paging] 每页默认条数 · 对齐 deepseek-harness session.ts PAGE_MESSAGES=50。 */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * [window-paging] 分页读取会话消息 · GET /sessions/{sessionId}/messages/page。
     *
     * <p><b>语义</b>（对齐 deepseek 有界历史窗口）：前端普通查看只按页加载 —— 缺省（{@code beforeMessageId}
     * 空）= <b>尾页</b>（最新 {@code limit} 条，seq ASC 返回）；{@code beforeMessageId} 非空 = 该消息
     * 之前更早的一页。`seq` 为稳定位置序（全部写路径经 {@link #nextSeq} 雪花取号（进程内单调 long，
     * 且恒大于该会话 DB 已知 max(seq)：跨重启/时钟回拨也不倒挂）；compact 重挂 kept 段也只改 seq），
     * 作游标；多查 1 条判定 hasMore。
     *
     * <p><b>[seq NULL 兜底]</b>：seq 为 NULL 的行（位置键未落 = 数据异常）由
     * {@link #SEQ_DESC_NULLS_LAST_ORDER} 排到 DESC 结果<b>末尾</b>（= 最旧那头，绝不冒充「最新」被算进
     * 尾页），并对该结果集 {@link #warnNullSeqIfAny ERROR 一条}。新 NULL 由 V71 触发器堵在写入口。
     *
     * <p><b>与 {@link #listBySession} 的关系</b>：本方法是前端主通道（有界窗口，session 首载 / F5 / 向上翻页）；
     * {@code listBySession} 全量保留给后端内部（LLM resume / partialCompact / trim 定位）+ 前端 Trace 全程
     * （用户拍板：全量仅限 resume/trace/权威回填，前端查看一律 page）。上下文快照补算仅<b>尾页</b>有意义
     * （{@link #applyContextSnapshotToLastAssistant} 补末条 assistant）。
     *
     * @param sessionId       会话 id（DB 键 "sess-xxx"）
     * @param beforeMessageId 游标消息 id（null/blank = 尾页）；该消息本身排除
     * @param limit           每页条数（&lt;=0 → 回落 {@link #DEFAULT_PAGE_SIZE}）
     * @return {messages(seq ASC), hasMore}；空会话 → 空列表 + hasMore=false
     * @throws NotFoundException     session 不存在 / beforeMessageId 不在该会话
     * @throws IllegalStateException 游标消息 seq 为空（位置键未落 = 数据异常）—— <b>显式失败</b>：
     *                               若静默丢弃该条件会退化成「重复尾页」，前端翻页永久卡住且无日志
     */
    public PageResult listPageBySession(String sessionId, String beforeMessageId, int limit) {
        // [fail loud] sessionId 空 = 上游路径变量异常。若不拦，eq("session_id", null) 会被
        //   QueryColumnBehavior(IGNORE_NULL) 静默丢弃 → 退化成「跨会话尾页」（数据越权 + 顺序错乱），
        //   故显式失败而非交给 mapper。
        if (sessionId == null || sessionId.isBlank()) {
            throw new NotFoundException("Session " + sessionId + " not found（sessionId 为空：拒绝跨会话分页）");
        }
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        final int pageSize = limit > 0 ? limit : DEFAULT_PAGE_SIZE;
        QueryWrapper qw = QueryWrapper.create().eq("session_id", sessionId);
        if (beforeMessageId != null && !beforeMessageId.isBlank()) {
            MessageRecord pivot = messageMapper.selectOneByQuery(
                QueryWrapper.create().eq("session_id", sessionId).eq("id", beforeMessageId));
            if (pivot == null) {
                throw new NotFoundException("Message " + beforeMessageId + " not found in session " + sessionId);
            }
            // [seq 排序键] 游标按位置键 seq（非 created_at —— compact 重挂 kept 段只改 seq、created_at 保持
            //   原值；若按 created_at 游标，重挂后的 kept 行位置与时间序错位 → 翻页重复/丢行）。
            //
            // [fail loud · 不得静默丢条件] mybatis-flex QueryColumnBehavior 默认 ignoreFunction = IGNORE_NULL，
            //   value == null 时该条件被<b>静默丢弃</b>（实测 eq("session_id",..) + lt("seq",(Object)null) 生成
            //   的 SQL 与不带游标完全一致，无任何 warn/error）。后果：带 beforeMessageId 的请求退化成
            //   「再取一次尾页」→ 前端 handleLoadOlder 拿到重复页（按 id 去重后 fresh=0）→ hasMore 恒 true
            //   但游标永不前进（卡死在尾页）。seq 为空 = V70 位置键未落（数据异常），此处显式失败。
            //   （旧实现 lt("created_at", pivot.getCreatedAt()) 因该列 NOT NULL 结构上不可能命中此退化路径 ——
            //    即本批新引入了一个无守卫的隐含不变量，故必须补守卫。）
            Long pivotSeq = pivot.getSeq();
            if (pivotSeq == null) {
                throw new IllegalStateException(
                    "[MessageService] listPageBySession: 游标消息 seq 为空（该行位置键未落 = 数据异常），"
                        + "无法生成 beforeMessageId 游标（静默丢条件会退化成重复尾页并使翻页永久卡住）"
                        + " sessionId=" + sessionId + " pivot.id=" + pivot.getId());
            }
            qw.lt("seq", pivotSeq);
        }
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] listPageBySession: session={} before={} limit={}（window-paging 有界窗口）",
                sessionId, beforeMessageId, pageSize);
        }
        // DESC 取 pageSize+1 → 多 1 条 ⇒ hasMore；reverse 回 ASC 供前端顺序渲染（seq 位置序）
        // [fail loud · limit 溢出兜底] pageSize = Integer.MAX_VALUE 时 pageSize+1 溢出为负 → limit(0,-N)，
        //   SQLite 语义下 NEGATIVE LIMIT = 不限量（一次拉全表），与调用方意图相反且无日志。饱和到
        //   Integer.MAX_VALUE（= 调用方语义「全要」）而非改小成某个上限（不静默改调用方语义）。
        int probe = pageSize == Integer.MAX_VALUE ? Integer.MAX_VALUE : pageSize + 1;
        // [seq NULL 兜底] DESC 侧用 SEQ_DESC_NULLS_LAST_ORDER（**不是** NULLS FIRST）：
        //   后者会把 NULL 顶到 DESC 结果最前 = 冒充「最新」并混进尾页（位置未知的行冒充刚写入的那条）。
        //   本片段的语义 = NULL 落在 DESC 结果末尾（最旧那头）；且带游标的分页含 seq < pivot 条件，
        //   NULL 行本就不会被游标页取到 → 分页通道对 NULL 行不可见（可见性由 warnNullSeqIfAny 的 ERROR 承担）。
        //   WHY 不用旧的 `seq IS NULL, seq DESC` 前置键：那是表达式 → 实测多一次 USE TEMP B-TREE FOR ORDER BY，
        //   把「DESC LIMIT 51 走索引取够即停」退化成「全量排序再取 51 行」。见 SEQ_ASC_NULLS_LAST_ORDER 的 javadoc。
        List<MessageRecord> desc = messageMapper.selectListByQuery(
            qw.orderByUnSafely(SEQ_DESC_NULLS_LAST_ORDER).limit(0, probe));
        warnNullSeqIfAny(sessionId, "listPageBySession", desc);
        boolean hasMore = desc.size() > pageSize;
        List<MessageRecord> page = desc.size() > pageSize ? desc.subList(0, pageSize) : desc;
        List<MessageRecord> asc = new ArrayList<>(page);
        java.util.Collections.reverse(asc);
        List<ChatMessageDto> result = new ArrayList<>(asc.size());
        for (MessageRecord m : asc) {
            result.add(toDto(m));
        }
        if (beforeMessageId == null || beforeMessageId.isBlank()) {
            // 尾页才补上下文快照（前页更早消息的 usage 已随 DB 存储，无需重算）
            applyContextSnapshotToLastAssistant(result, sessionId);
        }
        if (log.isInfoEnabled()) {
            log.info("[MessageService] listPageBySession: session={} 返回 {} 条（hasMore={}, before={}）",
                sessionId, result.size(), hasMore, beforeMessageId);
        }
        // total = 会话消息总数（messages 表实际非 meta 行数，权威口径）——前端轨迹徽标全量。
        //   不用 sessions.messageCount：该列只在部分写路径自增（实测 sess-1d389722 = 4，真实 64），会少算 assistant/tool。
        int total = countNonMetaMessages(sessionId);
        return new PageResult(result, hasMore, total);
    }

    /** [window-paging] 分页响应 · {messages(seq ASC), hasMore, total}。total = 会话消息总数
     *  （sessions.messageCount · 非 meta 口径）——前端轨迹 tab 徽标全量用，避免拿「已加载页」条数当全量。 */
    public record PageResult(List<ChatMessageDto> messages, boolean hasMore, int total) {}

    /**
     * 会话消息总数（sessions.messageCount · 非 meta 口径）· GET /sessions/{sessionId}/messages/count。
     * 轻量、零 COUNT 查询，供前端定时轮询轨迹徽标实时刷新。
     *
     * @param sessionId 会话 ID（DB 键）
     * @return 会话非 meta 消息总数
     * @throws NotFoundException session 不存在
     */
    public int countBySession(String sessionId) {
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        return countNonMetaMessages(sessionId);
    }

    /**
     * 会话非 meta 消息行数（权威）· {@code messages} 表 COUNT。
     *
     * <p><b>WHY 不读 sessions.messageCount</b>：该列只在部分落库路径自增（实测 user 4 条但表内
     * 64 条非 meta），会少算 assistant/tool → 轨迹徽标/分页 total 偏小。COUNT 走 session_id 索引，
     * 轮询（5s）与分页开销可接受。{@code is_meta NULL} 显式包含（V51 存量旧行），与 TraceView
     * {@code !isMeta} 过滤同口径。
     */
    private int countNonMetaMessages(String sessionId) {
        long n = messageMapper.selectCountByQuery(
            QueryWrapper.create().where("session_id = ? AND (is_meta IS NULL OR is_meta != 1)", sessionId));
        return (int) n;
    }

    /** 删除本会话指定 subtype 的消息行（如 hook_additional_context：CC 不落 transcript，我们覆盖式永保 1 条）。 */
    public int deleteBySessionAndSubtype(String sessionId, String subtype) {
        return deleteBySessionAndSubtype(sessionId, subtype, null);
    }

    /**
     * 删除本会话指定 subtype 的消息行（可排除一条 id）· <b>「先插后删」覆盖式写的删除半边</b>。
     *
     * <p><b>WHY（保证 DB 中该 subtype 至少 1 条）</b>：LlmAgentLoop §14 SessionStart hook_additional_context
     * 覆盖式注入改为「先插新份 → 再删其它旧份」——调用方在插入新份<b>成功之后</b>才调用本方法，
     * 并以 {@code excludeId = 新份 id} 排除刚插入的行，删除其它同 subtype 行。任何时刻 DB 中该 subtype
     * 至少 1 条（插入失败则不调删除 → 旧份仍在），消除「删了旧、新份没落库」的丢份窗口。
     *
     * @param sessionId 会话 ID（DB 键；null/空白 → 0）
     * @param subtype   消息 subtype（null/空白 → 0）
     * @param excludeId 需保留（不删）的消息 id；null/空白 → 等同 {@link #deleteBySessionAndSubtype(String, String)}
     * @return 删除行数
     */
    public int deleteBySessionAndSubtype(String sessionId, String subtype, String excludeId) {
        if (sessionId == null || subtype == null || sessionId.isBlank() || subtype.isBlank()) {
            return 0;
        }
        QueryWrapper qw = QueryWrapper.create().eq("session_id", sessionId).eq("subtype", subtype);
        if (excludeId != null && !excludeId.isBlank()) {
            qw.ne("id", excludeId);
        }
        return messageMapper.deleteByQuery(qw);
    }

    /**
     * [token-compact-fix ⑤方案B] 重拉上下文快照补算 · 用户拍板：不落库，每次重算。
     *
     * <p><b>WHY</b>: 实时 {@code message.complete} 事件推 contextWindow/contextTokensUsed/percentLeft
     * （ChatService:559-581，对齐 CC context.ts current_usage/percentLeft），历史消息重拉
     * （GET /messages）不落库 → 重拉丢失。本方法在重拉结果上对<b>末条 assistant 消息</b>（usage 非空，
     * 对齐 toDto :691 input/output 任一非 null 判据）补算三字段，使前端重拉后上下文余量展示与实时一致。
     *
     * <p><b>[P3-b] 扫描下界 = 最后一个 compact boundary 之后</b>（与请求面同源）：压缩后 DB 仍保留
     * 压缩前的旧 assistant（append-only），若按全表取「末条带 usage 的 assistant」，压缩后（下一轮
     * 尚未发生）取到的正是压缩前那条 → 重拉显示压缩前大值（数字不降）。切片后无 assistant 时
     * <b>不出快照</b> = 压缩后归零（对齐 CC full compact 后 {@code getCurrentUsage} 找不到带 usage
     * 的 assistant，compact.ts:767-777）。
     *
     * <p><b>重算公式（[B1 方案A] V53 cache 落库后完整 usage · 协议分派对齐实时）</b>:
     * {@code contextTokensUsed} 由 {@link ContextUsageCalculator#computeContextTokensUsed} 按协议分派：
     * Anthropic → input + cacheRead + cacheCreate（Claude API 三字段独立）；OpenAI/DeepSeek → 仅 input
     * （prompt_tokens 已含 cache hit，加 cacheRead 会双计）。与实时 complete 事件 ChatService:572-578
     * 同源单点，消除两处漂移。contextWindow = 模型表 models.max_context_tokens（回落 1M，对齐实时路径
     * ChatService:570-571）；模型不可判定（会话 override + settings.mainModelName 均空）→ 不出快照
     * （对齐实时 usage null 省略语义）。percentLeft = max(0, round((1 - used/window)*100))。
     *
     * @param messages  重拉结果列表（toDto 投影后；原地替换末条 assistant 消息）
     * @param sessionId 会话 ID（DB 键）
     */
    private void applyContextSnapshotToLastAssistant(List<ChatMessageDto> messages, String sessionId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // [P3-b · 压缩后归零] 只在<b>最后一个 compact boundary 之后</b>找 assistant —— 与请求面同源
        //   （模型面 = LlmAgentLoop 循环入口经 BoundaryReader.getMessagesAfterCompactBoundary 的切片）。
        //   WHY 必须（要修的缺陷）：/compact 后 DB 是 append-only（旧 assistant 行仍在），压缩后尚无
        //   新一轮时「全表末条带 usage 的 assistant」恰是<b>压缩前</b>那条 → 重拉按其 input/cache 重算
        //   → 前端重拉后仍显示压缩前的大值（数字不降）。CC 对照：full compact 产出的 messages 数组
        //   不含旧 assistant（compact.ts:767-777）→ getCurrentUsage 找不到 → 指示器归零。
        //   本切片后无 assistant → <b>不出快照</b>（与 CC 归零等价；前端无快照即回落为不显示该值）。
        int boundaryIdx = BoundaryReader.findLastCompactBoundaryIndex(messages);
        int scanFrom = boundaryIdx >= 0 ? boundaryIdx + 1 : 0;
        int lastAsstIdx = -1;
        for (int i = messages.size() - 1; i >= scanFrom; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant
                    && (m.inputTokens() != null || m.outputTokens() != null)) {
                lastAsstIdx = i;
                break;
            }
        }
        if (lastAsstIdx < 0) {
            if (log.isDebugEnabled()) {
                log.debug("[MessageService] 重拉上下文快照跳过: 会话 {} 最后 compact boundary(下标={}) 之后"
                        + "无 usage 非空的 assistant 消息（压缩后归零语义 · 对齐 CC getCurrentUsage undefined）",
                    sessionId, boundaryIdx);
            }
            return;
        }
        ChatMessageDto asst = messages.get(lastAsstIdx);
        // 会话模型：会话 override → settings.mainModelName → null（不可判定 → 不出快照）
        String model = resolveSessionModel(sessionId);
        if (model == null) {
            if (log.isDebugEnabled()) {
                log.debug("[MessageService] 重拉上下文快照跳过: 会话 {} 模型不可判定（会话 override + "
                    + "settings.mainModelName 均空），末条 assistant {} 不出快照", sessionId, asst.id());
            }
            return;
        }
        // [token-compact-fix 修复] contextTokensUsed 按协议分派（与实时 complete 事件同源单点，
        //   ContextUsageCalculator）——Anthropic → input+cacheRead+cacheCreate（三字段独立）；
        //   OpenAI/DeepSeek → 仅 input（prompt_tokens 已含 cache hit，加 cacheRead 会双计，主模型
        //   DeepSeek 必走此路）。判定链 = isAnthropic（同 ChatService.providerTypeForModel：
        //   ModelNameResolver.resolve → provider.type=='anthropic'）。DB 读回 Integer cache →
        //   null 容错转 long（V53 前旧行无 cache）。
        boolean anthropic = ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, model);
        long used = ContextUsageCalculator.computeContextTokensUsed(
            asst.inputTokens() != null ? asst.inputTokens() : 0L,
            asst.inputCacheReadTokens() != null ? asst.inputCacheReadTokens().longValue() : null,
            asst.inputCacheCreationTokens() != null ? asst.inputCacheCreationTokens().longValue() : null,
            anthropic);
        long window = resolveContextWindowForModel(model);
        if (window <= 0) {
            return;
        }
        int percentLeft = Math.max(0, (int) Math.round((1 - (double) used / window) * 100));
        messages.set(lastAsstIdx, asst.withContextSnapshot(used, percentLeft, window));
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] 重拉上下文快照补算: session={} 末条 assistant={} model={} "
                    + "contextTokensUsed={} contextWindow={} percentLeft={} "
                    + "（input={} cacheRead={} cacheCreation={} anthropic={}，协议分派对齐实时）",
                sessionId, asst.id(), model, used, window, percentLeft,
                asst.inputTokens(), asst.inputCacheReadTokens(), asst.inputCacheCreationTokens(), anthropic);
        }
    }

    /**
     * [token-compact-fix ⑤方案B] 解析会话当前模型名 · 对齐 ChatService.resolveModelNameForSession
     * 四层链的会话层 + settings 层（请求体层在重拉路径不存在）：会话 override（sessions.model_name）
     * → settings.mainModelName → null（调用方跳过快照）。
     *
     * @param sessionId 会话 ID
     * @return 模型名（全名/裸名）；不可判定 → null
     */
    private String resolveSessionModel(String sessionId) {
        try {
            SessionRecord session = sessionMapper.selectOneById(sessionId);
            if (session != null && session.getModelName() != null && !session.getModelName().isBlank()) {
                return session.getModelName();
            }
            if (settingsMapper != null) {
                SettingsRecord s = settingsMapper.selectOneById(1);
                if (s != null && s.getMainModelName() != null && !s.getMainModelName().isBlank()) {
                    return s.getMainModelName();
                }
            }
        } catch (Exception e) {
            log.warn("[MessageService] 会话模型解析失败, 上下文快照跳过: sessionId={} err={}", sessionId, e.toString());
        }
        return null;
    }

    /**
     * [token-compact-fix ⑤方案B] 解析模型上下文窗口 · 对齐实时路径 ChatService:570-571
     * （ModelRecord.max_context_tokens，回落 1M）+ 参照 CompactThresholdSystem.getContextWindowForModel
     * 的 DB model 元数据窗口来源（models.max_context_tokens，AgentLoopContextFactory:293-294）。
     *
     * <p>经 {@link ModelNameResolver#resolve}（全名感知，models.name 精确匹配 enabled model）反查
     * ModelRecord 取 {@code max_context_tokens}；未命中/未配置/异常 → 回落 1_048_576（与实时 complete
     * 事件同值 ChatService:571，非 CompactConstants.CONTEXT_1M_WINDOW=1_000_000）。
     *
     * @param model 模型名（全名/裸名；null → 直接回落 1M）
     * @return 模型上下文窗口 token 数（> 0）
     */
    private long resolveContextWindowForModel(String model) {
        if (model == null || modelMapper == null || providerMapper == null) {
            return 1_048_576L;
        }
        try {
            ModelRecord modelRecord = ModelNameResolver.resolve(modelMapper, providerMapper, model);
            if (modelRecord != null && modelRecord.getMaxContextTokens() != null
                    && modelRecord.getMaxContextTokens() > 0) {
                return modelRecord.getMaxContextTokens();
            }
            if (log.isDebugEnabled()) {
                log.debug("[MessageService] 重拉上下文窗口: 模型 {} 无 max_context_tokens 或未命中，回落 1M",
                    model);
            }
        } catch (Exception e) {
            log.warn("[MessageService] 重拉上下文窗口解析失败, 回落 1M: model={} err={}", model, e.toString());
        }
        return 1_048_576L;
    }

    /**
     * 会话恢复专用读取 · 对齐 CC conversationRecovery.ts:167-255
     * {@code deserializeMessagesWithInterruptDetection}（S1 会话恢复补中断语义）。
     *
     * <p><b>WHY</b>：{@link #listBySession} 返回 DB 原始行（seq ASC 位置序），无中断检测 /
     * tool_use 配对过滤 / "Continue" sentinel 注入 —— 中断 turn 恢复后"有问无答"。恢复/续聊
     * 加载历史的通道（ChatController background、PartialCompactService、AwaySummaryController）
     * 消费本方法，对 DB 消息流应用 CC 同款反序列化（未配对 tool_use 剥离 / 孤立 thinking 剥离 /
     * 纯空白 assistant 剥离 / detectTurnInterruption / "Continue" sentinel 注入）。
     *
     * <p><b>不破坏 {@link #listBySession}</b>：前端 GET /messages 原始展示仍走 listBySession
     * （本方法为恢复消费点专用漏斗，DB 权威写入不变）。
     *
     * @param sessionId 会话 ID
     * @return 反序列化（中断语义注入）后的消息列表
     */
    public List<ChatMessageDto> listForResume(String sessionId) {
        List<ChatMessageDto> raw = listBySession(sessionId);
        return SessionResumeDeserializer.deserializeWithInterruptDetection(raw).messages();
    }

    /**
     * 会话恢复专用读取（排除在途用户消息）· fix-loop-resume-history。
     *
     * <p><b>WHY</b>: loop 主路径 resume 时当前用户消息已被 {@code ChatController.createUserMessage}
     * 落 DB，{@link #listForResume} 会把它作为末条 → deserializer 误判 INTERRUPTED_PROMPT 并在其后
     * splice sentinel（破坏本轮回复）。本方法先排除 excludeMessageId 再应用中断语义漏斗，
     * 使 history 末尾即上一轮真实终止状态（对齐 CC conversationRecovery.ts:485-512
     * {@code loadConversationForResume} 全量历史注入）。
     *
     * <p>excludeMessageId == null/blank → 不排除（非流式测试路径回落，对齐
     * LlmAgentLoop :2428-2430 streamUserMessageId==null 回落「转录非空」）。
     *
     * <p><b>不破坏 {@link #listBySession}</b>：DB 权威读取不变（前端 GET /messages 仍走
     * listBySession 原始展示）；本方法为 loop 主路径恢复消费点专用漏斗。
     *
     * @param sessionId       会话 ID（DB 键 "sess-xxx"）
     * @param excludeMessageId 需排除的消息 id（当前 in-flight 用户消息；null=不排除）
     * @return 反序列化（中断语义注入）后的历史消息列表
     */
    public List<ChatMessageDto> listForResumeExcluding(String sessionId, String excludeMessageId) {
        return listForResumeExcluding(listBySession(sessionId), excludeMessageId);
    }

    /**
     * 会话恢复专用读取（排除在途用户消息）· 已取原始转录的内存派生重载。
     *
     * <p><b>WHY（低效非错误修复）</b>：LlmAgentLoop.doRun 注入块与续跑 skill 恢复块每 run 各自
     * {@link #listBySession} 全量读取同一会话消息 = 冗余 DB I/O。注入块改为复用主流程预取一次的
     * 原始转录（LlmAgentLoop.doRun:1917 前一次性读取缓存），经本重载在内存派生排除 + 中断语义
     * 漏斗产物，skill 恢复块直接消费缓存，消除重复查询。
     *
     * <p>语义与 {@link #listForResumeExcluding(String, String)} 完全一致：先排除 excludeMessageId
     * （null/blank = 不排除，回落「转录非空」全量语义），再应用
     * {@code SessionResumeDeserializer.deserializeWithInterruptDetection} 中断语义漏斗。
     *
     * @param raw              已从 DB 读取的原始消息列表（seq ASC 位置序；null/空 → 恒返回空列表）
     * @param excludeMessageId 需排除的消息 id（当前 in-flight 用户消息；null=不排除）
     * @return 反序列化（中断语义注入）后的历史消息列表
     */
    public List<ChatMessageDto> listForResumeExcluding(List<ChatMessageDto> raw, String excludeMessageId) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (excludeMessageId == null || excludeMessageId.isBlank()) {
            return SessionResumeDeserializer.deserializeWithInterruptDetection(raw).messages();
        }
        List<ChatMessageDto> filtered = new ArrayList<>(raw.size());
        for (ChatMessageDto m : raw) {
            if (m != null && excludeMessageId.equals(m.id())) {
                continue;
            }
            filtered.add(m);
        }
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] listForResumeExcluding(raw): raw={} filtered={}（排除在途用户消息 {}）",
                raw.size(), filtered.size(), excludeMessageId);
        }
        return SessionResumeDeserializer.deserializeWithInterruptDetection(filtered).messages();
    }

    public ChatMessageDto getById(String id) {
        MessageRecord m = messageMapper.selectOneById(id);
        if (m == null) throw new NotFoundException("Message " + id + " not found");
        return toDto(m);
    }

    /** [mid-turn 加固 2026-08-25] 消息是否存在（非抛）· 排队 user 补落库前检查已原位落库
     *  （replayAndPersist 单调保序），避免 3 参 now() 重复落库覆盖 created_at 顺序。 */
    public boolean existsById(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return messageMapper.selectOneById(id) != null;
    }

    /**
     * [queue-order-fix 方案A] 排队命令消费时落库 user 消息（指定 id = 队列 uuid）· 对齐 CC
     *   enqueue 内存队列 → 消费时 createUserMessage 落库（落库顺序 = 消费顺序，修复 busy 时
     *   user 消息提前落库插入到未落库 assistant 前的 DB 顺序错位）。
     *
     * <p>busy 时 ChatController 不调 {@link #createUserMessage}（预生成 id 入队），
     * CronIdleExecutor 消费 busy-queued 时经本方法落库（此时前一轮 assistant 已落库 → 顺序正确）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content       排队命令原文（QueueItem.value；null → 空串）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content) {
        // [created_at 单调分配器] 传 null → 8 参重载走 nextCreatedAt（per-session 单点取号），
        //   即「该会话最新」时间戳（语义同旧 now()，但与其它写路径同源单调）。
        return createQueuedUserMessage(sessionId, userMessageId, content, null);
    }

    /**
     * 4 参重载：{@link #createQueuedUserMessage(String, String, String)} + 显式落库时间戳。
     *
     * <p><b>WHY（DB 单键排序并列修复）</b>：replayAndPersist 同一循环内 assistantA / queued-user /
     * assistantB 连续 insert 时，默认 {@code OffsetDateTime.now()} 毫秒精度下 created_at 可能并列 →
     * 历史上 ORDER BY created_at 单键并列序不确定，queued-user 可能间歇排在 assistantB 之后。
     * 调用方（ChatService）传 {@code baseTs.plusNanos(seq)} 单调时间戳 → created_at 严格位于
     * assistantA 与 assistantB 之间。
     *
     * <p><b>[V70 起]</b> 会话内位置序已改由 {@code seq} 承担（读侧 ORDER BY seq，见
     * {@link #nextSeq}）；本方法的显式 created_at 只保证<b>展示时间</b>单调（前端「X 分钟前」），
     * 位置正确性不再依赖它（既有调用点保留，零行为回退）。
     *
     * <p>isMeta 恒 false（排队命令为普通用户输入，非系统生成）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content       排队命令原文（QueueItem.value；null → 空串）
     * @param createdAt     落库 created_at（调用方给定的单调 ts；null → {@link #nextCreatedAt} 取号）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content,
                                                          OffsetDateTime createdAt) {
        return createQueuedUserMessage(sessionId, userMessageId, content, createdAt, false);
    }

    /**
     * 5 参重载：{@link #createQueuedUserMessage(String, String, String, OffsetDateTime)} + 显式 isMeta。
     *
     * <p><b>[cron-task-inject-align C1] isMeta 落库</b>：cron 触发的 user prompt 落库 isMeta=true
     * （CC original: isMeta，useScheduledTasks.ts:76 cron 入队 isMeta 语义 —— UI 隐藏但模型可见），
     * 供 CronIdleExecutor.executeQueuedInput 消费 cron（workload=WORKLOAD_CRON）时落库。busy-queued
     * 排队 prompt 恒 isMeta=false（普通用户输入）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content       排队命令原文（QueueItem.value；null → 空串）
     * @param createdAt     落库 created_at（调用方给定的单调 ts；null → {@link #nextCreatedAt} 取号）
     * @param isMeta        CC original: isMeta（messages.ts:3753 / useScheduledTasks.ts:76）—
     *                      true = 系统生成消息（UI 隐藏、模型可见，V51 is_meta 列落库）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content,
                                                          OffsetDateTime createdAt, boolean isMeta) {
        return createQueuedUserMessage(sessionId, userMessageId, content, createdAt, isMeta, null);
    }

    /**
     * 6 参重载：{@link #createQueuedUserMessage(String, String, String, OffsetDateTime, boolean)} +
     * 显式 queuedOrigin（排队来源标记）。
     *
     * <p><b>[P0-1 OD-1/OD-3] queuedOrigin 落库</b>：mid-turn 注入的 busy-queued 排队用户消息
     * （真实用户工作途中消息）落库 queued_origin='busy-queued'（V67 列），resume 重放 toDto 读回
     * → 发送层按标记重新包壳。仅 busy-queued 传 'busy-queued'；空闲 cron/busy / slash meta/result/reject
     * / 其余生产调用方恒 null（红线：空闲路径零标记，原文发）。imagePasteIds/userAttachments 缺省 null
     * （纯文本排队命令无附件）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content       排队命令原文（QueueItem.value；null → 空串）
     * @param createdAt     落库 created_at（调用方给定的单调 ts；null → {@link #nextCreatedAt} 取号）
     * @param isMeta        CC original: isMeta（messages.ts:3753 / useScheduledTasks.ts:76）—
     *                      true = 系统生成消息（UI 隐藏、模型可见，V51 is_meta 列落库）
     * @param queuedOrigin  排队来源标记（'busy-queued'；null = 非排队消息不标记）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content,
                                                          OffsetDateTime createdAt, boolean isMeta, String queuedOrigin) {
        return createQueuedUserMessage(sessionId, userMessageId, content, createdAt, isMeta, queuedOrigin,
            null, null);
    }

    /**
     * 8 参重载：{@link #createQueuedUserMessage(String, String, String, OffsetDateTime, boolean, String)}
     * + 显式 imagePasteIds + userAttachments（附件落库，OD-D13 busy 图片）。
     *
     * <p><b>[OD-D5/OD-D13] 附件落库</b>：mid-turn 注入的 busy 带图排队用户消息落库
     * {@code image_paste_ids}（V46 JSON 数组；F5 前端按 id 拉图）+ {@code user_attachments}
     * （V63，本期 busy 图 userAttachments 恒 null —— base64 直传无附件表 contentId，imagePasteIds
     * 链路承载）。content 仍存<b>原文 RAW</b>（inj.content = QueueItem.value，壳不落库，红线 §五.4）。
     *
     * @param sessionId      目标会话（short）
     * @param userMessageId  预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content        排队命令原文（QueueItem.value；null → 空串）
     * @param createdAt      落库 created_at（调用方给定的单调 ts；null → {@link #nextCreatedAt} 取号）
     * @param isMeta         CC original: isMeta（messages.ts:3753 / useScheduledTasks.ts:76）
     * @param queuedOrigin   排队来源标记（'busy-queued'；null = 非排队消息不标记）
     * @param imagePasteIds  图片粘贴 id 列表（V46 JSON 数组；null/空 → 列 NULL，纯文本排队消息）
     * @param userAttachments 附件快照（V63；本期 busy 图 null，预留 path/upload 附件回补通道）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content,
                                                          OffsetDateTime createdAt, boolean isMeta, String queuedOrigin,
                                                          List<String> imagePasteIds,
                                                          List<ChatMessageDto.UserAttachmentInfo> userAttachments) {
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " not found");
        if (userMessageId == null || userMessageId.isBlank()) {
            userMessageId = generateId("msg");
        }
        MessageRecord m = new MessageRecord();
        m.setId(userMessageId);
        // [userMessageId] 排队用户消息 = 排队命令 uuid（自身 id，CC parentUuid 链根）· V47 列落库
        m.setUserMessageId(userMessageId);
        m.setSessionId(sessionId);
        m.setRole(Role.user.name());
        m.setAuthor(null);
        m.setContent(content != null ? content : "");
        m.setReasoning(null);
        m.setReasoningDurationMs(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        // [created_at 单调分配器] null → nextCreatedAt(sessionId) per-session 取号（恒晚于该会话所有已有行）
        m.setCreatedAt((createdAt != null ? createdAt : nextCreatedAt(sessionId)).toString());
        m.setCwd(CwdResolution.getCwd(sessionId));
        // [OD-D13] 排队命令附件落库：纯文本（imagePasteIds=null）→ V46 列 NULL（现状零变化）；
        //   busy 带图 → 落 imagePasteIds JSON 数组（F5 前端按 id 拉图）。userAttachments 本期 null
        //   （base64 直传无附件表 contentId，V63 预留 path/upload 通道回补）。
        m.setImagePasteIds(imagePasteIds != null && !imagePasteIds.isEmpty()
            ? serializeStringList(imagePasteIds) : null);
        if (userAttachments != null && !userAttachments.isEmpty()) {
            m.setUserAttachments(serializeUserAttachments(userAttachments));
        }
        // [C1] isMeta 落库 · CC original: isMeta（messages.ts:3753 createUserMessage({..., isMeta:true}) /
        //   useScheduledTasks.ts:76 cron 入队 isMeta 语义）· V51 is_meta 列；cron=true / busy-queued=false
        m.setIsMeta(isMeta);
        // [P0-1 OD-1/OD-3] queuedOrigin 落库 · CC original: queued_command origin 语义 · V67
        //   queued_origin 列；仅 busy-queued 传 'busy-queued'（resume 重放 toDto 读回 → 发送层重包壳）；
        //   空闲 cron/busy / slash meta/result/reject 恒 null（红线：空闲路径零标记，原文发）
        m.setQueuedOrigin(queuedOrigin);
        // [seq 排序键] 位置键取号（雪花全局单调；读侧 listBySession/listPageBySession ORDER BY seq）
        m.setSeq(nextSeq(sessionId));
        messageMapper.insert(m);
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(session);
        // [streamTopic-session-level] 会话级单 topic（对齐 CC 会话单一事件流）；createQueuedUserMessage
        //   返回值仅由后端消费（原位落库忽略返回值），语义一致。
        String streamTopic = "/topic/sessions/" + sessionId + "/stream";
        return new MessageCreatedResponse(userMessageId, "msg-stub-pending", streamTopic, false);
    }

    public MessageCreatedResponse createUserMessage(String sessionId, SendMessageRequest req) {
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " not found");

        // 1. 持久化用户消息
        MessageRecord m = new MessageRecord();
        m.setId(generateId("msg"));
        // [userMessageId] user 消息 = 自己的 id（CC parentUuid 链根，sessionStorage.ts:1066-1068
        //   isChainParticipant 后 parentUuid = message.uuid）· V47 列落库
        m.setUserMessageId(m.getId());
        m.setSessionId(sessionId);
        m.setRole(Role.user.name());
        m.setAuthor(null);
        m.setContent(req.content());
        m.setReasoning(null);
        // reasoningDurationMs：user 消息恒 null（后端测推理耗时仅 assistant 消息；对称风格）
        m.setReasoningDurationMs(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        // [created_at 单调分配器] per-session 取号（恒晚于该会话所有已有行）
        m.setCreatedAt(nextCreatedAt(sessionId).toString());
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059 transcriptMessage.cwd = getCwd()。
        //   消息产生/落库时戳入当前工作目录，供 /resume 恢复目录上下文（:2522 projectPath=firstMessage.cwd）。
        m.setCwd(CwdResolution.getCwd(sessionId));
        // [V46] imagePasteIds · CC original: imagePasteIds（messages.ts:460-523 createUserMessage
        //   签名）· 若 user 消息经 HTTP 发送图片附件（req.attachments() 中 type=image 或
        //   mediaType=image/* 项），取其 contentId（ImageAttachmentStore 数字 id 串）落库，
        //   供前端重拉缩略图 + TokenEstimator 图片 token 估算。无图片 → null。
        m.setImagePasteIds(serializeStringList(imagePasteIdsFromAttachments(req.attachments())));
        // [userAttachments] 附件快照（type+filename 全类型含图片）· user 消息落库时持久化，供前端 F5 重拉显示附件 chip
        m.setUserAttachments(serializeUserAttachments(userAttachmentsFromAttachments(req.attachments())));
        // [C1] 普通用户输入非系统生成 · CC original: isMeta=false（messages.ts:3753 createUserMessage
        //   默认非元消息）· V51 is_meta 列显式落 false
        m.setIsMeta(false);
        // [seq 排序键] 位置键取号（雪花全局单调）
        m.setSeq(nextSeq(sessionId));
        messageMapper.insert(m);

        // 2. 自增 sessions.messageCount
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(session);

        // 3. 会话级 streamTopic（对齐 CC 会话单一事件流）· 前端按事件 userMessageId/assistantMessageId
        //    路由归组，topic 不再编码消息 id；assistantMessageId 占位（跑完后才确定）。
        String assistantId = "msg-stub-pending";
        String streamTopic = "/topic/sessions/" + sessionId + "/stream";

        // [queue-first B6] queued=false 占位：busy 判定在 ChatController.send 前置（若 turn 运行中
        //   再发 → controller 以 queued=true 重建响应并走 enqueueBusyPrompt，不调 processUserMessage）。
        return new MessageCreatedResponse(m.getId(), assistantId, streamTopic, false);
    }

    /**
     * 落一条系统 subtype 消息 · CC original: {@code createScheduledTaskFireMessage}
     * （messages.ts:4385-4392，system + subtype='scheduled_task_fire' + isMeta=false）。
     *
     * <p><b>[cron-fire-visible] WHY</b>: CC onFireTask（useScheduledTasks.ts:110-113）在 cron 触发时
     * {@code setMessages([...prev, createScheduledTaskFireMessage('Running scheduled task (<time>)')])}
     * 落可见系统消息 → 前端 SystemTextMessage.tsx:137 按 subtype 渲染「任务执行中」。Java 侧经本方法
     * 落 DB 消息表（前端 GET /messages 读转录）——转录序 = [scheduled_task_fire, cron user, assistant]。
     *
     * <p><b>role=system 落库合规</b>: messages.role TEXT 含 'system'（V1__init_schema.sql:63）；
     * SessionResumeDeserializer 判末条 turn-relevant 时跳过 system（:353-357/441-442）→ 不破坏
     * 中断检测/resume。subtype 供前端判别渲染（CC SystemTextMessage.tsx:137）；DB toDto 读回 subtype
     * （MessageService:567）。isMeta 已 V51 落库；scheduled_task_fire isMeta=false 仍可见
     * （CC createScheduledTaskFireMessage messages.ts:4385-4392 isMeta=false，与 CC 一致）。
     *
     * @param sessionId 目标会话（short；session 行不存在 → NotFoundException，调用方 try/catch）
     * @param subtype   CC original: subtype（scheduled_task_fire / 其他系统消息）
     * @param content   系统消息正文（前端显示；null → 空串）
     * @return 落库后的消息 id
     */
    public String appendSystemSubtypeMessage(String sessionId, String subtype, String content) {
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " not found");
        MessageRecord m = new MessageRecord();
        m.setId(generateId("msg"));
        m.setSessionId(sessionId);
        m.setRole(Role.system.name());
        m.setAuthor(null);
        m.setContent(content != null ? content : "");
        m.setReasoning(null);
        m.setReasoningDurationMs(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        // [created_at 单调分配器] per-session 取号（恒晚于该会话所有已有行）
        m.setCreatedAt(nextCreatedAt(sessionId).toString());
        m.setSubtype(subtype);
        // [C1] 系统 subtype 消息 isMeta=false · CC original: isMeta（messages.ts:4385-4392
        //   createScheduledTaskFireMessage isMeta=false，仍可见）· V51 is_meta 列显式落 false
        m.setIsMeta(false);
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059（与 createUserMessage 同款）
        m.setCwd(CwdResolution.getCwd(sessionId));
        // [seq 排序键] 位置键取号（雪花全局单调）
        m.setSeq(nextSeq(sessionId));
        messageMapper.insert(m);
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(session);
        if (log.isInfoEnabled()) {
            log.info("[MessageService] 系统 subtype 消息落库: session={} id={} subtype={} contentLen={}",
                sessionId, m.getId(), subtype, m.getContent().length());
        }
        return m.getId();
    }

    public void delete(String id) {
        MessageRecord m = messageMapper.selectOneById(id);
        if (m == null) throw new NotFoundException("Message " + id + " not found");
        // [级联对称 2026-09-03] 删 assistant 若带工具调用 → 连带删其 tool_result 消息行（role=tool 且
        //   tool_call_id ∈ 该 assistant 的 tool_calls.id）。tool_result 不挂 assistant 的 FK（独立 messages
        //   行，tool_call_id 列仅引用 tool_calls.id），原实现漏删 → 中断/裁剪后残留孤儿 tool_result →
        //   resume 注入 OpenAI 400 'Messages with role tool must follow tool_calls'。tool_calls 表本体
        //   由下方 FK CASCADE/显式删清除（ON DELETE CASCADE 级联 tool_calls，不级联 messages tool_result）。
        if (m.getRole() != null && Role.assistant.name().equals(m.getRole())) {
            messageMapper.deleteByQuery(QueryWrapper.create().where(
                "role = ? AND tool_call_id IN (SELECT id FROM tool_calls WHERE message_id = ?)",
                Role.tool.name(), id));
        }
        // tool_calls 的 FK ON DELETE CASCADE 会连带清，这里显式删一次更稳
        toolCallMapper.deleteByQuery(QueryWrapper.create().eq("message_id", id));
        messageMapper.deleteById(id);
    }

    /**
     * 对话裁剪（删除 pivot 起全部后续消息）· 对齐 CC {@code rewindConversationTo}
     * （REPL.tsx:3661-3699）{@code setMessages(prev.slice(0, messageIndex))} ——
     * 保留 pivot 之前消息，丢弃 pivot（含）及其后。
     *
     * <p><b>WHY（gap28 前端缺口 §28）</b>: 前端「裁剪到某点」需删除 pivot 之后全部消息且
     * 被删消息<b>不进模型上下文</b>——模型上下文来自 DB transcript（LlmAgentLoop.run 经
     * listBySession 加载），故本方法 DB 删 + 重插保留段即等价 CC 前端 setMessages 裁剪。
     * conversationId 旋转由调用方（ChatController 裁剪端点）负责，对齐 CC REPL.tsx:3673
     * {@code setConversationId(randomUUID())}。
     *
     * <p><b>实现</b>: 复用 {@link #replaceSessionMessages}「删全部 + 重插」路径
     * （toolCallMapper/messageMapper deleteByQuery + 保序重插 created_at={@link #nextCreatedAt} 单调取号），
     * 最小新增、无新 SQL；tool_calls 随消息级联清除（replace 路径已显式删 toolCall）。
     *
     * @param sessionId      会话 ID（DB 键，如 "sess-xxx"）
     * @param pivotMessageId 裁剪 pivot 消息 ID（该消息本身及其后全部删除）
     * @return 裁剪后剩余消息列表（空列表 = pivot 是首条 / 全删，前端 setMessages 权威回填）
     * @throws NotFoundException session 不存在 / pivot 消息不存在（对齐 delete :115 语义）
     */
    @Transactional
    public List<ChatMessageDto> trimSessionAfter(String sessionId, String pivotMessageId) {
        if (sessionMapper.selectOneById(sessionId) == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        // pivot 定位必须在「当前会话消息列表」内匹配（created_at ASC 序），而非 getById——
        // 避免跨会话消息 id 误匹配（对齐 listBySession :59-71 同源读取）。
        List<ChatMessageDto> all = listBySession(sessionId);
        int pivotIndex = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id() != null && all.get(i).id().equals(pivotMessageId)) {
                pivotIndex = i;
                break;
            }
        }
        if (pivotIndex < 0) {
            throw new NotFoundException("Message " + pivotMessageId + " not found");
        }
        // keep = [0, pivotIndex)（含 pivot 丢弃，对齐 CC slice(0, messageIndex) REPL.tsx:3671）
        List<ChatMessageDto> keep = new ArrayList<>(all.subList(0, pivotIndex));
        if (log.isInfoEnabled()) {
            log.info("[MessageService] trimSessionAfter: session={} pivot={} 裁剪 {}→{} 条消息"
                    + "（含 pivot 及其后丢弃，对齐 CC REPL.tsx:3671 slice(0, messageIndex)）",
                sessionId, pivotMessageId, all.size(), keep.size());
        }
        return replaceSessionMessages(sessionId, keep);
    }

    /**
     * 追加一条出站消息到会话消息库 · W8-04 完成通知链（OPD-TP-07）。
     *
     * <p><b>WHY</b>: teammate 终端转换（completed/failed/killed）产出 task_status attachment
     * （{@code TeammateMessageFoldingChain.teammateTaskStatusAttachment}，author='attachment' +
     * subtype='task_status'），必须落消息表 —— 否则 GET /messages 折叠链（ChatController 出站
     * 组装点）无 in_process_teammate 输入，连续 teammate shutdown 洪泛无法折叠。CC 侧该附件进
     * leader transcript（内存），Java 侧落 DB 消息表等价（前端 GET /messages 读折叠链）。
     *
     * <p>映射复用 {@link #replaceSessionMessages} 的 DTO→Record 规则（author/subtype/content 等
     * 字段），单条插入；sessionId 取自 dto.sessionId()（父会话 = parentSessionId）。
     *
     * @param dto 出站附件消息（如 task_status attachment）
     * @return 落库后的消息（id 已生成）
     */
    public ChatMessageDto appendMessage(ChatMessageDto dto) {
        // [created_at 单调分配器] 传 null → 2 参重载走 nextCreatedAt（per-session 单点取号）。
        //   旧实现硬传 OffsetDateTime.now()：与其它写路径不同源 → 同会话可倒挂（多 writer 时间基冲突）。
        return appendMessage(dto, null);
    }

    /**
     * [session-start-cc-align P0-1] appendMessage 2 参重载：显式指定 {@code created_at}。
     *
     * <p><b>WHY</b>: ChatService 实时落库（persistAppendedMessage）走 {@link #nextCreatedAt}
     * per-session 单调时间戳保序（跨 writer 单一分配器），hook_additional_context 注入消息（SessionStart
     * additionalContext，对齐 CC sessionStart.ts:163-172 / messages.ts:4117-4127）必须落在调用方给定的
     * 单调 ts 上，否则与同轮 assistant/工具行排序错乱。1 参委托本重载传 {@code null} →
     * 走 {@link #nextCreatedAt} per-session 单调分配器（既有调用方 spawnInProcessTeammate /
     * PermissionRulesController.retryDenials 等拿到「该会话最新」时间戳，语义等价且跨 writer 单调）。
     *
     * @param dto       出站消息（DTO→Record 完整映射：id/author/subtype/content/isMeta/imagePasteIds/cwd
     *                  全保留，不 bump sessions.messageCount——元消息不入用户计数）
     * @param createdAt 显式 created_at（调用方给定的单调 ts；null → {@link #nextCreatedAt} 兜底）
     * @return 落库后的消息（id 已生成；createdAt 字段保留 dto 原值，对齐 1 参既有语义）
     */
    public ChatMessageDto appendMessage(ChatMessageDto dto, OffsetDateTime createdAt) {
        // seq = null → 走 nextSeq 单号取号（既有调用方的语义逐位不变）；appendPostCompactMessages
        // 走 3 参重载传入整块预算好的号，使「compact 块内不可被并发 append 插入」成立（见 nextSeqBlock）。
        return appendMessage(dto, createdAt, null);
    }

    /**
     * 3 参重载：{@link #appendMessage(ChatMessageDto, OffsetDateTime)} + <b>显式 seq</b>。
     *
     * <p><b>WHY（compact 块的位次必须由调用方整块给定，不能在本方法内逐个取号）</b>：
     * {@link #appendPostCompactMessages} 的整块顺序由 {@link #nextSeqBlock} 一次 CAS 占位，
     * 若本方法仍自行 {@link #nextSeq} 取号，则每个新行各自一次 CAS → 块内重新出现可被并发
     * 实时落库插入的窗口（详见 {@link #nextSeqBlock} 的不可打断论证）。
     *
     * <p><b>为什么不在本方法内「先 INSERT 再 UPDATE seq」</b>：那样每次落库多一次 UPDATE，且
     * 中间态（seq 还是临时值）对其他读线程可见 —— 正在读 DB 的翻页/切片线程可能看到错位行。
     * 显式传号 = 一次 INSERT 落定最终位置，无中间态。
     *
     * <p><b>非 CC 对齐项，属本仓自研</b>：CC 内存消息无 DB 位置键，无对应物。
     *
     * @param dto       出站消息（映射同 2 参重载）
     * @param createdAt 显式 created_at（null → {@link #nextCreatedAt} 兜底）
     * @param seq       显式位置键（null → {@link #nextSeq} 单号取号；非 null → <b>原样落库</b>，
     *                  调用方须保证它是 {@link #nextSeqBlock} 预算出的号且顺序 = 数组序）
     * @return 落库后的消息（同 2 参重载）
     */
    public ChatMessageDto appendMessage(ChatMessageDto dto, OffsetDateTime createdAt, Long seq) {
        if (dto == null) {
            throw new IllegalArgumentException("appendMessage: dto 不能为 null");
        }
        String id = dto.id() != null ? dto.id() : generateId("msg");
        MessageRecord rec = new MessageRecord();
        rec.setId(id);
        rec.setSessionId(dto.sessionId());
        // [Fix C] messages.role TEXT NOT NULL（V1__init_schema.sql:63，无 DEFAULT）+ JDBC foreign_keys=on
        //   → role-less 消息（CC AttachmentMessage 无 role，attachments.ts:3201-3207；出站 DTO 保持
        //   role=null 与 CC 一致）落库必违反 NOT NULL → INSERT 抛异常（teammate 终端 task_status
        //   attachment 落库失败，20:33 实证）。DB 持久化边界把 null role 适配为 Role.system
        //   （系统生成元数据）；折叠链/前端判据是 author/subtype，不依赖 role。
        rec.setRole(dto.role() != null ? dto.role().name() : Role.system.name());
        rec.setAuthor(dto.author());
        rec.setContent(dto.content() == null ? "" : dto.content());
        rec.setReasoning(dto.reasoning());
        // [reasoningDurationMs] 后端测推理耗时 · V41 列落库（净新增字段，非 CC 对齐）
        rec.setReasoningDurationMs(dto.reasoningDurationMs());
        rec.setFinishReason(dto.finishReason() != null ? dto.finishReason().name() : null);
        rec.setInputTokens(dto.inputTokens());
        rec.setOutputTokens(dto.outputTokens());
        // [token-compact-fix B1 方案A] cache 用量落库 · V53 列；从 AgentUsage 投影
        //   （dto.usage() 真源 → DTO cache 字段回退），使 GET/messages 重算 contextTokensUsed
        //   含 cache（与实时 complete 事件一致，不再少算）。
        rec.setCacheReadInputTokens(cacheReadInputTokensOf(dto));
        rec.setCacheCreationInputTokens(cacheCreationInputTokensOf(dto));
        // [session-start-cc-align P0-1] created_at 用显式 createdAt（调用方给定的单调 ts，如实时落库的
        //   分配器值 / appendPostCompactMessages 的重挂值）；null → [created_at 单调分配器] nextCreatedAt
        //   per-session 取号（恒晚于该会话所有已有行；旧实现回落 now() 与其它 writer 不同源可倒挂）。
        rec.setCreatedAt(createdAt != null ? createdAt.toString() : nextCreatedAt(dto.sessionId()).toString());
        rec.setToolCallId(dto.toolCallId());
        rec.setSubtype(dto.subtype());
        rec.setStructuredOutput(serializeStructuredOutput(dto.structuredOutput()));
        rec.setAssistantMessageId(dto.assistantMessageId());
        // [userMessageId] 出站附件消息跟随 dto.userMessageId()（CC parentUuid 链根）· V47 列落库；
        //   dto 无归属（null）= 落 NULL（system/teammate 终端附件不在用户轮内，容错）
        rec.setUserMessageId(dto.userMessageId());
        // IMP2-14 · boundary 元数据持久化（V13 列；round-trip 闭环，CC messages.ts:4540-4574/4551-4553）
        rec.setCompactMetadata(serializeMap(dto.compactMetadata()));
        rec.setMicrocompactMetadata(serializeMap(dto.microcompactMetadata()));
        // [snip-persist-field] snip_boundary 元数据持久化（V62 列；round-trip 闭环，CC
        //   snipCompact.ts:99-106 / snipProjection.ts:31 —— removedUuids 落库供前端「已裁剪」标注）
        rec.setSnipMetadata(serializeMap(dto.snipMetadata()));
        rec.setLogicalParentUuid(dto.logicalParentUuid());
        // [V46] imagePasteIds · CC original: imagePasteIds（messages.ts:460-523）。
        //   非空 → JSON 数组字符串落库（round-trip 闭环）；null/空 → null（V46 列）。
        rec.setImagePasteIds(serializeStringList(dto.imagePasteIds()));
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059。优先保留消息产生时戳入的 dto.cwd()
        //   （消息产生时工作目录，更精确）；未戳则落库时经 CwdResolution.getCwd 补戳。
        rec.setCwd(dto.cwd() != null ? dto.cwd() : CwdResolution.getCwd(dto.sessionId()));
        // [C1] isMeta round-trip 持久化 · CC original: isMeta（messages.ts:3753 createUserMessage）·
        //   V51 is_meta 列；出站透传 dto.isMeta()（:454），落库同源保证 round-trip 闭环
        rec.setIsMeta(dto.isMeta());
        // [seq 排序键] 位置键：显式 seq 优先（compact 块整块预算的号，见 nextSeqBlock）；否则单号取号
        //   （雪花全局单调；读侧 listBySession/listPageBySession ORDER BY seq）
        rec.setSeq(seq != null ? seq : nextSeq(dto.sessionId()));
        // [V70] compact 摘要可观察性标志落库（CC original: isCompactSummary/isVisibleInTranscriptOnly，
        //   messages.ts:464-465/479-480）—— TraceView.compactSummaryAfter 读侧依赖 isCompactSummary===true。
        rec.setIsCompactSummary(dto.isCompactSummary());
        rec.setIsVisibleInTranscriptOnly(dto.isVisibleInTranscriptOnly());
        messageMapper.insert(rec);
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] appendMessage: session={} id={} author={} subtype={} seq={} 已落库",
                dto.sessionId(), id, dto.author(), dto.subtype(), rec.getSeq());
        }
        return new ChatMessageDto(
            id, dto.sessionId(), dto.role(), dto.author(), dto.content(), dto.reasoning(),
            dto.toolCalls(), dto.finishReason(), dto.inputTokens(), dto.outputTokens(),
            dto.time(), dto.createdAt(), dto.toolCallId(), dto.assistantMessageId(),
            dto.acceptFeedback(), dto.contentBlocks(), dto.imagePasteIds(),
            dto.structuredOutput(), dto.isMeta(), dto.isError(), dto.sourceToolUseID(),
            dto.subtype()).withCwd(rec.getCwd())
            .withReasoningDurationMs(dto.reasoningDurationMs()) // [reasoningDurationMs] 写后回传保留字段
            .withUserMessageId(dto.userMessageId()) // [userMessageId] 写后回传保留字段（与 rec 落库同源）
            .withIsCompactSummary(dto.isCompactSummary())           // [V70] 写后回传保真（TraceView 消费）
            .withIsVisibleInTranscriptOnly(dto.isVisibleInTranscriptOnly()); // [V70] 写后回传保真
    }

    /**
     * 全量替换会话消息 · 对齐 CC REPL.tsx:4964 {@code setMessages(postCompact)}
     * （partial 压缩后消息列表全量替换语义）· CC original: 前端内存替换，Java 侧落 DB。
     *
     * <p><b>WHY（OD-14 D-1 写回）</b>: partial 压缩后新消息列表（boundary + summary +
     * kept + attachments + hooks）须写回消息表，供下次 partial 重复剥离（BoundaryReader
     * 判 boundary）与前端 setMessages 刷新。CC 无 DB 概念，本方法为 Java 持久化载体：
     * <ol>
     *   <li>删该 session 全部消息（FK CASCADE 连带清 tool_calls，显式删更稳）</li>
     *   <li>按数组序重插（保序：created_at / seq 双键单调递增，listBySession 按 seq ASC）</li>
     *   <li>归一化 sessionId=sessionId（boundary.toChatMessageDto() 的 sessionId=null，
     *       CompactBoundaryMessage.java:286-312）</li>
     *   <li>id 去重（批内重复 id → PK 冲突，重插时去重。boundary id 已改随机 UUID，partial from
     *       方向不再产生「两条同 id boundary」；去重保留为通用防御）</li>
     *   <li>kept 消息的 toolCalls 重插 ToolCallRecord（保留原 ID，镜像
     *       ChatService.replayAndPersist:339-357 模式）</li>
     * </ol>
     *
     * <p><b>原子性</b>: 删+重插整体 @Transactional —— 任一重插失败回滚全部，避免半写。
     *
     * @param sessionId   会话 ID
     * @param newMessages 压缩后新消息列表（数组序即最终顺序）
     * @return 归一化后的消息列表（id 去重 + sessionId 落定），供响应回传前端
     */
    @Transactional
    public List<ChatMessageDto> replaceSessionMessages(String sessionId, List<ChatMessageDto> newMessages) {
        // 校验 session 存在
        if (sessionMapper.selectOneById(sessionId) == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        if (log.isInfoEnabled()) {
            log.info("[MessageService] replaceSessionMessages: session={} 全量替换 {} 条消息（partial 压缩写回）",
                sessionId, newMessages == null ? 0 : newMessages.size());
        }
        // 1. 删旧消息（FK CASCADE 连带清 tool_calls；显式删更稳）。
        //    ⚠️ tool_calls 表无 session_id 列（V1 表结构：id/message_id/tool_name/arguments/result/
        //    is_error/created_at）——按 message_id IN (该 session 的 messages.id) 子查询显式删，
        //    不能 eq("session_id")（SQLITE_ERROR no such column: session_id，裁剪消息触发）。
        toolCallMapper.deleteByQuery(QueryWrapper.create()
            .where("message_id IN (SELECT id FROM messages WHERE session_id = ?)", sessionId));
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", sessionId));

        // 2. 重插（保序 + id 去重 + sessionId 归一化）
        List<ChatMessageDto> normalized = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        List<ChatMessageDto> sources = newMessages == null ? List.of() : newMessages;
        for (int i = 0; i < sources.size(); i++) {
            ChatMessageDto dto = sources.get(i);
            // id 去重（boundary 固定 id 在 from 方向可能重复 → PK 冲突）
            String id = dto.id();
            if (id == null || !seenIds.add(id)) {
                id = generateId("msg");
            }
            MessageRecord rec = new MessageRecord();
            rec.setId(id);
            rec.setSessionId(sessionId);
            // [Fix C] messages.role TEXT NOT NULL（V1__init_schema.sql:63，无 DEFAULT）+ JDBC foreign_keys=on
            //   → role-less 消息（CC AttachmentMessage 无 role，attachments.ts:3201-3207；出站 DTO 保持
            //   role=null 与 CC 一致）落库必违反 NOT NULL → INSERT 抛异常（teammate 终端 task_status
            //   attachment 落库失败，20:33 实证）。DB 持久化边界把 null role 适配为 Role.system
            //   （系统生成元数据）；折叠链/前端判据是 author/subtype，不依赖 role。
            rec.setRole(dto.role() != null ? dto.role().name() : Role.system.name());
            rec.setAuthor(dto.author());
            rec.setContent(dto.content() == null ? "" : dto.content());
            rec.setReasoning(dto.reasoning());
            // [reasoningDurationMs] 后端测推理耗时 · V41 列落库（净新增字段，非 CC 对齐）
            rec.setReasoningDurationMs(dto.reasoningDurationMs());
            rec.setFinishReason(dto.finishReason() != null ? dto.finishReason().name() : null);
            rec.setInputTokens(dto.inputTokens());
            rec.setOutputTokens(dto.outputTokens());
            // [token-compact-fix B1 方案A] cache 用量落库 · V53 列；partial 压缩写回路径同样
            //   持久化（appendMessage 已持久化，本路径漏掉则 round-trip 破缺——kept 消息经
            //   replaceSessionMessages 重插后 cache 丢失，重算少算）。
            rec.setCacheReadInputTokens(cacheReadInputTokensOf(dto));
            rec.setCacheCreationInputTokens(cacheCreationInputTokensOf(dto));
            // created_at 单调递增（DB 位置序自 V70 起按 seq ASC，重插保序由下方 setSeq 承担；
            //   created_at 此处仅保时间序不倒挂）· [created_at 单调分配器]
            //   逐条取号（调用顺序 = 数组序），同时把本会话分配槽推进到 ≥ 重插值 → 后续任何写路径
            //   （实时落库 / compact 追加）恒晚于本次重插行（跨 writer 单调；旧 base.plusNanos(i) 用
            //   独立时间基，与分配槽不同源可倒挂 —— 本方法仅剩 trimSessionAfter 调用）。
            rec.setCreatedAt(nextCreatedAt(sessionId).toString());
            rec.setToolCallId(dto.toolCallId());
            rec.setSubtype(dto.subtype());
            rec.setStructuredOutput(serializeStructuredOutput(dto.structuredOutput()));
            // A1: assistantMessageId 持久化 · CC sourceToolAssistantUUID（utils/messages.ts:491）
            rec.setAssistantMessageId(dto.assistantMessageId());
            // [userMessageId] partial 压缩写回路径同样持久化——否则 kept 消息经 replaceSessionMessages
            //   重插后归属丢失（appendMessage 已持久化，本路径漏掉则 round-trip 破缺）· V47 列落库
            rec.setUserMessageId(dto.userMessageId());
            // IMP2-14 · boundary 元数据持久化（V13 列；round-trip 闭环，CC messages.ts:4540-4574/4551-4553）
            rec.setCompactMetadata(serializeMap(dto.compactMetadata()));
            rec.setMicrocompactMetadata(serializeMap(dto.microcompactMetadata()));
            // [snip-persist-field] snip_boundary 元数据持久化（V62 列；round-trip 闭环，CC
            //   snipCompact.ts:99-106 / snipProjection.ts:31 —— removedUuids 落库供前端「已裁剪」标注）
            rec.setSnipMetadata(serializeMap(dto.snipMetadata()));
            rec.setLogicalParentUuid(dto.logicalParentUuid());
            // [V46] imagePasteIds · CC original: imagePasteIds（messages.ts:460-523）。
            //   partial 压缩写回路径同样持久化——否则 kept 消息经 replaceSessionMessages 重插后
            //   图片粘贴序号丢失（appendMessage 已持久化，本路径漏掉则 round-trip 破缺）。
            rec.setImagePasteIds(serializeStringList(dto.imagePasteIds()));
            // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059。优先保留 dto.cwd()（消息产生时戳），
            //   未戳则落库时补戳（partial 压缩写回路径：boundary/summary 等消息可能未戳）。
            rec.setCwd(dto.cwd() != null ? dto.cwd() : CwdResolution.getCwd(sessionId));
            // [C1] isMeta round-trip 持久化 · CC original: isMeta（messages.ts:3753）· V51 is_meta 列；
            //   partial 压缩写回路径补持久化（appendMessage 已持久化，本路径漏掉则 round-trip 破缺）
            rec.setIsMeta(dto.isMeta());
            // [seq 排序键] 逐条取号（调用顺序 = 数组序），与 created_at 取号同源推进 → 重插保序
            rec.setSeq(nextSeq(sessionId));
            // [V70] compact 摘要可观察性标志落库（CC original: isCompactSummary/isVisibleInTranscriptOnly）
            rec.setIsCompactSummary(dto.isCompactSummary());
            rec.setIsVisibleInTranscriptOnly(dto.isVisibleInTranscriptOnly());
            messageMapper.insert(rec);

            // 3. 重插 tool_calls（保留原 ID，镜像 ChatService.replayAndPersist:339-357）
            if (dto.toolCalls() != null) {
                for (ToolCallDto tc : dto.toolCalls()) {
                    ToolCallRecord tcRec = new ToolCallRecord();
                    tcRec.setId(tc.id() != null ? tc.id() : generateId("tc"));
                    tcRec.setMessageId(id);
                    tcRec.setToolName(tc.name());
                    tcRec.setArguments(tc.arguments());
                    tcRec.setResult(tc.result());
                    tcRec.setIsError(tc.isError());
                    tcRec.setCreatedAt(rec.getCreatedAt());
                    toolCallMapper.insert(tcRec);
                }
            }

            // 归一化 DTO（id 去重后 + sessionId 落定）→ 响应回传
            normalized.add(new ChatMessageDto(
                id, sessionId, dto.role(), dto.author(), dto.content(), dto.reasoning(),
                dto.toolCalls(), dto.finishReason(), dto.inputTokens(), dto.outputTokens(),
                dto.time(), dto.createdAt(), dto.toolCallId(), dto.assistantMessageId(),
                dto.acceptFeedback(), dto.contentBlocks(), dto.imagePasteIds(),
                dto.structuredOutput(), dto.isMeta(), dto.isError(), dto.sourceToolUseID(),
                dto.subtype()).withCwd(rec.getCwd())
                .withReasoningDurationMs(dto.reasoningDurationMs()) // [reasoningDurationMs] 写后回传保留字段
                .withUserMessageId(dto.userMessageId()) // [userMessageId] 写后回传保留字段（与 rec 落库同源）
                .withIsCompactSummary(dto.isCompactSummary())       // [V70] 写后回传保真
                .withIsVisibleInTranscriptOnly(dto.isVisibleInTranscriptOnly())); // [V70] 写后回传保真
        }
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] replaceSessionMessages: 完成重插 {} 条（保序 + id 去重 {} 条）",
                normalized.size(), sources.size() - normalized.size());
        }
        return normalized;
    }

    /**
     * [SM/compact 对齐 CC] 压缩结果<b>追加落库</b>（append-only）· <b>绝不删除任何旧行</b>。
     *
     * <p><b>WHY（对齐 CC transcript append-only）</b>：CC 的 transcript 是 append-only
     * （sessionStorage.ts {@code recordTranscript} / {@code insertMessageChain} 只追加新消息行，
     * 旧行保留；compact 只<b>追加</b> boundary + summary，见 compact.ts:330-338
     * {@code buildPostCompactMessages} 的 [boundary → summary → messagesToKeep → attachments →
     * hookResults] 顺序），加载/请求侧按最后一个 boundary 剪枝
     * （{@code getMessagesAfterCompactBoundary}）。
     *
     * <p>旧路径 {@link #replaceSessionMessages}（删全表 + 按新时间基重插）偏离 CC 且引入 HIGH bug：
     * 同一 run 内 compact <b>之后</b>追加的消息其 created_at 早于重插的 boundary 行 →
     * 下轮 {@link #listBySession}（created_at ASC）顺序倒挂 → boundary 切片把这些消息整段丢掉。
     * 本方法的 append-only 语义：
     * <ol>
     *   <li><b>dto.id 已存在于 DB</b>（= messagesToKeep 段）→ 仅 <b>UPDATE 该行 seq</b> 到 boundary
     *       之后（等价 CC 的链式重挂），<b>created_at 保持原值</b>（真实产生时间 = 展示语义，不被位置
     *       重挂污染），其它字段一律不动；</li>
     *   <li><b>新 dto.id</b>（boundary / summary / attachments / hookResults）→ <b>INSERT 新行</b>，
     *       复用 {@link #appendMessage(ChatMessageDto, OffsetDateTime, Long)} 的完整 DTO→Record 映射
     *       （id 沿用 dto.id、sessionId 落定、created_at = {@link #nextCreatedAt}、seq = 本块预算值）；</li>
     *   <li>被压缩掉的旧行<b>保留在 DB</b>（轨迹 = 全程、可阅读），只是按 seq 排在 boundary 之前被剪枝。</li>
     * </ol>
     *
     * <p><b>[seq 块·块内不可被打断]</b>：整块 seq 在进入循环前经 {@link #nextSeqBlock(String, int)}
     * <b>一次 CAS 原子占位</b>（{@code [base, base+count-1]}），循环内按数组序发号 —— 故并发实时落库
     * （{@code ChatService.persistAppendedMessage}，持 {@code ctx.lock}，与本方法 {@code @Transactional}
     * 两把锁不同源）拿到的号只可能在本块<b>之前</b>或<b>之后</b>，不可能插进 boundary 与 summary 之间。
     * 这是「DB(seq) 恢复序 == 内存视图序」的前提：否则并发行若为 tool_result 而对应 tool_use 在 kept 段，
     * 下轮 boundary 切片会让 tool_result 先于 tool_use 出现（provider 配对校验失败）。详见 nextSeqBlock。
     *
     * <p><b>与 replaceSessionMessages 的区别</b>：后者删全表 + 重插（把压缩前历史从 DB 物理抹除 +
     * 换时间基）；本方法只追加 + 重挂 kept 段，DB 行数<b>只增不减</b>（压缩轨迹可回溯，且不破坏同 run
     * 后续消息的时间序）。
     *
     * <p><b>id 重复（防御分支）</b>：批内重复出现的 id → 都命中「已存在」分支，映射到同一 DB 行（后一次
     * UPDATE 覆盖前一次的重挂位置），不会 PK 冲突；批内新增 id 亦登记进 knownIds 供后续判重。
     * <b>该分支为防御性</b>：boundary id 已改为每个实例取雪花（{@code CompactBoundaryMessage.newBoundaryId()}，
     * 每实例唯一），生产 partial-from 不再产生「两条同 id boundary」—— 保留它只为「调用方误传同 id /
     * 上游 id 复用」时退化到 UPDATE 而不是 PK 冲突崩库。
     *
     * <p><b>原子性</b>：整体 @Transactional —— 任一写失败回滚全部，避免半写（调用方 fail-loud 记 error）。
     *
     * @param sessionId           会话 ID（DB 键，如 "sess-xxx"）
     * @param postCompactMessages compact 后消息集（顺序 = boundary → summary → messagesToKeep → attachments
     *                            → hookResults，<b>必须保持</b>，即 CC buildPostCompactMessages 顺序）
     * @return 归一化后的消息列表（id 保持 + sessionId 落定；新插入行 id 已生成）· 供内存
     *         {@code state.replaceMessages(...)} 使用
     * @throws NotFoundException session 不存在
     */
    @Transactional
    public List<ChatMessageDto> appendPostCompactMessages(String sessionId, List<ChatMessageDto> postCompactMessages) {
        if (sessionMapper.selectOneById(sessionId) == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        // 1. 读本会话已有消息 id 集合 —— 只用于判「已存在 → 重挂 seq」vs「新行 → INSERT」，
        //    本方法不删任何行（append-only 铁律）。
        //    [性能] 只取 id 列（select("id")）—— 避免整行大字段（content/reasoning/structured_output…）
        //    经 session_id 全表读取的内存开销；本处只需主键集合判在/不在。
        Set<String> knownIds = new HashSet<>();
        for (MessageRecord r : messageMapper.selectListByQuery(
                QueryWrapper.create().select("id").eq("session_id", sessionId))) {
            if (r != null && r.getId() != null) {
                knownIds.add(r.getId());
            }
        }
        List<ChatMessageDto> sources = postCompactMessages == null ? List.of() : postCompactMessages;
        // 2. [seq 块·不可打断] 整块 seq <b>一次原子占位</b>（见 nextSeqBlock JavaDoc）——先算「实际取号条数」
        //    （null 占位元素不取号，避免块内出现空洞），再一次性拿到连续号段，循环里按数组序发号。
        //    WHY 不能沿用「循环内逐个 nextSeq」：实时落库（ChatService.persistAppendedMessage，持
        //    ctx.lock）与本方法（@Transactional）两把锁不同源 → 并发 append 的号可能插进 boundary 与
        //    summary 之间 → 下轮 DB(seq) 恢复序与内存视图不一致 → 若并发行是 tool_result 而其 tool_use
        //    在 kept 段，切片后 tool_result 出现在 tool_use 之前（provider 配对校验失败）。
        int seqCount = 0;
        for (ChatMessageDto d : sources) {
            if (d != null) {
                seqCount++;
            }
        }
        long[] seqBlock = nextSeqBlock(sessionId, seqCount);
        int seqIdx = 0;
        List<ChatMessageDto> normalized = new ArrayList<>(sources.size());
        int rehung = 0;
        int inserted = 0;
        for (ChatMessageDto dto : sources) {
            if (dto == null) {
                continue;
            }
            // 本元素的位置键 = 块内按数组序发下去的号（块首 → 块末单调，且整块不可被并发取号插入）
            long seq = seqBlock[seqIdx++];
            // sessionId 落定（compact 产出的 boundary/summary DTO sessionId=null，落 DB 必须非空）
            ChatMessageDto src = withSession(dto, sessionId);
            String id = src.id();
            if (id != null && knownIds.contains(id)) {
                // 2a. 已存在的行（messagesToKeep）→ 只把 <b>seq 重挂</b>到 boundary 之后（对齐 CC 链式重挂，
                //     使「最后一个 boundary 之后」的位置切片 = post-compact 视图）；created_at 保持原值
                //     （kept 的真实产生时间 = 展示语义，不被位置重挂污染），其余字段一律不动。
                //     MyBatis-Flex update(entity) 默认 ignoreNulls=true → SET 仅含非 null 的 seq，WHERE id = ?（主键）。
                MessageRecord patch = new MessageRecord();
                patch.setId(id);
                // [seq 块] 重挂值取自本块预算的号（块内按数组序递增，恒 > 该会话所有已有行 + 恒 > 块内
                //   前序元素）→ 位置切片稳定；created_at 不写（保持原值）。
                patch.setSeq(seq);
                messageMapper.update(patch);
                rehung++;
                normalized.add(src);
            } else {
                // 2b. 新行（boundary / summary / attachments / hookResults）→ INSERT（复用 appendMessage 的
                //     完整 DTO→Record 映射），seq 传块内预算值（appendMessage 内不再自行取号 → 块内无窗口）；
                //     created_at 仍逐条经 nextCreatedAt 单调分配器取号（时间语义，见下）。
                //   [created_at 为什么不一起块分配] 时间语义（前端「X 分钟前」展示 + 轨迹时间感），
                //   位置正确性已全部由 seq 承担（读侧恒 ORDER BY seq），故并发线程把它的 created_at 插进
                //   本块的两个 created_at 之间只影响展示时间感、<b>不影响</b>切片/恢复顺序；且 nextCreatedAt
                //   是同一 per-session 分配器（单点），跨 writer 不会倒挂。为此再引入第二个块分配器属于
                //   过度设计（两个分配器反而更易错位），故保持逐条取号。
                OffsetDateTime ts = nextCreatedAt(sessionId);
                ChatMessageDto insertedDto = appendMessage(src, ts, seq);
                String newId = insertedDto != null ? insertedDto.id() : id;
                if (newId != null) {
                    knownIds.add(newId);
                }
                // tool_calls 补插：appendMessage 只写 messages 行、不写 tool_calls 表（本路径新增行通常为
                //   boundary/summary/attachment，toolCalls 恒空）；此处与 replaceSessionMessages :975-988 对齐，
                //   保证「新行携带 toolCalls」时不丢。
                if (newId != null && src.toolCalls() != null && !src.toolCalls().isEmpty()) {
                    for (ToolCallDto tc : src.toolCalls()) {
                        ToolCallRecord tcRec = new ToolCallRecord();
                        tcRec.setId(tc.id() != null ? tc.id() : generateId("tc"));
                        tcRec.setMessageId(newId);
                        tcRec.setToolName(tc.name());
                        tcRec.setArguments(tc.arguments());
                        tcRec.setResult(tc.result());
                        tcRec.setIsError(tc.isError());
                        tcRec.setCreatedAt(ts.toString());
                        toolCallMapper.insert(tcRec);
                    }
                }
                inserted++;
                // id 保持（dto.id 非 null = 新行自带 id，内存与 DB 一致）；dto.id 为 null（理论不可达：
                //   boundary/summary/attachments 均自带上游 id）→ 用 appendMessage 生成的 id 回传。
                normalized.add(src.id() != null ? src : insertedDto);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[MessageService] appendPostCompactMessages: session={} append-only 落库 {} 条"
                    + "（重挂 kept {} 条 + 新插 {} 条；无删除，对齐 CC recordTranscript append-only）",
                sessionId, normalized.size(), rehung, inserted);
        }
        return normalized;
    }

    /**
     * sessionId 落定辅助 · 已一致则原样返回（零分配），否则经
     * {@link ChatMessageDto#withSessionId(String)} 拷贝覆盖。
     */
    private static ChatMessageDto withSession(ChatMessageDto dto, String sessionId) {
        if (dto == null || sessionId == null || sessionId.equals(dto.sessionId())) {
            return dto;
        }
        return dto.withSessionId(sessionId);
    }

    // ============== helpers ==============

    /**
     * [token-compact-fix B1 方案A] 从 DTO 提取 cache_read_input_tokens 供落库 · V53 列。
     *
     * <p><b>真源</b>：优先 {@code dto.usage().cacheReadInputTokens()}（AgentUsage 为 provider
     * 解析的完整 usage，DEC-04 R2-USAGE 数据源闭环真源），回退 DTO 投影字段
     * {@code dto.inputCacheReadTokens()}（LlmAgentLoop withUsageCache 从 usage 投影，
     * 测试/旧构造消息可能仅该字段有值）。两者同源（withUsageCache 即 Math.toIntExact 投影
     * usage.cacheReadInputTokens），双轨冗余仅为防御性容错。null = 无 cache 数据 → 落 NULL
     * （重算回退 0，与实时 usage null 省略语义等价）。
     */
    private static Integer cacheReadInputTokensOf(ChatMessageDto dto) {
        if (dto.usage() != null && dto.usage().cacheReadInputTokens() != null) {
            return Math.toIntExact(dto.usage().cacheReadInputTokens());
        }
        return dto.inputCacheReadTokens();
    }

    /**
     * [token-compact-fix B1 方案A] 从 DTO 提取 cache_creation_input_tokens 供落库 · V53 列。
     * 语义同 {@link #cacheReadInputTokensOf}（优先 AgentUsage 真源 → DTO 投影回退）。
     */
    private static Integer cacheCreationInputTokensOf(ChatMessageDto dto) {
        if (dto.usage() != null && dto.usage().cacheCreationInputTokens() != null) {
            return Math.toIntExact(dto.usage().cacheCreationInputTokens());
        }
        return dto.inputCacheCreationTokens();
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * MessageRecord → 出站 ChatMessageDto（transcript 读回）。
     *
     * <p><b>[P-28] isApiErrorMessage 恒 false</b>：DB schema 未持久化该标志（与
     * isMeta/isError/acceptFeedback/imagePasteIds 同先例），读回恒 false —— CC original
     * messages.ts:453 的运行期消费面（max_tokens 恢复触发 / StopFailure 门控）在 LlmAgentLoop
     * 内存消息流内生效（ER-IMP-11 接线），transcript 重放侧无该语义（P-28 接受，JavaDoc 标注）。
     */
    private ChatMessageDto toDto(MessageRecord m) {
        // 查 tool_calls（若无，返回空 list）
        List<ToolCallRecord> toolEntities = toolCallMapper.selectListByQuery(
            QueryWrapper.create().eq("message_id", m.getId()));
        List<ToolCallDto> toolDtos = new ArrayList<>(toolEntities.size());
        for (ToolCallRecord tc : toolEntities) {
            toolDtos.add(new ToolCallDto(
                tc.getId(),
                tc.getToolName(),
                tc.getArguments(),
                tc.getResult(),
                tc.getIsError()
            ));
        }

        ChatMessageDto dto = new ChatMessageDto(
            m.getId(),
            m.getSessionId(),
            m.getRole() != null ? Role.valueOf(m.getRole()) : null,
            m.getAuthor(),
            m.getContent(),
            m.getReasoning(),
            toolDtos,
            FinishReason.parse(m.getFinishReason()),
            m.getInputTokens(),
            m.getOutputTokens(),
            formatRelativeTimeAgo(parseDateTime(m.getCreatedAt()), OffsetDateTime.now()),
            parseDateTime(m.getCreatedAt()),
            m.getToolCallId(),   // Phase 6·s02 字段
            m.getAssistantMessageId(), // A1 assistantMessageId · CC original: sourceToolAssistantUUID (utils/messages.ts:491)
            null,                // R32-b9 acceptFeedback (DB schema 未持久化)
            java.util.List.of(), // R32-b9 contentBlocks
            parseStringList(m.getImagePasteIds()), // V46 imagePasteIds（V46 列 JSON 数组读回；null 列 → 空列表）
            parseStructuredOutput(m.getStructuredOutput()), // OD-14: V6 读回
            Boolean.TRUE.equals(m.getIsMeta()), // R32-c-1 isMeta（V51 is_meta 列读回；null→false 容错）
            false,               // H13-GAP isError（DB schema 未持久化）
            null,                // P2-22 sourceToolUseID（DB schema 未持久化）
            m.getSubtype(),      // IMP-05/OD-14: V6 读回 subtype（读侧 BoundaryReader 判别）
            false,               // ER-IMP-11 isApiErrorMessage（DB schema 未持久化）
            null, null, null,    // ER-IMP-11 apiError/error/errorDetails（DB schema 未持久化）
            parseMap(m.getCompactMetadata()),       // IMP2-14: V13 读回 boundary compactMetadata
            parseMap(m.getMicrocompactMetadata()),  // IMP2-14: V13 读回 microcompactMetadata
            m.getLogicalParentUuid(),               // IMP2-14: V13 读回 logicalParentUuid
            Boolean.TRUE.equals(m.getIsCompactSummary()),        // [V70] is_compact_summary 读回（null→false 容错）
            Boolean.TRUE.equals(m.getIsVisibleInTranscriptOnly())) // [V70] is_visible_in_transcript_only 读回（null→false）
            .withSnipMetadata(parseMap(m.getSnipMetadata())) // [snip-persist-field] V62 读回 snipMetadata（removedUuids 供前端「已裁剪」标注）
            .withCwd(m.getCwd()) // [G13] 回填 cwd 戳（V22 列；旧行 NULL = 未戳容错，对齐 CC 旧 jsonl 无 cwd）
            .withReasoningDurationMs(m.getReasoningDurationMs()) // [reasoningDurationMs] 读侧回填（V41 列；GET /messages 出站唯一点）
            .withUserMessageId(m.getUserMessageId()) // [userMessageId] 读侧回填（V47 列；GET /messages 出站唯一点；旧行 NULL = 无归属容错）
            .withQueuedOrigin(m.getQueuedOrigin()) // [P0-1 OD-1/OD-3] 读侧回填（V67 queued_origin 列；resume 发送层按标记重新包壳；旧行 NULL = 非排队不包壳，与现状一致可接受）
            .withUserAttachments(resolveAttachmentUrls(parseUserAttachments(m.getUserAttachments()), m.getSessionId())); // [userAttachments] 读侧回填（V62 列；GET /messages 出站唯一点；contentId 非空 → url 动态拼 /api/v1/attachments/content/{sessionId}/{contentId}；null 列 → 空列表，恒非 null）
        // [usage 读侧回填] DB 持久化 inputTokens/outputTokens + cache 4 字段 → 重拉投影回填 usage，
        //   使 F5 后消息 token 展示与实时 complete 事件一致（↑input ↓output + cache）。
        //   仅 assistant 消息（tokens 非 null）回填；user/tool 消息不回填（避免 ↑0 ↓0 假值）。
        //   [token-compact-fix B1 方案A] V53 cache 列读回：withUsage 后链式 withUsageCache
        //   回填 inputCacheReadTokens/inputCacheCreationTokens（withUsage 会把 DTO cache 字段
        //   重置为 null，必须先 withUsage 再 withUsageCache，对齐 ChatMessageDto:613-616 顺序约束）。
        if (m.getInputTokens() != null || m.getOutputTokens() != null) {
            dto = dto.withUsage(AgentUsage.fromInputOutput(m.getInputTokens(), m.getOutputTokens()))
                .withUsageCache(m.getCacheReadInputTokens(), m.getCacheCreationInputTokens());
        }
        return dto;
    }

    /** Map 序列化（null → null，写回 DB；复用 structured_output 同款 JSON 通道）。 */
    private static String serializeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[MessageService] 元数据 Map 序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /** Map 反序列化（JSON 文本 → Map；null/空/解析失败 → null）。 */
    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] 元数据 Map 反序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /** structured_output 序列化（null → null，写回 DB）。 */
    private static String serializeStructuredOutput(Map<String, Object> structuredOutput) {
        if (structuredOutput == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(structuredOutput);
        } catch (Exception e) {
            log.warn("[MessageService] structured_output 序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /** structured_output 反序列化（JSON 文本 → Map；null/空/解析失败 → null，fail loud 不吞关键异常）。 */
    private static Map<String, Object> parseStructuredOutput(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] structured_output 反序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /**
     * [AM-CC-20260825] 回写 user 消息 image_paste_ids（小图 base64 直传场景）：A4 生成的 user 消息
     * 带正确 imagePasteIds（LlmAgentLoop F1 落盘自增分配 id），但 createUserMessage 落库时拿不到
     * （当时 base64 直传无 contentId）→ 这里按 userMessageId UPDATE 补写 image_paste_ids 列。
     * 消息本体由 createUserMessage 落库，本方法只更新 image_paste_ids（MyBatis-Flex update 仅非 null 字段）。
     *
     * @param userMessageId DB user 消息 id（前端正式气泡 id 与 DB 一致）
     * @param imagePasteIds 图片粘贴序号（A4 生成的 imagePasteIds，可能含自增分配的小图 id）
     */
    public void updateUserImagePasteIds(String userMessageId, List<String> imagePasteIds) {
        if (userMessageId == null || userMessageId.isBlank() || imagePasteIds == null || imagePasteIds.isEmpty()) {
            return;
        }
        MessageRecord rec = new MessageRecord();
        rec.setId(userMessageId);
        rec.setImagePasteIds(serializeStringList(imagePasteIds));
        messageMapper.update(rec);  // id 作条件 + 只更新非 null（image_paste_ids）
        if (log.isInfoEnabled()) {
            log.info("[MessageService] 回写 user 消息 image_paste_ids：id={} ids={}", userMessageId, imagePasteIds);
        }
    }

    /**
     * [附件双模式] 回写 user 消息 user_attachments（path/upload 附件 contentId 回补）：createUserMessage
     * 落库时 path 附件 contentId 未知（resolveAttachments 注册附件表后才拿到）→ 这里按 userMessageId
     * UPDATE 补写含 contentId 的完整附件快照（url 为出站投影，toDto 动态拼，不落库）。
     * 消息本体由 createUserMessage 落库，本方法只更新 user_attachments（MyBatis-Flex update 仅非 null 字段）。
     *
     * <p><b>全量覆盖语义</b>：serializeUserAttachments 对 null/空列表返回 null → update 跳过该列（不清空）；
     * 入参应传该 user 消息的<b>全量</b>附件快照（含既有 contentId 项），非增量。与
     * {@link #updateUserImagePasteIds}（列级追加）不同：user_attachments 是整列 JSON 快照，只能整列覆盖。
     *
     * @param userMessageId   DB user 消息 id（前端正式气泡 id 与 DB 一致）
     * @param userAttachments 附件快照全量列表（contentId 已回补）；null/空 = 不更新
     */
    public void updateUserAttachments(String userMessageId, List<UserAttachmentInfo> userAttachments) {
        if (userMessageId == null || userMessageId.isBlank() || userAttachments == null || userAttachments.isEmpty()) {
            return;
        }
        MessageRecord rec = new MessageRecord();
        rec.setId(userMessageId);
        rec.setUserAttachments(serializeUserAttachments(userAttachments));
        messageMapper.update(rec);  // id 作条件 + 只更新非 null（user_attachments）
        if (log.isInfoEnabled()) {
            log.info("[MessageService] 回写 user 消息 user_attachments：id={} count={}",
                userMessageId, userAttachments.size());
        }
    }

    /**
     * 字符串列表序列化（V46 imagePasteIds JSON 数组通道）· 参照 {@link #serializeMap} 同款 JSON 模式。
     *
     * <p>null/空列表 → null（DB 落 NULL，不存空数组 "[]"）；非空 → JSON 数组文本。
     * 序列化失败 → warn + null（fail loud，降级不落脏数据）。
     *
     * @param values 字符串列表（可 null/空）
     * @return JSON 数组文本；null/空/序列化失败 → null
     */
    private static String serializeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("[MessageService] 字符串列表序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /**
     * 字符串列表反序列化（V46 imagePasteIds JSON 数组读回）· 参照 {@link #parseMap} 同款 JSON 模式。
     *
     * <p>null/空列 → 空列表（toDto 消费侧契约：imagePasteIds 恒非 null，旧行 V46 列 NULL 兜底）；
     * 解析失败 → 空列表（fail loud warn，不抛）。
     *
     * @param json JSON 数组文本（可 null/空白）
     * @return 反序列化列表；null/空白/解析失败 → 空列表
     */
    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] 字符串列表反序列化失败（降级空列表）: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 从 HTTP 请求附件提取图片粘贴序号 · CC original: imagePasteIds
     * （messages.ts:460-523 createUserMessage 签名）。
     *
     * <p>判定图片沿用 {@code MediaLimitGuard.isImage} 同款规则（type=image 或 mediaType=image/*）；
     * 取 image 项 contentId（ImageAttachmentStore 数字 id 串）入列 —— CC getNextImagePasteId
     * 记录 imageStore 图片 id 序列。无图片/无 contentId → null。
     *
     * @param attachments 请求体 attachments（可 null/空）
     * @return 图片粘贴序号列表；无图片 → null
     */
    private static List<String> imagePasteIdsFromAttachments(List<AttachmentRequest> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (AttachmentRequest att : attachments) {
            if (att == null) {
                continue;
            }
            boolean isImage = (att.type() != null && "image".equalsIgnoreCase(att.type()))
                || (att.mediaType() != null && att.mediaType().startsWith("image/"));
            if (isImage && att.contentId() != null && !att.contentId().isBlank()) {
                ids.add(att.contentId());
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    /**
     * 附件快照列表序列化（[{type,filename}] JSON 数组）· 参照 {@link #serializeStringList} 同款 JSON 模式。
     *
     * <p>null/空列表 → null（DB 落 NULL）；非空 → JSON 数组文本。序列化失败 → warn + null
     * （fail loud，降级不落脏数据）。
     *
     * @param list 附件快照列表（可 null/空）
     * @return JSON 数组文本；null/空/序列化失败 → null
     */
    private static String serializeUserAttachments(List<UserAttachmentInfo> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("[MessageService] 附件快照序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /**
     * 附件快照 JSON 反序列化（[{type,filename,mediaType,contentId,url}] → List<UserAttachmentInfo>）· 参照
     * {@link #parseStringList} 同款 JSON 模式。
     *
     * <p>record canonical 5 参缺省 null 容错：历史 2 字段 JSON（{type,filename}）读回 mediaType/contentId/url
     * 均为 null；新字段（mediaType/contentId）自动反序列化（url DB 恒缺，出站由
     * {@link #resolveAttachmentUrls} 动态拼）。
     *
     * <p>null/空列 → 空列表（toDto 消费侧契约：userAttachments 恒非 null，旧行 V62 列 NULL 兜底）；
     * 解析失败 → 空列表（fail loud warn，不抛）。
     *
     * @param json 附件快照 JSON 数组文本（可 null/空白）
     * @return 反序列化列表；null/空白/解析失败 → 空列表
     */
    private static List<UserAttachmentInfo> parseUserAttachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<UserAttachmentInfo>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] 附件快照反序列化失败（降级空列表）: {}", e.toString());
            return List.of();
        }
    }

    /**
     * [附件双模式] 出站附件 url 动态拼接（user_attachments 持久化 contentId → F5 预览 url）· 对齐前端契约
     * {@code /api/v1/attachments/content/{sessionId}/{contentId}}（AttachmentController GET /attachments/content，
     * 附件表 path 流式字节 + Range 206）。
     *
     * <p><b>出站纯投影</b>：url 由 contentId + sessionId 每次动态重算（DB user_attachments 不落 url，
     * 避免重启后 url 与 contentId 漂移）；contentId 非空 → 拼 url；contentId null/空（path 附件未回补 /
     * 历史旧行 / ≤5MB base64 图无 contentId）→ url null（前端无预览分支，附件 chip 降级纯文本）。
     * 幂等：url 已存在（异常反序列化带 url）以重算为准，不保留 DB 中的陈旧 url。
     *
     * @param list      反序列化后的附件快照（可 null/空）
     * @param sessionId 所属会话 id（消息表 session_id 列，toDto 的 m.getSessionId() 可拿；null → 不拼 url）
     * @return url 已填充的附件快照列表；无变更/无 session → 原列表
     */
    private static List<UserAttachmentInfo> resolveAttachmentUrls(List<UserAttachmentInfo> list, String sessionId) {
        if (list == null || list.isEmpty() || sessionId == null || sessionId.isBlank()) {
            return list;
        }
        List<UserAttachmentInfo> resolved = new ArrayList<>(list.size());
        boolean changed = false;
        for (UserAttachmentInfo info : list) {
            if (info == null) {
                continue;
            }
            String cid = info.contentId();
            String url = (cid != null && !cid.isBlank())
                ? "/attachments/content/" + sessionId + "/" + cid
                : null;
            if (url == null && info.url() == null) {
                resolved.add(info); // 无 contentId → 无 url 可拼，保留原 record
                continue;
            }
            resolved.add(new UserAttachmentInfo(info.type(), info.filename(), info.mediaType(), cid, url));
            changed = true;
        }
        return changed ? resolved : list;
    }

    /**
     * 从 HTTP 请求附件提取全部附件（含图片，不 filter）的 type+filename+mediaType+contentId 快照 ·
     * 前端 F5 重拉附件 chip + 预览 url（contentId → toDto 出站拼 url）。
     *
     * <p>与 {@link #imagePasteIdsFromAttachments} 的差异：该方法是图片专用（contentId 入列，供
     * 缩略图重拉 + token 估算），本方法覆盖全类型（file/image/video/audio），null 附件跳过。
     *
     * <p>[附件双模式] contentId 规则：req 附件项已带 contentId（upload / image-cache 引用）→ 直接入快照；
     * path 附件（local-read 本地读盘，path() 非空）contentId 此刻未知 → null（resolveAttachments 注册
     * 附件表后经 {@link #updateUserAttachments} 回补）；≤5MB base64 图无 contentId → null（imagePasteIds
     * 链路不变）。url 恒 null（toDto 出站动态拼，不落库）。无附件 → null。
     *
     * @param attachments 请求体 attachments（可 null/空）
     * @return 附件快照列表（{@link UserAttachmentInfo}）；无附件 → null
     */
    private static List<UserAttachmentInfo> userAttachmentsFromAttachments(List<AttachmentRequest> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<UserAttachmentInfo> list = new ArrayList<>();
        for (AttachmentRequest att : attachments) {
            if (att == null) {
                continue;
            }
            String contentId = (att.contentId() != null && !att.contentId().isBlank()) ? att.contentId() : null;
            list.add(new UserAttachmentInfo(att.type(), att.filename(), att.mediaType(), contentId, null));
        }
        return list.isEmpty() ? null : list;
    }

    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 人读相对时间 · Phase 5 补齐（原 "刚刚" stub）。对齐 CC {@code formatRelativeTimeAgo}
     * （utils/format.ts:186-198，Intl.RelativeTimeFormat）语义——按消息创建时间距现在的时长
     * 返回中文人读格式（项目 {@code ChatMessageDto.time} 契约「2 分钟」/「刚刚」，非 CC 英文
     * "X minutes ago"）：&lt;60s → 「刚刚」；&lt;1h → 「X 分钟前」；&lt;24h → 「X 小时前」；
     * &lt;30 天 → 「X 天前」；更早 → 具体日期（{@code yyyy-MM-dd}）。createdAt 为 null →
     * 「刚刚」（无时间戳兜底，不抛）。
     *
     * @param createdAt 消息创建时间（可 null）
     * @param now       当前时刻（可注入测试；生产 {@code OffsetDateTime.now()}）
     * @return 人读相对时间串
     */
    private static String formatRelativeTimeAgo(OffsetDateTime createdAt, OffsetDateTime now) {
        if (createdAt == null || now == null) {
            return "刚刚";
        }
        long seconds = Duration.between(createdAt, now).getSeconds();
        if (seconds < 60) {
            return "刚刚";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分钟前";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + " 小时前";
        }
        if (seconds < 86400L * 30) {
            return (seconds / 86400) + " 天前";
        }
        return createdAt.toLocalDate().toString();
    }
}
