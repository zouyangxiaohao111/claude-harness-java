package com.nexusai.apis.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.NexusAiApplication;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.provider.dto.ModelTag;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionGroup;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [session-order] 会话列表定序集成测试（**真实 SQLite** · Flyway 全量建库）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>：{@code SessionService.list()} 的
 * {@code ORDER BY updated_at DESC, id ASC} 此前**零测试覆盖** —— 唯一触达 {@code list()} 的 3 个单测
 * （{@code SessionServiceTest}）都 mock 了 {@code SessionMapper}，只验逐行 DTO 映射、**不验序**
 * （mock 返回什么序就是什么序，与真实 SQL 无关）。
 *
 * <p><b>为什么必须用真实库</b>：排序在 **SQL** 里、不在 Java 里 —— {@code list()} 对 records 不做任何
 * 内存排序。故「传入乱序 records → 返回有序」在 mock 层**不可表达**（mock 返回的序即最终序）。
 * 本测试真落库后调 {@code list()}，是 ORDER BY 唯一可被变异检出的守卫：把
 * {@code orderBy("updated_at", false)} 改成 {@code true}（DESC→ASC）时本测试必红，而
 * {@code selectAll()} 的裸查在 SQLite 上**恰好**常返回 updated_at 序而可能蒙对，故本测试另以
 * 「同 updated_at 的一对按 id 升序」与「乱序写入」两个手段把序钉死。
 *
 * <p><b>新增耦合的第二层</b>：{@link #wireUpdatedAtStrings_areJacksonNormalized_andStillSortChronologically()}
 * 钉住**前端消费侧的保序前提** —— 前端拿到的 {@code updatedAt} 是 Jackson 规范化后的字符串
 * （补零被裁），与 DB 字面量并不相同；该用例若因 Jackson 配置变更（如加 {@code @JsonFormat}）而
 * 破序会显式变红，而不是静默破序。
 */
// ⚠️ 必须 RANDOM_PORT（真实 Servlet 容器）而非 MOCK：BrowserWebSocketConfig 是无条件 @Configuration，
//   其 ServletServerContainerFactoryBean 依赖容器提供的 jakarta.websocket.server.ServerContainer
//   ServletContext 属性 —— MOCK 环境无真实容器 → 该属性缺失 → 上下文加载失败
//   （实测：同仓 SessionCreateTitleOnlyIntegrationTest 用 MOCK，其 6 个用例全部 ApplicationContext
//   加载失败；根因与本测试无关，属既有环境问题）。RANDOM_PORT 起真实 Tomcat，上下文可正常加载。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = NexusAiApplication.class)
@DisplayName("[session-order] 会话列表定序集成测试（真实 SQLite）")
class SessionListOrderIntegrationTest {

    // CleanupMode.NEVER：SQLite 文件（.db/-shm/-wal）测试结束时仍被 Hikari 锁定，JUnit 默认清理
    // 会因「文件被占用」抛 IOException（Windows 实测，同 SessionCreateTitleOnlyIntegrationTest）。
    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tempDir;

    @DynamicPropertySource
    static void freshDb(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", () -> "jdbc:sqlite:"
            + tempDir.resolve("session-order.db").toAbsolutePath()
            + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000");
    }

    @Autowired private SessionService sessionService;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 每个用例从**干净表**开始：本类用例共享同一个 @TempDir 库文件，不清表则上一个用例的行会
     * 混进 {@code list()} 全量结果。清表后**自证表确为空**（fail loud · 规则十二）—— 若将来
     * Flyway 种入 sessions 行，前置断言显式失败而非让序断言静默错位。
     */
    @BeforeEach
    void cleanSessions() {
        jdbcTemplate.update("DELETE FROM sessions");
        Integer remaining = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class);
        assertEquals(0, remaining == null ? -1 : remaining.intValue(),
            "前置假设失败：清表后 sessions 应为空，实际=" + remaining);
    }

    /** 最小可落库会话行（sessions 表 NOT NULL 列：id/model_tag/title/time/session_group/message_count）。 */
    private static SessionRecord rec(String id, String updatedAt) {
        SessionRecord r = new SessionRecord();
        r.setId(id);
        r.setModelTag(ModelTag.DS.name());
        r.setTitle(id);
        r.setTime("现在");
        r.setSessionGroup(SessionGroup.current.name());
        r.setMessageCount(0);
        r.setCreatedAt(updatedAt);
        r.setUpdatedAt(updatedAt);
        return r;
    }

    private List<String> listIdsInOrder() {
        return sessionService.list().stream().map(SessionDto::id).toList();
    }

    // ── 1. 行序（ORDER BY 本体）─────────────────────────────────────────

    @Test
    @DisplayName("乱序写入 + 同 updated_at 一对 → list() 按 updated_at 降序、同值按 id 升序（DESC 改 ASC 必红）")
    void list_ordersByUpdatedAtDescThenIdAsc() {
        // WHY: 左栏「最近活动在前」是唯一用户预期，且必须与新建会话重排、F5 重拉三方一致；
        //   tie-breaker 则让序成为【全序】—— 无它时 SQLite 对同值不保证相对顺序，行序仍会漂。
        // 故意逆序写入（插入序 ≠ 目标序），确保断言的是 ORDER BY 而不是 SQLite 的行存顺序。
        String t1 = "2026-09-10T10:00:00.100000+08:00";
        String t2 = "2026-09-11T10:00:00.200000+08:00";
        String t3 = "2026-09-12T10:00:00.300000+08:00";
        String tie = "2026-09-12T11:00:00.400000+08:00";   // 两个会话同值 → 只有 id 能定序

        sessionMapper.insert(rec("sess-zzz-tie", tie));    // 同值对：先插 id 大的
        sessionMapper.insert(rec("sess-aaa-tie", tie));
        sessionMapper.insert(rec("sess-3-oldest", t1));
        sessionMapper.insert(rec("sess-1-newest", t3));
        sessionMapper.insert(rec("sess-2-middle", t2));

        assertEquals(
            List.of("sess-aaa-tie", "sess-zzz-tie", "sess-1-newest", "sess-2-middle", "sess-3-oldest"),
            listIdsInOrder(),
            "list() 必须按 updated_at DESC, id ASC —— 同值对（aaa-tie/zzz-tie）先后由 id 升序决定");
    }

    @Test
    @DisplayName("updated_at 相同时反复查询序稳定（偏序→全序的回归守卫）")
    void list_tieBreakIsStable() {
        // WHY: 原缺陷是「顺序不固定」—— 同值只有偏序时，同一份数据多次查询可能给出不同行序。
        //   补 id 兜底后序成为全序：同一数据快照下任意次查询结果必须逐位相同。
        String tie = "2026-09-12T11:00:00.400000+08:00";
        for (String id : List.of("sess-e5", "sess-b2", "sess-d4", "sess-a1", "sess-c3")) {
            sessionMapper.insert(rec(id, tie));
        }
        List<String> first = listIdsInOrder();
        assertEquals(List.of("sess-a1", "sess-b2", "sess-c3", "sess-d4", "sess-e5"), first,
            "5 个同 updated_at 会话必须按 id 升序");
        for (int i = 0; i < 20; i++) {
            assertEquals(first, listIdsInOrder(), "同 data snapshot 下第 " + (i + 2) + " 次查询行序不得变化");
        }
    }

    // ── 2. 前端消费侧前提（Jackson 规范化保序）──────────────────────────

    @Test
    @DisplayName("出线 updatedAt 为 Jackson 规范化字符串（裁尾零）· 且规范化后仍按时间序排列")
    void wireUpdatedAtStrings_areJacksonNormalized_andStillSortChronologically() throws Exception {
        // WHY: 前端 compareSessions 做的是【朴素字符串比较】，而后端 SQL 比的是 DB 字面量
        //   （OffsetDateTime.toString() 补零到 9 位）。前端拿到的却是 Jackson 规范化后的串
        //   （裁掉尾随零）——两者【字面不同】（真库 13 行里 12 行不同）。两端能同序的前提是
        //   「Jackson 规范化保序」：本用例把该前提显式钉住；若将来有人改 Jackson 配置
        //   （@JsonFormat/时区/精度）导致出线串不再保序，这里必红 —— 此前该前提零测试覆盖。
        // 序列化用容器里的 ObjectMapper：本仓未注册自定义 MappingJackson2HttpMessageConverter /
        //   自定义 ObjectMapper bean，故它就是 @RestController 出线所用的同一个（= 前端真正收到的形态）。
        // 时间轴（升序）：.12 < .5 < .7795937 < .9；四种不同补零位数 → 规范化后长度各异（混合精度）
        List<String> dbPadded = List.of(
            "2026-09-08T15:17:25.120000000+08:00",
            "2026-09-08T15:17:25.500000000+08:00",
            "2026-09-08T15:17:25.779593700+08:00",
            "2026-09-08T15:17:25.900000000+08:00");
        int i = 0;
        for (String u : dbPadded) {
            sessionMapper.insert(rec("sess-w" + (i++), u));
        }

        List<String> wire = new ArrayList<>();
        for (SessionDto dto : sessionService.list()) {
            JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(dto)).get("updatedAt");
            assertNotNull(node, "出线 DTO 必须含 updatedAt 键：dto=" + dto);
            assertTrue(node.isTextual(),
                "updatedAt 必须是 ISO 字符串（前端 SessionDto.updatedAt?: string 契约）——"
                + "若变成数字时间戳说明 Jackson WRITE_DATES_AS_TIMESTAMPS 被打开，前端比较会全部失效。实际=" + node);
            wire.add(node.asText());
        }
        assertEquals(4, wire.size(), "应返回 4 个会话");

        // 前置自证（fail loud · 规则十二）：Jackson 规范化确实发生了 —— 至少一串与 DB 字面量不同。
        assertTrue(wire.stream().anyMatch(w -> !dbPadded.contains(w)),
            "前置假设失败：出线串未发生 Jackson 规范化（全部与 DB 字面量一致）——本用例失去意义。实际=" + wire);
        assertTrue(wire.stream().allMatch(w -> w.contains(".") && w.length() < 35),
            "前置假设失败：出线串应已裁掉尾随零（短于 DB 的 9 位小数形态）。实际=" + wire);

        // 核心不变量：出线串按朴素字符串降序 == 真实时间降序（.9 > .7795937 > .5 > .12）
        List<String> sortedDesc = new ArrayList<>(wire);
        sortedDesc.sort((a, b) -> a.compareTo(b));
        java.util.Collections.reverse(sortedDesc);
        assertEquals(
            List.of("2026-09-08T15:17:25.9+08:00", "2026-09-08T15:17:25.7795937+08:00",
                    "2026-09-08T15:17:25.5+08:00", "2026-09-08T15:17:25.12+08:00"),
            sortedDesc,
            "出线串（混合精度）按朴素字符串排序必须仍是真实时间降序 —— 这是前端 compareSessions 保序的前提");
        // 且出线序 == 后端序（两端同一口径）
        assertEquals(Arrays.asList("sess-w3", "sess-w2", "sess-w1", "sess-w0"), listIdsInOrder(),
            "后端 list() 行序 == 出线串朴素字符串序（两端同口径）");
    }
}
