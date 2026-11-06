package com.auditiq.analytics;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.auditiq.common.Exceptions.ConflictException;
import com.auditiq.common.Exceptions.ServiceUnavailableException;
import com.auditiq.config.AnalyticsServiceProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client for the internal Python analytics / AI service. Every call carries the shared service key; failures are
 * translated into domain exceptions so controllers return meaningful HTTP statuses.
 */
@Component
public class AnalyticsClient {

    static final String SERVICE_KEY_HEADER = "X-Service-Key";

    private final RestClient client;
    private final RestClient longClient;
    private final RestClient healthClient;
    private final ObjectMapper mapper;

    public AnalyticsClient(AnalyticsServiceProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(SERVICE_KEY_HEADER, props.serviceKey() == null ? "" : props.serviceKey())
                .requestFactory(factory(Duration.ofSeconds(3), Duration.ofSeconds(30)))
                .build();
        // Rule execution is synchronous and scans the full curated layer.
        this.longClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(SERVICE_KEY_HEADER, props.serviceKey() == null ? "" : props.serviceKey())
                .requestFactory(factory(Duration.ofSeconds(3), Duration.ofMinutes(10)))
                .build();
        this.healthClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory(Duration.ofSeconds(2), Duration.ofSeconds(3)))
                .build();
    }

    private static SimpleClientHttpRequestFactory factory(Duration connect, Duration read) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StartRunCommand(String source, String triggeredBy, Integer seed, Integer transactionCount,
                                  String folder) {
    }

    public record StartRunResult(long runId, String runRef, String status) {
    }

    public StartRunResult startPipelineRun(StartRunCommand command) {
        return call(() -> client.post().uri("/internal/pipeline/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(StartRunResult.class));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecuteRulesCommand(String executedBy, List<String> ruleIds) {
    }

    public record RuleResult(String ruleId, String ruleName, long executionId, String status, int matchesTotal,
                             int exceptionsCreated, long durationMs, String error) {
    }

    public record ExecuteRulesResult(String batchRef, String executedBy, long durationMs, List<RuleResult> results) {
    }

    /** Runs audit rules synchronously; 409 when another execution is in progress. */
    public ExecuteRulesResult executeRules(ExecuteRulesCommand command) {
        return call(() -> longClient.post().uri("/internal/rules/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(ExecuteRulesResult.class));
    }

    public record TrainModelCommand(String trainedBy, Integer nEstimators, Integer maxSamples, Integer seed) {
    }

    /** Trains a new model version (synchronous); 409 when a model run is in progress. */
    public JsonNode trainModel(TrainModelCommand command) {
        return call(() -> longClient.post().uri("/internal/ml/train")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.createObjectNode().put("trainedBy", command.trainedBy())
                        .put("nEstimators", command.nEstimators() == null ? 200 : command.nEstimators())
                        .put("maxSamples", command.maxSamples() == null ? 256 : command.maxSamples())
                        .put("seed", command.seed() == null ? 42 : command.seed()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(JsonNode.class));
    }

    /** Scores unscored transactions with the active model and raises potential exceptions. */
    public JsonNode detectAnomalies(String executedBy) {
        return call(() -> longClient.post().uri("/internal/ml/detect")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("executedBy", executedBy))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(JsonNode.class));
    }

    /** Evaluation of a model version (active when {@code versionId} is null). */
    public JsonNode modelPerformance(Long versionId) {
        return call(() -> longClient.get()
                .uri(b -> versionId == null ? b.path("/internal/ml/performance").build()
                        : b.path("/internal/ml/performance").queryParam("version_id", versionId).build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::translateError)
                .body(JsonNode.class));
    }

    private void translateError(org.springframework.http.HttpRequest req,
                                org.springframework.http.client.ClientHttpResponse res) throws java.io.IOException {
        String detail = detail(res.getBody().readAllBytes());
        switch (res.getStatusCode().value()) {
            case 409 -> throw new ConflictException(detail);
            case 400, 422 -> throw new IllegalArgumentException(detail);
            default -> throw new ServiceUnavailableException(
                    "The analytics service rejected the request (" + res.getStatusCode().value() + ")", null);
        }
    }

    public List<String> pipelineSources() {
        JsonNode body = call(() -> client.get().uri("/internal/pipeline/sources").retrieve().body(JsonNode.class));
        return body == null ? List.of()
                : mapper.convertValue(body.path("folders"), mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    /** UP, DEGRADED or DOWN – never throws. */
    public String health() {
        try {
            Map<?, ?> body = healthClient.get().uri("/health").retrieve().body(Map.class);
            return body != null && "UP".equals(body.get("status")) ? "UP" : "DEGRADED";
        } catch (RestClientException ex) {
            return "DOWN";
        }
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException ex) {
            throw new ServiceUnavailableException("The analytics service is unavailable. Try again shortly.", ex);
        }
    }

    private String detail(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body).path("detail");
            return node.isTextual() ? node.asText() : "Invalid request parameters";
        } catch (java.io.IOException ex) {
            return "Invalid request parameters";
        }
    }
}
