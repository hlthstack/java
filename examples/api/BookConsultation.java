import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Books an in-clinic doctor consultation.
 *
 * <p>Uses HLTHSTACK_USERNAME / HLTHSTACK_PASSWORD against staging (see ApiClient). Set
 * HLTHSTACK_CONSULTATION_PACKAGE_ID to a consultation package id shared with you for staging.
 */
public final class BookConsultation {
  static final int PACKAGE_ID = Integer.parseInt(System.getenv().getOrDefault("HLTHSTACK_CONSULTATION_PACKAGE_ID", "2001"));

  static JsonNode bookInclinicConsultation(ApiClient api) throws IOException, InterruptedException {
    JsonNode doctors = api.get("/api/consultations/inclinic_consultation/providers")
        .query("package_ids", PACKAGE_ID)
        .query("pincode", "500084")
        .query("sort_by", "distance")
        .send()
        .path("providers");
    long providerId = doctors.get(0).get("provider_id").asLong();
    JsonNode slots = api.get("/api/providers/" + providerId + "/inclinic_consultation/slots")
        .query("package_ids", PACKAGE_ID)
        .send();
    String slotTime = null;
    for (JsonNode day : slots.path("booking_slots")) {
      for (JsonNode slot : day.path("slots")) {
        if (slotTime == null && slot.path("available").asBoolean()) slotTime = slot.get("utc_slot_time").asText();
      }
    }
    if (slotTime == null) throw new IllegalStateException("No free slot at this doctor.");

    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("name", "Jane Doe");
    customer.put("dob", "1990-08-24");
    customer.put("mobile_number", "9999999999");
    customer.put("api_customer_id", "CUST1001");
    customer.put("gender", "female");
    customer.put("package_ids", List.of(PACKAGE_ID));
    Map<String, Object> address = new LinkedHashMap<>(); // the member's address; pincode is required here
    address.put("address_line_one", "1 Example Road");
    address.put("address_line_two", "Kondapur");
    address.put("city", "Hyderabad");
    address.put("state", "Telangana");
    address.put("country", "India");
    address.put("pincode", "500084");
    address.put("latitude", 17.47);
    address.put("longitude", 78.35);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("appointment_date_time", slotTime); // must be in the future
    body.put("service_mode", "at_provider_location");
    body.put("provider_id", providerId);
    body.put("customers", List.of(customer));
    body.put("address", address);
    return api.post("/api/consultations/inclinic_consultation").json(body).send();
  }

  public static void main(String[] args) throws Exception {
    JsonNode booking = bookInclinicConsultation(ApiClient.fromEnvironment());
    System.out.println(booking.get("booking_id").asText() + " " + booking.at("/customers/0/appointment_id").asText());
  }
}
