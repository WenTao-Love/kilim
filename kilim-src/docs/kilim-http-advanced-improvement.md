# Kilim HTTP 高级改进方案

## 目录
1. [协议增强](#协议增强)
2. [安全加固](#安全加固)
3. [功能完善](#功能完善)
4. [可观测性提升](#可观测性提升)
5. [实施路线图](#实施路线图)

---

## 协议增强

### 1. HTTP/2支持

**目标**: 实现HTTP/2协议支持，提升性能

**核心组件**:

```java
package kilim.http.ext;

/**
 * HTTP/2帧类型
 */
public enum Http2FrameType {
    DATA(0x0),
    HEADERS(0x1),
    PRIORITY(0x2),
    RST_STREAM(0x3),
    SETTINGS(0x4),
    PUSH_PROMISE(0x5),
    PING(0x6),
    GOAWAY(0x7),
    WINDOW_UPDATE(0x8),
    CONTINUATION(0x9);

    private final byte value;
    Http2FrameType(int value) { this.value = (byte) value; }
    public byte getValue() { return value; }
}

/**
 * HTTP/2帧基类
 */
public abstract class Http2Frame {
    public final int streamId;
    public final Http2FrameType type;
    public final byte flags;
    public final int payloadLength;

    protected Http2Frame(int streamId, Http2FrameType type, 
                     byte flags, int payloadLength) {
        this.streamId = streamId;
        this.type = type;
        this.flags = flags;
        this.payloadLength = payloadLength;
    }
}

/**
 * HTTP/2 HEADERS帧
 */
public class Http2HeadersFrame extends Http2Frame {
    public final byte[] headerBlock;
    public final int padLength;

    public Http2HeadersFrame(int streamId, byte flags, 
                          int payloadLength, byte[] headerBlock,
                          int padLength) {
        super(streamId, Http2FrameType.HEADERS, flags, payloadLength);
        this.headerBlock = headerBlock;
        this.padLength = padLength;
    }
}

/**
 * HTTP/2 DATA帧
 */
public class Http2DataFrame extends Http2Frame {
    public final byte[] data;
    public final int padLength;

    public Http2DataFrame(int streamId, byte flags,
                       int payloadLength, byte[] data,
                       int padLength) {
        super(streamId, Http2FrameType.DATA, flags, payloadLength);
        this.data = data;
        this.padLength = padLength;
    }
}

/**
 * HTTP/2 SETTINGS帧
 */
public class Http2SettingsFrame extends Http2Frame {
    public final Http2Setting[] settings;

    public Http2SettingsFrame(byte flags, int payloadLength,
                          Http2Setting[] settings) {
        super(0, Http2FrameType.SETTINGS, flags, payloadLength);
        this.settings = settings;
    }
}

/**
 * HTTP/2设置参数
 */
public class Http2Setting {
    public final short id;
    public final int value;

    public Http2Setting(short id, int value) {
        this.id = id;
        this.value = value;
    }
}

/**
 * HTTP/2流管理器
 */
public class Http2StreamManager {
    private final java.util.Map<Integer, Http2Stream> streams = 
        new java.util.concurrent.ConcurrentHashMap<>();
    private final int maxConcurrentStreams;
    private final int initialWindowSize;

    public Http2StreamManager(int maxConcurrentStreams, int initialWindowSize) {
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.initialWindowSize = initialWindowSize;
    }

    public Http2Stream getOrCreateStream(int streamId) {
        if (streams.size() >= maxConcurrentStreams) {
            throw new IllegalStateException("Too many streams");
        }
        return streams.computeIfAbsent(streamId, 
            id -> new Http2Stream(id, initialWindowSize));
    }

    public void closeStream(int streamId) {
        streams.remove(streamId);
    }
}

/**
 * HTTP/2流
 */
public class Http2Stream {
    public final int streamId;
    private int windowSize;
    private final java.util.Queue<Http2DataFrame> dataQueue = 
        new java.util.concurrent.LinkedBlockingQueue<>();

    public Http2Stream(int streamId, int initialWindowSize) {
        this.streamId = streamId;
        this.windowSize = initialWindowSize;
    }

    public void sendData(Http2DataFrame frame) {
        dataQueue.offer(frame);
    }

    public Http2DataFrame receiveData() throws kilim.Pausable {
        return dataQueue.take();
    }

    public void updateWindow(int delta) {
        windowSize += delta;
    }
}
```

### 2. WebSocket支持

**目标**: 实现完整的WebSocket协议支持

**核心组件**:

```java
package kilim.http.ext;

/**
 * WebSocket状态
 */
public enum WebSocketState {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED
}

/**
 * WebSocket帧类型
 */
public enum WebSocketFrameType {
    CONTINUATION(0x0),
    TEXT(0x1),
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA);

    private final byte value;
    WebSocketFrameType(int value) { this.value = (byte) value; }
    public byte getValue() { return value; }
}

/**
 * WebSocket帧基类
 */
public abstract class WebSocketFrame {
    public final WebSocketFrameType opcode;
    public final boolean fin;
    public final byte[] payload;

    protected WebSocketFrame(WebSocketFrameType opcode, boolean fin, byte[] payload) {
        this.opcode = opcode;
        this.fin = fin;
        this.payload = payload;
    }
}

/**
 * WebSocket文本帧
 */
public class WebSocketTextFrame extends WebSocketFrame {
    public final String text;

    public WebSocketTextFrame(String text) {
        super(WebSocketFrameType.TEXT, true, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.text = text;
    }
}

/**
 * WebSocket二进制帧
 */
public class WebSocketBinaryFrame extends WebSocketFrame {
    public WebSocketBinaryFrame(byte[] data) {
        super(WebSocketFrameType.BINARY, true, data);
    }
}

/**
 * WebSocket关闭帧
 */
public class WebSocketCloseFrame extends WebSocketFrame {
    public final int code;
    public final String reason;

    public WebSocketCloseFrame(int code, String reason) {
        super(WebSocketFrameType.CLOSE, true, buildPayload(code, reason));
        this.code = code;
        this.reason = reason;
    }

    private static byte[] buildPayload(int code, String reason) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        baos.write((code >> 8) & 0xFF);
        baos.write(code & 0xFF);
        if (reason != null && !reason.isEmpty()) {
            byte[] reasonBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            baos.write(reasonBytes.length & 0xFF);
            baos.write(reasonBytes);
        }
        return baos.toByteArray();
    }
}

/**
 * WebSocket消息处理器
 */
public interface WebSocketMessageHandler {
    void onOpen(WebSocketSession session);
    void onMessage(WebSocketSession session, String message);
    void onMessage(WebSocketSession session, byte[] data);
    void onClose(WebSocketSession session, int code, String reason);
    void onError(WebSocketSession session, Throwable error);
}
```

---

## 安全加固

### 1. 请求验证

**目标**: 实现全面的请求验证机制

**核心组件**:

```java
package kilim.http.ext;

/**
 * 验证结果
 */
public class ValidationResult {
    private final java.util.List<String> errors = new java.util.ArrayList<>();

    public void addError(String error) {
        errors.add(error);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public java.util.List<String> getErrors() {
        return java.util.Collections.unmodifiableList(errors);
    }
}

/**
 * 速率限制器
 */
public class RateLimiter {
    private final java.util.concurrent.ConcurrentHashMap<String, RateInfo> rateMap = 
        new java.util.concurrent.ConcurrentHashMap<>();
    private final int requestsPerSecond;
    private final int burstSize;
    private final long windowSizeMs;

    public RateLimiter(int requestsPerSecond, int burstSize) {
        this.requestsPerSecond = requestsPerSecond;
        this.burstSize = burstSize;
        this.windowSizeMs = 1000;
    }

    public boolean checkRateLimit(String clientId) {
        long now = System.currentTimeMillis();
        RateInfo info = rateMap.computeIfAbsent(clientId, 
            id -> new RateInfo(now));

        synchronized (info) {
            // 滑动窗口算法
            long windowStart = now - windowSizeMs;
            info.timestamps.removeIf(ts -> ts < windowStart);

            if (info.timestamps.size() >= burstSize) {
                return false;
            }

            info.timestamps.add(now);
            return true;
        }
    }

    private static class RateInfo {
        final java.util.Queue<Long> timestamps = 
            new java.util.concurrent.ConcurrentLinkedQueue<>();

        RateInfo(long initialTimestamp) {
            timestamps.add(initialTimestamp);
        }
    }
}

/**
 * IP白名单/黑名单
 */
public class IpFilter {
    private final java.util.Set<String> whitelist = 
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> blacklist = 
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final boolean whitelistMode;

    public IpFilter(boolean whitelistMode) {
        this.whitelistMode = whitelistMode;
    }

    public void addToWhitelist(String ip) {
        whitelist.add(ip);
    }

    public void addToBlacklist(String ip) {
        blacklist.add(ip);
    }

    public boolean isAllowed(String ip) {
        if (whitelistMode) {
            return whitelist.contains(ip);
        } else {
            return !blacklist.contains(ip);
        }
    }
}
```

---

## 功能完善

### 1. 中间件机制

**目标**: 实现灵活的中间件系统

**核心组件**:

```java
package kilim.http.ext;

/**
 * 中间件接口
 */
public interface Middleware {
    /**
     * 处理请求
     * @param request HTTP请求
     * @param response HTTP响应
     * @param next 下一个中间件
     */
    void process(HttpRequest request, HttpResponse response, 
               MiddlewareChain next) throws kilim.Pausable, Exception;
}

/**
 * 中间件链
 */
public class MiddlewareChain {
    private final java.util.List<Middleware> middlewares;
    private final kilim.http.KilimMvc.KilimHandler handler;
    private int currentIndex = 0;

    public MiddlewareChain(java.util.List<Middleware> middlewares,
                      kilim.http.KilimMvc.KilimHandler handler) {
        this.middlewares = new java.util.ArrayList<>(middlewares);
        this.handler = handler;
    }

    public void proceed(HttpRequest request, HttpResponse response) 
            throws kilim.Pausable, Exception {
        if (currentIndex < middlewares.size()) {
            Middleware middleware = middlewares.get(currentIndex++);
            middleware.process(request, response, this);
        } else {
            handler.handle(null, request, response);
        }
    }
}

/**
 * 常用中间件实现
 */
public class CommonMiddlewares {
    /**
     * 日志中间件
     */
    public static Middleware logging(java.util.logging.Logger logger) {
        return (request, response, next) -> {
            long startTime = System.currentTimeMillis();
            logger.info("Request: " + request.method + " " + request.uriPath);

            try {
                next.proceed(request, response);
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                logger.info("Response: " + 
                    new String(response.status, 0, response.status.length - 2) + 
                    " (" + duration + "ms)");
            }
        };
    }

    /**
     * CORS中间件
     */
    public static Middleware cors(String origin, String methods, 
                           String headers, int maxAge) {
        return (request, response, next) -> {
            response.addField("Access-Control-Allow-Origin", origin);
            response.addField("Access-Control-Allow-Methods", methods);
            response.addField("Access-Control-Allow-Headers", headers);
            response.addField("Access-Control-Max-Age", String.valueOf(maxAge));

            if ("OPTIONS".equals(request.method)) {
                response.status = kilim.http.HttpResponse.ST_OK;
                return;
            }

            next.proceed(request, response);
        };
    }

    /**
     * 安全头中间件
     */
    public static Middleware security() {
        return (request, response, next) -> {
            response.addField("X-Content-Type-Options", "nosniff");
            response.addField("X-Frame-Options", "DENY");
            response.addField("X-XSS-Protection", "1; mode=block");
            response.addField("Strict-Transport-Security", "max-age=31536000");

            next.proceed(request, response);
        };
    }
}
```

### 2. Cookie和Session管理

**目标**: 实现完整的Cookie和Session支持

**核心组件**:

```java
package kilim.http.ext;

/**
 * Cookie管理器
 */
public class CookieManager {
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<Cookie>> cookieStore = 
        new java.util.concurrent.ConcurrentHashMap<>();

    public void addCookie(String sessionId, Cookie cookie) {
        cookieStore.computeIfAbsent(sessionId, 
            id -> new java.util.concurrent.CopyOnWriteArrayList<>())
               .add(cookie);
    }

    public java.util.List<Cookie> getCookies(String sessionId) {
        return cookieStore.getOrDefault(sessionId, java.util.Collections.emptyList());
    }

    public void removeCookie(String sessionId, String cookieName) {
        java.util.List<Cookie> cookies = cookieStore.get(sessionId);
        if (cookies != null) {
            cookies.removeIf(c -> c.name.equals(cookieName));
        }
    }

    public void clearSession(String sessionId) {
        cookieStore.remove(sessionId);
    }
}

/**
 * Session管理器
 */
public class SessionManager {
    private final java.util.concurrent.ConcurrentHashMap<String, HttpSession> sessions = 
        new java.util.concurrent.ConcurrentHashMap<>();
    private final long sessionTimeout;

    public SessionManager(long sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
        startCleanupThread();
    }

    public String createSession() {
        String sessionId = generateSessionId();
        HttpSession session = new HttpSession(sessionId, System.currentTimeMillis());
        sessions.put(sessionId, session);
        return sessionId;
    }

    public HttpSession getSession(String sessionId) {
        HttpSession session = sessions.get(sessionId);
        if (session != null) {
            session.lastAccessed = System.currentTimeMillis();
        }
        return session;
    }

    public void invalidateSession(String sessionId) {
        sessions.remove(sessionId);
    }

    private String generateSessionId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60000); // 每分钟检查一次
                    cleanupExpiredSessions();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.setName("Session-Cleanup-Thread");
        cleanupThread.start();
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> 
            now - entry.getValue().lastAccessed > sessionTimeout);
    }

    public static class HttpSession {
        public final String sessionId;
        public final long createTime;
        public volatile long lastAccessed;
        public final java.util.Map<String, Object> attributes = 
            new java.util.concurrent.ConcurrentHashMap<>();

        public HttpSession(String sessionId, long createTime) {
            this.sessionId = sessionId;
            this.createTime = createTime;
            this.lastAccessed = createTime;
        }

        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        public void removeAttribute(String name) {
            attributes.remove(name);
        }
    }
}
```

---

## 可观测性提升

### 1. 日志和监控

**目标**: 实现完整的日志和监控系统

**核心组件**:

```java
package kilim.http.ext;

/**
 * HTTP服务器监控器
 */
public class HttpServerMonitor {
    // 请求统计
    private final java.util.concurrent.atomic.AtomicLong totalRequests = 
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong activeRequests = 
        new java.util.concurrent.atomic.AtomicLong(0);

    // 响应统计
    private final java.util.concurrent.atomic.AtomicLong totalResponses = 
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalResponseTime = 
        new java.util.concurrent.atomic.AtomicLong(0);

    // 错误统计
    private final java.util.concurrent.atomic.AtomicLong totalErrors = 
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> errorCounts = 
        new java.util.concurrent.ConcurrentHashMap<>();

    // 性能指标
    private final java.util.concurrent.atomic.AtomicLong minResponseTime = 
        new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
    private final java.util.concurrent.atomic.AtomicLong maxResponseTime = 
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 记录请求开始
     */
    public void recordRequestStart() {
        activeRequests.incrementAndGet();
        totalRequests.incrementAndGet();
    }

    /**
     * 记录请求完成
     */
    public void recordRequestEnd(long responseTime) {
        activeRequests.decrementAndGet();
        totalResponses.incrementAndGet();
        totalResponseTime.addAndGet(responseTime);

        // 更新最小/最大响应时间
        minResponseTime.accumulateAndGet(responseTime, Math::min);
        maxResponseTime.accumulateAndGet(responseTime, Math::max);
    }

    /**
     * 记录错误
     */
    public void recordError(String errorType) {
        totalErrors.incrementAndGet();
        errorCounts.computeIfAbsent(errorType, 
            k -> new java.util.concurrent.atomic.AtomicLong(0))
               .incrementAndGet();
    }

    /**
     * 获取统计信息
     */
    public ServerStats getStats() {
        long responses = totalResponses.get();
        return new ServerStats(
            totalRequests.get(),
            activeRequests.get(),
            responses,
            totalErrors.get(),
            responses > 0 ? totalResponseTime.get() / responses : 0,
            minResponseTime.get(),
            maxResponseTime.get()
        );
    }

    /**
     * 重置统计
     */
    public void reset() {
        totalRequests.set(0);
        activeRequests.set(0);
        totalResponses.set(0);
        totalResponseTime.set(0);
        totalErrors.set(0);
        minResponseTime.set(Long.MAX_VALUE);
        maxResponseTime.set(0);
        errorCounts.clear();
    }

    /**
     * 服务器统计
     */
    public static class ServerStats {
        public final long totalRequests;
        public final long activeRequests;
        public final long totalResponses;
        public final long totalErrors;
        public final long averageResponseTime;
        public final long minResponseTime;
        public final long maxResponseTime;

        public ServerStats(long totalRequests, long activeRequests,
                      long totalResponses, long totalErrors,
                      long averageResponseTime,
                      long minResponseTime, long maxResponseTime) {
            this.totalRequests = totalRequests;
            this.activeRequests = activeRequests;
            this.totalResponses = totalResponses;
            this.totalErrors = totalErrors;
            this.averageResponseTime = averageResponseTime;
            this.minResponseTime = minResponseTime;
            this.maxResponseTime = maxResponseTime;
        }
    }
}

/**
 * 请求追踪器
 */
public class RequestTracer {
    private static final ThreadLocal<TraceContext> traceContext = 
        ThreadLocal.withInitial(TraceContext::new);

    /**
     * 开始追踪
     */
    public static void startTrace(String traceId) {
        TraceContext context = traceContext.get();
        context.traceId = traceId;
        context.startTime = System.nanoTime();
        context.spans.clear();
    }

    /**
     * 记录span
     */
    public static void recordSpan(String name) {
        TraceContext context = traceContext.get();
        Span span = new Span(name, System.nanoTime());
        context.spans.add(span);
    }

    /**
     * 结束追踪
     */
    public static void endTrace() {
        TraceContext context = traceContext.get();
        context.endTime = System.nanoTime();
    }

    /**
     * 获取追踪信息
     */
    public static TraceInfo getTraceInfo() {
        TraceContext context = traceContext.get();
        return new TraceInfo(
            context.traceId,
            context.startTime,
            context.endTime,
            new java.util.ArrayList<>(context.spans)
        );
    }

    private static class TraceContext {
        String traceId;
        long startTime;
        long endTime;
        final java.util.List<Span> spans = new java.util.ArrayList<>();
    }

    public static class Span {
        final String name;
        final long startTime;

        Span(String name, long startTime) {
            this.name = name;
            this.startTime = startTime;
        }
    }

    public static class TraceInfo {
        public final String traceId;
        public final long startTime;
        public final long endTime;
        public final java.util.List<Span> spans;

        TraceInfo(String traceId, long startTime, long endTime,
                 java.util.List<Span> spans) {
            this.traceId = traceId;
            this.startTime = startTime;
            this.endTime = endTime;
            this.spans = spans;
        }
    }
}
```

---

## 实施路线图

### 阶段一：性能优化（1-2个月）
1. 实现EnhancedHttpRequest和EnhancedHttpResponse
2. 实现ConnectionManager
3. 性能测试和调优

### 阶段二：协议增强（3-4个月）
1. 实现HTTP/2支持
2. 实现WebSocket支持
3. 集成测试和验证

### 阶段三：安全加固（2-3个月）
1. 实现RequestValidator
2. 实现RateLimiter
3. 实现IpFilter
4. 安全测试和验证

### 阶段四：功能完善（3-4个月）
1. 实现中间件机制
2. 实现Cookie和Session管理
3. 实现文件上传支持
4. 实现静态资源服务

### 阶段五：可观测性提升（2-3个月）
1. 实现HttpServerMonitor
2. 实现RequestTracer
3. 集成日志系统
4. 实现监控面板

---

## 总结

本改进方案通过继承和扩展的方式，在不修改原有代码的前提下，全面提升Kilim HTTP的功能和性能。主要改进包括：

1. **性能优化**: 减少内存分配，提高吞吐量
2. **协议增强**: 支持HTTP/2和WebSocket
3. **安全加固**: 全面的请求验证和速率限制
4. **功能完善**: 中间件、Cookie/Session管理
5. **可观测性**: 完整的日志和监控系统

所有改进都采用继承原有类的实现方式，确保与现有代码的兼容性，用户可以根据需要选择性使用新功能。
