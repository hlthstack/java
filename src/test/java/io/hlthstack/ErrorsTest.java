package io.hlthstack;

import static io.hlthstack.TestSupport.list;
import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ErrorsTest {
  private final MockServer server = new MockServer();
  private final Hlthstack client = TestSupport.client(server);
  private static final Map<String, Object> QUERY = map("booking_id", "EKI0000000001700000000");

  @AfterEach
  void close() {
    server.close();
  }

  private HlthstackException fail(int status, Object body) {
    server.reply("GET", "/api/appointments", status, body);
    return assertThrows(HlthstackException.class, () -> client.appointments().get(QUERY));
  }

  @Test
  void oneSubclassPerStatus() {
    Object[][] cases = {
      {400, BadRequestException.class}, {403, PermissionDeniedException.class}, {404, NotFoundException.class},
      {409, ConflictException.class}, {422, UnprocessableEntityException.class}, {500, ServerException.class},
      {502, ServerException.class}, {503, ServerException.class}, {418, HlthstackException.class},
    };
    for (Object[] c : cases) {
      HlthstackException e = fail((Integer) c[0], map("message", "boom"));
      assertSame(c[1], e.getClass(), String.valueOf(c[0]));
      assertEquals((int) (Integer) c[0], e.statusCode());
      assertEquals(List.of("boom"), e.messages());
    }
    server.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    server.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    assertThrows(AuthenticationException.class, () -> client.appointments().get(QUERY));
  }

  @Test
  void flattensEveryErrorShape() {
    Object[][] cases = {
      {map("errors", map("messages", list("This booking cannot be rescheduled"))), List.of("This booking cannot be rescheduled")},
      {map("errors", map("package_ids", list("should be an integer array"))), List.of("package_ids: should be an integer array")},
      {map("message", "Required details are missing"), List.of("Required details are missing")},
      {map("errors", list("Invalid status")), List.of("Invalid status")},
      {map("success", false, "message", "Order not found"), List.of("Order not found")},
      {map("msg", "Booking failed", "recall", false), List.of("Booking failed")},
      {map("error", "Invalid company slug"), List.of("Invalid company slug")},
      {map("success", false, "errors", map("msg", "Pharmacy benefit not enabled", "recall", false)), List.of("Pharmacy benefit not enabled")},
      {map("status", "invalid_payment", "errors", map("base", list("Amount mismatch"))), List.of("Amount mismatch")},
      {"HTTP Token: Access denied.", List.of("HTTP Token: Access denied.")},
      {null, List.of()},
    };
    for (Object[] c : cases) {
      HlthstackException e = fail(422, c[0]);
      assertEquals(c[1], e.messages(), String.valueOf(c[0]));
      if (c[0] instanceof Map) assertEquals(Json.toTree(c[0]), e.body());
      if (c[0] instanceof String) assertEquals(c[0], e.body().asText());
      if (c[0] == null) assertEquals(null, e.body());
    }
  }

  @Test
  void carriesTheMessageHeadersAndRequestId() {
    server.reply("GET", "/api/appointments",
        new MockServer.Reply(400, map("errors", map("messages", list("This appointment cannot be rescheduled")))).header("X-Request-Id", "req-123"));
    BadRequestException e = assertThrows(BadRequestException.class, () -> client.appointments().get(QUERY));
    assertEquals("req-123", e.requestId());
    assertEquals("application/json", e.headers().get("content-type").get(0));
    assertEquals(
        "400 GET " + server.url() + "/api/appointments: This appointment cannot be rescheduled", e.getMessage());
    assertTrue(e instanceof RuntimeException);
  }

  @Test
  void aSuccessWithNestedFailureIsReturnedNotThrown() {
    server.reply("GET", "/api/epharmacy/fulfillment_options", 200, map("success", true, "delivery_options", map("success", false, "message", "No stock")));
    assertEquals(
        false,
        client.pharmacy().getFulfillmentOptions(map("customer_details", map("api_customer_id", "CUST1001"), "cart_id", 701))
            .at("/delivery_options/success").asBoolean());
  }
}
