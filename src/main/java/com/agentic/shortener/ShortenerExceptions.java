package com.agentic.shortener;

/**
 * Domain exceptions mapped to RFC 9457 problem responses by {@link com.agentic.shortener.web.ShortenerExceptionHandler}.
 */
public final class ShortenerExceptions {

    private ShortenerExceptions() {
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String code) {
            super("Short URL '" + code + "' does not exist");
        }
    }

    public static class Gone extends RuntimeException {
        public Gone(String code, String why) {
            super("Short URL '" + code + "' is no longer available: " + why);
        }
    }

    public static class InvalidRequest extends RuntimeException {
        public InvalidRequest(String message) {
            super(message);
        }
    }

    public static class AliasTaken extends RuntimeException {
        public AliasTaken(String alias) {
            super("Alias '" + alias + "' is already in use");
        }
    }

    public static class RateLimited extends RuntimeException {
        public RateLimited() {
            super("Too many create requests; try again later");
        }
    }
}
