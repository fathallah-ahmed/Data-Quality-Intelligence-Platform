package wevioo.backend.service;

import org.springframework.stereotype.Service;
import wevioo.backend.correction.CorrectionEngine;
import wevioo.backend.correction.CorrectionEngine.OpResult;
import wevioo.backend.correction.CorrectionEngine.RunResult;
import wevioo.backend.correction.CorrectionPlanner;
import wevioo.backend.correction.Operations;
import wevioo.backend.correction.Operations.OpSpec;
import wevioo.backend.correction.TableScanner;
import wevioo.backend.correction.TableScanner.Scan;
import wevioo.backend.dto.CorrectionDtos.*;
import wevioo.backend.dto.UploadResponse;
import wevioo.backend.exception.NotFoundException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Corrections of a table: what could be corrected, what a written request means, what a list of corrections
 * would change, and the application of that list as a new version. The version being corrected is only read.
 */
@Service
public class CorrectionService {
    static final String LOG_FILE = "corrections_log.json";
    private static final String DATASET_CONFIG_FILE = "dataset_config.csv";

    private final DatasetService datasetService;
    private final TableScanner scanner;
    private final CorrectionEngine engine;
    private final CorrectionPlanner planner;
    private final JsonService jsonService;

    public CorrectionService(
        DatasetService datasetService,
        TableScanner scanner,
        CorrectionEngine engine,
        CorrectionPlanner planner,
        JsonService jsonService
    ) {
        this.datasetService = datasetService;
        this.scanner = scanner;
        this.engine = engine;
        this.planner = planner;
        this.jsonService = jsonService;
    }

    public Suggestions suggestions(Long datasetId, Long versionId, String table) {
        Scan scan = scan(datasetId, versionId, table);
        return new Suggestions(table, scan.rows(), scan.columns(), scan.suggestions());
    }

    public Interpretation interpret(Long datasetId, Long versionId, InterpretRequest request) {
        Scan scan = scan(datasetId, versionId, request.table());
        CorrectionPlanner.Plan plan = planner.plan(request.table(), scan, request.instruction());
        return new Interpretation(
            plan.operations().stream().map(op -> new OperationView(op, Operations.describe(op))).toList(),
            plan.assumptions(),
            plan.rejected()
        );
    }

    public Preview preview(Long datasetId, Long versionId, PlanRequest request) {
        Path file = tableFile(datasetService.versionFolder(datasetId, versionId), request.table());
        List<OpSpec> operations = validate(file, request.operations());
        RunResult result = run(file, operations, null);
        return toPreview(request.table(), result);
    }

    /** Applies the corrections to a copy: the result is a new version, analysed like an upload. */
    public UploadResponse apply(Long datasetId, Long versionId, PlanRequest request) {
        Path currentFile = tableFile(datasetService.versionFolder(datasetId, versionId), request.table());
        List<OpSpec> operations = validate(currentFile, request.operations());
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("Add at least one correction");
        }
        return datasetService.createDerivedVersion(datasetId, versionId, (source, target, sourceNumber) -> {
            Path chosen = tableFile(source, request.table());
            RunResult result;
            try (Stream<Path> files = Files.list(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path destination = target.resolve(file.getFileName().toString());
                    if (!file.equals(chosen)) {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            result = engine.run(chosen, operations, target.resolve(chosen.getFileName().toString()));
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("derivedFromVersionNumber", sourceNumber);
            log.put("table", request.table());
            log.put("appliedAt", Instant.now().toString());
            log.put("rowsBefore", result.rowsBefore());
            log.put("rowsAfter", result.rowsAfter());
            log.put("cellsChanged", result.cellsChanged());
            log.put("operations", toPreview(request.table(), result).operations());
            Files.writeString(target.resolve(LOG_FILE), jsonService.toJson(log));
        });
    }

    /** What was corrected to make this version; empty for a version that came from an upload. */
    public Map<String, Object> log(Long datasetId, Long versionId) {
        Path file = datasetService.versionFolder(datasetId, versionId).resolve(LOG_FILE);
        return Files.isRegularFile(file) ? jsonService.readMap(file) : Map.of();
    }

    // ------------------------------------------------------------------ helpers

    private Scan scan(Long datasetId, Long versionId, String table) {
        Path file = tableFile(datasetService.versionFolder(datasetId, versionId), table);
        try {
            return scanner.scan(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RunResult run(Path file, List<OpSpec> operations, Path output) {
        try {
            return engine.run(file, operations, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Check every operation against the real columns of the table; the first problem stops the request. */
    private List<OpSpec> validate(Path file, List<OpSpec> requested) {
        if (requested == null) {
            return List.of();
        }
        List<String> columns = scan(file).columns();
        List<OpSpec> checked = new ArrayList<>();
        for (OpSpec op : requested) {
            checked.add(Operations.validate(op, columns));
        }
        return checked;
    }

    private Scan scan(Path file) {
        try {
            return scanner.scan(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The CSV of a table in a version folder. The name must match a file there; it is never used as a path. */
    private static Path tableFile(Path folder, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Choose the table to correct");
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files
                .filter(Files::isRegularFile)
                .filter(file -> {
                    String name = file.getFileName().toString();
                    return name.toLowerCase(Locale.ROOT).endsWith(".csv")
                        && !name.equalsIgnoreCase(DATASET_CONFIG_FILE)
                        && name.substring(0, name.length() - 4).equalsIgnoreCase(table.strip());
                })
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Table not found in this version: " + table));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Preview toPreview(String table, RunResult result) {
        List<OperationPreview> operations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (OpResult op : result.operations()) {
            operations.add(new OperationPreview(op.op, op.description, op.affected, op.review, op.notRecognised, op.examples));
            if (op.affected == 0 && op.review == 0) {
                warnings.add("\"" + op.description + "\" changes nothing in this table.");
            }
            if (op.review > 0) {
                warnings.add(op.review + " date(s) in " + op.op.column() + " can be read as day/month or month/day. They are left as they are; "
                    + "say how the dates are written to convert them.");
            }
            if (op.notRecognised > 0) {
                warnings.add(op.notRecognised + " value(s) in " + op.op.column() + " are not dates this tool reads; they are left as they are.");
            }
            if (op.op.type().startsWith("DROP_") && op.affected > 0) {
                warnings.add(op.affected + " row(s) will be removed from the corrected version (the original keeps them).");
            }
        }
        if (result.adjustedRows() > 0) {
            warnings.add(result.adjustedRows() + " row(s) have more or fewer cells than the header; they were padded or cut to fit.");
        }
        return new Preview(table, result.rowsBefore(), result.rowsAfter(), result.cellsChanged(), operations, warnings);
    }
}
