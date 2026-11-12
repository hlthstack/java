package io.hlthstack;

/** The hlthstack environments. Credentials, ids and webhook secrets differ per environment. */
public enum Environment {
  /** {@code https://api.hlthstack.io} */
  PRODUCTION("https://api.hlthstack.io"),
  /** {@code https://staging-api.hlthstack.io}: sandbox with the staging-only simulators. */
  STAGING("https://staging-api.hlthstack.io");

  private final String baseUrl;

  Environment(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String baseUrl() {
    return baseUrl;
  }
}
