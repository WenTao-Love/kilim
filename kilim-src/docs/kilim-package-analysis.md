# Kilim包源码详细解析

## 目录
1. [基础组件](#基础组件)
2. [核心组件](#核心组件)
3. [辅助组件](#辅助组件)
4. [工具类](#工具类)
5. [学习路径建议](#学习路径建议)

---

## 基础组件

### 1. 事件系统

#### Event.java
- **作用**：表示事件类型，用于任务间通信
- **关键属性**：
  - `eventType`：事件类型标识符（1-1000为Kilim保留）
- **设计特点**：
  - 简单的事件封装类
  - 支持自定义事件类型
  - 建议使用项目名称的前四个辅音字母作为事件类型ID，避免冲突

#### EventPublisher.java
- **作用**：事件发布者接口
- **说明**：标记接口，用于标识可以发布事件的类
- **实现类**：Mailbox、Cell等

#### EventSubscriber.java
- **作用**：事件订阅者接口
- **关键方法**：
  - `onEvent(EventPublisher ep, Event e)`：处理接收到的事件
- **实现类**：Task等

### 2. 暂停和恢复机制

#### PauseReason.java
- **作用**：定义任务暂停的原因接口
- **关键方法**：
  - `isValid(Task t)`：判断暂停原因是否仍然有效
- **说明**：用于实现任务的暂停和恢复机制

#### YieldReason.java
- **作用**：实现让出CPU的原因
- **特点**：
  - `isValid()`总是返回false，表示不需要继续暂停
  - 用于实现协作式多任务调度
  - 任务调用`yield()`时会使用此类

#### TaskDoneReason.java
- **作用**：表示任务完成的原因
- **特点**：
  - `isValid()`总是返回true，表示任务已完成
  - 包含退出对象信息
  - 用于任务间同步和通知

### 3. 状态管理

#### State.java
- **作用**：状态基类，用于保存执行状态
- **关键属性**：
  - `pc`：程序计数器，记录执行位置
  - `self`：保存对象引用
- **说明**：
  - 由ClassWeaver生成自定义状态类
  - 状态类名称包含类型信息（如S_O2I3表示2个对象和3个整型）
  - 用于保存激活帧的状态

#### Continuation.java
- **作用**：提供显式控制事件循环的能力
- **关键方法**：
  - `run()`：执行一次迭代，返回true表示完成或抛出异常
  - `execute()`：顶层入口点，需要重写
  - `reset()`：重置续体和Fiber
- **特点**：
  - 不提供调度器，由调用代码控制事件循环
  - 适用于状态机和生成器
  - 比Task更底层，需要手动管理

#### Generator.java
- **作用**：实现生成器模式，简化值序列的生成
- **关键方法**：
  - `yield(T val)`：生成一个值
  - `hasNext()`：检查是否有更多值
  - `next()`：获取下一个值
- **特点**：
  - 实现了Iterator和Iterable接口
  - 自动管理栈，无需手动管理
  - 适用于生成序列或遍历复杂数据结构

---

## 核心组件

### 1. Task（任务）

#### 概述
Task是Kilim的基本执行单元，比传统线程更轻量级。每个Task包含一个Fiber对象，用于管理其执行状态。

#### 关键属性
- `fiber`：管理任务的执行状态
- `pauseReason`：记录任务暂停的原因
- `running`：标记任务是否正在运行
- `scheduler`：任务所属的调度器
- `preferredResumeThread`：首选恢复线程
- `timer`：定时器

#### 关键方法
- `start()`：启动任务
- `pause()`：暂停当前任务
- `yield()`：让出CPU，允许其他任务运行
- `join()`：等待任务完成
- `informOnExit(Mailbox)`：任务退出时通知

#### 实现特点
1. 实现了Runnable接口，可被调度器执行
2. 实现了EventSubscriber接口，可以接收事件
3. 实现了Fiber.Worker接口，可以与Fiber协作
4. 提供了线程绑定机制，支持使用锁的任务

#### 使用场景
- 需要并发执行的任务
- 需要暂停和恢复的任务
- 需要与其他任务通信的任务

### 2. Fiber（纤程）

#### 概述
Fiber是Kilim的核心组件，负责管理和存储续体栈。它通过状态栈(stateStack)来保存和恢复执行状态，实现任务的暂停和恢复。

#### 关键属性
- `stateStack`：状态栈，保存每个激活帧的状态
- `iStack`：状态栈的索引
- `curState`：当前帧的状态
- `pc`：程序计数器
- `isPausing`：标记是否正在暂停
- `task`：关联的Task

#### 关键方法
- `down()`：进入下一个方法调用
- `up()`：从方法调用返回
- `upEx()`：在异常发生时恢复执行状态
- `setState()`：设置当前状态
- `getState()`：获取当前状态
- `reset()`：重置Fiber状态

#### 状态转换
- `NOT_PAUSING__NO_STATE`：正常返回，无需恢复状态
- `NOT_PAUSING__HAS_STATE`：正常返回，需要恢复状态
- `PAUSING__NO_STATE`：暂停，无需保存状态
- `PAUSING__HAS_STATE`：暂停，已保存状态

#### 实现特点
1. 使用状态栈管理调用层次
2. 通过程序计数器实现跳转
3. 支持异常处理
4. 提供了状态保存和恢复机制

#### 使用场景
- 需要暂停和恢复的执行流
- 需要保存和恢复状态的任务
- 需要实现协程的场景

### 3. Mailbox（邮箱）

#### 概述
Mailbox是Kilim中任务间通信的基础组件，支持生产者-消费者模式。它提供了多种实现，适用于不同的使用场景。

#### 类型

##### Mailbox.java
- **特点**：多生产者单消费者
- **实现**：使用同步机制保证线程安全
- **适用场景**：多个生产者向一个消费者发送消息

##### MailboxSPSC.java
- **特点**：单生产者单消费者
- **实现**：使用无锁算法
- **性能**：比Mailbox更高
- **适用场景**：单个生产者向单个消费者发送消息

##### MailboxMPSC.java
- **特点**：多生产者单消费者（高性能版本）
- **实现**：使用无锁算法和填充技术
- **优化**：减少伪共享
- **适用场景**：多个生产者向一个消费者发送消息，需要高性能

#### 关键方法
- `get()`：阻塞获取消息，如果没有消息则暂停
- `getnb()`：非阻塞获取消息，立即返回
- `put()`：阻塞发送消息，如果邮箱已满则暂停
- `putnb()`：非阻塞发送消息，立即返回

#### 事件机制
- `SPACE_AVAILABLE`：邮箱有空间可用
- `MSG_AVAILABLE`：邮箱有消息可用
- `TIMED_OUT`：操作超时

#### 使用示例
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

---

## 辅助组件

### 1. 调度器

#### AffineScheduler.java
- **概述**：基于亲和性的调度器
- **实现**：使用ThreadPoolExecutor
- **特点**：
  - 每个工作线程维护自己的任务队列
  - 任务倾向于在同一个线程上执行
  - 支持任务与线程的绑定
- **适用场景**：CPU密集型任务

#### ForkJoinScheduler.java
- **概述**：基于ForkJoin框架的调度器
- **实现**：使用ForkJoinPool
- **特点**：
  - 支持工作窃取算法
  - 不支持任务与线程的绑定
  - 适用于可分解为子任务的并行计算
- **适用场景**：可以分解为子任务的并行计算

### 2. 并发工具

#### Cell.java
- **概述**：单空间缓冲区
- **特点**：
  - 支持多生产者单消费者
  - 功能上等同于大小为1的Mailbox
  - 针对大小为1进行了优化
- **关键方法**：
  - `get()`：获取消息
  - `put()`：发送消息
  - `hasMessage()`：检查是否有消息
  - `hasSpace()`：检查是否有空间

#### RingQueue.java
- **概述**：环形队列实现
- **特点**：
  - 使用数组实现环形缓冲区
  - 支持动态扩容
  - 可以设置最大容量
- **关键方法**：
  - `get()`：获取元素
  - `put()`：添加元素
  - `peek()`：查看队首元素
  - `contains()`：检查是否包含元素

#### ReentrantLock.java
- **概述**：可重入锁，支持任务与线程的绑定
- **特点**：
  - 继承自java.util.concurrent.locks.ReentrantLock
  - 提供任务与线程的绑定机制
  - 支持公平和非公平锁
- **关键方法**：
  - `lock()`：获取锁
  - `unlock()`：释放锁
  - `tryLock()`：尝试获取锁
  - `preLock()`：锁定前调用
  - `checkPin()`：检查绑定状态

### 3. 其他组件

#### TaskGroup.java
- **概述**：任务组，用于管理多个任务
- **特点**：
  - 可以动态添加任务
  - 收集所有任务的退出消息
  - 等待所有任务完成
- **关键方法**：
  - `add(Task t)`：添加任务
  - `join()`：等待所有任务完成
  - `joinb()`：阻塞等待所有任务完成

#### WeavingClassLoader.java
- **概述**：编织类加载器，运行时进行字节码编织
- **特点**：
  - 加载类时自动进行字节码编织
  - 支持排除过滤器
  - 可以指定类路径
- **关键方法**：
  - `loadClass()`：加载类
  - `weaveClass()`：编织类
  - `run()`：运行静态方法
  - `exclude()`：设置排除过滤器

#### ServletHandler.java
- **概述**：Servlet处理器，支持异步Servlet
- **特点**：
  - 使用异步Servlet API
  - 每个请求由一个Task处理
  - 支持Pausable方法
- **关键方法**：
  - `service()`：处理HTTP请求
  - `handle()`：处理请求的接口

---

## 工具类

### Constants.java
- **作用**：定义常量和类型描述符
- **内容包括**：
  - 类型描述符（如D_INT、D_OBJECT等）
  - 类名（如FIBER_CLASS、TASK_CLASS等）
  - 操作码（如ILOAD_0、ALOAD_0等）
  - SAM前缀常量

### KilimException.java
- **作用**：Kilim框架的异常
- **特点**：继承自RuntimeException

### NotPausable.java
- **作用**：不可暂停异常
- **特点**：继承自RuntimeException

### ShutdownException.java
- **作用**：关闭异常
- **特点**：继承自Exception

### KilimClassLoader.java
- **作用**：扩展ClassLoader以访问findLoadedClass方法
- **特点**：
  - 提供isLoaded()方法
  - 用于检查类是否已加载

---

## 学习路径建议

### 1. 理解事件系统和暂停恢复机制
- 从Event、EventPublisher、EventSubscriber开始
- 理解PauseReason及其实现类
- 掌握暂停和恢复的基本概念

### 2. 学习Task和Fiber的核心实现
- 深入理解Task的生命周期
- 掌握Fiber的状态管理机制
- 理解状态转换和程序计数器的作用

### 3. 研究Mailbox的消息传递机制
- 理解不同类型的Mailbox及其适用场景
- 掌握事件机制的使用
- 学习生产者-消费者模式的实现

### 4. 了解调度器和并发工具
- 理解不同调度器的实现和适用场景
- 掌握Cell、RingQueue等并发工具的使用
- 学习任务与线程绑定的机制

### 5. 实践应用
- 编写简单的Task和Mailbox示例
- 实现生产者-消费者模式
- 尝试使用不同的调度器

通过这样的学习路径，你能够从基础到高级，逐步掌握Kilim框架的核心概念和实现细节，为接手这个项目打下良好的基础。
