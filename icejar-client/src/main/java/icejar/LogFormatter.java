package icejar;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class LogFormatter extends Formatter {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("'['dd MMM yyyy hh:mm:ss zzz']' ")
                                                                     .withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord record) {
        final var timestamp = FORMAT.format(record.getInstant());
        final var level = record.getLevel();
        final var logger = record.getLoggerName();
        final var message = record.getMessage();

        return timestamp + "[" + level + "] " + logger + ": " + message + "\n";
    }
}
