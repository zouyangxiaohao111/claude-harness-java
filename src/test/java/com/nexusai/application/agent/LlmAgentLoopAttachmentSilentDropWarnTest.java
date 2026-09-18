package com.nexusai.application.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.attachment.MediaAttachmentStore;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [附件静默丢弃 · fail-loud 红线] 附件消费链上「丢掉用户看得见的东西」的分支必须 ≥WARN 留痕。
 *
 * <h2>WHY（CLAUDE.md 规则 9 · 规则十二）</h2>
 * <p>前端 chip 已经显示、用户以为图/视频已经发出去，但附件链某一跳把该项 {@code continue} 掉之后
 * <b>既没有内容块、也没有说明文本、更没有一行日志</b> ⇒ 模型零内容 + 运维无痕，是「正面违反
 * fail-loud」的最坏形态：<b>故障不可归因</b>。同一份 LlmAgentLoop 里的同类分支（PDF 链
 * {@code PdfAttachmentProcessor:448}）与 ChatService 侧（{@code resolveAttachments} 的每个
 * {@code continue}）<b>全部有 {@code log.warn}</b>，只有本类点名的这几处是裸 {@code continue}。
 *
 * <h2>判据（本类逐条钉住）</h2>
 * <p>被点名的分支必须满足：<b>它丢的是用户可见产物（内容块或说明文本）</b> 且 <b>此前没有 ≥WARN 留痕</b>。
 * 于是本类用一个「只收 WARN+」的 appender 当探针 —— 这一点是刻意的：生产
 * {@code logback-spring.xml} root=INFO，{@code log.debug} <b>不可见</b>，所以「已有 debug 日志」
 * <b>不构成留痕</b>。把 logger 限到 WARN 恰好把「只有 debug」与「有 WARN」区分开。
 *
 * <h2>反向实验配方</h2>
 * <p>删掉任意一处新增的 {@code log.warn} ⇒ 对应用例立刻变红（捕获不到 WARN）。
 *
 * <h2>边界（不属本类判据）</h2>
 * <p>「分流式 {@code continue}」（type 不属于本条链 / 该项由另一条链消费，如 type=image 不进
 * {@code buildMediaAttachmentNotes}）<b>不</b>算静默丢弃 —— 另有一条链对它负责。本类只点名
 * 「没有任何一条链会接手」的那几处。
 */
@DisplayName("[附件静默丢弃] 消费链丢弃分支必须 ≥WARN 留痕（debug 不算留痕）")
class LlmAgentLoopAttachmentSilentDropWarnTest {

    private ch.qos.logback.classic.Logger loopLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachWarnOnlyAppender() {
        loopLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmAgentLoop.class);
        originalLevel = loopLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        loopLogger.addAppender(appender);
        // 只放行 WARN+ ⇒ 生产 root=INFO 下不可见的 debug 留痕在此同样不可见（探针口径 = 生产口径）
        loopLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachAppender() {
        loopLogger.detachAppender(appender);
        appender.stop();
        loopLogger.setLevel(originalLevel);
    }

    /** 已捕获的 WARN+ 消息（格式化后）。 */
    private List<String> warns() {
        return appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private void assertWarnMentions(String marker, String whatWasDropped) {
        assertThat(warns())
                .as("「%s」被丢弃却没有 ≥WARN 留痕（前端 chip 在、模型零内容、运维无痕）", whatWasDropped)
                .anyMatch(msg -> msg.contains(marker));
    }

    // ════════════════════════════════════════════════════════════════════
    // ① buildMediaAttachmentNotes —— 媒体（video/audio/file）附件说明
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("媒体附件无 path 且无 contentId ⇒ 无说明产出，但必须 WARN（否则用户零感知）")
    void mediaAttachment_withoutPathAndContentId_warns() {
        String notes = LlmAgentLoop.buildMediaAttachmentNotes(
                null, null, "sess-w",
                List.of(new AttachmentRequest("video", null, "clip.mp4", "video/mp4", null, null)));

        // 控制流不变：该丢的还是丢（本条链产不出任何说明）
        assertThat(notes).as("无 path / 无 contentId —— 说明链无法产出内容（本批不改控制流）").isEmpty();
        assertWarnMentions("clip.mp4", "无 path 无 contentId 的媒体附件");
    }

    @Test
    @DisplayName("媒体附件 contentId 非数字 ⇒ 无法解析，必须 WARN（此前 catch 里裸 continue）")
    void mediaAttachment_contentIdNotNumeric_warns() {
        String notes = LlmAgentLoop.buildMediaAttachmentNotes(
                null, null, "sess-w",
                List.of(new AttachmentRequest("file", "not-a-number", "报告.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null, null)));

        assertThat(notes).isEmpty();
        assertWarnMentions("not-a-number", "contentId 非数字的媒体附件");
    }

    @Test
    @DisplayName("媒体附件 contentId 解析不出路径且 media store 未注入 ⇒ 必须 WARN（无可回退源）")
    void mediaAttachment_storeNotInjected_warns() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.getPath(anyLong())).thenReturn(null);

        String notes = LlmAgentLoop.buildMediaAttachmentNotes(
                null, attachmentService, "sess-w",
                List.of(new AttachmentRequest("audio", "5", "voice.mp3", "audio/mpeg", null, null)));

        assertThat(notes).isEmpty();
        assertWarnMentions("voice.mp3", "附件表与 store 均不可用的媒体附件");
    }

    @Test
    @DisplayName("媒体附件 附件表未命中 + media store 未命中 ⇒ 必须 WARN（此前裸 continue）")
    void mediaAttachment_notFoundAnywhere_warns() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.getPath(anyLong())).thenReturn(null);
        MediaAttachmentStore store = mock(MediaAttachmentStore.class);
        when(store.get(anyString(), anyLong())).thenReturn(null);

        String notes = LlmAgentLoop.buildMediaAttachmentNotes(
                store, attachmentService, "sess-w",
                List.of(new AttachmentRequest("file", "5", "打包产物.zip", "application/octet-stream", null, null)));

        assertThat(notes).isEmpty();
        assertWarnMentions("打包产物.zip", "附件表与 media-cache 双未命中的媒体附件");
    }

    // ════════════════════════════════════════════════════════════════════
    // ② buildLargeImagePathNotes —— 大图（>5MB）路径说明
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("大图解析不出真实路径 ⇒ 无说明产出，但必须 WARN（原注释自称 fail loud 却零日志）")
    void largeImage_withoutResolvablePath_warns() {
        String notes = LlmAgentLoop.buildLargeImagePathNotes(
                null, null, "sess-w",
                List.of(new AttachmentRequest("image", null, "大图.png", "image/png", null, null)));

        assertThat(notes).as("无 path / 无 contentId ⇒ 拼不出「本地路径=…」（本批不改控制流）").isEmpty();
        assertWarnMentions("大图.png", "解析不出真实路径的大图附件");
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ buildUserMessageWithImages —— 图片 drain（registerRunPromptImages 的消费侧）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("图片 drain 时缓存未命中（base64 空 + 磁盘读回也空）⇒ 该图不进 content，但必须 WARN")
    void imageDrain_cacheMiss_warns() {
        ImageAttachmentStore store = mock(ImageAttachmentStore.class);
        when(store.drainPendingPromptImages("sess-w"))
                .thenReturn(List.of(new ImageAttachmentStore.PastedImage(9L, null, "image/png")));
        when(store.getBase64("sess-w", 9L)).thenReturn(null);
        // supportsImage=true 需要 DB 判据：mock ModelMapper 返回 type=multimodal 的模型行
        // （ModelNameResolver 裸名兼容路径走 selectListByQuery，见 ModelNameResolver:274-280）
        ModelMapper modelMapper = mock(ModelMapper.class);
        ModelRecord multimodal = new ModelRecord();
        multimodal.setType("multimodal");
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(multimodal));

        LlmAgentLoop.buildUserMessageWithImages(store, null, modelMapper, null,
                "这张图里有什么？", "test-model", "sess-w", null, false);

        assertWarnMentions("图片 drain", "drain 时缓存未命中的图片（id=9）");
    }
}
