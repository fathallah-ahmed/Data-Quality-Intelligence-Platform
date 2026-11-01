package wevioo.backend.correction;

import wevioo.backend.exception.NotFoundException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/** Finds the CSV of a table in the folder of a version. */
public final class TableFiles {
    private static final String DATASET_CONFIG_FILE = "dataset_config.csv";

    private TableFiles() {}

    /** The name must match a file in the folder; it is never used as a path, so ../ or a missing table is a 404. */
    public static Path find(Path folder, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Choose the table");
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
}
