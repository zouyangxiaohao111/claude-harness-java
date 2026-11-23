package com.nexusai.model.connector;

/**
 * 连接器手动凭据表单字段（Octop {@code credential_fields[]} 的扁平化形态）。
 *
 * <p>只在 catalog 里确实声明了字段的条目上存在（当前 5 条：dingtalk-cli/obsidian-cli/
 * ragflow/weknora/dify）；前端抽屉按此渲染，缺省时回落到自有通用表单。
 *
 * @param key         字段键（提交到 POST /credentials 的 fields 里）
 * @param label       中文标签
 * @param fieldType   text | password | url | tags
 * @param placeholder 占位提示
 * @param help        帮助文案（可空）
 * @param required    是否必填
 * @param secret      是否敏感（前端按密码框渲染；后端统一加密落库）
 */
public record ConnectorCredentialField(
        String key,
        String label,
        String fieldType,
        String placeholder,
        String help,
        boolean required,
        boolean secret
) {
}
