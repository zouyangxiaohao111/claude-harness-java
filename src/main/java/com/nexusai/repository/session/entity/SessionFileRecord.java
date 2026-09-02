package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("session_files")
public class SessionFileRecord {
    @Id private String id;
    private String sessionId;
    private String path;
    private String status;          // 'modified'|'added'|'deleted'|'renamed'
    private Integer additions;
    private Integer deletions;
    private String oldRev;
    private String newRev;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAdditions() { return additions; }
    public void setAdditions(Integer additions) { this.additions = additions; }
    public Integer getDeletions() { return deletions; }
    public void setDeletions(Integer deletions) { this.deletions = deletions; }
    public String getOldRev() { return oldRev; }
    public void setOldRev(String oldRev) { this.oldRev = oldRev; }
    public String getNewRev() { return newRev; }
    public void setNewRev(String newRev) { this.newRev = newRev; }
}