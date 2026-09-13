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
 * <p>本类锁死两件事：
 * <ol>
 *   <li>缺 / 空白 {@code ?sessionId=} ⇒ <b>400</b>（(a) 类 fail loud），四个端点一致；</li>
 *   <li>[批 3c] 裸 MDC 会话槽已整体删除 ⇒ 原「残留会话 id 不被读取」的反向实验失去对照装置
 *       （装置本身已不存在），断言仍保留，见 {@link #staleMdc_isNotRead_is400()} 内注释。</li>
 * </ol>
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

    @Test
    @DisplayName("[批 3a 反向实验 · 批 3c 装置已删] 无 query 仍 400（原「残留别的会话 id」对照无法再构造）")
    void staleMdc_isNotRead_is400() throws Exception {
        // [批 3c] 语义消失：裸 MDC 会话槽已整体删除 ⇒ 无法再制造「残留别的会话 id」这一对照装置
        //   （缺口即缺口，请求缺失 sessionId 不再有可回落的第三源）。断言文本原样保留，
        //   本用例现与 list_missingSessionId_is400 等价，仅作反向实验的历史留痕。
        mockMvc.perform(get("/api/v1/branches")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/branches/some-slug/keep")).andExpect(status().isBadRequest());
    }
}
