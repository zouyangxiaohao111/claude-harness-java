package com.nexusai.model.session.dto;

/**
 * 附件 DTO（HTTP 请求体 {@code SendMessageRequest.attachments} 元素）· 对齐 CC
 * {@code PastedContent}（utils/config.ts:54-62）的多模态扩展（A1 方案定稿：图片直发
 * image content block / 视频音频走多模态工具路由）。
 *
 * <p>字段命名对齐 CC（snake_case → camelCase，行号标注）：
 * <table>
 *   <tr><th>Java 字段</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>{@link #type()}</td><td>{@code type: 'text'|'image'}</td><td>config.ts:56（本 DTO 扩展 image/video/audio/file）</td></tr>
 *   <tr><td>{@link #contentId()}</td><td>{@code id: number // Sequential numeric ID}</td><td>config.ts:55（指向 {@code ImageAttachmentStore} 缓存的数字 id 字符串）</td></tr>
 *   <tr><td>{@link #filename()}</td><td>{@code filename? // Display name for images}</td><td>config.ts:59</td></tr>
 *   <tr><td>{@link #mediaType()}</td><td>{@code mediaType? // e.g. 'image/png'}</td><td>config.ts:58</td></tr>
 *   <tr><td>{@link #base64()}</td><td>{@code content}</td><td>config.ts:57（可选直传；无 contentId 时使用）</td></tr>
 * </table>
 *
 * <p><b>附件三通道（path / base64 / contentId 三选一，互斥取一）</b>：
 * <ul>
 *   <li>{@link #path()} 非空 → 本地绝对路径附件（local-read=true 前后端同机，后端直读；
 *       大文件 PDF/媒体/大图经附件表注册后按 contentId 解析，本 DTO 先承载外部 path）</li>
 *   <li>{@link #base64()} 非空白 → 直传内容（CC {@code PastedContent.content} 直发语义）</li>
 *   <li>{@link #contentId()} 非空 → 缓存/附件表引用（读缓存/查附件表补全）</li>
 * </ul>
 *
 * <p>消费侧（{@code ChatService.processUserMessage} → {@code RunRequest.attachments}）：
 * <ul>
 *   <li>{@link #base64()} 非空白 → 直传内容（CC {@code PastedContent.content} 直发语义）</li>
 *   <li>{@link #base64()} 为空且 {@link #contentId()} 非空 → 经 {@code ImageAttachmentStore.getBase64}
 *       读缓存补全 base64 + mediaType（CC imageStore.ts 缓存路径语义，Java 读取 API）</li>
 *   <li>二者皆无 / 缓存未命中 → 无法消费，warn 并跳过（fail loud）</li>
 * </ul>
 *
 * @param type      附件类型：image / video / audio / file · CC original: {@code type}（config.ts:56）
 * @param contentId 指向 ImageAttachmentStore 缓存的数字 id（字符串承载）· CC original: {@code id}（config.ts:55）
 * @param filename  显示名 · CC original: {@code filename?}（config.ts:59）
 * @param mediaType MIME 类型（e.g. 'image/png'）· CC original: {@code mediaType?}（config.ts:58）
 * @param base64    可选直传 base64 内容 · CC original: {@code content}（config.ts:57）
 * @param path      本地绝对路径附件（local-read=true 前端 Tauri 直传后端读盘；与 base64/contentId
 *                  三选一）· 净新增字段，非 CC 对齐（CC 无外部路径直读语义，config.ts 附件仅
 *                  content/id/filename/mediaType）· 大文件（&gt;5MB PDF/媒体/大图）走此通道省 upload，
 *                  resolveAttachments path 分支校验后经 {@code AttachmentService.register} 注册附件表
 */
public record AttachmentRequest(
    String type,
    String contentId,
    String filename,
    String mediaType,
    String base64,
    String path
) {}
