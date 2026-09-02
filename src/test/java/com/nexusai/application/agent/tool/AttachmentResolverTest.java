package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session L · {@link AttachmentResolver} CC 对齐契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * AttachmentResolver L1 行为保留（G20② 已删除 read-deny 静默跳过特性，回归 CC 纯
 * stat + imageExt + bridge 主链，见 AttachmentResolver.java 类 Javadoc）。契约：
 * <ol>
 *   <li><b>L1 行为保留</b> → image 后缀识别 + stat 填充 + bridge mode 门控不变</li>
 *   <li><b>6 参 ctor</b> → 不注入 deny 上下文, 所有附件保留</li>
 * </ol>
 */
@DisplayName("Session L · AttachmentResolver CC 对齐 + L1 行为保留")
class AttachmentResolverTest {

    // ═════════════ 测试辅助：构造最小 FileStat ═════════════

    private static AttachmentResolver.FileStat stat(long size) {
        return new AttachmentResolver.FileStat(size, true);
    }

    // ═════════════ 测试: L1 行为保留 · 6 参 ctor 全附件保留 ═════════════

    @Test
    @DisplayName("L1 行为保留 · 6 参 ctor(无 deny 上下文) → 所有附件保留")
    void sixArgCtorPreservesAllAttachments() {
        // WHY: G20② 删除 read-deny 后 6 参 ctor 为唯一构造路径; 只要 fileStatFn 返回
        // isFile=true 的 stat, 附件必须全部保留（回归 CC 纯 stat 链, 无 deny 过滤）。
        Map<String, AttachmentResolver.FileStat> stats = new LinkedHashMap<>();
        stats.put("secret.txt", stat(100L));
        stats.put("data.png", stat(512L));

        AttachmentResolver resolver = new AttachmentResolver(
            "/workspace",
            stats::get,
            null,  // imageExtTest 留 null → 用默认 IMAGE_EXTENSION_REGEX
            () -> false,
            () -> false,
            null);

        List<AttachmentResolver.ResolvedAttachment> result = resolver.resolveAttachments(
            List.of("secret.txt", "data.png"), false, false);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AttachmentResolver.ResolvedAttachment::path)
            .containsExactlyInAnyOrder("secret.txt", "data.png");
    }

    // ═════════════ 测试: L1 行为保留 · stat + imageExt + bridge 门控 ═════════════

    @Test
    @DisplayName("L1 行为保留 · stat→size/isFile, imageExt→isImage, bridge mode 门控 → 上传 + file_uuid 合并")
    void l1BehaviorStatAndBridgeUploadPreserved() {
        Map<String, AttachmentResolver.FileStat> stats = new LinkedHashMap<>();
        stats.put("photo.png", stat(4096L));

        AtomicReference<AttachmentResolver.UploadRequest> capturedReq = new AtomicReference<>();
        AttachmentResolver resolver = new AttachmentResolver(
            "/workspace",
            stats::get,
            null,  // default image regex
            () -> true,   // bridge mode ON
            () -> false,
            (req) -> {
                capturedReq.set(req);
                return "uuid-from-bridge";
            });

        // bridge 模式: replBridgeEnabled=true → upload
        List<AttachmentResolver.ResolvedAttachment> result = resolver.resolveAttachments(
            List.of("photo.png"), true, false);

        assertThat(result).hasSize(1);
        AttachmentResolver.ResolvedAttachment att = result.get(0);
        assertThat(att.path()).isEqualTo("photo.png");
        assertThat(att.size()).isEqualTo(4096L);
        assertThat(att.isImage()).isTrue();
        assertThat(att.fileUuid()).isEqualTo("uuid-from-bridge");
        // 上传调用参数正确
        assertThat(capturedReq.get().path()).isEqualTo("photo.png");
        assertThat(capturedReq.get().size()).isEqualTo(4096L);
        assertThat(capturedReq.get().replBridgeEnabled()).isTrue();
    }

    @Test
    @DisplayName("L1 行为保留 · bridge off → 直接返回 stated, 不上传")
    void l1BehaviorBridgeOffReturnsStatedWithoutUpload() {
        Map<String, AttachmentResolver.FileStat> stats = new LinkedHashMap<>();
        stats.put("doc.txt", stat(64L));  // 非图像后缀

        AttachmentResolver resolver = new AttachmentResolver(
            "/workspace",
            stats::get,
            null,
            () -> false,  // bridge mode OFF
            () -> false,
            null);

        List<AttachmentResolver.ResolvedAttachment> result = resolver.resolveAttachments(
            List.of("doc.txt"), false, false);

        assertThat(result).hasSize(1);
        AttachmentResolver.ResolvedAttachment att = result.get(0);
        assertThat(att.isImage()).as("doc.txt 非图像后缀").isFalse();
        assertThat(att.fileUuid()).isNull();
    }
}
