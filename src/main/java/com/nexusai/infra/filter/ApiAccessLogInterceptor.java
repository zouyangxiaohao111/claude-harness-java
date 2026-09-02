package com.nexusai.infra.filter;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.StopWatch;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.nexusai.infra.util.ServletUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * API 访问日志 Interceptor
 *
 * 目的：在非 prod 环境时，打印 request 和 response 两条日志到日志文件（控制台）中。
 *
 * <p>[access-log 可配置] 是否打印由 {@link ApiAccessLogProperties}（yml
 * {@code nexusai.access-log}）控制：enabled=false 或请求 URI 命中 exclude-uris
 * 前缀时跳过「开始请求 / 完成请求」两条日志（用于排除前端 2s 轮询端点刷屏）。
 *
 * @author zhengchangwei
 */
@Component
public class ApiAccessLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessLogInterceptor.class);

    public static final String ATTRIBUTE_HANDLER_METHOD = "HANDLER_METHOD";

    private static final String ATTRIBUTE_STOP_WATCH = "ApiAccessLogInterceptor.StopWatch";

    /**
     * 访问日志配置（@Component + @ConfigurationProperties 注入，prefix=nexusai.access-log）。
     */
    private final ApiAccessLogProperties properties;

    public ApiAccessLogInterceptor(ApiAccessLogProperties properties) {
        this.properties = properties;
    }

    /**
     * 是否打印本请求访问日志：enabled=true 且 URI 未命中任何 exclude 前缀。
     */
    private boolean shouldLog(HttpServletRequest request) {
        return properties.isEnabled() && !isExcluded(request.getRequestURI());
    }

    /**
     * 前缀匹配：URI 以 excludeUris 任一 pattern 开头即命中。
     */
    private boolean isExcluded(String uri) {
        List<String> excludeUris = properties.getExcludeUris();
        if (excludeUris == null || excludeUris.isEmpty() || uri == null) {
            return false;
        }
        for (String pattern : excludeUris) {
            if (pattern != null && !pattern.isEmpty() && uri.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // [access-log 可配置] 未命中日志条件（enabled=false / URI 命中 exclude 前缀）：
        // 跳过「开始请求」日志与 StopWatch 计时，但 return true 保持请求链继续。
        if (!shouldLog(request)) {
            return true;
        }

        // 记录 HandlerMethod，提供给 ApiAccessLogFilter 使用
        HandlerMethod handlerMethod = handler instanceof HandlerMethod ? (HandlerMethod) handler : null;
        if (handlerMethod != null) {
            request.setAttribute(ATTRIBUTE_HANDLER_METHOD, handlerMethod);
        }

        // 打印 request 日志
        //if (!SpringUtils.isProd()) {
        Map<String, String> queryString = ServletUtils.getParamMap(request);
        String requestBody = ServletUtils.isJsonRequest(request) ? ServletUtils.getBody(request) : null;

        // 打印 Controller 路径
        //String controller = printHandlerMethodPosition(handlerMxethod);

        if (CollUtil.isEmpty(queryString) && StrUtil.isEmpty(requestBody)) {
            log.info("\t[开始请求] URL: {}, IP: {}, 无参数", request.getRequestURI(), ServletUtils.getClientIP(request));
        } else {
            log.info("\t[开始请求] URL: {}, IP: {}, 参数: ({})", request.getRequestURI(), ServletUtils.getClientIP(request),
                    StrUtil.blankToDefault(requestBody, queryString.toString()));
        }
        // 计时
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        request.setAttribute(ATTRIBUTE_STOP_WATCH, stopWatch);

        //}
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // [access-log 可配置] 与 preHandle 同源判定（enabled / exclude-uris），
        // 命中即跳过「完成请求」日志（preHandle 同样跳过，StopWatch 未设置，无空指针）。
        if (!shouldLog(request)) {
            return;
        }

        // 打印 response 日志
        //if (!SpringUtils.isProd()) {
        StopWatch stopWatch = (StopWatch) request.getAttribute(ATTRIBUTE_STOP_WATCH);
        stopWatch.stop();
        log.info("\t[完成请求] URL: {}, IP: {}, 耗时:{} ms",
                request.getRequestURI(), ServletUtils.getClientIP(request), stopWatch.getTotalTimeMillis());
        //}
    }

    /**
     * 打印 Controller 方法路径
     */
    private String printHandlerMethodPosition(HandlerMethod handlerMethod) {
        if (handlerMethod == null) {
            return "";
        }

        try {
            Method method = handlerMethod.getMethod();
            Class<?> clazz = method.getDeclaringClass();

            // 使用类加载器直接获取源码路径
            URL resourceUrl = clazz.getProtectionDomain().getCodeSource().getLocation();
            String classPath = resourceUrl.getPath();

            // 转换到源码路径（处理多模块情况）
            String baseDir = new File(classPath).getParentFile().getParentFile().getParent();
            String classPackage = clazz.getPackageName().replace('.', '/');
            String sourceFileRelPath = "/src/main/java/" + classPackage + "/" + clazz.getSimpleName() + ".java";

            // 查找实际的源码文件
            File sourceFile = findActualSourceFile(baseDir, sourceFileRelPath);

            // 处理测试目录情况
            if (sourceFile == null || !sourceFile.exists()) {
                sourceFileRelPath = "/src/test/java/" + classPackage + "/" + clazz.getSimpleName() + ".java";
                sourceFile = findActualSourceFile(baseDir, sourceFileRelPath);
            }

            if (sourceFile == null || !sourceFile.exists()) {
                return ""; // 源文件未找到
            }

            // 定位代码行号
            List<String> sourceLines = FileUtil.readUtf8Lines(sourceFile);

            for (int i = 0; i < sourceLines.size(); i++) {
                String line = sourceLines.get(i);
                // 简单匹配
                if (line.contains("public") && line.contains(method.getName())) {
                    return String.format(" (%s : %d)", clazz.getSimpleName() + "." + method.getName(), i + 1);
                }
            }

            return ""; // 方法未找到
        } catch (Exception e) {
            return ""; // 错误处理
        }
    }

    /**
     * 在项目结构中递归查找源文件
     * @param baseDir
     * @param relPath
     * @return
     */
    private File findActualSourceFile(String baseDir, String relPath) {
        // 在项目根目录查找
        File baseFile = new File(baseDir + relPath);
        if (baseFile.exists()) return baseFile;

        // 在多模块项目中查找（递归查找所有子模块）
        File projectDir = new File(baseDir);
        File[] modules = projectDir.listFiles(File::isDirectory);
        if (modules != null) {
            for (File module : modules) {
                File moduleFile = new File(module, relPath);
                if (moduleFile.exists()) return moduleFile;
            }
        }

        return null;
    }



}