package wevioo.backend.dto;

import wevioo.backend.correction.CorrectionEngine.Example;
import wevioo.backend.correction.Operations.OpSpec;
import wevioo.backend.correction.TableScanner.Suggestion;

import java.util.List;

public class CorrectionDtos {
    /** What the user wants corrected, in their own words. */
    public record InterpretRequest(String table, String instruction) {}

    /** The corrections to preview or apply, for one table. */
    public record PlanRequest(String table, List<OpSpec> operations) {}

    /** An operation with its description in words. */
    public record OperationView(OpSpec operation, String description) {}

    public record Suggestions(String table, long rows, List<String> columns, List<Suggestion> suggestions) {}

    /**
     * @param assumptions what the model assumed while reading the request (shown so the user can object)
     * @param rejected    parts of the model's answer that were not allowed or did not fit the table
     */
    public record Interpretation(List<OperationView> operations, String assumptions, List<String> rejected) {}

    /**
     * What one correction does to the table.
     *
     * @param affected      cells changed (or rows removed, for the operations that remove rows)
     * @param review        dates left alone because day and month could be swapped
     * @param notRecognised dates the tool could not read, left alone
     */
    public record OperationPreview(OpSpec operation, String description, long affected, long review, long notRecognised, List<Example> examples) {}

    public record Preview(
        String table,
        long rowsBefore,
        long rowsAfter,
        long cellsChanged,
        List<OperationPreview> operations,
        List<String> warnings
    ) {}
}
