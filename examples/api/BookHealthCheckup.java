import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Books, reschedules, confirms (staging), downloads the voucher for, and cancels a health
 * checkup.
 *
 * <p>Uses HLTHSTACK_USERNAME / HLTHSTACK_PASSWORD against staging (see ApiClient). Set
 * HLTHSTACK_PACKAGE_ID to a package id shared with you for staging.
 */
public final class BookHealthCheckup {
  static final int PACKAGE_ID = Integer.parseInt(System.getenv().getOrDefault("HLTHSTACK_PACKAGE_ID", "1001"));
  static final String PINCODE = "500081";

  static List<JsonNode> listProviders(ApiClient api) throws IOException, InterruptedException {
    JsonNode response = api.get("/api/providers")
        .query("service_name", "ahc")
        .query("package_ids", PACKAGE_ID) // comma-separated for several: "1001,1002"
        .query("pincode", PINCODE)
        .send();
    List<JsonNode> providers = new ArrayList<>();
    response.path("providers").forEach(providers::add);
    return providers;
  }

  static List<String> availableSlots(ApiClient api, long providerId) throws IOException, InterruptedException {
    // No booking_date: up to 14 days of slots, from the provider's first bookable date.
    JsonNode response = api.get("/api/providers/" + providerId + "/ahc/slots")
        .query("package_ids", PACKAGE_ID)
        .query("pincode", PINCODE)
        .send();
    List<String> slots = new ArrayList<>();
    for (JsonNode day : response.path("booking_slots")) {
      for (JsonNode slot : day.path("slots")) {
        if (slot.path("available").asBoolean()) slots.add(slot.get("utc_slot_time").asText());
      }
    }
    return slots;
  }

  static JsonNode createBooking(ApiClient api, long providerId, String slotTime) throws IOException, InterruptedException {
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("name", "Jane Doe");
    customer.put("dob", "1990-08-24");
    customer.put("gender", "female");
    customer.put("mobile_number", "9999999999");
    customer.put("api_customer_id", "CUST1001");
    customer.put("package_ids", List.of(PACKAGE_ID));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("service_name", "ahc");
    body.put("appointment_type", "inclinic");
    body.put("provider_id", providerId);
    body.put("appointment_date_time", slotTime); // a slot's utc_slot_time
    body.put("customers", List.of(customer));
    return api.post("/api/appointments").json(body).send();
  }

  static JsonNode getBooking(ApiClient api, String bookingId) throws IOException, InterruptedException {
    return api.get("/api/appointments").query("booking_id", bookingId).send();
  }

  static void reschedule(ApiClient api, String appointmentId, String slotTime) throws IOException, InterruptedException {
    api.patch("/api/appointments/" + appointmentId + "/reschedule").json(Map.of("appointment_date_time", slotTime)).send();
  }

  static void cancel(ApiClient api, String appointmentId) throws IOException, InterruptedException {
    api.patch("/api/appointments/" + appointmentId + "/cancel").send();
  }

  static void downloadVoucher(ApiClient api, String appointmentId, Path path) throws IOException, InterruptedException {
    byte[] pdf = api.get("/api/appointments/" + appointmentId + "/voucher").sendForBytes();
    Files.write(path, pdf);
  }

  /** Staging only: confirm the appointment as the provider would. */
  static void simulateConfirmation(ApiClient api, String appointmentId) throws IOException, InterruptedException {
    api.post("/api/appointments/" + appointmentId + "/update_status").json(Map.of("status", "confirm")).send();
  }

  static void run(ApiClient api, Path voucher) throws IOException, InterruptedException {
    List<JsonNode> providers = listProviders(api);
    if (providers.isEmpty()) throw new IllegalStateException("No provider serves this package near this pincode.");
    long providerId = providers.get(0).get("provider_id").asLong();

    List<String> slots = availableSlots(api, providerId);
    if (slots.size() < 2) throw new IllegalStateException("Not enough free slots at this provider.");

    JsonNode booking;
    try {
      booking = createBooking(api, providerId, slots.get(0));
    } catch (ApiClient.ApiException e) {
      // Don't blindly retry: the booking may have been created. Check first.
      throw new IllegalStateException("Booking failed: " + e.messages, e);
    }
    System.out.println("booking " + booking.get("booking_id").asText() + " " + booking.path("status").asText());
    String appointmentId = booking.at("/customers/0/appointment_id").asText();

    for (JsonNode appointment : getBooking(api, booking.get("booking_id").asText()).path("appointments")) {
      System.out.println(appointment.get("appointment_id").asText() + " " + appointment.get("status").asText());
    }

    reschedule(api, appointmentId, slots.get(1));
    simulateConfirmation(api, appointmentId);
    downloadVoucher(api, appointmentId, voucher);
    cancel(api, appointmentId);
    System.out.println("rescheduled, confirmed, voucher saved to " + voucher + ", cancelled");
  }

  public static void main(String[] args) throws Exception {
    run(ApiClient.fromEnvironment(), Paths.get("voucher.pdf"));
  }
}
