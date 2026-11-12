package io.hlthstack;

import static io.hlthstack.TestSupport.list;
import static io.hlthstack.TestSupport.map;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Every operation's method, URL, query string and body; and that every operationId has a method. */
class EndpointsTest {
  private final MockServer server = new MockServer();
  private final Hlthstack client = TestSupport.client(server);

  @AfterEach
  void close() {
    server.close();
  }

  /** operationId (from the spec) -> client method. Keep in sync with the SDK guide's table. */
  static final Map<String, String> METHODS = new LinkedHashMap<>();

  static {
    String[] pairs = {
      "getAccessToken", "auth.getAccessToken",
      "listProviders", "appointments.listProviders",
      "listSlots", "appointments.listSlots",
      "createAppointment", "appointments.create",
      "getAppointment", "appointments.get",
      "rescheduleBooking", "appointments.rescheduleBooking",
      "cancelBooking", "appointments.cancelBooking",
      "rescheduleAppointment", "appointments.reschedule",
      "cancelAppointment", "appointments.cancel",
      "getAppointmentVoucher", "appointments.getVoucher",
      "getAppointmentReports", "appointments.getReports",
      "getAppointmentLabResults", "appointments.getLabResults",
      "listInclinicConsultationProviders", "consultations.listInclinicProviders",
      "listSpecialistConsultationProviders", "consultations.listSpecialistProviders",
      "createInclinicConsultation", "consultations.createInclinic",
      "createSpecialistConsultation", "consultations.createSpecialist",
      "getInclinicConsultation", "consultations.getInclinic",
      "getSpecialistConsultation", "consultations.getSpecialist",
      "rescheduleConsultation", "consultations.reschedule",
      "cancelConsultation", "consultations.cancel",
      "getConsultationSummaryAndPrescription", "consultations.getSummaryAndPrescription",
      "getConsultationVoucher", "consultations.getVoucher",
      "uploadConsultationDocuments", "consultations.uploadDocuments",
      "searchMedicines", "pharmacy.searchMedicines",
      "digitizePrescription", "pharmacy.digitizePrescription",
      "getPrescriptionDigitization", "pharmacy.getPrescriptionDigitization",
      "getCart", "pharmacy.getCart",
      "updateCart", "pharmacy.updateCart",
      "getFulfillmentOptions", "pharmacy.getFulfillmentOptions",
      "getOrderPaymentDetails", "pharmacy.getOrderPaymentDetails",
      "createOrder", "pharmacy.createOrder",
      "listOrders", "pharmacy.listOrders",
      "getOrder", "pharmacy.getOrder",
      "cancelOrder", "pharmacy.cancelOrder",
      "confirmOrder", "pharmacy.confirmOrder",
      "getCustomerWallet", "wallet.getCustomerWallet",
      "listCustomerPlans", "wallet.listCustomerPlans",
      "getPreRegistrationAccessToken", "preRegistration.getAccessToken",
      "createPreRegistrations", "preRegistration.create",
      "updatePreRegistrations", "preRegistration.update",
      "deactivatePreRegistrations", "preRegistration.deactivate",
      "getPreRegistrationStatus", "preRegistration.getStatus",
      "simulateAppointmentStatus", "testing.simulateAppointmentStatus",
      "simulateConsultationStatus", "testing.simulateConsultationStatus",
      "simulateOrderStatus", "testing.simulateOrderStatus",
    };
    for (int i = 0; i < pairs.length; i += 2) METHODS.put(pairs[i], pairs[i + 1]);
  }

  // ------------------------------------------------------------------ coverage

  @Test
  void hasOneMethodPerSpecOperation() {
    TreeSet<String> ids = new TreeSet<>();
    for (Iterator<JsonNode> items = TestSupport.spec().get("paths").elements(); items.hasNext(); ) {
      for (Iterator<JsonNode> ops = items.next().elements(); ops.hasNext(); ) {
        JsonNode op = ops.next();
        if (op.has("operationId")) ids.add(op.get("operationId").asText());
      }
    }
    assertEquals(45, ids.size());
    assertEquals(ids, new TreeSet<>(METHODS.keySet()));
    assertEquals(ids, new TreeSet<>(iterable(Validator.operations().fieldNames())));
  }

  @Test
  void everyMappedMethodExists() throws Exception {
    for (String target : METHODS.values()) {
      String[] parts = target.split("\\.");
      Method accessor = Hlthstack.class.getMethod(parts[0]);
      boolean found = false;
      for (Method m : accessor.getReturnType().getMethods()) found |= m.getName().equals(parts[1]);
      assertTrue(found, target);
    }
  }

  @Test
  void versionMatchesThePom() throws Exception {
    String pom = new String(Files.readAllBytes(Paths.get("pom.xml")), StandardCharsets.UTF_8);
    assertTrue(pom.contains("<artifactId>hlthstack</artifactId>\n  <version>" + Hlthstack.VERSION + "</version>"), Hlthstack.VERSION);
  }

  private static <T> List<T> iterable(Iterator<T> it) {
    List<T> out = new ArrayList<>();
    it.forEachRemaining(out::add);
    return out;
  }

  // ------------------------------------------------------------------ every endpoint

  static final class Case {
    final String operationId;
    final Consumer<Hlthstack> call;
    final String method;
    final String path;
    final String query;
    final String body;

    Case(String operationId, Consumer<Hlthstack> call, String method, String path, String query, String body) {
      this.operationId = operationId;
      this.call = call;
      this.method = method;
      this.path = path;
      this.query = query;
      this.body = body;
    }
  }

  static final Map<String, Object> CUSTOMER = map(
      "name", "Jane Doe", "dob", "1990-08-24", "mobile_number", "9999999999", "api_customer_id", "CUST1001", "gender", "female",
      "package_ids", list(1001));
  static final Map<String, Object> MEMBER = map("customer_details", map("api_customer_id", "CUST1001"));

  static List<Case> cases() {
    String apt = "APT1A2B3C4D5";
    List<Case> c = new ArrayList<>();
    // Appointments
    c.add(new Case("listProviders",
        h -> h.appointments().listProviders(map("service_name", "ahc", "package_ids", list(1001, 1002), "pincode", "500081", "page", 2)),
        "GET", "/api/providers", "service_name=ahc&package_ids=1001%2C1002&pincode=500081&page=2", null));
    c.add(new Case("listSlots",
        h -> h.appointments().listSlots(1001, "ahc", map("package_ids", "1001", "booking_date", "2030-01-15")),
        "GET", "/api/providers/1001/ahc/slots", "package_ids=1001&booking_date=2030-01-15", null));
    c.add(new Case("createAppointment",
        h -> h.appointments().create(map("service_name", "ahc", "appointment_type", "inclinic", "provider_id", 1001,
            "appointment_date_time", "2030-01-15T02:30:00Z", "customers", list(CUSTOMER))),
        "POST", "/api/appointments", null,
        "{\"service_name\":\"ahc\",\"appointment_type\":\"inclinic\",\"provider_id\":1001,\"appointment_date_time\":\"2030-01-15T02:30:00Z\","
            + "\"customers\":[{\"name\":\"Jane Doe\",\"dob\":\"1990-08-24\",\"mobile_number\":\"9999999999\",\"api_customer_id\":\"CUST1001\","
            + "\"gender\":\"female\",\"package_ids\":[1001]}]}"));
    c.add(new Case("getAppointment",
        h -> h.appointments().get(map("booking_id", "EKI0000000001700000000", "appointment_id", apt)),
        "GET", "/api/appointments", "booking_id=EKI0000000001700000000&appointment_id=APT1A2B3C4D5", null));
    c.add(new Case("rescheduleBooking",
        h -> h.appointments().rescheduleBooking("EKI0000000001700000000", map("appointment_date_time", "2030-01-16T02:30:00Z")),
        "PATCH", "/api/bookings/EKI0000000001700000000/reschedule", null, "{\"appointment_date_time\":\"2030-01-16T02:30:00Z\"}"));
    c.add(new Case("cancelBooking", h -> h.appointments().cancelBooking("EKI0000000001700000000"),
        "PATCH", "/api/bookings/EKI0000000001700000000/cancel", null, null));
    c.add(new Case("rescheduleAppointment",
        h -> h.appointments().reschedule(apt, map("appointment_date_time", "2030-01-16T02:30:00Z", "provider_id", 1002)),
        "PATCH", "/api/appointments/APT1A2B3C4D5/reschedule", null, "{\"appointment_date_time\":\"2030-01-16T02:30:00Z\",\"provider_id\":1002}"));
    c.add(new Case("cancelAppointment", h -> h.appointments().cancel(apt), "PATCH", "/api/appointments/APT1A2B3C4D5/cancel", null, null));
    c.add(new Case("getAppointmentVoucher", h -> h.appointments().getVoucher(apt), "GET", "/api/appointments/APT1A2B3C4D5/voucher", null, null));
    c.add(new Case("getAppointmentReports", h -> h.appointments().getReports(apt), "GET", "/api/appointments/APT1A2B3C4D5/reports", null, null));
    c.add(new Case("getAppointmentLabResults", h -> h.appointments().getLabResults(apt),
        "GET", "/api/appointments/APT1A2B3C4D5/lab_results", null, null));
    // Consultations
    c.add(new Case("listInclinicConsultationProviders",
        h -> h.consultations().listInclinicProviders(map("package_ids", "2001", "latitude", 17.44, "longitude", 78.38, "sort_by", "distance")),
        "GET", "/api/consultations/inclinic_consultation/providers", "package_ids=2001&latitude=17.44&longitude=78.38&sort_by=distance", null));
    c.add(new Case("listSpecialistConsultationProviders",
        h -> h.consultations().listSpecialistProviders(map("package_ids", "3001", "default_provider", true)),
        "GET", "/api/consultations/specialist_consultation/providers", "package_ids=3001&default_provider=true", null));
    c.add(new Case("listSlots",
        h -> h.consultations().listSlots(2001, "inclinic_consultation", map("package_ids", "2001")),
        "GET", "/api/providers/2001/inclinic_consultation/slots", "package_ids=2001", null));
    Map<String, Object> address = map("address_line_one", "1 Example Road", "address_line_two", "Kondapur", "city", "Hyderabad",
        "state", "Telangana", "country", "India", "pincode", "500084", "latitude", 17.47, "longitude", 78.35);
    c.add(new Case("createInclinicConsultation",
        h -> h.consultations().createInclinic(map("appointment_date_time", "2030-01-15T04:30:00.000Z", "service_mode", "at_provider_location",
            "provider_id", 2001, "customers", list(CUSTOMER), "address", address)),
        "POST", "/api/consultations/inclinic_consultation", null,
        "{\"appointment_date_time\":\"2030-01-15T04:30:00.000Z\",\"service_mode\":\"at_provider_location\",\"provider_id\":2001,"
            + "\"customers\":[{\"name\":\"Jane Doe\",\"dob\":\"1990-08-24\",\"mobile_number\":\"9999999999\",\"api_customer_id\":\"CUST1001\","
            + "\"gender\":\"female\",\"package_ids\":[1001]}],\"address\":{\"address_line_one\":\"1 Example Road\",\"address_line_two\":\"Kondapur\","
            + "\"city\":\"Hyderabad\",\"state\":\"Telangana\",\"country\":\"India\",\"pincode\":\"500084\",\"latitude\":17.47,\"longitude\":78.35}}"));
    c.add(new Case("createSpecialistConsultation",
        h -> h.consultations().createSpecialist(map("service_mode", "audio_call", "provider_id", 3001, "priority_consultation", true,
            "customers", list(CUSTOMER))),
        "POST", "/api/consultations/specialist_consultation", null,
        "{\"service_mode\":\"audio_call\",\"provider_id\":3001,\"priority_consultation\":true,\"customers\":[{\"name\":\"Jane Doe\","
            + "\"dob\":\"1990-08-24\",\"mobile_number\":\"9999999999\",\"api_customer_id\":\"CUST1001\",\"gender\":\"female\",\"package_ids\":[1001]}]}"));
    c.add(new Case("getInclinicConsultation", h -> h.consultations().getInclinic(apt),
        "GET", "/api/consultations/inclinic_consultation/APT1A2B3C4D5", null, null));
    c.add(new Case("getSpecialistConsultation", h -> h.consultations().getSpecialist(apt),
        "GET", "/api/consultations/specialist_consultation/APT1A2B3C4D5", null, null));
    c.add(new Case("rescheduleConsultation",
        h -> h.consultations().reschedule(apt, map("appointment_date_time", "2030-01-16T04:30:00.000Z")),
        "PATCH", "/api/consultations/APT1A2B3C4D5/reschedule", null, "{\"appointment_date_time\":\"2030-01-16T04:30:00.000Z\"}"));
    c.add(new Case("cancelConsultation", h -> h.consultations().cancel(apt), "PATCH", "/api/consultations/APT1A2B3C4D5/cancel", null, null));
    c.add(new Case("getConsultationSummaryAndPrescription", h -> h.consultations().getSummaryAndPrescription(apt),
        "GET", "/api/consultations/APT1A2B3C4D5/summary_and_prescription", null, null));
    c.add(new Case("getConsultationVoucher", h -> h.consultations().getVoucher(apt),
        "GET", "/api/consultations/APT1A2B3C4D5/voucher", null, null));
    c.add(new Case("uploadConsultationDocuments",
        h -> h.consultations().uploadDocuments(apt, List.of(ConsultationDocument.of("Report", FileInput.of(new byte[] {1}, "r.pdf")))),
        "POST", "/api/consultations/APT1A2B3C4D5/upload_documents", null, null));
    // Pharmacy
    c.add(new Case("searchMedicines", h -> h.pharmacy().searchMedicines(map("name", "paracetamol 500", "page", 1)),
        "GET", "/api/epharmacy/medicines/search", "name=paracetamol+500&page=1", null));
    c.add(new Case("digitizePrescription",
        h -> h.pharmacy().digitizePrescription(map("customers", list(CUSTOMER), "files", list(FileInput.of(new byte[] {1}, "rx.pdf")))),
        "POST", "/api/prescriptions/digitize", null, null));
    c.add(new Case("getPrescriptionDigitization", h -> h.pharmacy().getPrescriptionDigitization("DIGI_RQ_A1B2C3D4E"),
        "GET", "/api/prescriptions/digitize/DIGI_RQ_A1B2C3D4E", null, null));
    c.add(new Case("getCart", h -> h.pharmacy().getCart(with(MEMBER, "plan_id", "EXAMPLE_PLAN")),
        "GET", "/api/epharmacy/cart", "customer_details%5Bapi_customer_id%5D=CUST1001&plan_id=EXAMPLE_PLAN", null));
    c.add(new Case("updateCart", h -> h.pharmacy().updateCart(with(MEMBER, "products", list(map("id", 50001, "quantity", 2)))),
        "PATCH", "/api/epharmacy/cart", null, "{\"customer_details\":{\"api_customer_id\":\"CUST1001\"},\"products\":[{\"id\":50001,\"quantity\":2}]}"));
    c.add(new Case("getFulfillmentOptions", h -> h.pharmacy().getFulfillmentOptions(with(MEMBER, "cart_id", 701)),
        "GET", "/api/epharmacy/fulfillment_options", "customer_details%5Bapi_customer_id%5D=CUST1001&cart_id=701", null));
    c.add(new Case("getOrderPaymentDetails",
        h -> h.pharmacy().getOrderPaymentDetails(with(MEMBER, "cart_id", 701, "selected_delivery_option", "standard")),
        "POST", "/api/epharmacy/orders/payment_details", null,
        "{\"customer_details\":{\"api_customer_id\":\"CUST1001\"},\"cart_id\":701,\"selected_delivery_option\":\"standard\"}"));
    c.add(new Case("createOrder",
        h -> h.pharmacy().createOrder(with(MEMBER, "cart_id", 701, "delivery_option", "standard", "location_token", "OPAQUE_TOKEN")),
        "POST", "/api/epharmacy/orders", null,
        "{\"customer_details\":{\"api_customer_id\":\"CUST1001\"},\"cart_id\":701,\"delivery_option\":\"standard\",\"location_token\":\"OPAQUE_TOKEN\"}"));
    c.add(new Case("listOrders", h -> h.pharmacy().listOrders(with(MEMBER, "page", 1, "per_page", 10)),
        "GET", "/api/epharmacy/orders", "customer_details%5Bapi_customer_id%5D=CUST1001&page=1&per_page=10", null));
    c.add(new Case("getOrder", h -> h.pharmacy().getOrder("EPO000000000001", MEMBER),
        "GET", "/api/epharmacy/orders/EPO000000000001", "customer_details%5Bapi_customer_id%5D=CUST1001", null));
    c.add(new Case("cancelOrder", h -> h.pharmacy().cancelOrder("EPO000000000001", with(MEMBER, "reason", "Ordered by mistake")),
        "PATCH", "/api/epharmacy/orders/EPO000000000001/cancel", null,
        "{\"customer_details\":{\"api_customer_id\":\"CUST1001\"},\"reason\":\"Ordered by mistake\"}"));
    c.add(new Case("confirmOrder",
        h -> h.pharmacy().confirmOrder("EPO000000000003", with(MEMBER, "partner_collected_amount", "261.0", "partner_payment_reference", "TXN-0001")),
        "POST", "/api/epharmacy/orders/EPO000000000003/confirm", null,
        "{\"customer_details\":{\"api_customer_id\":\"CUST1001\"},\"partner_collected_amount\":\"261.0\",\"partner_payment_reference\":\"TXN-0001\"}"));
    // Wallet
    c.add(new Case("getCustomerWallet", h -> h.wallet().getCustomerWallet("example-co", map("api_customer_id", "CUST1001", "plan_id", "EXAMPLE_PLAN")),
        "GET", "/api/example-co/customers/wallet", "api_customer_id=CUST1001&plan_id=EXAMPLE_PLAN", null));
    c.add(new Case("listCustomerPlans", h -> h.wallet().listCustomerPlans("example co", map("customer_id", "EKI000000001")),
        "GET", "/api/example%20co/customers/plans", "customer_id=EKI000000001", null));
    // Pre-registration
    c.add(new Case("createPreRegistrations", h -> h.preRegistration().create(map("employees", list())),
        "POST", "/v1/corporate/pre_registrations", null, "{\"employees\":[]}"));
    c.add(new Case("updatePreRegistrations",
        h -> h.preRegistration().update(map("employees", list(map("email", "jane.doe@example.com", "grade", "C")))),
        "POST", "/v1/corporate/pre_registrations/update", null, "{\"employees\":[{\"email\":\"jane.doe@example.com\",\"grade\":\"C\"}]}"));
    c.add(new Case("deactivatePreRegistrations", h -> h.preRegistration().deactivate(map("employees", list("jane.doe@example.com"))),
        "POST", "/v1/corporate/pre_registrations/deactivate", null, "{\"employees\":[\"jane.doe@example.com\"]}"));
    c.add(new Case("getPreRegistrationStatus", h -> h.preRegistration().getStatus("3f2b8c1e-0d4a-4c5e-9b7a-2e6f1d8c9a01"),
        "GET", "/v1/corporate/pre_registrations/3f2b8c1e-0d4a-4c5e-9b7a-2e6f1d8c9a01/status", null, null));
    // Testing (staging)
    c.add(new Case("simulateAppointmentStatus", h -> h.testing().simulateAppointmentStatus(apt, map("status", "confirm")),
        "POST", "/api/appointments/APT1A2B3C4D5/update_status", null, "{\"status\":\"confirm\"}"));
    c.add(new Case("simulateConsultationStatus",
        h -> h.testing().simulateConsultationStatus(apt, map("status", "reschedule", "reschedule_time", "2030-01-16T04:30:00Z")),
        "PATCH", "/api/consultations/APT1A2B3C4D5/update_status", null, "{\"status\":\"reschedule\",\"reschedule_time\":\"2030-01-16T04:30:00Z\"}"));
    c.add(new Case("simulateOrderStatus", h -> h.testing().simulateOrderStatus("EPO000000000001", with(MEMBER, "status", "in_progress")),
        "PATCH", "/api/epharmacy/orders/EPO000000000001/update_status", null,
        "{\"customer_details\":{\"api_customer_id\":\"CUST1001\"},\"status\":\"in_progress\"}"));
    return c;
  }

  static Map<String, Object> with(Map<String, Object> base, Object... more) {
    Map<String, Object> out = new LinkedHashMap<>(base);
    out.putAll(map(more));
    return out;
  }

  @Test
  void everyApiOperationHasACase() {
    TreeSet<String> covered = new TreeSet<>();
    for (Case c : cases()) covered.add(c.operationId);
    TreeSet<String> expected = new TreeSet<>(METHODS.keySet());
    expected.remove("getAccessToken");
    expected.remove("getPreRegistrationAccessToken");
    assertEquals(expected, covered);
  }

  @TestFactory
  Stream<DynamicTest> sendsEachOperation() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Case c : cases()) {
      tests.add(dynamicTest(c.operationId + " " + c.path, () -> {
        try (MockServer mock = new MockServer()) {
          c.call.accept(TestSupport.client(mock));
          List<MockServer.Request> calls = mock.apiCalls();
          assertEquals(1, calls.size());
          MockServer.Request r = calls.get(0);
          assertEquals(c.method, r.method);
          assertEquals(c.path, r.rawPath);
          assertEquals(c.query, r.rawQuery);
          JsonNode op = Validator.operations().get(c.operationId);
          assertEquals(op.get("method").asText(), r.method);
          assertTrue(r.path.matches(op.get("path").asText().replaceAll("\\{[^}]+}", "[^/]+")), r.path);
          if (c.body != null) {
            assertEquals("application/json", r.header("content-type"));
            assertEquals(TestSupport.json(c.body), r.json());
          } else if (r.header("content-type") == null || !r.header("content-type").startsWith("multipart/")) {
            assertEquals(0, r.body.length);
          }
        }
      }));
    }
    return tests.stream();
  }

  // ------------------------------------------------------------------ responses

  @Test
  void returnsJsonTreesBytesAndEmptyObjects() {
    server.reply("GET", "/api/appointments", 200, map("appointments", list(map("appointment_id", "APT1A2B3C4D5", "amount", 1499.5))));
    JsonNode details = client.appointments().get(map("booking_id", "EKI0000000001700000000"));
    assertEquals("APT1A2B3C4D5", details.at("/appointments/0/appointment_id").asText());
    assertEquals("1499.5", details.at("/appointments/0/amount").decimalValue().toPlainString());

    server.reply("GET", "/api/appointments/APT1A2B3C4D5/voucher", 200, "%PDF-1.4 voucher".getBytes(StandardCharsets.UTF_8));
    assertArrayEquals("%PDF-1.4 voucher".getBytes(StandardCharsets.UTF_8), client.appointments().getVoucher("APT1A2B3C4D5"));
    MockServer.Request voucher = server.calls("/api/appointments/APT1A2B3C4D5/voucher").get(0);
    assertEquals("application/pdf, application/json", voucher.header("accept"));

    server.reply("PATCH", "/api/appointments/APT1A2B3C4D5/cancel", 200, null);
    JsonNode empty = client.appointments().cancel("APT1A2B3C4D5");
    assertTrue(empty.isObject() && empty.size() == 0);
  }

  @Test
  void sendsBodyValuesExactly() {
    client.appointments().create(map("service_name", "ahc", "appointment_type", "home_collection", "provider_id", 1001L,
        "appointment_date_time", "2030-01-15T02:30:00Z", "customers", list(CUSTOMER),
        "address", map("address_line_one", "1 Example Road", "address_line_two", "Madhapur", "city", "Hyderabad", "state", "Telangana",
            "country", "India", "pincode", "500081", "latitude", new java.math.BigDecimal("17.4437"), "longitude", 78.386)));
    JsonNode sent = server.calls("/api/appointments").get(0).json();
    assertEquals("17.4437", sent.at("/address/latitude").decimalValue().toPlainString());
    assertEquals("78.386", sent.at("/address/longitude").decimalValue().toPlainString());
    assertNull(sent.get("plan_id"));
  }

  @Test
  void stagingSimulatorsRefuseInProduction() {
    Hlthstack production = TestSupport.builder(server).environment(Environment.PRODUCTION).build();
    IllegalStateException e = assertThrows(
        IllegalStateException.class, () -> production.testing().simulateAppointmentStatus("APT1A2B3C4D5", map("status", "confirm")));
    assertTrue(e.getMessage().contains("only on staging"));
    assertThrows(IllegalStateException.class, () -> production.testing().simulateConsultationStatus("APT1A2B3C4D5", map("status", "confirm")));
    assertThrows(IllegalStateException.class, () -> production.testing().simulateOrderStatus("EPO000000000001", map("status", "in_progress")));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void rejectsNullArguments() {
    assertThrows(IllegalArgumentException.class, () -> client.appointments().cancel(null));
    assertThrows(IllegalArgumentException.class, () -> client.appointments().listProviders(null));
    assertThrows(IllegalArgumentException.class, () -> client.appointments().create(null));
    assertTrue(server.requests().isEmpty());
  }
}
