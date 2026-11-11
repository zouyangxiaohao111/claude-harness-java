package com.nexusai.kb.app.parser;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * 解析引擎能力快照 · 对齐源侧 {@code GET /api/v1/system/parser-engines} 的 {@code data[]} 元素。
 *
 * <p><b>⚠️ 键名是大写开头</b>：源侧 Go 结构体未打 json tag，序列化出 {@code Name} / {@code Description}
 * / {@code FileTypes} / {@code Available} / {@code UnavailableReason}。前端按这批键读取，
 * 故这里用 {@link JsonProperty} 逐字锁定，⛔ 不要「顺手」改成 camelCase。
 *
 * <p><b>不可用引擎</b>：{@code anydoc} 依赖随包分发的原生库 —— 非 Windows / 库缺失时
 * {@code available=false} 并在 {@code unavailableReason} 给出原因（由 {@code KbAnydocParser}
 * 实现 {@code SelfDescribingAvailability} 自报）。其余内置解析器恒 {@code available=true}、
 * {@code unavailableReason=""}；字段保留是为了让前端那套「置灰 + 说明」的渲染逻辑不用为
 * NexusAI 分支特判。
 *
 * @param name              引擎标识（同 {@link KbDocumentParser#name()}）
 * @param displayName       展示名（同 {@link KbDocumentParser#displayName()}）
 * @param fileTypes         支持的扩展名（小写、不含点）
 * @param available         当前是否可用
 * @param unavailableReason 不可用原因（可用时为空串，非 null）
 */
public record ParserEngineInfo(
    @JsonProperty("Name") String name,
    @JsonProperty("Description") String displayName,
    @JsonProperty("FileTypes") Set<String> fileTypes,
    @JsonProperty("Available") boolean available,
    @JsonProperty("UnavailableReason") String unavailableReason
) {

    /**
     * 归一化 {@code fileTypes}：按字典序重排。
     *
     * <p><b>为什么要排</b>：解析器手里的是 {@code Set.of(...)}，其迭代顺序<b>每次 JVM 启动都不同</b>
     * （由 {@code java.util.ImmutableCollections} 的随机盐决定）。不排则同一份能力在两次进程启动后
     * 吐出不同的 {@code FileTypes} 数组顺序 —— 前端下拉的选项顺序会无端跳动，响应也不可复现。
     * 源站返回的同样是字典序数组，排序后两边形状一致。
     */
    public ParserEngineInfo {
        fileTypes = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(fileTypes));
    }
}
