package wevioo.backend.correction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

/** What a correction must never do: change anything it was not asked to change, or lose data. */
class CorrectionSafetyTest {
    private final CorrectionEngine engine = new CorrectionEngine();

    @TempDir
    Path dir;

    private Path file(String name, byte[] content) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, content);
        return path;
    }

    private static OpSpec op(String type, String column, Map<String, Object> params, List<String> columns) {
        return Operations.validate(new OpSpec(type, column, params), columns);
    }

    @Test
    void theHeaderIsWrittenBackExactlyAsItWasRead() throws IOException {
        Path source = file("t.csv", "Student_ID , First_Name \n1, Ann \n".getBytes(StandardCharsets.UTF_8));
        Path out = dir.resolve("out.csv");

        engine.run(source, List.of(op("TRIM", "First_Name", Map.of(), List.of("Student_ID", "First_Name"))), out);

        // Column names are matched without their spaces, but the file keeps them: renaming a column is not a correction.
        assertThat(Files.readString(out)).isEqualTo("Student_ID , First_Name \n1,Ann\n");
    }

    @Test
    void anAccentLateInALargeLatin1FileIsNotDamaged() throws IOException {
        StringBuilder text = new StringBuilder("id,name\n");
        for (int i = 0; i < 40_000; i++) {
            text.append(i).append(",plain\n");          // more than the sample read to guess the encoding
        }
        text.append("99999,Élodie\n");
        Path source = file("t.csv", text.toString().getBytes(Charset.forName("windows-1252")));
        Path out = dir.resolve("out.csv");

        engine.run(source, List.of(op("TRIM", "name", Map.of(), List.of("id", "name"))), out);

        assertThat(Csv.detect(source).charset().name()).isEqualToIgnoringCase("windows-1252");
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).endsWith("99999,Élodie\n");
    }

    @Test
    void rowsWithDataBeyondTheHeaderAreRefusedInsteadOfCut() throws IOException {
        Path source = file("t.csv", "id,name\n1,Ann,extra data\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> engine.run(source, List.of(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Row 1 has 3 cells but the header has 2");
    }

    @Test
    void emptyCellsBeyondTheHeaderAreDroppedAndShortRowsArePadded() throws IOException {
        Path source = file("t.csv", "id,name\n1,Ann,,\n2\n".getBytes(StandardCharsets.UTF_8));
        Path out = dir.resolve("out.csv");

        CorrectionEngine.RunResult result = engine.run(source, List.of(), out);

        assertThat(result.adjustedRows()).isEqualTo(2);
        assertThat(Files.readString(out)).isEqualTo("id,name\n1,Ann\n2,\n");
    }

    @Test
    void aDateWithATimeZoneOrFractionalSecondsIsLeftAloneBecauseISODateTimeWouldLoseIt() {
        assertThat(DateNormalizer.normalize("2024-01-05T10:00:00Z", "AUTO").outcome()).isEqualTo(DateNormalizer.Outcome.UNRECOGNISED);
        assertThat(DateNormalizer.normalize("2024-01-05 10:00:00.123", "AUTO").outcome()).isEqualTo(DateNormalizer.Outcome.UNRECOGNISED);
        assertThat(DateNormalizer.normalize("2024-01-05T10:00:00+02:00", "AUTO").outcome()).isEqualTo(DateNormalizer.Outcome.UNRECOGNISED);
        assertThat(DateNormalizer.normalize("05/01/2024 10:30", "DMY").value()).isEqualTo("2024-01-05 10:30:00");
    }

    @Test
    void everyCellNotNamedByAnOperationComesOutIdentical() throws IOException {
        String original = "id,name,note\n001,Ann,\"has, a comma\"\n002, Bo ,\"line one\nline two\"\n003,,\"say \"\"hi\"\"\"\n";
        Path source = file("t.csv", original.getBytes(StandardCharsets.UTF_8));
        Path out = dir.resolve("out.csv");

        engine.run(source, List.of(op("TRIM", "name", Map.of(), List.of("id", "name", "note"))), out);

        // Only " Bo " changed: the leading zeros, the quotes, the comma and the line break are all kept.
        assertThat(Files.readString(out)).isEqualTo(original.replace(" Bo ", "Bo"));
    }
}
