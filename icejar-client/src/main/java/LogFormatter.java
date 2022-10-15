package icejar;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.Date;
import java.text.SimpleDateFormat;


public class LogFormatter extends Formatter {
    Date date = new Date();
    SimpleDateFormat d = new SimpleDateFormat("[dd MMM yyyy hh:mm:ss zzz] ");
    StringBuilder s = new StringBuilder();

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
