# Kilim 核心组件详解

## 1. Task（任务）

Task 是 Kilim 中的基本执行单元，类似于线程但更轻量级。每个 Task 包含一个 Fiber 对象，用于管理其执行状态。

### 关键特性

- **轻量级**：Task 比传统线程更节省资源，可以创建成千上万个 Task 而不会耗尽系统资源
- **可暂停**：Task 可以在执行过程中暂停，并在稍后恢复执行
- **基于消息传递**：Task 通过 Mailbox 进行通信，避免共享状态和锁竞争

### 主要方法

- `start()`：启动任务
- `pause()`：暂停当前任务
- `yield()`：让出 CPU，允许其他任务运行
- `join()`：等待任务完成

### 使用示例

```java
public class MyTask extends Task<Object> {
    private Mailbox<String> mailbox;

    public MyTask(Mailbox<String> mailbox) {
        this.mailbox = mailbox;
    }

    @Override
    public void execute() throws Pausable, Exception {
        String msg = mailbox.get(); // 暂停直到有消息可用
        System.out.println("Received: " + msg);
    }
}
```

### 内部实现

Task 类包含以下关键字段：

- `fiber`：管理任务的执行状态
- `pauseReason`：记录任务暂停的原因
- `running`：标记任务是否正在运行
- `scheduler`：任务所属的调度器

## 2. Fiber（纤程）

Fiber 是 Kilim 的核心组件，负责管理和存储续体栈。它通过状态栈（stateStack）来保存和恢复执行状态，实现任务的暂停和恢复。

### 关键特性

- **状态管理**：Fiber 维护一个状态栈，保存每个激活帧的状态
- **程序计数器**：Fiber 维护一个程序计数器（pc），用于在恢复执行时跳转到正确的位置
- **异常处理**：Fiber 支持异常处理，能够在异常发生时正确恢复执行状态

### 主要方法

- `down()`：进入下一个方法调用
- `up()`：从方法调用返回
- `upEx()`：在异常发生时恢复执行状态
- `setState()`：设置当前状态
- `getState()`：获取当前状态

### 状态转换

Fiber 支持以下状态：

- `NOT_PAUSING__NO_STATE`：正常返回，无需恢复状态
- `NOT_PAUSING__HAS_STATE`：正常返回，需要恢复状态
- `PAUSING__NO_STATE`：暂停，无需保存状态
- `PAUSING__HAS_STATE`：暂停，已保存状态

### 内部实现

Fiber 类包含以下关键字段：

- `stateStack`：状态栈，保存每个激活帧的状态
- `iStack`：状态栈的索引
- `curState`：当前帧的状态
- `pc`：程序计数器
- `isPausing`：标记是否正在暂停
- `task`：关联的 Task

## 3. Mailbox（邮箱）

Mailbox 是 Kilim 中任务间通信的基础组件，支持生产者-消费者模式。它提供了多种实现，适用于不同的使用场景。

### 类型

1. **Mailbox**：多生产者单消费者
   - 适用于多个生产者向一个消费者发送消息的场景
   - 使用同步机制保证线程安全

2. **MailboxSPSC**：单生产者单消费者
   - 适用于单个生产者向单个消费者发送消息的场景
   - 使用无锁算法，性能更高

3. **MailboxMPSC**：多生产者单消费者（高性能版本）
   - 适用于多个生产者向一个消费者发送消息的场景
   - 使用无锁算法和填充技术，减少伪共享

### 主要方法

- `get()`：阻塞获取消息，如果没有消息则暂停
- `getnb()`：非阻塞获取消息，立即返回
- `put()`：阻塞发送消息，如果邮箱已满则暂停
- `putnb()`：非阻塞发送消息，立即返回

### 事件机制

Mailbox 支持事件机制，当邮箱状态改变时通知订阅者：

- `SPACE_AVAILABLE`：邮箱有空间可用
- `MSG_AVAILABLE`：邮箱有消息可用
- `TIMED_OUT`：操作超时

### 使用示例

```java
// 创建邮箱
Mailbox<String> mailbox = new Mailbox<>();

// 生产者任务
class Producer extends Task<Object> {
    private Mailbox<String> mailbox;

    public Producer(Mailbox<String> mailbox) {
        this.mailbox = mailbox;
    }

    @Override
    public void execute() throws Pausable, Exception {
        for (int i = 0; i < 10; i++) {
            mailbox.put("Message " + i);
            Thread.sleep(1000);
        }
    }
}

// 消费者任务
class Consumer extends Task<Object> {
    private Mailbox<String> mailbox;

    public Consumer(Mailbox<String> mailbox) {
        this.mailbox = mailbox;
    }

    @Override
    public void execute() throws Pausable, Exception {
        while (true) {
            String msg = mailbox.get();
            System.out.println("Received: " + msg);
        }
    }
}
```

## 4. Scheduler（调度器）

Scheduler 负责管理可运行的任务，并将它们分配给工作线程执行。Kilim 提供了多种调度器实现，适用于不同的使用场景。

### 类型

1. **AffineScheduler**：基于亲和性的调度器
   - 每个工作线程维护自己的任务队列
   - 任务倾向于在同一个线程上执行，减少缓存未命中
   - 适用于 CPU 密集型任务

2. **ForkJoinScheduler**：基于 ForkJoin 框架的调度器
   - 使用 ForkJoin 框架的工作窃取算法
   - 适用于可以分解为子任务的并行计算

3. **NioSelectorScheduler**：用于 NIO 操作的调度器
   - 管理 NIO 选择器和通道
   - 适用于 IO 密集型任务

### 主要方法

- `schedule(Task t)`：调度一个任务
- `schedule(int index, Task t)`：调度一个任务到指定的工作线程
- `scheduleTimer(Timer t)`：调度一个定时器
- `idledown()`：等待所有任务完成并关闭调度器
- `shutdown()`：关闭调度器

### 使用示例

```java
// 创建调度器
Scheduler scheduler = new AffineScheduler(4); // 4个工作线程

// 创建任务并设置调度器
MyTask task = new MyTask(mailbox);
task.setScheduler(scheduler);

// 启动任务
task.start();

// 等待所有任务完成
scheduler.idledown();
```

## 5. 字节码编织（Weaving）

Kilim 通过字节码编织技术，在编译时或运行时修改 Java 字节码，以支持可暂停的方法。

### 组件

1. **Weaver**：编织工具的主入口
   - 提供命令行接口和 API
   - 支持编译时和运行时编织

2. **ClassWeaver**：类级别的字节码编织
   - 分析类的结构
   - 生成状态类
   - 修改类的字节码

3. **MethodWeaver**：方法级别的字节码编织
   - 分析方法的控制流和数据流
   - 识别暂停点
   - 插入状态保存和恢复代码

### 编织过程

1. 分析类文件，识别 pausable 方法
2. 为每个 pausable 方法生成状态类
3. 在方法中插入代码，保存和恢复执行状态
4. 修改方法签名，添加 Fiber 参数
5. 生成新的字节码

### 使用方式

1. **编译时编织**：
   ```bash
   java kilim.tools.Weaver -d ./classes ./classes
   ```

2. **运行时编织**：
   ```bash
   java -javaagent:kilim.jar your.MainClass
   ```

3. **使用 Kilim 运行**：
   ```bash
   java kilim.tools.Kilim your.MainClass
   ```

## 6. HTTP 服务器

Kilim 提供了一个基于协程的 HTTP 服务器实现，可以高效处理大量并发连接。

### 特性

- **协程支持**：每个 HTTP 连接由一个协程处理，不会阻塞线程
- **NIO 支持**：使用 Java NIO 处理网络 IO
- **高性能**：可以处理大量并发连接

### 主要类

- `HttpServer`：HTTP 服务器
- `HttpSession`：HTTP 会话
- `HttpRequest`：HTTP 请求
- `HttpResponse`：HTTP 响应

### 使用示例

```java
// 创建 HTTP 服务器
HttpServer server = new HttpServer(8080, new HttpSession.StringRouter((req, resp) -> {
    resp.setBody("Hello, Kilim!");
    return true;
}));

// 服务器会自动启动并监听 8080 端口
```

## 7. 并发工具

Kilim 提供了一系列高性能的并发工具类，用于构建高效的并发系统。

### 类型

1. **SPSCQueue**：单生产者单消费者队列
   - 使用无锁算法
   - 适用于单个生产者向单个消费者发送消息的场景

2. **MPSCQueue**：多生产者单消费者队列
   - 使用无锁算法和填充技术
   - 适用于多个生产者向单个消费者发送消息的场景

3. **VolatileLongCell**：基于 volatile 的长整型单元格
   - 提供原子读写操作
   - 适用于需要可见性但不需要原子性的场景

### 使用示例

```java
// 创建队列
SPSCQueue<String> queue = new SPSCQueue<>(100);

// 生产者
queue.offer("Message 1");
queue.offer("Message 2");

// 消费者
String msg = queue.poll();
```

## 总结

Kilim 的核心组件共同构建了一个高效的并发编程框架，使得开发者能够以更简单、更高效的方式编写并发代码。通过协程、续体、纤程、Actor 模型和消息传递，Kilim 提供了一种不同于传统线程模型的并发编程方式，特别适合处理 IO 密集型和事件驱动的应用。
