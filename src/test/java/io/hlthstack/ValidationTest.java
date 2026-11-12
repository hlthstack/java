package io.hlthstack;

import static io.hlthstack.TestSupport.list;
import static io.hlthstack.TestSupport.map;
import static io.hlthstack.TestSupport.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ValidationTest {
  /** The validator's clock here: 10 Jan 2026, 12:00 IST. */
  static final Instant NOW = Instant.parse("2026-01-10T06:30:00Z");

  // ------------------------------------------------------------------ helpers

  /** A request's inputs, as the namespace methods hand them to the validator. */
  static final class Input {
    JsonNode params;
    JsonNode query;
    JsonNode body;
    JsonNode form;

    Input params(Map<String, ?> v) {
      params = Json.toTree(v);
      return this;
    }

    Input query(Map<String, ?> v) {
      query = Json.toTree(v);
      return this;
    }

    Input body(Object v) {
      body = Json.toTree(v);
      return this;
    }

    Input form(Map<String, ?> v) {
      form = Transport.formTree(v);
      return this;
    }
  }

  static Input in() {
    return new Input();
  }

  static List<ValidationIssue> issues(String operation, Input input) {
    return issues(operation, input, NOW);
  }

  static List<ValidationIssue> issues(String operation, Input input, Instant now) {
    return Validator.validate(operation, input.params, input.query, input.body, input.form, now);
  }

  /** "path rule" pairs, for compact assertions. */
  static List<String> problems(String operation, Input input) {
    return problems(operation, input, NOW);
  }

  static List<String> problems(String operation, Input input, Instant now) {
    List<String> out = new ArrayList<>();
    for (ValidationIssue i : issues(operation, input, now)) out.add(i.path() + " " + i.rule());
    return out;
  }

  static ValidationIssue issue(String path, String rule, String message) {
    return new ValidationIssue(path, message, rule);
  }

  static JsonNode operation(String operationId) {
    for (Iterator<JsonNode> items = spec().get("paths").elements(); items.hasNext(); ) {
      JsonNode item = items.next();
      for (Iterator<JsonNode> ops = item.elements(); ops.hasNext(); ) {
        JsonNode op = ops.next();
        if (operationId.equals(op.path("operationId").asText())) return op;
      }
    }
    throw new AssertionError("no operation " + operationId);
  }

  /** A mutable copy of a spec request-body example. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> bodyExample(String operationId, String name) {
    JsonNode content = operation(operationId).path("requestBody").path("content").path("application/json");
    JsonNode example = name.equals("example") ? content.get("example") : content.path("examples").path(name).get("value");
    if (example == null) throw new AssertionError("no " + name + " for " + operationId);
    return Json.MAPPER.convertValue(example, LinkedHashMap.class);
  }

  static Map<String, Object> bodyExample(String operationId) {
    return bodyExample(operationId, "example");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Object> arr(Object value) {
    return (List<Object>) value;
  }

  static Map<String, Object> customer() {
    return map(
        "name", "Jane Doe", "dob", "1990-08-24", "mobile_number", "9999999999", "api_customer_id", "CUST1001",
        "gender", "female", "package_ids", list(1001));
  }

  static Map<String, Object> with(Map<String, Object> base, Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>(base);
    out.putAll(map(keysAndValues));
    return out;
  }

  static Map<String, Object> employee() {
    return obj(arr(bodyExample("createPreRegistrations").get("employees")).get(0));
  }

  static FileInput pdf() {
    return FileInput.of("%PDF-1.4".getBytes(), "rx.pdf");
  }

  // ------------------------------------------------------------------ spec examples

  @TestFactory
  Stream<DynamicTest> specRequestBodyExamplesPass() {
    Map<String, Object> params =
        map("booking_id", "EKI0000000001700000000", "appointment_id", "APT1A2B3C4D5", "order_id", "EPO000000000001");
    List<DynamicTest> tests = new ArrayList<>();
    for (Iterator<JsonNode> items = spec().get("paths").elements(); items.hasNext(); ) {
      for (Iterator<JsonNode> ops = items.next().elements(); ops.hasNext(); ) {
        JsonNode op = ops.next();
        JsonNode content = op.path("requestBody").path("content").path("application/json");
        String id = op.path("operationId").asText();
        List<String> names = new ArrayList<>();
        if (content.has("example")) names.add("example");
        content.path("examples").fieldNames().forEachRemaining(names::add);
        for (String name : names) {
          tests.add(dynamicTest(id + " " + name, () ->
              assertEquals(List.of(), issues(id, in().params(params).body(bodyExample(id, name))))));
        }
      }
    }
    assertEquals(20, tests.size());
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> specParameterExamplesPass() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Iterator<JsonNode> items = spec().get("paths").elements(); items.hasNext(); ) {
      JsonNode item = items.next();
      for (Iterator<Map.Entry<String, JsonNode>> ops = item.fields(); ops.hasNext(); ) {
        Map.Entry<String, JsonNode> entry = ops.next();
        if (!entry.getValue().has("operationId")) continue;
        String id = entry.getValue().get("operationId").asText();
        List<JsonNode> parameters = new ArrayList<>();
        item.path("parameters").forEach(parameters::add);
        entry.getValue().path("parameters").forEach(parameters::add);
        for (JsonNode raw : parameters) {
          JsonNode p = raw.has("$ref")
              ? spec().get("components").get("parameters").get(raw.get("$ref").asText().replaceAll(".*/", ""))
              : raw;
          String location = p.path("in").asText();
          if (!p.has("example") || !(location.equals("query") || location.equals("path"))) continue;
          String name = p.get("name").asText();
          Map<String, Object> value = map(name, Json.MAPPER.convertValue(p.get("example"), Object.class));
          tests.add(dynamicTest(id + " " + location + " " + name, () -> {
            Input input = location.equals("path") ? in().params(value) : in().query(value);
            // Only this parameter is set, so ignore problems reported for the others.
            List<ValidationIssue> own = new ArrayList<>();
            for (ValidationIssue i : issues(id, input)) {
              if (i.path().equals(name) && !i.rule().equals("required_if")) own.add(i);
            }
            assertEquals(List.of(), own);
          }));
        }
      }
    }
    assertTrue(tests.size() > 10);
    return tests.stream();
  }

  // ------------------------------------------------------------------ schema keywords

  @Nested
  class SchemaKeywords {
    @Test
    void requiredReportsEveryMissingFieldWithItsPath() {
      Map<String, Object> body = map("appointment_type", "inclinic", "service_name", "ahc", "customers", list(map("name", "Jane Doe")));
      assertEquals(
          List.of(
              "provider_id required", "appointment_date_time required", "customers[0].api_customer_id required",
              "customers[0].mobile_number required", "customers[0].gender required", "customers[0].dob required",
              "customers[0].package_ids required"),
          problems("createAppointment", in().body(body)));
    }

    @Test
    void requiredAMissingRequestBody() {
      assertEquals(List.of(issue("", "required", "a request body is required")), issues("createAppointment", in()));
    }

    @Test
    void typeIncludingTypeArraysAndNull() {
      Map<String, Object> body = with(bodyExample("createAppointment"), "provider_id", true, "customers", "CUST1001");
      assertEquals(List.of("provider_id type", "customers type"), problems("createAppointment", in().body(body)));
      assertEquals(List.of(), issues("createAppointment", in().body(with(body, "provider_id", "1001", "customers", list(customer())))));
      // [string, null]: null is fine for last_name, not for first_name (type string).
      Map<String, Object> row = with(employee(), "last_name", null);
      assertEquals(List.of(), issues("createPreRegistrations", in().body(map("employees", list(row)))));
      assertEquals(
          List.of("employees[0].first_name type"),
          problems("createPreRegistrations", in().body(map("employees", list(with(row, "first_name", null))))));
    }

    @Test
    void typeQueryAndPathValuesTravelAsText() {
      assertEquals(List.of(), issues("listProviders", in().query(map("service_name", "ahc", "package_ids", 1001, "pincode", 500081))));
      assertEquals(
          List.of("pincode pattern"),
          problems("listProviders", in().query(map("service_name", "ahc", "package_ids", "1001", "pincode", 50008))));
      // Text that reads as the declared number, integer or boolean is accepted, as the server parses it.
      assertEquals(
          List.of(),
          issues("listProviders", in().query(map(
              "service_name", "ahc", "package_ids", "1001", "latitude", "17.44", "longitude", "78.38", "page", "2",
              "auto_allocate_provider", "true"))));
      assertEquals(
          List.of("latitude type", "page type"),
          problems("listProviders", in().query(map(
              "service_name", "ahc", "package_ids", "1001", "pincode", "500081", "latitude", "north", "page", "2.5"))));
      assertEquals(List.of(), issues("listSlots", in().params(map("provider_id", "1001", "benefit_type", "ahc")).query(map("package_ids", "1001"))));
    }

    @Test
    void typeNullQueryValuesAreNotSent() {
      assertEquals(
          List.of(),
          issues("listProviders", in().query(map("service_name", "ahc", "package_ids", "1001", "pincode", "500081", "plan_id", null))));
    }

    @Test
    void enumeration() {
      Map<String, Object> body = with(bodyExample("createAppointment"), "appointment_type", "home");
      assertEquals(
          List.of(issue("appointment_type", "enum", "must be one of \"home_collection\", \"inclinic\"")),
          issues("createAppointment", in().body(body)));
    }

    @Test
    void patternAnchoredAndUnanchoredAsTheServerMatchesThem() {
      assertEquals(List.of("appointment_id pattern"), problems("cancelAppointment", in().params(map("appointment_id", "APT123"))));
      // The server's own check is unanchored: surrounding characters pass.
      assertEquals(List.of(), issues("cancelAppointment", in().params(map("appointment_id", "xAPT1A2B3C4D5x"))));
      Map<String, Object> body = bodyExample("createAppointment");
      obj(arr(body.get("customers")).get(0)).put("api_customer_id", "CUST-1001");
      assertEquals(List.of("customers[0].api_customer_id pattern"), problems("createAppointment", in().body(body)));
    }

    @Test
    void patternCharacterClassesForCaseInsensitiveValuesAndBlanks() {
      Map<String, Object> query = map("package_ids", "2001", "pincode", "500081");
      assertEquals(List.of(), issues("listInclinicConsultationProviders", in().query(with(query, "gender", "fEmAlE"))));
      assertEquals(List.of("gender pattern"), problems("listInclinicConsultationProviders", in().query(with(query, "gender", "F"))));
      assertEquals(List.of(), issues("createPreRegistrations", in().body(map("employees", list(with(employee(), "mobile_number", "  "))))));
      assertEquals(
          List.of("employees[0].mobile_number pattern"),
          problems("createPreRegistrations", in().body(map("employees", list(with(employee(), "mobile_number", "99999"))))));
    }

    @Test
    void minLengthAndMaxLength() {
      assertEquals(List.of("name minLength", "name pattern"), problems("searchMedicines", in().query(map("name", "ab"))));
      assertEquals(List.of("name pattern"), problems("searchMedicines", in().query(map("name", " ab "))));
      assertEquals(List.of(), issues("searchMedicines", in().query(map("name", "paracetamol"))));
      Map<String, Object> body = bodyExample("createAppointment");
      obj(body.get("address")).put("address_line_one", "x".repeat(255));
      assertEquals(List.of("address.address_line_one maxLength"), problems("createAppointment", in().body(body)));
    }

    @Test
    void minimumMaximumAndExclusiveMinimum() {
      assertEquals(
          List.of("per_page maximum"),
          problems("listProviders", in().query(map("service_name", "ahc", "package_ids", "1001", "pincode", "500081", "per_page", 51))));
      assertEquals(
          List.of("customer_plan_id minimum"),
          problems("getCustomerWallet", in().params(map("company_slug", "example-co")).query(map("customer_id", "X", "customer_plan_id", -1))));
      Map<String, Object> body = bodyExample("createAppointment");
      obj(body.get("address")).put("pincode", 99999); // integer pincodes: 100000-999999
      assertEquals(List.of("address.pincode minimum"), problems("createAppointment", in().body(body)));
      Map<String, Object> confirm = with(bodyExample("confirmOrder"), "partner_collected_amount", 0);
      assertEquals(
          List.of("partner_collected_amount exclusiveMinimum", "partner_collected_amount positive_decimal"),
          problems("confirmOrder", in().params(map("order_id", "EPO000000000001")).body(confirm)));
    }

    @Test
    void minItemsAndMaxItems() {
      assertEquals(
          List.of("customers minItems"),
          problems("createAppointment", in().body(with(bodyExample("createAppointment"), "customers", list()))));
      List<Object> urls = new ArrayList<>();
      for (int i = 0; i < 11; i++) urls.add("https://files.example.com/" + i + ".pdf");
      Map<String, Object> cart = with(bodyExample("updateCart", "prescriptionUrl"), "prescription_urls", urls);
      assertEquals(List.of("prescription_urls maxItems"), problems("updateCart", in().body(cart)));
    }

    @Test
    void formatDateDateTimeUriAndUuid() {
      Map<String, Object> body = bodyExample("createAppointment");
      body.put("appointment_date_time", "tomorrow");
      obj(arr(body.get("customers")).get(0)).put("dob", "1990-02-30");
      assertEquals(List.of("appointment_date_time format", "customers[0].dob format"), problems("createAppointment", in().body(body)));
      // A date-time without an offset is accepted (the API reads it as IST), as are a space and no seconds.
      for (String ok : new String[] {"2026-01-15T08:00:00", "2026-01-15 08:00", "2026-01-15T08:00:00.000+0530", "2026-01-15t02:30:00z"}) {
        assertEquals(List.of(), issues("createAppointment", in().body(with(bodyExample("createAppointment"), "appointment_date_time", ok))), ok);
      }
      for (String bad : new String[] {"2026-01-15T24:00:00Z", "2026-13-01T08:00:00Z", "2026-01-15"}) {
        assertEquals(
            List.of("appointment_date_time format"),
            problems("createAppointment", in().body(with(bodyExample("createAppointment"), "appointment_date_time", bad))), bad);
      }
      Map<String, Object> cart = with(bodyExample("updateCart", "prescriptionUrl"), "prescription_urls", list("not a uri"));
      assertEquals(List.of("prescription_urls[0] pattern", "prescription_urls[0] format"), problems("updateCart", in().body(cart)));
      assertEquals(List.of("request_id format"), problems("getPreRegistrationStatus", in().params(map("request_id", "abc"))));
      assertEquals(
          List.of(),
          issues("getPreRegistrationStatus", in().params(map("request_id", "3f2b8c1e-0d4a-4c5e-9b7a-2e6f1d8c9a01"))));
    }

    @Test
    void allOfAnInclinicConsultationAddressNeedsAPincode() {
      Map<String, Object> body = bodyExample("createInclinicConsultation");
      obj(body.get("address")).remove("pincode");
      assertEquals(List.of("address.pincode required"), problems("createInclinicConsultation", in().body(body)));
      // The same address without a pincode is fine for a booking (it is derived from the coordinates).
      Map<String, Object> booking = bodyExample("createAppointment");
      obj(booking.get("address")).remove("pincode");
      assertEquals(List.of(), issues("createAppointment", in().body(booking)));
    }

    @Test
    void oneOfAndAnyOfVoluntaryPlanEntries() {
      Map<String, Object> ok = with(
          employee(), "voluntary_plan_ids",
          list("TOPUP", map("plan_id", "TOPUP", "additional", "TRUE"), map("plan_id", "X", "additional", 1)));
      assertEquals(List.of(), issues("createPreRegistrations", in().body(map("employees", list(ok)))));
      Map<String, Object> bad = with(
          employee(), "voluntary_plan_ids",
          list(5, map("additional", true), map("plan_id", "TOPUP", "additional", "yes"), map("plan_id", "X", "additional", 2)));
      assertEquals(
          List.of(
              "employees[0].voluntary_plan_ids[0] oneOf", "employees[0].voluntary_plan_ids[1].plan_id required",
              "employees[0].voluntary_plan_ids[2].additional pattern", "employees[0].voluntary_plan_ids[3].additional enum"),
          problems("createPreRegistrations", in().body(map("employees", list(bad)))));
    }

    @Test
    void filePartsOfMultipartBodies() {
      Input input = in().form(map("customers", list(customer()), "files", list(pdf(), "not-a-file")));
      List<ValidationIssue> found = issues("digitizePrescription", input);
      assertEquals(List.of("files[1] type"), problems("digitizePrescription", input));
      assertEquals("must be a file (FileInput), got string", found.get(0).message());
    }
  }

  // ------------------------------------------------------------------ rule kinds

  @Nested
  class Rules {
    @Test
    void requiredIfAFieldRequiredWhenAnotherIsNotSent() {
      Map<String, Object> query = map("service_name", "ahc", "package_ids", "1001");
      assertEquals(
          List.of(
              issue("latitude", "required_if", "is required when pincode is not sent"),
              issue("longitude", "required_if", "is required when pincode is not sent")),
          issues("listProviders", in().query(query)));
      assertEquals(List.of(), issues("listProviders", in().query(with(query, "latitude", 17.44, "longitude", 78.38))));
      assertEquals(List.of(), issues("listProviders", in().query(with(query, "pincode", "500081"))));
    }

    @Test
    void requiredIfWhenAnotherHasAValueAndUnlessOneIsTrue() {
      assertEquals(
          List.of("selected_chemist_id required_if"),
          problems("getOrderPaymentDetails", in().body(with(bodyExample("getOrderPaymentDetails"), "selected_delivery_option", "store_pickup"))));
      Map<String, Object> specialist = bodyExample("createSpecialistConsultation");
      specialist.remove("appointment_date_time");
      List<ValidationIssue> found = issues("createSpecialistConsultation", in().body(specialist));
      assertEquals(List.of(issue("appointment_date_time", "required_if", "is required unless priority_consultation is true")), found);
      assertEquals(List.of(), issues("createSpecialistConsultation", in().body(with(specialist, "priority_consultation", true))));
    }

    @Test
    void allowedOnlyIf() {
      Map<String, Object> query = map("service_name", "ahc", "package_ids", "1001", "pincode", "500081");
      String message = "is accepted only when service_name is inclinic_consultation";
      assertEquals(
          List.of(issue("sort_by", "allowed_only_if", message), issue("gender", "allowed_only_if", message)),
          issues("listProviders", in().query(with(query, "sort_by", "fee", "gender", "Male"))));
      assertEquals(
          List.of(),
          issues("listProviders", in().query(with(query, "service_name", "inclinic_consultation", "sort_by", "fee", "gender", "Male"))));
    }

    @Test
    void atLeastOneOfAtTheTopLevelPerArrayItemAndInsideAnOptionalObject() {
      assertEquals(
          List.of(issue("", "at_least_one_of", "send at least one of api_customer_id, employee_id")),
          issues("listCustomerAppointments", in().query(map("status", "confirmed"))));
      Map<String, Object> dependant = map(
          "first_name", "John", "last_name", "Doe", "date_of_birth", "", "gender", "", "mobile_number", "", "relation", "",
          "guardian_email", " ");
      assertEquals(
          List.of(issue("dependants[0]", "at_least_one_of", "send guardian_email, or company_id and guardian_employee_code")),
          issues("createPreRegistrations", in().body(map("employees", list(), "dependants", list(dependant)))));
      // A group counts only when complete: guardian_employee_code needs company_id.
      assertEquals(
          List.of("dependants[0] at_least_one_of"),
          problems("createPreRegistrations", in().body(map("dependants", list(with(dependant, "guardian_employee_code", "EMP1001"))))));
      assertEquals(
          List.of(),
          issues("createPreRegistrations", in().body(map("dependants", list(with(dependant, "guardian_employee_code", "EMP1001", "company_id", "1001"))))));
      Map<String, Object> cart = bodyExample("updateCart", "address");
      obj(cart.get("address")).remove("pincode");
      assertEquals(List.of("address at_least_one_of"), problems("updateCart", in().body(cart)));
      obj(cart.get("address")).put("zip_code", "380005");
      assertEquals(List.of(), issues("updateCart", in().body(cart)));
    }

    @Test
    void mutuallyExclusive() {
      Map<String, Object> cart = with(bodyExample("updateCart", "address"), "address_id", 12);
      assertEquals(List.of(issue("", "mutually_exclusive", "send only one of address, address_id")), issues("updateCart", in().body(cart)));
    }

    @Test
    void uniqueBy() {
      Map<String, Object> body = with(bodyExample("createAppointment"), "customers", list(customer(), with(customer(), "name", "John Doe")));
      assertEquals(
          List.of(issue("customers[1].api_customer_id", "unique_by", "duplicates customers[0].api_customer_id (\"CUST1001\")")),
          issues("createAppointment", in().body(body)));
      Map<String, Object> cart = with(
          bodyExample("updateCart", "products"), "products", list(map("id", 50001, "quantity", 1), map("id", 50001, "quantity", 2)));
      assertEquals(List.of(issue("products[1].id", "unique_by", "duplicates products[0].id (50001)")), issues("updateCart", in().body(cart)));
    }

    private Input dob(String dob) {
      Map<String, Object> body = bodyExample("createAppointment");
      obj(arr(body.get("customers")).get(0)).put("dob", dob);
      return in().body(body);
    }

    @Test
    void relativeDateAtLeastOneYearOldOnTheApiCalendar() {
      // 10 Jan 2026 12:00 IST: the latest accepted dob is 2025-01-10.
      assertEquals(List.of(), issues("createAppointment", dob("2025-01-10")));
      assertEquals(
          List.of(issue("customers[0].dob", "relative_date", "must be on or before 2025-01-10 (at least 1 year(s) ago)")),
          issues("createAppointment", dob("2025-01-11")));
      // 9 Jan 2026 20:00 UTC is already 10 Jan in IST.
      assertEquals(List.of(), issues("createAppointment", dob("2025-01-10"), Instant.parse("2026-01-09T20:00:00Z")));
      assertEquals(
          List.of("customers[0].dob relative_date"),
          problems("createAppointment", dob("2025-01-10"), Instant.parse("2026-01-09T18:00:00Z")));
      // 29 Feb falls back to 28 Feb a year later.
      assertEquals(List.of(), issues("createAppointment", dob("2027-02-28"), Instant.parse("2028-02-29T06:30:00Z")));
    }

    @Test
    void relativeDateNotAfterTodayInAnyAcceptedFormatSkippingBlanks() {
      assertEquals(List.of(), issues("createPreRegistrations", in().body(map("employees", list(with(employee(), "date_of_birth", "10-01-2026"))))));
      assertEquals(List.of(), issues("createPreRegistrations", in().body(map("employees", list(with(employee(), "date_of_birth", ""))))));
      assertEquals(
          List.of(issue("employees[0].date_of_birth", "relative_date", "must not be after today (2026-01-10)")),
          issues("createPreRegistrations", in().body(map("employees", list(with(employee(), "date_of_birth", "11/01/2026"))))));
      Input form = in().form(map("customers", list(with(customer(), "dob", "2026-01-11")), "files", list(pdf())));
      assertEquals(List.of("customers[0].dob relative_date"), problems("digitizePrescription", form));
    }

    private Input at(String time) {
      return in().params(map("appointment_id", "APT1A2B3C4D5")).body(map("appointment_date_time", time));
    }

    @Test
    void relativeDateLaterThanNowReadingATimeWithoutAnOffsetAsIst() {
      assertEquals(List.of(), issues("rescheduleConsultation", at("2026-01-10T06:31:00Z")));
      assertEquals(List.of("appointment_date_time relative_date"), problems("rescheduleConsultation", at("2026-01-10T06:30:00Z")));
      // 12:15 IST is 06:45 UTC: later than now. 11:45 IST is earlier (read as UTC it would be later).
      assertEquals(List.of(), issues("rescheduleConsultation", at("2026-01-10T12:15:00")));
      assertEquals(
          List.of(issue(
              "appointment_date_time", "relative_date",
              "must be later than now (2026-01-10T06:30:00Z); without an offset it is read as +05:30")),
          issues("rescheduleConsultation", at("2026-01-10T11:45:00")));
      assertEquals(List.of(), issues("rescheduleConsultation", at("2026-01-10T12:15:00+0530")));
      // Unless priority_consultation is true.
      Map<String, Object> specialist = with(bodyExample("createSpecialistConsultation"), "appointment_date_time", "2026-01-01T04:30:00Z");
      assertEquals(List.of("appointment_date_time relative_date"), problems("createSpecialistConsultation", in().body(specialist)));
      assertEquals(List.of(), issues("createSpecialistConsultation", in().body(with(specialist, "priority_consultation", true))));
    }

    private Input booking(String appointmentType, Object... address) {
      Map<String, Object> body = bodyExample("createAppointment");
      body.put("appointment_type", appointmentType);
      obj(body.get("address")).putAll(map(address));
      return in().body(body);
    }

    @Test
    void noConflictingPincode() {
      assertEquals(List.of(), issues("createAppointment", booking("home_collection", "address_line_two", "Madhapur 500081")));
      assertEquals(
          List.of(issue("address.city", "no_conflicting_pincode", "contains the pincode 500032, which differs from the address pincode 500081")),
          issues("createAppointment", booking("home_collection", "city", "Hyderabad 500032")));
      // Other countries and longer numbers are not checked.
      assertEquals(List.of(), issues("createAppointment", booking("home_collection", "country", "Nepal", "city", "Kathmandu 446600")));
      assertEquals(List.of(), issues("createAppointment", booking("home_collection", "address_line_one", "Plot 5000321")));
      // createAppointment checks it for home collection only.
      assertEquals(List.of(), issues("createAppointment", booking("inclinic", "city", "Hyderabad 500032")));
      // A cart address has no country (blank counts as India), and may carry the pincode as zip_code.
      Map<String, Object> cart = bodyExample("updateCart", "address");
      obj(cart.get("address")).put("line1", "Road 380001");
      assertEquals(List.of("address.line1 no_conflicting_pincode"), problems("updateCart", in().body(cart)));
      obj(cart.get("address")).put("line1", "1 Example Road");
      obj(cart.get("address")).remove("pincode");
      obj(cart.get("address")).putAll(map("zip_code", "380005", "line2", "Motera 380005"));
      assertEquals(List.of(), issues("updateCart", in().body(cart)));
    }

    @Test
    void fileLimitsSizeContentTypeAndExtension() {
      FileInput big = FileInput.of(new byte[10 * 1024 * 1024 + 1], "rx.pdf");
      Input form = in().form(map("customers", list(customer()), "files", list(big, FileInput.of(new byte[1], "rx.gif"))));
      assertEquals(
          List.of(
              issue("files[0]", "file_limits", "is 10485761 bytes; at most 10485760 bytes are accepted"),
              issue("files[1]", "file_limits",
                  "must be image/jpeg, image/png, application/pdf (got no content type; give the FileInput a filename with a"
                      + " known extension or a content type)")),
          issues("digitizePrescription", form));
      // updateCart: smaller than 7 MB, and a matching content type or extension is enough.
      assertEquals(List.of(), issues("updateCart", cart(FileInput.of(new byte[7 * 1024 * 1024 - 1], "rx.png"))));
      assertEquals(List.of("prescription_files[0] file_limits"), problems("updateCart", cart(FileInput.of(new byte[7 * 1024 * 1024], "rx.png"))));
      assertEquals(List.of(), issues("updateCart", cart(FileInput.of(new byte[1], "scan", "image/png"))));
      assertEquals(List.of(), issues("updateCart", cart(FileInput.of(new byte[1], "scan.PDF", "application/octet-stream"))));
      assertEquals(
          List.of(issue("prescription_files[0]", "file_limits",
              "must be application/pdf, image/png, image/jpeg (a filename ending in .pdf, .png, .jpg, .jpeg); got \"scan.gif\" with \"image/gif\"")),
          issues("updateCart", cart(FileInput.of(new byte[1], "scan.gif", "image/gif"))));
      // uploadConsultationDocuments: by extension.
      assertEquals(List.of(), issues("uploadConsultationDocuments", docs("report.JPG")));
      assertEquals(
          List.of(issue("files[0].value", "file_limits", "needs a filename ending in .pdf, .jpg, .jpeg, .png (got \"report.docx\")")),
          issues("uploadConsultationDocuments", docs("report.docx")));
    }

    private Input cart(FileInput file) {
      return in().form(map("customer_details", map("api_customer_id", "CUST1001"), "prescription_files", list(file)));
    }

    private Input docs(String filename) {
      return in()
          .params(map("appointment_id", "APT1A2B3C4D5"))
          .form(map("files", list(map("name", "Report", "value", FileInput.of(new byte[1], filename)))));
    }

    @Test
    void itemCount() {
      Input two = in().form(map("customers", list(customer(), with(customer(), "api_customer_id", "CUST1002")), "files", list(pdf())));
      assertEquals(List.of(issue("customers", "item_count", "customers must have exactly 1 item(s), got 2")), issues("digitizePrescription", two));
      assertEquals(
          List.of("files minItems", "files item_count"),
          problems("digitizePrescription", in().form(map("customers", list(customer()), "files", list()))));
      assertEquals(
          List.of("files item_count"),
          problems("uploadConsultationDocuments", in().params(map("appointment_id", "APT1A2B3C4D5")).form(map("files", list()))));
      assertEquals(List.of(), issues("deactivatePreRegistrations", in().body(map("employees", emails(200), "dependants", emails(50)))));
      assertEquals(
          List.of(issue("", "item_count", "employees and dependants must have at most 250 item(s) together, got 251")),
          issues("deactivatePreRegistrations", in().body(map("employees", emails(200), "dependants", emails(51)))));
    }

    @Test
    void positiveDecimal() {
      for (Object ok : new Object[] {"261.0", " 261 ", ".5", 261.5, 1}) {
        assertEquals(List.of(), issues("confirmOrder", confirm(ok)), String.valueOf(ok));
      }
      for (Object bad : new Object[] {"0", "-1", "0.00", "abc", "1e3", ""}) {
        assertEquals(List.of("partner_collected_amount positive_decimal"), problems("confirmOrder", confirm(bad)), String.valueOf(bad));
      }
    }

    private Input confirm(Object amount) {
      return in().params(map("order_id", "EPO000000000001")).body(with(bodyExample("confirmOrder"), "partner_collected_amount", amount));
    }
  }

  static List<Object> emails(int n) {
    List<Object> out = new ArrayList<>();
    for (int i = 0; i < n; i++) out.add("member" + i + "@example.com");
    return out;
  }

  // ------------------------------------------------------------------ one valid and one invalid case per operation with rules

  static final class Case {
    final Input valid;
    final Input invalid;
    final List<String> expected;

    Case(Input valid, Input invalid, String... expected) {
      this.valid = valid;
      this.invalid = invalid;
      this.expected = List.of(expected);
    }
  }

  static Map<String, Case> cases() {
    Map<String, Object> appointmentId = map("appointment_id", "APT1A2B3C4D5");
    Map<String, Object> bookingId = map("booking_id", "EKI0000000001700000000");
    Map<String, Object> slug = map("company_slug", "example-co");
    Map<String, Object> address = obj(bodyExample("createAppointment").get("address"));
    Map<String, Case> cases = new LinkedHashMap<>();
    cases.put("listProviders", new Case(
        in().query(map("service_name", "ahc", "package_ids", "1001,1002", "pincode", "500081")),
        in().query(map("service_name", "ahc", "package_ids", "1001", "sort_by", "visited_before", "latitude", 17.4)),
        "longitude required_if", "sort_by allowed_only_if", "api_customer_id required_if"));
    Map<String, Object> noAddress = bodyExample("createAppointment");
    noAddress.remove("address");
    cases.put("createAppointment", new Case(in().body(bodyExample("createAppointment")), in().body(noAddress), "address required_if"));
    cases.put("listCustomerAppointments", new Case(
        in().query(map("employee_id", "EMP1001")), in().query(map("api_customer_id", " ")), " at_least_one_of"));
    cases.put("rescheduleBooking", new Case(
        in().params(bookingId).body(bodyExample("rescheduleBooking")),
        in().params(bookingId).body(map("appointment_date_time", "2030-01-16T02:30:00Z", "address", with(address, "address_line_one", "Road 560001"))),
        "address.address_line_one no_conflicting_pincode"));
    cases.put("rescheduleAppointment", new Case(
        in().params(appointmentId).body(bodyExample("rescheduleAppointment")),
        in().params(appointmentId).body(map("appointment_date_time", "2030-01-16T02:30:00Z", "address", with(address, "state", "Telangana 500001"))),
        "address.state no_conflicting_pincode"));
    cases.put("listInclinicConsultationProviders", new Case(
        in().query(map("package_ids", "2001", "latitude", 17.44, "longitude", 78.38, "sort_by", "visited_before", "api_customer_id", "CUST1001")),
        in().query(map("package_ids", "2001", "sort_by", "visited_before")),
        "latitude required_if", "longitude required_if", "api_customer_id required_if"));
    cases.put("createInclinicConsultation", new Case(
        in().body(bodyExample("createInclinicConsultation")),
        in().body(with(bodyExample("createInclinicConsultation"), "appointment_date_time", "2026-01-09T04:30:00Z", "customers", list(customer(), customer()))),
        "customers[1].api_customer_id unique_by", "appointment_date_time relative_date"));
    cases.put("createSpecialistConsultation", new Case(
        in().body(bodyExample("createSpecialistConsultation")),
        in().body(with(bodyExample("createSpecialistConsultation"), "customers", list(with(customer(), "dob", "2025-06-01")))),
        "customers[0].dob relative_date"));
    cases.put("rescheduleConsultation", new Case(
        in().params(appointmentId).body(bodyExample("rescheduleConsultation")),
        in().params(appointmentId).body(map("appointment_date_time", "2025-12-31T10:00:00Z")),
        "appointment_date_time relative_date"));
    cases.put("uploadConsultationDocuments", new Case(
        in().params(appointmentId).form(map("files", list(map("name", "Blood report", "value", FileInput.of(new byte[1], "blood.pdf"))))),
        in().params(appointmentId).form(map("files", list(map("name", " ", "value", FileInput.of(new byte[1], "blood"))))),
        "files[0].name pattern", "files[0].value file_limits"));
    Map<String, Object> member = customer();
    member.remove("package_ids");
    cases.put("digitizePrescription", new Case(
        in().form(map("customers", list(member), "plan_id", "EXAMPLE_PLAN", "files", list(pdf()))),
        in().form(map("customers", list(), "files", list(FileInput.of(new byte[3], null)))),
        "customers item_count", "files[0] file_limits"));
    cases.put("updateCart", new Case(
        in().body(bodyExample("updateCart", "products")),
        in().body(map("plan_id", "EXAMPLE_PLAN", "customer_details", map("api_customer_id", "CUST1001"), "fulfillment_type", "store_pickup")),
        "selected_chemist_id required_if"));
    cases.put("getOrderPaymentDetails", new Case(
        in().body(bodyExample("getOrderPaymentDetails")),
        in().body(with(bodyExample("getOrderPaymentDetails"), "selected_delivery_option", "store_pickup")),
        "selected_chemist_id required_if"));
    cases.put("confirmOrder", new Case(
        in().params(map("order_id", "EPO000000000003")).body(bodyExample("confirmOrder")),
        in().params(map("order_id", "EPO000000000003")).body(with(bodyExample("confirmOrder"), "partner_collected_amount", "-5")),
        "partner_collected_amount positive_decimal"));
    cases.put("getCustomerWallet", new Case(
        in().params(slug).query(map("api_customer_id", "CUST1001")),
        in().params(slug).query(map("plan_id", "EXAMPLE_PLAN")),
        " at_least_one_of"));
    cases.put("listCustomerPlans", new Case(
        in().params(slug).query(map("customer_id", "EKI000000001")), in().params(slug).query(map()), " at_least_one_of"));
    cases.put("createPreRegistrations", new Case(
        in().body(bodyExample("createPreRegistrations")),
        in().body(map("employees", list(with(employee(), "email", "", "employee_code", null)))),
        "employees[0] at_least_one_of"));
    cases.put("updatePreRegistrations", new Case(
        in().body(bodyExample("updatePreRegistrations")),
        in().body(map("dependants", list(map("last_name", "Doe")))),
        "dependants[0] at_least_one_of"));
    List<Object> many = new ArrayList<>();
    for (int i = 0; i < 251; i++) many.add("m" + i + "@example.com");
    cases.put("deactivatePreRegistrations", new Case(
        in().body(bodyExample("deactivatePreRegistrations")), in().body(map("employees", many)), " item_count"));
    return cases;
  }

  @Test
  void everyOperationWithRulesHasACase() {
    TreeSet<String> withRules = new TreeSet<>();
    Validator.operations().fields().forEachRemaining(e -> {
      if (e.getValue().has("rules")) withRules.add(e.getKey());
    });
    assertEquals(withRules, new TreeSet<>(cases().keySet()));
  }

  @TestFactory
  Stream<DynamicTest> everyOperationWithRules() {
    List<DynamicTest> tests = new ArrayList<>();
    cases().forEach((operation, c) -> tests.add(dynamicTest(operation, () -> {
      assertEquals(List.of(), issues(operation, c.valid), "valid");
      assertEquals(c.expected, problems(operation, c.invalid), "invalid");
    })));
    return tests.stream();
  }

  // ------------------------------------------------------------------ the client

  @Nested
  class Client {
    private final MockServer server = new MockServer();

    @AfterEach
    void close() {
      server.close();
    }

    @Test
    void throwsBeforeFetchingATokenOrCallingTheApi() {
      Hlthstack client = TestSupport.client(server);
      Map<String, Object> body = with(bodyExample("createAppointment"), "customers", list(with(customer(), "gender", "f"), customer()));
      ValidationException e = assertThrows(ValidationException.class, () -> client.appointments().create(body));
      assertEquals("createAppointment", e.operationId());
      assertEquals(
          List.of(
              issue("customers[0].gender", "enum", "must be one of \"male\", \"female\", \"non-binary\""),
              issue("customers[1].api_customer_id", "unique_by", "duplicates customers[0].api_customer_id (\"CUST1001\")")),
          e.errors());
      assertEquals(
          "Invalid createAppointment request (2 problems): customers[0].gender: must be one of \"male\", \"female\", \"non-binary\"; "
              + "customers[1].api_customer_id: duplicates customers[0].api_customer_id (\"CUST1001\")",
          e.getMessage());
      assertEquals(0, e.statusCode());
      assertEquals(2, e.messages().size());
      assertTrue(server.requests().isEmpty());
    }

    @Test
    void validatesPathParametersMultipartUploadsAndPreRegistrationCalls() {
      Hlthstack client = TestSupport.client(server);
      assertMessage("appointment_id: must match APT[0-9A-Z_]{9}", () -> client.appointments().getVoucher("not-an-id"));
      assertMessage(
          "documents[0].file: needs a filename ending in .pdf, .jpg, .jpeg, .png (got \"file\")",
          () -> client.consultations().uploadDocuments(
              "APT1A2B3C4D5", List.of(ConsultationDocument.of("Report", FileInput.of(new byte[1], null)))));
      assertMessage(
          "customer_details.api_customer_id: must match \\S",
          () -> client.pharmacy().updateCart(map("customer_details", map("api_customer_id", " "), "prescription_files", list(pdf()))));
      List<Object> mobiles = new ArrayList<>();
      for (int i = 0; i < 251; i++) mobiles.add("9999999999");
      assertThrows(ValidationException.class, () -> client.preRegistration().deactivate(map("employees", list(), "dependants", mobiles)));
      assertMessage("request_id: must be a UUID", () -> client.preRegistration().getStatus("abc"));
      assertTrue(server.requests().isEmpty());
    }

    private void assertMessage(String expected, Runnable call) {
      ValidationException e = assertThrows(ValidationException.class, call::run);
      assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void canBeTurnedOffForTheClientOrForOneCall() {
      Hlthstack off = TestSupport.builder(server).validate(false).build();
      off.appointments().getVoucher("not-an-id");
      assertEquals(1, server.apiCalls().size());

      Hlthstack client = TestSupport.client(server);
      RequestOptions unchecked = RequestOptions.builder().validate(false).build();
      client.appointments().cancel("not-an-id", unchecked);
      client.pharmacy().digitizePrescription(map("customers", list(customer()), "files", list(FileInput.of(new byte[1], null))), unchecked);
      List<String> paths = new ArrayList<>();
      for (MockServer.Request r : server.apiCalls()) paths.add(r.path);
      assertEquals(List.of("/api/appointments/not-an-id/voucher", "/api/appointments/not-an-id/cancel", "/api/prescriptions/digitize"), paths);
      Hlthstack lenient = TestSupport.builder(server).validate(false).build();
      assertThrows(
          ValidationException.class,
          () -> lenient.appointments().cancel("not-an-id", RequestOptions.builder().validate(true).build()));
    }

    @Test
    void usesTheClientsClockForDateRules() {
      Hlthstack client = TestSupport.builder(server).clock(new MutableClock(NOW)).build();
      Map<String, Object> body = map("appointment_date_time", "2026-01-10T11:45:00");
      assertThrows(ValidationException.class, () -> client.consultations().reschedule("APT1A2B3C4D5", body));
      client.consultations().reschedule("APT1A2B3C4D5", map("appointment_date_time", "2026-01-10T12:15:00"));
      assertEquals(1, server.apiCalls().size());
    }
  }
}
