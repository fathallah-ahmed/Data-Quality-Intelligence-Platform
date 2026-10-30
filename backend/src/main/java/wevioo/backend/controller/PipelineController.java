package wevioo.backend.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import wevioo.backend.config.AppProperties;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.dto.PipelineDtos.*;
import wevioo.backend.service.EventStreamService;
import wevioo.backend.service.PipelineIngestionService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pipeline/{runId}")
public class PipelineController {
    private final PipelineIngestionService pipelineService;
    private final EventStreamService eventStreamService;
    private final AppProperties properties;

    public PipelineController(
        PipelineIngestionService pipelineService,
        EventStreamService eventStreamService,
        AppProperties properties
    ) {
        this.pipelineService = pipelineService;
        this.eventStreamService = eventStreamService;
        this.properties = properties;
    }

    @PostMapping("/start")
    public Map<String, String> started(
        @PathVariable String runId,
        @RequestHeader(value = "X-Pipeline-Token", required = false) String token,
        @RequestBody(required = false) Map<String, Object> request
    ) {
        requirePipelineToken(token);
        publish(pipelineService.markStarted(runId));
        return Map.of("status", "accepted");
    }

    @PostMapping("/table-analysis")
    public Map<String, String> tableAnalysis(
        @PathVariable String runId,
        @RequestHeader(value = "X-Pipeline-Token", required = false) String token,
        @RequestBody Map<String, Object> request
    ) {
        requirePipelineToken(token);
        publish(pipelineService.saveTableAnalysis(runId, tableAnalysisRequest(request)));
        return Map.of("status", "accepted");
    }

    @PostMapping("/global-analysis")
    public Map<String, String> globalAnalysis(
        @PathVariable String runId,
        @RequestHeader(value = "X-Pipeline-Token", required = false) String token,
        @RequestBody Map<String, Object> request
    ) {
        requirePipelineToken(token);
        publish(pipelineService.saveGlobalAnalysis(runId, globalAnalysisRequest(request)));
        return Map.of("status", "accepted");
    }

    @PostMapping("/complete")
    public Map<String, String> completed(
        @PathVariable String runId,
        @RequestHeader(value = "X-Pipeline-Token", required = false) String token,
        @RequestBody Map<String, Object> request
    ) {
        requirePipelineToken(token);
        publish(pipelineService.markCompleted(runId, new PipelineCompletedRequest(
            stringValue(request, "pipeline_run_id"),
            stringValue(request, "status"),
            stringValue(request, "error_message")
        )));
        return Map.of("status", "accepted");
    }

    private void publish(LiveEvent event) {
        eventStreamService.publish(event);
    }

    private void requirePipelineToken(String token) {
        String expected = properties.getPipelineApiToken();
        if (expected == null || expected.isBlank() || !expected.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid pipeline token");
        }
    }

    private TableAnalysisRequest tableAnalysisRequest(Map<String, Object> payload) {
        return new TableAnalysisRequest(
            stringValue(payload, "dataset_id"),
            stringValue(payload, "version_id"),
            stringValue(payload, "pipeline_run_id"),
            stringValue(payload, "table_name"),
            datasetMetrics(mapValue(payload.get("dataset_metrics"))),
            columnMetrics(listValue(payload.get("column_metrics"))),
            mapValue(payload.get("llm_interpretation")),
            stringValue(payload, "llm_model_used"),
            stringValue(payload, "metrics_json_path"),
            stringValue(payload, "analysis_json_path"),
            mapValue(payload.get("data_signature")),
            stringValue(payload, "change_status"),
            stringValue(payload, "status")
        );
    }

    private GlobalAnalysisRequest globalAnalysisRequest(Map<String, Object> payload) {
        return new GlobalAnalysisRequest(
            stringValue(payload, "pipeline_run_id"),
            intValue(payload.get("dataset_count")),
            longValue(payload.get("total_row_count")),
            longValue(payload.get("total_column_count")),
            mapValue(payload.get("llm_interpretation")),
            stringValue(payload, "analysis_json_path")
        );
    }

    private DatasetMetrics datasetMetrics(Map<String, Object> payload) {
        if (payload.isEmpty()) {
            return null;
        }
        return new DatasetMetrics(
            longValue(payload.get("row_count")),
            intValue(payload.get("column_count")),
            longValue(payload.get("duplicate_rows")),
            doubleValue(payload.get("duplicate_percent"))
        );
    }

    private List<ColumnMetrics> columnMetrics(List<Object> payload) {
        return payload.stream()
            .map(this::mapValue)
            .map(column -> new ColumnMetrics(
                stringValue(column, "name"),
                stringValue(column, "data_type"),
                longValue(column.get("missing_count")),
                doubleValue(column.get("missing_percent")),
                longValue(column.get("distinct_count")),
                doubleValue(column.get("min_value")),
                doubleValue(column.get("max_value"))
            ))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private String stringValue(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer intValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
