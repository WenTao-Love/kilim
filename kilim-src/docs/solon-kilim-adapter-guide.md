# Solon-Server Kilim 适配实现指南

## 核心难点分析

### 1. 协程与Solon框架的集成难点

**问题**: Solon框架基于传统线程模型，而Kilim基于协程模型

**挑战**:
- Solon的Handler调用可能在协程中执行
- 需要确保Solon的Bean访问在协程上下文中安全
- 需要处理Pausable异常的传播

**解决方案**: 
```java
package solon.kilim;

import kilim.Pausable;
import kilim.Task;
import solon.core.BeanWrap;
import solon.core.AppContext;

/**
 * Solon协程任务包装器
 * 在Kilim协程中安全执行Solon Handler
 */
public class SolonPausableTask extends Task<Object> {
    private final SolonKilimContext context;
    private final solon.core.handle.Handler handler;
    private final AppContext solonContext;

    public SolonPausableTask(AppContext solonContext,
                              solon.core.handle.Handler handler,
                              SolonKilimContext context) {
        this.solonContext = solonContext;
        this.handler = handler;
        this.context = context;
    }

    @Override
    public void execute() throws Pausable, Exception {
        // 在协程中执行Solon Handler
        try {
            Object result = handler.handle(context);

            // 渲染结果
            if (result != null) {
                renderResult(result, context);
            }
        } catch (Pausable p) {
            // 重新抛出Pausable，让Kilim处理
            throw p;
        } catch (Exception e) {
            // 其他异常转换为HTTP错误
            handleError(e, context);
        }
    }

    private void renderResult(Object result, SolonKilimContext context) 
            throws Pausable {
        kilim.http.HttpResponse resp = context.getKilimResponse();

        if (result instanceof String) {
            resp.getOutputStream().write(((String) result).getBytes());
        } else if (result instanceof byte[]) {
            resp.getOutputStream().write((byte[]) result);
        } else {
            // 使用Solon的JSON渲染
            String json = solon.core.render.JsonRender.render(result);
            resp.setContentType("application/json");
            resp.getOutputStream().write(json.getBytes());
        }
    }

    private void handleError(Exception e, SolonKilimContext context) {
        kilim.http.HttpResponse resp = context.getKilimResponse();
        resp.status = kilim.http.HttpResponse.ST_INTERNAL_SERVER_ERROR;
        resp.setContentType("application/json");
        String error = "{"error":"" + e.getMessage() + ""}";
        resp.getOutputStream().write(error.getBytes());
    }
}
```

### 2. 路由系统的适配难点

**问题**: Solon的路由注解系统与Kilim的路由机制不兼容

**挑战**:
- Solon使用注解定义路由
- Kilim使用StringRouter接口
- 需要在运行时动态注册路由

**解决方案**:
```java
package solon.kilim;

import kilim.http.HttpSession;
import kilim.http.HttpRequest;
import kilim.http.HttpResponse;
import solon.core.AppContext;
import solon.core.handle.Handler;
import solon.core.handle.Router;
import solon.core.handle.Gateway;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Method;

/**
 * Solon路由适配器
 * 将Solon的注解路由转换为Kilim的StringRouter
 */
public class SolonRouterAdapter {
    private final AppContext solonContext;
    private final Map<String, RouteInfo> routeMap = new HashMap<>();
    private final List<RouteInfo> wildcardRoutes = new ArrayList<>();

    public SolonRouterAdapter(AppContext solonContext) {
        this.solonContext = solonContext;
        scanRoutes();
    }

    /**
     * 扫描Solon路由
     */
    private void scanRoutes() {
        // 获取所有Handler
        Map<Class<?>, BeanWrap<?>> beans = solonContext.getBeanWraps();

        for (BeanWrap<?> beanWrap : beans.values()) {
            Class<?> beanClass = beanWrap.clz();

            // 检查是否是Controller
            if (isController(beanClass)) {
                scanControllerRoutes(beanWrap.raw());
            }
        }
    }

    /**
     * 检查是否是Controller
     */
    private boolean isController(Class<?> clazz) {
        return clazz.getAnnotation(solon.core.annotation.Controller.class) != null;
    }

    /**
     * 扫描Controller路由
     */
    private void scanControllerRoutes(Object controller) {
        Class<?> clazz = controller.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            // 检查路由注解
            solon.core.annotation.Mapping mapping = 
                method.getAnnotation(solon.core.annotation.Mapping.class);

            if (mapping != null) {
                continue;
            }

            // 创建路由信息
            RouteInfo routeInfo = createRouteInfo(controller, method, mapping);

            // 注册路由
            if (routeInfo.path.contains("*")) {
                wildcardRoutes.add(routeInfo);
            } else {
                routeMap.put(routeInfo.method + ":" + routeInfo.path, routeInfo);
            }
        }
    }

    /**
     * 创建路由信息
     */
    private RouteInfo createRouteInfo(Object controller, 
                                      Method method,
                                      solon.core.annotation.Mapping mapping) {
        RouteInfo info = new RouteInfo();

        // 解析路径和方法
        info.path = mapping.value();
        info.method = mapping.method();
        info.controller = controller;
        info.handlerMethod = method;

        return info;
    }

    /**
     * 匹配路由
     */
    public Handler match(HttpRequest req) {
        String key = req.method + ":" + req.uriPath;

        // 1. 精确匹配
        RouteInfo route = routeMap.get(key);
        if (route != null) {
            return createHandler(route, req);
        }

        // 2. 通配符匹配
        for (RouteInfo wildcardRoute : wildcardRoutes) {
            if (matchesWildcard(wildcardRoute.path, req.uriPath)) {
                return createHandler(wildcardRoute, req);
            }
        }

        return null;
    }

    /**
     * 通配符匹配
     */
    private boolean matchesWildcard(String pattern, String path) {
        String[] patternParts = pattern.split("/");
        String[] pathParts = path.split("/");

        if (patternParts.length != pathParts.length) {
            return false;
        }

        for (int i = 0; i < patternParts.length; i++) {
            String patternPart = patternParts[i];
            String pathPart = pathParts[i];

            if ("*".equals(patternPart)) {
                continue;
            }

            if (!patternPart.equals(pathPart)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 创建Handler
     */
    private Handler createHandler(RouteInfo route, HttpRequest req) {
        return ctx -> {
            try {
                // 解析路径参数
                Map<String, String> pathParams = 
                    parsePathParams(route.path, req.uriPath);

                // 解析查询参数
                Map<String, String> queryParams = 
                    req.getQueryComponents().toMap();

                // 设置到上下文
                if (ctx instanceof SolonKilimContext) {
                    SolonKilimContext kctx = (SolonKilimContext) ctx;
                    kctx.setPathParams(pathParams);
                    kctx.setQueryParams(queryParams);
                }

                // 调用Handler方法
                return route.handlerMethod.invoke(
                    route.controller, 
                    buildArgs(route, pathParams, queryParams, ctx)
                );

            } catch (Exception e) {
                throw new RuntimeException("Handler execution failed", e);
            }
        };
    }

    /**
     * 构建方法参数
     */
    private Object[] buildArgs(RouteInfo route, 
                              Map<String, String> pathParams,
                              Map<String, String> queryParams,
                              Object context) throws Exception {
        Method method = route.handlerMethod;
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];

            if (Context.class.isAssignableFrom(paramType)) {
                args[i] = context;
            } else if (String.class.isAssignableFrom(paramType)) {
                // 从路径参数或查询参数获取
                args[i] = getParamValue(method, i, pathParams, queryParams);
            } else {
                args[i] = null;
            }
        }

        return args;
    }

    /**
     * 获取参数值
     */
    private String getParamValue(Method method, int index,
                                Map<String, String> pathParams,
                                Map<String, String> queryParams) {
        // 检查@Param注解
        java.lang.annotation.Annotation[] annotations = 
            method.getParameterAnnotations()[index];

        for (java.lang.annotation.Annotation ann : annotations) {
            if (ann instanceof solon.core.annotation.Param) {
                solon.core.annotation.Param param = 
                    (solon.core.annotation.Param) ann;

                // 先从路径参数获取
                String value = pathParams.get(param.value());
                if (value != null) {
                    return value;
                }

                // 再从查询参数获取
                return queryParams.get(param.value());
            }
        }

        return null;
    }

    /**
     * 路由信息
     */
    private static class RouteInfo {
        String path;
        String method;
        Object controller;
        Method handlerMethod;
    }
}
```

### 3. HTTP请求/响应的适配难点

**问题**: Solon的Context接口与Kilim的HttpRequest/HttpResponse不兼容

**挑战**:
- 需要延迟解析参数
- 需要支持Cookie和Session
- 需要处理文件上传
- 需要支持流式响应

**解决方案**:
```java
package solon.kilim;

import kilim.http.HttpRequest;
import kilim.http.HttpResponse;
import solon.core.Context;

/**
 * Solon-Kilim上下文适配器
 * 实现Solon的Context接口，底层使用Kilim的HttpRequest/HttpResponse
 */
public class SolonKilimContext implements Context {
    private final HttpRequest kilimRequest;
    private final HttpResponse kilimResponse;

    // 延迟解析的参数
    private java.util.Map<String, String> queryParams;
    private java.util.Map<String, String> pathParams;
    private java.util.Map<String, String> headers;
    private java.util.Map<String, String> cookies;

    // 文件上传
    private java.util.Map<String, solon.core.upload.Uploading> files;

    public SolonKilimContext(HttpRequest request, HttpResponse response) {
        this.kilimRequest = request;
        this.kilimResponse = response;
    }

    // ========== 延迟解析实现 ==========

    public void setPathParams(java.util.Map<String, String> params) {
        this.pathParams = params;
    }

    public void setQueryParams(java.util.Map<String, String> params) {
        this.queryParams = params;
    }

    @Override
    public String param(String name) {
        // 1. 从路径参数获取
        if (pathParams != null && pathParams.containsKey(name)) {
            return pathParams.get(name);
        }

        // 2. 从查询参数获取
        if (queryParams == null) {
            parseQueryParams();
        }
        return queryParams.get(name);
    }

    @Override
    public String cookie(String name) {
        if (cookies == null) {
            parseCookies();
        }
        return cookies.get(name);
    }

    @Override
    public void sessionSet(String name, Object value) {
        // 设置Session
        kilim.http.Cookie cookie = new kilim.http.Cookie(name, 
            String.valueOf(value));
        cookie.setPath("/");
        cookie.setMaxAge(3600); // 1小时

        String setCookie = cookie.toSetCookieHeader();
        kilimResponse.addField("Set-Cookie", setCookie);
    }

    @Override
    public Object sessionGet(String name) {
        // 从Cookie获取Session
        String sessionCookie = cookie(name);
        if (sessionCookie != null) {
            return null;
        }

        // 解析Session（简化实现）
        return sessionCookie;
    }

    // ========== 辅助方法 ==========

    private void parseQueryParams() {
        if (queryParams != null) {
            queryParams = new java.util.HashMap<>();

            kilim.http.KeyValues kv = kilimRequest.getQueryComponents();
            for (int i = 0; i < kv.count; i++) {
                queryParams.put(kv.keys[i], kv.values[i]);
            }
        }
    }

    private void parseCookies() {
        if (cookies != null) {
            cookies = new java.util.HashMap<>();

            String cookieHeader = kilimRequest.getHeader("Cookie");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                String[] cookiePairs = cookieHeader.split(";");
                for (String pair : cookiePairs) {
                    String[] kv = pair.trim().split("=", 2);
                    if (kv.length == 2) {
                        cookies.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
        }
    }

    // ========== Getter方法 ==========

    public HttpRequest getKilimRequest() {
        return kilimRequest;
    }

    public HttpResponse getKilimResponse() {
        return kilimResponse;
    }

    @Override
    public String path() {
        return kilimRequest.uriPath;
    }

    @Override
    public String method() {
        return kilimRequest.method;
    }

    @Override
    public String header(String name) {
        if (headers == null) {
            parseHeaders();
        }
        return headers.get(name);
    }

    private void parseHeaders() {
        if (headers != null) {
            headers = new java.util.HashMap<>();
            for (int i = 0; i < kilimRequest.nFields; i++) {
                headers.put(kilimRequest.keys[i], 
                    kilimRequest.extractRange(kilimRequest.valueRanges[i]));
            }
        }
    }
}
```

### 4. Bean访问的协程安全难点

**问题**: Solon的Bean访问机制在协程中可能不安全

**挑战**:
- 协程可能在不同线程执行
- Bean的ThreadLocal可能失效
- 需要确保Bean访问的线程安全

**解决方案**:
```java
package solon.kilim;

import solon.core.AppContext;
import solon.core.BeanWrap;

/**
 * Solon协程Bean访问器
 * 确保在协程中安全访问Solon Bean
 */
public class SolonFiberBeanAccessor {
    private final AppContext solonContext;
    private final ThreadLocal<SolonFiberContext> fiberContext = 
        new ThreadLocal<>();

    public SolonFiberBeanAccessor(AppContext solonContext) {
        this.solonContext = solonContext;
    }

    /**
     * 获取Bean
     */
    public <T> T get(Class<T> clazz) {
        SolonFiberContext ctx = fiberContext.get();

        if (ctx == null) {
            // 在协程中，使用当前协程上下文
            ctx = new SolonFiberContext();
            fiberContext.set(ctx);
        }

        // 从协程上下文获取Bean
        return ctx.getBean(clazz);
    }

    /**
     * 获取Bean包装器
     */
    public <T> BeanWrap<T> getWrap(Class<T> clazz) {
        SolonFiberContext ctx = fiberContext.get();

        if (ctx == null) {
            ctx = new SolonFiberContext();
            fiberContext.set(ctx);
        }

        return ctx.getBeanWrap(clazz);
    }

    /**
     * 协程上下文
     */
    private static class SolonFiberContext {
        private final java.util.Map<Class<?>, Object> beans = 
            new java.util.HashMap<>();
        private final java.util.Map<Class<?>, BeanWrap<?>> wraps = 
            new java.util.HashMap<>();

        public <T> T getBean(Class<T> clazz) {
            Object bean = beans.get(clazz);
            if (bean == null) {
                // 从Solon上下文获取Bean
                bean = solon.Solon.context().getBean(clazz);
                beans.put(clazz, bean);
            }
            return (T) bean;
        }

        public <T> BeanWrap<T> getBeanWrap(Class<T> clazz) {
            BeanWrap<?> wrap = wraps.get(clazz);
            if (wrap == null) {
                // 从Solon上下文获取Bean包装器
                wrap = solon.Solon.context().getBeanWrap(clazz);
                wraps.put(clazz, wrap);
            }
            return (BeanWrap<T>) wrap;
        }
    }
}
```

## 集成示例

### Solon应用集成

```java
package solon.kilim.example;

import solon.Solon;
import solon.core.annotation.Controller;
import solon.core.annotation.Get;
import solon.core.annotation.Mapping;
import solon.core.annotation.Param;
import solon.core.Context;

/**
 * Solon Controller示例
 */
@Controller
public class DemoController {

    @Get
    @Mapping("/hello")
    public String hello(@Param("name") String name) {
        return "Hello, " + name + "!";
    }

    @Get
    @Mapping("/user/*")
    public User getUser(String id) {
        // id是路径参数
        return new User(id, "User " + id);
    }

    @Get
    @Mapping("/info")
    public Map<String, Object> getInfo(Context ctx) {
        Map<String, Object> info = new java.util.HashMap<>();
        info.put("path", ctx.path());
        info.put("method", ctx.method());
        info.put("userAgent", ctx.userAgent());
        return info;
    }

    static class User {
        String id;
        String name;

        User(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
```

### 启动Solon-Kilim适配器

```java
package solon.kilim.example;

import solon.Solon;
import solon.kilim.SolonKilimAdapter;
import solon.kilim.SolonKilimConfig;

/**
 * Solon-Kilim启动类
 */
public class SolonKilimApp {
    public static void main(String[] args) {
        // 1. 启动Solon
        Solon.start(args);

        // 2. 创建配置
        SolonKilimConfig config = new SolonKilimConfig();
        config.setPort(8080);
        config.setScheduler(kilim.Scheduler.getDefaultScheduler());

        // 3. 创建适配器
        SolonKilimAdapter adapter = new SolonKilimAdapter(
            Solon.context(),
            config
        );

        // 4. 启动适配器
        adapter.start();

        System.out.println("Solon-Kilim server started on port 8080");
    }
}
```

## 性能优化建议

### 1. 协程池优化

```java
package solon.kilim;

import kilim.Scheduler;
import kilim.Task;

/**
 * Solon协程任务池
 * 复用协程任务，减少GC压力
 */
public class SolonFiberPool {
    private final ThreadLocal<SolonPausableTask> taskPool = 
        ThreadLocal.withInitial(() -> null);

    public SolonPausableTask obtain(
            solon.core.handle.Handler handler,
            SolonKilimContext context) {
        SolonPausableTask task = taskPool.get();

        if (task == null) {
            task = new SolonPausableTask(
                solon.Solon.context(),
                handler,
                context
            );
            taskPool.set(task);
        } else {
            task.reset(handler, context);
        }

        return task;
    }

    public void recycle(SolonPausableTask task) {
        task.reset(null, null);
    }
}
```

### 2. 请求处理优化

```java
package solon.kilim;

import kilim.http.HttpRequest;

/**
 * Solon请求缓存
 * 缓存常用请求数据，减少重复解析
 */
public class SolonRequestCache {
    private static final ThreadLocal<java.util.Map<String, String>> paramCache = 
        ThreadLocal.withInitial(java.util.HashMap::new);

    public static String getCachedParam(HttpRequest req, String name) {
        // 生成缓存键
        String cacheKey = req.method + ":" + req.uriPath + ":" + name;

        java.util.Map<String, String> cache = paramCache.get();
        String value = cache.get(cacheKey);

        if (value == null) {
            // 从请求获取参数
            value = getQueryParam(req, name);
            cache.put(cacheKey, value);
        }

        return value;
    }

    private static String getQueryParam(HttpRequest req, String name) {
        kilim.http.KeyValues kv = req.getQueryComponents();
        for (int i = 0; i < kv.count; i++) {
            if (name.equals(kv.keys[i])) {
                return kv.values[i];
            }
        }
        return null;
    }
}
```

## 总结

本方案解决了Solon-Server与Kilim集成的核心难点：

1. **协程集成**: 通过SolonPausableTask安全地在协程中执行Solon Handler
2. **路由适配**: 通过SolonRouterAdapter将Solon注解路由转换为Kilim路由
3. **上下文适配**: 通过SolonKilimContext实现Solon的Context接口
4. **Bean安全**: 通过SolonFiberBeanAccessor确保协程中安全访问Bean
5. **性能优化**: 通过协程池和请求缓存提升性能

所有实现都遵循以下原则：
- 不修改Solon和Kilim原有代码
- 通过适配器模式实现集成
- 保持API完全兼容
- 充分利用Kilim协程优势
