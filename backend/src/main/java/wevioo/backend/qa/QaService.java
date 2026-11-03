package wevioo.backend.qa;

import org.springframework.stereotype.Service;
import wevioo.backend.domain.Dataset;
import wevioo.backend.dto.DatasetDtos.ColumnMetricView;
import wevioo.backend.dto.DatasetDtos.QualityDimension;
import wevioo.backend.dto.DatasetDtos.TableAnalysisView;
import wevioo.backend.dto.DatasetDtos.VersionDetails;
import wevioo.backend.dto.RuleDtos.RuleResultView;
import wevioo.backend.exception.NotFoundException;
import wevioo.backend.repository.DatasetRepository;
import wevioo.backend.service.DatasetService;
import wevioo.backend.service.GraphService;
import wevioo.backend.service.RuleService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers a question about a dataset version. The graph holds the data; the quality results (rules and why they
 * fail, dimensions, what the graph build found, column roles) live in the backend, so they are gathered here and
 * sent with the question: the model can then explain a failing rule with its own message and examples, which a
 * graph alone cannot do.
 */
@Service
public class QaService {
    static final int MAX_QUESTION_CHARS = 1000;
    private static final int MAX_COLUMNS = 150;
    /** How to use the application, kept in a file so it is written and reviewed like documentation. */
    static final String GUIDE = readGuide();

    private static String readGuide() {
        try (java.io.InputStream in = QaService.class.getResourceAsStream("/help/app-guide.md")) {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private final GraphService graph;
    private final DatasetService datasets;
    private final RuleService rules;
    private final DatasetRepository datasetRepository;
    private final QaClient client;

    public QaService(GraphService graph, DatasetService datasets, RuleService rules, DatasetRepository datasetRepository, QaClient client) {
        this.graph = graph;
        this.datasets = datasets;
        this.rules = rules;
        this.datasetRepository = datasetRepository;
        this.client = client;
    }

    public Map<String, Object> ask(Long datasetId, Long versionId, String question) {
        String text = question == null ? "" : question.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Write a question");
        }
        if (text.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("The question is limited to " + MAX_QUESTION_CHARS + " characters");
        }
        // The graph is optional: quality and how-to questions do not need it, and the service says so when a question does.
        Map<String, Object> built = graph.last(datasetId, versionId);
        return client.ask(text, GraphService.scope(datasetId, versionId), facts(datasetId, versionId, built));
    }

    Map<String, Object> facts(Long datasetId, Long versionId, Map<String, Object> built) {
        Dataset dataset = datasetRepository.findById(datasetId).orElseThrow(() -> new NotFoundException("Dataset not found"));
        VersionDetails details = datasets.getVersionDetails(datasetId, versionId, false);
        Map<String, Object> result = map(built.get("result"));

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("dataset", dataset.getName());
        facts.put("version", details.version().versionNumber());
        facts.put("context", graph.businessContext(datasetId, versionId));
        facts.put("rules", ruleFacts(datasetId, versionId));
        facts.put("dimensions", dimensionFacts(details.dimensions()));
        facts.put("findings", result.getOrDefault("findings", List.of()));
        facts.put("columns", columnFacts(details.tables()));
        if (!built.isEmpty()) {
            Map<String, Object> graphFacts = new LinkedHashMap<>();
            graphFacts.put("schema", built.get("schema"));
            graphFacts.put("built", Map.of("nodes", result.getOrDefault("nodes", Map.of()), "relationships", result.getOrDefault("relationships", Map.of())));
            facts.put("graph", graphFacts);
        }
        facts.put("guide", GUIDE);
        return facts;
    }

    private List<Map<String, Object>> ruleFacts(Long datasetId, Long versionId) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (RuleResultView rule : rules.results(datasetId, versionId).results()) {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("description", rule.description());
            fact.put("status", rule.status());
            fact.put("severity", rule.severity());
            fact.put("message", rule.message());
            facts.add(fact);
        }
        return facts;
    }

    private List<Map<String, Object>> dimensionFacts(List<QualityDimension> dimensions) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (QualityDimension dimension : dimensions == null ? List.<QualityDimension>of() : dimensions) {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("label", dimension.label());
            fact.put("percent", dimension.percent());
            fact.put("numerator", dimension.numerator());
            fact.put("denominator", dimension.denominator());
            fact.put("basis", dimension.basis());
            facts.add(fact);
        }
        return facts;
    }

    private List<Map<String, Object>> columnFacts(List<TableAnalysisView> tables) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (TableAnalysisView table : tables) {
            for (ColumnMetricView column : table.columns()) {
                if (facts.size() >= MAX_COLUMNS) {
                    return facts;
                }
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("table", table.tableName());
                fact.put("column", column.columnName().strip());
                fact.put("role", column.role());
                fact.put("missingPercent", column.missingPercentage());
                facts.add(fact);
            }
        }
        return facts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
