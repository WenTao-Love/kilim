package kilim.http.ext;

import kilim.http.HttpRequest;
import kilim.Pausable;
import kilim.nio.EndPoint;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 增强的HTTP请求
 * 继承自HttpRequest，添加缓存、验证、解析优化等功能
 */
public class EnhancedHttpRequest extends HttpRequest {

    // ========== 缓存字段 ==========
    private String cachedContentType;
    private String cachedContentLength;
    private String cachedAcceptEncoding;
    private String cachedUserAgent;
    private String cachedHost;

    // ========== 对象池 ==========
    private static final ThreadLocal<EnhancedHttpRequest> requestPool = 
        ThreadLocal.withInitial(() -> new EnhancedHttpRequest());

    // ========== 缓冲区配置 ==========
    private static final int INITIAL_BUFFER_SIZE = 2048;
    private static final int MAX_BUFFER_SIZE = 65536;

    // ========== 请求验证 ==========
    private static final int MAX_URI_LENGTH = 2048;
    private static final int MAX_HEADER_SIZE = 8192;
    private static final int MAX_HEADERS_COUNT = 100;
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024; // 10MB

    // ========== 解析的参数 ==========
    private Map<String, String> queryParams;
    private Map<String, String> pathParams;
    private Map<String, String> headers;
    private Map<String, String> cookies;

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
        // 清理缓存字段
        this.cachedContentType = null;
        this.cachedContentLength = null;
        this.cachedAcceptEncoding = null;
        this.cachedUserAgent = null;
        this.cachedHost = null;
        this.queryParams = null;
        this.pathParams = null;
        this.headers = null;
        this.cookies = null;
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
        if ("User-Agent".equalsIgnoreCase(key)) {
            if (cachedUserAgent == null) {
                cachedUserAgent = super.getHeader(key);
            }
            return cachedUserAgent;
        }
        if ("Host".equalsIgnoreCase(key)) {
            if (cachedHost == null) {
                cachedHost = super.getHeader(key);
            }
            return cachedHost;
        }

        return super.getHeader(key);
    }

    /**
     * 获取查询参数
     */
    public Map<String, String> getQueryParams() {
        if (queryParams == null) {
            queryParams = parseQueryParams(getQuery());
        }
        return queryParams;
    }

    /**
     * 设置路径参数
     */
    public void setPathParams(Map<String, String> params) {
        this.pathParams = params;
    }

    /**
     * 获取路径参数
     */
    public Map<String, String> getPathParams() {
        return pathParams;
    }

    /**
     * 解析查询参数
     */
    private static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isEmpty()) {
            return new ConcurrentHashMap<>();
        }

        Map<String, String> params = new ConcurrentHashMap<>();
        String[] pairs = query.split("&");

        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    String key = java.net.URLDecoder.decode(kv[0], "UTF-8");
                    String value = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    params.put(key, value);
                } catch (Exception e) {
                    // 忽略解码错误
                }
            }
        }

        return params;
    }

    /**
     * 获取所有请求头
     */
    public Map<String, String> getHeaders() {
        if (headers == null) {
            headers = new ConcurrentHashMap<>();
            for (int i = 0; i < nFields; i++) {
                String key = keys[i];
                String value = extractRange(valueRanges[i]);
                headers.put(key, value);
            }
        }
        return headers;
    }

    /**
     * 获取所有Cookie
     */
    public Map<String, String> getCookies() {
        if (cookies == null) {
            cookies = parseCookies(getHeader("Cookie"));
        }
        return cookies;
    }

    /**
     * 解析Cookie
     */
    private static Map<String, String> parseCookies(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return new ConcurrentHashMap<>();
        }

        Map<String, String> cookies = new ConcurrentHashMap<>();
        String[] cookiePairs = cookieHeader.split(";");

        for (String pair : cookiePairs) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2) {
                cookies.put(kv[0].trim(), kv[1].trim());
            }
        }

        return cookies;
    }

    /**
     * 验证请求
     */
    public boolean validate() {
        // 验证URI长度
        if (uriPath == null || uriPath.length() > MAX_URI_LENGTH) {
            return false;
        }

        // 验证请求头数量
        if (nFields > MAX_HEADERS_COUNT) {
            return false;
        }

        // 验证请求体大小
        if (contentLength > MAX_BODY_SIZE) {
            return false;
        }

        // 检查危险字符
        if (containsDangerousChars(uriPath)) {
            return false;
        }

        return true;
    }

    /**
     * 检查危险字符
     */
    private static boolean containsDangerousChars(String input) {
        Pattern dangerous = Pattern.compile("[<>\\"\\x00-\\x1F]");
        return dangerous.matcher(input).find();
    }

    /**
     * 回收请求对象
     */
    public void recycle() {
        reuse();
        requestPool.set(new EnhancedHttpRequest());
    }
}
