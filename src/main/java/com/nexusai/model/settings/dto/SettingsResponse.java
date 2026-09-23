package com.nexusai.model.settings.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * GET /api/v1/settings 的<b>响应包装</b>：内层 {@link SettingsDto} 摊平 + 两个只读运行期标识。
 *
 * <p><b>为什么用 {@link JsonUnwrapped}（而不是给 SettingsDto 加 record 组件）</b>：
 * {@code SettingsDto} 被 GET 与 PUT <b>共用</b>（PUT 的入参就是它），全仓另有 12 处
 * {@code new SettingsDto(...)} 调用点 —— 往里加组件会连锁改 12 处，且会让 PUT 侧看到
 * 「这两个字段可写」。改用一个只服务 GET 的包装类 + {@code @JsonUnwrapped} 把内层摊平后：
 * 出站 JSON 形态与改造前<b>逐字相同</b>（顶层仍是 theme / fontSize / … 这些既有键），
 * 仅多出 appName / configHome 两个键 ⇒ 前端既有 AppSettings 加两个可选字段即可，
 * {@code SettingsDto} 零改动、PUT 零改动。
 *
 * <p><b>为什么这两个字段结构上只读</b>：写侧
 * {@code SettingsController.update} 的入参类型仍是 {@link SettingsDto}（本类不出现在任何
 * 入参位置）⇒ 前端即便把 appName / configHome 回传，也<b>没有字段承接</b>
 * （Jackson 按未知字段忽略，见 JacksonAutoConfiguration 的 FAIL_ON_UNKNOWN_PROPERTIES=false），
 * PUT 永远写不动它们。值只由后端从 {@code NexusaiPaths} 现取。
 *
 * <p><b>appName = nexusai（默认）时值与旧行为一致</b>：appName 来自
 * {@code spring.application.name}，本仓生产值即 {@code nexusai} ⇒
 * {@code configHome = {user.home}/.nexusai}，与改造前 GET 的既有字段一字不变，
 * 仅新增 {@code appName:"nexusai"} / {@code configHome:"<user.home>/.nexusai"} 两个只读键。
 *
 * @param settings   内层用户全局设置（{@code @JsonUnwrapped} ⇒ 其字段与 appName/configHome 同级扁平出站）
 * @param appName    当前应用名 = 自有根目录名去掉前导点（如 {@code nexusai} / {@code nexusai-scene}）·
 *                   取值 {@code NexusaiPaths.getAppName()}
 * @param configHome 当前自有配置根绝对路径（{@code {user.home}/.{appName}}，NFC 归一化）·
 *                   取值 {@code NexusaiPaths.getAppConfigHomeDir()}
 */
public record SettingsResponse(
    @JsonUnwrapped SettingsDto settings,
    String appName,
    String configHome
) {}
