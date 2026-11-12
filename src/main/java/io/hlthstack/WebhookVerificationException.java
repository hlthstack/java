package io.hlthstack;

/**
 * A webhook's {@code Signature} JWT, ids or timestamp did not verify. Respond with 401 and do not
 * process the webhook.
 */
public class WebhookVerificationException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  /** Why verification failed. */
  public enum Reason {
    MISSING_SIGNATURE,
    MALFORMED_SIGNATURE,
    UNSUPPORTED_ALGORITHM,
    INVALID_SIGNATURE,
    INVALID_TIMESTAMP,
    STALE_TIMESTAMP,
    ID_MISMATCH,
    INVALID_PAYLOAD
  }

  private final Reason reason;

  WebhookVerificationException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
