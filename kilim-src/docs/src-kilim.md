# Kilim 包源码解析

## 概述

`kilim` 包是 Kilim 框架的核心包，提供了协程（Fiber）编程的基础设施。该包包含了任务调度、协程管理、消息传递、事件处理等核心功能，是整个框架的基础。

## 包结构

```
kilim
├── AffineScheduler.java      // 仿射调度器
├── Cell.java              // 单元素缓冲区
├── Constants.java          // 常量定义
├── Continuation.java      // 协程延续
├── Event.java             // 事件对象
├── EventPublisher.java    // 事件发布者接口
├── EventSubscriber.java    // 事件订阅者接口
├── ExitMsg.java           // 退出消息
├── Fiber.java             // 协程核心类
├── ForkJoinScheduler.java  // 分支合并调度器
├── Generator.java          // 生成器
├── KilimClassLoader.java  // Kilim类加载器
├── KilimException.java    // Kilim异常
├── Mailbox.java           // 邮箱（多生产者单消费者）
├── MailboxMPSC.java      // 多生产者单消费者邮箱
├── MailboxSPSC.java      // 单生产者多消费者邮箱
├── NotPausable.java       // 非可暂停异常
├── Pausable.java         // 可暂停异常
├── PauseReason.java       // 暂停原因
├── ReentrantLock.java     // 可重入锁
├── RingQueue.java         // 环形队列
├── Scheduler.java         // 调度器抽象
├── ServletHandler.java    // Servlet处理器
├── ShutdownException.java  // 关闭异常
├── State.java            // 状态对象
├── Task.java             // 任务抽象
├── TaskDoneReason.java   // 任务完成原因
├── TaskGroup.java        // 任务组
├── WeavingClassLoader.java // 织入类加载器
└── YieldReason.java       // 让出原因
```

## 核心类详解

### 1. AffineScheduler 类

#### 类定义

```java
public class AffineScheduler extends Scheduler implements ThreadFactory
```

`AffineScheduler` 是基于线程池的调度器实现，采用仿射（Affine）调度策略。

#### 核心字段

```java
protected Executor [] exes;              // 执行器数组
protected AtomicInteger index;            // 当前索引
protected AtomicInteger count;            // 任务计数
protected TimerService timerService;      // 定时器服务
```

#### 核心方法

1. **构造方法**
```java
public AffineScheduler(int numThreads, int queueSize)
```
- 创建指定数量的执行器
- 设置队列大小
- 初始化定时器服务

2. **schedule 方法**
```java
public void schedule(int index, Task t)
```
- 调度任务到指定执行器
- 支持轮询分配

3. **shutdown 方法**
```java
public void shutdown()
```
- 关闭所有执行器
- 停止定时器服务

#### 内部类：Executor

```java
protected class Executor extends ThreadPoolExecutor implements WatchdogContext
```
- 单线程执行器
- 实现看门狗上下文
- 管理待处理任务

### 2. Cell 类

#### 类定义

```java
public class Cell<T> implements PauseReason, EventPublisher
```

`Cell` 是单元素缓冲区，支持多生产者和单消费者。

#### 核心字段

```java
Queue<EventSubscriber> srcs;              // 生产者队列
VolatileReferenceCell<EventSubscriber> sink;  // 消费者
VolatileReferenceCell<T> message;          // 消息
```

#### 核心方法

1. **get 方法**
```java
public T get(EventSubscriber eo)
```
- 非阻塞获取消息
- 注册消息可用监听器

2. **put 方法**
```java
public boolean put(T msg, EventSubscriber eo)
```
- 非阻塞放入消息
- 注册空间可用监听器

3. **get/put 变体**
- `getnb()` - 非阻塞获取
- `get(long timeout)` - 带超时获取
- `putnb()` - 非阻塞放入
- `putb()` - 阻塞放入

### 3. Constants 接口

#### 类定义

```java
public interface Constants extends Opcodes
```

`Constants` 定义了 Kilim 框架使用的所有常量。

#### 常量分类

**版本信息：**
```java
String KILIM_VERSION = "1.0";
int KILIM_ASM = ASM7;
```

**类型描述符：**
```java
String D_BOOLEAN = "Z";
String D_INT = "I";
String D_LONG = "J";
String D_OBJECT = "Ljava/lang/Object;";
// ... 等等
```

**Kilim 特定类型：**
```java
String D_FIBER = "Lkilim/Fiber;";
String D_STATE = "Lkilim/State;";
String D_TASK = "Lkilim/Task;";
String D_PAUSABLE = "Lkilim/Pausable;";
```

**操作码：**
```java
int ILOAD_0 = 26;
int LLOAD_0 = 30;
// ... 等等
```

### 4. Continuation 类

#### 类定义

```java
public abstract class Continuation implements Fiber.Worker
```

`Continuation` 是协程延续，提供对事件循环的显式控制。

#### 核心方法

1. **run 方法**
```java
public boolean run() throws NotPausable
```
- 执行一次 execute()
- 管理协程状态
- 返回是否完成

2. **execute 方法**
```java
public void execute() throws Pausable, Exception
```
- 用户覆盖此方法
- 实现业务逻辑

3. **reset 方法**
```java
public void reset()
```
- 重置延续和协程
- 清除异常状态

#### 使用场景

- 状态机实现
- 生成器模式
- 事件循环移植

### 5. Event 类

#### 类定义

```java
public class Event
```

`Event` 表示事件类型，用于事件系统。

#### 核心字段

```java
public final int eventType;
```

#### 使用说明

- 事件类型 1-1000 为 Kilim 保留
- 自定义事件类型应避免冲突
- 建议使用项目名称的辅音 ASCII 码

### 6. EventPublisher 接口

#### 接口定义

```java
public interface EventPublisher
```

`EventPublisher` 是事件发布者标记接口。

#### 用途

- 标识事件源
- 支持事件订阅机制
- 用于 Cell、Mailbox 等类

### 7. EventSubscriber 接口

#### 接口定义

```java
public interface EventSubscriber {
    void onEvent(EventPublisher ep, Event e);
}
```

`EventSubscriber` 是事件订阅者接口。

#### 用途

- 接收事件通知
- 实现观察者模式
- 用于暂停/恢复机制

### 8. Fiber 类

#### 类定义

```java
public final class Fiber
```

`Fiber` 是协程的核心类，管理和存储延续栈。

#### 核心字段

```java
public State curState;              // 当前状态
public int pc;                     // 程序计数器
private State[] stateStack;        // 状态栈
private int iStack;                // 栈索引
boolean isPausing;               // 是否暂停中
boolean isDone;                 // 是否完成
public Task task;                  // 所属任务
```

#### 核心方法

1. **栈管理**
```java
public Fiber down()      // 下移栈
public int up()         // 上移栈
public int upEx()      // 异常后上移栈
```

2. **状态管理**
```java
public void setState(State state)    // 设置状态
public State getState()           // 获取状态
```

3. **静态方法**
```java
public static void yield() throws Pausable              // 让出控制
public static void yield(Fiber f)                    // 让出指定协程
public static void pause(Fiber f)                    // 暂停协程
```

#### Worker 接口

```java
public interface Worker {
    void execute() throws Pausable, Exception;
    void execute(kilim.Fiber fiber) throws Exception;
}
```

协程工作者接口，用于生成代码调用。

### 9. Generator 类

#### 类定义

```java
public class Generator<T> extends Continuation implements Iterator<T>, Iterable<T>
```

`Generator` 是生成器，提供类似迭代器的接口但自动管理栈。

#### 核心方法

1. **hasNext 方法**
```java
public boolean hasNext()
```
- 检查是否有下一个值
- 自动执行 run()

2. **next 方法**
```java
public T next()
```
- 获取下一个值
- 自动执行 run()

3. **yield 方法**
```java
public void yield(T val) throws Pausable
```
- 产生值并让出控制
- 简化生成器实现

#### 使用示例

```java
class StringGenerator extends Generator<String> {
    public void execute() throws Pausable {
        while (!done) {
            String s = getNextWord();
            yield(s);
        }
    }
}
```

### 10. KilimClassLoader 类

#### 类定义

```java
public class KilimClassLoader extends ClassLoader
```

`KilimClassLoader` 提供对受保护方法的访问。

#### 核心方法

```java
public boolean isLoaded(String className)
```
- 检查类是否已加载
- 使用 findLoadedClass()

### 11. KilimException 类

#### 类定义

```java
public class KilimException extends RuntimeException
```

Kilim 框架的异常基类。

#### 构造方法

```java
public KilimException(String msg)
```

### 12. Mailbox 类

#### 类定义

```java
public class Mailbox<T> implements PauseReason, EventPublisher
```

`Mailbox` 是类型化缓冲区，支持多生产者和单消费者。

#### 核心字段

```java
T[] msgs;                    // 消息数组
private int iprod;              // 生产者索引
private int icons;              // 消费者索引
private int numMsgs;            // 消息数量
private int maxMsgs;            // 最大消息数
EventSubscriber sink;          // 消费者订阅者
```

#### 核心方法

1. **get 方法**
```java
public T get(EventSubscriber eo)
```
- 获取消息
- 注册消息可用监听器

2. **put 方法**
```java
public boolean put(T msg, EventSubscriber eo)
```
- 放入消息
- 注册空间可用监听器

3. **select 方法**
```java
public static int select(Mailbox... mboxes) throws Pausable
```
- 从多个邮箱中选择
- 阻塞直到有消息可用

4. **辅助方法**
```java
public boolean hasMessage()      // 是否有消息
public boolean hasSpace()        // 是否有空间
public int size()               // 当前大小
public T peek(int idx)         // 查看消息
public T remove(int idx)        // 移除消息
```

### 13. Pausable 类

#### 类定义

```java
public class Pausable extends Exception
```

`Pausable` 是可暂停异常，用于协程暂停机制。

#### 内部接口

1. **Spawn 接口**
```java
public interface Spawn<TT> {
    TT execute() throws Pausable, Exception;
}
```

2. **Fork 接口**
```java
public interface Fork {
    void execute() throws Pausable, Exception;
}
```

3. **Pfun/Psumer 接口**
```java
public interface Pfun<XX, YY, EE extends Throwable> { 
    YY apply(XX obj) throws Pausable, EE; 
}
```

#### 静态方法

```java
public static <XX, EE extends Throwable> XX apply(XX obj, Psumer<XX, EE> func)
public static <XX, EE extends Throwable> XX apply(XX obj, Psumer<XX, EE> func1, Psumer<XX, EE> func2)
```

### 14. Scheduler 类

#### 类定义

```java
public abstract class Scheduler
```

`Scheduler` 是调度器抽象基类，定义了任务调度的接口。

#### 核心字段

```java
public static volatile Scheduler defaultScheduler;  // 默认调度器
public static volatile Scheduler pinnableScheduler; // 可固定调度器
public static int defaultNumberThreads;         // 默认线程数
private static final ThreadLocal<Task> taskMgr_;  // 任务管理器
protected AtomicBoolean shutdown;               // 关闭标志
private Logger logger;                          // 日志记录器
```

#### 核心方法

1. **schedule 方法**
```java
public void schedule(Task t)
public abstract void schedule(int index, Task t)
public abstract void scheduleTimer(Timer t)
```
- 调度任务
- 支持索引分配
- 调度定时器

2. **生命周期方法**
```java
public void shutdown()
public boolean isShutdown()
public abstract boolean isEmptyish()
public abstract int numThreads()
public abstract void idledown()
```

3. **工厂方法**
```java
public static Scheduler make(int numThreads)
public static Scheduler getDefaultScheduler()
public static Scheduler getDefaultPinnable()
```

#### Logger 接口

```java
public interface Logger {
    void log(Object source, Object problem);
}
```

### 15. Task 类

#### 类定义

```java
public abstract class Task<TT> implements Runnable, EventSubscriber, Fiber.Worker
```

`Task` 是任务抽象类，代表一个轻量级线程（包含自己的栈）。

#### 核心字段

```java
public final int id;                    // 任务ID
protected Fiber fiber;                   // 协程实例
protected PauseReason pauseReason;       // 暂停原因
protected AtomicBoolean running;         // 运行状态
protected volatile boolean done;           // 完成标志
volatile int preferredResumeThread;       // 首选恢复线程
int numActivePins;                    // 活动固定数
private LinkedList<Mailbox<ExitMsg<TT>>> exitMBs;  // 退出邮箱
protected Scheduler scheduler;              // 调度器
public kilim.timerservice.Timer timer;   // 定时器
```

#### 核心方法

1. **生命周期方法**
```java
public Task<TT> start()                    // 启动任务
public boolean resume()                       // 恢复任务
public void shutdown()                        // 关闭任务
```

2. **暂停/恢复方法**
```java
public static void yield() throws Pausable
public static void yield(Fiber f)
public static void pause(PauseReason pauseReason) throws Pausable
public static void pause(PauseReason pauseReason, Fiber f)
```

3. **退出方法**
```java
public static void exit(Object aExitValue) throws Pausable
public static void errorExit(Throwable ex) throws Pausable
public ExitMsg<TT> join() throws Pausable
public ExitMsg<TT> joinb() throws Pausable
```

4. **内部类**

**Spawn 类**
```java
public static class Spawn<TT> extends Task<TT>
```
- 封装可暂停执行体

**Fork 类**
```java
public static class Fork extends Task
```
- 封装可暂停执行体

**Invoke 类**
```java
public static class Invoke<TT> extends Task<TT>
```
- 封装方法调用

### 16. State 类

#### 类定义

```java
public class State
```

`State` 是状态对象基类，用于存储激活帧的状态。

#### 核心字段

```java
public int pc;          // 程序计数器
public Object self;     // 自引用
```

#### 用途

- 存储局部变量和操作数栈元素
- 在协程恢复时使用
- 由 ClassWeaver 生成的自定义状态类继承

## 工作原理

### 1. 协程调度流程

```
任务创建 → 调度器分配 → 执行器执行 → 协程运行 → 暂停/恢复 → 完成
```

### 2. 消息传递机制

```
生产者 → Mailbox/Cell → 消费者
         ↓
    事件通知（EventPublisher/EventSubscriber）
```

### 3. 暂停/恢复机制

```
协程运行 → 调用 pause() → 抛出 Pausable → 调度器捕获 → 恢复执行
```

### 4. 栈管理

```
方法调用 → Fiber.down() → 保存状态 → 执行 → Fiber.up() → 恢复状态 → 返回
```

## 设计特点

### 1. 轻量级线程

- Task 包含自己的栈
- 不依赖操作系统线程
- 快速上下文切换

### 2. 协作式多任务

- 单线程执行多个任务
- 显式让出控制
- 高效资源利用

### 3. 消息传递

- 类型化消息缓冲
- 事件驱动通知
- 支持多生产者/消费者

### 4. 可扩展性

- 抽象调度器接口
- 可插拔的执行器
- 灵活的事件系统

## 使用场景

### 1. 基本协程使用

```java
class MyTask extends Task {
    public void execute() throws Pausable {
        while (true) {
            // 执行工作
            String result = doWork();
            // 发送结果
            resultBox.put(result);
            // 让出控制
            Task.yield();
        }
    }
}
```

### 2. 生成器模式

```java
class NumberGenerator extends Generator<Integer> {
    public void execute() throws Pausable {
        int n = 0;
        while (n < 10) {
            yield(n);
            n++;
        }
    }
}

// 使用
for (int num : new NumberGenerator()) {
    System.out.println(num);
}
```

### 3. 消息传递

```java
// 生产者
Mailbox<String> mailbox = new Mailbox<>();
mailbox.put("Hello");

// 消费者
String msg = mailbox.get();
System.out.println(msg);
```

### 4. 任务组合

```java
Task.fork(() -> {
    // 子任务1
    Task.fork(() -> {
        // 子任务2
    Task.fork(() -> {
            // 子任务3
        });
    });
});
```

## 注意事项

1. **织入要求**
   - 使用 Task 的类必须经过织入
   - 使用 Weaver 或 Kilim 工具
   - 确保类路径正确

2. **调度器选择**
   - 默认使用 AffineScheduler
   - 可通过系统属性配置
   - 注意线程数设置

3. **异常处理**
   - Pausable 用于正常暂停
   - KilimException 用于错误情况
   - 正确处理退出消息

4. **线程安全**
   - 使用原子操作
   - 注意可重入性
   - 避免死锁

## 总结

`kilim` 包提供了完整的协程编程基础设施，包括任务调度、协程管理、消息传递、事件处理等核心功能。其设计体现了轻量级、高效、可扩展的原则，通过协程实现了用户态线程的协作式多任务，是 Kilim 框架的核心所在。
