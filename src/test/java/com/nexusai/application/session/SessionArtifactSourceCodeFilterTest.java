package com.nexusai.application.session;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
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
 * 产物<b>源码过滤</b>单测 · 纯 JUnit，无 Spring 上下文（⛔ @SpringBootTest 会迁移用户真库）。
 *
 * <p><b>WHY（规则九：测试验证意图）</b>：用户实测「代码之类的不能当做产物」——
 * 抽取提示词里已经写了「⛔ 排除源码改动」，但模型仍会偶发把 {@code Foo.java} / {@code bar.tsx}
 * 当成交付物报上来。这是**概率问题**（模型不保证 100% 听话），不是能力问题 ⇒ 必须在代码层
 * 再拦一道确定性的扩展名黑名单。
 *
 * <p>规则五的边界正落在这里：<b>「什么算交付物」交模型判断（提示词），
 * 「这个扩展名绝对不是交付物」交代码判断（黑名单）</b>。
 */
class SessionArtifactSourceCodeFilterTest {

    private static final String SESSION = "sess-src-filter";

    @TempDir
    Path tempDir;

    private SessionArtifactMapper mapper;
    private SessionArtifactService service;

    @BeforeEach
    void setUp() {
        // 自有根指向 @TempDir ⇒ 绝不碰用户真 ~/.nexusai
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

    /** 造一个真实存在的文件（抽取链路会校验存在性）。 */
    private Path file(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    /**
     * 调私有 {@code saveDeliverable}。
     *
     * <p>⚠️ 用 {@code getDeclaredMethod} + {@code invoke}（显式签名），**不用**
     * {@code ReflectionTestUtils.invokeMethod} —— 后者按「参数个数 + 可赋值性」挑重载，
     * 在本类这种「多个 String 形参」的场景会挑错方法（实测：源码文件仍然入库 ⇒ 断言失败）。
     */
    private void callSaveDeliverable(Path path) {
        var item = new SessionArtifactParser.ParsedArtifact(path.getFileName().toString(), path.toString(), null, null);
        try {
            var m = SessionArtifactService.class.getDeclaredMethod(
                    "saveDeliverable", String.class, SessionArtifactParser.ParsedArtifact.class, String.class, String.class);
            m.setAccessible(true);
            m.invoke(service, SESSION, item, tempDir.toString(), "2026-11-02T00:00:00Z");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("saveDeliverable 签名变了（私有方法改名/改参）", e);
        }
    }

    @Test
    @DisplayName("源码文件（.java/.ts/.tsx/.py…）⇒ 不入库（模型偶尔会误报，靠这层确定性拦住）")
    void sourceCodeFilesAreRejected() throws IOException {
        for (String name : List.of(
                "Foo.java", "bar.ts", "baz.tsx", "qux.py", "main.go", "style.css",
                "run.sh", "app.log", "Main.kt", "mod.rs", "a.c", "b.hpp", "s.py")) {
            Path p = file(name, "x");
            callSaveDeliverable(p);
        }
        // 一条都没进库
        verify(mapper, never()).insert(any(SessionArtifactRecord.class));
    }

    @Test
    @DisplayName("真正的交付物（.html/.pdf/.xlsx/.docx/.pptx/.md/图片）⇒ 正常入库")
    void deliverableFilesAreAccepted() throws IOException {
        for (String name : List.of(
                "报告.html", "方案.pdf", "结果.xlsx", "说明.docx", "汇报.pptx", "笔记.md", "截图.png")) {
            Path p = file(name, "x");
            callSaveDeliverable(p);
        }
        verify(mapper, org.mockito.Mockito.times(7)).insert(any(SessionArtifactRecord.class));
    }

    @Test
    @DisplayName("无扩展名的文件 ⇒ 不被源码黑名单误伤（仍走后续逻辑判定）")
    void extensionlessFileIsNotBlockedBySourceFilter() throws IOException {
        Path p = file("README", "hello");
        callSaveDeliverable(p);
        verify(mapper, org.mockito.Mockito.times(1)).insert(any(SessionArtifactRecord.class));
    }

    /**
     * ⭐ 边界守卫：黑名单**不能**太宽 —— 配置文件与接口契约本身常常就是交付物
     * （「帮我导出一份接口契约 json」「把配置模板给我」）。
     * ⛔ 这些扩展名一律不进黑名单：json / yaml / yml / xml / toml / ini / conf / properties / sql / csv。
     */
    @Test
    @DisplayName("配置 / 契约 / 数据文件 ⇒ 不被误伤（黑名单不能太宽）")
    void configAndContractFilesAreNotBlocked() throws IOException {
        for (String name : List.of(
                "契约.json", "配置.yaml", "配置.yml", "schema.xml", "settings.toml",
                "app.ini", "app.conf", "app.properties", "建表.sql", "数据.csv")) {
            Path p = file(name, "x");
            callSaveDeliverable(p);
        }
        verify(mapper, org.mockito.Mockito.times(10)).insert(any(SessionArtifactRecord.class));
    }
}
