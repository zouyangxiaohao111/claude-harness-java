package com.nexusai.application.agent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionCwdHolder 会话级可变 cwd 载体 · 对齐 CC bootstrap/state.ts 单一可变 STATE.cwd
 * （getCwdState/setCwdState state.ts:527-533）。
 *
 * <p>WHY (规则九 · 测试验证意图): CC 的 {@code STATE.cwd} 是<b>单一可变字段</b>，bash cd（Shell.ts:407
 * setCwd → setCwdState）与 worktree 入口（EnterWorktreeTool.ts:95 setCwd）<b>均写此字段</b>，后者覆盖
 * 前者——cd 后 getCwd() 返回 cd 子目录。若 Java 端拆为 worktreeCwd/sessionCwd 双独立存储且让
 * worktreeCwd 遮蔽 sessionCwd，则活跃 worktree 内 cd 后 getCwd() 仍返回 worktree 基路径，违反
 * INV-2「cd 后用新 cwd」。SessionCwdHolder 作为<b>合并存储</b>承载此语义：二者写同一 sessionId 槽，
 * cd 覆盖 worktree 初始值。本测试锁定：per-session 隔离、set/get/clear、合并覆盖语义、NFC+realpath 归一化。
 */
@DisplayName("[CC-CWD-03] SessionCwdHolder 会话级可变 cwd（CC 单 STATE.cwd）")
class SessionCwdHolderTest {

    @AfterEach
    void cleanup() {
        SessionCwdHolder.reset();
    }

    @Test
    @DisplayName("per-session: 不同 sessionId 槽位互不污染 (state.ts per-session STATE.cwd)")
    void sessionBound_cwdsIsolated() {
        // WHY: CC 每会话独立 STATE.cwd，会话间不得互相覆盖。
        SessionCwdHolder.set("sess-a", "/cwd/work-a");
        SessionCwdHolder.set("sess-b", "/cwd/work-b");

        assertThat(SessionCwdHolder.get("sess-a"))
            .as("会话 A 槽位不得被 B 覆盖")
            .isNotEqualTo(SessionCwdHolder.get("sess-b"));
        assertThat(SessionCwdHolder.get("sess-a")).contains("work-a");
        assertThat(SessionCwdHolder.get("sess-b")).contains("work-b");
    }

    @Test
    @DisplayName("合并存储: worktree 入口写入后被 cd 覆盖 (后者覆盖前者 对齐 CC 单 STATE.cwd)")
    void worktreeEntryThenCd_overwritesSameSlot(@TempDir Path worktreeBase) throws Exception {
        // WHY: [Fix-R1] worktree 入口与 cd 共用同一 sessionId 槽（对齐 CC setCwd 均写 STATE.cwd）。
        //       若拆双存储且 worktreeCwd 遮蔽 sessionCwd，cd 后 getCwd() 仍返回 worktree 基路径 → 违反 INV-2。
        Path sub = worktreeBase.resolve("sub");
        sub.toFile().mkdirs();

        // worktree 入口写入基路径
        SessionCwdHolder.set("sess-a", worktreeBase.toString());
        String afterEnter = SessionCwdHolder.get("sess-a");
        assertThat(afterEnter).isEqualTo(worktreeBase.toRealPath().toString());

        // bash cd 子目录 → 同槽覆盖（不应保留 worktree 基路径）
        SessionCwdHolder.set("sess-a", sub.toString());
        String afterCd = SessionCwdHolder.get("sess-a");

        assertThat(afterCd)
            .as("cd 后 sessionCwd 必须是 cd 子目录，不得仍返回 worktree 基路径 (INV-2)")
            .isEqualTo(sub.toRealPath().toString())
            .isNotEqualTo(afterEnter);
    }

    @Test
    @DisplayName("clear 后 get 返回 null (退出 worktree 恢复 pre-worktree 回落下一层)")
    void clear_restoresNullForNextLayer() {
        // WHY: 退出 worktree 时 clear 即恢复 pre-worktree（worktree 入口写入被清，getCwd 回落 boundProject/user.dir）。
        SessionCwdHolder.set("sess-a", "/cwd/work-a");
        assertThat(SessionCwdHolder.get("sess-a")).isNotNull();

        SessionCwdHolder.clear("sess-a");
        assertThat(SessionCwdHolder.get("sess-a"))
            .as("clear 后 get 必须 null，使 getCwd 回落下一层")
            .isNull();
    }

    @Test
    @DisplayName("归一化: set 时 realpath+NFC (对齐 setCwdState NFC + Shell.ts realpathSync)")
    void set_normalizesRealpathAndNfc(@TempDir Path dir) throws Exception {
        // WHY: CC setCwdState 做 NFC 归一化、Shell.ts setCwd 做 realpathSync，避免符号链接/Unicode 假阳性。
        SessionCwdHolder.set("sess-a", dir.toString());
        String stored = SessionCwdHolder.get("sess-a");

        assertThat(stored)
            .as("存储值必须经 realpath 归一化")
            .isEqualTo(dir.toRealPath().toString());
    }

    @Test
    @DisplayName("null/空 入参静默忽略 (不污染已有槽位)")
    void nullArgs_ignored() {
        SessionCwdHolder.set("sess-a", "/cwd/work-a");
        SessionCwdHolder.set("sess-a", null);
        SessionCwdHolder.set(null, "/cwd/other");

        assertThat(SessionCwdHolder.get("sess-a"))
            .as("null/空 cwd 不得覆盖已有槽位")
            .isNotNull();
    }

    // ===== [INV-3] originalCwd 双槽（对齐 CC STATE.originalCwd state.ts:46/515-517） =====

    @Test
    @DisplayName("[INV-3] originalCwd 槽 per-session 隔离 (对齐 CC per-session STATE.originalCwd)")
    void originalCwdSlot_perSessionIsolated() {
        // WHY: CC STATE.originalCwd 是独立字段（state.ts:46），EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())
        //       重锚 worktree 入口；不同会话不得互相覆盖，否则跨会话 CLAUDE.md 扫描根错乱。
        SessionCwdHolder.setOriginalCwd("sess-a", "/cwd/orig-a");
        SessionCwdHolder.setOriginalCwd("sess-b", "/cwd/orig-b");

        assertThat(SessionCwdHolder.getOriginalCwd("sess-a"))
            .as("会话 A originalCwd 槽不得被 B 覆盖")
            .isNotEqualTo(SessionCwdHolder.getOriginalCwd("sess-b"));
        assertThat(SessionCwdHolder.getOriginalCwd("sess-a")).contains("orig-a");
        assertThat(SessionCwdHolder.getOriginalCwd("sess-b")).contains("orig-b");
    }

    @Test
    @DisplayName("[INV-3] originalCwd 槽与 cwd 槽独立 (对齐 CC STATE.cwd 与 STATE.originalCwd 双字段)")
    void originalCwdSlot_independentFromCwdSlot(@TempDir Path dir) throws Exception {
        // WHY: [Fix-R1] cwd 槽（worktree 入口与 cd 共用）与 originalCwd 槽（worktree 入口重锚）是 CC
        //       STATE.cwd / STATE.originalCwd 两个独立字段（state.ts:46/48 + 527-533/515-517）。
        //       若二者共用存储，则活跃 worktree 内 cd（写 cwd 槽）会冲掉 originalCwd 重锚值，
        //       CLAUDE.md 扫描/存档会跟着 cd 漂移，违反 INV-3「worktree 会话内 CLAUDE.md/存档走 worktreePath」。
        //       锁定：写 originalCwd 不污染 cwd 槽；写 cwd 不污染 originalCwd 槽。
        SessionCwdHolder.set("sess-a", dir.toString());
        SessionCwdHolder.setOriginalCwd("sess-a", dir.toString());

        // cd 写 cwd 槽到子目录，originalCwd 槽不得被冲
        Path sub = dir.resolve("sub");
        sub.toFile().mkdirs();
        SessionCwdHolder.set("sess-a", sub.toString());

        assertThat(SessionCwdHolder.get("sess-a"))
            .as("cwd 槽应反映 cd 子目录 (INV-2)")
            .isEqualTo(sub.toRealPath().toString());
        assertThat(SessionCwdHolder.getOriginalCwd("sess-a"))
            .as("originalCwd 槽必须独立——cd 不得冲掉 worktree 入口重锚值 (INV-3)")
            .isEqualTo(dir.toRealPath().toString())
            .isNotEqualTo(SessionCwdHolder.get("sess-a"));
    }

    @Test
    @DisplayName("[INV-3] originalCwd 槽 set 时 realpath+NFC 归一化 (对齐 setOriginalCwd state.ts:516 NFC)")
    void originalCwdSlot_normalizesRealpathAndNfc(@TempDir Path dir) throws Exception {
        // WHY: CC setOriginalCwd(cwd) state.ts:515-517 做 cwd.normalize('NFC')；realpath 对齐 Shell.ts setCwd。
        SessionCwdHolder.setOriginalCwd("sess-a", dir.toString());
        assertThat(SessionCwdHolder.getOriginalCwd("sess-a"))
            .as("originalCwd 存储值必须经 realpath+NFC 归一化")
            .isEqualTo(dir.toRealPath().toString());
    }

    @Test
    @DisplayName("[INV-3] clearOriginalCwd 后 getOriginalCwd 返回 null (退出 worktree 恢复回落 boundProject)")
    void clearOriginalCwd_restoresNullForNextLayer() {
        // WHY: CC ExitWorktreeTool.ts:129 setOriginalCwd(originalCwd) 退出恢复。Java 端无 pre-worktree originalCwd
        //       持久化（boundProject 是稳定身份），clear 即回落 boundProject（对齐 pre-worktree originalCwd=boundProject）。
        //       锁定：clear 后 getOriginalCwd=null，使 getOriginalCwdLayer 回落 boundProject。
        SessionCwdHolder.setOriginalCwd("sess-a", "/cwd/orig-a");
        assertThat(SessionCwdHolder.getOriginalCwd("sess-a")).isNotNull();

        SessionCwdHolder.clearOriginalCwd("sess-a");
        assertThat(SessionCwdHolder.getOriginalCwd("sess-a"))
            .as("clearOriginalCwd 后必须 null，使 getOriginalCwdLayer 回落 boundProject")
            .isNull();
    }
}
