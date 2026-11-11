package com.nexusai.repository.project.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("projects")
public class ProjectRecord {
    @Id private String id;
    private String name;
    private String path;
    private String branch;
    private Integer dirty;
    private Integer agents;
    private String lastIndexedAt;
    private Boolean bound;
    /** V280 · 注册时间（UTC ISO-8601，Z 结尾）· 左栏项目分组序唯一依据（注册先后，排完不动）。 */
    private String createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public Integer getDirty() { return dirty; }
    public void setDirty(Integer dirty) { this.dirty = dirty; }
    public Integer getAgents() { return agents; }
    public void setAgents(Integer agents) { this.agents = agents; }
    public String getLastIndexedAt() { return lastIndexedAt; }
    public void setLastIndexedAt(String lastIndexedAt) { this.lastIndexedAt = lastIndexedAt; }
    public Boolean getBound() { return bound; }
    public void setBound(Boolean bound) { this.bound = bound; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}