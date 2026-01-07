# Kilim HTTP 扩展分析与 solon-server-kilim 实现方案

## 概述

本文档分析了如何基于 `kilim.http` 包开发一个类似 `solon-server-kilim` 的 HTTP 服务器，并提出了必要的功能增强方案。

## 需求分析

### solon-server-kilim 核心功能

1. **HTTP 服务器功能**
   - 支持 HTTP/1.1 协议
   - 支持静态文件服务
   - 支持动态内容生成
   - 支持请求路由
   - 支持会话管理

2. **协程集成**
   - 使用协程处理请求
   - 高并发处理能力
   - 非阻塞 I/O 操作

3. **其他特性**
   - 支持热重载
   - 支持请求日志
   - 支持健康检查
   - 配置管理

## Kilim HTTP 当前状态

### 已有功能

1. **基础 HTTP 支持**
   - HTTP/1.1 协议实现
   - GET/POST/PUT/DELETE 方法支持
   - 请求头解析
   - 响应构建
   - 基于协程的并发处理

2. **协程集成**
   - 与 Kilim Task 系统集成
   - 使用 NIOSelectorScheduler
   - 支持非阻塞 I/O

3. **MVC 路由**
   - KilimMvc 框架支持
   - 灵活的路由机制
   - 支持多参数路由

### 功能差距分析

#### 1. 文件上传支持

**当前状态**：
- 不支持 multipart/form-data
- 不支持 multipart/mixed
- 不支持文件流式传输
- 不支持大文件分块上传

**影响**：
- 无法处理文件上传表单
- 无法处理大文件传输
- 无法实现文件服务器功能
- 限制 Web 应用开发能力

#### 2. WebSocket 支持

**当前状态**：
- 不支持 WebSocket 协议
- 不支持协议升级（Upgrade 头）
- 不支持双向通信
- 不支持实时推送

**影响**：
- 无法实现实时应用
- 无法支持聊天室功能
- 无法实现实时通知
- 无法支持协作编辑

#### 3. HTTPS 支持

**当前状态**：
- 不支持 SSL/TLS 加密
- 不支持证书管理
- 不支持安全连接
- 仅支持明文 HTTP

**影响**：
- 无法提供安全传输
- 无法保护敏感数据
- 无法满足安全合规要求
- 不支持现代 Web 标准

#### 4. 其他高级功能

**当前状态**：
- 不支持 HTTP/2
- 不支持 Server-Sent Events
- 不支持流式响应
- 不支持请求压缩
- 不支持 Cookie 管理

**影响**：
- 性能优化受限
- 功能完整性不足
- 用户体验受限
- 不符合现代 Web 标准

## 实现方案

### 1. 文件上传支持

#### 方案设计

```java
public class MultipartUploadHandler {
    private static final int MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int MAX_PART_SIZE = 10 * 1024 * 1024; // 10MB per part

    public void handleMultipartUpload(HttpRequest req, HttpResponse resp) 
            throws IOException, Pausable {
        String contentType = req.getHeader("Content-Type");
        String boundary = extractBoundary(contentType);

        // 解析 multipart 请求
        List<UploadedFile> files = parseMultipartRequest(req, boundary);

        // 验证文件大小
        for (UploadedFile file : files) {
            if (file.size > MAX_FILE_SIZE) {
                resp.status = HttpResponse.ST_REQUEST_ENTITY_TOO_LARGE;
                resp.setContentType("text/plain");
                resp.getOutputStream().write(
                    "File too large. Maximum size: " + MAX_FILE_SIZE + " bytes"
                );
                return;
            }
        }

        // 处理文件
        for (UploadedFile file : files) {
            processUploadedFile(file);
        }

        // 发送成功响应
        resp.status = HttpResponse.ST_OK;
        resp.setContentType("application/json");
        String jsonResponse = buildUploadResponse(files);
        resp.getOutputStream().write(jsonResponse.getBytes());
    }

    private String extractBoundary(String contentType) {
        int idx = contentType.indexOf("boundary=");
        if (idx > 0) {
            return contentType.substring(idx + 9).trim();
        }
        return null;
    }

    private List<UploadedFile> parseMultipartRequest(HttpRequest req, String boundary) 
            throws IOException, Pausable {
        List<UploadedFile> files = new ArrayList<>();
        String line;

        while ((line = req.readLine()) != null) {
            if (line.startsWith("--" + boundary)) {
                // 新部分开始
                UploadedFile file = parsePart(req, boundary);
                if (file != null) {
                    files.add(file);
                }
            }
        }

        return files;
    }

    private UploadedFile parsePart(HttpRequest req, String boundary) 
            throws IOException, Pausable {
        // 解析部分头
        String line;
        while ((line = req.readLine()) != null) {
            if (line.startsWith("Content-Disposition:")) {
                // 解析文件信息
                String filename = extractFilename(line);
                String contentType = extractContentType(line);

                // 读取文件内容
                byte[] content = readFileContent(req);

                return new UploadedFile(filename, contentType, content);
            }
        }
        return null;
    }

    private String extractFilename(String line) {
        int idx = line.indexOf("filename=");
        if (idx > 0) {
            int start = idx + 10; // filename="
            int end = line.indexOf(""", start);
            if (end > 0) {
                return line.substring(start, end);
            }
        }
        return "unknown";
    }
}
```

#### 集成到 HttpServer

```java
public class EnhancedHttpServer extends HttpServer {
    private MultipartUploadHandler uploadHandler;

    public EnhancedHttpServer(int port) throws IOException {
        super(port);
        this.uploadHandler = new MultipartUploadHandler();
    }

    @Override
    public void listen(int port, Class<? extends HttpSession> httpSessionClass, 
                     Scheduler httpSessionScheduler) throws IOException {
        // 添加上传处理器
        super.listen(port, new EnhancedSessionFactory(httpSessionClass, uploadHandler), 
                   httpSessionScheduler);
    }
}

class EnhancedSessionFactory implements SessionFactory {
    private MultipartUploadHandler uploadHandler;

    public EnhancedSessionFactory(MultipartUploadHandler uploadHandler) {
        this.uploadHandler = uploadHandler;
    }

    @Override
    public SessionTask get() throws Exception {
        return new EnhancedHttpSession(handler);
    }
}
```

### 2. WebSocket 支持

#### 方案设计

```java
public class WebSocketHandler {
    private static final String WS_UPGRADE_HEADER = "Upgrade";
    private static final String WS_CONNECTION_HEADER = "Connection";
    private static final String WS_PROTOCOL = "websocket";
    private static final String WS_VERSION = "13";

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor;

    public WebSocketHandler() {
        this.heartbeatExecutor = Executors.newScheduledThreadPool(1);
    }

    public boolean handleWebSocketUpgrade(HttpRequest req, HttpResponse resp) 
            throws IOException, Pausable {
        // 检查协议升级请求
        String upgrade = req.getHeader(WS_UPGRADE_HEADER);
        String connection = req.getHeader(WS_CONNECTION_HEADER);

        if ("websocket".equalsIgnoreCase(upgrade) && 
            "Upgrade".equalsIgnoreCase(connection)) {
            // 执行 WebSocket 握手
            performWebSocketHandshake(req, resp);
            return true;
        }
        return false;
    }

    private void performWebSocketHandshake(HttpRequest req, HttpResponse resp) 
            throws IOException, Pausable {
        // 设置响应头
        resp.status = HttpResponse.ST_SWITCHING_PROTOCOLS;
        resp.addField("Upgrade", WS_PROTOCOL);
        resp.addField("Connection", "Upgrade");
        resp.addField("Sec-WebSocket-Accept", WS_VERSION);

        // 创建 WebSocket 会话
        String sessionId = generateSessionId();
        WebSocketSession session = new WebSocketSession(sessionId);
        sessions.put(sessionId, session);

        // 发送握手响应
        resp.writeTo(req.endpoint);

        // 启动心跳
        startHeartbeat(session);
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    private void startHeartbeat(WebSocketSession session) {
        // 每30秒发送心跳
        heartbeatExecutor.scheduleAtFixedRate(
            new HeartbeatTask(session),
            30, 30, TimeUnit.SECONDS
        );
    }

    private static class HeartbeatTask implements Runnable {
        private final WebSocketSession session;

        HeartbeatTask(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void run() {
            try {
                if (session.isOpen()) {
                    session.sendPing();
                }
            } catch (Exception e) {
                session.close();
            }
        }
    }
}

public class WebSocketSession {
    private final String sessionId;
    private final EndPoint endpoint;
    private volatile boolean open = true;
    private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();

    public WebSocketSession(String sessionId, EndPoint endpoint) {
        this.sessionId = sessionId;
        this.endpoint = endpoint;
    }

    public void send(String message) throws IOException {
        if (!open) {
            throw new IOException("Session closed");
        }
        messageQueue.offer(message);
        processQueue();
    }

    public void sendPing() throws IOException {
        send("{"type":"ping"}");
    }

    public void sendPong() throws IOException {
        send("{"type":"pong"}");
    }

    private void processQueue() throws IOException {
        String message;
        while ((message = messageQueue.poll()) != null) {
            // 发送消息帧
            sendFrame(message);
        }
    }

    private void sendFrame(String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(data.length + 2 + 8); // 帧头

        // FIN: 1 byte
        buffer.put((byte) 0x81);

        // 长度：7 bytes
        buffer.putShort((short) data.length());

        // 掩码：4 bytes
        buffer.putInt(data.length());

        // 数据
        buffer.put(data);

        endpoint.write(buffer.array(), 0, buffer.position());
    }

    public void close() {
        open = false;
        sessions.remove(sessionId);
    }

    public boolean isOpen() {
        return open;
    }
}
```

#### 集成到 HttpServer

```java
public class WebSocketHttpServer extends HttpServer {
    private WebSocketHandler wsHandler;

    public WebSocketHttpServer(int port) throws IOException {
        super(port);
        this.wsHandler = new WebSocketHandler();
    }

    @Override
    public void listen(int port, Class<? extends HttpSession> httpSessionClass, 
                     Scheduler httpSessionScheduler) throws IOException {
        super.listen(port, new WebSocketSessionFactory(httpSessionClass, wsHandler), 
                   httpSessionScheduler);
    }
}

class WebSocketSessionFactory implements SessionFactory {
    private WebSocketHandler wsHandler;

    public WebSocketSessionFactory(WebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @Override
    public SessionTask get() throws Exception {
        return new WebSocketHttpSession(handler);
    }
}
```

### 3. HTTPS 支持

#### 方案设计

```java
public class SSLContext {
    private SSLServerSocketFactory sslSocketFactory;
    private SSLContext sslContext;
    private String[] enabledProtocols;
    private String[] enabledCipherSuites;

    public SSLContext(String keystorePath, String keystorePassword) 
            throws Exception {
        // 初始化 SSL 上下文
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(new FileInputStream(keystorePath), 
                    keystorePassword.toCharArray());

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, keystorePassword);

        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf);

        // 配置协议和密码套件
        enabledProtocols = new String[]{"TLSv1.2", "TLSv1.1"};
        enabledCipherSuites = new String[]{
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"
        };
    }

    public SSLEngine createSSLEngine() {
        return sslContext.createSSLEngine();
    }

    public SSLServerSocketFactory createSSLServerSocketFactory() {
        return sslContext.getServerSocketFactory();
    }
}
```

#### 集成到 HttpServer

```java
public class HttpsServer extends HttpServer {
    private boolean sslEnabled;
    private SSLContext sslContext;

    public HttpsServer(int port, boolean sslEnabled, 
                     String keystorePath, String keystorePassword) 
            throws IOException {
        if (sslEnabled) {
            initSSL(keystorePath, keystorePassword);
        }
        listen(port, HttpSession.class, Scheduler.getDefaultScheduler());
    }

    private void initSSL(String keystorePath, String keystorePassword) 
            throws Exception {
        sslContext = new SSLContext(keystorePath, keystorePassword);
        sslEnabled = true;
    }
}
```

### 4. 其他增强功能

#### 请求验证

```java
public class RequestValidator {
    private static final int MAX_URI_LENGTH = 2048;
    private static final int MAX_HEADER_SIZE = 8192;
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024; // 10MB

    public static void validate(HttpRequest req) throws HttpRequestException {
        // 验证 URI 长度
        if (req.uriPath.length() > MAX_URI_LENGTH) {
            throw new HttpRequestException(
                ErrorType.INVALID_URI,
                "URI too long"
            );
        }

        // 验证请求头数量
        if (req.nFields > MAX_HEADER_SIZE) {
            throw new HttpRequestException(
                ErrorType.INVALID_HEADER,
                "Too many headers"
            );
        }

        // 验证请求体大小
        if (req.contentLength > MAX_BODY_SIZE) {
            throw new HttpRequestException(
                ErrorType.INVALID_HEADER,
                "Request body too large"
            );
        }
    }
}
```

#### 速率限制

```java
public class RateLimiter {
    private final Map<String, RateInfo> rateMap = new ConcurrentHashMap<>();
    private static final int DEFAULT_LIMIT = 1000; // 每秒请求数
    private static final int DEFAULT_WINDOW = 60000; // 时间窗口（毫秒）

    public boolean checkRateLimit(String clientIp) {
        RateInfo info = rateMap.get(clientIp);
        long now = System.currentTimeMillis();

        if (info == null) {
            info = new RateInfo(now);
            rateMap.put(clientIp, info);
            return true;
        }

        // 检查时间窗口内的请求数
        if (now - info.windowStart > DEFAULT_WINDOW) {
            info.count = 1;
            info.windowStart = now;
            return true;
        }

        if (info.count >= DEFAULT_LIMIT) {
            return false;
        }

        info.count++;
        return true;
    }

    private static class RateInfo {
        long windowStart;
        int count;

        RateInfo(long start) {
            this.windowStart = start;
            this.count = 1;
        }
    }
}
```

#### 服务器监控

```java
public class ServerMetrics {
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);

    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    public void connectionOpened() {
        activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public void bytesSent(int bytes) {
        totalBytesSent.addAndGet(bytes);
    }

    public void bytesReceived(int bytes) {
        totalBytesReceived.addAndGet(bytes);
    }

    public void printMetrics() {
        System.out.println("=== Server Metrics ===");
        System.out.println("Total Requests: " + totalRequests.get());
        System.out.println("Active Connections: " + activeConnections.get());
        System.out.println("Bytes Sent: " + totalBytesSent.get());
        System.out.println("Bytes Received: " + totalBytesReceived.get());
    }
}
```

## 实现优先级

### 高优先级功能

1. **HTTPS 支持** - 安全传输是现代 Web 应用的基础
2. **文件上传** - 文件服务器是核心功能之一
3. **WebSocket 支持** - 实时通信是现代 Web 应用的关键特性

### 中优先级功能

1. **请求验证** - 防止恶意请求和资源耗尽
2. **速率限制** - 防止 DDoS 攻击
3. **服务器监控** - 运维和诊断的基础

### 低优先级功能

1. **HTTP/2 支持** - 性能优化
2. **响应压缩** - 提升传输效率
3. **Cookie 管理** - 会话管理增强
4. **流式响应** - 大数据传输优化

## 实施建议

### 第一阶段：基础增强（1-2个月）

1. 实现请求验证器
2. 实现速率限制器
3. 添加服务器监控
4. 添加 Cookie 支持

### 第二阶段：核心功能（2-3个月）

1. 实现文件上传支持
2. 实现 WebSocket 支持
3. 添加响应压缩

### 第三阶段：高级功能（3-6个月）

1. 实现 HTTPS 支持
2. 实现 HTTP/2 支持
3. 实现流式响应
4. 添加高级路由功能

## 测试策略

### 单元测试

1. 文件上传测试
   - 测试小文件上传
   - 测试大文件上传
   - 测试多文件上传
   - 测试超时处理

2. WebSocket 测试
   - 测试握手流程
   - 测试消息收发
   - 测试心跳机制
   - 测试连接管理

3. HTTPS 测试
   - 测试 SSL 握手
   - 测试证书验证
   - 测试加密传输
   - 测试性能影响

### 集成测试

1. 端到端测试
   - 测试完整请求流程
   - 测试并发处理
   - 测试错误处理
   - 测试资源清理

2. 性能测试
   - 并发连接测试
   - 大文件传输测试
   - 长时间运行测试
   - 内存使用测试

## 风险评估

### 技术风险

1. **复杂性增加**
   - 新增功能增加代码复杂度
   - 需要更多测试
   - 维护成本增加

2. **性能影响**
   - HTTPS 加密可能影响性能
   - 文件上传增加内存使用
   - WebSocket 增加连接管理开销

3. **兼容性问题**
   - 需要考虑向后兼容性
   - 需要测试不同客户端
   - 需要处理边界情况

### 缓解措施

1. **分阶段实施**
   - 优先实现核心功能
   - 逐步添加高级特性
   - 充分测试每个阶段
   - 及时调整和优化

2. **模块化设计**
   - 功能模块独立
   - 可选功能配置
   - 易于维护和扩展

3. **性能监控**
   - 持续监控性能指标
   - 及时发现性能问题
   - 建立性能基线

## 总结

本文档详细分析了基于 `kilim.http` 包开发 `solon-server-kilim` 类似服务器所需的工作，包括：

1. **功能差距分析**：
   - 文件上传支持缺失
   - WebSocket 支持缺失
   - HTTPS 支持缺失
   - 其他高级功能缺失

2. **详细实现方案**：
   - 文件上传完整实现
   - WebSocket 协议支持
   - HTTPS/TLS 安全传输
   - 请求验证和速率限制
   - 服务器监控和指标收集

3. **实施优先级**：
   - 高优先级：HTTPS、文件上传、WebSocket
   - 中优先级：请求验证、速率限制、监控
   - 低优先级：HTTP/2、响应压缩、Cookie 管理

4. **测试策略**：
   - 单元测试方案
   - 集成测试方案
   - 性能测试方法
   - 风险评估和缓解措施

5. **风险管理和缓解**：
   - 技术风险识别
   - 复杂性管理
   - 性能影响评估
   - 分阶段实施策略

该文档为开发团队提供了完整的实施路线图，可以作为项目决策和技术规划的重要参考。
