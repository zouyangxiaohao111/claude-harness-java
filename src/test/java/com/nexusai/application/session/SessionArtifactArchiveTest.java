package com.nexusai.application.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物<b>归档</b>（复制到自有目录）单测 · 纯 JUnit，无 Spring 上下文。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：用户 2026-10-29 指出的真实缺陷是「原文件被移走/删掉后
 * 产物就丢了」。修法的价值完全取决于下面三件事，所以逐条钉死：
 * <ul>
 *   <li><b>副本真的存在且可读</b>：只把路径字符串换成副本路径而没复制，等于把死链藏得更深；</li>
 *   <li><b>同一原路径稳定命中同一副本</b>：每轮抽取都触发，若文件名带随机量 ⇒ 用户磁盘被同一份
 *       交付物反复复印（这是归档方案最容易翻车的地方）；</li>
 *   <li><b>超限/清理绝不越界</b>：50MB 以上不复制（抽取线程不该搬大文件）；清理只允许动
 *       {@code {configHome}/artifacts/{sessionId}} 内的副本，⛔ 永不删用户原文件或别的会话。</li>
 * </ul>
 *
 * <p>⛔ 不启动 Spring（@SpringBootTest 会迁移用户真库 —— 仓库铁律）。
 * 配置自有根用 {@link NexusaiPaths#setConfigHomeDirOverride(String)} 重定向到 {@code @TempDir}，
 * 并在 {@link #restoreConfigHome()} 复位，防止污染真实 {@code ~/.{appName}}。
 */
class SessionArtifactArchiveTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void redirectConfigHome() {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());
    }

    @AfterEach
    void restoreConfigHome() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    @Test
    @DisplayName("归档：副本落在 {configHome}/artifacts/{sessionId}/，名含 hash 前缀，内容与修改时间一致")
    void archivesFileIntoSessionDir() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("work")).resolve("季度报告.html");
        String content = "<html><body>最终交付物</body></html>";
        Files.writeString(src, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_700_000_000_000L));

        Path archived = SessionArtifactService.archiveFile(
                src, "sess-abc123", SessionArtifactService.ARCHIVE_MAX_MB);

        assertThat(archived).isNotNull();
        assertThat(archived.isAbsolute()).isTrue();
        assertThat(Files.isRegularFile(archived)).isTrue();
        // 目录口径：{configHome}/artifacts/{sessionId}
        assertThat(archived.getParent())
                .isEqualTo(SessionArtifactService.sessionArtifactsDir("sess-abc123"));
        assertThat(SessionArtifactService.sessionArtifactsDir("sess-abc123").getParent())
                .isEqualTo(SessionArtifactService.artifactsRoot());
        assertThat(SessionArtifactService.artifactsRoot().getParent())
                .isEqualTo(Path.of(NexusaiPaths.getAppConfigHomeDir()));
        // 文件名口径：{sha256(原绝对路径) 前12位}-{原 basename}
        assertThat(archived.getFileName().toString()).matches("[0-9a-f]{12}-季度报告\\.html");
        // 内容一致（真的复制了，而不是只换了字符串）
        assertThat(Files.readString(archived, StandardCharsets.UTF_8)).isEqualTo(content);
        // 保留最后修改时间
        assertThat(Files.getLastModifiedTime(archived).toMillis())
                .isEqualTo(Files.getLastModifiedTime(src).toMillis());
    }

    @Test
    @DisplayName("归档幂等：同一原路径重复归档命中同一副本（覆盖而非堆垃圾）")
    void archiveIsIdempotentForSameSource() throws IOException {
        Path src = Files.writeString(tempDir.resolve("notes.md"), "v1");
        Path first = SessionArtifactService.archiveFile(
                src, "sess-idem", SessionArtifactService.ARCHIVE_MAX_MB);
        Files.writeString(src, "v2-changed");
        Path second = SessionArtifactService.archiveFile(
                src, "sess-idem", SessionArtifactService.ARCHIVE_MAX_MB);

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
        try (Stream<Path> files = Files.list(SessionArtifactService.sessionArtifactsDir("sess-idem"))) {
            assertThat(files.count()).isEqualTo(1L);
        }
        assertThat(Files.readString(second, StandardCharsets.UTF_8)).isEqualTo("v2-changed");
    }

    @Test
    @DisplayName("副本已是最新（大小+修改时间一致）时不重复复制（每轮抽取不重抄几十 MB）")
    void skipsCopyWhenCopyIsUpToDate() throws IOException {
        Path src = Files.writeString(tempDir.resolve("same.txt"), "original");
        Path first = SessionArtifactService.archiveFile(
                src, "sess-fresh", SessionArtifactService.ARCHIVE_MAX_MB);
        assertThat(first).isNotNull();
        // 用「同长度的篡改内容」探针：若实现真的重抄，内容会被原文件覆盖回 original
        Files.writeString(first, "TAINTED!");
        Files.setLastModifiedTime(first, Files.getLastModifiedTime(src));

        Path second = SessionArtifactService.archiveFile(
                src, "sess-fresh", SessionArtifactService.ARCHIVE_MAX_MB);

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(second, StandardCharsets.UTF_8)).isEqualTo("TAINTED!");
    }

    @Test
    @DisplayName("超限（>50MB）不复制：返回 null、归档目录不存在、源文件原样保留")
    void skipsOversizeFile() throws IOException {
        Path src = tempDir.resolve("huge.bin");
        long size = 50L * 1024 * 1024 + 1;
        // 稀疏文件：只改长度，不真写 50MB
        try (RandomAccessFile raf = new RandomAccessFile(src.toFile(), "rw")) {
            raf.setLength(size);
        }

        Path archived = SessionArtifactService.archiveFile(
                src, "sess-big", SessionArtifactService.ARCHIVE_MAX_MB);

        assertThat(archived).isNull();
        assertThat(Files.size(src)).isEqualTo(size);
        assertThat(SessionArtifactService.sessionArtifactsDir("sess-big")).doesNotExist();
    }

    @Test
    @DisplayName("目录不复制：传入目录返回 null，且不建归档目录")
    void skipsDirectory() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("a-directory"));

        assertThat(SessionArtifactService.archiveFile(
                dir, "sess-dir", SessionArtifactService.ARCHIVE_MAX_MB)).isNull();
        assertThat(SessionArtifactService.sessionArtifactsDir("sess-dir")).doesNotExist();
    }

    @Test
    @DisplayName("purgeSessionFiles 只删本会话归档目录：别的会话副本与归档外文件都不动")
    void purgeOnlyTouchesOwnSessionArtifacts() throws IOException {
        Path mine = Files.createDirectories(SessionArtifactService.sessionArtifactsDir("sess-mine"));
        Files.writeString(mine.resolve("0123456789ab-报告.html"), "mine");
        Path other = Files.createDirectories(SessionArtifactService.sessionArtifactsDir("sess-other"));
        Files.writeString(other.resolve("fedcba987654-别处.html"), "other");
        Path outside = Files.createDirectories(tempDir.resolve("user-files"));
        Files.writeString(outside.resolve("用户原文件.html"), "keep");

        new SessionArtifactService().purgeSessionFiles("sess-mine");

        assertThat(SessionArtifactService.sessionArtifactsDir("sess-mine")).doesNotExist();
        assertThat(other.resolve("fedcba987654-别处.html")).exists();
        assertThat(outside.resolve("用户原文件.html")).exists();
    }

    @Test
    @DisplayName("会话 id 含路径穿越（..）也不能删到归档根之外")
    void purgeSanitizesTraversalSessionId() throws IOException {
        Path shared = Files.createDirectories(SessionArtifactService.artifactsRoot()).resolve("shared.txt");
        Files.writeString(shared, "shared");

        new SessionArtifactService().purgeSessionFiles("..");

        assertThat(shared).exists();
        assertThat(tempDir.resolve("config-home")).exists();
    }

    @Test
    @DisplayName("deleteRows：按会话删行（条件真的是 session_id）并返回删除条数（V205 无 FK 级联）")
    void deleteRowsDelegatesToMapper() {
        SessionArtifactMapper mapper = mock(SessionArtifactMapper.class);
        when(mapper.deleteByQuery(any())).thenReturn(3);
        SessionArtifactService service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);

        assertThat(service.deleteRows("sess-rows")).isEqualTo(3);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).deleteByQuery(captor.capture());
        assertThat(captor.getValue().toSQL())
                .as("条件必须是 session_id（删错列 = 清掉别的会话或整表）")
                .contains("session_id = 'sess-rows'");
    }

    @Test
    @DisplayName("deleteRows：DB 异常不外抛、空 sessionId 不查库（会话删除主流程不能被拖垮）")
    void deleteRowsSwallowsFailureAndSkipsBlank() {
        SessionArtifactMapper mapper = mock(SessionArtifactMapper.class);
        when(mapper.deleteByQuery(any())).thenThrow(new RuntimeException("db down"));
        SessionArtifactService service = new SessionArtifactService();
        ReflectionTestUtils.setField(service, "artifactMapper", mapper);

        assertThat(service.deleteRows("sess-boom")).isZero();
        assertThat(service.deleteRows("  ")).isZero();
        assertThat(service.deleteRows(null)).isZero();
    }
}
