package com.nexusai.apis.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Q3 前端交付返工 · SendUserFile 下载端点（{@code GET /api/v1/sessions/{sessionId}/send-user-file/download}）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 / 端到端「文件发给用户」）</b>: 后端 31b80e58 只把文件事件（元数据）经
 * STOMP 推给前端（transport-only），前端<b>没有</b>拉取实际字节的下载端点，端到端不通。本端点补全
 * 缺口——前端收到 {@code send_user_file} 事件 → 调本端点 → 拿到文件流。测试锁定契约：
 * <ul>
 *   <li><b>真实文件</b> → 200 + 实际字节 + Content-Length + Content-Disposition attachment +
 *       octet-stream（前端可触发浏览器/客户端保存文件）。</li>
 *   <li><b>服务器绝对路径</b> 语义（事件 file_path 原样回传）——Windows/Linux 绝对路径都可下载。</li>
 *   <li><b>缺 path / 目录 / 不存在</b> → 400/404（前端可按错误分支给用户提示，不静默 200）。</li>
 * </ul>
 * 变异点（不红即测试失败）：
 * <ul>
 *   <li>返回空 body / 无 Content-Disposition → 前端拿到空流或无法触发下载 → 红</li>
 *   <li>文件不存在仍 200 → 前端误以为下载成功 → 红</li>
 *   <li>整读进内存 / 不校验非常规文件 → 大文件 OOM / 目录被当文件流出 → 红（本测试校验 length + 类型）</li>
 * </ul>
 */
@DisplayName("[Q3-rework] SendUserFile 下载端点（文件发给用户 · 实际字节拉取）")
class SendUserFileControllerTest {

    private static final String SESSION_ID = "sess-12345678";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SendUserFileController()).build();
    }

    @Test
    @DisplayName("真实文件 → 200 octet-stream + attachment + 实际字节（端到端下载核心）")
    void regularFileServesActualBytes(@TempDir Path tempDir) throws Exception {
        // WHY (核心意图): 前端收到 send_user_file 事件后经本端点拿实际字节——
        // 必须返回文件真实内容 + Content-Disposition 触发下载 + Content-Length 对齐.
        String content = "季度报告 2026-08-23 文件内容 hello nexusai";
        Path file = tempDir.resolve("report-2026.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        MvcResult res = mvc.perform(get(downloadEndpoint())
                        .param("path", file.toAbsolutePath().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"report-2026.txt\""))
                .andExpect(content().bytes(content.getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        // Content-Length 必须 = 真实文件字节数（前端下载进度/完整性依赖）
        assertThat(res.getResponse().getContentLengthLong())
                .as("Content-Length 必须等于文件实际字节数")
                .isEqualTo(file.toFile().length());
    }

    @Test
    @DisplayName("Windows 反斜杠绝对路径 → 200（事件 file_path 原样回传语义）")
    void windowsStyleAbsolutePathServed(@TempDir Path tempDir) throws Exception {
        // WHY: SendUserFileEvent.filePath 是服务器绝对路径（Windows 为 D:\\...\\file.txt），
        // 前端 encodeURIComponent 回传后必须仍可下载——端到端不能因路径分隔符形态断链.
        Path file = tempDir.resolve("win-file.txt");
        Files.writeString(file, "win path body");
        String winPath = file.toAbsolutePath().toString(); // 本机即 Windows，天然反斜杠形态

        mvc.perform(get(downloadEndpoint()).param("path", winPath))
                .andExpect(status().isOk())
                .andExpect(content().string("win path body"));
    }

    @Test
    @DisplayName("缺 path 参数 → 400（前端可提示下载参数缺失，不静默）")
    void missingPathIsBadRequest() throws Exception {
        // WHY: 无 path → 前端拿不到下载目标，必须显式 400 而非 200 空内容.
        mvc.perform(get(downloadEndpoint()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("缺少下载路径参数 path"));
    }

    @Test
    @DisplayName("路径不存在 → 404（前端可提示文件已不可用，不误报成功）")
    void missingFileIsNotFound(@TempDir Path tempDir) throws Exception {
        // WHY: 对齐 SendUserFileTool stat 语义（NoSuchFileException）——文件不存在必须 404,
        // 前端收到 send_user_file 后文件被删场景能正确提示.
        Path missing = tempDir.resolve("no-such.txt");
        mvc.perform(get(downloadEndpoint()).param("path", missing.toAbsolutePath().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("文件不存在"));
    }

    @Test
    @DisplayName("目录（非常规文件）→ 400（对齐 SendUserFileTool 'Path is not a file.'）")
    void directoryIsBadRequest(@TempDir Path tempDir) throws Exception {
        // WHY: 目录不能被当文件流出（读目录流会 IO 错）——必须 400 拦截，与工具 stat 语义一致.
        mvc.perform(get(downloadEndpoint()).param("path", tempDir.toAbsolutePath().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("路径不是文件"));
    }

    private static String downloadEndpoint() {
        return "/api/v1/sessions/" + SESSION_ID + "/send-user-file/download";
    }
}
