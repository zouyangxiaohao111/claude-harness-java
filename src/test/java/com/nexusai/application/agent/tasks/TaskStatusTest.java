package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task.TaskStatus 状态契约定向测试 · 对齐 CC tasks.ts:69-73 严格 z.enum
 *
 * <p>CC 真源（grep 自验，非注释；注意文件为 src/utils/tasks.ts，非根 tasks.ts）：
 * <pre>
 * export const TASK_STATUSES = ['pending', 'in_progress', 'completed'] as const   // utils/tasks.ts:69
 * export const TaskStatusSchema = lazySchema(() =>
 *   z.enum(['pending', 'in_progress', 'completed']),                               // utils/tasks.ts:71-73
 * )
 * ...
 * status: TaskStatusSchema(),                                                     // utils/tasks.ts:83
 * </pre>
 *
 * <p>CC 存储态严格 3 值，无任何 alias：'deleted' 仅 TaskUpdate 输入层 action
 * （TaskUpdateTool.ts:35 TaskUpdateStatusSchema），物理走 deleteTask()，从不写入
 * 'deleted' 存储值（tasks.ts:393-441）。历史宽松 alias（deleted→DELETED、
 * inprogress→IN_PROGRESS、done→COMPLETED）是 nexusai 自加的，违反 CC 规范，已删除。
 *
 * <p><b>WHY (意图验证)</b>: 本测试锁定 Java 端状态契约与 CC 严格 z.enum 的一致性——
 * 一旦状态契约回退为宽松 alias（或 DELETED 重新加入枚举），以下任一断言必须变红。
 * 这是防回归闸：旧实现（删除前）接受 deleted/inprogress/done 等宽松 alias，
 * 本测试确保该宽松行为不得回潮。
 *
 * <p>Java 端实现（grep 自验 Task.java）：
 * <ul>
 *   <li>{@code enum TaskStatus { PENDING, IN_PROGRESS, COMPLETED }} — Task.java:81-82</li>
 *   <li>{@code fromString} 严格 3 值 + 大小写敏感，失败（含 null / alias / 大小写变体）返回 null — Task.java:106-118</li>
 *   <li>{@code fromString(value, defaultValue)} 失败（含 null）落默认值 — Task.java:131-134</li>
 *   <li>{@code toValue()} = name().toLowerCase()（对齐 CC JSON 小写） — Task.java:137-139</li>
 * </ul>
 *
 * <p>严格化对齐（本测试按 Java 实际行为断言，锁死 CC 对齐）：
 * <ul>
 *   <li>fromString(null) → null（对齐 CC safeParse 失败返回 null，utils/tasks.ts:333-339），
 *       不默认 pending。</li>
 *   <li>fromString 大小写敏感（对齐 CC z.enum 精确匹配，utils/tasks.ts:71-73）：混合大小写
 *       'Pending' / 全大写 'PENDING' 一律返回 null，不再 toLowerCase 折叠。磁盘读路径
 *       parseStatusStrict（TaskFileStorage.parseStatusStrict）同样严格仅小写（DC-3 已移除旧 ⊕
 *       大写容忍），两端一致对齐 CC 严格 z.enum，无分歧。</li>
 *   <li>非法值（alias / 未知）返回 null，不抛 IllegalArgumentException。</li>
 * </ul>
 */
class TaskStatusTest {

    // ────────────────────────────────────────────────────────────────────────
    // 枚举形状：严格 3 值（防 DELETED / 第 4 值回潮）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("枚举仅含 CC 严格 3 值：PENDING / IN_PROGRESS / COMPLETED")
    void enumContainsExactlyCcThreeValues() {
        assertThat(Task.TaskStatus.values())
            .hasSize(3)
            .containsExactlyInAnyOrder(
                Task.TaskStatus.PENDING,
                Task.TaskStatus.IN_PROGRESS,
                Task.TaskStatus.COMPLETED);
    }

    // ────────────────────────────────────────────────────────────────────────
    // fromString 合法解析
    // ────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"pending", "in_progress", "completed"})
    @DisplayName("fromString 小写 CC 值解析正确（对齐 TASK_STATUSES，utils/tasks.ts:69）")
    void fromStringCcLowercaseParses(String value) {
        Task.TaskStatus expected = Task.TaskStatus.valueOf(value.toUpperCase());
        assertThat(Task.TaskStatus.fromString(value))
            .as("fromString('%s') 应解析为 %s", value, expected)
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "IN_PROGRESS", "COMPLETED"})
    @DisplayName("fromString 全大写 enum 名返回 null（对齐 CC z.enum 大小写敏感，utils/tasks.ts:71-73）")
    void fromStringUpperCaseRejected(String value) {
        // WHY: CC z.enum(['pending','in_progress','completed']) 精确匹配，'PENDING' 等全大写
        // 不在三值内 → safeParse 失败返回 null（tasks.ts:333-339）。旧实现经 toLowerCase 折叠
        // 接受大写（Java ⊕ 遗留），违反 CC 严格大小写敏感，已删除——本测试锁死不得回潮。
        assertThat(Task.TaskStatus.fromString(value))
            .as("fromString('%s') 应返回 null（大小写敏感）", value)
            .isNull();
    }

    @Test
    @DisplayName("fromString 显式映射 3 值（精确断言，防参数化仅靠 valueOf 自洽）")
    void fromStringExactThreeMappings() {
        assertThat(Task.TaskStatus.fromString("pending")).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(Task.TaskStatus.fromString("in_progress")).isEqualTo(Task.TaskStatus.IN_PROGRESS);
        assertThat(Task.TaskStatus.fromString("completed")).isEqualTo(Task.TaskStatus.COMPLETED);
    }

    // ────────────────────────────────────────────────────────────────────────
    // fromString 拒绝 alias（Rule 9 核心：防历史宽松 alias 回潮）
    // ────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "deleted", "inprogress", "done", "open", "resolved",   // 历史宽松 alias / CC 输入层 action
        "foo",                                                  // 任意未知值
        "DELETED",                                              // 大写 alias（大小写敏感仍被拒）
        "in progress",                                          // 空格拼写非下划线
        "pending "                                              // 尾随空格不 trim
    })
    @DisplayName("fromString 拒绝 alias / 未知值 → null（对齐 CC safeParse 失败返回 null，utils/tasks.ts:333-339）")
    void fromStringRejectsAliases(String value) {
        // WHY: CC z.enum 严格 3 值无 alias，safeParse 失败返回 null（tasks.ts:333-339），不抛异常。
        // 旧实现抛 IllegalArgumentException；严格化后返回 null，调用方（TaskUpdateTool/TaskService）
        // 以 null 判「解析失败」。
        assertThat(Task.TaskStatus.fromString(value))
            .as("fromString('%s') 应返回 null（严格 3 值无 alias，失败即 null）", value)
            .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // null / 默认值行为（对齐 CC safeParse→null）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fromString(null) → null（对齐 CC safeParse 失败返回 null，utils/tasks.ts:333-339）")
    void fromStringNullReturnsNull() {
        // WHY: CC z.enum 为必填字段，缺 status 走 safeParse 失败 → null，从不默认 pending。
        // 旧实现 fromString(null)→PENDING（Java ⊕ 防御默认），违反 CC，已严格化。
        assertThat(Task.TaskStatus.fromString(null))
            .as("fromString(null) 应返回 null（不默认 pending）")
            .isNull();
    }

    @Test
    @DisplayName("2 参 fromString：非法值 / null 落默认值；合法值直返不走默认")
    void fromStringWithDefaultFallsBackOnIllegal() {
        // 非法 alias → 返回默认值
        assertThat(Task.TaskStatus.fromString("done", Task.TaskStatus.COMPLETED))
            .as("非法 'done' 应落 defaultValue")
            .isEqualTo(Task.TaskStatus.COMPLETED);

        // 合法值 → 直接解析，不走默认分支（即便默认值不同）
        assertThat(Task.TaskStatus.fromString("pending", Task.TaskStatus.COMPLETED))
            .as("合法 'pending' 应直返 PENDING，不落 default")
            .isEqualTo(Task.TaskStatus.PENDING);

        // null → fromString(null) 返回 null（safeParse 失败），落 default 分支
        assertThat(Task.TaskStatus.fromString(null, Task.TaskStatus.COMPLETED))
            .as("fromString(null, default) 应落 defaultValue COMPLETED")
            .isEqualTo(Task.TaskStatus.COMPLETED);
    }

    // ────────────────────────────────────────────────────────────────────────
    // toValue / round-trip（对齐 CC JSON 小写序列化）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toValue() 输出对齐 CC JSON 小写（TASK_STATUSES，utils/tasks.ts:69）")
    void toValueMatchesCcJson() {
        assertThat(Task.TaskStatus.PENDING.toValue()).isEqualTo("pending");
        assertThat(Task.TaskStatus.IN_PROGRESS.toValue()).isEqualTo("in_progress");
        assertThat(Task.TaskStatus.COMPLETED.toValue()).isEqualTo("completed");
    }

    @Test
    @DisplayName("round-trip：fromString(v.toValue()) == v 对所有枚举值成立")
    void roundTripToValueFromString() {
        for (Task.TaskStatus v : Task.TaskStatus.values()) {
            assertThat(Task.TaskStatus.fromString(v.toValue()))
                .as("round-trip %s（toValue='%s'）应还原", v, v.toValue())
                .isEqualTo(v);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 大小写敏感（对齐 CC z.enum 精确匹配；磁盘读路径 parseStatusStrict 同样严格仅小写，
    // DC-3 已移除大写容忍，两端一致对齐 CC，见类 Javadoc）
    // ────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"Pending", "In_Progress", "Completed"})
    @DisplayName("fromString 混合大小写返回 null（对齐 CC z.enum 大小写敏感，utils/tasks.ts:71-73）")
    void fromStringMixedCaseRejected(String value) {
        // WHY: CC z.enum(['pending','in_progress','completed']) 精确匹配，'Pending'/'In_Progress'/
        // 'Completed' 不在三值内 → safeParse 失败返回 null（tasks.ts:333-339）。旧实现经 toLowerCase
        // 折叠接受混合大小写（Task.java:101），违反 CC 严格大小写敏感，已删除——本测试锁死不得回潮。
        assertThat(Task.TaskStatus.fromString(value))
            .as("fromString('%s') 应返回 null（大小写敏感，混合大小写不再折叠）", value)
            .isNull();
    }
}
