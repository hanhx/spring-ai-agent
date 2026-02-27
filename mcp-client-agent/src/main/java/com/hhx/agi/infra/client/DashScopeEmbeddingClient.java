package com.hhx.agi.infra.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DashScopeEmbeddingClient {

    private final String model;
    private final String encodingFormat;
    private final String apiKey;
    private final WebClient webClient;

    public DashScopeEmbeddingClient(
            @Value("${dashscope.embedding.model:text-embedding-v4}") String model,
            @Value("${dashscope.embedding.encoding-format:float}") String encodingFormat,
            @Value("${dashscope.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.model = model;
        this.encodingFormat = encodingFormat;
        this.apiKey = apiKey;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public float[] embedSingleText(String text) {
        List<float[]> vectors = embedTexts(List.of(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("empty embedding vector response");
        }
        return vectors.get(0);
    }

    public List<float[]> embedTexts(List<String> texts) {
        if (apiKey == null || apiKey.isBlank() || "xxx".equals(apiKey)) {
            throw new IllegalStateException("DashScope API key is empty, please configure spring.ai.openai.api-key");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", texts);
        payload.put("encoding_format", encodingFormat);

        JsonNode root = webClient.post()
                .headers(h -> {
                    h.setBearerAuth(apiKey);
                    h.set("Content-Type", "application/json");
                })
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (root == null || !root.has("data") || !root.get("data").isArray()) {
            throw new IllegalStateException("invalid embedding response payload");
        }

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : root.get("data")) {
            JsonNode embeddingNode = item.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray()) {
                continue;
            }
            float[] vec = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vec[i] = (float) embeddingNode.get(i).asDouble();
            }
            vectors.add(vec);
        }

        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("embedding size mismatch, expect " + texts.size() + " but got " + vectors.size());
        }
        return vectors;
    }
}
