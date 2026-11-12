import static io.hlthstack.TestSupport.list;
import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.hlthstack.MockServer;
import io.hlthstack.Webhooks;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the plain REST API examples in examples/api against a local mock of the API. */
class PlainApiExamplesTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final MockServer mock = new MockServer();

  @AfterEach
  void close() {
    mock.close();
  }

  private Map<String, String> env() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("HLTHSTACK_BASE_URL", mock.url());
    env.put("HLTHSTACK_USERNAME", MockServer.USERNAME);
    env.put("HLTHSTACK_PASSWORD", MockServer.PASSWORD);
    env.put("HLTHSTACK_PREREG_USERNAME", MockServer.PREREG_USERNAME);
    env.put("HLTHSTACK_PREREG_PASSWORD", MockServer.PREREG_PASSWORD);
    return env;
  }

  private ApiClient api() {
    return new ApiClient(env());
  }

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  private List<String> authorizations(String path) {
    List<String> out = new ArrayList<>();
    for (MockServer.Request r : mock.calls(path)) out.add(r.header("authorization"));
    return out;
  }

  private static Map<String, List<String>> query(Object... pairs) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], List.of((String) pairs[i + 1]));
    return out;
  }

  // ------------------------------------------------------------------ auth helpers

  @Test
  void tokenIsFetchedWithBasicAuthAndCached() throws Exception {
    ApiClient api = api();
    api.get("/api/providers").query("service_name", "ahc").send();
    api.get("/api/providers").query("service_name", "dental").send();

    List<MockServer.Request> tokenCalls = mock.calls(MockServer.TOKEN_PATH);
    assertEquals(1, tokenCalls.size());
    assertEquals("GET", tokenCalls.get(0).method);
    assertEquals(MockServer.basic(MockServer.USERNAME, MockServer.PASSWORD), tokenCalls.get(0).header("authorization"));
    assertEquals(List.of("Bearer token-1", "Bearer token-1"), authorizations("/api/providers"));
    MockServer.Request first = mock.calls("/api/providers").get(0);
    assertEquals(query("service_name", "ahc"), first.query);
    assertNull(first.header("api-entity-id"));
    assertNull(first.header("api-secret-key"));
  }

  @Test
  void tokenIsRefreshedFiveMinutesBeforeExpiry() throws Exception {
    mock.tokenLifetimeSeconds = 240; // already inside the 5-minute margin
    ApiClient api = api();
    api.get("/api/providers").send();
    api.get("/api/providers").send();
    assertEquals(2, mock.tokensIssued());
    assertEquals(List.of("Bearer token-1", "Bearer token-2"), authorizations("/api/providers"));
  }

  @Test
  void a401RefreshesTheTokenAndRetriesOnce() throws Exception {
    ApiClient api = api();
    mock.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    mock.reply("GET", "/api/appointments", 200, map("appointments", list()));
    assertEquals(json("{\"appointments\":[]}"), api.get("/api/appointments").query("booking_id", "B").send());
    assertEquals(2, mock.tokensIssued());
    assertEquals(List.of("Bearer token-1", "Bearer token-2"), authorizations("/api/appointments"));
  }

  @Test
  void aSecond401Throws() {
    ApiClient api = api();
    mock.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    mock.reply("GET", "/api/appointments", 401, "HTTP Token: Access denied.");
    ApiClient.ApiException e = assertThrows(ApiClient.ApiException.class, () -> api.get("/api/appointments").send());
    assertEquals(401, e.status);
    assertEquals(List.of("HTTP Token: Access denied."), e.messages);
    assertEquals(2, mock.calls("/api/appointments").size());
  }

  @Test
  void wrongCredentialsThrow() {
    Map<String, String> env = env();
    env.put("HLTHSTACK_PASSWORD", "wrong");
    ApiClient api = new ApiClient(env);
    ApiClient.ApiException e = assertThrows(ApiClient.ApiException.class, () -> api.get("/api/providers").send());
    assertEquals(401, e.status);
    assertEquals(List.of("Wrong credentials"), e.messages);
    assertTrue(mock.calls("/api/providers").isEmpty());
  }

  @Test
  void errorShapes() throws Exception {
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
    };
    ApiClient api = api();
    for (Object[] c : cases) {
      mock.reply("POST", "/api/appointments", 422, c[0]);
      ApiClient.ApiException e = assertThrows(ApiClient.ApiException.class, () -> api.post("/api/appointments").json(map()).send());
      assertEquals(422, e.status);
      assertEquals(JSON.valueToTree(c[0]), e.body);
      assertEquals(c[1], e.messages);
    }
  }

  @Test
  void entityHeaders() throws Exception {
    Map<String, String> env = env();
    env.put("HLTHSTACK_ENTITY_ID", "1001");
    env.put("HLTHSTACK_SECRET_KEY", "entity-secret");
    ApiClient api = new ApiClient(env);
    api.get("/api/providers").send();
    api.get("/api/providers").header("API-ENTITY-ID", "1002").header("API-SECRET-KEY", "other").send();

    MockServer.Request token = mock.calls(MockServer.TOKEN_PATH).get(0);
    assertNull(token.header("api-entity-id")); // never on the token request
    List<MockServer.Request> calls = mock.calls("/api/providers");
    assertEquals("1001", calls.get(0).header("api-entity-id"));
    assertEquals("entity-secret", calls.get(0).header("api-secret-key"));
    assertEquals("1002", calls.get(1).header("api-entity-id"));
    assertEquals("other", calls.get(1).header("api-secret-key"));
  }

  // ------------------------------------------------------------------ health checkups

  @Test
  void bookHealthCheckup(@TempDir Path dir) throws Exception {
    mock.reply("GET", "/api/providers", 200, map("providers", list(map("provider_id", 1001))));
    mock.reply("GET", "/api/providers/1001/ahc/slots", 200, map("booking_slots", list(map("date", "2030-01-15", "slots", list(
        map("available", false, "utc_slot_time", "2030-01-15T01:30:00.000Z"),
        map("available", true, "utc_slot_time", "2030-01-15T02:30:00.000Z"),
        map("available", true, "utc_slot_time", "2030-01-15T03:30:00.000Z"))))));
    mock.reply("POST", "/api/appointments", 200, map(
        "booking_id", "EKI0000000001700000000", "status", "confirmed", "customers", list(map("appointment_id", "APT1A2B3C4D5"))));
    mock.reply("GET", "/api/appointments", 200, map("appointments", list(map("appointment_id", "APT1A2B3C4D5", "status", "new"))));
    mock.reply("GET", "/api/appointments/APT1A2B3C4D5/voucher", 200, "%PDF-1.4 voucher".getBytes(StandardCharsets.UTF_8));
    Path voucher = dir.resolve("voucher.pdf");

    BookHealthCheckup.run(api(), voucher);

    List<MockServer.Request> calls = mock.apiCalls();
    List<String> sequence = new ArrayList<>();
    for (MockServer.Request c : calls) sequence.add(c.method + " " + c.path);
    assertEquals(List.of(
        "GET /api/providers", "GET /api/providers/1001/ahc/slots", "POST /api/appointments", "GET /api/appointments",
        "PATCH /api/appointments/APT1A2B3C4D5/reschedule", "POST /api/appointments/APT1A2B3C4D5/update_status",
        "GET /api/appointments/APT1A2B3C4D5/voucher", "PATCH /api/appointments/APT1A2B3C4D5/cancel"), sequence);
    assertEquals(query("service_name", "ahc", "package_ids", "1001", "pincode", "500081"), calls.get(0).query);
    assertEquals(query("package_ids", "1001", "pincode", "500081"), calls.get(1).query);
    assertEquals("application/json", calls.get(2).header("content-type"));
    assertEquals(json("{\"service_name\":\"ahc\",\"appointment_type\":\"inclinic\",\"provider_id\":1001,"
        + "\"appointment_date_time\":\"2030-01-15T02:30:00.000Z\",\"customers\":[{\"name\":\"Jane Doe\",\"dob\":\"1990-08-24\","
        + "\"gender\":\"female\",\"mobile_number\":\"9999999999\",\"api_customer_id\":\"CUST1001\",\"package_ids\":[1001]}]}"),
        calls.get(2).json());
    assertEquals(query("booking_id", "EKI0000000001700000000"), calls.get(3).query);
    assertEquals(json("{\"appointment_date_time\":\"2030-01-15T03:30:00.000Z\"}"), calls.get(4).json());
    assertEquals(json("{\"status\":\"confirm\"}"), calls.get(5).json());
    for (MockServer.Request c : calls) assertEquals("Bearer token-1", c.header("authorization"));
    assertArrayEquals("%PDF-1.4 voucher".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(voucher));
  }

  @Test
  void consultationAndPharmacy() throws Exception {
    mock.reply("GET", "/api/consultations/inclinic_consultation/providers", 200, map("providers", list(map("provider_id", 2001))));
    mock.reply("GET", "/api/providers/2001/inclinic_consultation/slots", 200,
        map("booking_slots", list(map("slots", list(map("available", true, "utc_slot_time", "2030-01-15T04:30:00.000Z"))))));
    mock.reply("POST", "/api/consultations/inclinic_consultation", 200,
        map("booking_id", "EKI0000000001700000000", "customers", list(map("appointment_id", "A"))));
    mock.reply("PATCH", "/api/epharmacy/cart", 200, map("success", true, "cart", map("id", 701)));
    mock.reply("GET", "/api/epharmacy/fulfillment_options", 200,
        map("success", true, "delivery_options", map("success", true, "standard", map("location_token", "TOKEN"))));
    mock.reply("POST", "/api/epharmacy/orders", 200, map("status", "confirmed"));
    ApiClient api = api();

    BookConsultation.bookInclinicConsultation(api);
    PharmacyOrder.placeOrder(api, 50001);

    MockServer.Request providers = mock.calls("/api/consultations/inclinic_consultation/providers").get(0);
    assertEquals(query("package_ids", "2001", "pincode", "500084", "sort_by", "distance"), providers.query);
    JsonNode book = mock.calls("/api/consultations/inclinic_consultation").get(0).json();
    assertEquals(2001, book.get("provider_id").asInt());
    assertEquals("2030-01-15T04:30:00.000Z", book.get("appointment_date_time").asText());
    assertEquals("at_provider_location", book.get("service_mode").asText());
    assertEquals("500084", book.at("/address/pincode").asText());
    assertEquals(json("[2001]"), book.at("/customers/0/package_ids"));

    MockServer.Request cart = mock.calls("/api/epharmacy/cart").get(0);
    assertEquals("PATCH", cart.method);
    assertEquals(json("{\"api_customer_id\":\"CUST1001\"}"), cart.json().get("customer_details"));
    assertEquals(json("[{\"id\":50001,\"quantity\":1}]"), cart.json().get("products"));
    MockServer.Request options = mock.calls("/api/epharmacy/fulfillment_options").get(0);
    assertEquals(query("customer_details[api_customer_id]", "CUST1001", "cart_id", "701"), options.query);
    assertEquals(
        json("{\"customer_details\":{\"api_customer_id\":\"CUST1001\"},\"cart_id\":701,\"delivery_option\":\"standard\",\"location_token\":\"TOKEN\"}"),
        mock.calls("/api/epharmacy/orders").get(0).json());
  }

  @Test
  void pharmacyStopsWhenStandardDeliveryIsUnavailable() {
    mock.reply("PATCH", "/api/epharmacy/cart", 200, map("success", true, "cart", map("id", 701)));
    mock.reply("GET", "/api/epharmacy/fulfillment_options", 200,
        map("success", true, "delivery_options", map("success", false, "message", "Not serviceable")));
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> PharmacyOrder.placeOrder(api(), 50001));
    assertTrue(e.getMessage().contains("Not serviceable"));
    assertTrue(mock.calls("/api/epharmacy/orders").isEmpty());
  }

  // ------------------------------------------------------------------ uploads

  @Test
  void uploadPrescription(@TempDir Path dir) throws Exception {
    Path prescription = dir.resolve("rx.pdf");
    Files.write(prescription, "%PDF-1.4 rx".getBytes(StandardCharsets.UTF_8));
    mock.reply("POST", "/api/prescriptions/digitize",
        new MockServer.Reply(401, "HTTP Token: Access denied."), // the file must be resent after the refresh
        new MockServer.Reply(202, map("request_id", "DIGI_RQ_A1B2C3D4E", "status", "pending")));
    mock.reply("GET", "/api/prescriptions/digitize/DIGI_RQ_A1B2C3D4E",
        new MockServer.Reply(200, map("status", "ai_processing")),
        new MockServer.Reply(200, map("status", "completed", "cart_id", 701, "items", list())));
    ApiClient api = api();

    JsonNode accepted = UploadPrescription.digitizePrescription(api, prescription, "upload-0001");
    JsonNode result = UploadPrescription.waitForDigitization(api, accepted.get("request_id").asText(), Duration.ZERO);

    assertEquals(701, result.get("cart_id").asInt());
    List<MockServer.Request> uploads = mock.calls("/api/prescriptions/digitize");
    assertEquals(2, uploads.size());
    MockServer.Request upload = uploads.get(1);
    assertEquals("Bearer token-2", upload.header("authorization"));
    assertEquals("upload-0001", upload.header("idempotency-key"));
    assertTrue(upload.header("content-type").startsWith("multipart/form-data; boundary="));
    List<String> expected = List.of(
        "customers[][name]=Jane Doe", "customers[][api_customer_id]=CUST1001", "customers[][mobile_number]=9999999999",
        "customers[][gender]=female", "customers[][dob]=1990-08-24", "files[]=@rx.pdf(application/pdf):%PDF-1.4 rx");
    for (MockServer.Request r : uploads) {
      List<String> parts = new ArrayList<>();
      for (MockServer.Part p : r.form()) parts.add(p.toString());
      assertEquals(expected, parts);
    }
    assertEquals(2, mock.calls("/api/prescriptions/digitize/DIGI_RQ_A1B2C3D4E").size());
  }

  // ------------------------------------------------------------------ pre-registration

  @Test
  void preRegistration() throws Exception {
    String[] ids = {"11111111-0000-4000-8000-000000000001", "2", "3"};
    String[] paths = {"", "/update", "/deactivate"};
    for (int i = 0; i < 3; i++) {
      mock.reply("POST", "/v1/corporate/pre_registrations" + paths[i], 200, map("message", "Request has been accepted", "request_id", ids[i]));
    }
    Map<String, Object> done = map("status", "completed", "success_records", list(), "failure_records", list());
    mock.reply("GET", "/v1/corporate/pre_registrations/" + ids[0] + "/status",
        new MockServer.Reply(200, map("status", "inprogress")), new MockServer.Reply(200, done));
    mock.reply("GET", "/v1/corporate/pre_registrations/2/status", 200, done);
    mock.reply("GET", "/v1/corporate/pre_registrations/3/status", 200, done);

    PreRegistration.run(api(), Duration.ZERO);

    MockServer.Request token = mock.calls(MockServer.PREREG_TOKEN_PATH).get(0);
    assertEquals(MockServer.basic(MockServer.PREREG_USERNAME, MockServer.PREREG_PASSWORD), token.header("authorization"));
    assertTrue(mock.calls(MockServer.TOKEN_PATH).isEmpty());
    for (MockServer.Request call : mock.apiCalls()) {
      assertEquals("prereg-1", call.header("x-ekincare-key")); // raw token, cached
      assertNull(call.header("authorization"));
      assertNull(call.header("api-entity-id"));
    }
    assertEquals(JSON.valueToTree(map("employees", list(PreRegistration.employee()))), mock.calls("/v1/corporate/pre_registrations").get(0).json());
    assertEquals(json("{\"employees\":[{\"email\":\"jane.doe@example.com\",\"grade\":\"C\"}]}"),
        mock.calls("/v1/corporate/pre_registrations/update").get(0).json());
    assertEquals(json("{\"employees\":[\"jane.doe@example.com\"]}"), mock.calls("/v1/corporate/pre_registrations/deactivate").get(0).json());
  }

  @Test
  void preRegistrationTokenExpiryFormat() throws Exception {
    mock.tokenLifetimeSeconds = 240; // inside the refresh margin: "YYYY-MM-DD HH:MM:SS UTC" parsed
    ApiClient api = api();
    api.preRegistration("GET", "/v1/corporate/pre_registrations/1/status").send();
    api.preRegistration("GET", "/v1/corporate/pre_registrations/1/status").send();
    assertEquals(2, mock.preRegistrationTokensIssued());
  }

  @Test
  void preRegistrationRowLimit() {
    List<Object> employees = new ArrayList<>();
    for (int i = 0; i < 200; i++) employees.add(PreRegistration.employee());
    List<Object> dependants = new ArrayList<>();
    for (int i = 0; i < 51; i++) dependants.add(map());
    Map<String, List<?>> body = new LinkedHashMap<>();
    body.put("employees", employees);
    body.put("dependants", dependants);
    IllegalArgumentException e = assertThrows(
        IllegalArgumentException.class, () -> PreRegistration.submit(api(), "/v1/corporate/pre_registrations", body));
    assertTrue(e.getMessage().contains("at most 250 rows"));
    assertTrue(mock.requests().isEmpty());
  }

  // ------------------------------------------------------------------ webhooks

  static final String SECRET = "test-webhook-secret";
  static final Map<String, Object> APPOINTMENT = map(
      "type", "status_update", "appointment_id", "APT1A2B3C4D5", "booking_id", "EKI0000000001700000000", "status", "confirmed");
  static final Map<String, Object> ORDER = map("type", "status_update", "order_id", "EPO000000000001", "service_type", "pharmacy");
  static final Map<String, Object> DIGITIZATION = map("type", "digitization_results", "request_id", "DIGI_RQ_A1B2C3D4E");

  static String b64(byte[] data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }

  static String sign(Object claims, String secret, String alg) throws Exception {
    String header = b64(("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
    String payload = b64(JSON.writeValueAsBytes(claims));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return header + "." + payload + "." + b64(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
  }

  static String sign(Object claims) throws Exception {
    return sign(claims, SECRET, "HS256");
  }

  static long now() {
    return System.currentTimeMillis() / 1000;
  }

  static Map<String, Object> appointmentClaims(Object... overrides) {
    Map<String, Object> claims = map(
        "appointment_id", "APT1A2B3C4D5", "booking_id", "EKI0000000001700000000", "timestamp", now(), "webhook_type", "status_update");
    claims.putAll(map(overrides));
    return claims;
  }

  @Test
  void webhookAcceptsValidSignatures() throws Exception {
    Object[][] cases = {
      {APPOINTMENT, appointmentClaims()},
      {ORDER, map("order_id", "EPO000000000001", "webhook_type", "status_update", "timestamp", now())},
      {DIGITIZATION, map("request_id", "DIGI_RQ_A1B2C3D4E", "timestamp", now())},
    };
    for (Object[] c : cases) {
      String signature = sign(c[1]);
      JsonNode body = JSON.valueToTree(c[0]);
      assertTrue(WebhookReceiver.verifySignature(signature, body, SECRET).get("timestamp").asLong() > 0);
      // The SDK's verifier accepts the same token: the test signer produces a real JWT.
      Webhooks.verifySignature(body, signature, SECRET);
    }
  }

  @Test
  void webhookRejectsInvalidSignatures() throws Exception {
    String good = sign(appointmentClaims());
    Map<String, Object> noTimestamp = appointmentClaims();
    noTimestamp.remove("timestamp");
    Object[][] cases = {
      {null, APPOINTMENT},
      {"not-a-jwt", APPOINTMENT},
      {"a.b.c", APPOINTMENT},
      {sign(appointmentClaims(), "wrong-secret", "HS256"), APPOINTMENT},
      {sign(appointmentClaims(), SECRET, "HS512"), APPOINTMENT},
      {good.substring(0, good.length() - 2) + "AA", APPOINTMENT}, // tampered signature
      {sign(appointmentClaims("timestamp", now() - 600)), APPOINTMENT}, // stale
      {sign(appointmentClaims("timestamp", now() + 600)), APPOINTMENT}, // future
      {sign(appointmentClaims("timestamp", "now")), APPOINTMENT},
      {sign(noTimestamp), APPOINTMENT},
      {sign(appointmentClaims("appointment_id", "APT000000000")), APPOINTMENT},
      {sign(appointmentClaims("booking_id", "EKI0000000000000000000")), APPOINTMENT},
      {sign(map("timestamp", now())), map()}, // no ids at all
      {sign(map("order_id", "EPO000000000002", "timestamp", now())), ORDER},
      {sign(map("order_id", "EPO000000000001", "webhook_type", "post_billing", "timestamp", now())), ORDER},
      {sign(map("request_id", "DIGI_RQ_OTHER", "timestamp", now())), DIGITIZATION},
    };
    for (Object[] c : cases) {
      JsonNode body = JSON.valueToTree(c[1]);
      assertThrows(WebhookReceiver.InvalidSignature.class, () -> WebhookReceiver.verifySignature((String) c[0], body, SECRET), String.valueOf(c[0]));
    }
    Map<String, Object> tampered = new LinkedHashMap<>(APPOINTMENT);
    tampered.put("booking_id", "EKI_OTHER");
    assertThrows(WebhookReceiver.InvalidSignature.class, () -> WebhookReceiver.verifySignature(good, JSON.valueToTree(tampered), SECRET));
  }

  @Test
  void webhookHttpEndpoint() throws Exception {
    List<JsonNode> received = new CopyOnWriteArrayList<>();
    HttpServer server = WebhookReceiver.start(0, SECRET, received::add);
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hlthstack/webhooks";
      String body = JSON.writeValueAsString(APPOINTMENT);
      assertEquals(200, post(url, body, sign(appointmentClaims())));
      assertEquals(401, post(url, body, sign(appointmentClaims("timestamp", now() - 600))));
      assertEquals(401, post(url, body, null));
      assertEquals(400, post(url, "not json", sign(appointmentClaims())));
      for (int i = 0; i < 50 && received.isEmpty(); i++) Thread.sleep(20);
      assertEquals(List.of(JSON.valueToTree(APPOINTMENT)), received);
    } finally {
      server.stop(0);
    }
  }

  private static int post(String url, String body, String signature) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (signature != null) request.header("Signature", signature);
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
