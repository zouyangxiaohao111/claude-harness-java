package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 构造器约束定向测试 · 对齐 CC tasks.ts:76-88 TaskSchema（OD-TC-4）
 *
 * <p><b>WHY (意图验证)</b>: CC TaskSchema 的 subject 为 {@code z.string()}（tasks.ts:79，
 * 无 min）——<b>空串合法</b>，仅拒绝 null/undefined（z.string() 语义）。旧 Java 构造器
 * {@code subject == null || subject.isBlank() → IAE}（Task.java:152-153）把空串也拦截，
 * 与 CC 偏离（D-TC-1 联动项）。删除 blank 检查后：
 * <ul>
 *   <li>空串 subject 允许构造，且经 TaskService.createTask 落盘可读回（CC 良性路径）；</li>
 *   <li>null subject 仍拒绝（对齐 z.string() 拒绝 null/undefined）。</li>
 * </ul>
 */
class TaskTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("空串 subject 允许构造（对齐 z.string() 无 min）：Task 构造成功且 activeForm 保持 null 缺省")
    void emptySubject_allowedByConstructor() {
        // WHY: CC tasks.ts:79 subject: z.string() 无 min → "" 通过校验；CC createTask
        // （tasks.ts:284-308）直接落盘，无任何空值拦截（D-TC-1 删除后 Java 同语义）。
        Task task = new Task(null, "", "desc", null, null,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of());

        assertThat(task.subject()).isEmpty();
        // OPD-TS-12：activeForm 缺省不物化为 subject（CC tasks.ts:81 activeForm:
        // z.string().optional() 无默认值；TaskCreateTool.ts:22 optional）——null 透传
        // 由 @JsonInclude(NON_NULL) 省略键，与 CC jsonStringify 省略 undefined 一致。
        assertThat(task.activeForm()).isNull();
    }

    @Test
    @DisplayName("null subject 仍拒绝（对齐 z.string() 拒绝 null/undefined）：IAE")
    void nullSubject_rejectedByConstructor() {
        // WHY: CC z.string() 对 null/undefined 校验失败（tasks.ts:79；zod 语义），
        // Java 构造器保留 null 拒绝即对齐该语义——仅放宽空串。
        assertThatThrownBy(() -> new Task(null, null, "desc", null, null,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("subject");
    }

    @Test
    @DisplayName("空 subject 经 TaskService.createTask 落盘可读回（CC 良性路径 E3）")
    void emptySubject_createTaskThroughService_succeeds() {
        // WHY: 端到端证明空 subject 全链路（构造 → createTask 写盘 → getTask 读回）
        // 均落 CC 良性路径，而非仅工具层 mock 绕过构造器。
        TaskService service = new TaskService(tempDir);
        String id = service.createTask("tl-empty-subject", Task.create("", ""));

        assertThat(id).isEqualTo("1"); // ID=highest+1 照常分配
        Task read = service.getTask("tl-empty-subject", id).orElseThrow();
        assertThat(read.subject()).isEmpty();
    }
}
