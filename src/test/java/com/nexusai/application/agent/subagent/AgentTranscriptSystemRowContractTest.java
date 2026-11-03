package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-02] 子代理 transcript 的系统注入三件套（isMeta / author / subtype）往返。
 *
 * <h2>WHY（规则九：意图而非行为）</h2>
 * <p>用户实测：「`&lt;system-reminder&gt; The following skills are available…` 这种 hook 不能算用户消息，
 * 应该显示为系统提示」。
 *
 * <p>病根在 <b>transcript 契约缺字段</b>：`skill_listing` / `edited_text_file` / hook 回执这些行在 DTO 里
 * 都是 `role=user`，写入侧 `chatMessageToMap` 不写 `isMeta`/`author`，读侧 `parseMessageNode` 也不读
 * ⇒ 右栏（AgentTranscriptView）**没有任何依据**区分「用户真打的字」与「系统注入的 meta 行」
 * ⇒ 几 KB 的技能清单被渲染成一条用户气泡。
 *
 * <p>本测试钉的是「**契约往返不丢字段**」：
 * <ol>
 *   <li><b>meta 行</b>：isMeta/author/subtype 三件套必须逐字回来（否则消费侧无法识别）；</li>
 *   <li><b>普通用户提问</b>：isMeta 必须是 false、author 必须是 null（⛔ 不能因补齐字段把正常提问标成 meta，
 *       那会让用户的真问题从对话流里消失）；</li>
 *   <li><b>旧转录兼容</b>：文件里没有这些键时解析出 false/null（= CC undefined，非双轨）。</li>
 * </ol>
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>把 `chatMessageToMap` 里新加的 `isMeta` 写入删掉 ⇒ 用例 ① 变红；</li>
 *   <li>把 `parseMessageNode` 里新加的三个读取删掉 ⇒ 用例 ① / ③ 变红；</li>
 *   <li>把 `isMeta` 读取改成恒 `true` ⇒ 用例 ② 变红。</li>
 * </ul>
 */
@DisplayName("[2026-11-02] transcript 系统注入三件套往返")
class AgentTranscriptSystemRowContractTest {

    @TempDir
    Path tmpDir;

    private static final String SESSION_ID = "sess-contract";
    private static final String AGENT_ID = "a0123456789abcdef";

    /**
     * 走**生产写入路径**（{@link AgentTranscript#recordSidechainTranscript} —— SubagentExecutor
     * 逐消息录制、tool_result 录制、压缩落盘全部经过它）落盘，再用生产读取路径读回。
     *
     * <p>⛔ 不直接调 {@code SubagentExecutor.chatMessageToMap}：它是 package-private，且本测试的
     * 意图是「**契约往返不丢字段**」，写入侧桩件与生产同构即可（真实的 DTO→map 映射由
     * {@code SubagentExecutor} 自己的测试覆盖）。
     */
    private Optional<AgentMessage> roundTrip(Map<String, Object> map) {
        map.put("parentUuid", null);
        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION_ID, AGENT_ID, List.of(map));
        return AgentTranscript.getAgentTranscript(tmpDir, SESSION_ID, AGENT_ID)
            .map(r -> r.messages().stream().findFirst().orElse(null));
    }

    /** 与 {@code SubagentExecutor.chatMessageToMap} 同构的 meta 行 map（含本次新增的三件套）。 */
    private Map<String, Object> metaMap(String id, String author, String subtype, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", id);
        m.put("role", "user");
        m.put("content", content);
        m.put("isMeta", true);
        m.put("author", author);
        m.put("subtype", subtype);
        return m;
    }


    @Test
    @DisplayName("meta 注入行：isMeta/author/subtype 逐字往返（WHY：否则右栏只能把它当用户提问渲染）")
    void metaRowRoundTrip() {
        Optional<AgentMessage> got = roundTrip(metaMap(
            "att-1", "attachment", "skill_listing",
            "<system-reminder>\nThe following skills are available for use with the Skill tool:\n\n- a: x\n</system-reminder>"));

        assertThat(got).isPresent();
        AgentMessage m = got.get();
        assertThat(m.isMeta()).as("isMeta 丢了 ⇒ 消费侧无法识别这是系统注入行").isTrue();
        assertThat(m.author()).isEqualTo("attachment");
        assertThat(m.subtype()).isEqualTo("skill_listing");
        // 正文必须原样保留（摘要/展开都要用）
        assertThat(m.content()).contains("The following skills are available");
    }

    @Test
    @DisplayName("普通用户提问：isMeta=false / author=null（WHY：误标成 meta 会让用户的真问题从对话流消失）")
    void normalUserRowNotMarkedMeta() {
        // 普通提问的写入形态：不写 isMeta / author / subtype 三个键（生产侧同样惰性写入）
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uuid", "u-1");
        m.put("role", "user");
        m.put("content", "帮我看看这个仓库");

        Optional<AgentMessage> got = roundTrip(m);
        assertThat(got).isPresent();
        assertThat(got.get().isMeta()).isFalse();
        assertThat(got.get().author()).isNull();
        assertThat(got.get().subtype()).isNull();
    }

    @Test
    @DisplayName("旧转录兼容：文件里没有 isMeta/author/subtype 键 ⇒ 解析出 false/null（= CC undefined）")
    void legacyRowDefaults() {
        // 直接写一行「旧格式」JSON（不经过 chatMessageToMap，模拟历史文件）
        Path file = AgentTranscript.getTranscriptPath(tmpDir, SESSION_ID, AGENT_ID);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            String legacy = "{\"uuid\":\"old-1\",\"role\":\"assistant\",\"content\":\"旧消息\","
                + "\"agentId\":\"" + AGENT_ID + "\",\"isSidechain\":true,\"parentUuid\":null}";
            java.nio.file.Files.writeString(file, legacy + "\n");
        } catch (Exception e) {
            throw new AssertionError("准备旧格式转录失败", e);
        }

        Optional<AgentTranscript.AgentTranscriptResult> res =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION_ID, AGENT_ID);
        assertThat(res).isPresent();
        AgentMessage m = res.get().messages().get(0);
        assertThat(m.isMeta()).isFalse();
        assertThat(m.author()).isNull();
        assertThat(m.subtype()).isNull();
        assertThat(m.content()).isEqualTo("旧消息");
    }
}
