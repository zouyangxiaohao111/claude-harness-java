package com.nexusai.application.chat;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.eventbus.ws.MessageUserEvent;
import com.nexusai.eventbus.ws.SessionStatusEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [P5-①] ChatService userInvocable=false 拒绝消息测试 · 对齐 CC processSlashCommand.tsx:526-548。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>userInvocable=false 技能不得被用户直接调用</b>——CC 拒绝路径返回
 *       {@code shouldQuery:false}（不起模型）+ 推第二条 user 可见消息。若本测试通过则证明：
 *       rejectNonUserInvocable 返回 true（调用方终结、不启动 LlmAgentLoop）+ 推送精确复刻
 *       CC :543 的拒绝文案。</li>
 *   <li><b>userInvocable=true / 未知命令 / 非 slash 输入必须回落正常路径</b>（返回 false）——
 *       拒绝逻辑不得误伤正常技能（回归防线）。</li>
 *   <li><b>位置约束（风险 §1）</b>：拒绝判定只落在用户输入入口（ChatService/CronIdleExecutor），
 *       不落在 SkillToolImpl（模型经 SkillTool 主动调用 userInvocable=false 技能仍放行）。
 *       本测试在 ChatService 层锁定该位置语义。</li>
 * </ol>
 */
@DisplayName("[P5-①] userInvocable=false 拒绝消息")
class ChatServiceUserInvocableRejectionTest {

    private ChatService service;
    private com.nexusai.domain.session.MessageService messageService;
    private SimpMessagingTemplate wsTemplate;
    private String sid;

    /** 写一个 frontmatter 显式 user-invocable 的 SKILL.md 到 skillsRoot/skillName/SKILL.md。 */
    private static void writeSkill(Path skillsRoot, String skillName, boolean userInvocable) throws Exception {
        Path dir = skillsRoot.resolve(skillName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: " + skillName + "\nuser-invocable: " + userInvocable + "\n---\n# " + skillName + "\n");
    }

    @BeforeEach
    void setUp() {
        service = new ChatService();
        sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        messageService = mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        wsTemplate = mock(SimpMessagingTemplate.class);
    }

    @Test
    @DisplayName("userInvocable=false 技能 → 返回 true + 推精确 CC :543 拒绝文案 + status=idle（不起模型）")
    void reject_userInvocableFalse_pushesRejection(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "locked-skill", false);
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        ReflectionTestUtils.setField(service, "skillRegistry", registry);

        boolean rejected = service.rejectNonUserInvocable(sid, "/locked-skill", "msg-user-1", wsTemplate);

        assertThat(rejected)
            .as("P5-①: userInvocable=false 命中 → 拒绝路径返回 true（调用方不启动 LlmAgentLoop，CC shouldQuery:false）")
            .isTrue();
        // 持久化第二条 user 可见消息（CC createUserMessage 同样落 transcript）
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService, atLeastOnce()).createQueuedUserMessage(eq(sid), any(), contentCaptor.capture());
        assertThat(contentCaptor.getValue())
            .as("P5-①: 拒绝文案精确复刻 CC processSlashCommand.tsx:543")
            .isEqualTo("This skill can only be invoked by Claude, not directly by users. "
                + "Ask Claude to use the \"locked-skill\" skill for you.");
        // 推 message.user（拒绝文案）+ status=idle
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate, atLeastOnce()).convertAndSend(eq("/topic/sessions/" + sid + "/stream"), eventCaptor.capture());
        boolean hasRejectionEvent = eventCaptor.getAllValues().stream()
            .anyMatch(e -> e instanceof MessageUserEvent u
                && u.getContent().contains("can only be invoked by Claude"));
        boolean hasIdleStatus = eventCaptor.getAllValues().stream()
            .anyMatch(e -> e instanceof SessionStatusEvent s && "idle".equals(s.getStatus()));
        assertThat(hasRejectionEvent).as("P5-①: 推 message.user（拒绝文案，user 可见）").isTrue();
        assertThat(hasIdleStatus).as("P5-①: 推 status=idle（CC shouldQuery:false 等价）").isTrue();
    }

    @Test
    @DisplayName("userInvocable=true 技能 → 返回 false（回落正常 LLM 路径，不误伤）")
    void reject_userInvocableTrue_fallsThrough(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "open-skill", true);
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        ReflectionTestUtils.setField(service, "skillRegistry", registry);

        boolean rejected = service.rejectNonUserInvocable(sid, "/open-skill", "msg-user-1", wsTemplate);

        assertThat(rejected).as("P5-①: userInvocable=true → 正常路径").isFalse();
        verify(messageService, never()).createQueuedUserMessage(any(), any(), any());
    }

    @Test
    @DisplayName("未知命令 / 非 slash 输入 / skillRegistry 未注入 → 返回 false（回归防线）")
    void reject_missOrNonSlash_fallsThrough() {
        // skillRegistry 未注入 → 恒 false
        boolean noRegistry = service.rejectNonUserInvocable(sid, "/whatever", "msg-user-1", wsTemplate);
        assertThat(noRegistry).as("P5-①: skillRegistry 未注入 → 回落正常路径").isFalse();
    }

    @Test
    @DisplayName("unknown command（registry 未命中）→ 返回 false")
    void reject_unknownCommand_fallsThrough(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "known", true);
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        ReflectionTestUtils.setField(service, "skillRegistry", registry);

        boolean rejected = service.rejectNonUserInvocable(sid, "/not-there", "msg-user-1", wsTemplate);
        assertThat(rejected).as("P5-①: 未命中命令 → 回落正常路径").isFalse();
    }

    @Test
    @DisplayName("非 slash 输入（普通文本）→ 返回 false")
    void reject_plainText_fallsThrough(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "locked-skill", false);
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        ReflectionTestUtils.setField(service, "skillRegistry", registry);

        boolean rejected = service.rejectNonUserInvocable(sid, "just a normal question", "msg-user-1", wsTemplate);
        assertThat(rejected).as("P5-①: 非 slash 输入 → 回落正常路径").isFalse();
        verify(messageService, never()).createQueuedUserMessage(any(), any(), any());
    }

    @Test
    @DisplayName("immediate local-jsx 判定：immediate+local-jsx+enabled 才命中；非 immediate/未命中回落")
    void immediateLocalJsx_detection(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "normal-skill", true);
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        ReflectionTestUtils.setField(service, "skillRegistry", registry);
        when(messageService.createQueuedUserMessage(any(), any(), any())).thenReturn(null);

        // 文件系统技能非 immediate → 非 immediate 命令判定为 false（进 LLM/排队）
        assertThat(service.isImmediateLocalJsxCommand("/normal-skill"))
            .as("P5-②: 普通 prompt 技能非 immediate → 回落")
            .isFalse();
        // 未命中命令 → false
        assertThat(service.isImmediateLocalJsxCommand("/no-such")).isFalse();
        // 非 slash → false
        assertThat(service.isImmediateLocalJsxCommand("plain text")).isFalse();
        // 无 registry → false
        ChatService bare = new ChatService();
        assertThat(bare.isImmediateLocalJsxCommand("/anything")).isFalse();
    }

    @Test
    @DisplayName("dispatchImmediateLocalJsx：无命名 handler → 返回 false（fail loud 回落排队，不静默吞）")
    void dispatchImmediate_noHandler_returnsFalse() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        ReflectionTestUtils.setField(service, "userInputDispatcher", dispatcher);

        // 未注册 handler 的命令 → dispatch 返回 false（调用方回落原 busy 排队）
        boolean dispatched = service.dispatchImmediateLocalJsx(sid, "msg-immediate-1", "/never-registered",
            true, wsTemplate);
        assertThat(dispatched).as("P5-②: 无命名 handler → 回落（fail loud）").isFalse();
    }

    @Test
    @DisplayName("dispatchImmediateLocalJsx：命名 handler 已注册 → dispatch + 落库 + 推 message.user")
    void dispatchImmediate_withHandler_dispatchesAndPushes() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean(false);
        dispatcher.registerSlashCommand("btw", args -> executed.set(true));
        ReflectionTestUtils.setField(service, "userInputDispatcher", dispatcher);
        when(messageService.createQueuedUserMessage(eq(sid), any(), eq("/btw info"))).thenReturn(
            new com.nexusai.model.session.dto.MessageCreatedResponse(
                "msg-immediate-1", "msg-stub-pending", "/topic/sessions/" + sid + "/stream", false));

        boolean dispatched = service.dispatchImmediateLocalJsx(
            sid, "msg-immediate-1", "/btw info", true, wsTemplate);

        assertThat(dispatched).as("P5-②: 命名 handler 命中 → dispatch 成功").isTrue();
        assertThat(executed.get()).as("P5-②: handler 已执行（立即执行不排队）").isTrue();
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate).convertAndSend(eq("/topic/sessions/" + sid + "/stream"), eventCaptor.capture());
        assertThat(eventCaptor.getValue())
            .as("P5-②: busy 路径推 message.user（web 无 TUI 显式推送，CC setToolJSX 展示等价）")
            .isInstanceOf(MessageUserEvent.class);
    }
}
