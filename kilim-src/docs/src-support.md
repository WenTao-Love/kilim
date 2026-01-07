# Kilim Support 包源码解析

## 概述

`kilim.support` 包提供了 Kilim 框架与第三方服务器集成的支持类。目前该包包含 `JettyHandler` 类，用于将 Kilim 的协程（Fiber）能力集成到 Jetty Web 服务器中，实现基于协程的异步 HTTP 请求处理。

## 核心类：JettyHandler

### 类定义

```java
public class JettyHandler extends AbstractHandler
```

`JettyHandler` 继承自 Jetty 的 `AbstractHandler`，是 Jetty 服务器的请求处理器，它将传统的 Jetty 请求处理与 Kilim 的协程模型结合起来。

### 主要功能

`JettyHandler` 的主要功能是：
1. 接收 Jetty 服务器的 HTTP 请求
2. 将请求处理委托给 Kilim 的协程（Task/Fiber）
3. 通过异步上下文（AsyncContext）管理请求的生命周期
4. 提供简洁的接口供用户实现业务逻辑

### 类结构

#### 1. 成员变量

```java
Iface handler;
```

- 类型: `Iface` 接口
- 描述: 用户自定义的请求处理器
- 作用: 封装实际的业务逻辑处理

#### 2. 构造方法

```java
public JettyHandler(Iface handler) { this.handler = handler; }
```

- 参数: `handler` - 用户实现的请求处理器
- 描述: 创建 JettyHandler 实例并注入用户处理器

#### 3. 核心方法：handle

```java
public void handle(final String target, final Request br, 
                   final HttpServletRequest req, 
                   final HttpServletResponse resp) 
    throws IOException, ServletException
```

**参数说明：**
- `target`: 请求的目标路径
- `br`: Jetty 的 Request 对象
- `req`: Servlet 标准的 HttpServletRequest 对象
- `resp`: Servlet 标准的 HttpServletResponse 对象

**执行流程：**

1. **启动异步上下文**
   ```java
   final AsyncContext async = req.startAsync();
   ```
   - 启用 Servlet 3.0 的异步处理功能
   - 允许请求在原始线程之外继续处理

2. **创建并启动协程**
   ```java
   new kilim.Task() {
       public void execute() throws Pausable, Exception {
           // 处理逻辑
       }
   }.start();
   ```
   - 创建一个匿名 Task 实例
   - 在协程中执行请求处理
   - `start()` 方法启动协程

3. **协程内部处理**
   ```java
   try {
       String result = handler.handle(target, br, req, resp);
       if (result != null) resp.getOutputStream().print(result);
   }
   catch (Exception ex) { 
       resp.sendError(500, "the server encountered an error"); 
   }
   br.setHandled(true);
   async.complete();
   ```

   - 调用用户处理器处理请求
   - 将处理结果写入响应
   - 捕获异常并返回 500 错误
   - 标记请求为已处理
   - 完成异步上下文

### 内部接口：Iface

```java
public interface Iface {
    String handle(String target, Request br, HttpServletRequest req, 
                  HttpServletResponse resp) throws Pausable, Exception;
}
```

**接口说明：**
- 用户需要实现此接口来定义请求处理逻辑
- 方法签名与 Jetty 的 handle 方法类似
- 关键区别在于方法声明了 `throws Pausable`，允许在协程中暂停

**使用示例：**

```java
public class MyHandler implements JettyHandler.Iface {
    @Override
    public String handle(String target, Request br, 
                        HttpServletRequest req, 
                        HttpServletResponse resp) 
        throws Pausable, Exception {

        // 可以在这里使用 Kilim 的协程特性
        Fiber.sleep(1000); // 模拟耗时操作

        return "Hello, World!";
    }
}

// 使用方式
JettyHandler handler = new JettyHandler(new MyHandler());
Server server = new Server(8080);
server.setHandler(handler);
server.start();
```

### Java7Handler 抽象类

```java
public static abstract class Java7Handler implements Iface {
    public String handle(String arg0,
            Request arg1, HttpServletRequest arg2, HttpServletResponse arg3,
            Fiber arg4)
            throws Pausable, Exception {
        return null;
    }
}
```

**类说明：**
- 这是一个适配器类，用于支持 Java 7
- 注释说明：Java 7 不支持默认接口方法，因此需要一个虚拟实现
- 这个类需要放在父类中，否则 Kilim 会拒绝织入真正的 handle 方法

**设计原因：**
- Java 8 引入了默认方法，允许在接口中提供默认实现
- Java 7 需要通过抽象类来实现类似功能
- 这个类为 Kilim 的字节码织入提供了必要的结构

## 工作原理

### 异步处理模型

`JettyHandler` 使用 Servlet 3.0 的异步处理机制：

1. **请求到达**
   - Jetty 接收 HTTP 请求
   - 调用 JettyHandler.handle() 方法

2. **启动异步**
   - 通过 `req.startAsync()` 创建 AsyncContext
   - 请求处理不再绑定到原始线程

3. **协程处理**
   - 创建新的 Task（协程）处理请求
   - 协程可以在执行过程中暂停和恢复
   - 允许在不阻塞线程的情况下等待 I/O

4. **响应完成**
   - 处理完成后调用 `async.complete()`
   - 将响应返回给客户端

### 与 Kilim 的集成

`JettyHandler` 展示了如何将 Kilim 集成到现有的 Web 框架中：

1. **协程封装**
   - 将请求处理逻辑封装在 Task 中
   - 利用 Kilim 的协程调度器管理执行

2. **暂停支持**
   - Iface 接口声明 `throws Pausable`
   - 允许在处理逻辑中使用 Fiber.sleep() 等可暂停操作

3. **资源管理**
   - 通过 AsyncContext 管理请求生命周期
   - 确保资源正确释放

## 使用场景

### 1. 异步 I/O 密集型应用

```java
public class AsyncIOHandler implements JettyHandler.Iface {
    @Override
    public String handle(String target, Request br, 
                        HttpServletRequest req, 
                        HttpServletResponse resp) 
        throws Pausable, Exception {

        // 模拟数据库查询
        String data = queryDatabase();

        // 模拟外部 API 调用
        String apiResult = callExternalAPI();

        return "Data: " + data + ", API: " + apiResult;
    }

    private String queryDatabase() throws Pausable {
        Fiber.sleep(100); // 模拟数据库查询
        return "database result";
    }

    private String callExternalAPI() throws Pausable {
        Fiber.sleep(200); // 模拟 API 调用
        return "api result";
    }
}
```

### 2. 高并发 Web 服务

```java
public class HighConcurrencyHandler implements JettyHandler.Iface {
    @Override
    public String handle(String target, Request br, 
                        HttpServletRequest req, 
                        HttpServletResponse resp) 
        throws Pausable, Exception {

        // 处理大量并发请求
        String userId = req.getParameter("userId");
        UserProfile profile = getUserProfile(userId);

        return profile.toJson();
    }

    private UserProfile getUserProfile(String userId) throws Pausable {
        // 可以并发执行多个查询
        // 而不阻塞线程
        Fiber.sleep(50);
        return new UserProfile(userId);
    }
}
```

## 优势与特点

### 1. 高效的并发处理

- 使用协程而非线程处理请求
- 减少线程上下文切换开销
- 提高系统吞吐量

### 2. 简洁的编程模型

- 提供类似同步的代码风格
- 避免回调地狱
- 易于理解和维护

### 3. 良好的集成性

- 与 Jetty 服务器无缝集成
- 兼容 Servlet 3.0 规范
- 支持标准的 HTTP 请求/响应模型

### 4. 灵活的扩展性

- 通过 Iface 接口自定义处理逻辑
- 支持复杂的业务场景
- 易于测试和重构

## 注意事项

1. **Servlet 容器要求**
   - 需要支持 Servlet 3.0 规范
   - Jetty 版本需要支持异步处理

2. **异常处理**
   - 协程中的异常需要妥善处理
   - 确保资源正确释放
   - 避免协程泄漏

3. **性能考虑**
   - 协程调度开销
   - 内存使用情况
   - 合理设置协程池大小

4. **Java 版本兼容性**
   - Java 7 需要使用 Java7Handler
   - Java 8+ 可以直接实现 Iface 接口

## 总结

`JettyHandler` 是 Kilim 框架与 Jetty Web 服务器集成的关键组件，它展示了如何将协程模型应用到 Web 服务中。通过结合 Servlet 3.0 的异步处理能力和 Kilim 的协程特性，开发者可以编写出高性能、易维护的 Web 应用程序。该组件的设计体现了 Kilim 框架的核心优势：在不牺牲代码可读性的前提下，实现高效的并发处理。
