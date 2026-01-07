# Kilim TimerService 包源码解析

## 概述

`kilim.timerservice` 包提供了 Kilim 框架的定时器服务实现，用于在协程环境中管理定时任务。该包采用分层设计，结合了优先级队列和无锁队列，实现了高效的定时任务调度机制。

## 包结构

```
kilim.timerservice
├── Timer.java              // 定时器实体类
├── TimerPriorityHeap.java  // 基于堆的优先级队列
└── TimerService.java       // 定时器服务核心实现
```

## 核心类详解

### 1. Timer 类

#### 类定义

```java
public class Timer implements Comparable<Timer>
```

`Timer` 是定时器的基本单元，实现了 `Comparable` 接口以便在优先级队列中排序。

#### 核心字段

```java
private volatile long nextExecutionTime;  // 下一次执行时间
public VolatileBoolean onQueue = new VolatileBoolean(false);  // 是否在队列中
public volatile boolean onHeap = false;    // 是否在堆中
public int index;                          // 在堆中的索引
public EventSubscriber es;                 // 事件订阅者
```

#### 常量

```java
public static final int TIMED_OUT = 3;      // 超时事件类型
public static final Event timedOut = new Event(TIMED_OUT);  // 超时事件实例
```

#### 核心方法

**1. 构造方法**
```java
public Timer(EventSubscriber es) {
    this.es = es;
}
```

**2. 设置相对时间**
```java
public void setTimer(long timeoutMillis) {
    nextExecutionTime = System.currentTimeMillis() + timeoutMillis;
}
```

**3. 设置绝对时间**
```java
public void setLiteral(long value) {
    nextExecutionTime = value;
}
```

**4. 取消定时器**
```java
public void cancel() {
    nextExecutionTime = -1;
}
```

**5. 获取执行时间**
```java
public long getExecutionTime() {
    return nextExecutionTime;
}
```

**6. 比较方法**
```java
@Override
public int compareTo(Timer o) {
    return (int) (((Long) nextExecutionTime))
            .compareTo((Long) o.nextExecutionTime);
}
```

### 2. TimerPriorityHeap 类

#### 类定义

```java
public class TimerPriorityHeap
```

基于最小堆实现的优先级队列，用于高效管理定时任务。

#### 核心字段

```java
private Timer[] queue;  // 定时器数组（从索引1开始使用）
private int size = 0;   // 当前堆大小
```

#### 核心方法

**1. 构造方法**
```java
public TimerPriorityHeap() {
    this(128);
}

public TimerPriorityHeap(int size) {
    queue = new Timer[size];
}
```

**2. 添加元素**
```java
public void add(Timer task) {
    if (size + 1 == queue.length)
        queue = Arrays.copyOf(queue, 2 * queue.length);
    queue[++size] = task;
    heapifyUp(size);
}
```

**3. 重新调度**
```java
public void reschedule(int i) {
    heapifyUp(i);
    heapifyDown(i);
}
```

**4. 向上堆化**
```java
private void heapifyUp(int k) {
    while (k > 1) {
        int j = k >> 1;
        if (queue[j].getExecutionTime() <= queue[k].getExecutionTime())
            break;
        Timer tmp = queue[j];
        queue[j] = queue[k];
        queue[j].index = j;
        queue[k] = tmp;
        queue[k].index = k;
        k = j;
    }
}
```

**5. 向下堆化**
```java
private void heapifyDown(int k) {
    int j;
    while ((j = k << 1) <= size && j > 0) {
        if (j < size
                && queue[j].getExecutionTime() > queue[j + 1]
                        .getExecutionTime())
            j++;
        if (queue[k].getExecutionTime() <= queue[j].getExecutionTime())
            break;
        Timer tmp = queue[j];
        queue[j] = queue[k];
        queue[j].index = j;
        queue[k] = tmp;
        queue[k].index = k;
        k = j;
    }
}
```

**6. 获取堆顶元素**
```java
public Timer peek() {
    return queue[1];
}
```

**7. 移除堆顶元素**
```java
public void poll() {
    queue[1] = queue[size];
    queue[1].index = 1;
    queue[size--] = null;
    heapifyDown(1);
}
```

**8. 其他辅助方法**
```java
public int size() {
    return size;
}

public boolean isEmpty() {
    return size == 0;
}
```

### 3. TimerService 类

#### 类定义

```java
public class TimerService
```

定时器服务的核心实现，负责管理和调度所有定时任务。

#### 核心字段

```java
private final MPSCQueue<Timer> timerQueue;      // 多生产者单消费者队列
private final TimerPriorityHeap timerHeap;      // 优先级堆
private ScheduledExecutorService timerProxy;     // 定时器代理
final private Lock lock;                        // 锁
private static boolean debugStats = false;      // 调试统计标志
private volatile WatchdogTask argos;            // 看门狗任务
private static volatile int c1, c2, c3;         // 统计计数器
public WatchdogContext defaultExec;             // 默认执行上下文
```

#### 内部类

**1. DaemonFactory**
```java
static class DaemonFactory implements ThreadFactory {
    ThreadFactory defaultFactory = Executors.defaultThreadFactory();
    public Thread newThread(Runnable r) {
        Thread thread = defaultFactory.newThread(r);
        thread.setDaemon(true);
        return thread;
    }
}
```
- 创建守护线程的工厂类

**2. Watcher**
```java
private class Watcher implements Runnable {
    WatchdogContext doghouse;
    WatchdogTask dog;
    Watcher(WatchdogContext $doghouse,long time) { 
        doghouse = $doghouse; 
        dog = new WatchdogTask(time); 
    }
    @Override
    public void run() {
        if (! launch()) { dog.done = true; launch(); }
    }
    private boolean launch() {
        WatchdogTask hund = argos;
        if ((dog.time <= hund.time | hund.done) && doghouse.isEmptyish()) {
            doghouse.publish(dog);
            return true;
        }
        return false;
    }
}
```
- 监视定时器执行状态

**3. WatchdogTask**
```java
public static class WatchdogTask implements Runnable {
    volatile boolean done;
    final long time;
    public WatchdogTask(long $time) { time = $time; }
    @Override
    public void run() { done = true; c3++; }
}
```
- 看门狗任务，用于监控定时器服务

**4. Empty**
```java
private class Empty implements EventSubscriber {
    boolean empty, done;
    WatchdogContext executor;
    @Override
    public void onEvent(EventPublisher ep,Event e) {
        empty = executor.isEmpty() && empty();
        done = true;
        synchronized (this) { this.notify(); }
    }
    boolean check(WatchdogContext executor) {
        this.executor = executor;
        if (! timerQueue.offer(new kilim.timerservice.Timer(this)))
            return false;
        trigger(executor);
        synchronized (this) {
            try { if (!done) this.wait(); } catch (InterruptedException ex) {}
        }
        return empty;
    }
}
```
- 用于检查队列是否为空

#### 核心方法

**1. 构造方法**
```java
public TimerService(WatchdogContext doghouse) {
    timerHeap = new TimerPriorityHeap();
    timerQueue = new MPSCQueue<Timer>(Integer.getInteger("kilim.maxpendingtimers",100000));
    timerProxy = Executors.newSingleThreadScheduledExecutor(factory);
    lock = new java.util.concurrent.locks.ReentrantLock();
    defaultExec = doghouse;
}
```

**2. 提交定时器**
```java
public void submit(Timer t) {
    if (t.onQueue.compareAndSet(false, true)) {
        while (!timerQueue.offer(t)) {
            trigger(defaultExec);
            try { Thread.sleep(0); }
            catch (InterruptedException ex) { return; }
        }
    }
}
```

**3. 触发处理**
```java
public void trigger(final WatchdogContext doghouse) {
    int maxtry = 5;
    long clock = System.currentTimeMillis(), sched = 0;
    int retry = -1;
    while ((retry < 0 || !timerQueue.isEmpty() || (sched > 0 && sched <= clock))
            && ++retry < maxtry
            && lock.tryLock()) {
        try {
            sched = doTrigger(clock);
        } finally { lock.unlock(); }
        clock = System.currentTimeMillis();
    }
    if (! doghouse.isEmptyish()) return;

    WatchdogTask dragon = argos;

    if (retry==maxtry) {
        doghouse.publish(argos = new WatchdogTask(0));
        c1++;
    }
    else if (sched > 0 & (dragon.done | sched < dragon.time)) {
        Watcher watcher = new Watcher(doghouse,sched);
        argos = watcher.dog;
        timerProxy.schedule(watcher,sched-clock,TimeUnit.MILLISECONDS);
        c2++;
    }
}
```

**4. 执行触发**
```java
private long doTrigger(long currentTime) {
    Timer[] buf = new Timer[100];
    for (Timer t; (t = timerHeap.peek())!=null && t.getExecutionTime()==-1;) {
        t.onHeap = false;
        timerHeap.poll();
    }
    int i = 0;
    timerQueue.fill(buf);
    do {
        for (i = 0; i<buf.length; i++) {
            Timer t = buf[i];
            if (t==null)
                break;
            t.onQueue.set(false);
            long executionTime = t.getExecutionTime();
            if (executionTime<0)
                t = null;
            else if (executionTime > 0 && executionTime<=currentTime)
                t.es.onEvent(null,Timer.timedOut);
            else if (!t.onHeap) {
                timerHeap.add(t);
                t.onHeap = true;
            }
            else
                timerHeap.reschedule(t.index);
            buf[i] = null;
        }
    } while (i==100);
    while (!timerHeap.isEmpty()) {
        Timer t = timerHeap.peek();
        long executionTime = t.getExecutionTime();
        if (executionTime > currentTime)
            return executionTime;
        t.onHeap = false;
        timerHeap.poll();
        if (executionTime >= 0)
            t.es.onEvent(null,Timer.timedOut);
    }
    return 0L;
}
```

**5. 关闭服务**
```java
public void shutdown() {
    timerProxy.shutdown();
    if (debugStats)
        System.out.format("timerservice: %d %d %d\n",c1,c2,c3);
}
```

**6. 检查是否为空**
```java
public boolean isEmptyLazy(WatchdogContext executor) {
    return empty() && new Empty().check(executor);
}

private boolean empty() { 
    return timerHeap.isEmpty() && timerQueue.isEmpty(); 
}
```

#### 接口定义

```java
public interface WatchdogContext {
    boolean isEmpty();
    boolean isEmptyish();
    void publish(WatchdogTask dog);
}
```

## 工作原理

### 1. 定时器生命周期

```
创建定时器 → 提交到队列 → 转移到堆 → 触发执行 → 完成或取消
```

### 2. 数据结构

- **MPSCQueue**: 多生产者单消费者无锁队列，用于接收新提交的定时器
- **TimerPriorityHeap**: 最小堆，按执行时间排序，快速获取最早到期的定时器

### 3. 调度流程

1. **提交阶段**
   - 定时器通过 submit() 提交到 MPSCQueue
   - 使用 CAS 操作确保线程安全

2. **处理阶段**
   - trigger() 被调用时处理队列中的定时器
   - 将定时器从队列转移到堆中

3. **执行阶段**
   - 检查堆顶定时器是否到期
   - 到期则触发事件订阅者
   - 未到期则返回下次执行时间

4. **监控阶段**
   - Watcher 监控定时器状态
   - WatchdogTask 确保服务正常运行

### 4. 线程模型

- **单线程调度**: 使用 ScheduledExecutorService 的单线程执行器
- **多线程提交**: 支持多线程并发提交定时器
- **无锁队列**: 使用 MPSCQueue 实现高效并发

## 使用示例

### 基本使用

```java
// 创建定时器服务
TimerService timerService = new TimerService(executorContext);

// 创建定时器
Timer timer = new Timer(new EventSubscriber() {
    @Override
    public void onEvent(EventPublisher ep, Event e) {
        System.out.println("Timer fired!");
    }
});

// 设置延迟
timer.setTimer(1000); // 1秒后执行

// 提交定时器
timerService.submit(timer);
```

### 取消定时器

```java
// 取消定时器
timer.cancel();

// 重新提交
timer.setTimer(2000);
timerService.submit(timer);
```

### 关闭服务

```java
// 关闭定时器服务
timerService.shutdown();
```

## 设计特点

### 1. 高效性

- 使用最小堆快速获取最早到期的定时器
- 无锁队列支持高并发提交
- 批量处理减少锁竞争

### 2. 可靠性

- 看门狗机制监控服务状态
- 重复尝试确保定时器不被遗漏
- 完善的错误处理

### 3. 灵活性

- 支持相对时间和绝对时间
- 可取消和重新调度
- 与事件系统集成

### 4. 可扩展性

- 通过 WatchdogContext 接口集成
- 支持自定义事件订阅者
- 模块化设计便于扩展

## 性能考虑

1. **队列容量**
   - 默认最大待处理定时器数: 100000
   - 可通过系统属性 `kilim.maxpendingtimers` 调整

2. **批量处理**
   - 每次最多处理 100 个定时器
   - 减少锁持有时间

3. **重试机制**
   - 最多重试 5 次
   - 避免忙等待

4. **内存管理**
   - 堆自动扩容
   - 及时清理已完成的定时器

## 注意事项

1. **线程安全**
   - Timer 对象可以多线程提交
   - 内部使用 CAS 和锁保证安全

2. **时间精度**
   - 基于系统毫秒级时间戳
   - 受系统调度影响

3. **资源清理**
   - 使用完毕后调用 shutdown()
   - 避免资源泄漏

4. **异常处理**
   - 事件订阅者需自行处理异常
   - 异常不会影响其他定时器

## 总结

`kilim.timerservice` 包实现了一个高效、可靠的定时器服务，通过结合优先级堆和无锁队列，在协程环境中提供了强大的定时任务调度能力。其设计充分考虑了并发性能和可靠性，是 Kilim 框架的重要组成部分。
