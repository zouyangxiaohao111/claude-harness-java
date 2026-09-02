package com.nexusai.application.agent.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sidecar 持久化语义定向测试 · 对齐 CC sessionStorage.ts:320-399。
 *
 * <p><b>WHY（意图验证，规则九）</b>:
 * <ul>
 *   <li>每任务独立 sidecar 文件（remote-agent-{taskId}.meta.json）——--resume 恢复按任务精确判活，
 *       单文件共享会互相污染；</li>
 *   <li>delete 移除任务结束（:105-111 注释）——否则恢复会复活已完成/已 kill 任务；</li>
 *   <li>list 跳过损坏文件（:390-396 注释：crash 的部分写入不应拖垮整个 restore）。</li>
 * </ul>
 */
@DisplayName("[W6-02] RemoteAgentMetadataStore sidecar 持久化（对齐 CC sessionStorage.ts:320-399）")
class RemoteAgentMetadataStoreTest {

    @TempDir
    Path sessionDir;

    private RemoteAgentMetadata meta(String taskId) {
        return new RemoteAgentMetadata(taskId, "remote-agent", "sess-1", "部署",
            "claude -p", 1000L, "tool-1", null, null, null, Map.of("repo", "nexus"), null);
    }

    @Test
    @DisplayName("write→list/read 往返：文件路径 + 字段逐一对齐 CC :328/:337-344")
    void writeListReadRoundtrip() throws Exception {
        // WHY: 恢复路径（restoreRemoteAgentTasks :477-532）从 sidecar 读回全部字段重建任务，
        //      写-读丢失任一字段都会导致恢复后 poll 判活/重建错乱
        RemoteAgentMetadata in = meta("r12345678");
        RemoteAgentMetadataStore.write(sessionDir, in);

        Path file = RemoteAgentMetadataStore.getRemoteAgentMetadataPath(sessionDir, "r12345678");
        assertThat(file).exists();
        assertThat(file.getFileName().toString()).isEqualTo("remote-agent-r12345678.meta.json");

        RemoteAgentMetadata back = RemoteAgentMetadataStore.read(sessionDir, "r12345678");
        assertThat(back).isNotNull();
        assertThat(back.taskId()).isEqualTo("r12345678");
        assertThat(back.remoteTaskType()).isEqualTo("remote-agent");
        assertThat(back.sessionId()).isEqualTo("sess-1");
        assertThat(back.title()).isEqualTo("部署");
        assertThat(back.spawnedAt()).isEqualTo(1000L);
        assertThat(back.toolUseId()).isEqualTo("tool-1");
        assertThat(back.remoteTaskMetadata()).containsEntry("repo", "nexus");

        List<RemoteAgentMetadata> all = RemoteAgentMetadataStore.list(sessionDir);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).taskId()).isEqualTo("r12345678");
    }

    @Test
    @DisplayName("delete 移除 sidecar：结束任务不复活（CC :105-111/:359-367）")
    void deleteRemovesSidecar() throws Exception {
        // WHY: 任务完成/kill 后 sidecar 必须删除，否则 --resume 会 fetchSession 复活已结束任务
        RemoteAgentMetadataStore.write(sessionDir, meta("r11111111"));
        assertThat(RemoteAgentMetadataStore.getRemoteAgentMetadataPath(sessionDir, "r11111111")).exists();

        RemoteAgentMetadataStore.delete(sessionDir, "r11111111");

        assertThat(RemoteAgentMetadataStore.getRemoteAgentMetadataPath(sessionDir, "r11111111")).doesNotExist();
        assertThat(RemoteAgentMetadataStore.list(sessionDir)).isEmpty();
        assertThat(RemoteAgentMetadataStore.read(sessionDir, "r11111111")).isNull();
    }

    @Test
    @DisplayName("list 跳过损坏/不可读文件：部分写入不拖垮 restore（CC :390-396）")
    void listSkipsCorruptFiles() throws Exception {
        // WHY: persistRemoteAgentMetadata 是 fire-and-forget，crash 可能留下半个 JSON；
        //      restore 若因单文件损坏整体抛错，所有远程任务都无法恢复
        RemoteAgentMetadataStore.write(sessionDir, meta("r22222222"));
        Files.writeString(RemoteAgentMetadataStore.getRemoteAgentMetadataPath(sessionDir, "rcorrupt00"),
            "{this is not valid json");

        List<RemoteAgentMetadata> all = RemoteAgentMetadataStore.list(sessionDir);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).taskId()).isEqualTo("r22222222");
    }

    @Test
    @DisplayName("无 remote-agents 目录：list/read 空，不抛错")
    void emptyDirTolerated() {
        // WHY: 首次运行无任何远程任务时 restore 应静默返回（CC :377-383 readdir ENOENT → []）
        assertThat(RemoteAgentMetadataStore.list(sessionDir)).isEmpty();
        assertThat(RemoteAgentMetadataStore.read(sessionDir, "r99999999")).isNull();
    }
}
