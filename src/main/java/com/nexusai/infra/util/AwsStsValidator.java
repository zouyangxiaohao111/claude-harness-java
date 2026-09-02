package com.nexusai.infra.util;

import java.util.Map;

/**
 * AwsStsValidator · 对齐 CC utils/aws.ts (validation 部分).
 *
 * <p>L1 语义: AWS STS assume-role 输出 type guard + CredentialsProviderError 检测。
 * <ul>
 *   <li>{@code isAwsCredentialsProviderError(err)} — err.name === 'CredentialsProviderError'</li>
 *   <li>{@code isValidAwsStsOutput(obj)} — type guard: obj.Credentials 包含 3 非空字符串字段</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态 method (isAwsCredentialsProviderError + isValidAwsStsOutput) + AwsCredentials record</li>
 *   <li><b>A2 Golden Trace</b>: err.name='CredentialsProviderError' → true;err={name:'X'} → false;valid obj → true;missing AccessKeyId → false</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output;no I/O</li>
 *   <li><b>A4 边界</b>: null obj→false;non-object→false;empty string fields→false</li>
 *   <li><b>A5 业务场景</b>: AWS Bedrock auth refresh 验证 STS output;err classification</li>
 * </ul>
 *
 * <p>L3 升级: TS type guard `obj is AwsStsOutput` → Java instanceof check + record cast;
 * TS typeof string → Java instanceof String;
 * TS optional chaining → Java null-safe field check.
 */
public final class AwsStsValidator {

    public record AwsCredentials(
        String AccessKeyId,
        String SecretAccessKey,
        String SessionToken,
        String Expiration) {}

    public record AwsStsOutput(AwsCredentials Credentials) {}

    private AwsStsValidator() {}

    /**
     * Returns true iff {@code err} is an AWS SDK CredentialsProviderError.
     * CC uses duck-typing on the {@code name} field.
     */
    public static boolean isAwsCredentialsProviderError(Object err) {
        if (err == null) return false;
        if (!(err instanceof Map)) return false;
        Object name = ((Map<?, ?>) err).get("name");
        return "CredentialsProviderError".equals(name);
    }

    /**
     * Returns true iff {@code obj} is a structurally valid AWS STS assume-role output.
     */
    public static boolean isValidAwsStsOutput(Object obj) {
        if (obj == null || !(obj instanceof Map)) return false;
        Map<?, ?> output = (Map<?, ?>) obj;
        Object credentials = output.get("Credentials");
        if (!(credentials instanceof Map)) return false;
        Map<?, ?> creds = (Map<?, ?>) credentials;
        Object accessKey = creds.get("AccessKeyId");
        Object secretKey = creds.get("SecretAccessKey");
        Object sessionToken = creds.get("SessionToken");
        return accessKey instanceof String s1 && !s1.isEmpty()
            && secretKey instanceof String s2 && !s2.isEmpty()
            && sessionToken instanceof String s3 && !s3.isEmpty();
    }
}
