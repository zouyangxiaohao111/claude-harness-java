package com.nexusai.repository.im_channel.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.im_channel.ImInstanceEntry;

/**
 * MyBatis-Flex 持久化记录：{@code im_channel_instance} 表行（V302）。
 *
 * <p>DDD 分层同 {@code ChannelBindingRecord}：持久化关注点与 domain record 分离，
 * 应用层只持 {@link ImInstanceEntry}。
 *
 * <p><b>credential 只在本层出现</b>：它存 {@code CryptoUtil.encrypt} 的密文，
 * 且 {@link #toDomain()} <b>不带出</b>该字段 —— 防止凭证明文经 DTO 泄漏到前端。
 *
 * <p>命名：MyBatis-Flex camelCase → snake 精确映射
 * （displayName→display_name、statusDetail→status_detail、lastActiveAt→last_active_at、
 * lastCursor→last_cursor、configJson→config_json、createdAt→created_at、updatedAt→updated_at）。
 */
@Table("im_channel_instance")
public class ImChannelInstanceRecord {

    @Id private String id;
    private String platform;
    private String displayName;
    private String credential;     // 密文，不出本层
    private String status;
    private String statusDetail;
    private String lastActiveAt;
    private String lastCursor;     // 微信 get_updates_buf（单消费者游标，必须落库）
    private String configJson;
    /** V313：飞书 user_access_token（**密文**，与 credential 同一套 CryptoUtil）。 */
    private String userToken;
    /** V313：飞书 refresh_token（**密文**）。 */
    private String userRefreshToken;
    /** V313：user token 过期时间（ISO-8601，可空）。 */
    private String userTokenExpiresAt;
    /** V313：授权用户的 open_id（非敏感，明文便于排障）。 */
    private String userOpenId;
    /**
     * V314：授权用户的**昵称**。
     *
     * <p>为什么必须存：飞书事件不带昵称、单聊 chat_id 没有名字、应用身份查通讯录也拿不到 name
     * ⇒ 只有「授权时用 user token 查 {@code authen/v1/user_info}」能拿到。
     * ⚠️ 只在「p2p 且 sender.open_id == user_open_id」时可用（那才是你本人）。
     */
    private String userDisplayName;
    /**
     * V316：这次授权实际拿到的 scope（飞书返回的空格分隔原串）。
     *
     * <p>为什么必须存：user token 携带的是「授权那一刻」的权限集合。
     * 我们后来给 {@code DEFAULT_SCOPE} 加了读权限，旧 token 不会自动获得
     * ⇒ 表现成「代码对、调用报权限错」，用户无从知道要重新授权。
     */
    private String userScope;
    private String createdAt;
    private String updatedAt;

    public ImInstanceEntry toDomain() {
        return new ImInstanceEntry(id, platform, displayName, status, statusDetail,
            lastActiveAt, configJson, createdAt, updatedAt);
    }

    public static ImChannelInstanceRecord fromDomain(ImInstanceEntry e) {
        ImChannelInstanceRecord r = new ImChannelInstanceRecord();
        r.setId(e.id());
        r.setPlatform(e.platform());
        r.setDisplayName(e.displayName());
        r.setStatus(e.status());
        r.setStatusDetail(e.statusDetail());
        r.setLastActiveAt(e.lastActiveAt());
        r.setConfigJson(e.configJson());
        r.setCreatedAt(e.createdAt());
        r.setUpdatedAt(e.updatedAt());
        return r;
    }

    public String getUserToken() { return userToken; }
    public void setUserToken(String v) { this.userToken = v; }
    public String getUserRefreshToken() { return userRefreshToken; }
    public void setUserRefreshToken(String v) { this.userRefreshToken = v; }
    public String getUserTokenExpiresAt() { return userTokenExpiresAt; }
    public void setUserTokenExpiresAt(String v) { this.userTokenExpiresAt = v; }
    public String getUserOpenId() { return userOpenId; }
    public void setUserOpenId(String v) { this.userOpenId = v; }
    public String getUserDisplayName() { return userDisplayName; }
    public void setUserDisplayName(String v) { this.userDisplayName = v; }
    public String getUserScope() { return userScope; }
    public void setUserScope(String v) { this.userScope = v; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCredential() { return credential; }
    public void setCredential(String credential) { this.credential = credential; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusDetail() { return statusDetail; }
    public void setStatusDetail(String statusDetail) { this.statusDetail = statusDetail; }
    public String getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(String lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public String getLastCursor() { return lastCursor; }
    public void setLastCursor(String lastCursor) { this.lastCursor = lastCursor; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
