package com.nexusai.apis.im;

import com.nexusai.model.im_channel.ImInstanceEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多实例同名时列表显示名加序号（飞书 1 / 飞书 2）。
 *
 * <p>为什么需要：两个飞书接入默认都叫「飞书」，渠道列表与输入框渠道选择器
 * 共用 {@code GET /im/instances}，不加序号用户分不清点的是哪一个。
 */
@DisplayName("实例列表 · 同名加序号")
class ImChannelControllerSequenceTest {

    private static ImInstanceEntry inst(String id, String platform, String name) {
        return new ImInstanceEntry(id, platform, name, "connected", null, null, null, "t", "t");
    }

    @Test
    @DisplayName("两个同名飞书 ⇒ 飞书 1 / 飞书 2（按入参顺序=创建先后）")
    void twoSameNamedFeishu() {
        List<ImInstanceEntry> out = ImChannelController.withDuplicateSequence(List.of(
                inst("a", "feishu", "飞书"),
                inst("b", "feishu", "飞书")));

        assertThat(out).extracting(ImInstanceEntry::displayName)
                .containsExactly("飞书 1", "飞书 2");
        // id / 其它字段不变
        assertThat(out.get(0).id()).isEqualTo("a");
        assertThat(out.get(1).id()).isEqualTo("b");
    }

    @Test
    @DisplayName("三个同名 ⇒ 1 / 2 / 3")
    void threeSameNamed() {
        List<ImInstanceEntry> out = ImChannelController.withDuplicateSequence(List.of(
                inst("a", "feishu", "飞书"),
                inst("b", "feishu", "飞书"),
                inst("c", "feishu", "飞书")));

        assertThat(out).extracting(ImInstanceEntry::displayName)
                .containsExactly("飞书 1", "飞书 2", "飞书 3");
    }

    @Test
    @DisplayName("名字不同 ⇒ 原样，不加序号")
    void differentNamesUntouched() {
        List<ImInstanceEntry> out = ImChannelController.withDuplicateSequence(List.of(
                inst("a", "feishu", "测试飞书"),
                inst("b", "feishu", "生产飞书")));

        assertThat(out).extracting(ImInstanceEntry::displayName)
                .containsExactly("测试飞书", "生产飞书");
    }

    @Test
    @DisplayName("跨平台同名 ⇒ 各自唯一，不加序号")
    void crossPlatformSameNameUntouched() {
        List<ImInstanceEntry> out = ImChannelController.withDuplicateSequence(List.of(
                inst("a", "feishu", "飞书"),
                inst("b", "wechat", "飞书")));

        assertThat(out).extracting(ImInstanceEntry::displayName)
                .containsExactly("飞书", "飞书");
    }

    @Test
    @DisplayName("单个实例 ⇒ 原样")
    void singleUntouched() {
        List<ImInstanceEntry> out = ImChannelController.withDuplicateSequence(List.of(
                inst("a", "feishu", "飞书")));

        assertThat(out).extracting(ImInstanceEntry::displayName).containsExactly("飞书");
    }

    @Test
    @DisplayName("空列表 ⇒ 原样返回，不抛")
    void emptySafe() {
        assertThat(ImChannelController.withDuplicateSequence(List.of())).isEmpty();
        assertThat(ImChannelController.withDuplicateSequence(null)).isNull();
    }
}
