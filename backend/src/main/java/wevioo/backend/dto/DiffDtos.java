package wevioo.backend.dto;

import java.time.Instant;
import java.util.List;

/** Result of comparing two versions of the same dataset. */
public class DiffDtos {
    public record VersionRef(
        Long id,
        Integer versionNumber,
        String status,
        Instant uploadedAt
    ) {}

    public record TypeChange(String column, String from, String to) {}

    /** Change of one column's share of missing values (percent points). */
    public record ColumnShift(String column, Double from, Double to, Double delta) {}

    /**
     * @param change ADDED, REMOVED, CHANGED or UNCHANGED
     */
    public record TableDiff(
        String tableName,
        String change,
        Long rowsFrom,
        Long rowsTo,
        Integer columnsFrom,
        Integer columnsTo,
        Double scoreFrom,
        Double scoreTo,
        Long missingFrom,
        Long missingTo,
        Long duplicatesFrom,
        Long duplicatesTo,
        boolean contentChanged,
        List<String> columnsAdded,
        List<String> columnsRemoved,
        List<TypeChange> typeChanges,
        List<ColumnShift> missingShifts
    ) {}

    public record VersionDiff(
        VersionRef from,
        VersionRef to,
        Double scoreFrom,
        Double scoreTo,
        Double scoreDelta,
        Long rowsFrom,
        Long rowsTo,
        int tablesAdded,
        int tablesRemoved,
        int tablesChanged,
        int tablesUnchanged,
        List<TableDiff> tables
    ) {}
}
