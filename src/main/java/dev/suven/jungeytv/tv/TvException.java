package dev.suven.jungeytv.tv;

/** Something the TV could not do, with a message fit to be said aloud. */
public final class TvException extends Exception {

    /** What kind of trouble, so a caller can decide what to offer next. */
    public enum Problem {
        /** No TV found on the network, or none set up yet. */
        NOT_FOUND,
        /** The TV did not answer - off at the wall, or off the network. */
        UNREACHABLE,
        /** Nobody has pressed Allow on the TV yet. */
        NOT_PAIRED,
        /** Someone pressed Deny, or the question timed out. */
        DENIED,
        /** A different certificate answered at the TV's address. */
        CERT_CHANGED,
        /** Asked of a TV that is in standby. */
        OFF,
        /** No installed app by that name, or no video for that search. */
        NO_MATCH,
        FAILED
    }

    private final Problem problem;

    public TvException(Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    public TvException(Problem problem, String message, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
