package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H P2-7] PermissionMetadata sealed interface 测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/types/permissions.ts:157-169}
 * ({@code PermissionCommandMetadata} + {@code PermissionMetadata} union).
 *
 * <p><b>WHY (规则九 · 测试验证意图)</b>: H 把 {@link PermissionResult.Ask#metadata} 从
 * 死字段 {@code Map<String, Object>}（全工程构造点均传 null，无生产者）升级为 CC 等价
 * {@code PermissionMetadata} sealed interface（唯一分支 {@code CommandMetadata}）。
 * 本测试锁定:
 * <ol>
 *   <li>{@code Ask.metadata} 类型已切换（编译期: 可接受 CommandMetadata / null）</li>
 *   <li>sealed 约束: permits 仅 {@code CommandMetadata}（CC union 的唯一分支）</li>
 *   <li>{@code CommandMetadata(name, description)} 两字段形状（CC 157-162 name 必填 +
 *       description 可选; index signature Java record 不支持）</li>
 *   <li>{@code Passthrough} 无 metadata 字段（CC passthrough 变体确实无,
 *       types/permissions.ts:255-266）</li>
 * </ul>
 *
 * @see PermissionResult.PermissionMetadata
 * @since Session H P2-7
 */
class PermissionMetadataSealedInterfaceTest {

    @Test
    @DisplayName("Ask.metadata 接受 CommandMetadata / null (编译期契约)")
    void askMetadata_acceptsCommandMetadataAndNull() {
        PermissionResult.Ask withMeta = new PermissionResult.Ask(
            "ask with meta", new PermissionDecisionReason.Other("test"), List.of(),
            null, null,
            new PermissionResult.PermissionMetadata.CommandMetadata("Bash", "run a command"),
            false, null, List.of());
        assertThat(withMeta.metadata()).isInstanceOf(PermissionResult.PermissionMetadata.class);
        assertThat(withMeta.metadata().command().name()).isEqualTo("Bash");
        assertThat(withMeta.metadata().command().description()).isEqualTo("run a command");

        PermissionResult.Ask withNull = new PermissionResult.Ask(
            "ask with null meta", new PermissionDecisionReason.Other("test"), List.of(),
            null, null, null, false, null, List.of());
        assertThat(withNull.metadata()).as("null 合法 (CC '| undefined')").isNull();
    }

    @Test
    @DisplayName("sealed interface permits 仅 CommandMetadata (反射断言)")
    void permissionMetadata_sealedPermitsOnlyCommandMetadata() throws Exception {
        Class<?> metaInterface = PermissionResult.PermissionMetadata.class;

        List<Class<?>> permitted = Arrays.stream(metaInterface.getPermittedSubclasses())
            .collect(Collectors.toList());
        assertThat(permitted)
            .as("sealed interface 必须恰好 permits CommandMetadata 一个分支 (CC union 唯一分支)")
            .containsExactly(PermissionResult.PermissionMetadata.CommandMetadata.class);
    }

    @Test
    @DisplayName("CommandMetadata 形状: name 必填 + description 可选 (CC 157-162)")
    void commandMetadata_shapeNameRequiredDescriptionOptional() throws Exception {
        Class<?> cmd = PermissionResult.PermissionMetadata.CommandMetadata.class;
        List<String> componentNames = Arrays.stream(cmd.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toList());
        assertThat(componentNames)
            .as("CommandMetadata 只保留 name + description 两字段 (index signature 无法表达, 丢弃)")
            .containsExactly("name", "description");

        // name 必填: null/blank → IllegalArgumentException (与 Ask/Deny 同款紧凑构造器校验)
        try {
            new PermissionResult.PermissionMetadata.CommandMetadata(null, "desc");
            assertThat(false).as("name=null 必须抛 IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) {
            // 符合预期
        }
    }

    @Test
    @DisplayName("metadata 字段不再是 Map (旧死字段类型归零)")
    void askMetadata_notMapAnymore() throws Exception {
        // Ask 第 6 个 record 组件 (message, reason, suggestions, blockedPath, updatedInput, metadata, ...)
        Class<?> askClass = PermissionResult.Ask.class;
        Class<?> metadataType = askClass.getRecordComponents()[5].getType();
        assertThat(Map.class.isAssignableFrom(metadataType))
            .as("metadata 不得再是 Map<String,Object> 死字段")
            .isFalse();
        assertThat(PermissionResult.PermissionMetadata.class.isAssignableFrom(metadataType))
            .as("metadata 必须是 PermissionMetadata sealed interface")
            .isTrue();
    }

    @Test
    @DisplayName("Passthrough 无 metadata 字段 (CC passthrough 变体无, types/permissions.ts:255-266)")
    void passthrough_hasNoMetadataField() {
        List<String> passthroughComponents = Arrays.stream(PermissionResult.Passthrough.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toList());
        assertThat(passthroughComponents)
            .as("Passthrough 只承载 message/reason/suggestions/blockedPath/pendingClassifierCheck (CC 同款)")
            .doesNotContain("metadata");
    }
}
