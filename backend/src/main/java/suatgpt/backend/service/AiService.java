// src/main/java/suatgpt/backend/service/AiService.java

package suatgpt.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.InitializingBean;

import suatgpt.backend.model.ChatMessage;
import suatgpt.backend.model.User;
import suatgpt.backend.repository.ChatMessageRepository;
import suatgpt.backend.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional; // 确保导入 Optional

/**
 * AI 代理调用逻辑服务类
 * 负责处理用户消息、调用外部 AI 逻辑和存储聊天记录
 */
@Service
public class AiService implements InitializingBean {

    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    // --- 从 application.yml 注入所有配置 ---
    @Value("${ai.qwen-public.base-url}")
    private String qwenPublicBaseUrl;
    @Value("${ai.qwen-public.api-key}")
    private String qwenPublicApiKey;

    @Value("${ai.qwen-internal.base-url}")
    private String qwenInternalBaseUrl;
    @Value("${ai.qwen-internal.api-key}")
    private String qwenInternalApiKey;

    @Value("${ai.deepseek.base-url}")
    private String deepseekBaseUrl;
    @Value("${ai.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${ai.embedding.base-url}")
    private String embeddingBaseUrl;
    @Value("${ai.embedding.api-key}")
    private String embeddingApiKey;
    
    // 默认模型ID（用于内部逻辑，但聊天接口使用前端传入的 modelKey）
    private static final String QWEN_INTERNAL_MODEL = "Qwen3-30B-A3B";
    private static final String QWEN_PUBLIC_MODEL = "qwen-plus";

    // Preferred DashScope public base URL (force to avoid using deprecated hosts)
    private static final String DASHSCOPE_PUBLIC_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public AiService(ChatMessageRepository chatMessageRepository, UserRepository userRepository, RestTemplate restTemplate) {
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.restTemplate = restTemplate;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (this.qwenPublicBaseUrl == null || this.qwenPublicBaseUrl.isBlank()) {
            this.qwenPublicBaseUrl = DASHSCOPE_PUBLIC_BASE;
            System.out.println("AiService: qwenPublicBaseUrl was empty => set to DashScope: " + this.qwenPublicBaseUrl);
            return;
        }

        // If configuration still references the old provider host, force DashScope
        if (this.qwenPublicBaseUrl.contains("api.qwen.cloud") || this.qwenPublicBaseUrl.contains("qwen.cloud")) {
            System.out.println("AiService: Detected deprecated host in qwenPublicBaseUrl; replacing with DashScope");
            this.qwenPublicBaseUrl = DASHSCOPE_PUBLIC_BASE;
        }
    }

    /**
     * 处理用户消息，生成 AI 响应，并存储记录 (保留原有逻辑)
     * @param userId 当前用户ID
     * @param userMessage 用户输入的消息
     * @param modelKey 前端选择的模型 ('qwen-internal', 'qwen-public', 'deepseek' 等)
     * @return AI 响应消息内容
     */
    @Transactional
    public String processUserMessage(Long userId, String userMessage, String modelKey) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        // 1. 存储用户消息
        ChatMessage userMsg = new ChatMessage(user, "USER", userMessage, LocalDateTime.now());
        chatMessageRepository.save(userMsg);

        // 2. 调用 AI 逻辑 (替换模拟调用)
        String aiResponse = callExternalAiApi(user, userMessage, modelKey);

        // 3. 存储 AI 响应
        ChatMessage aiMsg = new ChatMessage(user, "AI", aiResponse, LocalDateTime.now());
        chatMessageRepository.save(aiMsg);

        return aiResponse;
    }

    /**
     * 核心方法：调用外部 LLM API
     */
    private String callExternalAiApi(User user, String userContent, String modelKey) {
        
        // --- 1. 根据 modelKey 选择配置 ---
        String baseUrl;
        String apiKey;
        String modelId;

        switch (modelKey) {
            case "qwen-internal":
                baseUrl = qwenInternalBaseUrl;
                apiKey = qwenInternalApiKey;
                modelId = QWEN_INTERNAL_MODEL;
                break;
            case "deepseek":
                baseUrl = deepseekBaseUrl;
                apiKey = deepseekApiKey;
                modelId = "deepseek-r1-0528-w8a8";
                break;
            case "qwen-public":
            default:
                // 默认优先使用公网 Qwen 配置，方便校外/无校网环境调用
                baseUrl = qwenPublicBaseUrl;
                apiKey = qwenPublicApiKey;
                modelId = QWEN_PUBLIC_MODEL;
                break;
        }

        // 2. 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        
        // 3. 构造请求体 (简化版，生产环境应包含聊天历史)
        Map<String, Object> message = Map.of("role", "user", "content", userContent);
        Map<String, Object> requestBody = Map.of(
            "model", modelId,
            "messages", List.of(message),
            "temperature", 0.7
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        // 4. 发送请求
        try {
            // If using qwen-public but API key is empty, fail early with helpful message
            if (baseUrl != null && baseUrl.contains("dashscope") && (apiKey == null || apiKey.isBlank())) {
                String warn = "AI Service: qwen-public API key is not set (env AI_QWEN_PUBLIC_API_KEY).";
                System.err.println(warn);
                return "对不起，AI服务 (qwen-public) 未配置 API Key，请设置环境变量 AI_QWEN_PUBLIC_API_KEY。";
            }
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                baseUrl + "/chat/completions",
                entity,
                JsonNode.class
            );

            // 5. 解析响应 (使用 JsonNode 避免未经检查的类型转换警告)
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode body = response.getBody();
                JsonNode choices = body.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode messageNode = choices.get(0).path("message");
                    return messageNode.path("content").asText("");
                }
            }
        } catch (RestClientException e) {
            // 记录失败日志
            System.err.println("AI Service Call Failed for " + modelKey + ": " + e.getMessage());
            return "对不起，AI服务 (" + modelKey + ") 调用失败，请检查网络（公网/内网）或 API Key。";
        }
        
        return "AI 服务返回了无效或空响应。";
    }

    /**
     * 获取用户所有聊天记录 (保留原有逻辑)
     */
    public List<ChatMessage> getChatHistory(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        return chatMessageRepository.findByUserOrderByTimestampAsc(user);
    }

    // --- Public getters for diagnostics/testing ---
    public String qwenPublicBaseUrl() {
        return this.qwenPublicBaseUrl;
    }

    public String qwenInternalBaseUrl() {
        return this.qwenInternalBaseUrl;
    }

    public String deepseekBaseUrl() {
        return this.deepseekBaseUrl;
    }
}