import com.fasterxml.jackson.databind.JsonNode;
import io.hlthstack.Environment;
import io.hlthstack.Hlthstack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds a provider and a slot, books a health checkup and reads it back, with the SDK.
 *
 * <p>Uses HLTHSTACK_USERNAME / HLTHSTACK_PASSWORD against staging. Walkthrough:
 * docs/sdk-integration.md
 */
public final class Quickstart {
  public static void main(String[] args) {
    Hlthstack client = Hlthstack.builder()
        .username(System.getenv("HLTHSTACK_USERNAME"))
        .password(System.getenv("HLTHSTACK_PASSWORD"))
        .environment(Environment.STAGING)
        .build();

    // 1. Find providers. The access token is requested on the first call and reused.
    JsonNode providers = client.appointments().listProviders(
        Map.of("service_name", "ahc", "package_ids", "1001", "pincode", "500081"));
    long providerId = providers.at("/providers/0/provider_id").asLong();

    // 2. Pick a slot.
    JsonNode slots = client.appointments().listSlots(providerId, "ahc", Map.of("package_ids", "1001", "pincode", "500081"));
    String slotTime = null;
    for (JsonNode day : slots.path("booking_slots")) {
      for (JsonNode slot : day.path("slots")) {
        if (slotTime == null && slot.path("available").asBoolean()) slotTime = slot.get("utc_slot_time").asText();
      }
    }

    // 3. Book.
    Map<String, Object> customer = new LinkedHashMap<>();
    customer.put("name", "Jane Doe");
    customer.put("dob", "1990-08-24");
    customer.put("mobile_number", "9999999999");
    customer.put("api_customer_id", "CUST1001");
    customer.put("gender", "female");
    customer.put("package_ids", List.of(1001));
    customer.put("booking_customer", true);
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("address_line_one", "1 Example Road");
    address.put("address_line_two", "Madhapur");
    address.put("city", "Hyderabad");
    address.put("state", "Telangana");
    address.put("country", "India");
    address.put("pincode", "500081");
    address.put("latitude", 17.4437);
    address.put("longitude", 78.386);
    Map<String, Object> booking = new LinkedHashMap<>();
    booking.put("provider_id", providerId);
    booking.put("appointment_date_time", slotTime);
    booking.put("appointment_type", "home_collection");
    booking.put("service_name", "ahc");
    booking.put("customers", List.of(customer));
    booking.put("address", address);
    JsonNode created = client.appointments().create(booking);

    if (created.path("status").asText().equals("payment_pending")) {
      System.out.println("send this payment link to the member: " + created.path("payment_link").asText());
    }

    // 4. Read it back.
    JsonNode details = client.appointments().get(Map.of("booking_id", created.get("booking_id").asText()));
    for (JsonNode appointment : details.path("appointments")) {
      System.out.println(appointment.path("appointment_id").asText() + " " + appointment.path("status").asText());
    }
  }
}
