package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.provider.dto.ModelTag;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [V33] SessionService bare（精简）模式会话级开关读写（用户 2026-08-23 拍板：bareMode 随会话走）。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：bareMode 是会话级属性（sessions.bare_mode 列），前端「精简模式」
 * 开关经 create/update 写入、list/getById 读取回显。CC 侧 bare 判定是进程级 env（isBareMode()
 * envUtils.ts:60-65），Web 多会话无进程级语义 → Java 以会话列承载（对齐 effort_level / ultracode_enabled
 * 同款 V31/V32 范式）。本测试钉死读写契约：
 * <ol>
 *   <li><b>create 落库</b>——POST 带 bareMode=true → SessionRecord.bareMode=1（Boolean→0/1）；</li>
 *   <li><b>update 落库</b>——PATCH 带 bareMode=false → SessionRecord.bareMode=0（PATCH 语义，null 不改动）；</li>
 *   <li><b>toDto 透出</b>——0/1 → Boolean（getById 回显），null 保持 null（未显式设置）。</li>
 * </ol>
 * 变异点：任意一环缺失 → 前端精简模式开关无法持久化/回显 → 会话级 bare 判定（MemoryBareModeConfig
 * isBareMode(sessionId)）读不到该会话设置 → 行为偏离拍板。
 */
@DisplayName("[V33] SessionService bare 会话级开关读写")
class SessionServiceTest {

    private SessionService service;
    private SessionMapper sessionMapper;

    @BeforeEach
    void setUp() {
        service = new SessionService();
        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        // create/update/getById 不触达 message/sessionFile/chatService/suggestionStore
        ReflectionTestUtils.setField(service, "messageMapper", mock(com.nexusai.repository.session.mapper.MessageMapper.class));
        ReflectionTestUtils.setField(service, "sessionFileMapper", mock(com.nexusai.repository.session.mapper.SessionFileMapper.class));
    }

    @Test
    @DisplayName("create 带 bareMode=true → 落库 sessions.bare_mode=1 + SessionDto.bareMode=true")
    void create_persistsBareModeTrue() {
        // WHY: 前端「精简模式」开关在创建会话时传入 → 必须落库（V33 列），否则该会话 bare 判定无源可读。
        SessionCreateRequest req = new SessionCreateRequest("需求分析", ModelTag.DS, null, null, true);

        SessionDto dto = service.create(req);

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getBareMode())
                .as("create 落库 bare_mode=1（Boolean true → 0/1）")
                .isEqualTo(1);
        assertThat(dto.bareMode())
                .as("create 返回 DTO bareMode=true 回显")
                .isTrue();
    }

    @Test
    @DisplayName("create 不带 bareMode → 落库 null（会话未显式设置，回落全局判定）")
    void create_nullBareModeStaysNull() {
        // WHY: bareMode 是可选开关（null = 该会话未显式设置），不得强塞默认值——判定回落 env/配置/false。
        SessionCreateRequest req = new SessionCreateRequest("需求分析", ModelTag.DS, null, null, null);

        SessionDto dto = service.create(req);

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getBareMode())
                .as("create 未带 bareMode → bare_mode 保持 null")
                .isNull();
        assertThat(dto.bareMode()).isNull();
    }

    @Test
    @DisplayName("update 带 bareMode=false → 落库 bare_mode=0 + SessionDto.bareMode=false")
    void update_persistsBareModeFalse() {
        // WHY: PATCH 语义局部更新——前端切换精简模式关闭 → 该会话 bare_mode=0，且不被全局 true 覆盖
        //   （会话级关闭优先）。变异点：update 漏写 bareMode → 开关无法持久化 → 会话级判定读旧值 → 红。
        SessionRecord existing = new SessionRecord();
        existing.setId("sess-1");
        existing.setTitle("旧标题");
        existing.setBareMode(1);                  // 会话原为 bare 开启
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);

        SessionUpdateRequest req = new SessionUpdateRequest("新标题", null, null, null, false, null, null);
        SessionDto dto = service.update("sess-1", req);

        assertThat(existing.getBareMode())
                .as("update bareMode=false → 会话记录 bare_mode=0")
                .isZero();
        assertThat(existing.getTitle()).as("PATCH 非 bare 字段照常更新").isEqualTo("新标题");
        assertThat(dto.bareMode()).as("update 返回 DTO bareMode=false 回显").isFalse();
    }

    @Test
    @DisplayName("update 不带 bareMode → bare_mode 不改动（PATCH 语义，null 跳过）")
    void update_nullBareModeUnchanged() {
        // WHY: PATCH 全字段可选——未传 bareMode 不得误改会话 bare 开关。
        SessionRecord existing = new SessionRecord();
        existing.setId("sess-1");
        existing.setBareMode(1);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);

        SessionUpdateRequest req = new SessionUpdateRequest("新标题", null, null, null, null, null, null);
        service.update("sess-1", req);

        assertThat(existing.getBareMode())
                .as("update 未带 bareMode → 原 bare_mode=1 保持")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("getById 会话 bare_mode=1 → SessionDto.bareMode=true（0/1 → Boolean 透出）")
    void getById_toDtoExposesBareModeTrue() {
        // WHY: 前端列表/详情回显会话 bare 开关状态——0/1 列须转 Boolean 透出（对齐 ultracodeEnabled）。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        record.setBareMode(1);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.bareMode()).as("bare_mode=1 → DTO bareMode=true").isTrue();
    }

    @Test
    @DisplayName("getById 会话 bare_mode=null → SessionDto.bareMode=null（未显式设置保持 null）")
    void getById_toDtoNullBareModeStaysNull() {
        // WHY: 未显式设置 bare 的会话（老数据/null）回显 null——前端据此显示"跟随全局默认"而非硬值。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.bareMode()).as("bare_mode=null → DTO bareMode=null").isNull();
    }

    @Test
    @DisplayName("list 全量会话 → bare_mode 逐条透出为 Boolean")
    void list_toDtoExposesBareMode() {
        // WHY: 前端会话列表需要每会话 bare 开关状态（可批量显示/区分精简会话）。
        SessionRecord on = new SessionRecord();
        on.setId("sess-1");
        on.setModelTag(ModelTag.DS.name());
        on.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        on.setMessageCount(0);
        on.setBareMode(1);
        SessionRecord off = new SessionRecord();
        off.setId("sess-2");
        off.setModelTag(ModelTag.DS.name());
        off.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        off.setMessageCount(0);
        off.setBareMode(0);
        when(sessionMapper.selectAll()).thenReturn(java.util.List.of(on, off));

        java.util.List<SessionDto> dtos = service.list();

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).bareMode()).isTrue();
        assertThat(dtos.get(1).bareMode()).isFalse();
    }

    // ═══════════════════════ [A4] 会话级 teamContext（V39 列 team_context）═══════════════════════

    @Test
    @DisplayName("getTeamContext 缺失/空列/解析失败 → null（fail-soft，工具回退 appState）")
    void getTeamContext_missingOrNullColumn_returnsNull() {
        // WHY: V39 team_context 可空（null = 该会话未建 team）——未建 team 的会话读回 null，
        //   工具经 null 回退 ctx.getAppState()（同轮内存态），不抛（对齐 TeamHelpers.readConfig ENOENT→null）。
        when(sessionMapper.selectOneById("sess-1")).thenReturn(null);
        assertThat(service.getTeamContext("sess-1")).as("session 不存在 → null").isNull();

        SessionRecord rec = new SessionRecord();
        rec.setId("sess-1");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(rec);
        assertThat(service.getTeamContext("sess-1")).as("列 null → null").isNull();

        rec.setTeamContext("not-json{");
        assertThat(service.getTeamContext("sess-1")).as("解析失败 → null（fail-soft，不抛）").isNull();
    }

    @Test
    @DisplayName("setTeamContext 落库 JSON + getTeamContext 回读同构 Map（round-trip）")
    void setTeamContext_then_getTeamContext_roundTrip() {
        // WHY: TeamCreateTool 写 / TeamDeleteTool 清 / SendMessageTool 读共用会话列（A4），
        //   set 后 get 必须回读同构结构（teamName/leadAgentId/teammates 嵌套），否则工具链跨回合断链
        //   （变异点：只写 appState 不落列 → 跨工具即失，SendMessage/TeamDelete 读不到 team）。
        SessionRecord rec = new SessionRecord();
        rec.setId("sess-1");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(rec);

        Map<String, Object> teamContext = new LinkedHashMap<>();
        teamContext.put("teamName", "research-team");
        teamContext.put("teamFilePath", "/tmp/teams/research-team/config.json");
        teamContext.put("leadAgentId", "team-lead@research-team");
        Map<String, Object> teammates = new LinkedHashMap<>();
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("name", "team-lead");
        lead.put("agentType", "team-lead");
        teammates.put("team-lead@research-team", lead);
        teamContext.put("teammates", teammates);

        service.setTeamContext("sess-1", teamContext);

        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        verify(sessionMapper).update(captor.capture());
        assertThat(captor.getValue().getTeamContext())
                .as("set 必须落 JSON 串（sessions.team_context 列）")
                .contains("\"teamName\":\"research-team\"")
                .contains("\"leadAgentId\":\"team-lead@research-team\"")
                .contains("\"teammates\"");

        Map<String, Object> readBack = service.getTeamContext("sess-1");
        assertThat(readBack).as("get 回读同构 Map").isNotNull();
        assertThat(readBack.get("teamName")).isEqualTo("research-team");
        assertThat(readBack.get("leadAgentId")).isEqualTo("team-lead@research-team");
        assertThat(readBack.get("teammates")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("clearTeamContext → update(entity, false) 显式写 NULL（bugfix2 修正：完整实体非稀疏 patch）")
    void clearTeamContext_setsNull() {
        // WHY: TeamDeleteTool.ts:118-124 清 teamContext —— 会话列须置 null（读回 null → 工具回退 appState），
        //   否则删除后残留旧 team 上下文。三层坑（team-panel-backend-bugfix2 + 修正）：
        //   ① update(entity) 默认 ignoreNulls=true → null 被 SET 跳过 → DB 残留（no-op）；
        //   ② 稀疏 patch + updateByQuery(patch,false) 会把 patch 所有 null 字段写 NULL → model_tag
        //      NOT NULL 违反 → 500（前端联调实测）；
        //   ③ 正确：selectOneById 完整实体置 null + update(entity, false) —— 仅 team_context 写 NULL，
        //      非目标列保留 DB 原值（对齐 EffortCommand:354 / ProjectSessionBindingService:80）。
        SessionRecord rec = new SessionRecord();
        rec.setId("sess-1");
        rec.setModelTag("DS");  // model_tag NOT NULL 非空，模拟完整实体（selectOneById 返回全字段）
        rec.setTeamContext("{\"teamName\":\"research-team\"}");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(rec);

        service.clearTeamContext("sess-1");

        // 清空路径必须走 update(entity, false)（完整实体 + ignoreNulls=false），非 updateByQuery 稀疏 patch
        ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
        ArgumentCaptor<Boolean> ignoreNullsCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(sessionMapper).update(captor.capture(), ignoreNullsCaptor.capture());
        assertThat(captor.getValue().getTeamContext()).as("clear 必须落 null").isNull();
        assertThat(captor.getValue().getModelTag())
            .as("完整实体非目标列（model_tag NOT NULL）保留原值，不得被误写 NULL")
            .isEqualTo("DS");
        assertThat(ignoreNullsCaptor.getValue())
            .as("ignoreNulls=false 显式写 NULL（update(entity) 默认忽略 null 字段导致 DB 残留）")
            .isFalse();
    }

    @Test
    @DisplayName("[P2] getById 会话 team_context 列 → SessionDto.teamContext 解析透出（嵌套 teammates Map）")
    void getById_toDtoExposesTeamContext() {
        // WHY: [P2] SessionDto 加 teamContext 字段——前端会话详情头显示当前 team。变异点：toDto 不回填
        //   → GET /sessions/{id} 拿不到 teamContext → 前端详情无 team 信息（P2 契约 §4.3 失效）。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        record.setTeamContext("{\"teamName\":\"research-team\",\"teamFilePath\":\"/tmp/teams/research-team/config.json\","
            + "\"leadAgentId\":\"team-lead@research-team\",\"teammates\":{\"team-lead@research-team\":{\"name\":\"team-lead\"}}}");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.teamContext()).as("toDto 必须回填 teamContext（P2）").isNotNull();
        assertThat(dto.teamContext().get("teamName")).isEqualTo("research-team");
        assertThat(dto.teamContext().get("leadAgentId")).isEqualTo("team-lead@research-team");
        assertThat(dto.teamContext().get("teammates")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("[P2] list 全量会话 → teamContext 逐条透出（有 team → Map；无 team → null）")
    void list_toDtoExposesTeamContextPerRow() {
        // WHY: [P2] GET /sessions 列表也返回 teamContext——前端会话列表可批量显示 team 状态。
        SessionRecord withTeam = new SessionRecord();
        withTeam.setId("sess-1");
        withTeam.setModelTag(ModelTag.DS.name());
        withTeam.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        withTeam.setMessageCount(0);
        withTeam.setTeamContext("{\"teamName\":\"research-team\"}");
        SessionRecord withoutTeam = new SessionRecord();
        withoutTeam.setId("sess-2");
        withoutTeam.setModelTag(ModelTag.DS.name());
        withoutTeam.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        withoutTeam.setMessageCount(0);
        when(sessionMapper.selectAll()).thenReturn(java.util.List.of(withTeam, withoutTeam));

        java.util.List<SessionDto> dtos = service.list();

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).teamContext().get("teamName")).isEqualTo("research-team");
        assertThat(dtos.get(1).teamContext()).as("无 team 会话 → teamContext=null").isNull();
    }

    @Test
    @DisplayName("[P2] parseTeamContext 单测：合法 JSON / 空白 / 非对象 / 非法 JSON → 相应 null")
    void parseTeamContext_edgeCases() {
        // WHY: parseTeamContext 是 toDto/ProjectSessionBindingService 的公共解析（fail-soft）——
        //   合法 JSON 解析为 Map；空白/非对象/非法 JSON 一律 null（不抛）。
        Map<String, Object> ok = SessionService.parseTeamContext("{\"teamName\":\"t\",\"teammates\":{}}");
        assertThat(ok).as("合法 JSON 解析为 Map").isNotNull();
        assertThat(ok.get("teamName")).isEqualTo("t");

        assertThat(SessionService.parseTeamContext(null)).as("null → null").isNull();
        assertThat(SessionService.parseTeamContext("   ")).as("空白 → null").isNull();
        assertThat(SessionService.parseTeamContext("[1,2,3]")).as("非对象 JSON → null").isNull();
        assertThat(SessionService.parseTeamContext("not-json{")).as("非法 JSON → null（不抛）").isNull();
        assertThat(SessionService.parseTeamContext("{}")).as("空对象 → null").isNull();
    }

    @Test
    @DisplayName("getTeamContext 派生 UUID → resolveRowKey 归一 sess-xxx 查 DB（两形态同键）")
    void getTeamContext_derivedUuid_resolvesToRowKey() {
        // WHY: 工具路径 ctx.sessionId() 传派生 UUID、HTTP 路径传 "sess-xxx"，两形态须归一同一行
        //   （SessionKeys.originalKey 反解，CRON-D5 F2 双形态同键）。变异点：不归一 → 派生 UUID
        //   selectOneById 查不到行 → teamContext 恒 null → 会话级化失效。
        SessionRecord rec = new SessionRecord();
        rec.setId("sess-00000abc");
        rec.setTeamContext("{\"teamName\":\"research-team\"}");
        when(sessionMapper.selectOneById("sess-00000abc")).thenReturn(rec);

        Map<String, Object> teamContext = service.getTeamContext("00000000-0000-0000-0000-00000abc0000");

        verify(sessionMapper).selectOneById("sess-00000abc");
        assertThat(teamContext).as("派生 UUID 查 row key 须命中").isNotNull();
        assertThat(teamContext.get("teamName")).isEqualTo("research-team");
    }

    // ═══════════════════════ [R3 持久升级] 会话级 todos（V43 列 todos）═══════════════════════

    @Test
    @DisplayName("[R3] getById 会话 todos 列 → SessionDto.todos 解析透出（status 小写）")
    void getById_toDtoExposesTodosParsed() {
        // WHY: [R3] 持久读 = 前端会话 todo 面板刷新/重开拉取数据源（SessionController.get →
        //   getById → toDto）。变异点：toDto 不回填 todos → 前端刷新拿不到持久化 todo。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        record.setTodos("{\"sess-1\":[{\"content\":\"A\",\"status\":\"in_progress\",\"activeForm\":\"Doing A\"}]}");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.todos()).as("toDto 必须回填 todos（R3 持久读）").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> bucket = (Map<String, Object>) ((List<?>) dto.todos().get("sess-1")).get(0);
        assertThat(bucket.get("content")).isEqualTo("A");
        assertThat(bucket.get("status")).as("status 必须小写（CC types.ts:4-6 值域）").isEqualTo("in_progress");
        assertThat(bucket.get("activeForm")).isEqualTo("Doing A");
    }

    @Test
    @DisplayName("[R3] getById 会话 todos 列 null → SessionDto.todos null（从未 TodoWrite）")
    void getById_toDtoNullTodosStaysNull() {
        // WHY: null = 该会话从未 TodoWrite → 前端据此隐藏面板（对齐 CC 新会话空态）。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.todos()).as("todos 列 null → DTO todos=null").isNull();
    }

    @Test
    @DisplayName("[R3] getById 会话 todos 列解析失败 → SessionDto.todos null（fail-soft）")
    void getById_toDtoInvalidTodosJsonStaysNull() {
        // WHY: 坏列不得把会话详情读炸——fail-soft 降级 null（对齐 parseTeamContext 容错）。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        record.setTodos("not-json{");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.todos()).as("todos 列非法 JSON → DTO todos=null（fail-soft 不抛）").isNull();
    }

    @Test
    @DisplayName("[R3] parseTodos 单测：合法 JSON / null / 空白 / 非对象 / 非法 JSON / 空对象")
    void parseTodos_edgeCases() {
        // WHY: parseTodos 是 toDto 的公共解析（fail-soft）——合法 JSON 解析为 Map；其余一律 null（不抛）。
        Map<String, Object> ok = SessionService.parseTodos("{\"sess-1\":[{\"content\":\"A\",\"status\":\"pending\"}]}");
        assertThat(ok).as("合法 JSON 解析为 Map").isNotNull();
        assertThat(((List<?>) ok.get("sess-1"))).hasSize(1);

        assertThat(SessionService.parseTodos(null)).as("null → null").isNull();
        assertThat(SessionService.parseTodos("   ")).as("空白 → null").isNull();
        assertThat(SessionService.parseTodos("[1,2,3]")).as("非对象 JSON → null").isNull();
        assertThat(SessionService.parseTodos("not-json{")).as("非法 JSON → null（不抛）").isNull();
        assertThat(SessionService.parseTodos("{}")).as("空对象 → null").isNull();
    }

    // ═══════════════════════ [V44] 会话级权限模式覆盖（V44 列 permission_mode）═══════════════════════

    @Test
    @DisplayName("update 带 permissionMode='dontAsk' → 落库 CC 串 + SessionDto.permissionMode 透出")
    void update_persistsPermissionMode() {
        // WHY: 前端 PATCH 会话权限模式 → sessions.permission_mode（V44 列）→ ChatService 读本列解析
        //   effectiveMode（per-call ?? 会话 override）。变异点：update 漏写 permissionMode → 会话级
        //   覆盖不生效 → 回落全局默认。
        SessionRecord existing = new SessionRecord();
        existing.setId("sess-1");
        existing.setTitle("旧标题");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);

        SessionUpdateRequest req = new SessionUpdateRequest("新标题", null, null, null, null, "dontAsk", null);
        SessionDto dto = service.update("sess-1", req);

        assertThat(existing.getPermissionMode())
                .as("update permissionMode=dontAsk → 会话记录 permission_mode=dontAsk（CC 串）")
                .isEqualTo("dontAsk");
        assertThat(dto.permissionMode()).as("update 返回 DTO permissionMode=dontAsk 回显").isEqualTo("dontAsk");
    }

    @Test
    @DisplayName("update 不带 permissionMode → 不改动（PATCH 语义，null 跳过）")
    void update_nullPermissionModeUnchanged() {
        // WHY: PATCH 全字段可选——未传 permissionMode 不得误改会话权限覆盖（对齐 bareMode :128 同款语义）。
        SessionRecord existing = new SessionRecord();
        existing.setId("sess-1");
        existing.setPermissionMode("plan");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);

        SessionUpdateRequest req = new SessionUpdateRequest("新标题", null, null, null, null, null, null);
        service.update("sess-1", req);

        assertThat(existing.getPermissionMode())
                .as("update 未带 permissionMode → 原 permission_mode=plan 保持")
                .isEqualTo("plan");
    }

    @Test
    @DisplayName("update 带 permissionMode='bubble' → ValidationException（BUBBLE 不可由 UI 设置）")
    void update_invalidPermissionMode_throws() {
        // WHY: BUBBLE 恒不可由 UI 设置（isSettable 拒绝）——写侧 fail-loud（V44 双防），不静默折叠 DEFAULT。
        SessionRecord existing = new SessionRecord();
        existing.setId("sess-1");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);

        SessionUpdateRequest req = new SessionUpdateRequest("新标题", null, null, null, null, "bubble", null);

        assertThatThrownBy(() -> service.update("sess-1", req))
            .as("BUBBLE 恒不可由 UI 设置——写侧 fail-loud（V44 双防）")
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("update 带 mainThreadAgent → 会话记录 main_thread_agent 落库 + DTO 回显（SP-03 会话指定主线程 agent）")
    void update_persistsMainThreadAgent() {
        // WHY: 前端 PATCH 会话指定主线程 agent（对齐 CC appState.agent / /init --agent，resumeAgent.ts:121-124）
        //   → sessions.main_thread_agent（V58 列）→ LlmAgentLoop.buildEffectivePromptOptions 读本列激活
        //   agent 分支。变异点：update 漏写 mainThreadAgent → 会话指定不生效 → agent 分支休眠。
        SessionRecord existing = new SessionRecord();
        existing.setId("sess-1");
        existing.setTitle("旧标题");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);

        SessionUpdateRequest req = new SessionUpdateRequest("新标题", null, null, null, null, null, "researcher");
        SessionDto dto = service.update("sess-1", req);

        assertThat(existing.getMainThreadAgent())
                .as("update mainThreadAgent=researcher → 会话记录 main_thread_agent=researcher（CC agentType 串）")
                .isEqualTo("researcher");
        assertThat(dto.mainThreadAgent()).as("update 返回 DTO mainThreadAgent=researcher 回显").isEqualTo("researcher");
    }

    @Test
    @DisplayName("update 不带 mainThreadAgent → 不改动（PATCH 语义，null 跳过）；getById 透出")
    void update_nullMainThreadAgentUnchanged_andDtoExposes() {
        // WHY: PATCH 全字段可选——未传 mainThreadAgent 不得误改会话指定（对齐 bareMode :128 同款语义）；
        //   getById 需透出该列供前端会话详情回显（对齐 permissionMode/teamContext 范式）。
        SessionRecord existing = new SessionRecord();
        existing.setId("sess-1");
        existing.setMainThreadAgent("planner");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);

        SessionUpdateRequest req = new SessionUpdateRequest("新标题", null, null, null, null, null, null);
        service.update("sess-1", req);

        assertThat(existing.getMainThreadAgent())
                .as("update 未带 mainThreadAgent → 原 main_thread_agent=planner 保持")
                .isEqualTo("planner");

        // getById 透出（V58 列 → DTO）
        when(sessionMapper.selectOneById("sess-1")).thenReturn(existing);
        SessionDto dto = service.getById("sess-1");
        assertThat(dto.mainThreadAgent()).as("getById 会话 main_thread_agent 透出").isEqualTo("planner");
    }

    @Test
    @DisplayName("getById 会话 permission_mode 列 → SessionDto.permissionMode 透出（前端会话详情回显）")
    void getById_toDtoExposesPermissionMode() {
        // WHY: 前端会话列表/详情需回显每会话权限覆盖——V44 列须经 toDto 透出（对齐 bareMode/teamContext 范式）。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        record.setPermissionMode("acceptEdits");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.permissionMode()).as("permission_mode=acceptEdits → DTO permissionMode=acceptEdits").isEqualTo("acceptEdits");
    }

    // ═══════════════════════ [V48] 会话累计花费/token 汇总（V50 列 total_cost_yuan + model_usage_json）═══════════════════════

    @Test
    @DisplayName("[V48] getById 会话 total_cost_yuan + model_usage_json 列 → SessionDto 透出（前端 F5 恢复源）")
    void getById_toDtoExposesUsageCost() {
        // WHY: [V48] 前端会话底部 token/金额汇总 —— GET /sessions/{id} 返回 totalCostYuan + totalTokens
        //   （前端 F5 恢复源，无需依赖 STOMP 事件重放；complete 事件只在会话进行中实时推）。
        //   变异点：toDto 不回填两字段 → 前端刷新后累计花费/用量归零 → footer 展示错乱。
        SessionRecord record = new SessionRecord();
        record.setId("sess-1");
        record.setTitle("t");
        record.setModelTag(ModelTag.DS.name());
        record.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        record.setMessageCount(0);
        record.setTotalCostYuan(0.0123);
        record.setModelUsageJson("{\"deepseek-v4-flash\":{\"inputTokens\":1000,\"outputTokens\":500,"
            + "\"cacheReadInputTokens\":2000,\"cacheCreationInputTokens\":0,\"webSearchRequests\":0,"
            + "\"costUSD\":0.01,\"contextWindow\":1048576,\"maxOutputTokens\":393216}}");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(record);

        SessionDto dto = service.getById("sess-1");

        assertThat(dto.totalCostYuan()).as("total_cost_yuan 列 → DTO totalCostYuan 透出").isEqualTo(0.0123);
        assertThat(dto.totalTokens())
            .as("model_usage_json inputTokens+outputTokens 求和 → DTO totalTokens")
            .isEqualTo(1500L);
    }

    @Test
    @DisplayName("[V48] list 全量会话 → totalCostYuan/totalTokens 逐条透出（有数据 → 值；无 → null/0）")
    void list_toDtoExposesUsageCostPerRow() {
        // WHY: 前端会话列表 footer 批量展示 token/金额——每会话各自累计（null = 从未有 usage 落库）。
        SessionRecord withData = new SessionRecord();
        withData.setId("sess-1");
        withData.setModelTag(ModelTag.DS.name());
        withData.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        withData.setMessageCount(0);
        withData.setTotalCostYuan(0.5);
        withData.setModelUsageJson("{\"deepseek-v4-flash\":{\"inputTokens\":200,\"outputTokens\":100}}");
        SessionRecord withoutData = new SessionRecord();
        withoutData.setId("sess-2");
        withoutData.setModelTag(ModelTag.DS.name());
        withoutData.setSessionGroup(com.nexusai.model.session.dto.SessionGroup.current.name());
        withoutData.setMessageCount(0);
        when(sessionMapper.selectAll()).thenReturn(java.util.List.of(withData, withoutData));

        java.util.List<SessionDto> dtos = service.list();

        assertThat(dtos.get(0).totalCostYuan()).as("有 usage 会话 → totalCostYuan 透出").isEqualTo(0.5);
        assertThat(dtos.get(0).totalTokens()).as("有 usage 会话 → totalTokens 求和透出").isEqualTo(300L);
        assertThat(dtos.get(1).totalCostYuan()).as("无 usage 会话 → totalCostYuan=null").isNull();
        assertThat(dtos.get(1).totalTokens()).as("无 usage 会话 → totalTokens=0").isZero();
    }

    @Test
    @DisplayName("[V48] sumTokensFromModelUsage 单测：多模型桶求和 / cache 桶不计 / null / 空白 / 非法 JSON / 缺键")
    void sumTokensFromModelUsage_edgeCases() {
        // WHY: sumTokensFromModelUsage 是 toDto/ProjectSessionBindingService 的公共汇总——前端契约
        //   明确 "model_usage_json 各模型 inputTokens+outputTokens 求和"。cache 桶（cacheRead/
        //   cacheCreation）不参与 totalTokens 展示（与 complete 事件同源口径一致）。坏列 fail-soft → 0。
        long multi = SessionService.sumTokensFromModelUsage(
            "{\"deepseek-v4-flash\":{\"inputTokens\":1000,\"outputTokens\":500},"
            + "\"claude-opus-5\":{\"inputTokens\":300,\"outputTokens\":200}}");
        assertThat(multi).as("多模型桶 inputTokens+outputTokens 求和").isEqualTo(2000L);

        long cacheOnly = SessionService.sumTokensFromModelUsage(
            "{\"deepseek-v4-flash\":{\"inputTokens\":0,\"outputTokens\":0,\"cacheReadInputTokens\":9999,"
            + "\"cacheCreationInputTokens\":999}}");
        assertThat(cacheOnly).as("cache 桶不计入 totalTokens（仅 inputTokens+outputTokens）").isZero();

        assertThat(SessionService.sumTokensFromModelUsage(null)).as("null → 0").isZero();
        assertThat(SessionService.sumTokensFromModelUsage("   ")).as("空白 → 0").isZero();
        assertThat(SessionService.sumTokensFromModelUsage("not-json{")).as("非法 JSON → 0（fail-soft 不抛）").isZero();
        assertThat(SessionService.sumTokensFromModelUsage("{}")).as("空对象 → 0").isZero();
        assertThat(SessionService.sumTokensFromModelUsage("[]")).as("非对象 JSON → 0（fail-soft 不抛）").isZero();
    }
}
