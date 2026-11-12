package io.hlthstack;

import static io.hlthstack.TestSupport.builder;
import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EntityHeadersTest {
  private final MockServer server = new MockServer();
  private static final Map<String, Object> QUERY = map("booking_id", "EKI0000000001700000000");

  @AfterEach
  void close() {
    server.close();
  }

  @Test
  void sentOnEveryCallButNeverOnTheTokenRequest() {
    Hlthstack client = builder(server).entityId("1001").secretKey("entity-secret").build();
    client.appointments().get(QUERY);
    MockServer.Request token = server.calls(MockServer.TOKEN_PATH).get(0);
    assertNull(token.header("api-entity-id"));
    assertNull(token.header("api-secret-key"));
    MockServer.Request call = server.calls("/api/appointments").get(0);
    assertEquals("1001", call.header("api-entity-id"));
    assertEquals("entity-secret", call.header("api-secret-key"));
  }

  @Test
  void overriddenOrDroppedPerCall() {
    Hlthstack client = builder(server).entityId("1001").secretKey("entity-secret").build();
    client.appointments().get(QUERY, RequestOptions.builder().entityId("1002").build());
    client.appointments().get(QUERY, RequestOptions.builder().entityId("1003").secretKey("other-secret").build());
    client.appointments().get(QUERY, RequestOptions.builder().withoutEntity().build());
    List<MockServer.Request> calls = server.calls("/api/appointments");
    assertEquals("1002", calls.get(0).header("api-entity-id"));
    assertEquals("entity-secret", calls.get(0).header("api-secret-key"));
    assertEquals("1003", calls.get(1).header("api-entity-id"));
    assertEquals("other-secret", calls.get(1).header("api-secret-key"));
    assertNull(calls.get(2).header("api-entity-id"));
    assertNull(calls.get(2).header("api-secret-key"));
  }

  @Test
  void withoutEntityIdNoHeadersAreSent() {
    Hlthstack client = builder(server).build();
    client.appointments().get(QUERY);
    assertNull(server.calls("/api/appointments").get(0).header("api-entity-id"));
    client.appointments().get(QUERY, RequestOptions.builder().entityId("1002").secretKey("s").build());
    assertEquals("1002", server.calls("/api/appointments").get(1).header("api-entity-id"));
  }

  @Test
  void entityIdNeedsASecretKey() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> builder(server).entityId("1001").build());
    assertEquals("secretKey is required when entityId is set", e.getMessage());
    Hlthstack client = builder(server).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> client.appointments().get(QUERY, RequestOptions.builder().entityId("1002").build()));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void extraHeadersAreSent() {
    Hlthstack client = builder(server).build();
    client.appointments().get(QUERY, RequestOptions.builder().header("X-Trace-Id", "trace-1").build());
    assertEquals("trace-1", server.calls("/api/appointments").get(0).header("x-trace-id"));
  }
}
