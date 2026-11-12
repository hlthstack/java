import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-registers a member, waits for the outcome, then updates and deactivates them.
 *
 * <p>Uses HLTHSTACK_PREREG_USERNAME / HLTHSTACK_PREREG_PASSWORD (the pre-registration account,
 * not the booking API credentials) against staging (see ApiClient).
 */
public final class PreRegistration {
  static final int MAX_ROWS = 250; // employees + dependants per call

  /** Every required key must be present in every row, even when empty, or the row is silently dropped. */
  static Map<String, Object> employee() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("employee_code", "EMP1001");
    row.put("company_id", "1001");
    row.put("first_name", "Jane");
    row.put("last_name", "Doe");
    row.put("email", "jane.doe@example.com");
    row.put("date_of_birth", "24-08-1990"); // DD-MM-YYYY
    row.put("gender", "Female");
    row.put("mobile_number", "9999999999");
    row.put("vip", "No");
    row.put("entity_name", "");
    row.put("employee_office_location", "Hyderabad");
    row.put("department", "Engineering");
    row.put("grade", "B");
    row.put("date_of_joining", "01-01-2026");
    return row;
  }

  static String submit(ApiClient api, String path, Map<String, List<?>> body) throws IOException, InterruptedException {
    int rows = body.getOrDefault("employees", List.of()).size() + body.getOrDefault("dependants", List.of()).size();
    if (rows > MAX_ROWS) throw new IllegalArgumentException("at most " + MAX_ROWS + " rows per call, got " + rows);
    JsonNode accepted = api.preRegistration("POST", path).json(body).send();
    return accepted.get("request_id").asText();
  }

  static JsonNode waitFor(ApiClient api, String requestId, Duration interval) throws IOException, InterruptedException {
    while (true) {
      JsonNode status = api.preRegistration("GET", "/v1/corporate/pre_registrations/" + requestId + "/status").send();
      if (status.path("status").asText().equals("completed")) return status;
      Thread.sleep(interval.toMillis());
    }
  }

  static void run(ApiClient api, Duration interval) throws IOException, InterruptedException {
    String requestId = submit(api, "/v1/corporate/pre_registrations", Map.of("employees", List.of(employee())));
    JsonNode status = waitFor(api, requestId, interval);
    for (JsonNode record : status.path("failure_records")) {
      System.out.println("failed: " + record.path("email").asText() + " " + record.get("errors"));
    }

    Map<String, List<?>> update = Map.of("employees", List.of(Map.of("email", "jane.doe@example.com", "grade", "C")));
    waitFor(api, submit(api, "/v1/corporate/pre_registrations/update", update), interval);

    Map<String, List<?>> deactivate = Map.of("employees", List.of("jane.doe@example.com")); // emails; dependants by mobile
    waitFor(api, submit(api, "/v1/corporate/pre_registrations/deactivate", deactivate), interval);
  }

  public static void main(String[] args) throws Exception {
    run(ApiClient.fromEnvironment(), Duration.ofSeconds(5));
  }
}
