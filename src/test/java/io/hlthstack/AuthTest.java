package io.hlthstack;

import static io.hlthstack.TestSupport.builder;
import static io.hlthstack.TestSupport.client;
import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuthTest {
  private final MockServer server = new MockServer();
  private static final Map<String, Object> PROVIDERS = map("service_name", "ahc", "package_ids", "1001", "pincode", "500081");

  @AfterEach
  void close() {
    server.close();
  }

  private List<String> authorizations(String path) {
    List<String> out = new ArrayList<>();
    for (MockServer.Request r : server.calls(path)) out.add(r.header("authorization"));
    return out;
  }

  @Test
  void fetchesTheTokenWithBasicAuthAndCachesIt() {
    Hlthstack client = client(server);
    client.appointments().listProviders(PROVIDERS);
    client.appointments().listProviders(PROVIDERS);

    List<MockServer.Request> tokenCalls = server.calls(MockServer.TOKEN_PATH);
    assertEquals(1, tokenCalls.size());
    assertEquals("GET", tokenCalls.get(0).method);
    assertEquals(MockServer.basic(MockServer.USERNAME, MockServer.PASSWORD), tokenCalls.get(0).header("authorization"));
    assertEquals(List.of("Bearer token-1", "Bearer token-1"), authorizations("/api/providers"));
    MockServer.Request call = server.calls("/api/providers").get(0);
    assertEquals("application/json", call.header("accept"));
    assertEquals("hlthstack-java/" + Hlthstack.VERSION, call.header("user-agent"));
    assertNull(call.header("api-entity-id"));
  }

  @Test
  void refreshesFiveMinutesBeforeExpiry() {
    server.tokenLifetimeSeconds = 240; // already inside the 5-minute margin
    Hlthstack client = client(server);
    client.appointments().listProviders(PROVIDERS);
    client.appointments().listProviders(PROVIDERS);
    assertEquals(2, server.tokensIssued());
    assertEquals(List.of("Bearer token-1", "Bearer token-2"), authorizations("/api/providers"));
  }

  @Test
  void refreshesWhenTheClockPassesTheRefreshTime() {
    MutableClock clock = new MutableClock(Instant.now());
    Hlthstack client = builder(server).clock(clock).build();
    client.appointments().listProviders(PROVIDERS);
    clock.advance(Duration.ofMinutes(54));
    client.appointments().listProviders(PROVIDERS);
    assertEquals(1, server.tokensIssued());
    clock.advance(Duration.ofMinutes(2)); // 56 minutes: within 5 minutes of the 1-hour expiry
    client.appointments().listProviders(PROVIDERS);
    assertEquals(2, server.tokensIssued());
  }

  @Test
  void on401RefreshesTheTokenAndRetriesOnce() {
    Hlthstack client = client(server);
    server.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    server.reply("GET", "/api/appointments", 200, map("appointments", List.of()));
    JsonNode details = client.appointments().get(map("booking_id", "EKI0000000001700000000"));
    assertEquals(0, details.get("appointments").size());
    assertEquals(2, server.tokensIssued());
    assertEquals(List.of("Bearer token-1", "Bearer token-2"), authorizations("/api/appointments"));
  }

  @Test
  void aSecond401Throws() {
    Hlthstack client = client(server);
    server.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    server.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    AuthenticationException e = assertThrows(
        AuthenticationException.class, () -> client.appointments().get(map("booking_id", "EKI0000000001700000000")));
    assertEquals(401, e.statusCode());
    assertEquals(List.of("HTTP Token: Access denied."), e.messages());
    assertEquals(2, server.calls("/api/appointments").size());
  }

  @Test
  void wrongCredentialsThrowWithoutCallingTheApi() {
    Hlthstack client = builder(server).password("wrong").build();
    AuthenticationException e = assertThrows(AuthenticationException.class, () -> client.appointments().listProviders(PROVIDERS));
    assertEquals(List.of("Wrong credentials"), e.messages());
    assertTrue(server.apiCalls().isEmpty());
  }

  @Test
  void concurrentCallsShareOneTokenRequest() throws Exception {
    server.tokenDelayMillis = 300;
    Hlthstack client = client(server);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<JsonNode>> tasks = new ArrayList<>();
      for (int i = 0; i < 8; i++) tasks.add(() -> client.appointments().listProviders(PROVIDERS));
      for (Future<JsonNode> f : pool.invokeAll(tasks)) f.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, server.tokensIssued());
    assertEquals(8, server.calls("/api/providers").size());
    for (String auth : authorizations("/api/providers")) assertEquals("Bearer token-1", auth);
  }

  @Test
  void concurrent401sShareOneRefresh() throws Exception {
    Hlthstack client = client(server);
    client.auth().getAccessToken(); // token-1, which the server now rejects
    server.rejectedToken = "token-1";
    server.tokenDelayMillis = 300;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<JsonNode>> tasks = new ArrayList<>();
      for (int i = 0; i < 4; i++) tasks.add(() -> client.appointments().listProviders(PROVIDERS));
      for (Future<JsonNode> f : pool.invokeAll(tasks)) f.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(2, server.tokensIssued());
  }

  @Test
  void getAccessTokenReturnsTheCachedTokenOrAFreshOne() {
    Hlthstack client = client(server);
    AccessToken first = client.auth().getAccessToken();
    assertEquals("token-1", first.accessToken());
    assertTrue(first.expiresAt().endsWith("Z"));
    assertEquals("token-1", client.auth().getAccessToken().accessToken());
    assertEquals("token-2", client.auth().getAccessToken(true).accessToken());
    assertEquals(2, server.tokensIssued());
  }

  @Test
  void parsesBothExpiryFormats() {
    assertEquals(Instant.parse("2026-01-15T07:16:21Z"), TokenCache.parseExpiry("2026-01-15T07:16:21Z"));
    assertEquals(Instant.parse("2026-01-15T07:16:21Z"), TokenCache.parseExpiry("2026-01-15T12:46:21+05:30"));
    assertEquals(Instant.parse("2026-01-15T07:16:21Z"), TokenCache.parseExpiry("2026-01-15 07:16:21 UTC"));
    assertNull(TokenCache.parseExpiry("soon"));
  }

  @Test
  void anUnparseableExpiryFallsBackToOneHour() {
    MutableClock clock = new MutableClock(Instant.now());
    server.reply("GET", MockServer.TOKEN_PATH, 200, map("access_token", "odd-token", "expires_at", "soon"));
    Hlthstack client = builder(server).clock(clock).build();
    assertEquals("odd-token", client.auth().getAccessToken().accessToken());
    clock.advance(Duration.ofMinutes(54));
    assertEquals("odd-token", client.auth().getAccessToken().accessToken());
    clock.advance(Duration.ofMinutes(2));
    assertEquals("token-1", client.auth().getAccessToken().accessToken());
  }

  // ------------------------------------------------------------------ configuration

  @Test
  void needsOneCompleteSetOfCredentials() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Hlthstack.builder().username("u").build());
    assertTrue(e.getMessage().contains("username and password are required"));
    Hlthstack preRegistrationOnly = Hlthstack.builder().preRegistrationUsername("a@example.com").preRegistrationPassword("p").build();
    IllegalStateException missing = assertThrows(
        IllegalStateException.class, () -> preRegistrationOnly.auth().getAccessToken());
    assertTrue(missing.getMessage().contains("username and password are required for this call"));
  }

  @Test
  void environmentsAndBaseUrl() {
    Hlthstack production = Hlthstack.builder().username("u").password("p").build();
    assertEquals(Environment.PRODUCTION, production.environment());
    assertEquals("https://api.hlthstack.io", production.baseUrl());
    Hlthstack staging = Hlthstack.builder().username("u").password("p").environment(Environment.STAGING).build();
    assertEquals("https://staging-api.hlthstack.io", staging.baseUrl());
    assertEquals("https://proxy.example.com", Hlthstack.builder().username("u").password("p").baseUrl("https://proxy.example.com//").build().baseUrl());
  }

  @Test
  void timeoutsAndConnectionErrorsThrowApiConnectionException() {
    server.reply("GET", "/api/providers", new MockServer.Reply(200, map(), 2000));
    Hlthstack client = builder(server).timeout(Duration.ofMillis(200)).build();
    client.auth().getAccessToken();
    ApiConnectionException timeout = assertThrows(ApiConnectionException.class, () -> client.appointments().listProviders(PROVIDERS));
    assertTrue(timeout.timedOut());
    assertTrue(timeout.getMessage().contains("timed out"));

    server.reply("GET", "/api/providers", new MockServer.Reply(200, map(), 600));
    Hlthstack longer = builder(server).timeout(Duration.ofMillis(200)).build();
    longer.appointments().listProviders(PROVIDERS, RequestOptions.builder().timeout(Duration.ofSeconds(5)).build());

    Hlthstack nowhere = Hlthstack.builder().username("u").password("p").baseUrl("http://127.0.0.1:1").build();
    ApiConnectionException refused = assertThrows(ApiConnectionException.class, () -> nowhere.auth().getAccessToken());
    assertFalse(refused.timedOut());
    assertEquals(0, refused.statusCode());
  }
}
