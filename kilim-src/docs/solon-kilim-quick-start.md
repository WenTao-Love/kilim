# Solon-Server Kilim 快速启动指南

## 快速开始

### 1. 项目结构

```
solon-kilim-adapter/
├── solon/
│   ├── SolonKilimAdapter.java      // 核心适配器
│   ├── SolonKilimContext.java      // 上下文适配
│   ├── SolonRouterAdapter.java       // 路由适配
│   └── SolonPausableTask.java       // 协程任务
└── example/
    └── DemoController.java          // 示例控制器
```

### 2. 最小化配置

```java
package example;

import solon.Solon;
import solon.annotation.Controller;
import solon.annotation.Mapping;
import solon.annotation.Get;
import solon.annotation.Post;
import solon.core.render.JsonRender;

/**
 * 示例控制器
 */
@Controller
public class DemoController {

    @Get
    @Mapping("/hello")
    public String hello() {
        return "Hello from Solon-Kilim!";
    }

    @Get
    @Mapping("/user/:id")
    public String getUser(String id) {
        return "User ID: " + id;
    }

    @Post
    @Mapping("/user")
    public String createUser(String name) {
        return "Created user: " + name;
    }

    @Get
    @Mapping("/json")
    public User getUser() {
        User user = new User();
        user.id = 1;
        user.name = "test";
        return JsonRender.render(user);
    }

    static class User {
        public int id;
        public String name;
    }
}
```

### 3. 启动类

```java
package example;

import solon.Solon;
import solon.kilim.SolonKilimAdapter;
import solon.kilim.SolonKilimConfig;

/**
 * Solon-Kilim启动类
 */
public class SolonKilimApp {
    public static void main(String[] args) {
        // 1. 启动Solon
        Solon app = Solon.start(DemoController.class);

        // 2. 创建Kilim适配器
        SolonKilimConfig config = new SolonKilimConfig();
        config.setPort(8080);

        SolonKilimAdapter adapter = new SolonKilimAdapter(
            Solon.context(),
            config
        );

        // 3. 启动适配器
        adapter.start();

        System.out.println("Solon-Kilim server started on port 8080");
    }
}
```

## 核心使用说明

### 1. 控制器开发

使用Solon的标准注解开发控制器：

```java
@Controller
public class MyController {

    @Get
    @Mapping("/api/users")
    public List<User> listUsers() {
        return userService.getAll();
    }

    @Get
    @Mapping("/api/users/:id")
    public User getUser(String id) {
        return userService.getById(id);
    }

    @Post
    @Mapping("/api/users")
    public User createUser(String name) {
        return userService.create(name);
    }

    @Put
    @Mapping("/api/users/:id")
    public User updateUser(String id, String name) {
        return userService.update(id, name);
    }

    @Delete
    @Mapping("/api/users/:id")
    public void deleteUser(String id) {
        userService.delete(id);
    }
}
```

### 2. 参数绑定

Solon-Kilim支持多种参数绑定方式：

```java
@Controller
public class ParamController {

    // 路径参数
    @Get
    @Mapping("/user/:id")
    public String getUser(@Param("id") String id) {
        return "User: " + id;
    }

    // 查询参数
    @Get
    @Mapping("/search")
    public String search(@Param("q") String query,
                       @Param("page") int page) {
        return "Search: " + query + ", page: " + page;
    }

    // 请求体参数
    @Post
    @Mapping("/user")
    public String createUser(@Body User user) {
        return "Created: " + user.name;
    }

    // 上下文参数
    @Get
    @Mapping("/info")
    public String getInfo(Context ctx) {
        return "IP: " + ctx.remoteIp();
    }
}
```

### 3. 响应渲染

支持多种响应格式：

```java
@Controller
public class ResponseController {

    // 文本响应
    @Get
    @Mapping("/text")
    public String text() {
        return "Plain text response";
    }

    // JSON响应
    @Get
    @Mapping("/json")
    public Object json() {
        Map<String, Object> data = new HashMap<>();
        data.put("message", "Hello");
        data.put("time", System.currentTimeMillis());
        return data;
    }

    // 自定义渲染
    @Get
    @Mapping("/custom")
    public void custom(Context ctx) {
        ctx.contentType("application/xml");
        ctx.output("<root><message>Hello</message></root>");
    }
}
```

## 配置说明

### 1. 基本配置

```java
SolonKilimConfig config = new SolonKilimConfig();

// 端口配置
config.setPort(8080);

// 调度器配置
config.setScheduler(kilim.Scheduler.getDefaultScheduler());

// 启用压缩
config.setCompressionEnabled(true);
config.setCompressionThreshold(1024);

// 连接配置
config.setMaxConnections(10000);
config.setKeepAliveTimeout(30000);
config.setConnectionTimeout(60000);
```

### 2. 高级配置

```java
// SSL配置
config.setSslEnabled(true);
config.setKeystorePath("/path/to/keystore.jks");
config.setKeystorePassword("password");

// 速率限制
config.setRateLimitEnabled(true);
config.setRateLimitPerSecond(1000);
config.setRateLimitBurstSize(100);

// 日志配置
config.setLoggingEnabled(true);
config.setLogLevel("INFO");
config.setLogFormat("combined");

// 中间件配置
config.addMiddleware(new LoggingMiddleware());
config.addMiddleware(new CorsMiddleware());
config.addMiddleware(new AuthMiddleware());
```

## 性能优化建议

### 1. 连接池优化

```java
// 配置连接池
config.setConnectionPoolEnabled(true);
config.setMaxIdleConnections(100);
config.setMinIdleConnections(10);
config.setConnectionValidationInterval(30000);
```

### 2. 响应压缩

```java
// 启用压缩
config.setCompressionEnabled(true);
config.setCompressionType("gzip");
config.setCompressionLevel(6); // 0-9，默认6
config.setCompressionThreshold(1024); // 大于1KB才压缩
```

### 3. 协程优化

```java
// 配置协程栈大小
config.setFiberStackSize(64 * 1024); // 64KB

// 配置协程调度策略
config.setSchedulerType("affine"); // affine或forkjoin
config.setSchedulerThreads(Runtime.getRuntime().availableProcessors());
```

## 常见问题

### 1. 路由不生效

**问题**: 控制器方法没有被调用

**解决**:
- 检查Controller类是否有@Controller注解
- 检查方法是否有@Mapping注解
- 检查路径是否正确
- 查看日志确认路由是否注册

### 2. 参数绑定失败

**问题**: 参数值为null

**解决**:
- 检查@Param注解的value是否正确
- 检查路径参数名是否匹配
- 检查查询参数名是否正确
- 使用Context.param()调试参数

### 3. 响应格式错误

**问题**: 响应格式不符合预期

**解决**:
- 检查返回类型
- 使用JsonRender.render()返回JSON
- 使用Context.contentType()设置内容类型
- 检查字符编码设置

### 4. 协程异常

**问题**: Pausable异常传播

**解决**:
- 不要在控制器方法中捕获Pausable异常
- 使用try-catch包装Handler调用
- 确保异常正确传播

## 总结

本指南提供了Solon-Server Kilim适配的快速开始方法：

1. **使用Solon标准注解**开发控制器
2. **最小化配置**快速启动服务器
3. **遵循Solon编程模型**处理请求和响应
4. **利用Kilim协程优势**提升性能

通过本指南，您可以在5分钟内启动一个Solon-Kilim服务器！
