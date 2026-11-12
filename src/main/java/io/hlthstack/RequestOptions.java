package io.hlthstack;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options for one call, passed as every method's optional last argument. Immutable; build with
 * {@link #builder()}.
 *
 * <pre>{@code
 * client.appointments().create(params, RequestOptions.builder().entityId("1002").build());
 * client.appointments().create(params, RequestOptions.builder().withoutEntity().build());
 * client.appointments().get(query, RequestOptions.builder().validate(false).build());
 * }</pre>
 */
public final class RequestOptions {
  /** No overrides: the client's settings apply. */
  public static final RequestOptions NONE = new Builder().build();

  private final boolean entityIdSet;
  private final String entityId;
  private final String secretKey;
  private final Boolean validate;
  private final Duration timeout;
  private final Map<String, String> headers;

  private RequestOptions(Builder b) {
    this.entityIdSet = b.entityIdSet;
    this.entityId = b.entityId;
    this.secretKey = b.secretKey;
    this.validate = b.validate;
    this.timeout = b.timeout;
    this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Whether {@link Builder#entityId} or {@link Builder#withoutEntity} was set. */
  boolean entityIdSet() {
    return entityIdSet;
  }

  /** The entity override; null with {@link #entityIdSet()} means "no entity headers". */
  String entityId() {
    return entityId;
  }

  String secretKey() {
    return secretKey;
  }

  Boolean validate() {
    return validate;
  }

  Duration timeout() {
    return timeout;
  }

  Map<String, String> headers() {
    return headers;
  }

  public static final class Builder {
    private boolean entityIdSet;
    private String entityId;
    private String secretKey;
    private Boolean validate;
    private Duration timeout;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Act for this entity on this call (sent as {@code API-ENTITY-ID}, with the client's or this
     * call's secret key). {@code null} acts for the entity tied to the credentials, like {@link
     * #withoutEntity()}. Ignored by pre-registration calls.
     */
    public Builder entityId(String entityId) {
      this.entityIdSet = true;
      this.entityId = entityId == null || entityId.isEmpty() ? null : entityId;
      return this;
    }

    /** Send no entity headers on this call: act for the entity tied to the credentials. */
    public Builder withoutEntity() {
      return entityId(null);
    }

    /** The secret key for this call's {@code API-SECRET-KEY} header. */
    public Builder secretKey(String secretKey) {
      this.secretKey = secretKey;
      return this;
    }

    /** Check (true) or skip (false) client-side validation for this call. */
    public Builder validate(boolean validate) {
      this.validate = validate;
      return this;
    }

    /** The timeout for this call's HTTP request. */
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /** An extra header to send. */
    public Builder header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    /**
     * Sent as {@code Idempotency-Key} (prescription digitization): repeating a key returns the
     * original request instead of creating a duplicate.
     */
    public Builder idempotencyKey(String key) {
      return header("Idempotency-Key", key);
    }

    public RequestOptions build() {
      return new RequestOptions(this);
    }
  }
}
