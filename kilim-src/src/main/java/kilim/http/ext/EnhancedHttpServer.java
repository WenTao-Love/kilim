package kilim.http.ext;

import kilim.http.HttpServer;
import kilim.Scheduler;
import kilim.http.HttpSession;
import kilim.http.HttpResponse;
import kilim.nio.NioSelectorScheduler;
import kilim.nio.NioSelectorScheduler.SessionFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * 增强的HTTP服务器
 * 继承自HttpServer，添加连接管理、压缩、安全验证等功能
 */
public class EnhancedHttpServer extends HttpServer {

    // ========== 连接管理 ==========
    private ConnectionManager connectionManager;
    private final int maxConnections;
    private final long keepAliveTimeout;
    private final long connectionTimeout;

    // ========== 压缩配置 ==========
    private boolean compressionEnabled = true;
    private int compressionThreshold = 1024;
    private String compressionType = "gzip";

    // ========== 安全配置 ==========
    private SecurityConfig securityConfig;

    // ========== 监控统计 ==========
    private ServerMetrics metrics;

    // ========== 中间件 ==========
    private List<HttpMiddleware> middlewares = new ArrayList<>();

    /**
     * 基础构造函数
     */
    public EnhancedHttpServer(int port, HttpSession.StringRouter handler) throws IOException {
        this(port, handler, createDefaultConfig());
    }

    /**
     * 带配置的构造函数
     */
    public EnhancedHttpServer(int port, HttpSession.StringRouter handler, 
                          ServerConfig config) throws IOException {
        super(port, wrapHandlerWithMiddleware(handler));
        initializeComponents(config);
    }

    /**
     * 创建默认配置
     */
    private static ServerConfig createDefaultConfig() {
        ServerConfig config = new ServerConfig();
        config.maxConnections = 10000;
        config.keepAliveTimeout = 30000; // 30秒
        config.connectionTimeout = 60000;   // 60秒
        config.compressionEnabled = true;
        config.compressionThreshold = 1024;
        config.securityEnabled = true;
        config.metricsEnabled = true;
        return config;
    }

    /**
     * 初始化组件
     */
    private void initializeComponents(ServerConfig config) {
        // 初始化连接管理器
        connectionManager = new ConnectionManager(
            config.maxConnections,
            config.keepAliveTimeout,
            config.connectionTimeout
        );

        // 初始化安全配置
        securityConfig = new SecurityConfig(config.securityEnabled);

        // 初始化监控统计
        metrics = new ServerMetrics(config.metricsEnabled);

        // 配置压缩
        this.compressionEnabled = config.compressionEnabled;
        this.compressionThreshold = config.compressionThreshold;
        this.compressionType = config.compressionType;
    }

    /**
     * 包装处理器，添加中间件
     */
    private HttpSession.StringRouter wrapHandlerWithMiddleware(
            HttpSession.StringRouter handler) {
        return (req, resp) -> {
            try {
                // 执行前置中间件
                for (HttpMiddleware middleware : middlewares) {
                    if (!middleware.beforeProcess(req, resp)) {
                        return null;
                    }
                }

                // 执行实际处理器
                String result = handler.route(req, resp);

                // 执行后置中间件
                for (HttpMiddleware middleware : middlewares) {
                    middleware.afterProcess(req, resp, result);
                }

                return result;
            } catch (Exception e) {
                // 错误处理
                handleError(e, req, resp);
                return null;
            }
        };
    }

    /**
     * 错误处理
     */
    private void handleError(Exception e, kilim.http.HttpRequest req, 
                       HttpResponse resp) {
        metrics.recordError();

        resp.status = HttpResponse.ST_INTERNAL_SERVER_ERROR;
        resp.setContentType("application/json");

        try {
            String error = "{"error":"" + e.getMessage() + ""}";
            resp.getOutputStream().write(error.getBytes());
        } catch (Exception ex) {
            // 忽略写入异常
        }
    }

    /**
     * 添加中间件
     */
    public void addMiddleware(HttpMiddleware middleware) {
        middlewares.add(middleware);
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
     * 获取连接统计
     */
    public ConnectionStats getConnectionStats() {
        return connectionManager.getStats();
    }

    /**
     * 获取服务器指标
     */
    public ServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * 服务器配置
     */
    public static class ServerConfig {
        public int maxConnections;
        public long keepAliveTimeout;
        public long connectionTimeout;
        public boolean compressionEnabled;
        public int compressionThreshold;
        public boolean securityEnabled;
        public boolean metricsEnabled;
    }

    /**
     * 连接管理器
     */
    private static class ConnectionManager {
        private final AtomicInteger activeConnections = new AtomicInteger(0);
        private final AtomicLong totalConnections = new AtomicLong(0);
        private final ConcurrentHashMap<String, ConnectionInfo> connections = 
            new ConcurrentHashMap<>();
        private final int maxConnections;
        private final long keepAliveTimeout;
        private final long connectionTimeout;

        public ConnectionManager(int maxConnections, long keepAliveTimeout, 
                            long connectionTimeout) {
            this.maxConnections = maxConnections;
            this.keepAliveTimeout = keepAliveTimeout;
            this.connectionTimeout = connectionTimeout;
            startCleanupThread();
        }

        public void registerConnection(String sessionId, String clientIp) {
            if (activeConnections.get() >= maxConnections) {
                throw new IllegalStateException("Too many connections");
            }

            activeConnections.incrementAndGet();
            totalConnections.incrementAndGet();

            ConnectionInfo info = new ConnectionInfo(
                System.currentTimeMillis(),
                clientIp
            );
            connections.put(sessionId, info);
        }

        public void updateActivity(String sessionId) {
            ConnectionInfo info = connections.get(sessionId);
            if (info != null) {
                info.lastActivity = System.currentTimeMillis();
            }
        }

        public void unregisterConnection(String sessionId) {
            connections.remove(sessionId);
            activeConnections.decrementAndGet();
        }

        public ConnectionStats getStats() {
            return new ConnectionStats(
                activeConnections.get(),
                totalConnections.get(),
                connections.size()
            );
        }

        private void startCleanupThread() {
            Thread cleanupThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(5000);
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

        private void cleanupIdleConnections() {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ConnectionInfo> entry : connections.entrySet()) {
                ConnectionInfo info = entry.getValue();
                if (now - info.lastActivity > keepAliveTimeout) {
                    try {
                        entry.getKey(); // sessionId
                    } catch (Exception e) {
                        // 忽略
                    }
                    connections.remove(entry.getKey());
                    activeConnections.decrementAndGet();
                }
            }
        }

        private static class ConnectionInfo {
            final long createTime;
            volatile long lastActivity;
            final String clientIp;

            ConnectionInfo(long createTime, String clientIp) {
                this.createTime = createTime;
                this.lastActivity = createTime;
                this.clientIp = clientIp;
            }
        }

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
    }

    /**
     * 安全配置
     */
    private static class SecurityConfig {
        private final boolean enabled;
        private final int maxUriLength = 2048;
        private final int maxHeaderSize = 8192;
        private final int maxHeadersCount = 100;
        private final int maxBodySize = 10 * 1024 * 1024; // 10MB

        public SecurityConfig(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean validateRequest(kilim.http.HttpRequest req) {
            if (!enabled) {
                return true;
            }

            // 验证URI
            if (req.uriPath == null || req.uriPath.isEmpty()) {
                return false;
            }
            if (req.uriPath.length() > maxUriLength) {
                return false;
            }

            // 验证请求头
            if (req.nFields > maxHeadersCount) {
                return false;
            }

            // 验证请求体
            if (req.contentLength > maxBodySize) {
                return false;
            }

            return true;
        }
    }

    /**
     * 服务器指标
     */
    public static class ServerMetrics {
        private final boolean enabled;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong totalErrors = new AtomicLong(0);
        private final AtomicLong totalBytesRead = new AtomicLong(0);
        private final AtomicLong totalBytesWritten = new AtomicLong(0);
        private final AtomicInteger activeConnections = new AtomicInteger(0);

        public ServerMetrics(boolean enabled) {
            this.enabled = enabled;
        }

        public void recordRequest() {
            if (enabled) {
                totalRequests.incrementAndGet();
            }
        }

        public void recordError() {
            if (enabled) {
                totalErrors.incrementAndGet();
            }
        }

        public void recordBytesRead(long bytes) {
            if (enabled) {
                totalBytesRead.addAndGet(bytes);
            }
        }

        public void recordBytesWritten(long bytes) {
            if (enabled) {
                totalBytesWritten.addAndGet(bytes);
            }
        }

        public void updateActiveConnections(int count) {
            if (enabled) {
                activeConnections.set(count);
            }
        }

        public MetricsSnapshot getSnapshot() {
            return new MetricsSnapshot(
                totalRequests.get(),
                totalErrors.get(),
                totalBytesRead.get(),
                totalBytesWritten.get(),
                activeConnections.get()
            );
        }

        public static class MetricsSnapshot {
            public final long totalRequests;
            public final long totalErrors;
            public final long totalBytesRead;
            public final long totalBytesWritten;
            public final int activeConnections;

            MetricsSnapshot(long totalRequests, long totalErrors, long totalBytesRead,
                          long totalBytesWritten, int activeConnections) {
                this.totalRequests = totalRequests;
                this.totalErrors = totalErrors;
                this.totalBytesRead = totalBytesRead;
                this.totalBytesWritten = totalBytesWritten;
                this.activeConnections = activeConnections;
            }
        }
    }
}
