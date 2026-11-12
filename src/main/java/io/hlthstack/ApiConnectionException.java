package io.hlthstack;

/**
 * The request got no HTTP response: a network error, a client-side timeout, or the calling
 * thread was interrupted. Retry with backoff, except for booking creation (check whether the
 * booking exists first).
 */
public class ApiConnectionException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  private final boolean timedOut;

  ApiConnectionException(String message, boolean timedOut, Throwable cause) {
    super(message, cause);
    this.timedOut = timedOut;
  }

  /** True when the request hit the client-side timeout. */
  public boolean timedOut() {
    return timedOut;
  }
}
