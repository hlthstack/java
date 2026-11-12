package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import io.hlthstack.WebhookVerificationException.Reason;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the webhooks hlthstack sends you.
 *
 * <p>Every webhook carries a {@code Signature} header: an HS256 JWT signed with your webhook
 * secret. The JWT has no {@code exp} claim and does not cover the body, so {@link
 * #verifySignature} checks that:
 *
 * <ol>
 *   <li>the JWT is signed with your secret using HS256 (no other algorithm is accepted);
 *   <li>its {@code timestamp} claim is within the tolerance (5 minutes by default) of now;
 *   <li>the ids in its claims match the body: {@code order_id} and {@code webhook_type} (= the
 *       body's {@code type}) for pharmacy events, {@code request_id} for prescription
 *       digitization, otherwise {@code appointment_id} and {@code booking_id}.
 * </ol>
 *
 * <pre>{@code
 * try {
 *   VerifiedWebhook event = Webhooks.verifySignature(rawBody, signatureHeader, secret);
 *   enqueue(event.payload()); // acknowledge with 200 fast, process asynchronously
 * } catch (WebhookVerificationException e) {
 *   // respond 401
 * }
 * }</pre>
 */
public final class Webhooks {
  /** The default maximum difference between now and the JWT's {@code timestamp}. */
  public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

  private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]*");

  private Webhooks() {}

  /** {@link #verifySignature(Object, String, String, Duration, Instant)} with a 5-minute tolerance. */
  public static VerifiedWebhook verifySignature(Object body, String signature, String secret) {
    return verifySignature(body, signature, secret, DEFAULT_TOLERANCE, Instant.now());
  }

  /** {@link #verifySignature(Object, String, String, Duration, Instant)} at the current time. */
  public static VerifiedWebhook verifySignature(Object body, String signature, String secret, Duration tolerance) {
    return verifySignature(body, signature, secret, tolerance, Instant.now());
  }

  /**
   * Verifies a webhook and returns its claims and parsed body.
   *
   * @param body the request body: the raw body ({@code String} or {@code byte[]}), or the parsed
   *     JSON ({@code JsonNode} or {@code Map}). The JWT does not cover the body, so either works.
   * @param signature the {@code Signature} request header
   * @param secret your webhook secret (different for staging and production)
   * @param tolerance the maximum difference between {@code now} and the JWT's {@code timestamp}
   * @param now the current time
   * @throws WebhookVerificationException when a check fails: respond 401 and don't process it
   * @throws IllegalArgumentException when {@code secret} is empty
   */
  public static VerifiedWebhook verifySignature(
      Object body, String signature, String secret, Duration tolerance, Instant now) {
    if (secret == null || secret.isEmpty()) throw new IllegalArgumentException("the webhook secret is required");
    if (signature == null || signature.trim().isEmpty()) {
      throw new WebhookVerificationException(Reason.MISSING_SIGNATURE, "Missing Signature header");
    }
    String[] parts = signature.trim().split("\\.", -1);
    if (parts.length != 3) throw new WebhookVerificationException(Reason.MALFORMED_SIGNATURE, "Signature is not a JWT");

    JsonNode header;
    JsonNode claims;
    byte[] received;
    try {
      header = Json.parseOrNull(new String(decode(parts[0]), StandardCharsets.UTF_8));
      claims = Json.parseOrNull(new String(decode(parts[1]), StandardCharsets.UTF_8));
      received = decode(parts[2]);
    } catch (IllegalArgumentException e) {
      throw new WebhookVerificationException(Reason.MALFORMED_SIGNATURE, "Signature is not a valid JWT");
    }
    if (header == null || claims == null) {
      throw new WebhookVerificationException(Reason.MALFORMED_SIGNATURE, "Signature is not a valid JWT");
    }
    if (!header.isObject() || !"HS256".equals(header.path("alg").asText(null))) {
      throw new WebhookVerificationException(Reason.UNSUPPORTED_ALGORITHM, "Signature must use HS256");
    }
    if (!claims.isObject()) throw new WebhookVerificationException(Reason.MALFORMED_SIGNATURE, "JWT claims are not an object");

    byte[] expected = hmacSha256(secret, parts[0] + "." + parts[1]);
    if (!MessageDigest.isEqual(expected, received)) {
      throw new WebhookVerificationException(Reason.INVALID_SIGNATURE, "Invalid webhook signature");
    }

    JsonNode timestamp = claims.get("timestamp");
    if (timestamp == null || !timestamp.isNumber() || !Double.isFinite(timestamp.doubleValue())) {
      throw new WebhookVerificationException(Reason.INVALID_TIMESTAMP, "JWT has no numeric timestamp claim");
    }
    double nowSeconds = now.toEpochMilli() / 1000.0;
    if (Math.abs(nowSeconds - timestamp.doubleValue()) > tolerance.toMillis() / 1000.0) {
      throw new WebhookVerificationException(Reason.STALE_TIMESTAMP, "Webhook timestamp is outside the tolerance window");
    }

    JsonNode payload = parsePayload(body);
    boolean matches;
    if (present(claims.get("order_id"))) {
      matches = same(claims.get("order_id"), payload.get("order_id")) && same(claims.get("webhook_type"), payload.get("type"));
    } else if (present(claims.get("request_id"))) {
      matches = same(claims.get("request_id"), payload.get("request_id"));
    } else {
      matches = present(claims.get("appointment_id"))
          && same(claims.get("appointment_id"), payload.get("appointment_id"))
          && same(claims.get("booking_id"), payload.get("booking_id"));
    }
    if (!matches) throw new WebhookVerificationException(Reason.ID_MISMATCH, "Webhook claims do not match the body");
    return new VerifiedWebhook(claims, payload);
  }

  private static byte[] decode(String segment) {
    if (!BASE64URL.matcher(segment).matches()) throw new IllegalArgumentException("not base64url");
    return Base64.getUrlDecoder().decode(segment);
  }

  private static byte[] hmacSha256(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  private static JsonNode parsePayload(Object body) {
    JsonNode payload;
    if (body instanceof String || body instanceof byte[]) {
      String text = body instanceof String ? (String) body : new String((byte[]) body, StandardCharsets.UTF_8);
      payload = Json.parseOrNull(text);
      if (payload == null) throw new WebhookVerificationException(Reason.INVALID_PAYLOAD, "Webhook body is not valid JSON");
    } else {
      try {
        payload = Json.toTree(body);
      } catch (IllegalArgumentException e) {
        throw new WebhookVerificationException(Reason.INVALID_PAYLOAD, "Webhook body is not JSON");
      }
    }
    if (payload == null || !payload.isObject()) {
      throw new WebhookVerificationException(Reason.INVALID_PAYLOAD, "Webhook body is not a JSON object");
    }
    return payload;
  }

  private static boolean present(JsonNode value) {
    return value != null && !value.isNull();
  }

  /** Equal JSON values; both absent counts as equal. */
  private static boolean same(JsonNode a, JsonNode b) {
    if (!present(a) || !present(b)) return !present(a) && !present(b);
    if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
    return a.equals(b);
  }
}
