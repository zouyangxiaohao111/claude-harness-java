package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.eventbus.ws.StreamEvent;

import java.util.List;

/**
 * 文件变化 · 由 git hook / file watcher 触发 · Phase 5 v1 不主动发出，DTO 占位
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/files} （不在 stream topic 下）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FilesChangedEvent extends StreamEvent {

    public static class FileEntry {
        private final String path;
        private final String status;       // modified | added | deleted | renamed
        private final Integer additions;
        private final Integer deletions;

        public FileEntry(String path, String status, Integer additions, Integer deletions) {
            this.path = path;
            this.status = status;
            this.additions = additions;
            this.deletions = deletions;
        }
        public String getPath() { return path; }
        public String getStatus() { return status; }
        public Integer getAdditions() { return additions; }
        public Integer getDeletions() { return deletions; }
    }

    private final List<FileEntry> files;

    public FilesChangedEvent(String sessionId, String userMessageId, List<FileEntry> files) {
        super("files.changed", sessionId, userMessageId);
        this.files = files;
    }

    public List<FileEntry> getFiles() { return files; }
}