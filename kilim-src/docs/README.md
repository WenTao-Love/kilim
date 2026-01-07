# Kilim 项目文档

## 项目概述

Kilim 是一个为 Java 虚拟机（JVM）设计的协程、续体、纤程、Actor 模型和消息传递框架。它通过字节码编织（bytecode weaving）技术，在 Java 中实现了轻量级的并发编程模型，使得开发者能够以更简单、更高效的方式编写并发代码。

### 核心特性

1. **协程（Coroutines）**：支持可暂停和恢复的执行流，类似于 Python 的 yield 或 Go 的 goroutine
2. **续体（Continuations）**：能够捕获和恢复程序的执行状态
3. **纤程（Fibers）**：轻量级的用户级线程，比传统线程更节省资源
4. **Actor 模型**：基于消息传递的并发模型，避免共享状态和锁竞争
5. **消息传递**：通过 Mailbox 实现任务间的异步通信

## 项目结构

```
kilim-src/
├── libs/                      # 依赖库目录
├── pom.xml                    # Maven 项目配置文件
├── src/                       # 源代码目录
│   ├── main/
│   │   ├── java/
│   │   │   └── kilim/        # 主要源代码
│   │   │       ├── analysis/ # 字节码分析和编织
│   │   │       ├── concurrent/# 并发工具类
│   │   │       ├── http/     # HTTP 服务器支持
│   │   │       ├── nio/      # NIO 支持
│   │   │       ├── timerservice/ # 定时器服务
│   │   │       └── tools/    # 工具类
│   │   └── resources/        # 资源文件
│   └── test/                 # 测试代码
└── docs/                     # 项目文档（本目录）
```

## 核心组件

### 1. Task（任务）
Task 是 Kilim 中的基本执行单元，类似于线程但更轻量级。每个 Task 包含一个 Fiber 对象，用于管理其执行状态。Task 必须实现一个 pausable 的 execute 方法。

**关键类**：`kilim.Task`

### 2. Fiber（纤程）
Fiber 是 Kilim 的核心组件，负责管理和存储续体栈。它通过状态栈（stateStack）来保存和恢复执行状态，实现任务的暂停和恢复。

**关键类**：`kilim.Fiber`

### 3. Mailbox（邮箱）
Mailbox 是 Kilim 中任务间通信的基础组件，支持生产者-消费者模式。它提供了多种实现：
- `Mailbox`：多生产者单消费者
- `MailboxSPSC`：单生产者单消费者
- `MailboxMPSC`：多生产者单消费者（高性能版本）

**关键类**：`kilim.Mailbox`, `kilim.MailboxSPSC`, `kilim.MailboxMPSC`

### 4. Scheduler（调度器）
Scheduler 负责管理可运行的任务，并将它们分配给工作线程执行。Kilim 提供了多种调度器实现：
- `AffineScheduler`：基于亲和性的调度器
- `ForkJoinScheduler`：基于 ForkJoin 框架的调度器
- `NioSelectorScheduler`：用于 NIO 操作的调度器

**关键类**：`kilim.Scheduler`, `kilim.AffineScheduler`, `kilim.ForkJoinScheduler`, `kilim.nio.NioSelectorScheduler`

### 5. 字节码编织（Weaving）
Kilim 通过字节码编织技术，在编译时或运行时修改 Java 字节码，以支持可暂停的方法。主要组件包括：
- `Weaver`：编织工具的主入口
- `ClassWeaver`：类级别的字节码编织
- `MethodWeaver`：方法级别的字节码编织

**关键类**：`kilim.tools.Weaver`, `kilim.analysis.ClassWeaver`, `kilim.analysis.MethodWeaver`

### 6. HTTP 服务器
Kilim 提供了一个基于协程的 HTTP 服务器实现，可以高效处理大量并发连接。

**关键类**：`kilim.http.HttpServer`, `kilim.http.HttpSession`

### 7. 并发工具
Kilim 提供了一系列高性能的并发工具类，包括：
- `SPSCQueue`：单生产者单消费者队列
- `MPSCQueue`：多生产者单消费者队列
- `VolatileLongCell`：基于 volatile 的长整型单元格

**关键类**：`kilim.concurrent.SPSCQueue`, `kilim.concurrent.MPSCQueue`

## 技术细节

### 字节码编织原理

Kilim 使用 ASM 库来分析和修改 Java 字节码。当一个方法被标记为 pausable（通过声明抛出 Pausable 异常）时，Kilim 的编织器会：

1. 分析方法的控制流和数据流
2. 识别所有可能暂停的点（调用其他 pausable 方法的地方）
3. 在这些点插入代码，保存当前执行状态到 Fiber 的状态栈
4. 修改方法，使其能够从保存的状态恢复执行

这种技术使得 Java 方法可以在不阻塞线程的情况下暂停和恢复，从而实现高效的并发。

### 调度机制

Kilim 的调度器采用工作窃取（work-stealing）算法，每个工作线程维护一个任务队列。当一个线程完成自己的任务后，它可以从其他线程的队列中"窃取"任务来执行，从而提高 CPU 利用率。

### 消息传递机制

Kilim 的 Mailbox 实现了生产者-消费者模式，支持阻塞和非阻塞操作。当邮箱为空时，消费者可以暂停（pause）等待，直到有消息到达；当邮箱已满时，生产者可以暂停等待，直到有空间可用。

## 使用示例

### 基本任务

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

### 启动任务

```java
public static void main(String[] args) {
    Mailbox<String> mailbox = new Mailbox<>();
    MyTask task = new MyTask(mailbox);
    task.start(); // 启动任务

    mailbox.put("Hello, Kilim!"); // 发送消息
}
```

## 编译和运行

### 编译项目

```bash
mvn clean compile
```

### 字节码编织

Kilim 需要对 pausable 方法进行字节码编织才能正常运行。可以使用以下方式：

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

## 许可证

Kilim 采用 MIT 许可证，详见项目根目录下的 License 文件。

## 贡献者

- Sriram Srinivasan（原作者）
- nqzero（维护者）

## 参考资料

- [GitHub 仓库](https://github.com/nqzero/kilim)
- [邮件列表](https://groups.google.com/forum/#!forum/kilimthreads)
