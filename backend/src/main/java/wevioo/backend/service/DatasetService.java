package wevioo.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import wevioo.backend.config.AppProperties;
import wevioo.backend.domain.*;
import wevioo.backend.dto.DatasetDtos.*;
import wevioo.backend.dto.UploadResponse;
import wevioo.backend.repository.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;

@Service
public class DatasetService {
    private final AppProperties properties;
    private final DatasetRepository datasetRepository;
    private final DatasetVersionRepository versionRepository;
    private final TableVersionRepository tableVersionRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final GlobalAnalysisRepository globalAnalysisRepository;
    private final JsonService jsonService;
    private final AirflowClient airflowClient;

    public DatasetService(
        AppProperties properties,
        DatasetRepository datasetRepository,
        DatasetVersionRepository versionRepository,
        TableVersionRepository tableVersionRepository,
        PipelineRunRepository pipelineRunRepository,
        GlobalAnalysisRepository globalAnalysisRepository,
        JsonService jsonService,
        AirflowClient airflowClient
    ) {
        this.properties = properties;
        this.datasetRepository = datasetRepository;
        this.versionRepository = versionRepository;
        this.tableVersionRepository = tableVersionRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.globalAnalysisRepository = globalAnalysisRepository;
        this.jsonService = jsonService;
        this.airflowClient = airflowClient;
    }

    @Transactional
    public UploadResponse upload(
        String requestedName,
        MultipartFile[] files,
        MultipartFile legacyFile,
        boolean rerunExisting,
        String contextScope,
        String globalDescription,
        String globalQuestion,
        String tableDescriptionsJson
    ) {
        List<MultipartFile> uploadFiles = normalizeFiles(files, legacyFile);
        if (uploadFiles.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one CSV file");
        }
        String firstFilename = safeFilename(uploadFiles.get(0));
        String datasetName = (requestedName == null || requestedName.isBlank()) ? stripExtension(firstFilename) : requestedName.trim();
        Map<String, Object> uploadSignature = buildUploadSignature(uploadFiles);

        Optional<Dataset> existingDataset = datasetRepository.findByName(datasetName);
        if (existingDataset.isPresent()) {
            Dataset dataset = existingDataset.get();
            Optional<DatasetVersion> matchingVersion = findMatchingVersion(dataset, uploadSignature);
            if (matchingVersion.isPresent()) {
                DatasetVersion version = matchingVersion.get();
                String duplicateMessage = "Dataset '" + dataset.getName() + "' already has the same uploaded files in version "
                    + version.getVersionNumber() + ".";
                if (!rerunExisting) {
                    return new UploadResponse(
                        dataset.getId(),
                        version.getId(),
                        version.getVersionNumber(),
                        latestRunId(version),
                        version.getStatus().name(),
                        version.getFilePath(),
                        uploadFiles.size(),
                        false,
                        "Duplicate upload detected. Existing version was not re-run.",
                        true,
                        false,
                        duplicateMessage
                    );
                }
                return triggerExistingVersion(
                    dataset,
                    version,
                    uploadFiles.size(),
                    duplicateMessage,
                    contextScope,
                    globalDescription,
                    globalQuestion,
                    tableDescriptionsJson
                );
            }
        }

        Dataset dataset = existingDataset.orElseGet(() -> {
            Dataset created = new Dataset();
            created.setName(datasetName);
            created.setSourceType(uploadFiles.size() > 1 ? "CSV_COLLECTION" : sourceType(firstFilename));
            return datasetRepository.save(created);
        });

        int nextVersion = versionRepository.findTopByDatasetIdOrderByVersionNumberDesc(dataset.getId())
            .map(v -> v.getVersionNumber() + 1)
            .orElse(1);

        Path versionDir = storeFiles(dataset.getId(), nextVersion, uploadFiles);
        writeDatasetConfig(versionDir, contextScope, globalDescription, globalQuestion, tableDescriptionsJson);

        DatasetVersion version = new DatasetVersion();
        version.setDataset(dataset);
        version.setVersionNumber(nextVersion);
        version.setFilePath(versionDir.toString());
        version.setExpectedTableCount(uploadFiles.size());
        version.setDataSignatureJson(jsonService.toJson(Map.of("upload", uploadSignature)));
        version.setStatus(RunStatus.PENDING);
        version = versionRepository.save(version);

        PipelineRun run = createPipelineRun(version);
        AirflowClient.TriggerResult trigger = triggerAirflow(dataset, version, versionDir, run.getExternalRunId());

        return new UploadResponse(
            dataset.getId(),
            version.getId(),
            version.getVersionNumber(),
            run.getExternalRunId(),
            version.getStatus().name(),
            versionDir.toString(),
            uploadFiles.size(),
            trigger.triggered(),
            trigger.message(),
            false,
            false,
            null
        );
    }

    @Transactional(readOnly = true)
    public List<DatasetSummary> listDatasets() {
        return datasetRepository.findAll().stream()
            .map(dataset -> {
                VersionSummary latest = versionRepository.findTopByDatasetIdOrderByVersionNumberDesc(dataset.getId())
                    .map(this::toVersionSummary)
                    .orElse(null);
                return new DatasetSummary(
                    dataset.getId(),
                    dataset.getName(),
                    dataset.getSourceType(),
                    dataset.getCreatedAt(),
                    dataset.getUpdatedAt(),
                    latest
                );
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public List<VersionSummary> listVersions(Long datasetId) {
        return versionRepository.findByDatasetIdOrderByVersionNumberDesc(datasetId).stream()
            .map(this::toVersionSummary)
            .toList();
    }

    @Transactional(readOnly = true)
    public VersionDetails getVersionDetails(Long datasetId, Long versionId, boolean includeRaw) {
        DatasetVersion version = versionRepository.findById(versionId)
            .filter(v -> v.getDataset().getId().equals(datasetId))
            .orElseThrow(() -> new IllegalArgumentException("Dataset version not found"));

        GlobalAnalysisView global = globalAnalysisRepository.findByDatasetVersionId(versionId)
            .map(item -> toGlobalView(item, includeRaw))
            .orElse(null);

        List<TableAnalysisView> tables = tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(versionId).stream()
            .map(item -> toTableView(item, includeRaw))
            .toList();

        return new VersionDetails(toVersionSummary(version), global, tables);
    }

    @Transactional
    public void deleteDataset(Long datasetId) {
        Dataset dataset = datasetRepository.findById(datasetId)
            .orElseThrow(() -> new IllegalArgumentException("Dataset not found"));

        tableVersionRepository.findByDatasetVersionDatasetId(datasetId)
            .forEach(tableVersion -> tableVersion.setPreviousTableVersion(null));
        tableVersionRepository.flush();

        datasetRepository.delete(dataset);
        datasetRepository.flush();

        deleteDatasetArtifacts(datasetId);
    }

    private VersionSummary toVersionSummary(DatasetVersion version) {
        return new VersionSummary(
            version.getId(),
            version.getVersionNumber(),
            version.getStatus().name(),
            version.getFilePath(),
            version.getExpectedTableCount(),
            version.getUploadedAt(),
            latestRunId(version)
        );
    }

    private UploadResponse triggerExistingVersion(
        Dataset dataset,
        DatasetVersion version,
        int fileCount,
        String duplicateMessage,
        String contextScope,
        String globalDescription,
        String globalQuestion,
        String tableDescriptionsJson
    ) {
        version.setStatus(RunStatus.PENDING);
        Path versionDir = Path.of(version.getFilePath());
        writeDatasetConfig(versionDir, contextScope, globalDescription, globalQuestion, tableDescriptionsJson);
        PipelineRun run = createPipelineRun(version);
        AirflowClient.TriggerResult trigger = triggerAirflow(dataset, version, versionDir, run.getExternalRunId());
        return new UploadResponse(
            dataset.getId(),
            version.getId(),
            version.getVersionNumber(),
            run.getExternalRunId(),
            version.getStatus().name(),
            version.getFilePath(),
            fileCount,
            trigger.triggered(),
            trigger.message(),
            true,
            trigger.triggered(),
            duplicateMessage
        );
    }

    private PipelineRun createPipelineRun(DatasetVersion version) {
        String runId = "run-" + UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setExternalRunId(runId);
        run.setDatasetVersion(version);
        run.setStatus(RunStatus.PENDING);
        return pipelineRunRepository.save(run);
    }

    private AirflowClient.TriggerResult triggerAirflow(Dataset dataset, DatasetVersion version, Path versionDir, String runId) {
        return airflowClient.triggerPipeline(
            runId,
            String.valueOf(dataset.getId()),
            String.valueOf(version.getVersionNumber()),
            toContainerPath(versionDir)
        );
    }

    private String latestRunId(DatasetVersion version) {
        return version.getPipelineRuns().isEmpty()
            ? null
            : version.getPipelineRuns().get(version.getPipelineRuns().size() - 1).getExternalRunId();
    }

    private TableAnalysisView toTableView(TableVersion tableVersion, boolean includeRaw) {
        TableAnalysis analysis = tableVersion.getAnalysis();
        if (analysis == null) {
            return new TableAnalysisView(
                tableVersion.getId(),
                tableVersion.getTableName(),
                tableVersion.getChangeStatus().name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                jsonService.toMap(tableVersion.getDataSignatureJson()),
                Map.of(),
                Map.of(),
                List.of()
            );
        }
        List<ColumnMetricView> columns = analysis.getColumnMetrics().stream()
            .map(column -> new ColumnMetricView(
                column.getColumnName(),
                column.getDataType(),
                column.getMissingCount(),
                column.getMissingPercentage(),
                column.getUniqueCount(),
                column.getMinValue(),
                column.getMaxValue()
            ))
            .toList();
        return new TableAnalysisView(
            tableVersion.getId(),
            tableVersion.getTableName(),
            tableVersion.getChangeStatus().name(),
            analysis.getRowCount(),
            analysis.getColumnCount(),
            analysis.getMissingCount(),
            analysis.getDuplicateCount(),
            analysis.getQualityScore(),
            analysis.getLlmSummary(),
            jsonService.toMap(analysis.getLlmInterpretationJson()),
            jsonService.toMap(tableVersion.getDataSignatureJson()),
            includeRaw ? readPipelineJson(analysis.getMetricsJsonPath()) : Map.of(),
            includeRaw ? readPipelineJson(analysis.getAnalysisJsonPath()) : Map.of(),
            columns
        );
    }

    private GlobalAnalysisView toGlobalView(GlobalAnalysis global, boolean includeRaw) {
        return new GlobalAnalysisView(
            global.getId(),
            global.getTotalTables(),
            global.getTotalRows(),
            global.getTotalColumns(),
            global.getGlobalQualityScore(),
            global.getLlmGlobalSummary(),
            jsonService.toMap(global.getLlmInterpretationJson()),
            global.getRecommendations(),
            includeRaw ? readPipelineJson(global.getAnalysisJsonPath()) : Map.of()
        );
    }

    private Map<String, Object> readPipelineJson(String path) {
        if (path == null || path.isBlank()) {
            return Map.of();
        }
        Path resolved = resolvePipelinePath(path);
        if (resolved == null || !Files.exists(resolved)) {
            return Map.of();
        }
        return jsonService.readMap(resolved);
    }

    private Path resolvePipelinePath(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/opt/airflow/")) {
            Path workspacePath = Path.of("/workspace/Data_Quality_Wevioo", normalized.substring("/opt/airflow/".length()));
            if (Files.exists(workspacePath)) {
                return workspacePath;
            }
            return Path.of("..", "Data_Quality_Wevioo", normalized.substring("/opt/airflow/".length())).toAbsolutePath().normalize();
        }
        String marker = "/Data_Quality_Wevioo/";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex >= 0) {
            return Path.of("..", "Data_Quality_Wevioo", normalized.substring(markerIndex + marker.length()))
                .toAbsolutePath()
                .normalize();
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    private void deleteDatasetArtifacts(Long datasetId) {
        Path uploadRoot = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
        deleteDirectory(uploadRoot.resolve("datasets").resolve("dataset_" + datasetId), uploadRoot);

        Path pipelineStorageRoot = Path.of("..", "Data_Quality_Wevioo", "storage").toAbsolutePath().normalize();
        deleteDirectory(pipelineStorageRoot.resolve("datasets").resolve(String.valueOf(datasetId)), pipelineStorageRoot);
        deleteDirectory(pipelineStorageRoot.resolve("json").resolve(String.valueOf(datasetId)), pipelineStorageRoot);
        deleteDirectory(pipelineStorageRoot.resolve("sweetviz").resolve(String.valueOf(datasetId)), pipelineStorageRoot);
    }

    private void deleteDirectory(Path target, Path allowedRoot) {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedRoot = allowedRoot.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || normalizedTarget.equals(normalizedRoot) || !Files.exists(normalizedTarget)) {
            return;
        }
        try (var paths = Files.walk(normalizedTarget)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    System.err.println("Could not delete dataset artifact " + path + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("Could not scan dataset artifact folder " + normalizedTarget + ": " + e.getMessage());
        }
    }

    private Optional<DatasetVersion> findMatchingVersion(Dataset dataset, Map<String, Object> uploadSignature) {
        return versionRepository.findByDatasetIdOrderByVersionNumberDesc(dataset.getId()).stream()
            .filter(version -> versionMatchesUpload(version, uploadSignature))
            .findFirst();
    }

    private boolean versionMatchesUpload(DatasetVersion version, Map<String, Object> uploadSignature) {
        if (version.getFilePath() == null || !Files.exists(Path.of(version.getFilePath()))) {
            return false;
        }
        Map<String, Object> storedSignature = jsonService.toMap(version.getDataSignatureJson());
        Object storedUpload = storedSignature.get("upload");
        if (uploadSignature.equals(storedUpload)) {
            return true;
        }
        try {
            return uploadSignature.equals(buildStoredUploadSignature(Path.of(version.getFilePath())));
        } catch (IOException e) {
            return false;
        }
    }

    private Map<String, Object> buildUploadSignature(List<MultipartFile> files) {
        List<Map<String, Object>> entries = files.stream()
            .map(file -> fileSignatureEntry(safeFilename(file), file.getSize()))
            .sorted(Comparator.comparing(entry -> String.valueOf(entry.get("name"))))
            .toList();
        return uploadSignatureFromEntries(entries);
    }

    private Map<String, Object> buildStoredUploadSignature(Path versionDir) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (var paths = Files.list(versionDir)) {
            for (Path path : paths
                .filter(item -> item.getFileName().toString().toLowerCase().endsWith(".csv"))
                .filter(item -> !item.getFileName().toString().equalsIgnoreCase("dataset_config.csv"))
                .sorted(Comparator.comparing(item -> item.getFileName().toString()))
                .toList()) {
                entries.add(fileSignatureEntry(path.getFileName().toString(), Files.size(path)));
            }
        }
        return uploadSignatureFromEntries(entries);
    }

    private Map<String, Object> uploadSignatureFromEntries(List<Map<String, Object>> entries) {
        long totalSize = entries.stream()
            .map(entry -> entry.get("size_bytes"))
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .mapToLong(Number::longValue)
            .sum();
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("file_count", entries.size());
        signature.put("total_size_bytes", totalSize);
        signature.put("files", entries);
        return signature;
    }

    private Map<String, Object> fileSignatureEntry(String name, long size) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("size_bytes", size);
        return entry;
    }

    private Path storeFiles(Long datasetId, int versionNumber, List<MultipartFile> files) {
        try {
            Path dir = Path.of(properties.getStorageRoot(), "datasets", "dataset_" + datasetId, "v" + versionNumber);
            Files.createDirectories(dir);
            for (MultipartFile file : files) {
                String filename = safeFilename(file);
                if (!filename.toLowerCase().endsWith(".csv")) {
                    throw new IllegalArgumentException("Only CSV tables are supported for multi-table uploads: " + filename);
                }
                Path destination = dir.resolve(filename);
                Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return dir.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalStateException("Could not store uploaded files", e);
        }
    }

    private void writeDatasetConfig(
        Path versionDir,
        String contextScope,
        String globalDescription,
        String globalQuestion,
        String tableDescriptionsJson
    ) {
        try {
            Files.createDirectories(versionDir);
            Path configPath = versionDir.resolve("dataset_config.csv");
            String scope = normalizeContextScope(contextScope);
            boolean includeGlobal = scope.equals("global") || scope.equals("both");
            boolean includeTables = scope.equals("per_table") || scope.equals("both");
            List<String> lines = new ArrayList<>();
            lines.add("dataset_name,description,custom_question");

            if ((includeGlobal && hasText(globalDescription)) || hasText(globalQuestion)) {
                lines.add(csvCell("__global__") + "," + csvCell(globalDescription) + "," + csvCell(globalQuestion));
            }

            if (includeTables) {
                Map<String, Object> descriptions = jsonService.toMap(tableDescriptionsJson);
                descriptions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String tableName = stripExtension(entry.getKey()).trim();
                        String description = cleanText(entry.getValue());
                        if (!tableName.isBlank() && hasText(description)) {
                            lines.add(csvCell(tableName) + "," + csvCell(description) + "," + csvCell(""));
                        }
                    });
            }

            if (lines.size() == 1) {
                Files.deleteIfExists(configPath);
                return;
            }

            Files.write(configPath, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store dataset context config", e);
        }
    }

    private String normalizeContextScope(String contextScope) {
        String normalized = cleanText(contextScope).toLowerCase();
        if (normalized.equals("global") || normalized.equals("per_table") || normalized.equals("both")) {
            return normalized;
        }
        return "none";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String cleanText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String csvCell(Object value) {
        String text = cleanText(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private List<MultipartFile> normalizeFiles(MultipartFile[] files, MultipartFile legacyFile) {
        List<MultipartFile> normalized = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    normalized.add(file);
                }
            }
        }
        if (legacyFile != null && !legacyFile.isEmpty()) {
            normalized.add(legacyFile);
        }
        return normalized;
    }

    private String safeFilename(MultipartFile file) {
        return Path.of(file.getOriginalFilename() == null ? "table.csv" : file.getOriginalFilename()).getFileName().toString();
    }

    private String toContainerPath(Path hostPath) {
        String normalized = hostPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        String marker = "/Data_Quality_Wevioo/";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex >= 0) {
            return "/opt/airflow/" + normalized.substring(markerIndex + marker.length());
        }
        return normalized;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String sourceType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return "EXCEL";
        }
        return "CSV";
    }
}
