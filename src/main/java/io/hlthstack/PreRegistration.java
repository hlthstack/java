package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Register, update and deactivate members and their dependants in bulk. Uses the
 * pre-registration credentials ({@code preRegistrationUsername} / {@code preRegistrationPassword})
 * and sends their token as {@code X-EKINCARE-KEY}; entity options do not apply (each row names its
 * {@code company_id}). Writes are asynchronous: each returns a {@code request_id} to poll with
 * {@link #getStatus}.
 */
public final class PreRegistration {
  private final Transport transport;

  PreRegistration(Transport transport) {
    this.transport = transport;
  }

  /**
   * {@code GET /api/pre_registration/access-token} (getPreRegistrationAccessToken). Returns the
   * cached token while it is valid. {@code expires_at} looks like {@code 2026-01-15 07:16:21 UTC}.
   */
  public AccessToken getAccessToken() {
    return transport.preRegistrationAccessToken(false);
  }

  /** The same, fetching a new token when {@code forceRefresh} is true. */
  public AccessToken getAccessToken(boolean forceRefresh) {
    return transport.preRegistrationAccessToken(forceRefresh);
  }

  /**
   * {@code POST /v1/corporate/pre_registrations} (createPreRegistrations). Every required key must
   * be present in every row, even when empty: the API silently drops rows that miss one. At most
   * 250 rows in {@code employees} and {@code dependants} together.
   */
  public JsonNode create(Map<String, ?> body) {
    return create(body, RequestOptions.NONE);
  }

  public JsonNode create(Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("createPreRegistrations", "POST", "/v1/corporate/pre_registrations").body(body).preRegistration(),
        options);
  }

  /** {@code POST /v1/corporate/pre_registrations/update} (updatePreRegistrations). */
  public JsonNode update(Map<String, ?> body) {
    return update(body, RequestOptions.NONE);
  }

  public JsonNode update(Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("updatePreRegistrations", "POST", "/v1/corporate/pre_registrations/update")
            .body(body)
            .preRegistration(),
        options);
  }

  /**
   * {@code POST /v1/corporate/pre_registrations/deactivate} (deactivatePreRegistrations). {@code
   * employees} are emails, {@code dependants} mobile numbers; deactivating an employee
   * deactivates their dependants too.
   */
  public JsonNode deactivate(Map<String, ?> body) {
    return deactivate(body, RequestOptions.NONE);
  }

  public JsonNode deactivate(Map<String, ?> body, RequestOptions options) {
    return transport.json(
        new Transport.Call("deactivatePreRegistrations", "POST", "/v1/corporate/pre_registrations/deactivate")
            .body(body)
            .preRegistration(),
        options);
  }

  /**
   * {@code GET /v1/corporate/pre_registrations/{request_id}/status} (getPreRegistrationStatus).
   * Poll until {@code status} is {@code completed}.
   */
  public JsonNode getStatus(String requestId) {
    return getStatus(requestId, RequestOptions.NONE);
  }

  public JsonNode getStatus(String requestId, RequestOptions options) {
    return transport.json(
        new Transport.Call("getPreRegistrationStatus", "GET", "/v1/corporate/pre_registrations/{request_id}/status", requestId)
            .preRegistration(),
        options);
  }
}
