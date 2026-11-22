package com.nexusai.domain.im_channel.catalog;

import java.util.List;

/**
 * 平台通讯录里的一个人（尚未落库）。
 *
 * <h2>为什么单独一个 record，不复用 {@code ImChatCandidate}</h2>
 * 会话是「一个对话」，联系人是「一个人」——同一个人可能有多个会话（单聊/群聊里的身份），
 * 而一个会话里有多个人。两者的 id 空间也不同：企微的 {@code sessions/list} 给的是
 * <b>加密 chat_id</b>，{@code contact/users/search} 给的是<b>加密 userid</b>（实测同前缀
 * {@code woNH-}，都能当 `message/send` 的 chat_id，但语义不同）。
 *
 * @param userId      平台侧用户标识（企微：加密 userid，可直接当 chat_id 用）
 * @param name        显示名
 * @param alias       英文名（可空）
 * @param position    职务（可空）
 * @param departments 部门（可空）
 */
public record ImContactCandidate(
        String userId,
        String name,
        String alias,
        String position,
        List<String> departments) {

    public ImContactCandidate {
        departments = departments == null ? List.of() : List.copyOf(departments);
    }
}
