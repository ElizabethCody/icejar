package icejar;


public final class ErrorHelper {
    protected static void printException(String message, Object o, Exception e) {
        StringBuilder errorMsg = new StringBuilder()
            .append(message)
            .append(" `")
            .append(o)
            .append("` threw: ")
            .append(e);
        System.err.println(errorMsg);
    }
}
