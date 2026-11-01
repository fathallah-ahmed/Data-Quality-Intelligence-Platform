package wevioo.backend.correction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wevioo.backend.correction.CorrectionEngine.OpResult;
import wevioo.backend.correction.CorrectionEngine.RunResult;
import wevioo.backend.correction.Operations.OpSpec;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrectionEngineTest {
    private final CorrectionEngine engine = new CorrectionEngine();

    @TempDir
    Path dir;

    private Path csv(String name, String content, Charset charset) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(charset));
        return file;
    }

    private static final List<String> COLUMNS = List.of("id", "name", "country", "joined", "zip", "phone");

    private static OpSpec op(String type, String column, Map<String, Object> params) {
        return Operations.validate(new OpSpec(type, column, params), COLUMNS);
    }

    private RunResult run(Path file, OpSpec... ops) throws IOException {
        return engine.run(file, List.of(ops), null);
    }

    // ------------------------------------------------------------------ reading files

    @Test
    void readsQuotedCellsWithSeparatorsQuotesAndLineBreaks() throws IOException {
        Path file = csv("t.csv", "id,name\n1,\"Doe, John\"\n2,\"say \"\"hi\"\"\"\n3,\"two\nlines\"\n", StandardCharsets.UTF_8);

        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(file, Csv.detect(file)), ',')) {
            assertThat(reader.next()).containsExactly("id", "name");
            assertThat(reader.next()).containsExactly("1", "Doe, John");
            assertThat(reader.next()).containsExactly("2", "say \"hi\"");
            assertThat(reader.next()).containsExactly("3", "two\nlines");
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void detectsSemicolonsAndLatin1AndSkipsTheBom() throws IOException {
        Path file = csv("t.csv", "id;nom\r\n1;Élodie\r\n", Charset.forName("windows-1252"));
        Csv.Dialect dialect = Csv.detect(file);
        assertThat(dialect.delimiter()).isEqualTo(';');
        assertThat(dialect.charset().name()).isEqualToIgnoringCase("windows-1252");
        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(file, dialect), dialect.delimiter())) {
            reader.next();
            assertThat(reader.next()).containsExactly("1", "Élodie");
        }

        Path bom = csv("bom.csv", "﻿id,name\n1,Ann\n", StandardCharsets.UTF_8);
        try (Csv.RecordReader reader = new Csv.RecordReader(Csv.open(bom, Csv.detect(bom)), ',')) {
            assertThat(reader.next()).containsExactly("id", "name");
        }
    }

    @Test
    void writesCorrectedFilesAsUtf8WithCommasAndQuotesWhereNeeded() throws IOException {
        Path source = csv("t.csv", "id;name\n1; Ann \n2;Doe, John\n", StandardCharsets.UTF_8);
        Path out = dir.resolve("out.csv");

        engine.run(source, List.of(op("TRIM", "name", Map.of())), out);

        assertThat(Files.readString(out)).isEqualTo("id,name\n1,Ann\n2,\"Doe, John\"\n");
    }

    // ------------------------------------------------------------------ operations

    @Test
    void trimsAndCollapsesSpacesIncludingNoBreakSpaces() throws IOException {
        Path file = csv("t.csv", "id,name,country,joined,zip,phone\n1,  Ann ,FR,,,\n2,Bo   Li,FR,,,\n3,Cy,FR,,,\n", StandardCharsets.UTF_8);

        RunResult result = run(file, op("TRIM", "name", Map.of()), op("COLLAPSE_SPACES", "name", Map.of()));

        assertThat(result.operations().get(0).affected).isEqualTo(1);
        assertThat(result.operations().get(1).affected).isEqualTo(1);
        assertThat(result.operations().get(0).examples.get(0).after()).isEqualTo("Ann");
        assertThat(result.cellsChanged()).isEqualTo(2);
    }

    @Test
    void changesCaseReplacesValuesAndEmptiesPlaceholders() throws IOException {
        Path file = csv("t.csv", "id,name,country,joined,zip,phone\n1,ann smith,usa,,,\n2,BO LI-WANG,U.S.A.,,,\n3,N/A,France,,,\n", StandardCharsets.UTF_8);

        RunResult result = run(file,
            op("CASE", "name", Map.of("mode", "title")),
            op("EMPTY_TO_NULL", "name", Map.of()),
            op("REPLACE_VALUES", "country", Map.of("ignoreCase", true, "mappings", List.of(
                Map.of("from", "USA", "to", "United States"), Map.of("from", "U.S.A.", "to", "United States")))));

        assertThat(result.operations().get(0).examples.get(0).after()).isEqualTo("Ann Smith");
        assertThat(result.operations().get(0).examples.get(1).after()).isEqualTo("Bo Li-Wang");
        assertThat(result.operations().get(1).affected).isEqualTo(1);   // "N/A", after the case change it is still a placeholder
        assertThat(result.operations().get(2).affected).isEqualTo(2);
    }

    @Test
    void padsPostalCodesKeepsDigitsAndFillsEmptyCells() throws IOException {
        Path file = csv("t.csv", "id,name,country,joined,zip,phone\n1,a,,,1234,+33 (0)6.12.34\n2,b,FR,,75001,\n", StandardCharsets.UTF_8);

        RunResult result = run(file,
            op("PAD_LEFT", "zip", Map.of("width", 5)),
            op("DIGITS_ONLY", "phone", Map.of()),
            op("FILL_EMPTY", "country", Map.of("value", "Unknown")));

        assertThat(result.operations().get(0).examples.get(0).after()).isEqualTo("01234");
        assertThat(result.operations().get(1).examples.get(0).after()).isEqualTo("+330612" + "34");
        assertThat(result.operations().get(2).affected).isEqualTo(1);
    }

    @Test
    void removesDuplicateRowsAndRowsWithAnEmptyKey() throws IOException {
        Path file = csv("t.csv", "id,name\n1,Ann\n2,Bo\n1,Ann\n,Cy\n2,Bo\n", StandardCharsets.UTF_8);
        Path out = dir.resolve("out.csv");

        RunResult result = engine.run(file, List.of(
            Operations.validate(new OpSpec("DROP_DUPLICATE_ROWS", null, Map.of()), List.of("id", "name")),
            Operations.validate(new OpSpec("DROP_EMPTY_ROWS", "id", Map.of()), List.of("id", "name"))), out);

        assertThat(result.rowsBefore()).isEqualTo(5);
        assertThat(result.rowsAfter()).isEqualTo(2);
        assertThat(result.operations().get(0).affected).isEqualTo(2);
        assertThat(result.operations().get(1).affected).isEqualTo(1);
        assertThat(Files.readString(out)).isEqualTo("id,name\n1,Ann\n2,Bo\n");
        assertThat(result.cellsChanged()).isZero();   // removing rows is not a change to a cell
    }

    @Test
    void anAmbiguousDateIsLeftForReviewUnlessTheOrderIsGiven() throws IOException {
        Path file = csv("t.csv", "id,name,country,joined,zip,phone\n1,a,,25/02/2024,,\n2,b,,01/02/2024,,\n3,c,,2024-03-04,,\n4,d,,12 Mar 2023,,\n5,e,,not a date,,\n", StandardCharsets.UTF_8);

        OpResult auto = run(file, op("NORMALIZE_DATE", "joined", Map.of())).operations().get(0);
        assertThat(auto.affected).isEqualTo(2);        // 25/02/2024 and 12 Mar 2023
        assertThat(auto.review).isEqualTo(1);          // 01/02/2024 could be either
        assertThat(auto.notRecognised).isEqualTo(1);

        OpResult dayFirst = run(file, op("NORMALIZE_DATE", "joined", Map.of("order", "DMY"))).operations().get(0);
        assertThat(dayFirst.review).isZero();
        assertThat(dayFirst.examples.stream().map(CorrectionEngine.Example::after)).contains("2024-02-01");

        OpResult monthFirst = run(file, op("NORMALIZE_DATE", "joined", Map.of("order", "MDY"))).operations().get(0);
        assertThat(monthFirst.examples.stream().map(CorrectionEngine.Example::after)).contains("2024-01-02");
    }

    @Test
    void operationsSeeTheOutputOfTheOnesBeforeThem() throws IOException {
        Path file = csv("t.csv", "id,name,country\n1, france ,x\n", StandardCharsets.UTF_8);

        RunResult result = engine.run(file, List.of(
            Operations.validate(new OpSpec("TRIM", "country", Map.of()), List.of("id", "name", "country")),
            Operations.validate(new OpSpec("TRIM", "name", Map.of()), List.of("id", "name", "country")),
            Operations.validate(new OpSpec("CASE", "name", Map.of("mode", "UPPER")), List.of("id", "name", "country"))), null);

        assertThat(result.operations().get(2).examples.get(0).before()).isEqualTo("france");
        assertThat(result.operations().get(2).examples.get(0).after()).isEqualTo("FRANCE");
    }

    @Test
    void shortAndLongRowsAreAdjustedAndCounted() throws IOException {
        Path file = csv("t.csv", "id,name\n1\n2,Bo,extra\n3,Cy\n", StandardCharsets.UTF_8);

        RunResult result = engine.run(file, List.of(), null);

        assertThat(result.rowsBefore()).isEqualTo(3);
        assertThat(result.adjustedRows()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ what is allowed

    @Test
    void onlyKnownOperationsOnRealColumnsWithSaneSettingsAreAccepted() {
        assertThatThrownBy(() -> op("DELETE_EVERYTHING", "name", Map.of())).hasMessageContaining("Unknown correction");
        assertThatThrownBy(() -> op("TRIM", "ghost", Map.of())).hasMessageContaining("no column named ghost");
        assertThatThrownBy(() -> op("TRIM", null, Map.of())).hasMessageContaining("needs a column");
        assertThatThrownBy(() -> op("CASE", "name", Map.of("mode", "shout"))).hasMessageContaining("UPPER, LOWER or TITLE");
        assertThatThrownBy(() -> op("REPLACE_VALUES", "name", Map.of())).hasMessageContaining("at least one");
        assertThatThrownBy(() -> op("PAD_LEFT", "zip", Map.of("width", 500))).hasMessageContaining("width");
        assertThatThrownBy(() -> op("NORMALIZE_DATE", "joined", Map.of("order", "XYZ"))).hasMessageContaining("order");
        assertThat(op("TRIM", "NAME", Map.of()).column()).isEqualTo("name");   // the real spelling is kept
    }

    @Test
    void operationsAreDescribedInWords() {
        assertThat(Operations.describe(op("TRIM", "name", Map.of()))).isEqualTo("Remove spaces around the values of name");
        assertThat(Operations.describe(op("PAD_LEFT", "zip", Map.of("width", 5)))).isEqualTo("Pad zip to 5 characters with '0' on the left");
        assertThat(Operations.describe(op("NORMALIZE_DATE", "joined", Map.of()))).contains("ambiguous ones are left for review");
    }
}
