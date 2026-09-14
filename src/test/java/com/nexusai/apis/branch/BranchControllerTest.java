package com.nexusai.apis.branch;

import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link BranchController} 会话态显式化测试（批 3a）· plain JUnit + standaloneSetup。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：四个分支端点的 gitRoot 由**会话 boundProject** 解析
 * （{@code currentGitRoot} → {@code CwdResolution.getCwd(sessionId)} → {@code findCanonicalGitRoot}）。
 * 旧实现经**裸 MDC 会话槽**（批 3c 已删除，不再有此类名）取会话 —— 该槽的 sessionId 有第三态：
 * 不是 null，而是**上一个请求残留的、别的会话的 id**（看起来完全合法）。后果：用户 A 的分支面板
 * 会对**用户 B 的仓库**建/删 worktree，且日志前缀同样取自该槽 ⇒ 从日志上也看不出错。
 *
 * <p>本类锁死一件事：缺 / 空白 {@code ?sessionId=} ⇒ <b>400</b>（(a) 类 fail loud），四个端点一致。
 *
 * <p><b>[欠账清理批 · 去重记录]</b> 原 {@code staleMdc_isNotRead_is400} 已删：它的两条断言
 * （GET /branches、POST /{slug}/keep 无 sessionId ⇒ 400）是上面 {@code list_missingSessionId_is400}
 * 与 {@code keep_missingSessionId_is400} 的<b>严格子集</b>。该用例守护的「残留（别的会话）sessionId
 * 不被读取」在批 3c 删除裸 MDC 会话槽后已<b>结构性不可能</b>（无载体可携带残留值），
 * 断言本身退化为「无 sessionId ⇒ 400」的第二种编码（零鉴别力 @DisplayName）。
 * 保留兄弟用例即可覆盖同一行为；「不得重新引入 ambient 会话源」由载体不存在结构性保证。
 * 不断言 200 路径：{@code listWorktrees} 会真跑 {@code git worktree list}，断言结果强依赖执行环境
 * （工作区本身就在 git 仓库内），故只锁 http 契约层。
 */
class BranchControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BranchController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    @DisplayName("[批 3a] GET /api/v1/branches 缺 sessionId → 400（不再回落 MDC）")
    void list_missingSessionId_is400() throws Exception {
        mockMvc.perform(get("/api/v1/branches")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/branches").param("sessionId", "")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[批 3a] POST /api/v1/branches 缺 sessionId → 400（先于 slug 校验，不得建 worktree）")
    void create_missingSessionId_is400() throws Exception {
        mockMvc.perform(post("/api/v1/branches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[批 3a] DELETE /api/v1/branches/{slug} 缺 sessionId → 400（不得删 worktree）")
    void remove_missingSessionId_is400() throws Exception {
        mockMvc.perform(delete("/api/v1/branches/some-slug")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/branches/some-slug").param("sessionId", " "))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[批 3a] POST /api/v1/branches/{slug}/keep 缺 sessionId → 400")
    void keep_missingSessionId_is400() throws Exception {
        mockMvc.perform(post("/api/v1/branches/some-slug/keep")).andExpect(status().isBadRequest());
    }
}
