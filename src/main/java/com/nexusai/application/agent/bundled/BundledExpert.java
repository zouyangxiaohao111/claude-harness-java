package com.nexusai.application.agent.bundled;

/**
 * 一个随安装包内置的专家（classpath {@code experts/octop/<division>/<slug>.md}）。
 *
 * <p>只读目录模型：{@code content} 是该 md 的完整文本，安装时原样写到用户目录，
 * 与内置版本逐字节一致（用于判定「已安装且未改动」）。
 *
 * @param slug         全局唯一标识（= 目标文件名去 .md，撞名时已按 {@code <division>-<stem>} 消解）
 * @param division     分类目录（academic / engineering / …）
 * @param name         frontmatter name（agentType，安装后注册表的主键）
 * @param description  frontmatter description（一句话）
 * @param emoji        frontmatter emoji（可空；仅展示用）
 * @param color        frontmatter color（可空；仅展示用）
 * @param resourcePath classpath 资源路径（排错用）
 * @param content      md 全文（LF-only）
 */
public record BundledExpert(
    String slug,
    String division,
    String name,
    String description,
    String emoji,
    String color,
    String resourcePath,
    String content
) {
}
