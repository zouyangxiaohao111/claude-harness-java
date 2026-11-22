package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ListAgents · Peer sessions 段的 {@code lastActivityAt} 实际用途：同状态按最近活动倒序。
 *
 * <p><b>WHY</b>：heartbeat 刷新 {@code lastActivityAt} 后该字段必须有消费点，否则接了心跳也没用。
 * ListAgents 是本字段的用户面消费点：running → idle → closing 分组内，刚跑过的会话排在前面。
 *
 * <p><b>变异判据</b>：删掉 {@code planPeerSessions} 里的活动时间排序 ⇒ 本用例必红
 * （退回 snapshot 的地址名序，"zzz-new" 会排到 "aaa-old" 之后）。
 */
@DisplayName("ListAgents · 同状态按 lastActivityAt 倒序")
class ListAgentsActivityOrderTest {

    private static final String SELF = "sess-5e1f0001";
    private static final String OLDER = "sess-aaaaaaa1";
    private static final String NEWER = "sess-bbbbbbb2";

    private PeerSessionRegistry sessions;
    private ListAgentsTool tool;

    @BeforeEach
    void setUp() {
        // ToolUseContext 构造期经 CwdResolution 解析会话项目根（fail-loud）⇒ 绑真实目录。
        SessionProjectRoot.setForSession(SELF, System.getProperty("user.dir"));
        sessions = new PeerSessionRegistry();
        // 同状态（idle）：排序只能由 lastActivityAt 区分。
        sessions.setStatusSource(id -> PeerSessionRegistry.Status.IDLE);
        tool = new ListAgentsTool();
        tool.setPeerSessionRegistry(sessions);
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.clearSession(SELF);
    }

    private static PeerSessionRegistry.Registration reg(String id, String title) {
        return new PeerSessionRegistry.Registration(id, title, "/p", PermissionMode.DEFAULT, 1L);
    }

    private static ToolUseContext tuc() {
        return new ToolUseContext(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SELF, PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null,
            PermissionMode.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> peerRows() {
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(),
            ListAgentsTool.NAME, JsonNodeFactory.instance.objectNode());
        Map<String, Object> data =
            (Map<String, Object>) ((ToolResult<?>) tool.execute(call, tuc())).data();
        Object sections = data.get("sections");
        if (!(sections instanceof List<?> list)) {
            return List.of();
        }
        for (Object item : list) {
            Map<String, Object> section = (Map<String, Object>) item;
            if ("peer_sessions".equals(section.get("kind"))) {
                Object rows = section.get("rows");
                return rows instanceof List<?> r ? (List<Map<String, Object>>) r : List.of();
            }
        }
        return List.of();
    }

    @Test
    @DisplayName("d) 同状态时按 lastActivityAt 倒序（最近活动的会话排前）")
    void sameStatusOrderedByRecentActivity() throws Exception {
        // OLDER 先登记（活动更早），NEWER 后登记（活动更晚）；地址名字母序恰好相反。
        sessions.register(reg(OLDER, "aaa-old"));
        Thread.sleep(25L);
        sessions.register(reg(NEWER, "zzz-new"));

        List<Map<String, Object>> rows = peerRows();
        assertThat(rows).extracting(r -> r.get("id"))
            .as("同状态时最近活动的会话必须排前（否则 lastActivityAt 无消费点）")
            .containsExactly(NEWER, OLDER);
    }
}
