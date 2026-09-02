package com.nexusai.apis.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.NexusAiApplication;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [T2.5] title-only 会话创建集成测试（全新库 Flyway V1→V14 全量执行）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>：锁死原始 Bug 2 的场景回归——<b>全新数据库</b>
 * 下 {@code POST /api/v1/sessions} 只传 {@code {"title": "..."}} 不得 500。原始根因链：
 * 无 settings.main_model_id（V1 种入 settings 行但该列为 NULL）且 models 表无 seed（0 条 INSERT）
 * 时，create 期 {@code resolveDefaultModelName()} 确定性返回 null → {@code setModelName(null)} →
 * 撞 {@code model_name TEXT NOT NULL} → {@code SQLITE_CONSTRAINT_NOTNULL}。T2.1（model_name 可空）
 * + T2.2（create 不预填）修复后此路径必须 201 且落库 NULL。
 *
 * <p><b>全新库隔离</b>：默认 datasource 是 {@code ./nexusai.db}（会复用/污染开发库），故用
 * {@code @TempDir} + {@code @DynamicPropertySource} 把 {@code spring.datasource.url} 指向临时
 * SQLite 文件，Flyway 对空文件全量 V1→V14 → 得到真实"全新库"形态（settings 行存在但
 * main_model_id NULL + models 空表 + model_name 可空）。
 *
 * <p><b>前置自证（fail loud · 规则十二）</b>：{@link #precondition_freshDbSettingsMainModelIdNullAndModelsEmpty()}
 * 断言全新库确为 Bug 2 根因形态（settings.main_model_id NULL + models 0 行），若有人加了
 * models seed 或改了 V1 settings 种入，前置断言显式失败而非静默通过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = NexusAiApplication.class)
// IMP-MV2-17 鉴权收敛：/api/v1/sessions 族已纳入 BearerTokenAuthFilter（无 token → 401）；
// 本测试专注 T2.5 title-only 创建回归（全新库 Flyway 形态），不涉鉴权 —— addFilters=false
// 关闭 MockMvc 自动挂载的过滤器，避免 OAuth token 前置依赖（鉴权行为由
// BearerTokenAuthFilterTest / MemoryControllerTest / SessionControllerTest 专门覆盖）。
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("[T2.5] title-only 会话创建集成测试（全新库）")
class SessionCreateTitleOnlyIntegrationTest {

    // CleanupMode.NEVER：SQLite DB 文件（.db/-shm/-wal）在测试结束时仍被 Hikari 连接池锁定，
    // JUnit 默认 @TempDir 清理会因 "文件被占用" 抛 IOException（Windows 实测复现）。改为 NEVER
    // 留待 JVM 退出时释放（JUnit 官方文档对文件型 DB 锁的推荐处理）。
    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tempDir;

    @DynamicPropertySource
    static void freshDb(DynamicPropertyRegistry reg) {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("fresh-sessions.db").toAbsolutePath()
            + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000";
        // 仅重定向主 datasource（Flyway + MyBatis-Flex + 本测试直查）——Quartz 独立数据源
        // 保持与 McpFullContextStartupTest 同形态（其 job-store 走 ./nexusai.db，不影响本测试断言）。
        reg.add("spring.datasource.url", () -> dbUrl);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private ObjectMapper objectMapper;

    /** 断言 JSON 节点 modelName 为 null 或键缺失（防御未来 @JsonInclude(NON_NULL) 变化，前端双判）。 */
    private void assertModelNameNullOrAbsent(JsonNode node, String context) {
        JsonNode mn = node.get("modelName");
        assertTrue(mn == null || mn.isNull(),
            context + "：modelName 应为 null 或键缺失，实际=" + node);
    }

    // ── 前置自证 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("前置自证：全新库 settings.main_model_id=null 且 models 表为空（Bug2 根因场景成立）")
    void precondition_freshDbSettingsMainModelIdNullAndModelsEmpty() {
        String mainModelId = jdbcTemplate.queryForObject(
            "SELECT main_model_id FROM settings WHERE id=1", String.class);
        assertNull(mainModelId,
            "前置假设失败：全新库 settings.main_model_id 应为 null（V1 仅种入 settings 行），实际=" + mainModelId);

        Integer modelCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM models", Integer.class);
        assertEquals(0, modelCount.intValue(),
            "前置假设失败：全新库 models 表应为空（0 条 seed），实际=" + modelCount);
    }

    // ── 5 个场景 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("title-only 创建：全新库不再 500，返回 201 + modelName 为 null 或键缺失")
    void createTitleOnly_no500_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新会话\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(not(blankOrNullString())))
            .andExpect(jsonPath("$.title").value("新会话"))
            .andReturn();

        assertModelNameNullOrAbsent(
            objectMapper.readTree(result.getResponse().getContentAsString()),
            "create 响应");
    }

    @Test
    @DisplayName("title-only 创建：落库 model_name=null 且 model_tag=DS（相邻字段不受影响）")
    void createTitleOnly_persistsNullModelName() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新会话\"}"))
            .andExpect(status().isCreated())
            .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        SessionRecord rec = sessionMapper.selectOneById(id);
        assertNotNull(rec, "创建后会话必须可直查落库，id=" + id);
        assertNull(rec.getModelName(), "title-only 会话 model_name 必须落库为 null（T2.2 不预填）");
        assertEquals("DS", rec.getModelTag(), "model_tag 默认 DS 不受 model_name 可空影响");
    }

    @Test
    @DisplayName("显式传 modelName：仍落库该值（不因 T2.2 改变）")
    void createWithModelName_keepsExplicit() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"modelName\":\"deepseek-chat\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.modelName").value("deepseek-chat"));
    }

    @Test
    @DisplayName("空 body（无 title 无 modelName）：契约至少传其一 → 400")
    void createWithoutTitleOrModel_400() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("title-only 创建后：list 与 getById 均返回该会话 modelName 为 null 或键缺失")
    void createTitleOnly_thenList_getById() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"新会话\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // list 出口：找到刚创建的会话，其 modelName 应为 null 或键缺失
        MvcResult listResult = mockMvc.perform(get("/api/v1/sessions"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode listNode = objectMapper.readTree(listResult.getResponse().getContentAsString());
        JsonNode ourSession = null;
        for (JsonNode item : listNode) {
            if (id.equals(item.path("id").asText())) {
                ourSession = item;
                break;
            }
        }
        assertNotNull(ourSession, "list 应包含刚创建的会话 id=" + id);
        assertModelNameNullOrAbsent(ourSession, "list 出口");

        // getById 出口：modelName 应为 null 或键缺失
        MvcResult getResult = mockMvc.perform(get("/api/v1/sessions/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andReturn();
        assertModelNameNullOrAbsent(
            objectMapper.readTree(getResult.getResponse().getContentAsString()),
            "getById 出口");
    }
}
