package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An access token as returned by {@code GET /api/get-access-token} or {@code GET
 * /api/pre_registration/access-token}.
 */
public final class AccessToken {
  private final String accessToken;
  private final String expiresAt;
  private final JsonNode body;

  AccessToken(JsonNode body) {
    JsonNode token = body.get("access_token");
    if (token == null || !token.isValueNode() || token.asText().isEmpty()) {
      throw new HlthstackException("The token response has no access_token");
    }
    this.accessToken = token.asText();
    this.expiresAt = body.path("expires_at").asText(null);
    this.body = body;
  }

  /** The token value. */
  public String accessToken() {
    return accessToken;
  }

  /**
   * When the token expires, as the API sent it: ISO 8601 ({@code 2026-01-15T07:16:21Z}), or
   * {@code 2026-01-15 07:16:21 UTC} for the pre-registration token.
   */
  public String expiresAt() {
    return expiresAt;
  }

  /** The whole response body. */
  public JsonNode body() {
    return body;
  }

  @Override
  public String toString() {
    return "AccessToken(expiresAt=" + expiresAt + ")";
  }
}
