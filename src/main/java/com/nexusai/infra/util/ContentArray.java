package com.nexusai.infra.util;

import java.util.List;
import java.util.Map;

/**
 * ContentArray · 对齐 CC utils/contentArray.ts.
 *
 * <p>L1 语义: 在 API content 数组中插入 block — 放到最后一个 tool_result 之后;
 * 如果插入位置是末尾,附加一个 text continuation block (避免 API 拒绝非 text 末尾)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: insertBlockAfterToolResults(content, block)→void;mutates content in-place</li>
 *   <li><b>A2 Golden Trace</b>: 找到最后 tool_result 后插入;无 tool_result → 插入到 last - 1;尾插时附加 {type:'text', text:'.'} 续 block</li>
 *   <li><b>A3 副作用</b>: 改变 content 列表 (CC 等价)</li>
 *   <li><b>A4 边界</b>: 空 list → 插入到索引 0;无 tool_result + 单元素 → 替换当前;null block 仍插入</li>
 *   <li><b>A5 业务场景</b>: cache editing 指令插入到 tool_result 后,API 不会 reject</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS array.splice → Java List.add(idx, e);TS type 检查
 * ('type' in item) → Java instanceof Map key check;
 * TS text-continuation inline object → Java Map.of entries。
 */
public final class ContentArray {

    private ContentArray() {}

    /**
     * Insert {@code block} into the content array per placement rules.
     *
     * <p>Rules:
     * <ol>
     *   <li>If any tool_result block exists → insert AFTER the last tool_result</li>
     *   <li>If inserted block is now last → append a text continuation block (some APIs reject non-text trail)</li>
 *   <li>Else → insert before the last block</li>
 *   <li>Empty content → insert at index 0</li>
 * </ol>
     */
    public static void insertBlockAfterToolResults(List<Object> content, Object block) {
        if (content == null) return;
        int lastToolResultIdx = -1;
        for (int i = 0; i < content.size(); i++) {
            Object item = content.get(i);
            if (item instanceof Map) {
                Object type = ((Map<?, ?>) item).get("type");
                if ("tool_result".equals(type)) lastToolResultIdx = i;
            }
        }
        if (lastToolResultIdx >= 0) {
            int insertPos = lastToolResultIdx + 1;
            content.add(insertPos, block);
            if (insertPos == content.size() - 1) {
                content.add(java.util.Map.of("type", "text", "text", "."));
            }
        } else {
            int insertIdx = Math.max(0, content.size() - 1);
            content.add(insertIdx, block);
        }
    }
}
