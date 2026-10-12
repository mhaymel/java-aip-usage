package org.example;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Says what went wrong, in a fixed set of texts of the program's own. It is the one place that looks at what kind of
 * failure the system reported: everywhere else a failure is named by asking here, so that no text of the Java
 * runtime or of the operating system, which differ from one system and one language to the next, reaches the window
 * or the log.
 *
 * <p>A failure the program names itself, one of its own exceptions, is said in its own words.
 */
public final class Failure {

    public static final String FILE_MISSING = "the file or its folder is not there";

    public static final String ACCESS_DENIED = "access is denied";

    public static final String FILE_FAILED = "the file could not be read or written";

    public static final String HOST_UNKNOWN = "the host is not known";

    public static final String REFUSED = "the connection was refused";

    public static final String CLOSED = "the connection was closed";

    public static final String NOT_SECURE = "the secure connection could not be made";

    public static final String TOO_LONG = "there was no answer in time";

    public static final String ADDRESS = "the address is in use or cannot be used";

    public static final String CONNECTION_FAILED = "the connection failed";

    public static final String IO_FAILED = "input or output failed";

    public static final String INTERRUPTED = "interrupted";

    public static final String UNEXPECTED = "an unexpected fault";

    public static final String NOT_A_PROGRAM = "it is not a program this system can run";

    public static final String NOT_STARTED = "the system did not start it";

    /** The package of the program, whose exceptions carry messages it wrote. */
    private static final String OWN = "org.example.";

    /** The number the system gives for why a program did not start, which is in the text of the failure on every system. */
    private static final Pattern SYSTEM_ERROR = Pattern.compile("error=([0-9]+)");

    private Failure() {
    }

    /** What went wrong: the program's own words for a failure of its own, and one of the fixed texts for any other. */
    public static String of(Throwable failure) {
        return of(failure, IO_FAILED);
    }

    /** As {@link #of(Throwable)}, for a failure of a request over the network: one of no known kind is the connection failing. */
    public static String ofConnection(Throwable failure) {
        return of(failure, CONNECTION_FAILED);
    }

    /**
     * What went wrong when a program could not be started. The system says why with a number, which is read from the
     * text of the failure; the text itself, which is in the language of the system, is not used.
     */
    public static String ofStart(IOException failure) {
        if (isOwn(failure)) {
            return failure.getMessage();
        }
        Matcher number = SYSTEM_ERROR.matcher(String.valueOf(failure.getMessage()));
        if (!number.find()) {
            return NOT_STARTED;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return switch (number.group(1)) {
            // 2 is "no such file" on every system; 3 is "no such path" on Windows.
            case "2" -> FILE_MISSING;
            case "3" -> windows ? FILE_MISSING : NOT_STARTED;
            case "5" -> windows ? ACCESS_DENIED : NOT_STARTED;
            case "13" -> windows ? NOT_STARTED : ACCESS_DENIED;
            case "8" -> windows ? NOT_STARTED : NOT_A_PROGRAM;
            case "193" -> windows ? NOT_A_PROGRAM : NOT_STARTED;
            default -> NOT_STARTED;
        };
    }

    private static String of(Throwable failure, String otherInputOutput) {
        if (failure == null) {
            return UNEXPECTED;
        }
        if (isOwn(failure)) {
            return failure.getMessage() == null ? UNEXPECTED : failure.getMessage();
        }
        // A name that cannot be resolved is reported as a refused connection with the reason inside it, so that is looked for first.
        for (Throwable cause = failure; cause != null; cause = next(cause)) {
            if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return HOST_UNKNOWN;
            }
        }
        for (Throwable cause = failure; cause != null; cause = next(cause)) {
            String known = known(cause);
            if (known != null) {
                return known;
            }
        }
        if (failure instanceof FileSystemException) {
            return FILE_FAILED;
        }
        if (failure instanceof IOException) {
            return otherInputOutput;
        }
        return failure instanceof InterruptedException ? INTERRUPTED : UNEXPECTED;
    }

    /** The text for a failure of a kind that says what it is; null for one that does not. */
    private static String known(Throwable failure) {
        if (failure instanceof NoSuchFileException || failure instanceof FileNotFoundException) {
            return FILE_MISSING;
        }
        if (failure instanceof AccessDeniedException) {
            return ACCESS_DENIED;
        }
        if (failure instanceof BindException) {
            return ADDRESS;
        }
        if (failure instanceof ConnectException) {
            return REFUSED;
        }
        if (failure instanceof HttpTimeoutException || failure instanceof java.net.SocketTimeoutException) {
            return TOO_LONG;
        }
        if (failure instanceof javax.net.ssl.SSLException) {
            return NOT_SECURE;
        }
        if (failure instanceof SocketException || failure instanceof EOFException || failure instanceof ClosedChannelException) {
            return CLOSED;
        }
        if (failure instanceof InterruptedIOException) {
            return INTERRUPTED;
        }
        return null;
    }

    private static boolean isOwn(Throwable failure) {
        return failure.getClass().getName().startsWith(OWN);
    }

    /** What caused a failure, if anything other than itself did. */
    private static Throwable next(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == failure ? null : cause;
    }
}
