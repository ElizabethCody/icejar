package icejar;


public final class ErrorHelper {
    private static boolean printStackTrace = false;

    protected static void printException(String message, Object o, Exception e) {
        if (printStackTrace) {
            String errorMsg = String.format("%s `%s` threw:", message, o);
            System.err.println(errorMsg);
            e.printStackTrace(System.err);
        } else {
            System.err.println(String.format("%s `%s` threw: %s", message, o, e));
        }
    }

    protected static void setPrintStackTrace(boolean printStackTrace) {
        ErrorHelper.printStackTrace = printStackTrace;
    }
}
