package com.nexusai.application.chat;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.model.session.dto.AttachmentRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [mediaType 通配符归一化] 请求体里的 {@code image/*} / {@code video/*} / {@code audio/*}
 * 必须在发送侧归一化为真实 MIME，否则图片落盘文件名非法 ⇒ <b>图彻底丢</b>。
 *
 * <h2>WHY（CLAUDE.md 规则 9 · 用真实故障说明「为什么重要」）</h2>
 * <p>前端 {@code front/src/utils/pathAttachment.ts:57}（{@code classifyAttachmentPath}）对图片/视频/音频
 * 发的是<b>字面量</b> {@code 'image/*'} / {@code 'video/*'} / {@code 'audio/*'}，<b>不是真实 MIME</b>。
 * 该值在 A1 各分支被原样透传，最终落到
 * {@code LlmAgentLoop.registerRunPromptImages} → {@code ImageAttachmentStore.storeWithId}，
 * 而该 store 按 {@code {id}.{ext}} 落盘、ext 由 {@code mediaType.split('/')[1]} 得到
 * （{@code ImageAttachmentStore:114-123}，对齐 CC imageStore.ts:34）——通配符下 ext = {@code "*"}
 * ⇒ <b>文件名非法</b>。
 *
 * <p><b>实测铁证</b>（{@code ~/.nexusai/logs/backend.log:70449-70450}，2026-09-17 22:13:43）：
 * <pre>
 * 图片落盘失败：session=sess-569ef8ea id=2100589017534963712 mediaType=image/* 原因=Illegal char &lt;*&gt; at index 20: …
 * </pre>
 * id 是雪花 id ⇒ 该图走的是「≤5MB base64 直传」通道（无 contentId）。后果有二：
 * <b>①该图彻底丢</b>（image-cache 无文件，模型侧既无 image block 也无路径说明）；
 * <b>②前端 F5 按 {@code image_paste_ids} 批量拉图 cache miss</b>。
 * 大图（走 upload 通道带真实 {@code Content-Type}）不命中本缺陷，因此该 bug 长期被掩盖 ——
 * <b>只有小图会带着通配符上线</b>。
 *
 * <h2>为什么必须后端兜底（不能只改前端）</h2>
 * <p>{@code mediaType} 是<b>请求体可构造的输入</b>（{@code POST /chat} 的 {@code attachments[].mediaType}）。
 * 任何客户端（旧版前端 / 脚本 / 第三方集成）都能发 {@code image/*}；只改前端 <b>1 个源</b> 只覆盖
 * 本版前端，后端仍会因非法文件名丢掉<b>用户的图</b>。故归一化落在发送侧
 * {@code ChatService.resolveAttachments}（A1）—— 它是全部消费链的<b>唯一共同上游</b>。
 *
 * <h2>边界（本批不动）</h2>
 * <p>归一化只<b>改 mediaType 的值</b>，不改任何控制流：该丢的附件照丢（三道门 / 限额门 / 魔数门
 * 一律不放宽）。{@code AttachmentController} 上传端点（{@code ~.upload}）不属本批。
 */
@DisplayName("[附件 mediaType] 通配符 image/* → 真实 MIME 归一化（防落盘文件名非法丢图）")
class ChatServiceWildcardMediaTypeNormalizeTest {

    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @TempDir
    Path configHome;

    private final ImageAttachmentStore imageStore = new ImageAttachmentStore();

    @BeforeEach
    void setUp() {
        // 测试隔离：config home 指向 @TempDir（防写真实 ~/.nexusai），同 ImageAttachmentStoreTest 范式
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static List<AttachmentRequest> resolve(ChatService service, String sessionId,
                                                   List<AttachmentRequest> raw) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("resolveAttachments", String.class, List.class);
        m.setAccessible(true);
        return (List<AttachmentRequest>) m.invoke(service, sessionId, raw);
    }

    /** ChatService（attachmentService=register 返回 7 的 mock，localRead=true）。 */
    private static ChatService service() throws Exception {
        AttachmentService svc = mock(AttachmentService.class);
        when(svc.register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(7L);
        ChatService service = new ChatService();
        setField(service, "attachmentService", svc);
        setField(service, "localRead", true);
        return service;
    }

    /** base64 直传图片附件（前端 ≤5MB 小图的实际形态：无 contentId、type=image、mediaType 字面量通配符）。 */
    private static AttachmentRequest base64Image(String filename, String mediaType) {
        return new AttachmentRequest("image", null, filename, mediaType, PNG_BASE64, null);
    }

    private static String mediaTypeOf(List<AttachmentRequest> resolved) {
        assertThat(resolved).as("附件必须被解析（不得因归一化被丢弃）").hasSize(1);
        return resolved.get(0).mediaType();
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 归一化：通配符 → 真实 MIME
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① image/* + photo.png（base64 直传，实测故障形态）→ image/png（改前红：原样透传 image/*）")
    void imageWildcard_normalizedByExtension() throws Exception {
        List<AttachmentRequest> resolved = resolve(service(), "sess-w",
            List.of(base64Image("photo.png", "image/*")));

        assertThat(mediaTypeOf(resolved))
            .as("image/* 是通配符不是 MIME：必须按扩展名 png 归一为 image/png")
            .isEqualTo("image/png");
    }

    @Test
    @DisplayName("② image/* + logo.bmp → image/bmp（bmp 是前端可发的图片扩展名但不在 PATH_EXT_TO_MEDIA_TYPE）")
    void imageWildcard_bmpNotInExtMap_fallsBackToMajorTypeExtension() throws Exception {
        List<AttachmentRequest> resolved = resolve(service(), "sess-w",
            List.of(base64Image("logo.bmp", "image/*")));

        assertThat(mediaTypeOf(resolved))
            .as("bmp 不在 PATH_EXT_TO_MEDIA_TYPE ⇒ 按主类型 + 扩展名（image/bmp），"
                + "不得回落成 image/png —— 那会让文件名说 png 而字节是 bmp")
            .isEqualTo("image/bmp");
    }

    @Test
    @DisplayName("③ image/* + 无扩展名 → image/png（安全默认，不得留 * ）")
    void imageWildcard_noExtension_defaultsToPng() throws Exception {
        List<AttachmentRequest> resolved = resolve(service(), "sess-w",
            List.of(base64Image("screenshot", "image/*")));

        assertThat(mediaTypeOf(resolved))
            .as("扩展名推不出 ⇒ 安全默认 image/png（ImageAttachmentStore 自身兜底同值）")
            .isEqualTo("image/png");
    }

    @Test
    @DisplayName("④ image/* + 扩展名本身非法（photo.*）→ image/png（不得把 * 当扩展名带下去）")
    void imageWildcard_illegalExtension_defaultsToPng() throws Exception {
        List<AttachmentRequest> resolved = resolve(service(), "sess-w",
            List.of(base64Image("photo.*", "image/*")));

        assertThat(mediaTypeOf(resolved))
            .as("扩展名含 * 不是合法扩展名 ⇒ 必须回落安全默认，否则派生出的文件名仍非法")
            .isEqualTo("image/png");
    }

    @Test
    @DisplayName("⑤ video/* + clip.mp4 → video/mp4；audio/* + voice.mp3 → audio/mpeg")
    void videoAndAudioWildcards_normalized() throws Exception {
        // base64 非空只为让该项进入「直传」分支被解析（归一化在分支选择之前，与通道无关）
        assertThat(mediaTypeOf(resolve(service(), "sess-w",
            List.of(new AttachmentRequest("video", null, "clip.mp4", "video/*", PNG_BASE64, null)))))
            .as("video/* → video/mp4").isEqualTo("video/mp4");
        assertThat(mediaTypeOf(resolve(service(), "sess-w",
            List.of(new AttachmentRequest("audio", null, "voice.mp3", "audio/*", PNG_BASE64, null)))))
            .as("audio/* → audio/mpeg").isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("⑥ video/* + 无扩展名 → video/mp4（主类型默认，不得回落成 image/png）")
    void videoWildcard_noExtension_fallsBackToMajorTypeDefault() throws Exception {
        assertThat(mediaTypeOf(resolve(service(), "sess-w",
            List.of(new AttachmentRequest("video", null, "录屏", "video/*", PNG_BASE64, null)))))
            .as("安全默认必须按主类型给（video/mp4），否则给图片 MIME 会让下游当图片处理")
            .isEqualTo("video/mp4");
    }

    @Test
    @DisplayName("⑦ 回归钉：非通配符 mediaType 原样保留（零行为变更）")
    void concreteMediaType_untouched() throws Exception {
        assertThat(mediaTypeOf(resolve(service(), "sess-w",
            List.of(base64Image("photo.jpeg", "image/jpeg")))))
            .as("真实 MIME 不得被本批改写").isEqualTo("image/jpeg");
        assertThat(mediaTypeOf(resolve(service(), "sess-w",
            List.of(base64Image("photo.png", null)))))
            .as("mediaType 缺失不是通配符 ⇒ 不在此处补全（由下游各自兜底，语义不变）").isNull();
    }

    @Test
    @DisplayName("⑧ path 通道同样归一化：image/* + 真实 .png path → 附件表登记的 mediaType 为 image/png")
    void pathChannel_alsoNormalized(@TempDir Path dir) throws Exception {
        Path file = Files.write(dir.resolve("大图.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        AttachmentService svc = mock(AttachmentService.class);
        when(svc.register(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
            .thenReturn(11L);
        ChatService service = new ChatService();
        setField(service, "attachmentService", svc);
        setField(service, "localRead", true);

        List<AttachmentRequest> resolved = resolve(service, "sess-w", List.of(
            new AttachmentRequest("image", null, "大图.png", "image/*", null, file.toAbsolutePath().toString())));

        assertThat(mediaTypeOf(resolved)).as("path 通道与 base64 通道同源归一化").isEqualTo("image/png");
    }

    // ════════════════════════════════════════════════════════════════════
    // ② 根因与后果：文件名 {id}.* 非法 → 落盘失败
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("根因钉：未归一化的 image/* → 派生扩展名 \"*\" → 文件名 {id}.*（Windows 下直接非法）")
    void rootCause_wildcardDerivesStarExtension() {
        // store 的扩展名 = mediaType.split('/')[1]（ImageAttachmentStore:114-123 对齐 CC imageStore.ts:34）。
        // 该推导的产物是「扩展名字符串」，但取它的唯一公开入口 getImagePath 会先把整条路径交给
        // Path.resolve ⇒ 在 Windows（本仓实测/故障平台）**到不了**返回值就抛了；非 Windows 平台不抛。
        // 两种平台都证明同一根因：扩展名 = "*"（非法文件名字符段）。
        try {
            String path = imageStore.getImagePath("sess-w", 9L, "image/*");
            assertThat(Path.of(path).getFileName().toString())
                .as("非 Windows：Path 允许 \"*\" ⇒ 文件名仍是 {id}.*（扩展名 = * 这一根因不变）")
                .isEqualTo("9.*");
        } catch (java.nio.file.InvalidPathException e) {
            assertThat(e.getMessage())
                .as("Windows：正是实测 backend.log:70449 的 Illegal char <*>")
                .contains("Illegal char <*>");
        }
        assertThat(imageStore.getImagePath("sess-w", 9L, "image/png"))
            .as("归一化后同一 id 的文件名合法（同 id 对照，排除其它变量）").endsWith("9.png");
    }

    @Test
    @DisplayName("端到端：resolveAttachments → registerRunPromptImages → image-cache 真的落盘且文件名合法（改前红）")
    void endToEnd_wildcardImageActuallyPersists() throws Exception {
        List<AttachmentRequest> resolved = resolve(service(), "sess-w",
            List.of(base64Image("photo.png", "image/*")));

        int registered = LlmAgentLoop.registerRunPromptImages(imageStore, "sess-w", resolved);

        assertThat(registered).as("可注入 base64 图应被登记").isEqualTo(1);
        Path cacheDir = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "image-cache", "sess-w");
        assertThat(Files.isDirectory(cacheDir))
            .as("落盘目录应存在（改前：文件名含 * ⇒ 落盘失败 ⇒ 目录内无文件 / 目录不存在）")
            .isTrue();
        List<String> names;
        try (Stream<Path> s = Files.list(cacheDir)) {
            names = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(names)
            .as("必须落盘【恰好一个】图片文件，且文件名 = {雪花id}.png（合法扩展名，无 *）")
            .hasSize(1)
            .allMatch(n -> n.matches("\\d+\\.png"));
    }
}
