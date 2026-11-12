package io.hlthstack;

/** Access tokens. The client fetches, caches and refreshes them for you; you rarely need this. */
public final class Auth {
  private final Transport transport;

  Auth(Transport transport) {
    this.transport = transport;
  }

  /**
   * {@code GET /api/get-access-token} (getAccessToken). Returns the cached token while it is valid
   * (it is refreshed 5 minutes before {@code expires_at}).
   */
  public AccessToken getAccessToken() {
    return transport.accessToken(false);
  }

  /** The same, fetching a new token when {@code forceRefresh} is true. */
  public AccessToken getAccessToken(boolean forceRefresh) {
    return transport.accessToken(forceRefresh);
  }
}
