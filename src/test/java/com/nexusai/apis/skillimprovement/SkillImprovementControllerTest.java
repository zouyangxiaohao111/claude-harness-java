package com.nexusai.apis.skillimprovement;

import com.nexusai.application.agent.permission.hook.SkillImprovementHook;
import com.nexusai.application.agent.permission.hook.SkillImprovementHook.SkillUpdate;
import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore.PendingSuggestion;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-13] SkillImprovementController REST 决策闸门测试 · 对齐 CC
 * {@code useSkillImprovementSurvey.ts handleSelect (:53-98)}.
 *
 * <p>WHY (规则九 · 测试验证意图): 本 controller 是 apply 半环的<b>用户决策闸门</b> —
 * CC 必须用户显式 applied/dismissed 才重写 skill 文件 (自动触发有静默改文件副作用风险).
 * 关键意图:
 * <ul>
 *   <li><b>applied → 真实 apply</b>: 触发 {@link SkillImprovementHook#applySkillImprovement}
 *       改写 .claude/skills/&lt;name&gt;/SKILL.md (CC :73)</li>
 *   <li><b>dismissed → 不 rewrite</b>: 仅清空 store, 绝不触碰 skill 文件 (CC :89-95)</li>
 *   <li><b>skillName 不信任客户端</b>: decision 只从 store 读, 不接受客户端 skillName
 *       (防路径穿越) — 客户端伪造 skillName 无法指定写回路径</li>
 *   <li><b>store 清空</b>: 决策后 suggestion 移除, 避免同 suggestion 重复 apply</li>
 *   <li><b>responded 遥测</b>: tengu_skill_improvement_survey responded=applied/dismissed (CC :60-70)</li>
 * </ul>
 */
@DisplayName("[P1-13] SkillImprovementController REST 决策闸门")
class SkillImprovementControllerTest {

    /** 构造 controller: 真实 store + 真实 hook (modelQuery 打桩返回 <updated_file>), baseDir=tempDir. */
    private static SkillImprovementController controller(Telemetry telemetry,
                                                        SkillImprovementSuggestionStore store,
                                                        String llmResponse,
                                                        Path baseDir) {
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, llm, options) -> llmResponse,
                () -> Optional.empty(),
                telemetry,
                (skillName, updates) -> {},
                baseDir);
        return new SkillImprovementController(store, hook, telemetry);
    }

    private static Path createSkill(Path baseDir, String skillName, String content) throws Exception {
        // R9-2：项目级技能目录随 appName 动态（决策 D1/D6）= <baseDir>/<getProjectDirName()>/skills
        Path skillDir = baseDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve(skillName);
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, content);
        return skillMd;
    }

    /** fire-and-forget apply 是异步的 — 轮询等待写回 (≤5s). */
    private static void awaitFileContent(Path file, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file) && Files.readString(file).contains(expected)) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /** WHY: applied 决策必须真实改写 SKILL.md — 若只清 store 不 apply, apply 半环仍是断的. */
    @Test
    @DisplayName("decision applied → 真实改写 SKILL.md + store 清空 + responded 遥测")
    void decision_applied_rewritesSkillAndClearsStore(@TempDir Path tempDir) throws Exception {
        Telemetry telemetry = new Telemetry();
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path skillMd = createSkill(tempDir, "my-skill", "# Original content");
        store.put(sessionId, new PendingSuggestion("my-skill",
                List.of(new SkillUpdate("new step", "ask energy", "user asked"))));

        SkillImprovementController controller = controller(
                telemetry, store, "<updated_file># Rewritten by applier</updated_file>", tempDir);

        ResponseEntity<Map<String, Object>> resp =
                controller.decision(new SkillImprovementController.SkillImprovementDecisionRequest(sessionId.toString(), true));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("status", "applied");
        // store 清空 + responded 遥测 (CC :89-95 / :60-70)
        assertThat(store.peek(sessionId)).isNull();
        assertThat(store.size()).isZero();
        assertThat(telemetry.getCounter("tengu_skill_improvement_survey")).isEqualTo(1);
        // 真实 apply (CC :73) — 等异步写回
        awaitFileContent(skillMd, "# Rewritten by applier");
        assertThat(Files.readString(skillMd)).isEqualTo("# Rewritten by applier");
    }

    /** WHY: dismissed 决策绝不能改写 skill 文件 (CC handleSelect applied=false → 只清 suggestion). */
    @Test
    @DisplayName("decision dismissed → 不改写 SKILL.md + store 清空 + responded 遥测")
    void decision_dismissed_keepsSkillAndClearsStore(@TempDir Path tempDir) throws Exception {
        Telemetry telemetry = new Telemetry();
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path skillMd = createSkill(tempDir, "my-skill", "# Original content");
        store.put(sessionId, new PendingSuggestion("my-skill",
                List.of(new SkillUpdate("s", "c", "r"))));

        SkillImprovementController controller = controller(
                telemetry, store, "<updated_file># SHOULD NOT APPLY</updated_file>", tempDir);

        ResponseEntity<Map<String, Object>> resp =
                controller.decision(new SkillImprovementController.SkillImprovementDecisionRequest(sessionId.toString(), false));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("status", "dismissed");
        assertThat(store.peek(sessionId)).isNull();
        assertThat(store.size()).isZero();
        assertThat(telemetry.getCounter("tengu_skill_improvement_survey")).isEqualTo(1);
        // 稍等, 确保没有异步 apply 写回
        Thread.sleep(200);
        assertThat(Files.readString(skillMd)).isEqualTo("# Original content");
    }

    /** WHY: decision 不接受客户端 skillName — 客户端伪造 skillName 不得触发任意路径写回 (防路径穿越). */
    @Test
    @DisplayName("decision 只读 store: 无待定 suggestion → 404, 不 apply 不改写")
    void decision_unknownSession_returns404(@TempDir Path tempDir) throws Exception {
        Telemetry telemetry = new Telemetry();
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        Path skillMd = createSkill(tempDir, "my-skill", "# Original content");

        SkillImprovementController controller = controller(
                telemetry, store, "<updated_file># SHOULD NOT APPLY</updated_file>", tempDir);

        ResponseEntity<Map<String, Object>> resp = controller.decision(
                new SkillImprovementController.SkillImprovementDecisionRequest(UUID.randomUUID().toString(), true));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(store.size()).isZero();
        assertThat(telemetry.getCounter("tengu_skill_improvement_survey")).isZero();
        Thread.sleep(200);
        assertThat(Files.readString(skillMd)).isEqualTo("# Original content");
    }

    /** WHY: null sessionId → 400 (REST 层残缺请求显式失败, 规则十二). */
    @Test
    @DisplayName("decision sessionId 缺失 → 400")
    void decision_nullSessionId_returns400() {
        SkillImprovementController controller = controller(
                new Telemetry(), new SkillImprovementSuggestionStore(), "<updated_file>x</updated_file>", null);

        ResponseEntity<Map<String, Object>> resp =
                controller.decision(new SkillImprovementController.SkillImprovementDecisionRequest(null, true));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    /** WHY: GET /suggestion 镜像 CC useAppState 读 suggestion — 有待定 → 200, 无 → 204. */
    @Test
    @DisplayName("GET /suggestion: 有待定 → 200 {skillName,updates}; 无 → 204")
    void suggestion_returnsPendingOrNoContent() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        SkillImprovementController controller =
                controller(new Telemetry(), store, "", null);

        // 无待定 → 204
        assertThat(controller.suggestion(sessionId.toString()).getStatusCode().value()).isEqualTo(204);

        // 有待定 → 200 + suggestion body
        store.put(sessionId, new PendingSuggestion("my-skill",
                List.of(new SkillUpdate("s", "c", "r"))));
        ResponseEntity<PendingSuggestion> resp = controller.suggestion(sessionId.toString());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().skillName()).isEqualTo("my-skill");
        assertThat(resp.getBody().updates()).hasSize(1);
    }

    /**
     * WHY: [IMP-WF6-DC-01] REST 决策端点接受 {@code "sess-xxxxxxxx"} 原始会话键 —
     * 前端 STOMP 建议事件（/topic/sessions/{sess-xxx}）收到的会话 id 就是该格式，且与
     * 全仓其余 API（/api/v1/sessions/{sessionId}/...）约定一致；经 ChatService.parseSessionUuid
     * 归一化到 store 的 UUID 键空间（ChatService.java:856 拼接 0000）。若 sessionId 只支持 UUID，
     * 前端无法用自有 sess-xxx 会话键直接调用 decision/suggestion，survey 闭环断裂。
     */
    @Test
    @DisplayName("GET /suggestion + POST /decision 接受 sess-xxx 会话键 (session-id-short 直键)")
    void suggestion_and_decision_acceptSessKey() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessKey = "sess-abc12345";
        // [session-id-short] store 键空间已统一 short 直键（不再 parseSessionUuid 归一 UUID）
        store.put(sessKey, new PendingSuggestion("my-skill",
                List.of(new SkillUpdate("s", "c", "r"))));
        SkillImprovementController controller =
                controller(new Telemetry(), store, "", null);

        // GET /suggestion 用 sess-xxx 键 → 直键命中
        ResponseEntity<PendingSuggestion> peek = controller.suggestion(sessKey);
        assertThat(peek.getStatusCode().value()).isEqualTo(200);
        assertThat(peek.getBody().skillName()).isEqualTo("my-skill");

        // POST /decision 用 sess-xxx 键 → 原子移除 + responded 遥测（applied=true 触发 apply，baseDir=null 守卫静默）
        ResponseEntity<Map<String, Object>> resp = controller.decision(
                new SkillImprovementController.SkillImprovementDecisionRequest(sessKey, true));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("status", "applied");
        assertThat(store.size()).isZero();
    }
}
