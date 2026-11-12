import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills a member's ePharmacy cart and places the order.
 *
 * <p>Uses HLTHSTACK_USERNAME / HLTHSTACK_PASSWORD against staging (see ApiClient).
 */
public final class PharmacyOrder {
  static final Map<String, String> CUSTOMER = Map.of("api_customer_id", "CUST1001");

  static JsonNode placeOrder(ApiClient api, long medicineId) throws IOException, InterruptedException {
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("line1", "1 Example Road");
    address.put("line2", "Motera");
    address.put("city", "Ahmedabad");
    address.put("state", "Gujarat");
    address.put("pincode", "380005");
    Map<String, Object> cartUpdate = new LinkedHashMap<>();
    cartUpdate.put("customer_details", CUSTOMER);
    cartUpdate.put("products", List.of(Map.of("id", medicineId, "quantity", 1)));
    cartUpdate.put("address", address);
    JsonNode cart = api.patch("/api/epharmacy/cart").json(cartUpdate).send().get("cart");

    JsonNode options = api.get("/api/epharmacy/fulfillment_options") // slow: typically 3-8 seconds
        .query("customer_details[api_customer_id]", CUSTOMER.get("api_customer_id"))
        .query("cart_id", cart.get("id").asLong())
        .send();
    JsonNode delivery = options.path("delivery_options");
    if (!delivery.path("success").asBoolean() || !delivery.has("standard")) { // HTTP 200 can still fail
      throw new IllegalStateException("Standard delivery unavailable: " + delivery.path("message").asText());
    }

    Map<String, Object> order = new LinkedHashMap<>();
    order.put("customer_details", CUSTOMER);
    order.put("cart_id", cart.get("id").asLong());
    order.put("delivery_option", "standard");
    order.put("location_token", delivery.get("standard").get("location_token").asText());
    return api.post("/api/epharmacy/orders").json(order).send(); // status: confirmed | payment_pending | partner_payment_pending
  }

  public static void main(String[] args) throws Exception {
    ApiClient api = ApiClient.fromEnvironment();
    JsonNode medicines = api.get("/api/epharmacy/medicines/search").query("name", "paracetamol").send();
    JsonNode order = placeOrder(api, medicines.at("/medicines/0/id").asLong());
    System.out.println(order.get("status").asText() + " " + order.at("/user_prescription_details/unique_id").asText());
  }
}
