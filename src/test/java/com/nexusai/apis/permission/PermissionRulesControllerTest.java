package com.nexusai.apis.permission;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdatePersister;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

/**
 * WF-11 G3 · {@link PermissionRulesController} 规则管理 CRUD + 重试被拒工具通道
 * （OPD-WF8-01-T4）。
 *
 * <p>对齐 CC {@code /permissions} 命令（PermissionRuleList.tsx）：
 * <ul>
 *   <li>GET  —— 读取 allow/deny/ask 规则（CC getAllowRules/getAskRules/getDenyRules）</li>
 *   <li>POST —— 新增规则（CC addPermissionRulesToSettings，permissionsLoader.ts:229-296）</li>
 *   <li>DELETE —— 删除规则（CC deletePermissionRuleFromSettings，permissionsLoader.ts:163-227；
 *       只读 source 拒绝语义 permissions.ts:1333-1337）</li>
 *   <li>POST retry —— 重试被拒工具（CC onRetryDenials → createPermissionRetryMessage，
 *       permissions.tsx:7-8）</li>
 * </ul>
 */
class PermissionRulesControllerTest {

    private UserSettingsLoader userLoader;
    private ProjectSettingsLoader projectLoader;
    private LocalSettingsLoader localLoader;
    private PermissionUpdatePersister persister;
    private PermissionRuleValueParser parser;
    private MessageService messageService;
    private SessionAgentStateRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userLoader = mock(UserSettingsLoader.class);
        projectLoader = mock(ProjectSettingsLoader.class);
        localLoader = mock(LocalSettingsLoader.class);
        persister = mock(PermissionUpdatePersister.class);
        parser = mock(PermissionRuleValueParser.class);
        messageService = mock(MessageService.class);
        registry = mock(SessionAgentStateRegistry.class);

        PermissionRulesController controller = new PermissionRulesController();
        ReflectionTestUtils.setField(controller, "userSettingsLoader", userLoader);
        ReflectionTestUtils.setField(controller, "projectSettingsLoader", projectLoader);
        ReflectionTestUtils.setField(controller, "localSettingsLoader", localLoader);
        ReflectionTestUtils.setField(controller, "permissionUpdatePersister", persister);
        ReflectionTestUtils.setField(controller, "ruleValueParser", parser);
        ReflectionTestUtils.setField(controller, "messageService", messageService);
        ReflectionTestUtils.setField(controller, "sessionAgentStateRegistry", registry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /rules → 200 + 三源规则合并列表（CC getAllowRules/getAskRules/getDenyRules 数据面）")
    void listReturnsAllEditableRules() throws Exception {
        PermissionRule allowRule = new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Bash"));
        PermissionRule denyRule = new PermissionRule(
            PermissionRuleSource.PROJECT_SETTINGS, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Read", "/secret/**"));
        when(userLoader.load()).thenReturn(List.of(allowRule));
        when(projectLoader.load()).thenReturn(List.of(denyRule));
        when(localLoader.load()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/permissions/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].source").value("userSettings"))
            .andExpect(jsonPath("$[0].behavior").value("allow"))
            .andExpect(jsonPath("$[0].ruleValue").value("Bash"))
            .andExpect(jsonPath("$[1].source").value("projectSettings"))
            .andExpect(jsonPath("$[1].behavior").value("deny"))
            .andExpect(jsonPath("$[1].ruleValue").value("Read(/secret/**)"));
    }

    @Test
    @DisplayName("POST /rules → 201 + 经 persister AddRules 增量写盘（CC addPermissionRulesToSettings）")
    void addPersistsAddRules() throws Exception {
        when(parser.parse("Bash")).thenReturn(PermissionRuleValue.wholeTool("Bash"));

        mockMvc.perform(post("/api/v1/permissions/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"destination":"userSettings","behavior":"allow","rules":["Bash"]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.added").value(1));

        verify(persister).persist(any(PermissionUpdate.AddRules.class));
    }

    @Test
    @DisplayName("DELETE /rules → 200 + 经 persister RemoveRules（CC deletePermissionRuleFromSettings）")
    void deletePersistsRemoveRules() throws Exception {
        when(parser.parse("Bash")).thenReturn(PermissionRuleValue.wholeTool("Bash"));

        mockMvc.perform(delete("/api/v1/permissions/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"destination":"userSettings","behavior":"allow","rules":["Bash"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removed").value(1));

        verify(persister).persist(any(PermissionUpdate.RemoveRules.class));
    }

    @Test
    @DisplayName("DELETE /rules 只读 source → 400（对齐 CC deletePermissionRule 只读拒绝 permissions.ts:1333-1337）")
    void deleteRejectsReadOnlySource() throws Exception {
        mockMvc.perform(delete("/api/v1/permissions/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"destination":"policySettings","behavior":"allow","rules":["Bash"]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /rules malformed 规则 → 400（显式失败，不静默写盘）")
    void addRejectsMalformedRule() throws Exception {
        when(parser.parse("Bash(x")).thenReturn(null);

        mockMvc.perform(post("/api/v1/permissions/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"destination":"userSettings","behavior":"allow","rules":["Bash(x"]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /permission-retry → 202 + 追加 permission_retry 系统消息（CC createPermissionRetryMessage）")
    void retryDenialsAppendsPermissionRetryMessage() throws Exception {
        when(messageService.appendMessage(any(ChatMessageDto.class))).thenAnswer(inv -> inv.getArgument(0));
        when(registry.get(any(UUID.class))).thenReturn(null);

        mockMvc.perform(post("/api/v1/sessions/{sessionId}/permission-retry",
                    "00000000-0000-0000-0000-000000000002")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"commands":["Bash(git status)","Read(/a.txt)"]}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.messageId").isNotEmpty());

        verify(messageService).appendMessage(any(ChatMessageDto.class));
    }

    @Test
    @DisplayName("POST /permission-retry 空 commands → 400")
    void retryDenialsRejectsEmptyCommands() throws Exception {
        mockMvc.perform(post("/api/v1/sessions/{sessionId}/permission-retry",
                    "00000000-0000-0000-0000-000000000002")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"commands":[]}
                    """))
            .andExpect(status().isBadRequest());
    }
}
