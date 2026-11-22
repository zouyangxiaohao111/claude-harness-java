package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.messaging.ListAgentsCursorStore;
import com.nexusai.application.agent.messaging.PeerRefPins;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ListAgents 游标分页（对齐 CC）：
 * <ul>
 *   <li>a 无 cursor = 第一页；</li>
 *   <li>b 用返回的 cursor 取第二页，同一快照内不重不漏；</li>
 *   <li>c 非法/过期/别会话 cursor → {@code cursor_not_valid}，且不返回任何 agent；</li>
 *   <li>d 每会话超过 32 个游标时最旧的失效；</li>
 *   <li>e 末页不再给 cursor；</li>
 *   <li>f 分页状态下 pin 只含本页真正打印的行。</li>
 * </ul>
 */
@DisplayName("ListAgents · 游标分页")
class ListAgentsPaginationTest {

    private static final String SELF = "sess-aaaaaaa1";

    /** 205 个 peer ⇒ 页1 100 + 页2 100 + 页3 5（每节每页 100 行上限）。 */
    private static final int PEERS = 205;

    private PeerSessionRegistry sessions;
    private AgentNameRegistry registry;
    private PeerRefPins pins;
    private ListAgentsTool tool;

    @BeforeEach
    void setUp() {
        SessionProjectRoot.setForSession(SELF, System.getProperty("user.dir"));
        sessions = new PeerSessionRegistry();
        sessions.register(new PeerSessionRegistry.Registration(
            SELF, "alpha", "/p", PermissionMode.DEFAULT, 1L));
        for (int i = 0; i < PEERS; i++) {
            sessions.register(new PeerSessionRegistry.Registration(
                peerId(i), peerName(i), "/p", PermissionMode.DEFAULT, 1L));
        }
        registry = new AgentNameRegistry();
        pins = new PeerRefPins();
        tool = new ListAgentsTool();
        tool.setPeerSessionRegistry(sessions);
        tool.setAgentNameRegistry(registry);
        tool.setTeamHelpers(new TeamHelpers());
        tool.setRefPins(pins);
    }

    @AfterEach
    void tearDown() {
        com.nexusai.application.agent.tasks.TaskSystemConfig.clearForTest();
    }

    // ───────────────────────── a ─────────────────────────

    @Test
    @DisplayName("a：无 cursor = 从头列第一页，带下一页 cursor 与 hasMore")
    void firstPageWithoutCursor() {
        Map<String, Object> data = data(null);
        String listing = (String) data.get("listing");

        assertThat(data.get("hasMore")).isEqualTo(true);
        assertThat(data.get("total")).isEqualTo(PEERS);
        String cursor = (String) data.get("nextCursor");
        assertThat(cursor).isNotBlank();
        assertThat(listing).contains("  " + peerName(0) + " [")
            .doesNotContain("  " + peerName(100) + " [")
            .contains("pass cursor \"" + cursor + "\"");
        assertThat(listing.length()).isLessThanOrEqualTo(
            com.nexusai.application.agent.messaging.ListAgentsFormatter.MAX_LISTING_CHARS);
        assertThat(names(data)).hasSize(100).contains(peerName(0)).doesNotContain(peerName(100));
    }

    // ───────────────────────── b ─────────────────────────

    @Test
    @DisplayName("b：用返回的 cursor 取第二页；同一快照内不重不漏")
    void secondPageContinuesWithoutDuplicatesOrGaps() {
        Map<String, Object> first = data(null);
        List<String> page1 = names(first);
        String cursor = (String) first.get("nextCursor");

        Map<String, Object> second = data(cursor);
        List<String> page2 = names(second);

        assertThat(page1).hasSize(100);
        assertThat(page2).hasSize(100);
        assertThat(page2.get(0)).as("第二页从第 101 行继续").isEqualTo(peerName(100));
        Set<String> overlap = new HashSet<>(page1);
        overlap.retainAll(new HashSet<>(page2));
        assertThat(overlap).as("两页不得重复").isEmpty();

        List<String> union = new ArrayList<>(page1);
        union.addAll(page2);
        assertThat(union).as("两页合计 = 前 200 行且不重").hasSize(200);
        assertThat(new HashSet<>(union)).hasSize(200);
        for (int i = 0; i < 200; i++) {
            assertThat(union).contains(peerName(i));
        }
        assertThat(second.get("hasMore")).isEqualTo(true);
    }

    // ───────────────────────── c ─────────────────────────

    @Test
    @DisplayName("c：非法 cursor → cursor_not_valid，且不返回任何 agent、不登记 pin")
    void invalidCursorReturnsErrorWithoutAgents() {
        ToolResult<?> result = execute("not-a-real-cursor");

        assertThat(result.data()).isEqualTo(ListAgentsTool.CURSOR_NOT_VALID_MESSAGE);
        String message = String.valueOf(result.data());
        assertThat(message).startsWith("cursor_not_valid:")
            .contains("that cursor is not valid")
            .contains("no agents were looked up");
        assertThat(message).doesNotContain(peerName(0));
        assertThat(pins.snapshot(SELF)).as("错误路径不得登记任何 pin").isEmpty();
    }

    @Test
    @DisplayName("c：过期 cursor → cursor_not_valid（不返回 agent、不新增 pin）")
    void expiredCursorReturnsError() {
        long[] now = {1_000L};
        tool.setCursorStore(new ListAgentsCursorStore(60_000L, () -> now[0]));
        String cursor = (String) data(null).get("nextCursor");
        assertThat(cursor).isNotBlank();
        int pinnedBefore = pins.snapshot(SELF).size();

        now[0] += 60_001L;  // 越过 TTL
        ToolResult<?> result = execute(cursor);

        assertThat(result.data()).isEqualTo(ListAgentsTool.CURSOR_NOT_VALID_MESSAGE);
        assertThat(String.valueOf(result.data())).startsWith("cursor_not_valid:");
        assertThat(pins.snapshot(SELF)).as("过期路径不新增 pin").hasSize(pinnedBefore);
    }

    @Test
    @DisplayName("c：别的会话签发的 cursor → cursor_not_valid")
    void cursorFromAnotherSessionIsInvalid() {
        SessionProjectRoot.setForSession("sess-bbbbbbb2", System.getProperty("user.dir"));
        String cursor = (String) data(null).get("nextCursor");

        ToolUseBlock call = callWithCursor(cursor);
        ToolUseContext otherSession = new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), "sess-bbbbbbb2",
            PermissionMode.DEFAULT, Map.of(), List.of(), null, AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT);
        ToolResult<?> result = (ToolResult<?>) tool.execute(call, otherSession);

        assertThat(result.data()).isEqualTo(ListAgentsTool.CURSOR_NOT_VALID_MESSAGE);
    }

    // ───────────────────────── d ─────────────────────────

    @Test
    @DisplayName("d：超过 32 个游标时最旧的失效（每会话上限 32）")
    void oldestCursorEvictedBeyondLimit() {
        ListAgentsCursorStore store = new ListAgentsCursorStore();
        tool.setCursorStore(store);
        List<String> cursors = new ArrayList<>();
        for (int i = 0; i < ListAgentsCursorStore.MAX_CURSORS_PER_SESSION + 1; i++) {
            cursors.add((String) data(null).get("nextCursor"));
        }
        assertThat(cursors).allMatch(c -> c != null && !c.isBlank());
        assertThat(store.size(SELF)).isEqualTo(ListAgentsCursorStore.MAX_CURSORS_PER_SESSION);

        // 直接查存储（不翻页，避免翻页本身又签发新游标影响计数）。
        assertThat(store.resolve(SELF, cursors.get(cursors.size() - 1))).as("最新游标有效")
            .isNotNull();
        assertThat(store.resolve(SELF, cursors.get(cursors.size() - 32))).as("第 32 新游标有效")
            .isNotNull();
        assertThat(store.resolve(SELF, cursors.get(0))).as("最旧游标已被淘汰").isNull();
        // 通过工具调用也确实被判不合法。
        assertThat(execute(cursors.get(0)).data()).isEqualTo(ListAgentsTool.CURSOR_NOT_VALID_MESSAGE);
    }

    // ───────────────────────── e ─────────────────────────

    @Test
    @DisplayName("e：末页不再给 cursor（hasMore=false、无 nextCursor、listing 无续页提示）")
    void lastPageHasNoCursor() {
        Map<String, Object> page1 = data(null);
        Map<String, Object> page2 = data((String) page1.get("nextCursor"));
        Map<String, Object> page3 = data((String) page2.get("nextCursor"));

        assertThat(page3.get("hasMore")).isEqualTo(false);
        assertThat(page3).doesNotContainKey("nextCursor");
        assertThat((String) page3.get("listing")).doesNotContain("pass cursor");
        assertThat(names(page3)).hasSize(5).contains(peerName(204));
    }

    // ───────────────────────── f ─────────────────────────

    @Test
    @DisplayName("f：分页状态下 pin 只含本页真正打印的行")
    void pinsOnlyContainPrintedRowsPerPage() {
        Map<String, Object> page1 = data(null);
        List<String> page1Names = names(page1);
        Map<String, String> pinnedAfterPage1 = pins.snapshot(SELF);

        assertThat(pinnedAfterPage1).hasSize(100);
        assertThat(pinnedAfterPage1.values()).allMatch(name -> page1Names.contains(name));
        assertThat(pinnedAfterPage1.values()).doesNotContain(peerName(100), peerName(204));

        Map<String, Object> page2 = data((String) page1.get("nextCursor"));
        List<String> page2Names = names(page2);
        Map<String, String> pinnedAfterPage2 = pins.snapshot(SELF);

        assertThat(pinnedAfterPage2).hasSize(200);
        assertThat(pinnedAfterPage2.values()).as("第 2 页只新增第 2 页打印的行")
            .doesNotContain(peerName(204));
        List<String> union = new ArrayList<>(page1Names);
        union.addAll(page2Names);
        assertThat(union).containsAll(pinnedAfterPage2.values());

        Map<String, Object> page3 = data((String) page2.get("nextCursor"));
        assertThat(names(page3)).contains(peerName(204));
        assertThat(pins.snapshot(SELF).values()).as("末页打印后才登记最后一行的 pin")
            .contains(peerName(204));
    }

    // ───────────────────────── helpers ─────────────────────────

    private static String peerId(int i) {
        return String.format("sess-p%06d", i);
    }

    private static String peerName(int i) {
        return String.format("peer-%03d", i);
    }

    private static ToolUseContext tuc() {
        return new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SELF, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
    }

    private static ToolUseBlock callWithCursor(String cursor) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        if (cursor != null) {
            input.put("cursor", cursor);
        }
        return new ToolUseBlock(UUID.randomUUID().toString(), ListAgentsTool.NAME, input);
    }

    private ToolResult<?> execute(String cursor) {
        return (ToolResult<?>) tool.execute(callWithCursor(cursor), tuc());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(String cursor) {
        return (Map<String, Object>) execute(cursor).data();
    }

    /** 本页 sections 里打印出的所有行名。 */
    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> data) {
        Object sections = data.get("sections");
        List<String> out = new ArrayList<>();
        if (!(sections instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            Map<String, Object> section = (Map<String, Object>) item;
            Object rows = section.get("rows");
            if (!(rows instanceof List<?> r)) {
                continue;
            }
            for (Object row : r) {
                out.add((String) ((Map<String, Object>) row).get("name"));
            }
        }
        return out;
    }
}
