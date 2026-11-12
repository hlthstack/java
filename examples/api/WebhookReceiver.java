import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Receives hlthstack webhooks with the JDK's built-in HTTP server and verifies their Signature
 * header (an HS256 JWT) with the standard library only.
 *
 * <p>Usage: HLTHSTACK_WEBHOOK_SECRET=... (PORT=8080) then run this class; it listens on
 * {@code POST /hlthstack/webhooks}.
 */
public final class WebhookReceiver {
  static final long TOLERANCE_SECONDS = 300;
  static final ObjectMapper JSON = new ObjectMapper();

  static final class InvalidSignature extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidSignature(String message) {
      super(message);
    }
  }

  /** Checks the JWT's signature, that its ids match the body, and that it is recent. */
  static JsonNode verifySignature(String signature, JsonNode body, String secret) throws InvalidSignature {
    String[] parts = signature == null ? new String[0] : signature.split("\\.", -1);
    if (parts.length != 3) throw new InvalidSignature("malformed signature");
    JsonNode header;
    JsonNode claims;
    byte[] received;
    try {
      header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
      claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      received = Base64.getUrlDecoder().decode(parts[2]);
    } catch (IllegalArgumentException | IOException e) { // bad base64 or bad JSON
      throw new InvalidSignature("malformed signature");
    }
    if (header == null || !"HS256".equals(header.path("alg").asText())) throw new InvalidSignature("algorithm must be HS256");
    byte[] expected;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
    if (!MessageDigest.isEqual(expected, received)) throw new InvalidSignature("bad signature"); // constant-time
    if (claims == null || !claims.isObject()) throw new InvalidSignature("claims must be an object");

    // The JWT has no exp claim: reject old (replayed) signatures with its timestamp.
    JsonNode timestamp = claims.get("timestamp");
    if (timestamp == null || !timestamp.isNumber()) throw new InvalidSignature("missing timestamp");
    if (Math.abs(System.currentTimeMillis() / 1000.0 - timestamp.asDouble()) > TOLERANCE_SECONDS) {
      throw new InvalidSignature("stale timestamp");
    }

    // The JWT does not cover the body: the ids in the claims must match the body's.
    boolean ok;
    if (claims.hasNonNull("order_id")) { // pharmacy
      ok = claims.get("order_id").equals(body.get("order_id")) && claims.path("webhook_type").equals(body.path("type"));
    } else if (claims.hasNonNull("request_id")) { // prescription digitization
      ok = claims.get("request_id").equals(body.get("request_id"));
    } else { // appointment, consultation, lab results
      ok = claims.hasNonNull("appointment_id")
          && claims.get("appointment_id").equals(body.get("appointment_id"))
          && claims.path("booking_id").equals(body.path("booking_id"));
    }
    if (!ok) throw new InvalidSignature("claims do not match the body");
    return claims;
  }

  /** Starts the receiver; {@code enqueue} gets each verified body. */
  static HttpServer start(int port, String secret, Consumer<JsonNode> enqueue) throws IOException {
    ExecutorService queue = Executors.newSingleThreadExecutor(); // process outside the request
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/hlthstack/webhooks", exchange -> {
      try {
        if (!exchange.getRequestMethod().equals("POST")) {
          respond(exchange, 405);
          return;
        }
        JsonNode body;
        try (InputStream in = exchange.getRequestBody()) {
          body = JSON.readTree(in);
        } catch (IOException e) {
          body = null;
        }
        if (body == null || !body.isObject()) {
          respond(exchange, 400);
          return;
        }
        try {
          verifySignature(exchange.getRequestHeaders().getFirst("Signature"), body, secret);
        } catch (InvalidSignature e) {
          respond(exchange, 401);
          return;
        }
        respond(exchange, 200); // acknowledge fast (the timeout is 30 seconds)
        JsonNode event = body;
        queue.submit(() -> enqueue.accept(event)); // process asynchronously and deduplicate
      } finally {
        exchange.close();
      }
    });
    server.start();
    return server;
  }

  private static void respond(HttpExchange exchange, int status) throws IOException {
    exchange.sendResponseHeaders(status, -1);
  }

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    start(port, System.getenv("HLTHSTACK_WEBHOOK_SECRET"), event ->
        System.out.println("received " + event.path("type").asText() + " "
            + (event.has("appointment_id") ? event.get("appointment_id").asText() : event.path("order_id").asText())));
    System.out.println("listening on http://localhost:" + port + "/hlthstack/webhooks");
  }
}
