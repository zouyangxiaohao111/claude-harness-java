package com.nexusai.application.session;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目内文档采集（{@code syncProjectDocForPath} · V279）· 纯 JUnit，无 Spring 上下文。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：用户 2026-11-10 指出「这轮产出的两个 md 在产物列表里
 * 根本看不到」——实测产物表里 0 行。根因是采集只认交付物目录
 * （{@code file.startsWith(deliverablesDir)}），写在 {@code docs/} 下的文档结构上进不来。
 * 本测试锁住三件事，任何一条破了用户就会重新看不到自己的文档：
 *
 * <ol>
 *   <li><b>白名单才收</b>：{@code .md} 收，{@code .java} / 无扩展名文件不收
 *       —— 后者若不挡，{@code exclude} / {@code .gitignore} 会混进产物面板；</li>
 *   <li><b>必须归档复制</b>：{@code local_path} 落的是归档目录内的**副本**，不是项目原路径。
 *       产物端点有路径边界（不在归档目录内 → 400），存原路径 = 点不开；</li>
 *   <li><b>归档失败就不入库</b>：宁可少一条，也不要在列表上放一个点不开的死链。</li>
 * </ol>
 */
class SessionArtifactProjectDocTest {

    private static final String SESSION = "sess-proj-doc";
    /** 测试专用 appName，隔离 configHome（避免写用户真实 ~/.nexusai）。 */
    private static final String APP_NAME = "nexusai-projdoctest";

    private Path configHome;

    @TempDir
    Path tempDir;

    private SessionArtifactMapper mapper;
    private SessionArtifactService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SessionArtifactMapper.class);
        service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);
        // ⛔ 必须隔离 configHome：archiveFile 会往 {configHome}/artifacts/{sid} 复制文件。
        //   不隔离就会写进用户真实的 ~/.nexusai（本仓规矩：测试不许碰真库/真目录）。
        NexusaiPaths.setAppNameOverride(APP_NAME);
        configHome = Path.of(NexusaiPaths.getAppConfigHomeDir()).toAbsolutePath().normalize();
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setAppNameOverride(null);
        // 清掉本次测试往 configHome 里写的归档副本
        try {
            Path artifactsRoot = configHome.resolve("artifacts");
            if (Files.isDirectory(artifactsRoot)) {
                try (var walk = Files.walk(artifactsRoot)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignore) {
                            // 清理失败不影响断言
                        }
                    });
                }
            }
        } catch (Exception ignore) {
            // 同上
        }
    }

    @Test
    @DisplayName("项目内 md 文档 → 入库，且 local_path 是归档副本而不是原路径")
    void projectMarkdownIsCollectedAndArchived() throws Exception {
        Path doc = tempDir.resolve("docs").resolve("plan.md");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, "# 计划\n内容", StandardCharsets.UTF_8);

        boolean saved = service.syncProjectDocForPath(SESSION, doc.toString(), "msg-1");

        assertThat(saved).as("md 属于白名单，必须被收集").isTrue();
        ArgumentCaptor<SessionArtifactRecord> captor = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper).insert(captor.capture());
        SessionArtifactRecord row = captor.getValue();

        assertThat(row.getKind()).isEqualTo("deliverable");
        assertThat(row.getTitle()).isEqualTo("plan.md");
        assertThat(row.getOrigin())
                .as("origin 必须标 project-write，前端据此画「项目」角标")
                .isEqualTo(SessionArtifactService.ORIGIN_PROJECT_WRITE);
        assertThat(row.getSourceMessageId()).as("归属 = 本轮提问").isEqualTo("msg-1");
        assertThat(Path.of(row.getLocalPath()))
                .as("落库的必须是归档目录内的副本 —— 存项目原路径会导致产物端点 400")
                .isNotEqualTo(doc.toAbsolutePath().normalize());
        assertThat(Files.exists(Path.of(row.getLocalPath()))).as("副本必须真实存在").isTrue();
        assertThat(Files.readString(Path.of(row.getLocalPath()), StandardCharsets.UTF_8))
                .as("副本内容必须与原文件一致")
                .isEqualTo("# 计划\n内容");
    }

    @Test
    @DisplayName("源码文件（.java）→ 不收（黑名单优先于白名单顺序）")
    void sourceCodeIsRejected() throws Exception {
        Path src = tempDir.resolve("Foo.java");
        Files.writeString(src, "class Foo {}", StandardCharsets.UTF_8);

        assertThat(service.syncProjectDocForPath(SESSION, src.toString(), "msg-1")).isFalse();
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("无扩展名文件（exclude / gitignore 这类）→ 不收（白名单是正向声明）")
    void extensionlessFileIsRejected() throws Exception {
        Path noExt = tempDir.resolve("exclude");
        Files.writeString(noExt, "some rules", StandardCharsets.UTF_8);

        assertThat(service.syncProjectDocForPath(SESSION, noExt.toString(), "msg-1")).isFalse();
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("编译产物目录（target / node_modules）里的 md → 不收")
    void excludedPathSegmentIsRejected() throws Exception {
        Path inTarget = tempDir.resolve("target").resolve("report.md");
        Files.createDirectories(inTarget.getParent());
        Files.writeString(inTarget, "# 编译期报告", StandardCharsets.UTF_8);

        assertThat(service.syncProjectDocForPath(SESSION, inTarget.toString(), "msg-1"))
                .as("target 是编译产物目录，收进来会淹掉产物面板")
                .isFalse();
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("文件不存在 → 不收（防死链）")
    void missingFileIsRejected() {
        Path missing = tempDir.resolve("not-there.md");
        assertThat(service.syncProjectDocForPath(SESSION, missing.toString(), "msg-1")).isFalse();
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("幂等键不含轮次：同一文件第二轮进来必须命中已有行（走 update，不插新行）")
    void sameFileSecondTurnUpdatesInsteadOfInsert() throws Exception {
        Path doc = tempDir.resolve("notes.md");
        Files.writeString(doc, "v1", StandardCharsets.UTF_8);

        // 第一轮：无既有行 → insert
        when(mapper.selectOneByQuery(any())).thenReturn(null);
        assertThat(service.syncProjectDocForPath(SESSION, doc.toString(), "msg-turn-1")).isTrue();
        verify(mapper).insert(any());
        // ⚠️ 必须清掉第一轮的调用记录：下面 verify(never()) 是**累积计数**，
        //    不清就会把第一轮合法的 insert 也算进来 ⇒ 假的「第二轮插了新行」。
        org.mockito.Mockito.clearInvocations(mapper);

        // 第二轮：查到上一轮那行（内容变了）→ 必须 update，且归属仍停在 msg-turn-1
        SessionArtifactRecord existing = new SessionArtifactRecord();
        existing.setId("art-x");
        existing.setSessionId(SESSION);
        existing.setKind("deliverable");
        existing.setTitle("notes.md");
        existing.setLocalPath(doc.toString());
        existing.setSizeBytes(2L);
        existing.setSourceMessageId("msg-turn-1");
        existing.setOrigin(SessionArtifactService.ORIGIN_PROJECT_WRITE);
        existing.setUpdatedAt("2026-11-10T10:00:00+08:00");
        when(mapper.selectOneByQuery(any())).thenReturn(existing);

        Files.writeString(doc, "v2-changed", StandardCharsets.UTF_8);
        service.syncProjectDocForPath(SESSION, doc.toString(), "msg-turn-2");

        ArgumentCaptor<SessionArtifactRecord> captor = ArgumentCaptor.forClass(SessionArtifactRecord.class);
        verify(mapper).update(captor.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(captor.getValue().getSourceMessageId())
                .as("归属是「首次产出那一轮」，⛔ 不能被后来轮次覆盖（否则产物会在历史轮次之间搬家）")
                .isEqualTo("msg-turn-1");
        verify(mapper, never()).insert(any());
    }
}
