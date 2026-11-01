package wevioo.backend.correction;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the CSV files of a dataset version, a record at a time so a large file is never
 * held in memory. The way a file is written (encoding, separator) is detected, because uploaded files
 * come from many tools; corrected files are always written as UTF-8 with commas.
 */
public final class Csv {
    private static final int SNIFF_BYTES = 256 * 1024;
    private static final char[] DELIMITERS = {',', ';', '\t', '|'};
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private Csv() {}

    public record Dialect(Charset charset, char delimiter) {}

    public static Dialect detect(Path file) throws IOException {
        byte[] head;
        try (InputStream input = Files.newInputStream(file)) {
            head = input.readNBytes(SNIFF_BYTES);
        }
        return new Dialect(isUtf8(file) ? StandardCharsets.UTF_8 : WINDOWS_1252, detectDelimiter(head));
    }

    /** The column names of a file, without the spaces around them. */
    public static List<String> header(Path file) throws IOException {
        Dialect dialect = detect(file);
        try (RecordReader reader = new RecordReader(open(file, dialect), dialect.delimiter())) {
            List<String> header = reader.next();
            return header == null ? List.of() : header.stream().map(CorrectionEngine::strip).toList();
        }
    }

    /**
     * True when the whole file is valid UTF-8. The whole file, not its start: an accent that only appears
     * after the first megabyte of a Latin-1 file must not make it pass for UTF-8 and be read as garbage.
     */
    private static boolean isUtf8(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bytes = ByteBuffer.allocate(1 << 16);
        CharBuffer chars = CharBuffer.allocate(1 << 16);
        try (InputStream input = Files.newInputStream(file)) {
            boolean end = false;
            while (!end) {
                int read = input.read(bytes.array(), bytes.position(), bytes.remaining());
                end = read < 0;
                if (!end) {
                    bytes.position(bytes.position() + read);
                }
                bytes.flip();
                while (true) {
                    var result = decoder.decode(bytes, chars, end);
                    if (result.isError()) {
                        return false;
                    }
                    chars.clear();
                    if (!result.isOverflow()) {
                        break;
                    }
                }
                bytes.compact();
            }
        }
        return true;
    }

    private static char detectDelimiter(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        int end = 0;
        boolean quoted = false;
        for (; end < text.length(); end++) {
            char c = text.charAt(end);
            if (c == '"') {
                quoted = !quoted;
            } else if ((c == '\n' || c == '\r') && !quoted && end > 0) {
                break;
            }
        }
        String header = text.substring(0, end);
        char best = ',';
        int bestCount = 0;
        for (char candidate : DELIMITERS) {
            int count = 0;
            boolean inQuotes = false;
            for (int i = 0; i < header.length(); i++) {
                char c = header.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                } else if (c == candidate && !inQuotes) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    public static Reader open(Path file, Dialect dialect) throws IOException {
        return new InputStreamReader(Files.newInputStream(file), dialect.charset());
    }

    /** Streams records: the first one is the header. Quoted cells may hold separators, quotes and line breaks. */
    public static final class RecordReader implements Closeable {
        private final Reader reader;
        private final char delimiter;
        private boolean first = true;
        private int pushedBack = -2;

        public RecordReader(Reader reader, char delimiter) {
            this.reader = reader;
            this.delimiter = delimiter;
        }

        private int read() throws IOException {
            if (pushedBack != -2) {
                int c = pushedBack;
                pushedBack = -2;
                return c;
            }
            return reader.read();
        }

        /** The next record, or null at the end of the file. A blank line is skipped. */
        public List<String> next() throws IOException {
            while (true) {
                List<String> record = readRecord();
                if (record == null) {
                    return null;
                }
                if (record.size() == 1 && record.get(0).isEmpty()) {
                    continue;
                }
                return record;
            }
        }

        private List<String> readRecord() throws IOException {
            int c = read();
            if (c == -1) {
                return null;
            }
            if (first) {
                first = false;
                if (c == '﻿') {
                    c = read();
                }
            }
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            while (true) {
                if (c == -1) {
                    cells.add(cell.toString());
                    return cells;
                }
                if (quoted) {
                    if (c == '"') {
                        int following = read();
                        if (following == '"') {
                            cell.append('"');
                        } else {
                            quoted = false;
                            pushedBack = following;
                        }
                    } else {
                        cell.append((char) c);
                    }
                } else if (c == '"' && cell.length() == 0) {
                    quoted = true;
                } else if (c == delimiter) {
                    cells.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        int following = read();
                        if (following != '\n') {
                            pushedBack = following;
                        }
                    }
                    cells.add(cell.toString());
                    return cells;
                } else {
                    cell.append((char) c);
                }
                c = read();
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /** Writes records as UTF-8 with commas, quoting the cells that need it. */
    public static final class RecordWriter implements Closeable {
        private final BufferedWriter writer;

        public RecordWriter(Path file) throws IOException {
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        }

        public void write(List<String> cells) throws IOException {
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    writer.write(',');
                }
                writer.write(escape(cells.get(i)));
            }
            writer.write('\n');
        }

        static String escape(String value) {
            if (value == null) {
                return "";
            }
            boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
            return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
