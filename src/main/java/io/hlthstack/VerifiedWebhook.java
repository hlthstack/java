package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;

/** A webhook whose signature, ids and timestamp checked out. */
public final class VerifiedWebhook {
  private final JsonNode claims;
  private final JsonNode payload;

  VerifiedWebhook(JsonNode claims, JsonNode payload) {
    this.claims = claims;
    this.payload = payload;
  }

  /**
   * The claims of the {@code Signature} JWT: the event's ids ({@code appointment_id} and {@code
   * booking_id}, {@code order_id}, or {@code request_id}), {@code timestamp} (Unix seconds) and
   * {@code webhook_type}.
   */
  public JsonNode claims() {
    return claims;
  }

  /** The parsed webhook body. */
  public JsonNode payload() {
    return payload;
  }

  /**
   * The body's {@code type}: {@code status_update}, {@code reports_upload}, {@code lab_results},
   * {@code additional_payment_required}, {@code post_billing} or {@code digitization_results}.
   */
  public String type() {
    return payload.path("type").asText(null);
  }
}
