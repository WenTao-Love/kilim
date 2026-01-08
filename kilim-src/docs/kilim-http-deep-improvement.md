# Kilim HTTP 深度改进方案

## 目录
1. [项目概述](#项目概述)
2. [核心问题分析](#核心问题分析)
3. [重点改进方向](#重点改进方向)
4. [详细改进方案](#详细改进方案)
5. [实施路线图](#实施路线图)

---

## 项目概述

Kilim HTTP是一个基于协程的轻量级HTTP服务器实现，利用Kilim的协程特性实现高并发处理。当前实现提供了基本的HTTP/1.1协议支持，但在现代Web应用需求下存在明显不足。

### 当前架构特点

1. **协程驱动**: 每个连接由独立协程处理，非阻塞IO
2. **简单路由**: 基于KilimMvc的简单路由框架
3. **基础协议支持**: 支持HTTP/1.1基本特性
4. **零拷贝设计**: 使用ByteBuffer和FileChannel实现零拷贝传输

### 核心组件

- **HttpServer**: 服务器入口，监听端口和创建会话
- **HttpSession**: 会话处理，读取请求和发送响应
- **HttpRequest**: 请求解析和处理
- **HttpResponse**: 响应构建和发送
- **KilimMvc**: 路由框架
- **HttpRequestParser**: 使用Ragel生成的请求解析器

---

## 核心问题分析

### 1. 性能瓶颈

#### 1.1 请求解析性能问题
**问题**:
- HttpRequestParser使用Ragel生成，解析效率高但维护困难
- 每次请求都创建新的ByteBuffer(1024字节)
- 字段值懒加载导致频繁字符串创建
- Chunked传输编码处理效率低

**影响**:
- 高并发下内存分配压力大
- GC频繁，影响吞吐量
- 大请求处理性能下降

#### 1.2 响应构建性能问题
**问题**:
- 每次响应都创建新的ExposedBaos
- 响应头使用ArrayList存储，频繁扩容
- SimpleDateFormat每次创建新实例
- 无响应压缩支持

**影响**:
- 响应构建开销大
- 带宽利用率低
- 大响应传输慢

#### 1.3 连接管理问题
**问题**:
- 无连接池管理
- Keep-Alive连接无超时控制
- 无连接数限制
- 无优雅关闭机制

**影响**:
- 资源泄漏风险
- 无法应对突发流量
- 服务器稳定性差

### 2. 功能缺陷

#### 2.1 协议支持不足
**问题**:
- 仅支持HTTP/1.1
- 不支持HTTP/2
- 不支持WebSocket
- 不支持HTTPS
- 不支持Server-Sent Events

**影响**:
- 无法满足现代Web应用需求
- 与主流框架功能差距大
- 扩展性受限

#### 2.2 安全性不足
**问题**:
- 无请求验证
- 无速率限制
- 无认证授权机制
- 无CSRF保护
- 无请求体大小限制

**影响**:
- 易受DDoS攻击
- 安全漏洞风险高
- 不符合安全规范

#### 2.3 缺少关键特性
**问题**:
- 无Cookie管理
- 无Session管理
- 无文件上传支持
- 无静态资源缓存
- 无响应压缩
- 无流式响应

**影响**:
- 开发效率低
- 需要手动实现基础功能
- 应用开发成本高

### 3. 架构问题

#### 3.1 扩展性差
**问题**:
- 无中间件机制
- 无插件系统
- 路由系统简单
- 无过滤器链

**影响**:
- 功能扩展困难
- 代码重复多
- 维护成本高

#### 3.2 可观测性差
**问题**:
- 无日志系统
- 无监控指标
- 无请求追踪
- 无性能统计

**影响**:
- 问题定位困难
- 性能优化盲目
- 运维成本高

---

## 重点改进方向

基于以上问题分析，确定以下重点改进方向：

### 1. 性能优化优先
- 请求解析优化
- 响应构建优化
- 连接管理优化
- 内存管理优化

### 2. 协议增强
- HTTP/2支持
- WebSocket支持
- HTTPS支持
- SSE支持

### 3. 安全加固
- 请求验证
- 速率限制
- 认证授权
- 安全头处理

### 4. 功能完善
- 中间件机制
- Cookie/Session管理
- 文件上传
- 静态资源服务
- 响应压缩

### 5. 可观测性提升
- 日志系统
- 监控指标
- 请求追踪
- 性能统计

---

## 详细改进方案

### 1. 性能优化方案

#### 1.1 请求解析优化

**目标**: 减少内存分配，提高解析性能

**方案**: 实现EnhancedHttpRequest类

```java
package kilim.http.ext;

import kilim.http.HttpRequest;
import kilim.Pausable;
import kilim.nio.EndPoint;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 增强的HTTP请求实现，优化性能和内存使用
 */
public class EnhancedHttpRequest extends HttpRequest {
    // 使用对象池减少GC
    private static final ThreadLocal<EnhancedHttpRequest> requestPool = 
        ThreadLocal.withInitial(() -> new EnhancedHttpRequest());

    // 使用更大的缓冲区减少扩容
    private static final int INITIAL_BUFFER_SIZE = 2048;
    private static final int MAX_BUFFER_SIZE = 65536;

    // 缓存常用字段值
    private String cachedContentType;
    private String cachedContentLength;
    private String cachedAcceptEncoding;

    /**
     * 从对象池获取请求实例
     */
    public static EnhancedHttpRequest obtain() {
        EnhancedHttpRequest req = requestPool.get();
        req.reset();
        return req;
    }

    /**
     * 重置请求，复用对象
     */
    @Override
    public void reuse() {
        super.reuse();
        this.cachedContentType = null;
        this.cachedContentLength = null;
        this.cachedAcceptEncoding = null;
    }

    /**
     * 优化的请求头读取，使用更大的初始缓冲区
     */
    @Override
    public void readFrom(EndPoint endpoint) throws IOException, Pausable {
        // 使用更大的初始缓冲区
        if (buffer == null || buffer.capacity() < INITIAL_BUFFER_SIZE) {
            buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
        }

        iread = 0;
        readHeader(endpoint);
        readBody(endpoint);
    }

    /**
     * 缓存常用请求头
     */
    @Override
    public String getHeader(String key) {
        // 缓存常用字段
        if ("Content-Type".equalsIgnoreCase(key)) {
            if (cachedContentType == null) {
                cachedContentType = super.getHeader(key);
            }
            return cachedContentType;
        }
        if ("Content-Length".equalsIgnoreCase(key)) {
            if (cachedContentLength == null) {
                cachedContentLength = super.getHeader(key);
            }
            return cachedContentLength;
        }
        if ("Accept-Encoding".equalsIgnoreCase(key)) {
            if (cachedAcceptEncoding == null) {
                cachedAcceptEncoding = super.getHeader(key);
            }
            return cachedAcceptEncoding;
        }

        return super.getHeader(key);
    }

    /**
     * 优化的Chunked传输处理
     */
    @Override
    public void readAllChunks(EndPoint endpoint) throws IOException, Pausable {
        // 预分配足够大的缓冲区
        if (buffer.capacity() < MAX_BUFFER_SIZE) {
            byte[] newBuffer = new byte[MAX_BUFFER_SIZE];
            System.arraycopy(buffer.array(), 0, newBuffer, 0, buffer.position());
            buffer = ByteBuffer.wrap(newBuffer);
        }

        // 使用更高效的chunk处理逻辑
        super.readAllChunks(endpoint);
    }
}
```

#### 1.2 响应构建优化

**目标**: 减少响应构建开销，支持压缩

**方案**: 实现EnhancedHttpResponse类

```java
package kilim.http.ext;

import kilim.http.HttpResponse;
import kilim.Pausable;
import kilim.nio.EndPoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import java.nio.ByteBuffer;

/**
 * 增强的HTTP响应实现，支持压缩和性能优化
 */
public class EnhancedHttpResponse extends HttpResponse {
    // 压缩相关
    private boolean compressionEnabled = true;
    private int compressionThreshold = 1024; // 大于1KB才压缩
    private String compressionType = "gzip";

    // 对象池
    private static final ThreadLocal<EnhancedHttpResponse> responsePool = 
        ThreadLocal.withInitial(() -> new EnhancedHttpResponse());

    // 日期格式化器复用
    private static final ThreadLocal<java.text.SimpleDateFormat> dateFormat = 
        ThreadLocal.withInitial(() -> {
            java.text.SimpleDateFormat sdf = 
                new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            return sdf;
        });

    /**
     * 从对象池获取响应实例
     */
    public static EnhancedHttpResponse obtain() {
        EnhancedHttpResponse resp = responsePool.get();
        resp.reset();
        return resp;
    }

    /**
     * 启用压缩
     */
    public void enableCompression(boolean enabled) {
        this.compressionEnabled = enabled;
    }

    /**
     * 设置压缩阈值
     */
    public void setCompressionThreshold(int threshold) {
        this.compressionThreshold = threshold;
    }

    /**
     * 优化的响应写入，支持压缩
     */
    @Override
    public void writeTo(EndPoint endpoint) throws IOException, Pausable {
        // 获取响应体
        byte[] body = null;
        if (bodyStream != null && bodyStream.size() > 0) {
            body = bodyStream.toByteArray();
        }

        // 判断是否需要压缩
        boolean shouldCompress = compressionEnabled && 
                           body != null && 
                           body.length > compressionThreshold &&
                           shouldCompressContentType();

        // 如果需要压缩
        if (shouldCompress) {
            body = compress(body);
            addField("Content-Encoding", compressionType);
        }

        // 写入响应头
        writeHeaderOptimized(endpoint);

        // 写入响应体
        if (body != null && body.length > 0) {
            endpoint.write(ByteBuffer.wrap(body));
        }

        // 回收响应对象
        recycle();
    }

    /**
     * 判断内容类型是否应该压缩
     */
    private boolean shouldCompressContentType() {
        String ct = getHeaderValue("Content-Type");
        return ct != null && 
               (ct.startsWith("text/") || 
                ct.contains("json") || 
                ct.contains("xml") || 
                ct.contains("javascript"));
    }

    /**
     * 压缩响应体
     */
    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        GZIPOutputStream gzip = new GZIPOutputStream(bos);
        gzip.write(data);
        gzip.close();
        return bos.toByteArray();
    }

    /**
     * 优化的响应头写入
     */
    private void writeHeaderOptimized(EndPoint endpoint) throws IOException, Pausable {
        kilim.nio.ExposedBaos headerStream = new kilim.nio.ExposedBaos();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(headerStream);

        // 写入状态行
        dos.write(PROTOCOL);
        dos.write(status);

        // 使用复用的日期格式化器
        dos.write(F_DATE);
        String dateStr = dateFormat.get().format(new java.util.Date());
        dos.write(dateStr.getBytes());
        dos.write(CRLF);

        // 写入服务器头
        dos.write(F_SERVER);

        // 设置Content-Length
        if (bodyStream != null && getHeaderValue("Content-Length") == null) {
            setContentLength(bodyStream.size());
        }

        // 写入其他头
        int nfields = keys.size();
        for (int i = 0; i < nfields; i++) {
            String key = keys.get(i);
            byte[] keyb = byteCache.get(key);
            if (keyb == null) {
                keyb = key.getBytes();
                byteCache.put(key, keyb);
            }
            dos.write(keyb);
            dos.write(FIELD_SEP);
            dos.write(values.get(i).getBytes());
            dos.write(CRLF);
        }
        dos.write(CRLF);

        // 写入头
        endpoint.write(headerStream.toByteBuffer());
    }

    /**
     * 回收响应对象
     */
    private void recycle() {
        if (bodyStream != null) {
            bodyStream.reset();
        }
        keys.clear();
        values.clear();
        status = ST_OK;
    }
}
```

#### 1.3 连接管理优化

**目标**: 实现连接池和超时控制

**方案**: 实现ConnectionManager类

```java
package kilim.http.ext;

import kilim.http.HttpSession;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接管理器，管理HTTP连接的生命周期
 */
public class ConnectionManager {
    // 连接统计
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);

    // 连接映射
    private final ConcurrentHashMap<String, ConnectionInfo> connections = 
        new ConcurrentHashMap<>();

    // 配置
    private final int maxConnections;
    private final long keepAliveTimeout;
    private final long connectionTimeout;

    public ConnectionManager(int maxConnections, long keepAliveTimeout, long connectionTimeout) {
        this.maxConnections = maxConnections;
        this.keepAliveTimeout = keepAliveTimeout;
        this.connectionTimeout = connectionTimeout;

        // 启动清理线程
        startCleanupThread();
    }

    /**
     * 注册新连接
     */
    public void registerConnection(HttpSession session, String clientIp) {
        // 检查连接数限制
        if (activeConnections.get() >= maxConnections) {
            throw new IllegalStateException("Too many connections");
        }

        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();

        ConnectionInfo info = new ConnectionInfo(
            System.currentTimeMillis(),
            clientIp,
            session
        );

        connections.put(session.getId(), info);
    }

    /**
     * 更新连接活动时间
     */
    public void updateActivity(String sessionId) {
        ConnectionInfo info = connections.get(sessionId);
        if (info != null) {
            info.lastActivity = System.currentTimeMillis();
        }
    }

    /**
     * 注销连接
     */
    public void unregisterConnection(String sessionId) {
        connections.remove(sessionId);
        activeConnections.decrementAndGet();
    }

    /**
     * 获取连接统计
     */
    public ConnectionStats getStats() {
        return new ConnectionStats(
            activeConnections.get(),
            totalConnections.get(),
            connections.size()
        );
    }
    
    /**
     * 清理超时连接
     */
    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000); // 每5秒检查一次
                    cleanupIdleConnections();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.setName("Connection-Cleanup-Thread");
        cleanupThread.start();
    }
    
    /**
     * 清理空闲连接
     */
    private void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        for (ConnectionInfo info : connections.values()) {
            if (now - info.lastActivity > keepAliveTimeout) {
                try {
                    info.session.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                }
                connections.remove(info.session.getId());
                activeConnections.decrementAndGet();
            }
        }
    }
    
    /**
     * 连接信息
     */
    private static class ConnectionInfo {
        final long createTime;
        volatile long lastActivity;
        final String clientIp;
        final HttpSession session;
        
        ConnectionInfo(long createTime, String clientIp, HttpSession session) {
            this.createTime = createTime;
            this.lastActivity = createTime;
            this.clientIp = clientIp;
            this.session = session;
        }
    }
    
    /**
     * 连接统计
     */
    public static class ConnectionStats {
        public final int activeConnections;
        public final long totalConnections;
        public final int currentConnections;
        
        ConnectionStats(int active, long total, int current) {
            this.activeConnections = active;
            this.totalConnections = total;
            this.currentConnections = current;
        }
    }
}(
            activeConnections.get(),
            totalConnections.get(),
            connections.size()
        );
    }

    /**
     * 启动清理线程
     */
    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000); // 每5秒清理一次
                    cleanupIdleConnections();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * 清理空闲连接
     */
    private void cleanupIdleConnections() {
        long now = System.currentTimeMillis();

        connections.forEach((sessionId, info) -> {
            long idleTime = now - info.lastActivity;

            // 检查Keep-Alive超时
            if (idleTime > keepAliveTimeout) {
                try {
                    info.session.close();
                    unregisterConnection(sessionId);
                } catch (Exception e) {
                    // 记录错误
                }
            }
            // 检查连接超时
            } else if (idleTime > connectionTimeout) {
                try {
                    info.session.close();
                    unregisterConnection(sessionId);
                } catch (Exception e) {
                    // 记录错误
                }
            }
        });
    }

    /**
     * 连接信息
     */
    private static class ConnectionInfo {
        final long createTime;
        final String clientIp;
        final HttpSession session;
        volatile long lastActivity;

        ConnectionInfo(long createTime, String clientIp, HttpSession session) {
            this.createTime = createTime;
            this.clientIp = clientIp;
            this.session = session;
            this.lastActivity = createTime;
        }
    }

    /**
     * 连接统计
     */
    public static class ConnectionStats {
        public final int activeConnections;
        public final int totalConnections;
        public final int trackedConnections;

        ConnectionStats(int active, int total, int tracked) {
            this.activeConnections = active;
            this.totalConnections = total;
            this.trackedConnections = tracked;
        }
    }
}
```

### 2. 协议增强方案

#### 2.1 HTTP/2支持

**目标**: 添加HTTP/2协议支持

**方案**: 实现Http2Server类

```java
package kilim.http.ext;

import kilim.http.HttpServer;
import kilim.Scheduler;
import java.io.IOException;

/**
 * 支持HTTP/2的服务器实现
 */
public class Http2Server extends HttpServer {
    private final boolean http2Enabled;
    private final Http2ConnectionManager connectionManager;

    public Http2Server(int port, boolean http2Enabled) throws IOException {
        super(port);
        this.http2Enabled = http2Enabled;
        this.connectionManager = new Http2ConnectionManager();
    }

    /**
     * HTTP/2连接管理器
     */
    private static class Http2ConnectionManager {
        // HTTP/2流管理
        private final ConcurrentHashMap<Integer, Http2Stream> streams = 
            new ConcurrentHashMap<>();

        // HPACK压缩上下文
        private final Http2HpackContext hpackContext;

        /**
         * 处理HTTP/2帧
         */
        public void handleFrame(Http2Frame frame) {
            switch (frame.type) {
                case HEADERS:
                    handleHeadersFrame((Http2HeadersFrame) frame);
                    break;
                case DATA:
                    handleDataFrame((Http2DataFrame) frame);
                    break;
                case SETTINGS:
                    handleSettingsFrame((Http2SettingsFrame) frame);
                    break;
                // ...其他帧类型
            }
        }

        /**
         * 处理HEADERS帧
         */
        private void handleHeadersFrame(Http2HeadersFrame frame) {
            // 使用HPACK解压缩头
            Http2Headers headers = hpackContext.decode(frame.headerBlock);

            // 创建或获取流
            Http2Stream stream = streams.computeIfAbsent(
                frame.streamId,
                id -> new Http2Stream(id)
            );

            // 处理请求
            stream.processHeaders(headers);
        }

        /**
         * 处理DATA帧
         */
        private void handleDataFrame(Http2DataFrame frame) {
            Http2Stream stream = streams.get(frame.streamId);
            if (stream != null) {
                stream.processData(frame.data);
            }
        }

        /**
         * 处理SETTINGS帧
         */
        private void handleSettingsFrame(Http2SettingsFrame frame) {
            // 更新连接设置
            // ...
        }
    }

    /**
     * HTTP/2流
     */
    private static class Http2Stream {
        final int streamId;
        Http2Headers headers;
        java.io.ByteArrayOutputStream body;

        Http2Stream(int streamId) {
            this.streamId = streamId;
            this.body = new java.io.ByteArrayOutputStream();
        }

        void processHeaders(Http2Headers headers) {
            this.headers = headers;
        }

        void processData(byte[] data) throws IOException {
            body.write(data);
        }
    }

    /**
     * HTTP/2帧类型
     */
    private enum Http2FrameType {
        DATA, HEADERS, PRIORITY, RST_STREAM,
        SETTINGS, PUSH_PROMISE, PING, GOAWAY,
        WINDOW_UPDATE, CONTINUATION
    }
}
```

#### 2.2 WebSocket支持

**目标**: 添加WebSocket协议支持

**方案**: 实现WebSocketServer类

```java
package kilim.http.ext;

import kilim.http.HttpSession;
import kilim.Pausable;
import kilim.nio.EndPoint;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * WebSocket服务器实现
 */
public class WebSocketServer extends kilim.http.HttpServer {
    private final WebSocketHandler handler;

    public WebSocketServer(int port, WebSocketHandler handler) throws IOException {
        super(port, new WebSocketSessionFactory(handler));
        this.handler = handler;
    }

    /**
     * WebSocket会话工厂
     */
    private static class WebSocketSessionFactory 
            implements kilim.nio.NioSelectorScheduler.SessionFactory {
        final WebSocketHandler handler;

        WebSocketSessionFactory(WebSocketHandler handler) {
            this.handler = handler;
        }

        @Override
        public kilim.nio.SessionTask get() throws Exception {
            return new WebSocketSession(handler);
        }
    }

    /**
     * WebSocket会话
     */
    public static class WebSocketSession extends HttpSession {
        private final WebSocketHandler handler;
        private boolean handshakeComplete;

        WebSocketSession(WebSocketHandler handler) {
            this.handler = handler;
        }

        @Override
        public void execute() throws Pausable, Exception {
            kilim.http.HttpRequest req = new kilim.http.HttpRequest();

            // 执行WebSocket握手
            if (!handshakeComplete) {
                readRequest(req);
                if (isWebSocketUpgrade(req)) {
                    performHandshake(req);
                    handshakeComplete = true;
                    handler.onOpen(this);
                } else {
                    // 非WebSocket请求，返回错误
                    kilim.http.HttpResponse resp = new kilim.http.HttpResponse();
                    resp.status = kilim.http.HttpResponse.ST_BAD_REQUEST;
                    sendResponse(resp);
                    return;
                }
            }

            // 处理WebSocket帧
            while (true) {
                try {
                    WebSocketFrame frame = readFrame();
                    handleFrame(frame);
                } catch (IOException e) {
                    handler.onError(this, e);
                    break;
                }
            }

            handler.onClose(this);
            close();
        }

        /**
         * 判断是否是WebSocket升级请求
         */
        private boolean isWebSocketUpgrade(kilim.http.HttpRequest req) {
            return "Upgrade".equalsIgnoreCase(req.getHeader("Connection")) &&
                   "websocket".equalsIgnoreCase(req.getHeader("Upgrade"));
        }

        /**
         * 执行WebSocket握手
         */
        private void performHandshake(kilim.http.HttpRequest req) throws IOException, Pausable {
            String key = req.getHeader("Sec-WebSocket-Key");
            String acceptKey = generateAcceptKey(key);

            kilim.http.HttpResponse resp = new kilim.http.HttpResponse();
            resp.status = kilim.http.HttpResponse.ST_SWITCHING_PROTOCOLS;
            resp.addField("Upgrade", "websocket");
            resp.addField("Connection", "Upgrade");
            resp.addField("Sec-WebSocket-Accept", acceptKey);
            sendResponse(resp);
        }

        /**
         * 生成WebSocket接受密钥
         */
        private String generateAcceptKey(String key) {
            String guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                String combined = key + guid;
                byte[] hash = md.digest(combined.getBytes());
                return Base64.getEncoder().encodeToString(hash);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * 读取WebSocket帧
         */
        private WebSocketFrame readFrame() throws IOException, Pausable {
            // 读取帧头
            byte[] header = new byte[2];
            kilim.nio.ExposedBaos baos = new kilim.nio.ExposedBaos();

            endpoint.fill(java.nio.ByteBuffer.wrap(header), 2);

            boolean fin = (header[0] & 0x80) != 0;
            int opcode = header[0] & 0x0F;
            boolean masked = (header[1] & 0x80) != 0;
            int payloadLen = header[1] & 0x7F;

            // 读取扩展长度
            if (payloadLen == 126) {
                byte[] extLen = new byte[2];
                endpoint.fill(java.nio.ByteBuffer.wrap(extLen), 2);
                payloadLen = ((extLen[0] & 0xFF) << 8) | (extLen[1] & 0xFF);
            } else if (payloadLen == 127) {
                byte[] extLen = new byte[8];
                endpoint.fill(java.nio.ByteBuffer.wrap(extLen), 8);
                // 处理64位长度...
            }

            // 读取掩码
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                endpoint.fill(java.nio.ByteBuffer.wrap(mask), 4);
            }

            // 读取负载数据
            byte[] payload = new byte[payloadLen];
            endpoint.fill(java.nio.ByteBuffer.wrap(payload), payloadLen);

            // 解码数据
            if (masked) {
                for (int i = 0; i < payloadLen; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }

            return new WebSocketFrame(opcode, fin, payload);
        }

        /**
         * 处理WebSocket帧
         */
        private void handleFrame(WebSocketFrame frame) {
            switch (frame.opcode) {
                case WebSocketFrame.OPCODE_TEXT:
                    handler.onMessage(this, new String(frame.payload));
                    break;
                case WebSocketFrame.OPCODE_BINARY:
                    handler.onBinary(this, frame.payload);
                    break;
                case WebSocketFrame.OPCODE_CLOSE:
                    handler.onClose(this);
                    break;
                case WebSocketFrame.OPCODE_PING:
                    handler.onPing(this, frame.payload);
                    sendPong(frame.payload);
                    break;
                case WebSocketFrame.OPCODE_PONG:
                    handler.onPong(this, frame.payload);
                    break;
            }
        }

        /**
         * 发送文本消息
         */
        public void sendText(String text) throws IOException, Pausable {
            byte[] payload = text.getBytes();
            sendFrame(WebSocketFrame.OPCODE_TEXT, payload);
        }

        /**
         * 发送二进制消息
         */
        public void sendBinary(byte[] data) throws IOException, Pausable {
            sendFrame(WebSocketFrame.OPCODE_BINARY, data);
        }

        /**
         * 发送Ping
         */
        public void sendPing(byte[] data) throws IOException, Pausable {
            sendFrame(WebSocketFrame.OPCODE_PING, data);
        }

        /**
         * 发送Pong
         */
        public void sendPong(byte[] data) throws IOException, Pausable {
            sendFrame(WebSocketFrame.OPCODE_PONG, data);
        }

        /**
         * 发送帧
         */
        private void sendFrame(int opcode, byte[] payload) throws IOException, Pausable {
            kilim.nio.ExposedBaos baos = new kilim.nio.ExposedBaos();

            // 帧头
            int firstByte = 0x80 | opcode;
            baos.write(firstByte);

            // 长度
            int payloadLen = payload.length;
            if (payloadLen < 126) {
                baos.write(payloadLen);
            } else if (payloadLen < 65536) {
                baos.write(126);
                baos.write((payloadLen >> 8) & 0xFF);
                baos.write(payloadLen & 0xFF);
            } else {
                baos.write(127);
                // 写入64位长度...
            }

            // 负载
            baos.write(payload);

            // 发送
            endpoint.write(baos.toByteBuffer());
        }
    }

    /**
     * WebSocket帧
     */
    private static class WebSocketFrame {
        static final int OPCODE_CONTINUATION = 0x0;
        static final int OPCODE_TEXT = 0x1;
        static final int OPCODE_BINARY = 0x2;
        static final int OPCODE_CLOSE = 0x8;
        static final int OPCODE_PING = 0x9;
        static final int OPCODE_PONG = 0xA;

        final int opcode;
        final boolean fin;
        final byte[] payload;

        WebSocketFrame(int opcode, boolean fin, byte[] payload) {
            this.opcode = opcode;
            this.fin = fin;
            this.payload = payload;
        }
    }

    /**
     * WebSocket处理器接口
     */
    public interface WebSocketHandler {
        void onOpen(WebSocketSession session);
        void onMessage(WebSocketSession session, String message);
        void onBinary(WebSocketSession session, byte[] data);
        void onClose(WebSocketSession session);
        void onError(WebSocketSession session, Throwable error);
        void onPing(WebSocketSession session, byte[] data);
        void onPong(WebSocketSession session, byte[] data);
    }
}
```

### 3. 安全加固方案

#### 3.1 请求验证

**目标**: 验证和过滤恶意请求

**方案**: 实现RequestValidator类

```java
package kilim.http.ext;

import kilim.http.HttpRequest;
import java.util.regex.Pattern;

/**
 * 请求验证器，验证和过滤恶意请求
 */
public class RequestValidator {
    // 配置
    private final int maxUriLength;
    private final int maxHeaderSize;
    private final int maxBodySize;
    private final Pattern allowedMethods;
    private final Pattern allowedHosts;

    // 统计
    private final RequestStats stats = new RequestStats();

    public RequestValidator(int maxUriLength, int maxHeaderSize, int maxBodySize,
                        String allowedMethods, String allowedHosts) {
        this.maxUriLength = maxUriLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxBodySize = maxBodySize;
        this.allowedMethods = Pattern.compile(allowedMethods);
        this.allowedHosts = Pattern.compile(allowedHosts);
    }

    /**
     * 验证请求
     */
    public ValidationResult validate(HttpRequest req) {
        // 检查URI长度
        if (req.uriPath != null && req.uriPath.length() > maxUriLength) {
            stats.incrementRejected("uri_too_long");
            return ValidationResult.failure("URI too long");
        }

        // 检查方法
        if (req.method != null && !allowedMethods.matcher(req.method).matches()) {
            stats.incrementRejected("invalid_method");
            return ValidationResult.failure("Method not allowed");
        }

        // 检查Host头
        String host = req.getHeader("Host");
        if (host != null && !allowedHosts.matcher(host).matches()) {
            stats.incrementRejected("invalid_host");
            return ValidationResult.failure("Invalid host");
        }

        // 检查请求头数量
        if (req.nFields > maxHeaderSize) {
            stats.incrementRejected("too_many_headers");
            return ValidationResult.failure("Too many headers");
        }

        // 检查Content-Length
        String cl = req.getHeader("Content-Length");
        if (cl != null && !cl.isEmpty()) {
            try {
                int length = Integer.parseInt(cl);
                if (length > maxBodySize) {
                    stats.incrementRejected("body_too_large");
                    return ValidationResult.failure("Request body too large");
                }
            } catch (NumberFormatException e) {
                stats.incrementRejected("invalid_content_length");
                return ValidationResult.failure("Invalid Content-Length");
            }
        }

        stats.incrementAccepted();
        return ValidationResult.success();
    }

    /**
     * 获取统计信息
     */
    public RequestStats getStats() {
        return stats;
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        final boolean valid;
        final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        static ValidationResult failure(String message) {
            return new ValidationResult(false, message);
        }
    }

    /**
     * 请求统计
     */
    public static class RequestStats {
        private final java.util.concurrent.atomic.AtomicLong accepted = 
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong rejected = 
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> 
            rejectionReasons = new java.util.concurrent.ConcurrentHashMap<>();

        void incrementAccepted() {
            accepted.incrementAndGet();
        }

        void incrementRejected(String reason) {
            rejected.incrementAndGet();
            rejectionReasons.computeIfAbsent(
                reason, 
                k -> new java.util.concurrent.atomic.AtomicLong()
            ).incrementAndGet();
        }

        public long getAccepted() {
            return accepted.get();
        }

        public long getRejected() {
            return rejected.get();
        }

        public java.util.Map<String, Long> getRejectionReasons() {
            java.util.Map<String, Long> result = new java.util.HashMap<>();
            rejectionReasons.forEach((k, v) -> result.put(k, v.get()));
            return result;
        }
    }
}
```

#### 3.2 速率限制

**目标**: 防止DDoS攻击和滥用

**方案**: 实现RateLimiter类

```java
package kilim.http.ext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 速率限制器，防止DDoS攻击和滥用
 */
public class RateLimiter {
    // 配置
    private final int maxRequestsPerSecond;
    private final int maxRequestsPerMinute;
    private final int maxRequestsPerHour;

    // 限流数据
    private final ConcurrentHashMap<String, RateInfo> rateMap = 
        new ConcurrentHashMap<>();

    // 全局统计
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();

    public RateLimiter(int maxPerSecond, int maxPerMinute, int maxPerHour) {
        this.maxRequestsPerSecond = maxPerSecond;
        this.maxRequestsPerMinute = maxPerMinute;
        this.maxRequestsPerHour = maxPerHour;

        // 启动清理线程
        startCleanupThread();
    }

    /**
     * 检查是否允许请求
     */
    public boolean allowRequest(String clientIp) {
        totalRequests.incrementAndGet();

        RateInfo info = rateMap.computeIfAbsent(
            clientIp, 
            k -> new RateInfo()
        );

        long now = System.currentTimeMillis();

        // 检查秒级限制
        if (info.checkSecondLimit(now, maxRequestsPerSecond)) {
            rejectedRequests.incrementAndGet();
            return false;
        }

        // 检查分钟级限制
        if (info.checkMinuteLimit(now, maxRequestsPerMinute)) {
            rejectedRequests.incrementAndGet();
            return false;
        }

        // 检查小时级限制
        if (info.checkHourLimit(now, maxRequestsPerHour)) {
            rejectedRequests.incrementAndGet();
            return false;
        }

        return true;
    }

    /**
     * 获取统计信息
     */
    public RateLimiterStats getStats() {
        return new RateLimiterStats(
            totalRequests.get(),
            rejectedRequests.get(),
            rateMap.size()
        );
    }

    /**
     * 启动清理线程
     */
    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60000); // 每分钟清理一次
                    cleanupOldEntries();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * 清理旧条目
     */
    private void cleanupOldEntries() {
        long now = System.currentTimeMillis();
        long hourAgo = now - 3600000;

        rateMap.entrySet().removeIf(entry -> {
            RateInfo info = entry.getValue();
            return info.lastUpdate < hourAgo;
        });
    }

    /**
     * 速率信息
     */
    private static class RateInfo {
        volatile long lastUpdate;
        final AtomicInteger secondCount = new AtomicInteger();
        final AtomicInteger minuteCount = new AtomicInteger();
        final AtomicInteger hourCount = new AtomicInteger();

        long lastSecond;
        long lastMinute;
        long lastHour;

        boolean checkSecondLimit(long now, int limit) {
            if (now - lastSecond >= 1000) {
                secondCount.set(0);
                lastSecond = now;
            }
            return secondCount.incrementAndGet() > limit;
        }

        boolean checkMinuteLimit(long now, int limit) {
            if (now - lastMinute >= 60000) {
                minuteCount.set(0);
                lastMinute = now;
            }
            return minuteCount.incrementAndGet() > limit;
        }

        boolean checkHourLimit(long now, int limit) {
            if (now - lastHour >= 3600000) {
                hourCount.set(0);
                lastHour = now;
            }
            return hourCount.incrementAndGet() > limit;
        }
    }

    /**
     * 速率限制统计
     */
    public static class RateLimiterStats {
        public final long totalRequests;
        public final long rejectedRequests;
        public final int activeClients;

        RateLimiterStats(long total, long rejected, int active) {
            this.totalRequests = total;
            this.rejectedRequests = rejected;
            this.activeClients = active;
        }
    }
}
```

### 4. 功能完善方案

#### 4.1 中间件机制

**目标**: 实现灵活的中间件链

**方案**: 实现MiddlewarePipeline类

```java
package kilim.http.ext;

import kilim.http.HttpRequest;
import kilim.http.HttpResponse;
import kilim.Pausable;
import java.util.ArrayList;
import java.util.List;

/**
 * 中间件管道，实现请求处理链
 */
public class MiddlewarePipeline {
    private final List<Middleware> middlewares = new ArrayList<>();
    private final kilim.http.HttpSession.StringRouter handler;

    public MiddlewarePipeline(kilim.http.HttpSession.StringRouter handler) {
        this.handler = handler;
    }

    /**
     * 添加中间件
     */
    public MiddlewarePipeline use(Middleware middleware) {
        middlewares.add(middleware);
        return this;
    }

    /**
     * 处理请求
     */
    public String handle(HttpRequest req) throws Pausable, Exception {
        // 创建上下文
        MiddlewareContext context = new MiddlewareContext(req, middlewares, handler);

        // 执行中间件链
        return context.next();
    }

    /**
     * 中间件接口
     */
    public interface Middleware {
        void process(MiddlewareContext context) throws Pausable, Exception;
    }

    /**
     * 中间件上下文
     */
    public static class MiddlewareContext {
        final HttpRequest request;
        final List<Middleware> middlewares;
        final kilim.http.HttpSession.StringRouter handler;
        int currentIndex = 0;

        MiddlewareContext(HttpRequest request, List<Middleware> middlewares, 
                       kilim.http.HttpSession.StringRouter handler) {
            this.request = request;
            this.middlewares = middlewares;
            this.handler = handler;
        }

        /**
         * 执行下一个中间件
         */
        public String next() throws Pausable, Exception {
            if (currentIndex < middlewares.size()) {
                Middleware middleware = middlewares.get(currentIndex++);
                middleware.process(this);
                return null;
            } else {
                return handler.route(request);
            }
        }
    }
}
```

#### 4.2 Cookie和Session管理

**目标**: 提供Cookie和Session管理功能

**方案**: 实现SessionManager类

```java
package kilim.http.ext;

import kilim.http.HttpRequest;
import kilim.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session管理器，提供会话管理功能
 */
public class SessionManager {
    // Session存储
    private final ConcurrentHashMap<String, HttpSession> sessions = 
        new ConcurrentHashMap<>();

    // 配置
    private final long sessionTimeout;
    private final String cookieName;
    private final String cookiePath;
    private final String cookieDomain;

    public SessionManager(long sessionTimeout, String cookieName) {
        this.sessionTimeout = sessionTimeout;
        this.cookieName = cookieName;
        this.cookiePath = "/";
        this.cookieDomain = null;

        // 启动清理线程
        startCleanupThread();
    }

    /**
     * 创建新会话
     */
    public HttpSession createSession() {
        String sessionId = generateSessionId();
        HttpSession session = new HttpSession(sessionId);
        sessions.put(sessionId, session);
        return session;
    }

    /**
     * 获取会话
     */
    public HttpSession getSession(HttpRequest req) {
        String sessionId = extractSessionId(req);
        if (sessionId == null) {
            return null;
        }

        HttpSession session = sessions.get(sessionId);
        if (session != null) {
            session.touch();
        }
        return session;
    }

    /**
     * 设置会话Cookie
     */
    public void setSessionCookie(HttpResponse resp, HttpSession session) {
        Cookie cookie = new Cookie(cookieName, session.getId());
        cookie.setPath(cookiePath);
        if (cookieDomain != null) {
            cookie.setDomain(cookieDomain);
        }
        cookie.setMaxAge((int)(sessionTimeout / 1000));
        resp.addCookie(cookie);
    }

    /**
     * 从请求中提取Session ID
     */
    private String extractSessionId(HttpRequest req) {
        String cookieHeader = req.getHeader("Cookie");
        if (cookieHeader == null) {
            return null;
        }

        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].equals(cookieName)) {
                return parts[1];
            }
        }
        return null;
    }

    /**
     * 生成Session ID
     */
    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 启动清理线程
     */
    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60000); // 每分钟清理一次
                    cleanupExpiredSessions();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * 清理过期会话
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();

        sessions.entrySet().removeIf(entry -> {
            HttpSession session = entry.getValue();
            return (now - session.getLastAccessed()) > sessionTimeout;
        });
    }

    /**
     * 会话
     */
    public static class HttpSession {
        private final String id;
        private final long createTime;
        private volatile long lastAccessed;
        private final ConcurrentHashMap<String, Object> attributes = 
            new ConcurrentHashMap<>();

        HttpSession(String id) {
            this.id = id;
            this.createTime = System.currentTimeMillis();
            this.lastAccessed = createTime;
        }

        public String getId() {
            return id;
        }

        public void touch() {
            this.lastAccessed = System.currentTimeMillis();
        }

        public long getLastAccessed() {
            return lastAccessed;
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

    /**
     * Cookie
     */
    public static class Cookie {
        private final String name;
        private final String value;
        private String domain;
        private String path;
        private long maxAge;
        private boolean httpOnly;
        private boolean secure;

        public Cookie(String name, String value) {
            this.name = name;
            this.value = value;
        }

        public String toSetCookieHeader() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append("=").append(value);

            if (domain != null) {
                sb.append("; Domain=").append(domain);
            }
            if (path != null) {
                sb.append("; Path=").append(path);
            }
            if (maxAge >= 0) {
                sb.append("; Max-Age=").append(maxAge);
            }
            if (httpOnly) {
                sb.append("; HttpOnly");
            }
            if (secure) {
                sb.append("; Secure");
            }

            return sb.toString();
        }

        // Getter和Setter方法
        public Cookie setDomain(String domain) {
            this.domain = domain;
            return this;
        }

        public Cookie setPath(String path) {
            this.path = path;
            return this;
        }

        public Cookie setMaxAge(long maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        public Cookie setHttpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
            return this;
        }

        public Cookie setSecure(boolean secure) {
            this.secure = secure;
            return this;
        }
    }
}
```

### 5. 可观测性提升方案

#### 5.1 日志系统

**目标**: 实现结构化日志系统

**方案**: 实现HttpLogger类

```java
package kilim.http.ext;

import kilim.http.HttpRequest;
import kilim.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * HTTP日志记录器
 */
public class HttpLogger {
    // 日志级别
    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    // 日志格式
    private final LogFormat format;
    private final Level minLevel;
    private final java.io.PrintWriter writer;

    // 时间格式化器
    private static final DateTimeFormatter TIME_FORMATTER = 
        DateTimeFormatter.ISO_INSTANT;

    public HttpLogger(LogFormat format, Level minLevel, java.io.PrintWriter writer) {
        this.format = format;
        this.minLevel = minLevel;
        this.writer = writer;
    }

    /**
     * 记录请求
     */
    public void logRequest(HttpRequest req, HttpResponse resp, 
                      long duration, Level level) {
        if (level.ordinal() < minLevel.ordinal()) {
            return;
        }

        LogEntry entry = new LogEntry(
            Instant.now(),
            req.method,
            req.uriPath,
            resp.status,
            duration,
            req.getHeader("User-Agent"),
            req.getHeader("X-Forwarded-For")
        );

        String logLine = format.format(entry);
        writer.println(logLine);
        writer.flush();
    }

    /**
     * 记录错误
     */
    public void logError(String message, Throwable error) {
        if (Level.ERROR.ordinal() < minLevel.ordinal()) {
            return;
        }

        writer.println(format.formatError(Instant.now(), message, error));
        writer.flush();
    }

    /**
     * 日志格式
     */
    public interface LogFormat {
        String format(LogEntry entry);
        String formatError(Instant timestamp, String message, Throwable error);
    }

    /**
     * 日志条目
     */
    public static class LogEntry {
        final Instant timestamp;
        final String method;
        final String uri;
        final byte[] status;
        final long duration;
        final String userAgent;
        final String forwardedFor;

        LogEntry(Instant timestamp, String method, String uri, 
                 byte[] status, long duration, 
                 String userAgent, String forwardedFor) {
            this.timestamp = timestamp;
            this.method = method;
            this.uri = uri;
            this.status = status;
            this.duration = duration;
            this.userAgent = userAgent;
            this.forwardedFor = forwardedFor;
        }
    }

    /**
     * 常用日志格式
     */
    public static class CommonLogFormat implements LogFormat {
        @Override
        public String format(LogEntry entry) {
            return String.format(
                "%s - - [%s] "%s %s HTTP/1.1" %d %d",
                entry.forwardedFor != null ? entry.forwardedFor : "-",
                TIME_FORMATTER.format(entry.timestamp),
                entry.method,
                entry.uri,
                extractStatusCode(entry.status),
                entry.duration
            );
        }

        @Override
        public String formatError(Instant timestamp, String message, Throwable error) {
            return String.format(
                "[%s] ERROR: %s - %s",
                TIME_FORMATTER.format(timestamp),
                message,
                error.getMessage()
            );
        }

        private int extractStatusCode(byte[] status) {
            try {
                String statusStr = new String(status);
                return Integer.parseInt(statusStr.split(" ")[0]);
            } catch (Exception e) {
                return 500;
            }
        }
    }

    /**
     * JSON日志格式
     */
    public static class JsonLogFormat implements LogFormat {
        @Override
        public String format(LogEntry entry) {
            return String.format(
                "{"timestamp":"%s","method":"%s","uri":"%s","status":%d,"duration":%d}",
                TIME_FORMATTER.format(entry.timestamp),
                entry.method,
                escapeJson(entry.uri),
                extractStatusCode(entry.status),
                entry.duration
            );
        }

        @Override
        public String formatError(Instant timestamp, String message, Throwable error) {
            return String.format(
                "{"timestamp":"%s","level":"error","message":"%s","error":"%s"}",
                TIME_FORMATTER.format(timestamp),
                escapeJson(message),
                escapeJson(error.getMessage())
            );
        }

        private String escapeJson(String s) {
            return s.replace("\", "\\")
                   .replace(""", "\"")
                   .replace("
", "\n")
                   .replace("
", "\r");
        }

        private int extractStatusCode(byte[] status) {
            try {
                String statusStr = new String(status);
                return Integer.parseInt(statusStr.split(" ")[0]);
            } catch (Exception e) {
                return 500;
            }
        }
    }
}
```

#### 5.2 监控指标

**目标**: 收集和暴露性能指标

**方案**: 实现HttpMetrics类

```java
package kilim.http.ext;

import kilim.http.HttpRequest;
import kilim.http.HttpResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP监控指标收集器
 */
public class HttpMetrics {
    // 请求计数
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong activeRequests = new AtomicLong();

    // 响应时间统计
    private final AtomicLong totalResponseTime = new AtomicLong();
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxResponseTime = new AtomicLong(0);

    // 状态码统计
    private final ConcurrentHashMap<Integer, AtomicInteger> statusCodes = 
        new ConcurrentHashMap<>();

    // 错误统计
    private final AtomicLong totalErrors = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> errorTypes = 
        new ConcurrentHashMap<>();

    /**
     * 记录请求开始
     */
    public void recordRequestStart() {
        activeRequests.incrementAndGet();
    }

    /**
     * 记录请求完成
     */
    public void recordRequestEnd(HttpRequest req, HttpResponse resp, long duration) {
        activeRequests.decrementAndGet();
        totalRequests.incrementAndGet();
        totalResponseTime.addAndGet(duration);

        // 更新最小/最大响应时间
        updateMinResponseTime(duration);
        updateMaxResponseTime(duration);

        // 记录状态码
        recordStatusCode(resp.status);
    }

    /**
     * 记录错误
     */
    public void recordError(String errorType) {
        totalErrors.incrementAndGet();
        errorTypes.computeIfAbsent(
            errorType,
            k -> new AtomicLong()
        ).incrementAndGet();
    }

    /**
     * 获取指标摘要
     */
    public MetricsSnapshot getSnapshot() {
        return new MetricsSnapshot(
            totalRequests.get(),
            activeRequests.get(),
            getAverageResponseTime(),
            minResponseTime.get(),
            maxResponseTime.get(),
            getStatusCodes(),
            totalErrors.get(),
            getErrorTypes()
        );
    }

    /**
     * 计算平均响应时间
     */
    private double getAverageResponseTime() {
        long total = totalRequests.get();
        return total > 0 ? (double) totalResponseTime.get() / total : 0;
    }

    /**
     * 更新最小响应时间
     */
    private void updateMinResponseTime(long duration) {
        long current = minResponseTime.get();
        while (duration < current) {
            if (minResponseTime.compareAndSet(current, duration)) {
                break;
            }
            current = minResponseTime.get();
        }
    }

    /**
     * 更新最大响应时间
     */
    private void updateMaxResponseTime(long duration) {
        long current = maxResponseTime.get();
        while (duration > current) {
            if (maxResponseTime.compareAndSet(current, duration)) {
                break;
            }
            current = maxResponseTime.get();
        }
    }

    /**
     * 记录状态码
     */
    private void recordStatusCode(byte[] status) {
        try {
            String statusStr = new String(status);
            int code = Integer.parseInt(statusStr.split(" ")[0]);
            statusCodes.computeIfAbsent(
                code,
                k -> new AtomicInteger()
            ).incrementAndGet();
        } catch (Exception e) {
            // 忽略解析错误
        }
    }

    /**
     * 获取状态码统计
     */
    private java.util.Map<Integer, Integer> getStatusCodes() {
        java.util.Map<Integer, Integer> result = new java.util.HashMap<>();
        statusCodes.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    /**
     * 获取错误类型统计
     */
    private java.util.Map<String, Long> getErrorTypes() {
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        errorTypes.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    /**
     * 指标快照
     */
    public static class MetricsSnapshot {
        public final long totalRequests;
        public final long activeRequests;
        public final double averageResponseTime;
        public final long minResponseTime;
        public final long maxResponseTime;
        public final java.util.Map<Integer, Integer> statusCodes;
        public final long totalErrors;
        public final java.util.Map<String, Long> errorTypes;

        MetricsSnapshot(long totalRequests, long activeRequests, 
                    double averageResponseTime, long minResponseTime, 
                    long maxResponseTime, 
                    java.util.Map<Integer, Integer> statusCodes,
                    long totalErrors, 
                    java.util.Map<String, Long> errorTypes) {
            this.totalRequests = totalRequests;
            this.activeRequests = activeRequests;
            this.averageResponseTime = averageResponseTime;
            this.minResponseTime = minResponseTime;
            this.maxResponseTime = maxResponseTime;
            this.statusCodes = statusCodes;
            this.totalErrors = totalErrors;
            this.errorTypes = errorTypes;
        }
    }
}
```

---

## 实施路线图

### 第一阶段：性能优化（1-2个月）

**目标**: 解决性能瓶颈，提高吞吐量

1. **请求解析优化**
   - 实现EnhancedHttpRequest
   - 优化缓冲区管理
   - 添加字段缓存
   - 改进Chunked处理

2. **响应构建优化**
   - 实现EnhancedHttpResponse
   - 添加响应压缩
   - 优化响应头写入
   - 实现对象池

3. **连接管理优化**
   - 实现ConnectionManager
   - 添加连接池
   - 实现超时控制
   - 添加连接统计

### 第二阶段：安全加固（1-2个月）

**目标**: 提升安全性，防止攻击

1. **请求验证**
   - 实现RequestValidator
   - 添加URI长度限制
   - 添加方法验证
   - 添加Host验证

2. **速率限制**
   - 实现RateLimiter
   - 添加多级限流
   - 实现统计功能
   - 添加自动清理

### 第三阶段：协议增强（2-3个月）

**目标**: 支持现代协议

1. **HTTP/2支持**
   - 实现Http2Server
   - 添加帧处理
   - 实现HPACK压缩
   - 支持多路复用

2. **WebSocket支持**
   - 实现WebSocketServer
   - 添加握手处理
   - 实现帧编解码
   - 添加Ping/Pong支持

### 第四阶段：功能完善（2-3个月）

**目标**: 提供完整功能集

1. **中间件机制**
   - 实现MiddlewarePipeline
   - 添加常用中间件
   - 支持链式调用
   - 提供上下文

2. **Session管理**
   - 实现SessionManager
   - 添加Cookie支持
   - 实现Session存储
   - 添加过期清理

3. **文件上传**
   - 实现Multipart解析
   - 添加进度跟踪
   - 支持大文件上传
   - 添加临时文件管理

4. **静态资源服务**
   - 实现资源缓存
   - 添加ETag支持
   - 支持范围请求
   - 添加压缩支持

### 第五阶段：可观测性（1-2个月）

**目标**: 提升可观测性

1. **日志系统**
   - 实现HttpLogger
   - 支持多种格式
   - 添加日志级别
   - 支持异步写入

2. **监控指标**
   - 实现HttpMetrics
   - 收集性能指标
   - 暴露监控端点
   - 支持指标导出

3. **请求追踪**
   - 实现TraceId生成
   - 添加分布式追踪
   - 支持上下文传递
   - 集成日志系统

---

## 总结

本改进方案针对Kilim HTTP的核心问题，提出了全面的改进措施：

1. **性能优化**: 通过优化请求解析、响应构建和连接管理，显著提升性能
2. **协议增强**: 添加HTTP/2、WebSocket等现代协议支持
3. **安全加固**: 实现请求验证和速率限制，提升安全性
4. **功能完善**: 添加中间件、Session管理等关键功能
5. **可观测性**: 实现日志和监控，提升运维能力

所有改进方案均采用继承方式实现，不修改原有代码，确保向后兼容。建议按照实施路线图分阶段实施，每个阶段完成后进行充分测试，确保稳定性和性能提升。
