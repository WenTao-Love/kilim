package kilim.http.ext;

import kilim.http.HttpResponse;
import kilim.Pausable;
import kilim.nio.EndPoint;
import kilim.nio.ExposedBaos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocal;

/**
 * 增强的HTTP响应
 * 继承自HttpResponse，添加压缩、Cookie、流式响应等功能
 */
public class EnhancedHttpResponse extends HttpResponse {

    // ========== 压缩配置 ==========
    private boolean compressionEnabled = true;
    private int compressionThreshold = 1024;
    private String compressionType = "gzip";

    // ========== Cookie管理 ==========
    private java.util.List<Cookie> cookies = new java.util.ArrayList<>();

    // ========== 对象池 ==========
    private static final ThreadLocal<EnhancedHttpResponse> responsePool = 
        ThreadLocal.withInitial(() -> new EnhancedHttpResponse());

    // ========== 日期格式化器复用 ==========
    private static final ThreadLocal<java.text.SimpleDateFormat> dateFormat = 
        ThreadLocal.withInitial(() -> {
            java.text.SimpleDateFormat sdf = 
                new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            return sdf;
        });

    // ========== 流式响应 ==========
    private boolean streamingEnabled = false;
    private java.io.OutputStream streamWriter;

    /**
     * 从对象池获取响应实例
     */
    public static EnhancedHttpResponse obtain() {
        EnhancedHttpResponse resp = responsePool.get();
        resp.reset();
        return resp;
    }

    /**
     * 重置响应，复用对象
     */
    @Override
    public void reuse() {
        super.reuse();
        this.compressionEnabled = true;
        this.cookies.clear();
        this.streamingEnabled = false;
        this.streamWriter = null;
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
     * 设置压缩类型
     */
    public void setCompressionType(String type) {
        this.compressionType = type;
    }

    /**
     * 添加Cookie
     */
    public void addCookie(Cookie cookie) {
        cookies.add(cookie);
        addField("Set-Cookie", cookie.toSetCookieHeader());
    }

    /**
     * 获取所有Cookie
     */
    public java.util.List<Cookie> getCookies() {
        return java.util.Collections.unmodifiableList(cookies);
    }

    /**
     * 启用流式响应
     */
    public void enableStreaming() {
        this.streamingEnabled = true;
    }

    /**
     * 获取流式写入器
     */
    public java.io.OutputStream getStreamWriter() {
        if (!streamingEnabled) {
            throw new IllegalStateException("Streaming not enabled");
        }
        if (streamWriter == null) {
            streamWriter = new StreamingWriter();
        }
        return streamWriter;
    }

    /**
     * 优化的响应写入，支持压缩
     */
    @Override
    public void writeTo(EndPoint endpoint) throws IOException, Pausable {
        if (streamingEnabled && streamWriter != null) {
            // 流式响应
            writeStreamingResponse(endpoint);
        } else {
            // 普通响应
            writeNormalResponse(endpoint);
        }
    }

    /**
     * 写入普通响应
     */
    private void writeNormalResponse(EndPoint endpoint) throws IOException, Pausable {
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
     * 写入流式响应
     */
    private void writeStreamingResponse(EndPoint endpoint) throws IOException, Pausable {
        // 写入响应头
        writeHeaderOptimized(endpoint);
        // 流式响应不在这里写入body
        // 由streamWriter直接写入到endpoint
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
        ExposedBaos headerStream = new ExposedBaos();
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
        if (bodyStream != null && getHeaderValue("Content-Length") == null && !streamingEnabled) {
            setContentLength(bodyStream.size());
        }

        // 写入Cookie头
        for (Cookie cookie : cookies) {
            dos.write("Set-Cookie: ".getBytes());
            dos.write(cookie.toSetCookieHeader().getBytes());
            dos.write(CRLF);
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
        super.reuse();
        this.compressionEnabled = true;
        this.cookies.clear();
        this.streamingEnabled = false;
        this.streamWriter = null;
    }

    /**
     * 流式写入器
     */
    private class StreamingWriter extends java.io.OutputStream {
        private final java.io.ByteArrayOutputStream buffer = 
            new java.io.ByteArrayOutputStream(8192);
        private boolean closed = false;

        @Override
        public void write(int b) throws IOException {
            buffer.write(b);
            flushIfNeeded();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            buffer.write(b, off, len);
            flushIfNeeded();
        }

        @Override
        public void flush() throws IOException {
            buffer.flush();
        }

        private void flushIfNeeded() throws IOException {
            if (buffer.size() >= 8192) {
                flush();
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            flush();
        }
    }

    /**
     * Cookie类
     */
    public static class Cookie {
        private final String name;
        private final String value;
        private final String domain;
        private final String path;
        private final long maxAge;
        private final boolean secure;
        private final boolean httpOnly;

        public Cookie(String name, String value) {
            this(name, value, null, "/", -1, false, false);
        }

        public Cookie(String name, String value, String domain, String path, 
                    long maxAge, boolean secure, boolean httpOnly) {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.maxAge = maxAge;
            this.secure = secure;
            this.httpOnly = httpOnly;
        }

        public String toSetCookieHeader() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append("=").append(value);

            if (domain != null && !domain.isEmpty()) {
                sb.append("; Domain=").append(domain);
            }
            if (path != null && !path.isEmpty()) {
                sb.append("; Path=").append(path);
            }
            if (maxAge >= 0) {
                sb.append("; Max-Age=").append(maxAge);
            }
            if (secure) {
                sb.append("; Secure");
            }
            if (httpOnly) {
                sb.append("; HttpOnly");
            }

            return sb.toString();
        }
    }
}
