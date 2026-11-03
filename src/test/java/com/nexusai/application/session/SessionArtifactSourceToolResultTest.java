package com.nexusai.application.session;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 从网络工具结果收集来源单测 · 纯 JUnit，无 Spring 上下文。
 *
 * <p><b>WHY（2026-11-02 用户裁定）</b>：用户原话「来源 我们从 websearch 和 webfetch 能拿到啊 不需要了」。
 * 这两个工具的结果里天然带 URL ⇒ 直接读结果就是确定性来源，不该再让模型读一遍会话去猜。
 * 本测试钉住这条通道的两个真实形态：
 * <ul>
 *   <li>WebSearch：{@code {query, results:[{tool_use_id, content:[{title,url}]}, "注释"]}}；</li>
 *   <li>WebFetch：{@code {bytes, code, codeText, result, durationMs, url}}。</li>
 * </ul>
 */
class SessionArtifactSourceToolResultTest {

    private static final String SESSION = "sess-sources";

    @TempDir
    Path tempDir;

    private SessionArtifactMapper mapper;
    private SessionArtifactService service;

    @BeforeEach
    void setUp() {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
        mapper = mock(SessionArtifactMapper.class);
        service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);
        when(mapper.selectOneByQuery(any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    /** 造一条带单个工具结果的 assistant 消息。 */
    private static ChatMessageDto msgWithTool(String toolName, String result) {
        ToolCallDto call = new ToolCallDto("call-1", toolName, "{}", result, false);
        // 20 参便捷构造器（其余字段走默认 null/false）
        return new ChatMessageDto(
                "m1", SESSION, Role.assistant, "nexus", "搜索完成", null,
                List.of(call), null, null, null, null, null,
                null, null, null, List.of(), List.of(), null, false, false);
    }

    @Test
    @DisplayName("WebSearch 结果 → 抽出全部 {title,url} 来源（真实形状：results[].content[]）")
    void extractsFromWebSearchResult() {
        String result = """
                {"query":"农污 查询","results":[
                  {"tool_use_id":"s1","content":[
                    {"title":"生态环境部 - 农污治理","url":"https://www.mee.gov.cn/a"},
                    {"title":"农污排放标准","url":"https://www.mee.gov.cn/b"}
                  ]},
                  "以上是搜索结果"
                ],"durationSeconds":1.2}
                """;

        int n = service.syncSourcesFromToolResults(SESSION, List.of(msgWithTool("WebSearch", result)));

        assertThat(n).as("两条来源都应入库").isEqualTo(2);
        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, times(2)).insert(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(r -> {
            assertThat(r.getKind()).isEqualTo("source");
            assertThat(r.getUrl()).startsWith("https://");
            // title 优先用工具报的页面标题（不是原始 url）
            assertThat(r.getTitle()).isNotEqualTo(r.getUrl());
        });
    }

    @Test
    @DisplayName("WebFetch 结果 → 抽出 url（真实形状：顶层 url 字段）")
    void extractsFromWebFetchResult() {
        String result = """
                {"bytes":1024,"code":200,"codeText":"OK","result":"页面正文…","durationMs":30,
                 "url":"https://example.com/doc"}
                """;

        int n = service.syncSourcesFromToolResults(SESSION, List.of(msgWithTool("WebFetch", result)));

        assertThat(n).isEqualTo(1);
        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, times(1)).insert(cap.capture());
        assertThat(cap.getValue().getUrl()).isEqualTo("https://example.com/doc");
    }

    @Test
    @DisplayName("非网络工具的结果 ⇒ 不产生来源")
    void ignoresNonSourceTools() {
        String result = "{\"url\":\"https://evil.example.com\"}";
        int n = service.syncSourcesFromToolResults(SESSION, List.of(msgWithTool("Bash", result)));
        assertThat(n).isZero();
        verify(mapper, never()).insert(any(SessionArtifactRecord.class));
    }

    @Test
    @DisplayName("只收 http(s)：内部跳转 / 相对路径 / file:// 一律跳过")
    void onlyHttpUrls() {
        String result = """
                {"results":[{"content":[
                  {"title":"内部","url":"/internal/path"},
                  {"title":"本地","url":"file:///etc/passwd"},
                  {"title":"真网页","url":"https://ok.example.com"}
                ]}]}
                """;

        int n = service.syncSourcesFromToolResults(SESSION, List.of(msgWithTool("WebSearch", result)));

        assertThat(n).as("只有 http(s) 那条入库").isEqualTo(1);
        ArgumentCaptor<SessionArtifactRecord> cap = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper, times(1)).insert(cap.capture());
        assertThat(cap.getValue().getUrl()).isEqualTo("https://ok.example.com");
    }

    @Test
    @DisplayName("工具结果不是 JSON（报错文本）⇒ 跳过且不抛")
    void nonJsonResultIsSkipped() {
        int n = service.syncSourcesFromToolResults(SESSION, List.of(msgWithTool("WebFetch", "fetch failed: timeout")));
        assertThat(n).isZero();
    }

    @Test
    @DisplayName("无会话 / 空消息 ⇒ 返回 0")
    void blankInputsAreNoop() {
        assertThat(service.syncSourcesFromToolResults(null, List.of(msgWithTool("WebSearch", "{}")))).isZero();
        assertThat(service.syncSourcesFromToolResults(SESSION, List.of())).isZero();
    }
}
