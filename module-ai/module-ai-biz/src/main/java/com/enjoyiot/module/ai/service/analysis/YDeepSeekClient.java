package com.enjoyiot.module.ai.service.analysis;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.enjoyiot.framework.common.util.json.JsonUtils;
import com.enjoyiot.module.ai.enums.YAiAnalysisConstants;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 大模型客户端（OpenAI 兼容协议）
 *
 * 配置项统一收敛到 {@link YAiAnalysisConstants}（不再从 application.yaml 读取）：
 *   DEEPSEEK_API_KEY：API Key（默认值可被环境变量 AI_DEEPSEEK_API_KEY 覆盖）
 *   DEEPSEEK_URL：接口地址
 *   DEEPSEEK_MODEL：模型名
 *   DEEPSEEK_TIMEOUT_SECONDS：超时时间（秒）
 */
@Slf4j
@Component
public class YDeepSeekClient {

    /**
     * API Key
     */
    private final String apiKey = YAiAnalysisConstants.DEEPSEEK_API_KEY;

    /**
     * 接口地址
     */
    private final String url = YAiAnalysisConstants.DEEPSEEK_URL;

    /**
     * 模型名称
     */
    private final String model = YAiAnalysisConstants.DEEPSEEK_MODEL;

    /**
     * 超时时间（秒）
     */
    private final int timeoutSeconds = YAiAnalysisConstants.DEEPSEEK_TIMEOUT_SECONDS;

    /**
     * 对话消息
     */
    public static class Message {
        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }

    /**
     * 调用 DeepSeek，要求返回 JSON 格式
     *
     * @param system system 提示词
     * @param user   user 提示词
     * @return AI 返回的文本（JSON 字符串）
     */
    public String chatJson(String system, String user) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", system));
        messages.add(new Message("user", user));
        return chat(messages, true);
    }

    /**
     * 调用 DeepSeek
     * @param messages    消息列表
     * @param jsonMode    是否强制 JSON 输出
     * @return AI 返回的文本
     */
    public String chat(List<Message> messages, boolean jsonMode) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.3);
        body.put("stream", false);
        if (jsonMode) {
            Map<String, Object> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            body.put("response_format", responseFormat);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");

        String requestBody = JsonUtils.toJsonString(body);
        log.info("[YDeepSeekClient][chat] url: {}, model: {}, request: {}", url, model, requestBody);

        try (HttpResponse response = HttpRequest.post(url)
                .addHeaders(headers)
                .body(requestBody, "application/json;charset=UTF-8")
                .timeout(timeoutSeconds * 1000)
                .execute()) {
            String respBody = new String(response.bodyBytes(), StandardCharsets.UTF_8);
            log.info("[YDeepSeekClient][chat] httpCode: {}, response: {}", response.getStatus(), respBody);
            if (response.getStatus() != 200) {
                throw new RuntimeException("DeepSeek 调用失败，httpCode: " + response.getStatus() + "，body: " + respBody);
            }
            return parseContent(respBody);
        }
    }

    /**
     * 解析返回的 content
     */
    private String parseContent(String respBody) {
        try {
            // 形如 {"choices":[{"message":{"role":"assistant","content":"..."}}]}，逐层提取避免 JsonNode.path 不支持点分路径
            JsonNode root = JsonUtils.parseTree(respBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                throw new RuntimeException("DeepSeek 返回内容为空，body: " + respBody);
            }
            String content = contentNode.asText();
            // 兼容 AI 用 ```json ... ``` 代码块包裹返回
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start > 0 && end > start) {
                    json = json.substring(start + 1, end).trim();
                }
            }
            // 取第一个 { 到最后一个 }，剔除前后多余文字
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return json;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek 返回解析失败，body: " + respBody, e);
        }
    }

}
