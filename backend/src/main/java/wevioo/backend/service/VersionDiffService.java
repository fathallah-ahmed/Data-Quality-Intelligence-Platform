package wevioo.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import wevioo.backend.domain.ChangeStatus;
import wevioo.backend.domain.ColumnMetric;
import wevioo.backend.domain.DatasetVersion;
import wevioo.backend.domain.TableAnalysis;
import wevioo.backend.domain.TableVersion;
import wevioo.backend.dto.DiffDtos.*;
import wevioo.backend.exception.NotFoundException;
import wevioo.backend.repository.DatasetVersionRepository;
import wevioo.backend.repository.TableVersionRepository;

import java.util.*;
import java.util.stream.Collectors;

/** Compares two versions of a dataset: row/column/score deltas and schema drift per table. */
@Service
public class VersionDiffService {
    /** Shifts smaller than this many percent points are noise and are not listed. */
    static final double MIN_MISSING_SHIFT = 0.05;

    private final DatasetVersionRepository versionRepository;
    private final TableVersionRepository tableVersionRepository;
    private final JsonService jsonService;

    public VersionDiffService(
        DatasetVersionRepository versionRepository,
        TableVersionRepository tableVersionRepository,
        JsonService jsonService
    ) {
        this.versionRepository = versionRepository;
        this.tableVersionRepository = tableVersionRepository;
        this.jsonService = jsonService;
    }

    /**
     * @param againstVersionId the older version to compare with; the previous version when null
     */
    @Transactional(readOnly = true)
    public VersionDiff diff(Long datasetId, Long versionId, Long againstVersionId) {
        DatasetVersion to = requireVersion(datasetId, versionId);
        DatasetVersion from = againstVersionId != null
            ? requireVersion(datasetId, againstVersionId)
            : versionRepository
                .findTopByDatasetIdAndVersionNumberLessThanOrderByVersionNumberDesc(datasetId, to.getVersionNumber())
                .orElseThrow(() -> new NotFoundException("This is the first version: there is nothing to compare it with"));

        Map<String, TableVersion> fromTables = present(from);
        Map<String, TableVersion> toTables = present(to);
        TreeSet<String> names = new TreeSet<>(fromTables.keySet());
        names.addAll(toTables.keySet());

        List<TableDiff> tables = names.stream()
            .map(name -> tableDiff(name, fromTables.get(name), toTables.get(name)))
            .toList();

        Double scoreFrom = weightedScore(fromTables.values());
        Double scoreTo = weightedScore(toTables.values());
        return new VersionDiff(
            ref(from),
            ref(to),
            scoreFrom,
            scoreTo,
            scoreFrom == null || scoreTo == null ? null : scoreTo - scoreFrom,
            totalRows(fromTables.values()),
            totalRows(toTables.values()),
            count(tables, "ADDED"),
            count(tables, "REMOVED"),
            count(tables, "CHANGED"),
            count(tables, "UNCHANGED"),
            tables
        );
    }

    private TableDiff tableDiff(String name, TableVersion before, TableVersion after) {
        TableAnalysis a = before == null ? null : before.getAnalysis();
        TableAnalysis b = after == null ? null : after.getAnalysis();
        Map<String, ColumnMetric> columnsBefore = columns(a);
        Map<String, ColumnMetric> columnsAfter = columns(b);

        List<String> added = columnsAfter.keySet().stream().filter(c -> !columnsBefore.containsKey(c)).sorted().toList();
        List<String> removed = columnsBefore.keySet().stream().filter(c -> !columnsAfter.containsKey(c)).sorted().toList();
        List<TypeChange> typeChanges = new ArrayList<>();
        List<ColumnShift> shifts = new ArrayList<>();
        for (Map.Entry<String, ColumnMetric> entry : columnsAfter.entrySet()) {
            ColumnMetric previous = columnsBefore.get(entry.getKey());
            if (previous == null) {
                continue;
            }
            ColumnMetric current = entry.getValue();
            if (!Objects.equals(previous.getDataType(), current.getDataType())) {
                typeChanges.add(new TypeChange(entry.getKey(), previous.getDataType(), current.getDataType()));
            }
            double was = previous.getMissingPercentage() == null ? 0 : previous.getMissingPercentage();
            double now = current.getMissingPercentage() == null ? 0 : current.getMissingPercentage();
            if (Math.abs(now - was) >= MIN_MISSING_SHIFT) {
                shifts.add(new ColumnShift(entry.getKey(), was, now, now - was));
            }
        }
        shifts.sort(Comparator.comparingDouble((ColumnShift shift) -> Math.abs(shift.delta())).reversed());

        boolean contentChanged = before != null && after != null
            && !DataSignatures.sameContent(jsonService.toMap(after.getDataSignatureJson()), jsonService.toMap(before.getDataSignatureJson()));
        boolean schemaChanged = !added.isEmpty() || !removed.isEmpty() || !typeChanges.isEmpty();
        boolean metricsChanged = a != null && b != null
            && (!Objects.equals(a.getRowCount(), b.getRowCount())
                || !Objects.equals(a.getMissingCount(), b.getMissingCount())
                || !Objects.equals(a.getDuplicateCount(), b.getDuplicateCount()));

        String change = before == null ? "ADDED"
            : after == null ? "REMOVED"
            : (contentChanged || schemaChanged || metricsChanged) ? "CHANGED"
            : "UNCHANGED";

        return new TableDiff(
            name,
            change,
            a == null ? null : a.getRowCount(),
            b == null ? null : b.getRowCount(),
            a == null ? null : a.getColumnCount(),
            b == null ? null : b.getColumnCount(),
            a == null ? null : a.getQualityScore(),
            b == null ? null : b.getQualityScore(),
            a == null ? null : a.getMissingCount(),
            b == null ? null : b.getMissingCount(),
            a == null ? null : a.getDuplicateCount(),
            b == null ? null : b.getDuplicateCount(),
            contentChanged,
            added,
            removed,
            typeChanges,
            shifts.stream().limit(8).toList()
        );
    }

    /** Tables that really existed in the version (REMOVED markers are not tables). */
    private Map<String, TableVersion> present(DatasetVersion version) {
        return tableVersionRepository.findByDatasetVersionIdOrderByTableNameAsc(version.getId()).stream()
            .filter(table -> table.getChangeStatus() != ChangeStatus.REMOVED)
            .collect(Collectors.toMap(TableVersion::getTableName, table -> table, (first, second) -> first, LinkedHashMap::new));
    }

    private Map<String, ColumnMetric> columns(TableAnalysis analysis) {
        if (analysis == null) {
            return Map.of();
        }
        Map<String, ColumnMetric> columns = new LinkedHashMap<>();
        analysis.getColumnMetrics().forEach(column -> columns.put(column.getColumnName(), column));
        return columns;
    }

    /** Row-weighted average of the table scores, like {@link QualityScores}. */
    static Double weightedScore(Collection<TableVersion> tables) {
        double weighted = 0;
        double weight = 0;
        double plain = 0;
        int scored = 0;
        for (TableVersion table : tables) {
            TableAnalysis analysis = table.getAnalysis();
            if (analysis == null || analysis.getQualityScore() == null) {
                continue;
            }
            long rows = analysis.getRowCount() == null ? 0 : Math.max(0, analysis.getRowCount());
            weighted += analysis.getQualityScore() * rows;
            weight += rows;
            plain += analysis.getQualityScore();
            scored++;
        }
        if (scored == 0) {
            return null;
        }
        return weight > 0 ? weighted / weight : plain / scored;
    }

    private Long totalRows(Collection<TableVersion> tables) {
        return tables.stream()
            .map(TableVersion::getAnalysis)
            .filter(Objects::nonNull)
            .map(TableAnalysis::getRowCount)
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .sum();
    }

    private int count(List<TableDiff> tables, String change) {
        return (int) tables.stream().filter(table -> table.change().equals(change)).count();
    }

    private VersionRef ref(DatasetVersion version) {
        return new VersionRef(version.getId(), version.getVersionNumber(), version.getStatus().name(), version.getUploadedAt());
    }

    private DatasetVersion requireVersion(Long datasetId, Long versionId) {
        return versionRepository.findById(versionId)
            .filter(version -> version.getDataset().getId().equals(datasetId))
            .orElseThrow(() -> new NotFoundException("Dataset version not found"));
    }
}
