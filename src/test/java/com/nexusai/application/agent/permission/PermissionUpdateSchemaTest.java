package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PermissionUpdateSchema 定向测试 · 对齐 CC utils/permissions/PermissionUpdateSchema.ts:42-78。
 *
 * <p>WHY (规则九)：mailbox 来的 permissionUpdates 由 buggy/旧版 teammate 进程写入，畸形条目
 * 若未经 safeParse 过滤会直接透传到 callback.onAllow() → 下游 persist 时崩溃或落脏规则。
 * 严格 type 判别（discriminatedUnion）是唯一防线 —— 缺 type / 非法 type / 缺必填字段必须
 * 被拒（empty），否则「不合法跳过」的语义失效。
 */
class PermissionUpdateSchemaTest {

    private static Map<String, Object> rule(String toolName, String ruleContent) {
        return ruleContent == null
            ? Map.of("toolName", toolName)
            : Map.of("toolName", toolName, "ruleContent", ruleContent);
    }

    // ── 6 种 type 合法解析 ───────────────────────────────────────────────────

    @Test
    void addRules_parsesToAddRulesRecord() {
        Map<String, Object> entry = Map.of(
            "type", "addRules",
            "rules", List.of(rule("Bash", "ls")),
            "behavior", "allow",
            "destination", "session");
        Optional<PermissionUpdate> parsed = PermissionUpdateSchema.safeParse(entry);
        assertTrue(parsed.isPresent(), "合法 addRules 必须解析成功");
        assertTrue(parsed.get() instanceof PermissionUpdate.AddRules a && a.destination() == PermissionUpdate.Destination.SESSION);
        PermissionUpdate.AddRules a = (PermissionUpdate.AddRules) parsed.get();
        assertEquals(1, a.rules().size());
        assertEquals("Bash", a.rules().get(0).ruleValue().toolName());
        assertEquals("ls", a.rules().get(0).ruleValue().ruleContent());
        assertEquals(PermissionBehavior.ALLOW, a.behavior());
    }

    @Test
    void replaceRules_parsesToReplaceRulesRecord() {
        Map<String, Object> entry = Map.of(
            "type", "replaceRules",
            "rules", List.of(rule("Edit", "/tmp/**")),
            "behavior", "ask",
            "destination", "projectSettings");
        Optional<PermissionUpdate> parsed = PermissionUpdateSchema.safeParse(entry);
        assertTrue(parsed.get() instanceof PermissionUpdate.ReplaceRules);
        PermissionUpdate.ReplaceRules r = (PermissionUpdate.ReplaceRules) parsed.get();
        assertEquals(PermissionUpdate.Destination.PROJECT_SETTINGS, r.destination());
        assertEquals(PermissionBehavior.ASK, r.behavior());
    }

    @Test
    void removeRules_parsesToRemoveRulesRecord() {
        Map<String, Object> entry = Map.of(
            "type", "removeRules",
            "rules", List.of(rule("WebFetch", null)),
            "behavior", "allow",
            "destination", "userSettings");
        Optional<PermissionUpdate> parsed = PermissionUpdateSchema.safeParse(entry);
        assertTrue(parsed.get() instanceof PermissionUpdate.RemoveRules);
        PermissionUpdate.RemoveRules r = (PermissionUpdate.RemoveRules) parsed.get();
        assertEquals(PermissionUpdate.Destination.USER_SETTINGS, r.destination());
        assertEquals("WebFetch", r.rules().get(0).ruleValue().toolName());
    }

    @Test
    void setMode_parsesToSetModeRecord() {
        Map<String, Object> entry = Map.of(
            "type", "setMode",
            "mode", "acceptEdits",
            "destination", "session");
        Optional<PermissionUpdate> parsed = PermissionUpdateSchema.safeParse(entry);
        assertTrue(parsed.get() instanceof PermissionUpdate.SetMode);
        assertEquals(PermissionMode.ACCEPT_EDITS, ((PermissionUpdate.SetMode) parsed.get()).mode());
    }

    @Test
    void addDirectories_parsesToAddDirectoriesRecord() {
        Map<String, Object> entry = Map.of(
            "type", "addDirectories",
            "directories", List.of("/tmp", "/work"),
            "destination", "session");
        Optional<PermissionUpdate> parsed = PermissionUpdateSchema.safeParse(entry);
        assertTrue(parsed.get() instanceof PermissionUpdate.AddDirectories);
        assertEquals(List.of("/tmp", "/work"), ((PermissionUpdate.AddDirectories) parsed.get()).paths());
    }

    @Test
    void removeDirectories_parsesToRemoveDirectoriesRecord() {
        Map<String, Object> entry = Map.of(
            "type", "removeDirectories",
            "directories", List.of("/tmp"),
            "destination", "session");
        Optional<PermissionUpdate> parsed = PermissionUpdateSchema.safeParse(entry);
        assertTrue(parsed.get() instanceof PermissionUpdate.RemoveDirectories);
        assertEquals(List.of("/tmp"), ((PermissionUpdate.RemoveDirectories) parsed.get()).paths());
    }

    // ── 畸形条目拒绝（strict type 判别 + 必填字段）──────────────────────────

    @Test
    void nonMap_returnsEmpty() {
        assertTrue(PermissionUpdateSchema.safeParse("not a map").isEmpty());
        assertTrue(PermissionUpdateSchema.safeParse(null).isEmpty());
    }

    @Test
    void missingType_returnsEmpty() {
        // WHY: CC discriminatedUnion('type', ...) 必须携带 type，缺 type → fail
        //      （与 WebSocketPermissionPrompter 的形状推断回退不同 —— 本 schema 严格）。
        Map<String, Object> entry = Map.of("mode", "plan");
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "缺 type 的条目必须被拒（严格 discriminatedUnion，不做形状推断）");
    }

    @Test
    void unknownType_returnsEmpty() {
        Map<String, Object> entry = Map.of("type", "grantEverything", "rules", List.of());
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(), "非法 type 字面量必须被拒");
    }

    @Test
    void addRules_missingBehavior_returnsEmpty() {
        Map<String, Object> entry = Map.of(
            "type", "addRules",
            "rules", List.of(rule("Bash", null)),
            "destination", "session");
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "addRules 缺 behavior 必须被拒（CC schema :46 必填）");
    }

    @Test
    void addRules_missingDestination_returnsEmpty() {
        Map<String, Object> entry = Map.of(
            "type", "addRules",
            "rules", List.of(rule("Bash", null)),
            "behavior", "allow");
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "addRules 缺 destination 必须被拒（CC schema :48 必填）");
    }

    @Test
    void addRules_emptyRules_returnsEmpty() {
        Map<String, Object> entry = Map.of(
            "type", "addRules",
            "rules", List.of(),
            "behavior", "allow",
            "destination", "session");
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "addRules 空 rules 必须被拒（AddRules 不变量 rules 非空）");
    }

    @Test
    void removeRules_missingBehavior_returnsEmpty() {
        // WHY: CC removeRules 强制 behavior（PermissionUpdateSchema.ts:59）。Java RemoveRules
        //      record 不存 behavior 字段，但 safeParse 仍须校验存在性后丢弃 —— 不能以
        //      「Java 无法承载」为由跳过校验（否则 {type:'removeRules', rules, destination}
        //      缺 behavior 会 CC 拒 / Java 接受）。
        Map<String, Object> entry = Map.of(
            "type", "removeRules",
            "rules", List.of(rule("WebFetch", null)),
            "destination", "userSettings");
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "removeRules 缺 behavior 必须被拒（CC schema :59 必填，record 不存但 safeParse 须校验）");
    }

    @Test
    void setMode_missingDestination_returnsEmpty() {
        // WHY: CC 6 变体均强制 destination（PermissionUpdateSchema.ts:65）。委托解析器
        //      parseSetMode 只读 mode 不读 destination，故 safeParse 必须前置校验 ——
        //      否则 {type:'setMode', mode:'plan'} 缺 destination 会 CC 拒 / Java 接受。
        Map<String, Object> entry = Map.of(
            "type", "setMode",
            "mode", "plan");
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "setMode 缺 destination 必须被拒（CC schema :65 必填 destination）");
    }

    @Test
    void addDirectories_missingDestination_returnsEmpty() {
        // WHY: CC 6 变体均强制 destination（PermissionUpdateSchema.ts:70）。委托解析器
        //      parseDirectories 只读 directories 不读 destination，故 safeParse 必须前置校验。
        Map<String, Object> entry = Map.of(
            "type", "addDirectories",
            "directories", List.of("/tmp"));
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "addDirectories 缺 destination 必须被拒（CC schema :70 必填 destination）");
    }

    @Test
    void setMode_unknownMode_returnsEmpty() {
        Map<String, Object> entry = Map.of(
            "type", "setMode",
            "mode", "turbo", // 非 CC 字面量亦非 Java 枚举名 → 必拒
            "destination", "session");
        assertTrue(PermissionUpdateSchema.safeParse(entry).isEmpty(),
            "setMode 非法 mode 必须被拒（CC externalPermissionModeSchema 仅 5 种）");
    }
}
