package kilim.http.ext;

import kilim.http.HttpSession;
import kilim.http.HttpRequest;
import kilim.http.HttpResponse;
import kilim.Pausable;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * 增强的HTTP会话
 * 继承自HttpSession，添加连接管理、请求验证、会话管理等功能
 */
public class EnhancedHttpSession extends HttpSession {

    // ========== 连接管理 ==========
    private String sessionId;
    private String clientIp;
    private long connectionTime;
    private volatile long lastActivity;

    // ========== 会话管理 ==========
    private SessionManager sessionManager;

    // ========== 请求验证 ==========
    private RequestValidator requestValidator;

    // ========== 文件上传 ==========
    private FileUploadHandler fileUploadHandler;
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int MAX_FILE_COUNT = 10;

    /**
     * 构造函数
     */
    public EnhancedHttpSession() {
        this.sessionManager = new SessionManager();
        this.requestValidator = new RequestValidator();
        this.fileUploadHandler = new DefaultFileUploadHandler();
    }

    /**
     * 带配置的构造函数
     */
    public EnhancedHttpSession(SessionManager sessionManager,
                          RequestValidator validator,
                          FileUploadHandler uploadHandler) {
        this.sessionManager = sessionManager;
        this.requestValidator = validator;
        this.fileUploadHandler = uploadHandler;
    }

    /**
     * 优化的请求读取
     */
    @Override
    public HttpRequest readRequest(HttpRequest req) throws IOException, Pausable {
        // 记录连接信息
        recordConnectionInfo(req);

        // 读取请求
        super.readRequest(req);

        // 验证请求
        if (!requestValidator.validate(req)) {
            sendValidationError(req);
            return req;
        }

        // 处理文件上传
        if (isFileUploadRequest(req)) {
            handleFileUpload(req);
        }

        // 更新活动时间
        updateActivity();

        return req;
    }

    /**
     * 记录连接信息
     */
    private void recordConnectionInfo(HttpRequest req) {
        this.sessionId = String.valueOf(this.id);
        this.clientIp = req.getHeader("X-Real-IP");
        if (this.clientIp == null || this.clientIp.isEmpty()) {
            this.clientIp = req.getHeader("X-Forwarded-For");
        }
        if (this.clientIp == null || this.clientIp.isEmpty()) {
            this.clientIp = "unknown";
        }
        this.connectionTime = System.currentTimeMillis();
        this.lastActivity = this.connectionTime;
    }

    /**
     * 更新活动时间
     */
    private void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }

    /**
     * 发送验证错误
     */
    private void sendValidationError(HttpRequest req) throws IOException, Pausable {
        HttpResponse resp = new HttpResponse();
        resp.status = HttpResponse.ST_BAD_REQUEST;
        resp.setContentType("application/json");

        String errors = requestValidator.getErrors(req);
        resp.getOutputStream().write(errors.getBytes());

        sendResponse(resp);
    }

    /**
     * 检查是否是文件上传请求
     */
    private boolean isFileUploadRequest(HttpRequest req) {
        String contentType = req.getHeader("Content-Type");
        return contentType != null && 
               contentType.startsWith("multipart/form-data");
    }

    /**
     * 处理文件上传
     */
    private void handleFileUpload(HttpRequest req) throws IOException, Pausable {
        try {
            // 解析multipart请求
            FileUploadResult result = parseMultipartRequest(req);

            // 验证文件
            if (!validateFiles(result.getFiles())) {
                HttpResponse resp = new HttpResponse();
                resp.status = HttpResponse.ST_BAD_REQUEST;
                resp.setContentType("application/json");
                String error = "{"error":"Invalid file"}";
                resp.getOutputStream().write(error.getBytes());
                sendResponse(resp);
                return;
            }

            // 调用处理器
            fileUploadHandler.handleUpload(result, this);

        } catch (Exception e) {
            sendUploadError(e);
        }
    }

    /**
     * 解析multipart请求
     */
    private FileUploadResult parseMultipartRequest(HttpRequest req) 
            throws IOException, Pausable {
        String contentType = req.getHeader("Content-Type");
        String boundary = extractBoundary(contentType);

        if (boundary == null) {
            throw new IOException("Invalid Content-Type");
        }

        // 读取请求体
        byte[] body = readRequestBody(req);

        // 解析multipart数据
        List<FileUploadItem> items = parseMultipartData(body, boundary);

        // 验证文件数量
        if (items.size() > MAX_FILE_COUNT) {
            throw new IOException("Too many files");
        }

        return new FileUploadResult(items);
    }

    /**
     * 提取boundary
     */
    private String extractBoundary(String contentType) {
        String[] parts = contentType.split("boundary=");
        if (parts.length < 2) {
            return null;
        }
        return parts[1].trim();
    }

    /**
     * 解析multipart数据
     */
    private List<FileUploadItem> parseMultipartData(byte[] data, 
                                                String boundary) 
            throws IOException {
        List<FileUploadItem> items = new ArrayList<>();
        byte[] boundaryBytes = ("--" + boundary).getBytes();

        int start = 0;
        int end;

        while ((end = indexOf(data, boundaryBytes, start)) > 0) {
            // 提取part数据
            byte[] partData = new byte[end - start - 2];

            // 解析part
            FileUploadItem item = parsePart(partData);
            if (item != null) {
                items.add(item);
            }

            start = end + boundaryBytes.length;
        }

        return items;
    }

    /**
     * 解析part
     */
    private FileUploadItem parsePart(byte[] partData) {
        String partStr = new String(partData);

        // 查找Content-Disposition
        int cdIndex = partStr.indexOf("Content-Disposition:");
        if (cdIndex < 0) {
            return null;
        }

        String disposition = partStr.substring(cdIndex).trim();

        // 检查是否是文件
        if (!disposition.contains("filename=")) {
            return null;
        }

        // 提取文件名
        String filename = extractFilename(disposition);

        // 提取文件数据
        int dataIndex = partStr.indexOf("\r\n\r\n");
        if (dataIndex < 0) {
            return null;
        }

        byte[] fileData = new byte[partData.length - dataIndex - 4];
        System.arraycopy(partData, dataIndex + 4, fileData, 0, fileData.length);

        return new FileUploadItem(filename, fileData);
    }

    /**
     * 提取文件名
     */
    private String extractFilename(String disposition) {
        int start = disposition.indexOf("filename=\"");
        int end = disposition.indexOf(""", start + 10);
        if (start < 0 || end < 0) {
            return "unknown";
        }
        return disposition.substring(start + 10, end);
    }

    /**
     * 验证文件
     */
    private boolean validateFiles(List<FileUploadItem> files) {
        long totalSize = 0;

        for (FileUploadItem file : files) {
            // 验证文件大小
            if (file.getData().length > MAX_FILE_SIZE) {
                return false;
            }
            totalSize += file.getData().length;
        }

        // 验证总大小
        return totalSize <= MAX_FILE_SIZE * MAX_FILE_COUNT;
    }

    /**
     * 发送上传错误
     */
    private void sendUploadError(Exception e) throws IOException, Pausable {
        HttpResponse resp = new HttpResponse();
        resp.status = HttpResponse.ST_INTERNAL_SERVER_ERROR;
        resp.setContentType("application/json");
        String error = "{"error":"" + e.getMessage() + ""}";
        resp.getOutputStream().write(error.getBytes());
        sendResponse(resp);
    }

    /**
     * 获取会话
     */
    public Session getSession() {
        return sessionManager.getSession(getSessionId());
    }

    /**
     * 设置会话
     */
    public void setSession(Session session) {
        sessionManager.setSession(getSessionId(), session);
    }

    /**
     * 获取会话ID
     */
    private String getSessionId() {
        // 从Cookie获取会话ID
        String sessionIdCookie = getCookieValue("SESSIONID");
        if (sessionIdCookie != null && !sessionIdCookie.isEmpty()) {
            return sessionIdCookie;
        }
        return null;
    }

    /**
     * 获取Cookie值
     */
    private String getCookieValue(String name) {
        String cookieHeader = super.readRequest(new HttpRequest()).getHeader("Cookie");
        if (cookieHeader == null) {
            return null;
        }

        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] kv = cookie.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(name)) {
                return kv[1].trim();
            }
        }

        return null;
    }

    /**
     * 文件上传项
     */
    public static class FileUploadItem {
        private final String filename;
        private final byte[] data;

        public FileUploadItem(String filename, byte[] data) {
            this.filename = filename;
            this.data = data;
        }

        public String getFilename() {
            return filename;
        }

        public byte[] getData() {
            return data;
        }

        public long getSize() {
            return data != null ? data.length : 0;
        }
    }

    /**
     * 文件上传结果
     */
    public static class FileUploadResult {
        private final List<FileUploadItem> files;
        private final Map<String, String> formFields;

        public FileUploadResult(List<FileUploadItem> files) {
            this.files = files;
            this.formFields = new HashMap<>();
        }

        public List<FileUploadItem> getFiles() {
            return files;
        }

        public Map<String, String> getFormFields() {
            return formFields;
        }
    }

    /**
     * 会话接口
     */
    public interface Session {
        String getId();
        Object getAttribute(String key);
        void setAttribute(String key, Object value);
        void removeAttribute(String key);
        void invalidate();
        long getLastAccessedTime();
    }

    /**
     * 会话管理器
     */
    public static class SessionManager {
        private final Map<String, Session> sessions = new HashMap<>();
        private final long sessionTimeout = 1800000; // 30分钟

        public Session getSession(String sessionId) {
            if (sessionId == null) {
                return null;
            }

            Session session = sessions.get(sessionId);
            if (session != null) {
                return session;
            }

            // 创建新会话
            session = new DefaultSession(sessionId);
            sessions.put(sessionId, session);
            return session;
        }

        public void setSession(String sessionId, Session session) {
            sessions.put(sessionId, session);
        }

        public void removeSession(String sessionId) {
            sessions.remove(sessionId);
        }

        public void cleanupExpiredSessions() {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(entry -> {
                Session session = entry.getValue();
                return now - session.getLastAccessedTime() > sessionTimeout;
            });
        }
    }

    /**
     * 默认会话实现
     */
    public static class DefaultSession implements Session {
        private final String id;
        private final Map<String, Object> attributes = new HashMap<>();
        private final long createTime;
        private volatile long lastAccessedTime;

        public DefaultSession(String id) {
            this.id = id;
            this.createTime = System.currentTimeMillis();
            this.lastAccessedTime = this.createTime;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Object getAttribute(String key) {
            lastAccessedTime = System.currentTimeMillis();
            return attributes.get(key);
        }

        @Override
        public void setAttribute(String key, Object value) {
            attributes.put(key, value);
        }

        @Override
        public void removeAttribute(String key) {
            attributes.remove(key);
        }

        @Override
        public void invalidate() {
            attributes.clear();
        }

        @Override
        public long getLastAccessedTime() {
            return lastAccessedTime;
        }
    }

    /**
     * 请求验证器
     */
    public static class RequestValidator {
        private static final int MAX_URI_LENGTH = 2048;
        private static final int MAX_HEADER_SIZE = 8192;
        private static final int MAX_HEADERS_COUNT = 100;

        public boolean validate(HttpRequest req) {
            // 验证URI
            if (req.uriPath == null || req.uriPath.isEmpty()) {
                return false;
            }
            if (req.uriPath.length() > MAX_URI_LENGTH) {
                return false;
            }

            // 验证请求头
            if (req.nFields > MAX_HEADERS_COUNT) {
                return false;
            }

            return true;
        }

        public String getErrors(HttpRequest req) {
            List<String> errors = new ArrayList<>();

            if (req.uriPath == null || req.uriPath.isEmpty()) {
                errors.add("URI is empty");
            }
            if (req.uriPath.length() > MAX_URI_LENGTH) {
                errors.add("URI too long");
            }
            if (req.nFields > MAX_HEADERS_COUNT) {
                errors.add("Too many headers");
            }

            return "{"errors":" + errors + "}";
        }
    }

    /**
     * 文件上传处理器
     */
    public interface FileUploadHandler {
        void handleUpload(FileUploadResult result, EnhancedHttpSession session);
    }

    /**
     * 默认文件上传处理器
     */
    public static class DefaultFileUploadHandler implements FileUploadHandler {
        @Override
        public void handleUpload(FileUploadResult result, 
                              EnhancedHttpSession session) {
            // 保存文件到临时目录
            for (FileUploadItem file : result.getFiles()) {
                saveFile(file);
            }
        }

        private void saveFile(FileUploadItem file) {
            try {
                String tempDir = System.getProperty("java.io.tmpdir");
                java.io.File dest = new java.io.File(tempDir, file.getFilename());
                java.nio.file.Files.write(dest.toPath(), file.getData());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
