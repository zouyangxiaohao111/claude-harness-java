package com.nexusai.application.agent.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地址判类顺序（session → main → agent → Nexus sess- → name）与远端形态。
 */
@DisplayName("PeerAddressing · 判类顺序与远端形态")
class MessageAddressingTest {

    @Test
    @DisplayName("顺序: main 先于 name；agent 先于 name；sess- 在结构类之后、name 之前")
    void classifyOrder() {
        assertThat(PeerAddressing.classify("main").kind()).isEqualTo(PeerAddressing.Kind.MAIN);
        assertThat(PeerAddressing.classify("a1234567890abcdef").kind())
            .isEqualTo(PeerAddressing.Kind.AGENT);
        assertThat(PeerAddressing.classify("a-worker-1234567890abcdef").kind())
            .isEqualTo(PeerAddressing.Kind.AGENT);
        assertThat(PeerAddressing.classify("sess-1a2b3c4d").kind())
            .isEqualTo(PeerAddressing.Kind.NEXUS_SESSION);
        assertThat(PeerAddressing.classify("researcher").kind()).isEqualTo(PeerAddressing.Kind.NAME);
        // 大小写敏感：MAIN 不是 main
        assertThat(PeerAddressing.classify("MAIN").kind()).isEqualTo(PeerAddressing.Kind.NAME);
    }

    @Test
    @DisplayName("sess- 位置：放在 name catch-all 之前才可达；长度/字符不符则回落 name")
    void nexusSessionPosition() {
        assertThat(PeerAddressing.classify("sess-0123abcd").kind())
            .isEqualTo(PeerAddressing.Kind.NEXUS_SESSION);
        assertThat(PeerAddressing.classify("sess-0123ABCD").kind())
            .isEqualTo(PeerAddressing.Kind.NAME);
        assertThat(PeerAddressing.classify("sess-0123abc").kind())
            .isEqualTo(PeerAddressing.Kind.NAME);
    }

    @Test
    @DisplayName("远端 session 形全部识别为 SESSION（不得落本地 mailbox）")
    void remoteForms() {
        for (String raw : new String[]{
            "uds:/tmp/x.sock", "bridge:session_abc", "did:example:1",
            "/tmp/agent.sock", "\\\\.\\pipe\\x", "local_0123456789ab",
            "session_abc", "cse_abc"}) {
            assertThat(PeerAddressing.classify(raw).kind())
                .as("remote form %s", raw)
                .isEqualTo(PeerAddressing.Kind.SESSION);
            assertThat(PeerAddressing.isRemoteSessionAddress(raw)).isTrue();
        }
        assertThat(PeerAddressing.isRemoteSessionAddress("sess-0123abcd")).isFalse();
        assertThat(PeerAddressing.isRemoteSessionAddress("researcher")).isFalse();
    }

    @Test
    @DisplayName("name [ref] 解析与 ref 生成")
    void nameWithRef() {
        PeerAddressing.ParsedName parsed = PeerAddressing.parseNameWithRef("alice [ab12cd]");
        assertThat(parsed.name()).isEqualTo("alice");
        assertThat(parsed.ref()).isEqualTo("ab12cd");

        PeerAddressing.ParsedName plain = PeerAddressing.parseNameWithRef("alice");
        assertThat(plain.ref()).isNull();

        assertThat(PeerAddressing.formatAddress("alice", "sess-0123abcd"))
            .isEqualTo("alice [" + PeerAddressing.refFor("sess-0123abcd") + "]");
        assertThat(PeerAddressing.refFor("sess-0123abcd")).hasSize(6);
    }
}
