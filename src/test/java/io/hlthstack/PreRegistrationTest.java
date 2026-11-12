package io.hlthstack;

import static io.hlthstack.TestSupport.builder;
import static io.hlthstack.TestSupport.list;
import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PreRegistrationTest {
  private final MockServer server = new MockServer();
  private static final String REQUEST_ID = "3f2b8c1e-0d4a-4c5e-9b7a-2e6f1d8c9a01";

  @AfterEach
  void close() {
    server.close();
  }

  static Map<String, Object> employee() {
    return map(
        "employee_code", "EMP1001", "company_id", "1001", "first_name", "Jane", "last_name", "Doe", "email", "jane.doe@example.com",
        "date_of_birth", "24-08-1990", "gender", "Female", "mobile_number", "9999999999", "vip", "No", "entity_name", "",
        "employee_office_location", "Hyderabad", "department", "Engineering", "grade", "B", "date_of_joining", "01-01-2026");
  }

  @Test
  void usesItsOwnCredentialsAndSendsTheRawTokenAsXEkincareKey() {
    Hlthstack client = builder(server).entityId("1001").secretKey("entity-secret").build();
    server.reply("POST", "/v1/corporate/pre_registrations", 200, map("message", "Request has been accepted", "request_id", REQUEST_ID));
    JsonNode accepted = client.preRegistration().create(map("employees", list(employee())));
    client.preRegistration().update(map("employees", list(map("email", "jane.doe@example.com", "grade", "C"))));
    client.preRegistration().deactivate(map("employees", list("jane.doe@example.com")));
    client.preRegistration().getStatus(accepted.get("request_id").asText());

    MockServer.Request token = server.calls(MockServer.PREREG_TOKEN_PATH).get(0);
    assertEquals(MockServer.basic(MockServer.PREREG_USERNAME, MockServer.PREREG_PASSWORD), token.header("authorization"));
    assertTrue(server.calls(MockServer.TOKEN_PATH).isEmpty());
    List<MockServer.Request> calls = server.apiCalls();
    assertEquals(4, calls.size());
    for (MockServer.Request call : calls) {
      assertEquals("prereg-1", call.header("x-ekincare-key")); // raw token, cached
      assertNull(call.header("authorization"));
      assertNull(call.header("api-entity-id")); // entity options do not apply
      assertNull(call.header("api-secret-key"));
    }
    assertEquals(TestSupport.json("{\"employees\":[" + Json.toTree(employee()) + "]}"), calls.get(0).json());
    assertEquals("/v1/corporate/pre_registrations/" + REQUEST_ID + "/status", calls.get(3).path);
  }

  @Test
  void parsesTheUtcExpiryFormat() {
    Hlthstack client = TestSupport.client(server);
    client.preRegistration().getStatus(REQUEST_ID);
    client.preRegistration().getStatus(REQUEST_ID);
    assertEquals(1, server.preRegistrationTokensIssued());
    AccessToken token = client.preRegistration().getAccessToken();
    assertTrue(token.expiresAt().endsWith(" UTC"), token.expiresAt());
    assertEquals("prereg-2", client.preRegistration().getAccessToken(true).accessToken());

    server.tokenLifetimeSeconds = 240; // inside the 5-minute margin: refreshed every call
    Hlthstack other = TestSupport.client(server);
    other.preRegistration().getStatus(REQUEST_ID);
    other.preRegistration().getStatus(REQUEST_ID);
    assertEquals(4, server.preRegistrationTokensIssued());
  }

  @Test
  void refreshesAndRetriesOnceOnAnEmpty401() {
    Hlthstack client = TestSupport.client(server);
    server.reply("GET", "/v1/corporate/pre_registrations/" + REQUEST_ID + "/status", 401, null);
    server.reply("GET", "/v1/corporate/pre_registrations/" + REQUEST_ID + "/status", 200, map("status", "completed"));
    assertEquals("completed", client.preRegistration().getStatus(REQUEST_ID).get("status").asText());
    assertEquals(2, server.preRegistrationTokensIssued());
  }

  @Test
  void anEmpty403IsPermissionDenied() {
    Hlthstack client = TestSupport.client(server);
    server.reply("GET", "/v1/corporate/pre_registrations/" + REQUEST_ID + "/status", 403, null);
    PermissionDeniedException e = assertThrows(PermissionDeniedException.class, () -> client.preRegistration().getStatus(REQUEST_ID));
    assertNull(e.body());
    assertTrue(e.messages().isEmpty());
  }

  @Test
  void needsThePreRegistrationCredentials() {
    Hlthstack client = Hlthstack.builder().username(MockServer.USERNAME).password(MockServer.PASSWORD).baseUrl(server.url()).build();
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.preRegistration().getStatus(REQUEST_ID));
    assertTrue(e.getMessage().contains("preRegistrationUsername and preRegistrationPassword are required"));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void validatesRowsBeforeSending() {
    Hlthstack client = TestSupport.client(server);
    ValidationException e = assertThrows(
        ValidationException.class,
        () -> client.preRegistration().create(map("employees", list(TestSupport.map("first_name", "Jane", "email", "", "employee_code", "")))));
    assertEquals("createPreRegistrations", e.operationId());
    assertTrue(server.requests().isEmpty());
  }
}
