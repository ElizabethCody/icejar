package icejar;


public final class ExceptionLogger {
    private static boolean printStackTrace = false;

    protected static void print(String message, Object o, Throwable e) {
        if (printStackTrace) {
            String errorMsg = String.format("%s `%s` threw:", message, o);
            System.err.println(errorMsg);
            e.printStackTrace(System.err);
        } else {
            System.err.println(String.format("%s `%s` threw: %s", message, o, e));
        }
    }

    protected static void setPrintStackTrace(boolean printStackTrace) {
        ExceptionLogger.printStackTrace = printStackTrace;
    }
}
