package icejar;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.Date;
import java.text.SimpleDateFormat;


final class LogFormatter extends Formatter {
    final Date date = new Date();
    final SimpleDateFormat d = new SimpleDateFormat("[dd MMM yyyy hh:mm:ss zzz] ");
    final StringBuilder s = new StringBuilder();

    public String format(LogRecord record) {
        s.setLength(0);

        date.setTime(record.getMillis());
        s.append(d.format(date));

        s.append('[');
        s.append(record.getLevel());
        s.append("] ");

        s.append(record.getLoggerName());
        s.append(": ");

        s.append(record.getMessage());

        s.append('\n');
        return s.toString();
    }
}
