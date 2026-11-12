import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Uploads a prescription for digitization (multipart) and waits for the result.
 *
 * <p>Usage: {@code UploadPrescription prescription.pdf}. Uses HLTHSTACK_USERNAME /
 * HLTHSTACK_PASSWORD against staging (see ApiClient).
 */
public final class UploadPrescription {
  static final Set<String> FINISHED = Set.of("completed", "partial", "rejected", "failed");

  static JsonNode digitizePrescription(ApiClient api, Path path, String idempotencyKey) throws IOException, InterruptedException {
    byte[] content = Files.readAllBytes(path); // bytes, so the retry after a 401 can resend the file
    String filename = path.getFileName().toString();
    String contentType = filename.toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/jpeg";
    ApiClient.Multipart form = new ApiClient.Multipart() // bracketed field names, exactly one customer
        .field("customers[][name]", "Jane Doe")
        .field("customers[][api_customer_id]", "CUST1001")
        .field("customers[][mobile_number]", "9999999999")
        .field("customers[][gender]", "female")
        .field("customers[][dob]", "1990-08-24")
        .file("files[]", filename, contentType, content);
    return api.post("/api/prescriptions/digitize")
        .multipart(form)
        .header("Idempotency-Key", idempotencyKey) // retries return the same request
        .send(); // 202 {"request_id": ..., "status": "pending"}
  }

  static JsonNode waitForDigitization(ApiClient api, String requestId, Duration interval) throws IOException, InterruptedException {
    while (true) {
      JsonNode result = api.get("/api/prescriptions/digitize/" + requestId).send();
      if (FINISHED.contains(result.path("status").asText())) return result;
      Thread.sleep(interval.toMillis()); // or wait for the digitization_results webhook
    }
  }

  public static void main(String[] args) throws Exception {
    ApiClient api = ApiClient.fromEnvironment();
    JsonNode accepted = digitizePrescription(api, Paths.get(args[0]), UUID.randomUUID().toString());
    JsonNode result = waitForDigitization(api, accepted.get("request_id").asText(), Duration.ofSeconds(10));
    System.out.println(result.get("status").asText() + " " + result.path("cart_id") + " " + result.path("items"));
  }
}
