package wevioo.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import wevioo.backend.config.AppProperties;
import wevioo.backend.domain.*;
import wevioo.backend.dto.DatasetDtos.*;
import wevioo.backend.dto.LiveEvent;
import wevioo.backend.dto.RuleDtos;
import wevioo.backend.dto.UploadResponse;
import wevioo.backend.exception.ConflictException;
import wevioo.backend.exception.NotFoundException;
import wevioo.backend.repository.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Upload, versioning and read-side of datasets.
 *
 * <p>Uploads are validated before anything touches the disk, stored and recorded in one
 * transaction, and Airflow is only triggered after that transaction committed, so the
 * pipeline's {@code /start} callback always finds its run.
 */
@Service
public class DatasetService {
    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);
    private static final String DATASET_CONFIG_FILE = "dataset_config.csv";
    static final String BUSINESS_CONTEXT_FILE = "dataset_context.json";
    private static final int MAX_CONTEXT_CHARS = 1000;

    private final AppProperties properties;
    private final DatasetRepository datasetRepository;
    private final DatasetVersionRepository versionRepository;
    private final TableVersionRepository tableVersionRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final GlobalAnalysisRepository globalAnalysisRepository;
    private final RuleResultRepository ruleResultRepository;
    private final JsonService jsonService;
    private final AirflowClient airflowClient;
    private final EventStreamService eventStreamService;
    private final QualityDimensionsService dimensionsService;
    private final TransactionTemplate transactionTemplate;

    public DatasetService(
        AppProperties properties,
        DatasetRepository datasetRepository,
        DatasetVersionRepository versionRepository,
        TableVersionRepository tableVersionRepository,
        PipelineRunRepository pipelineRunRepository,
        GlobalAnalysisRepository globalAnalysisRepository,
        RuleResultRepository ruleResultRepository,
        JsonService jsonService,
        AirflowClient airflowClient,
        EventStreamService eventStreamService,
        QualityDimensionsService dimensionsService,
        PlatformTransactionManager transactionManager
    ) {
        this.properties = properties;
        this.datasetRepository = datasetRepository;
        this.versionRepository = versionRepository;
        this.tableVersionRepository = tableVersionRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.globalAnalysisRepository = globalAnalysisRepository;
        this.ruleResultRepository = ruleResultRepository;
        this.jsonService = jsonService;
        this.airflowClient = airflowClient;
        this.eventStreamService = eventStreamService;
        this.dimensionsService = dimensionsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public UploadResponse upload(
        String requestedName,
        MultipartFile[] files,
        MultipartFile legacyFile,
        boolean rerunExisting,
        String contextScope,
        String globalDescription,
        String globalQuestion,
        String tableDescriptionsJson,
        BusinessContext business
    ) {
        PreparedUpload prepared = prepareUpload(files, legacyFile);
        String datasetName = hasText(requestedName) ? requestedName.trim() : stripExtension(prepared.files().get(0).name());
        if (datasetName.length() > 255) {
            throw new IllegalArgumentException("Dataset name must be at most 255 characters");
        }
        UploadContext context = new UploadContext(
            contextScope, globalDescription, globalQuestion, tableDescriptionsJson,
            business == null ? BusinessContext.EMPTY : business
        );

        UploadPlan plan = transactionTemplate.execute(status -> planUpload(datasetName, prepared, rerunExisting, context));
        if (plan.duplicateResponse() != null) {
            return plan.duplicateResponse();
        }
        // Outside the transaction: the version and run are committed before Airflow can call back.
        return triggerCommittedRun(plan, prepared.skipped());
    }

    /** Re-run the pipeline on the files already stored for a version (used by the UI "Retry" button). */
    public UploadResponse rerunVersion(Long datasetId, Long versionId) {
        UploadPlan plan = transactionTemplate.execute(status -> {
            DatasetVersion version = requireVersion(datasetId, versionId);
            if (isActive(version.getStatus())) {
                throw new ConflictException("Version " + version.getVersionNumber() + " is already running");
            }
            if (storedFolder(version) == null) {
                throw new ConflictException("The stored files for version " + version.getVersionNumber() + " are missing");
            }
            return prepareRerun(version.getDataset(), version, null, null);
        });
        return triggerCommittedRun(plan, List.of());
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
                    dataset.getVersions().size(),
                    latest == null ? 0 : (int) ruleResultRepository.countByDatasetVersionIdAndStatus(latest.id(), RuleStatus.FAIL),
                    latest
                );
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public List<VersionSummary> listVersions(Long datasetId) {
        if (!datasetRepository.existsById(datasetId)) {
            throw new NotFoundException("Dataset not found");
        }
        return versionRepository.findByDatasetIdOrderByVersionNumberDesc(datasetId).stream()
            .map(this::toVersionSummary)
            .toList();
    }

    @Transactional(readOnly = true)
    public VersionDetails getVersionDetails(Long datasetId, Long versionId, boolean includeRaw) {
        DatasetVersion version = requireVersion(datasetId, versionId);

        GlobalAnalysisView global = globalAnalysisRepository.findByDatasetVersionId(versionId)
            .map(item -> toGlobalView(item, includeRaw))
            .orElse(null);

        List<TableVersion> tableVersions = tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(versionId);
        List<TableAnalysisView> tables = tableVersions.stream()
            .map(item -> toTableView(item, includeRaw))
            .toList();

        return new VersionDetails(
            toVersionSummary(version),
            global,
            tables,
            failedTables(latestRun(version)),
            QualityScores.rowWeighted(tables),
            ruleSummary(versionId),
            dimensionsService.forVersion(datasetId, tableVersions)
        );
    }

    private RuleDtos.RuleSummary ruleSummary(Long versionId) {
        List<RuleResult> results = ruleResultRepository.findByDatasetVersionIdOrderByIdAsc(versionId);
        return results.isEmpty() ? null : RuleService.summarize(results);
    }

    @Transactional(readOnly = true)
    public TableAnalysisView getTableDetails(Long datasetId, Long versionId, Long tableVersionId) {
        requireVersion(datasetId, versionId);
        TableVersion tableVersion = tableVersionRepository.findById(tableVersionId)
            .filter(item -> item.getDatasetVersion().getId().equals(versionId))
            .orElseThrow(() -> new NotFoundException("Table not found in this version"));
        return toTableView(tableVersion, true);
    }

    @Transactional
    public void deleteDataset(Long datasetId) {
        Dataset dataset = datasetRepository.findById(datasetId)
            .orElseThrow(() -> new NotFoundException("Dataset not found"));
        boolean running = dataset.getVersions().stream().anyMatch(version -> isActive(version.getStatus()));
        if (running) {
            throw new ConflictException("Wait for the running analysis to finish before deleting this dataset");
        }

        tableVersionRepository.findByDatasetVersionDatasetId(datasetId)
            .forEach(tableVersion -> tableVersion.setPreviousTableVersion(null));
        tableVersionRepository.flush();

        datasetRepository.delete(dataset);
        datasetRepository.flush();

        deleteDatasetArtifacts(datasetId);
    }

    // ---------------------------------------------------------------- upload planning

    private UploadPlan planUpload(String datasetName, PreparedUpload prepared, boolean rerunExisting, UploadContext context) {
        Optional<Dataset> existingDataset = datasetRepository.findByName(datasetName);
        if (existingDataset.isPresent()) {
            Dataset dataset = existingDataset.get();
            Optional<DatasetVersion> matchingVersion = findMatchingVersion(dataset, prepared.files());
            if (matchingVersion.isPresent()) {
                DatasetVersion version = matchingVersion.get();
                String duplicateMessage = "Dataset '" + dataset.getName() + "' already has the same uploaded files in version "
                    + version.getVersionNumber() + ".";
                if (!rerunExisting) {
                    return UploadPlan.duplicate(new UploadResponse(
                        dataset.getId(),
                        version.getId(),
                        version.getVersionNumber(),
                        latestRunId(version),
                        version.getStatus().name(),
                        version.getFilePath(),
                        prepared.files().size(),
                        false,
                        "Duplicate upload detected. Existing version was not re-run.",
                        true,
                        false,
                        duplicateMessage,
                        prepared.skipped()
                    ));
                }
                if (isActive(version.getStatus())) {
                    throw new ConflictException("Version " + version.getVersionNumber() + " is already running");
                }
                return prepareRerun(dataset, version, context, duplicateMessage);
            }
        }

        Dataset dataset = existingDataset.orElseGet(() -> {
            Dataset created = new Dataset();
            created.setName(datasetName);
            created.setSourceType(prepared.files().size() > 1 ? "CSV_COLLECTION" : "CSV");
            return datasetRepository.save(created);
        });
        dataset.touch();

        int nextVersion = versionRepository.findTopByDatasetIdOrderByVersionNumberDesc(dataset.getId())
            .map(v -> v.getVersionNumber() + 1)
            .orElse(1);

        Path versionDir = versionDirectory(dataset.getId(), nextVersion);
        // A folder for a brand-new version number can only be a leftover (e.g. the database was
        // reset but not the uploads); its files must not be mixed into this version.
        deleteDirectory(versionDir, Path.of(properties.getStorageRoot()));
        try {
            storeFiles(versionDir, prepared.files());
            writeDatasetConfig(versionDir, context);
            writeBusinessContext(versionDir, context.business());

            DatasetVersion version = new DatasetVersion();
            version.setDataset(dataset);
            version.setVersionNumber(nextVersion);
            version.setFilePath(versionDir.toString());
            version.setExpectedTableCount(prepared.files().size());
            version.setDataSignatureJson(jsonService.toJson(Map.of("upload", uploadSignature(prepared.files()))));
            version.setStatus(RunStatus.PENDING);
            version = versionRepository.save(version);

            PipelineRun run = createPipelineRun(version);
            return UploadPlan.trigger(dataset, version, run.getExternalRunId(), prepared.files().size(), false, null);
        } catch (RuntimeException e) {
            deleteDirectory(versionDir, Path.of(properties.getStorageRoot()));
            throw e;
        }
    }

    private UploadPlan prepareRerun(Dataset dataset, DatasetVersion version, UploadContext context, String duplicateMessage) {
        Path versionDir = storedFolder(version) != null ? storedFolder(version) : Path.of(version.getFilePath());
        // Versions stored by a backend running elsewhere (for example in a container) keep that
        // machine's path: point them at the folder this backend can actually read.
        version.setFilePath(versionDir.toString());
        if (context != null) {
            writeDatasetConfig(versionDir, context);
            writeBusinessContext(versionDir, context.business());
        }
        clearVersionResults(version);
        version.setStatus(RunStatus.PENDING);
        dataset.touch();
        PipelineRun run = createPipelineRun(version);
        int fileCount = version.getExpectedTableCount() == null ? 0 : version.getExpectedTableCount();
        return UploadPlan.trigger(dataset, version, run.getExternalRunId(), fileCount, true, duplicateMessage);
    }

    /** Drop the previous run's results so a re-run starts from an empty, consistent state. */
    private void clearVersionResults(DatasetVersion version) {
        List<TableVersion> tables = tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(version.getId());
        if (!tables.isEmpty()) {
            tableVersionRepository.findByPreviousTableVersionIn(tables)
                .forEach(next -> next.setPreviousTableVersion(null));
            version.getTableVersions().removeAll(tables);
            tableVersionRepository.deleteAll(tables);
        }
        if (version.getGlobalAnalysis() != null) {
            globalAnalysisRepository.delete(version.getGlobalAnalysis());
            version.setGlobalAnalysis(null);
        }
        // Rule outcomes belong to the run that is being replaced; they are re-evaluated at its end.
        version.getRuleResults().clear();
        tableVersionRepository.flush();
    }

    private UploadResponse triggerCommittedRun(UploadPlan plan, List<String> skippedFiles) {
        AirflowClient.TriggerResult trigger = airflowClient.triggerPipeline(
            plan.runId(),
            String.valueOf(plan.datasetId()),
            String.valueOf(plan.versionNumber()),
            toContainerPath(Path.of(plan.filePath()))
        );
        String status = RunStatus.PENDING.name();
        if (!trigger.triggered()) {
            markTriggerFailed(plan.runId(), trigger.message());
            status = RunStatus.FAILED.name();
        }
        return new UploadResponse(
            plan.datasetId(),
            plan.versionId(),
            plan.versionNumber(),
            plan.runId(),
            status,
            plan.filePath(),
            plan.fileCount(),
            trigger.triggered(),
            trigger.message(),
            plan.rerun(),
            plan.rerun() && trigger.triggered(),
            plan.duplicateMessage(),
            skippedFiles
        );
    }

    private void markTriggerFailed(String runId, String reason) {
        LiveEvent event = transactionTemplate.execute(status -> {
            PipelineRun run = pipelineRunRepository.findByExternalRunId(runId).orElse(null);
            if (run == null) {
                return null;
            }
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setErrorMessage("Could not start the analysis in Airflow. " + reason);
            DatasetVersion version = run.getDatasetVersion();
            version.setStatus(RunStatus.FAILED);
            return new LiveEvent(
                "PIPELINE_FAILED",
                version.getDataset().getId(),
                version.getId(),
                version.getVersionNumber(),
                runId,
                RunStatus.FAILED.name(),
                null,
                run.getErrorMessage(),
                Instant.now()
            );
        });
        if (event != null) {
            eventStreamService.publish(event);
        }
    }

    private PipelineRun createPipelineRun(DatasetVersion version) {
        String runId = "run-" + UUID.randomUUID();
        PipelineRun run = new PipelineRun();
        run.setExternalRunId(runId);
        run.setDatasetVersion(version);
        run.setStatus(RunStatus.PENDING);
        version.getPipelineRuns().add(run);
        return pipelineRunRepository.save(run);
    }

    // ---------------------------------------------------------------- validation and hashing

    /** Validate every file and compute its hash before anything is written to disk. */
    PreparedUpload prepareUpload(MultipartFile[] files, MultipartFile legacyFile) {
        List<MultipartFile> candidates = new ArrayList<>();
        if (files != null) {
            Arrays.stream(files).filter(Objects::nonNull).forEach(candidates::add);
        }
        if (legacyFile != null) {
            candidates.add(legacyFile);
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one CSV file");
        }

        List<IncomingFile> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (MultipartFile file : candidates) {
            String name = safeFilename(file);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                throw new IllegalArgumentException("Only .csv files are supported: " + name);
            }
            if (name.equalsIgnoreCase(DATASET_CONFIG_FILE)) {
                throw new IllegalArgumentException(DATASET_CONFIG_FILE + " is a reserved file name; rename this table");
            }
            if (!seenNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The same file name was selected twice: " + name);
            }
            if (file.isEmpty()) {
                skipped.add(name + " (empty file)");
                continue;
            }
            accepted.add(new IncomingFile(file, name, file.getSize(), sha256(file)));
        }
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("All selected files are empty: " + String.join(", ", skipped));
        }
        return new PreparedUpload(accepted, skipped);
    }

    private String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return sha256(input);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read uploaded file " + safeFilename(file), e);
        }
    }

    static String sha256(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (DigestInputStream stream = new DigestInputStream(input, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (stream.read(buffer) != -1) {
                // Reading drives the digest; the bytes themselves are not kept.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Optional<DatasetVersion> findMatchingVersion(Dataset dataset, List<IncomingFile> files) {
        Map<String, String> incoming = new TreeMap<>();
        files.forEach(file -> incoming.put(file.name(), file.sha256()));
        return versionRepository.findByDatasetIdOrderByVersionNumberDesc(dataset.getId()).stream()
            .filter(version -> versionMatchesUpload(version, files, incoming))
            .findFirst();
    }

    private boolean versionMatchesUpload(DatasetVersion version, List<IncomingFile> files, Map<String, String> incoming) {
        if (version.getFilePath() == null || !Files.isDirectory(Path.of(version.getFilePath()))) {
            return false;
        }
        Map<String, Object> storedSignature = jsonService.toMap(version.getDataSignatureJson());
        List<Map<String, Object>> storedFiles = recordList(mapValue(storedSignature.get("upload")).get("files"));

        // Cheap pre-check on names and sizes, then compare content hashes.
        Map<String, Long> incomingSizes = new TreeMap<>();
        files.forEach(file -> incomingSizes.put(file.name(), file.size()));
        Map<String, Long> storedSizes = new TreeMap<>();
        storedFiles.forEach(entry -> storedSizes.put(String.valueOf(entry.get("name")), longValue(entry.get("size_bytes"))));
        if (!storedFiles.isEmpty() && !incomingSizes.equals(storedSizes)) {
            return false;
        }

        Map<String, String> storedHashes = new TreeMap<>();
        boolean allHashed = !storedFiles.isEmpty();
        for (Map<String, Object> entry : storedFiles) {
            Object hash = entry.get("sha256");
            if (hash == null) {
                allHashed = false;
                break;
            }
            storedHashes.put(String.valueOf(entry.get("name")), String.valueOf(hash));
        }
        if (allHashed) {
            return incoming.equals(storedHashes);
        }

        // Versions stored before content hashing: hash the files kept on disk instead.
        try {
            List<IncomingFile> recomputed = storedFileHashes(Path.of(version.getFilePath()));
            Map<String, String> recomputedHashes = new TreeMap<>();
            recomputed.forEach(file -> recomputedHashes.put(file.name(), file.sha256()));
            if (!recomputedHashes.isEmpty()) {
                version.setDataSignatureJson(jsonService.toJson(Map.of("upload", uploadSignature(recomputed))));
            }
            return incoming.equals(recomputedHashes);
        } catch (IOException e) {
            log.warn("Could not hash stored files of version {}: {}", version.getId(), e.getMessage());
            return false;
        }
    }

    private List<IncomingFile> storedFileHashes(Path versionDir) throws IOException {
        List<IncomingFile> entries = new ArrayList<>();
        try (var paths = Files.list(versionDir)) {
            for (Path path : paths
                .filter(item -> item.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                .filter(item -> !item.getFileName().toString().equalsIgnoreCase(DATASET_CONFIG_FILE))
                .sorted(Comparator.comparing(item -> item.getFileName().toString()))
                .toList()) {
                try (InputStream input = Files.newInputStream(path)) {
                    entries.add(new IncomingFile(null, path.getFileName().toString(), Files.size(path), sha256(input)));
                }
            }
        }
        return entries;
    }

    private Map<String, Object> uploadSignature(List<IncomingFile> files) {
        List<Map<String, Object>> entries = files.stream()
            .sorted(Comparator.comparing(IncomingFile::name))
            .map(file -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", file.name());
                entry.put("size_bytes", file.size());
                entry.put("sha256", file.sha256());
                return entry;
            })
            .toList();
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("file_count", entries.size());
        signature.put("total_size_bytes", files.stream().mapToLong(IncomingFile::size).sum());
        signature.put("files", entries);
        return signature;
    }

    // ---------------------------------------------------------------- views

    private VersionSummary toVersionSummary(DatasetVersion version) {
        PipelineRun run = latestRun(version);
        return new VersionSummary(
            version.getId(),
            version.getVersionNumber(),
            version.getStatus().name(),
            version.getFilePath(),
            version.getExpectedTableCount(),
            version.getUploadedAt(),
            run == null ? null : run.getExternalRunId(),
            run == null ? null : run.getErrorMessage(),
            run == null ? null : (run.getStartedAt() != null ? run.getStartedAt() : run.getCreatedAt()),
            run == null ? null : run.getFinishedAt()
        );
    }

    private PipelineRun latestRun(DatasetVersion version) {
        return version.getPipelineRuns().stream()
            .max(Comparator.comparing(PipelineRun::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    }

    private String latestRunId(DatasetVersion version) {
        PipelineRun run = latestRun(version);
        return run == null ? null : run.getExternalRunId();
    }

    private List<FailedTableView> failedTables(PipelineRun run) {
        if (run == null || run.getFailedTablesJson() == null) {
            return List.of();
        }
        return recordList(jsonService.toList(run.getFailedTablesJson())).stream()
            .map(item -> new FailedTableView(
                item.get("tableName") == null ? null : String.valueOf(item.get("tableName")),
                item.get("error") == null ? null : String.valueOf(item.get("error"))
            ))
            .toList();
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
                null,
                null,
                jsonService.toMap(tableVersion.getDataSignatureJson()),
                Map.of(),
                Map.of(),
                List.of(),
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
                column.getMaxValue(),
                column.getRole(),
                column.getRoleConfidence(),
                jsonService.toList(column.getRoleEvidenceJson()).stream().map(String::valueOf).toList(),
                column.getRoleSource()
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
            analysis.getLlmStatus(),
            analysis.getLlmModel(),
            jsonService.toMap(analysis.getLlmInterpretationJson()),
            jsonService.toMap(tableVersion.getDataSignatureJson()),
            includeRaw ? RawPayloads.trimmed(readPipelineJson(analysis.getMetricsJsonPath())) : Map.of(),
            includeRaw ? readPipelineJson(analysis.getAnalysisJsonPath()) : Map.of(),
            columns,
            recordList(jsonService.toList(analysis.getRelationshipsJson()))
        );
    }

    private GlobalAnalysisView toGlobalView(GlobalAnalysis global, boolean includeRaw) {
        return new GlobalAnalysisView(
            global.getId(),
            global.getTotalTables(),
            global.getTotalRows(),
            global.getTotalColumns(),
            global.getLlmGlobalSummary(),
            global.getLlmStatus(),
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

    // ---------------------------------------------------------------- storage

    /**
     * The folder holding a version's files on this machine: the recorded path when it exists,
     * otherwise the canonical folder under the storage root, otherwise null.
     */
    private Path storedFolder(DatasetVersion version) {
        if (version.getFilePath() != null && Files.isDirectory(Path.of(version.getFilePath()))) {
            return Path.of(version.getFilePath());
        }
        Path canonical = versionDirectory(version.getDataset().getId(), version.getVersionNumber());
        return Files.isDirectory(canonical) ? canonical : null;
    }

    private Path versionDirectory(Long datasetId, int versionNumber) {
        return Path.of(properties.getStorageRoot(), "datasets", "dataset_" + datasetId, "v" + versionNumber)
            .toAbsolutePath()
            .normalize();
    }

    private void storeFiles(Path dir, List<IncomingFile> files) {
        try {
            Files.createDirectories(dir);
            for (IncomingFile file : files) {
                try (InputStream input = file.file().getInputStream()) {
                    Files.copy(input, dir.resolve(file.name()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not store uploaded files", e);
        }
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
                    log.warn("Could not delete dataset artifact {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not scan dataset artifact folder {}: {}", normalizedTarget, e.getMessage());
        }
    }

    private void writeDatasetConfig(Path versionDir, UploadContext context) {
        try {
            Files.createDirectories(versionDir);
            Path configPath = versionDir.resolve(DATASET_CONFIG_FILE);
            String scope = normalizeContextScope(context.scope());
            boolean includeGlobal = scope.equals("global") || scope.equals("both");
            boolean includeTables = scope.equals("per_table") || scope.equals("both");
            List<String> lines = new ArrayList<>();
            lines.add("dataset_name,description,custom_question");

            if ((includeGlobal && hasText(context.globalDescription())) || hasText(context.globalQuestion())) {
                lines.add(csvCell("__global__") + "," + csvCell(context.globalDescription()) + "," + csvCell(context.globalQuestion()));
            }

            if (includeTables) {
                Map<String, Object> descriptions = jsonService.toMap(context.tableDescriptionsJson());
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

    /**
     * The domain, objective and row grain the user gave, in their own file next to the CSVs so the
     * pipeline can read it. Nothing given means no file (a stale one from a previous run is removed).
     */
    private void writeBusinessContext(Path versionDir, BusinessContext business) {
        try {
            Path file = versionDir.resolve(BUSINESS_CONTEXT_FILE);
            if (business == null || business.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("domain", clip(business.domain()));
            content.put("objective", clip(business.objective()));
            content.put("row_grain", clip(business.rowGrain()));
            Files.writeString(file, jsonService.toJson(content), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the business context", e);
        }
    }

    private String clip(String value) {
        String text = cleanText(value);
        return text.length() > MAX_CONTEXT_CHARS ? text.substring(0, MAX_CONTEXT_CHARS) : text;
    }

    // ---------------------------------------------------------------- helpers

    private DatasetVersion requireVersion(Long datasetId, Long versionId) {
        return versionRepository.findById(versionId)
            .filter(v -> v.getDataset().getId().equals(datasetId))
            .orElseThrow(() -> new NotFoundException("Dataset version not found"));
    }

    static boolean isActive(RunStatus status) {
        return status == RunStatus.PENDING || status == RunStatus.RUNNING;
    }

    private String normalizeContextScope(String contextScope) {
        String normalized = cleanText(contextScope).toLowerCase(Locale.ROOT);
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

    private String safeFilename(MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "table.csv" : file.getOriginalFilename();
        String name = Path.of(original.replace('\\', '/')).getFileName().toString().trim();
        if (name.isEmpty() || name.startsWith(".")) {
            throw new IllegalArgumentException("Invalid file name: " + original);
        }
        return name;
    }

    private String toContainerPath(Path hostPath) {
        return PipelinePaths.toContainerPath(hostPath);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recordList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(item -> (Map<String, Object>) item)
            .toList();
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    record IncomingFile(MultipartFile file, String name, long size, String sha256) {}

    record PreparedUpload(List<IncomingFile> files, List<String> skipped) {}

    /** What the user wants from the data: its domain, the goal of the analysis and what one row stands for. */
    public record BusinessContext(String domain, String objective, String rowGrain) {
        public static final BusinessContext EMPTY = new BusinessContext(null, null, null);

        boolean isEmpty() {
            return blank(domain) && blank(objective) && blank(rowGrain);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    private record UploadContext(
        String scope,
        String globalDescription,
        String globalQuestion,
        String tableDescriptionsJson,
        BusinessContext business
    ) {}

    private record UploadPlan(
        UploadResponse duplicateResponse,
        Long datasetId,
        Long versionId,
        Integer versionNumber,
        String filePath,
        String runId,
        int fileCount,
        boolean rerun,
        String duplicateMessage
    ) {
        static UploadPlan duplicate(UploadResponse response) {
            return new UploadPlan(response, null, null, null, null, null, 0, false, null);
        }

        static UploadPlan trigger(Dataset dataset, DatasetVersion version, String runId, int fileCount, boolean rerun, String duplicateMessage) {
            return new UploadPlan(
                null,
                dataset.getId(),
                version.getId(),
                version.getVersionNumber(),
                version.getFilePath(),
                runId,
                fileCount,
                rerun,
                duplicateMessage
            );
        }
    }
}
