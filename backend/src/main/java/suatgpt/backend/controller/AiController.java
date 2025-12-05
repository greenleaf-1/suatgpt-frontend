package suatgpt.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import suatgpt.backend.model.ChatMessage;
import suatgpt.backend.repository.UserRepository;
import suatgpt.backend.service.AiService;
import suatgpt.backend.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.net.InetAddress;
import java.net.URI;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 交互控制器
 * 负责处理与 AI 聊天、获取历史记录等操作的 API 路由。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;
    private final UserRepository userRepository;

    public AiController(AiService aiService, UserRepository userRepository) {
        this.aiService = aiService;
        this.userRepository = userRepository;
    }

    // --- DTOs (Data Transfer Objects) ---

    /**
     * 聊天请求体 (DTO): 接收用户发送的消息内容 和 选择的模型键。
     * @param message 用户输入的文本消息
     * @param modelKey 用户选择的模型键 ('qwen-internal', 'qwen-public', 'deepseek' 等)
     */
    record ChatRequest(String message, String modelKey) {} // <<< 关键修改：新增 modelKey

    /**
     * 聊天消息响应体 (DTO): 用于向客户端发送聊天消息的简化结构。
     */
    record MessageResponse(String sender, String content, LocalDateTime timestamp) {}

    // --- API Endpoints ---

    /**
     * 发送新消息并获取 AI 响应
     */
    @PostMapping("/chat")
    public ResponseEntity<MessageResponse> chat(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody ChatRequest request) {
        
        // 校验输入
        if (request.message() == null || request.message().trim().isEmpty()) {
             return ResponseEntity.badRequest().body(new MessageResponse("AI", "消息内容不能为空。", LocalDateTime.now()));
        }
        
        // 1. 如果已登录，则使用真实用户；否则使用/创建匿名用户（username = 'anonymous'）
        User user;
        if (userDetails != null) {
            user = userRepository.findByUsername(userDetails.getUsername())
                    .orElseThrow(() -> new RuntimeException("Authenticated user not found."));
        } else {
            // 未登录：查找或创建一个匿名用户用于持久化聊天记录
            String anonUsername = "anonymous";
            user = userRepository.findByUsername(anonUsername).orElseGet(() -> {
                User u = new User();
                u.setUsername(anonUsername);
                // 存储一个 placeholder 密码（此用户不用于登录）
                u.setPassword(anonUsername + "-nopass");
                u.setRole("ANONYMOUS");
                return userRepository.save(u);
            });
        }

        // 2. 调用 AiService 处理用户消息、存储记录，并获取 AI 响应
        // <<< 关键修改：传递 modelKey >>>
        String aiResponse = aiService.processUserMessage(user.getId(), request.message(), request.modelKey());

        // 3. 返回 AI 响应消息
        return ResponseEntity.ok(new MessageResponse("AI", aiResponse, LocalDateTime.now()));
    }

    /**
     * 获取聊天历史记录
     */
    @GetMapping("/history")
    public ResponseEntity<List<MessageResponse>> getHistory(@AuthenticationPrincipal UserDetails userDetails) {
        
        // 1. 通过用户名查找实际的 User 实体
        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("Authenticated user not found."));

        // 2. 获取该用户的所有聊天历史记录实体
        List<ChatMessage> history = aiService.getChatHistory(user.getId());

        // 3. 将 ChatMessage 实体列表转换为 MessageResponse DTO 列表
        List<MessageResponse> response = history.stream()
                .map(msg -> new MessageResponse(msg.getSender(), msg.getContent(), msg.getTimestamp()))
                .collect(Collectors.toList());

        // 4. 返回历史消息列表
        return ResponseEntity.ok(response);
    }

    /**
     * Diagnostic endpoint: 测试与指定 AI 模型的网络连通性（DNS解析 + TCP 连接）
     * 示例: GET /api/ai/test?modelKey=qwen-public
     */
    @GetMapping("/test")
    public ResponseEntity<?> testAiConnection(@RequestParam(required = false, defaultValue = "qwen-public") String modelKey) {
        // 选择 baseUrl 和 apiKey (和 AiService 一致的选择逻辑)
        String baseUrl;
        switch (modelKey) {
            case "qwen-internal":
                baseUrl = aiService.qwenInternalBaseUrl();
                break;
            case "deepseek":
                baseUrl = aiService.deepseekBaseUrl();
                break;
            case "qwen-public":
            default:
                baseUrl = aiService.qwenPublicBaseUrl();
                break;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("modelKey", modelKey);
        result.put("baseUrl", baseUrl);

        try {
            URI uri = new URI(baseUrl);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            result.put("host", host);
            result.put("port", port);

            // DNS lookup
            InetAddress[] addrs = InetAddress.getAllByName(host);
            String[] ips = new String[addrs.length];
            for (int i = 0; i < addrs.length; i++) ips[i] = addrs[i].getHostAddress();
            result.put("dnsResolved", true);
            result.put("ips", ips);

            // TCP connect test
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000);
                result.put("tcpConnect", true);
            } catch (Exception e) {
                result.put("tcpConnect", false);
                result.put("tcpError", e.getMessage());
            }

            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            result.put("dnsResolved", false);
            result.put("error", ex.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}