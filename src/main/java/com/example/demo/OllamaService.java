package com.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Service
public class OllamaService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OllamaService(ObjectMapper objectMapper) {
        this.webClient = WebClient.builder()
                .baseUrl("http://localhost:11434")
                .build();
        this.objectMapper = objectMapper;
    }

    public String callOllamaForJSON(String userPrompt) throws Exception {
        // 1. Construct the JSON body using ObjectMapper
        ObjectNode requestNode = objectMapper.createObjectNode()
                .put("model", "llama3.2:latest")
                .put("prompt", createSystemPrompt() + "\n" + userPrompt)
                .put("stream", false);

        String requestBody = objectMapper.writeValueAsString(requestNode);
        System.out.println("Sending request to Ollama: " + requestBody);

        try {
            // 2. Make the POST request to Ollama
            System.out.println("Making request to Ollama...");
            String response = webClient.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> {
                                System.out.println("Received error status: " + clientResponse.statusCode());
                                return clientResponse.bodyToMono(String.class)
                                        .flatMap(errorBody -> {
                                            System.out.println("Error body: " + errorBody);
                                            return Mono.error(new RuntimeException("Error response: " + errorBody));
                                        });
                            })
                    .bodyToMono(String.class)
                    .doOnNext(r -> System.out.println("Received response: " + r))
                    .block();

            System.out.println("Raw response from Ollama: " + response);

            if (response != null) {
                // 3. The response is a JSON object with a "response" field that holds the entire generation
                JsonNode root = objectMapper.readTree(response);
                System.out.println("Parsed JSON response: " + root.toString());
                String result = root.get("response").asText();
                System.out.println("Final response: " + result);
                return result;
            } else {
                System.out.println("Received null response from Ollama");
                throw new RuntimeException("Failed to get a valid response from Ollama");
            }
        } catch (WebClientResponseException e) {
            System.err.println("WebClientResponseException occurred:");
            System.err.println("Status code: " + e.getStatusCode());
            System.err.println("Response body: " + e.getResponseBodyAsString());
            throw e;
        } catch (Exception e) {
            System.err.println("Unexpected exception: " + e.getClass().getName());
            System.err.println("Message: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * This is our system-like prompt instructing Ollama how to format the output.
     */
    private String createSystemPrompt() {
        return """
        You are an AI that returns roadmap data in JSON format.
        Please ONLY return valid JSON. The JSON structure should look like:
        {
          "title": "string",
          "description": "string",
          "link": "string or null",
          "children": [
            {
              "title": "string",
              "description": "string",
              "link": "string or null",
              "children": [...]
            },
            ...
          ]
        }
        """;
    }

    /**
     * Parse the returned JSON (the "response" field) into our RoadmapNodeDTO recursively.
     */
    public RoadmapNodeDTO parseRoadmapJSON(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        RoadmapNodeDTO dto = new RoadmapNodeDTO();
        dto.setTitle(getText(root, "title"));
        dto.setDescription(getText(root, "description"));
        dto.setLink(getText(root, "link"));

        if (root.has("children") && root.get("children").isArray()) {
            for (JsonNode child : root.get("children")) {
                RoadmapNodeDTO childDTO = parseRoadmapJSON(child.toString());
                dto.getChildren().add(childDTO);
            }
        }
        return dto;
    }

    private String getText(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            return node.get(fieldName).asText();
        }
        return null;
    }
}
