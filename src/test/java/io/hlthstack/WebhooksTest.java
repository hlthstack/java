package io.hlthstack;

import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.hlthstack.WebhookVerificationException.Reason;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhooksTest {
  static final String SECRET = "test-webhook-secret";
  static final Instant NOW = Instant.ofEpochSecond(1_768_000_000L);
  static final long TS = NOW.getEpochSecond();
  static final Map<String, Object> APPOINTMENT = map(
      "type", "status_update", "appointment_id", "APT1A2B3C4D5", "booking_id", "EKI0000000001700000000", "status", "confirmed");
  static final Map<String, Object> ORDER = map("type", "status_update", "order_id", "EPO000000000001", "service_type", "pharmacy");
  static final Map<String, Object> DIGITIZATION = map("type", "digitization_results", "request_id", "DIGI_RQ_A1B2C3D4E");

  static String b64(byte[] data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }

  static String sign(Object claims, String secret, String alg) {
    try {
      String header = b64(("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
      String payload = b64(Json.MAPPER.writeValueAsBytes(claims));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return header + "." + payload + "." + b64(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String sign(Object claims) {
    return sign(claims, SECRET, "HS256");
  }

  static Map<String, Object> appointmentClaims(Object... overrides) {
    Map<String, Object> claims = map(
        "appointment_id", "APT1A2B3C4D5", "booking_id", "EKI0000000001700000000", "timestamp", TS, "webhook_type", "status_update");
    claims.putAll(map(overrides));
    return claims;
  }

  static VerifiedWebhook verify(Object body, String signature) {
    return Webhooks.verifySignature(body, signature, SECRET, Webhooks.DEFAULT_TOLERANCE, NOW);
  }

  static Reason reason(Object body, String signature) {
    return assertThrows(WebhookVerificationException.class, () -> verify(body, signature)).reason();
  }

  @Test
  void acceptsEachEventKind() {
    VerifiedWebhook appointment = verify(APPOINTMENT, sign(appointmentClaims()));
    assertEquals("APT1A2B3C4D5", appointment.claims().get("appointment_id").asText());
    assertEquals("status_update", appointment.type());
    assertEquals("confirmed", appointment.payload().get("status").asText());
    verify(ORDER, sign(map("order_id", "EPO000000000001", "webhook_type", "status_update", "timestamp", TS)));
    verify(DIGITIZATION, sign(map("request_id", "DIGI_RQ_A1B2C3D4E", "webhook_type", "digitization_results", "timestamp", TS)));
  }

  @Test
  void acceptsTheRawOrParsedBody() throws Exception {
    String signature = sign(appointmentClaims());
    String raw = Json.MAPPER.writeValueAsString(APPOINTMENT);
    verify(raw, signature);
    verify(raw.getBytes(StandardCharsets.UTF_8), signature);
    verify(Json.toTree(APPOINTMENT), signature);
    verify(APPOINTMENT, signature);
    // The 3-argument form uses the current time.
    Map<String, Object> claims = appointmentClaims("timestamp", Instant.now().getEpochSecond());
    assertEquals("APT1A2B3C4D5", Webhooks.verifySignature(raw, sign(claims), SECRET).payload().get("appointment_id").asText());
  }

  @Test
  void rejectsBadSignatures() {
    assertEquals(Reason.MISSING_SIGNATURE, reason(APPOINTMENT, null));
    assertEquals(Reason.MISSING_SIGNATURE, reason(APPOINTMENT, " "));
    assertEquals(Reason.MALFORMED_SIGNATURE, reason(APPOINTMENT, "not-a-jwt"));
    assertEquals(Reason.MALFORMED_SIGNATURE, reason(APPOINTMENT, "a.b.c"));
    assertEquals(Reason.MALFORMED_SIGNATURE, reason(APPOINTMENT, "a+b.c.d"));
    assertEquals(Reason.INVALID_SIGNATURE, reason(APPOINTMENT, sign(appointmentClaims(), "wrong-secret", "HS256")));
    assertEquals(Reason.UNSUPPORTED_ALGORITHM, reason(APPOINTMENT, sign(appointmentClaims(), SECRET, "HS512")));
    assertEquals(Reason.UNSUPPORTED_ALGORITHM, reason(APPOINTMENT, sign(appointmentClaims(), SECRET, "none")));
    String good = sign(appointmentClaims());
    assertEquals(Reason.INVALID_SIGNATURE, reason(APPOINTMENT, good.substring(0, good.length() - 2) + "AA"));
    assertEquals(Reason.MALFORMED_SIGNATURE, reason(APPOINTMENT, sign("[1, 2]")));
  }

  @Test
  void rejectsStaleOrMissingTimestamps() {
    assertEquals(Reason.STALE_TIMESTAMP, reason(APPOINTMENT, sign(appointmentClaims("timestamp", TS - 301))));
    assertEquals(Reason.STALE_TIMESTAMP, reason(APPOINTMENT, sign(appointmentClaims("timestamp", TS + 301))));
    verify(APPOINTMENT, sign(appointmentClaims("timestamp", TS - 300)));
    assertEquals(Reason.INVALID_TIMESTAMP, reason(APPOINTMENT, sign(appointmentClaims("timestamp", "now"))));
    Map<String, Object> noTimestamp = appointmentClaims();
    noTimestamp.remove("timestamp");
    assertEquals(Reason.INVALID_TIMESTAMP, reason(APPOINTMENT, sign(noTimestamp)));
    // A custom tolerance.
    Webhooks.verifySignature(APPOINTMENT, sign(appointmentClaims("timestamp", TS - 3000)), SECRET, Duration.ofHours(1), NOW);
  }

  @Test
  void rejectsClaimsThatDoNotMatchTheBody() {
    assertEquals(Reason.ID_MISMATCH, reason(APPOINTMENT, sign(appointmentClaims("appointment_id", "APT000000000"))));
    assertEquals(Reason.ID_MISMATCH, reason(APPOINTMENT, sign(appointmentClaims("booking_id", "EKI0000000000000000000"))));
    Map<String, Object> tampered = new LinkedHashMap<>(APPOINTMENT);
    tampered.put("booking_id", "EKI_OTHER");
    assertEquals(Reason.ID_MISMATCH, reason(tampered, sign(appointmentClaims())));
    assertEquals(Reason.ID_MISMATCH, reason(map(), sign(map("timestamp", TS))));
    assertEquals(Reason.ID_MISMATCH, reason(ORDER, sign(map("order_id", "EPO000000000002", "webhook_type", "status_update", "timestamp", TS))));
    assertEquals(Reason.ID_MISMATCH, reason(ORDER, sign(map("order_id", "EPO000000000001", "webhook_type", "post_billing", "timestamp", TS))));
    assertEquals(Reason.ID_MISMATCH, reason(DIGITIZATION, sign(map("request_id", "DIGI_RQ_OTHER", "timestamp", TS))));
  }

  @Test
  void rejectsBodiesThatAreNotJsonObjects() {
    String signature = sign(appointmentClaims());
    assertEquals(Reason.INVALID_PAYLOAD, reason("not json", signature));
    assertEquals(Reason.INVALID_PAYLOAD, reason("[1]", signature));
    assertThrows(IllegalArgumentException.class, () -> Webhooks.verifySignature(APPOINTMENT, signature, ""));
  }
}
