package wevioo.backend.correction;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the dates found in a column into ISO form (2024-02-01). A date written 01/02/2024 can be the
 * 1st of February or the 2nd of January: unless the user says which convention the column uses, such a
 * value is left as it is and counted for review instead of being guessed.
 */
public final class DateNormalizer {
    public enum Outcome {
        /** Written in ISO form already. */
        CANONICAL,
        CONVERTED,
        /** Day and month can be swapped; the value was left untouched. */
        AMBIGUOUS,
        /** Not a date this tool reads (or not a real date); the value was left untouched. */
        UNRECOGNISED
    }

    public record Result(Outcome outcome, String value) {}

    private static final String TIME = "(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?";
    private static final Pattern YEAR_FIRST = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})" + TIME + "$");
    private static final Pattern YEAR_LAST = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})" + TIME + "$");
    private static final Pattern DAY_MONTH_NAME = Pattern.compile("^(\\d{1,2})[ -]([A-Za-z]{3,9})\\.?,?[ -](\\d{4})" + TIME + "$");
    private static final Pattern MONTH_NAME_DAY = Pattern.compile("^([A-Za-z]{3,9})\\.? (\\d{1,2}),? (\\d{4})" + TIME + "$");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
        Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("may", 5),
        Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("oct", 10),
        Map.entry("nov", 11), Map.entry("dec", 12)
    );

    private DateNormalizer() {}

    /** @param order AUTO, DMY, MDY or YMD: how a value like 01/02/2024 is read */
    public static Result normalize(String raw, String order) {
        String value = raw.strip();
        Matcher match = YEAR_FIRST.matcher(value);
        if (match.matches()) {
            return build(raw, value, intOf(match.group(1)), intOf(match.group(2)), intOf(match.group(3)), match, 4);
        }
        match = YEAR_LAST.matcher(value);
        if (match.matches()) {
            int first = intOf(match.group(1));
            int second = intOf(match.group(2));
            int year = intOf(match.group(3));
            boolean dayFirst;
            switch (order) {
                case "DMY" -> dayFirst = true;
                case "MDY" -> dayFirst = false;
                default -> {
                    if (first > 12) {
                        dayFirst = true;      // 25/02/2024 can only be the 25th
                    } else if (second > 12) {
                        dayFirst = false;     // 02/25/2024 can only be the 25th
                    } else if (first == second) {
                        dayFirst = true;      // 03/03/2024 reads the same either way
                    } else {
                        return new Result(Outcome.AMBIGUOUS, raw);
                    }
                }
            }
            return dayFirst
                ? build(raw, value, year, second, first, match, 4)
                : build(raw, value, year, first, second, match, 4);
        }
        match = DAY_MONTH_NAME.matcher(value);
        if (match.matches()) {
            Integer month = MONTHS.get(match.group(2).substring(0, 3).toLowerCase(Locale.ROOT));
            return month == null ? new Result(Outcome.UNRECOGNISED, raw)
                : build(raw, value, intOf(match.group(3)), month, intOf(match.group(1)), match, 4);
        }
        match = MONTH_NAME_DAY.matcher(value);
        if (match.matches()) {
            Integer month = MONTHS.get(match.group(1).substring(0, 3).toLowerCase(Locale.ROOT));
            return month == null ? new Result(Outcome.UNRECOGNISED, raw)
                : build(raw, value, intOf(match.group(3)), month, intOf(match.group(2)), match, 4);
        }
        return new Result(Outcome.UNRECOGNISED, raw);
    }

    /** True when the text looks like a date this tool reads, whatever its convention. */
    public static boolean looksLikeDate(String raw) {
        return normalize(raw, "AUTO").outcome() != Outcome.UNRECOGNISED;
    }

    private static Result build(String raw, String value, int year, int month, int day, Matcher match, int timeGroup) {
        LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return new Result(Outcome.UNRECOGNISED, raw);
        }
        String iso = date.toString();
        if (match.group(timeGroup) != null) {
            int hour = intOf(match.group(timeGroup));
            int minute = intOf(match.group(timeGroup + 1));
            int second = match.group(timeGroup + 2) == null ? 0 : intOf(match.group(timeGroup + 2));
            if (hour > 23 || minute > 59 || second > 59) {
                return new Result(Outcome.UNRECOGNISED, raw);
            }
            iso = String.format(Locale.ROOT, "%s %02d:%02d:%02d", iso, hour, minute, second);
        }
        return new Result(iso.equals(value) ? Outcome.CANONICAL : Outcome.CONVERTED, iso);
    }

    private static int intOf(String digits) {
        return Integer.parseInt(digits);
    }
}
