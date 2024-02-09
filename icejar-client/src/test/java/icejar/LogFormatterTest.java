package icejar;

import java.util.concurrent.*;
import java.util.logging.*;

/**
 * This test demonstrates the problematic lack of thread safety in the original
 * implementation of {@link LogFormatter}.
 * <p>
 * With the current implementation of {@link ClientManager}, a single {@code
 * LogFormatter} is unilaterally set across all parent {@link Handler Handlers},
 * which can be (and <i>are</i>) accessed by distinct threads.
 * <p>
 * Running this test against the problematic {@code LogFormatter} on an
 * adequately-multi-threaded system will often result in mangled console output.
 */
final class LogFormatterTest {
    private static final LogFormatter FORMATTER = new LogFormatter();

    private static final Level[] LEVELS = {
        Level.INFO,
        Level.WARNING,
        Level.SEVERE,
    };

    private static final Logger[] LOGGERS = {
        initLogger("yin"),
        initLogger("yang"),
    };

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void main(final String... args) throws InterruptedException {
        final var executor = Executors.newFixedThreadPool(4);

        for (int i = 0; i < 100; ++i) {
            final var logEntryId = i;
            executor.submit(() -> {
                final var level = LEVELS[logEntryId % LEVELS.length];
                final var logger = LOGGERS[logEntryId % LOGGERS.length];

                logger.log(
                    level, "id: " + logEntryId + ", expected level: " +
                    level.getName() + ", expected logger: " + logger.getName()
                );
            });
        }

        executor.shutdown();
        executor.awaitTermination(2L, TimeUnit.SECONDS);
    }

    private static Logger initLogger(final String name) {
        final var logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);

        final var handler = new ConsoleHandler();
        handler.setFormatter(FORMATTER);
        logger.addHandler(handler);

        LogManager.getLogManager().addLogger(logger);

        return logger;
    }
}
