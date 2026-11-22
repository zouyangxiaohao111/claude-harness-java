package com.zjky.pro.framework.common.exception.enums;

import com.zjky.pro.framework.common.exception.ErrorCode;

/**
 * 通用错误码常量（数字码 + 中文提示）。
 *
 * <p>取值与 {@code com.zjky.boot:zjky-common} 的
 * {@code com.zjky.pro.framework.common.exception.enums.GlobalErrorCodeConstants} 一致：
 * 0 成功；400/401/403/404/405/423/429/500/501/502 与 HTTP 语义同值；900 段为业务通用
 * （900 重复请求、901 演示模式禁止写、902 业务数据已存在）；999 未知。
 *
 * <p>包名带 {@code enums} 与上游 jar 完全一致，后期替换为依赖时本文件整体删除即可。
 */
public interface GlobalErrorCodeConstants {

    ErrorCode SUCCESS = new ErrorCode(0, "成功");

    ErrorCode BAD_REQUEST = new ErrorCode(400, "请求参数不正确");

    ErrorCode UNAUTHORIZED = new ErrorCode(401, "账号未登录");

    ErrorCode FORBIDDEN = new ErrorCode(403, "没有该操作权限");

    ErrorCode NOT_FOUND = new ErrorCode(404, "请求未找到");

    ErrorCode METHOD_NOT_ALLOWED = new ErrorCode(405, "请求方法不正确");

    ErrorCode LOCKED = new ErrorCode(423, "请求失败，请稍后重试");

    ErrorCode TOO_MANY_REQUESTS = new ErrorCode(429, "请求过于频繁，请稍后重试");

    ErrorCode INTERNAL_SERVER_ERROR = new ErrorCode(500, "系统异常");

    ErrorCode NOT_IMPLEMENTED = new ErrorCode(501, "功能未实现/未开启");

    ErrorCode ERROR_CONFIGURATION = new ErrorCode(502, "错误的配置项");

    ErrorCode REPEATED_REQUESTS = new ErrorCode(900, "重复请求，请稍后重试");

    ErrorCode DEMO_DENY = new ErrorCode(901, "演示模式，禁止写操作");

    ErrorCode INSERT_DUPLICATE_KEY = new ErrorCode(902, "业务数据已存在,不允许插入!");

    ErrorCode IP_NOT_IN_WHITELIST = new ErrorCode(403, "IP 不在白名单");

    ErrorCode UNKNOWN = new ErrorCode(999, "未知错误");
}
