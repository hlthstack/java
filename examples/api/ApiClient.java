import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plain-HTTP helpers for the hlthstack REST API: java.net.http.HttpClient and Jackson, no SDK.
 * Walkthrough: docs/api-integration.md
 *
 * <p>Environment variables:
 *
 * <pre>
 *   HLTHSTACK_BASE_URL         default https://staging-api.hlthstack.io
 *   HLTHSTACK_USERNAME         API username
 *   HLTHSTACK_PASSWORD         API password
 *   HLTHSTACK_ENTITY_ID        optional, multi-entity partners: entity to act for
 *   HLTHSTACK_SECRET_KEY       required with HLTHSTACK_ENTITY_ID
 *   HLTHSTACK_PREREG_USERNAME  pre-registration login (an email), only for pre-registration
 *   HLTHSTACK_PREREG_PASSWORD
 * </pre>
 */
public final class ApiClient {
  static final String DEFAULT_BASE_URL = "https://staging-api.hlthstack.io";
  static final Duration TIMEOUT = Duration.ofSeconds(60); // create, reschedule and cancel can take a few seconds
  static final Duration REFRESH_MARGIN = Duration.ofMinutes(5); // renew 5 minutes before expires_at
  static final ObjectMapper JSON = new ObjectMapper();

  final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  final String baseUrl;
  final String entityId;
  final String secretKey;
  final AccessToken token;
  final AccessToken preRegistrationToken;

  /** Reads the configuration from the environment variables above. */
  public static ApiClient fromEnvironment() {
    return new ApiClient(System.getenv());
  }

  public ApiClient(Map<String, String> env) {
    baseUrl = env.getOrDefault("HLTHSTACK_BASE_URL", DEFAULT_BASE_URL);
    entityId = env.get("HLTHSTACK_ENTITY_ID");
    secretKey = env.get("HLTHSTACK_SECRET_KEY");
    token = new AccessToken(
        "/api/get-access-token", env.getOrDefault("HLTHSTACK_USERNAME", ""), env.getOrDefault("HLTHSTACK_PASSWORD", ""));
    preRegistrationToken = new AccessToken(
        "/api/pre_registration/access-token",
        env.getOrDefault("HLTHSTACK_PREREG_USERNAME", ""),
        env.getOrDefault("HLTHSTACK_PREREG_PASSWORD", ""));
  }

  // ------------------------------------------------------------------ errors

  /** A non-2xx response. {@code body} is the parsed JSON, the text as a text node, or null. */
  public static final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public final transient JsonNode body;
    public final transient List<String> messages;

    ApiException(int status, JsonNode body) {
      super("HTTP " + status + ": " + (errorMessages(body).isEmpty() ? "no error message" : String.join("; ", errorMessages(body))));
      this.status = status;
      this.body = body;
      this.messages = errorMessages(body);
    }
  }

  /** Pulls the human-readable messages out of any of the API's error shapes. */
  static List<String> errorMessages(JsonNode body) {
    List<String> out = new ArrayList<>();
    if (body == null) return out;
    if (body.isTextual()) { // HTTP Token: Access denied.
      if (!body.asText().isBlank()) out.add(body.asText());
      return out;
    }
    JsonNode errors = body.path("errors");
    if (errors.isArray()) { // {"errors": ["..."]}
      errors.forEach(e -> out.add(e.asText()));
    } else if (errors.isObject()) { // {"errors": {"messages": [...]}}, {"errors": {"field": [...]}}
      errors.fields().forEachRemaining(e -> {
        String field = e.getKey();
        boolean generic = field.equals("messages") || field.equals("base") || field.equals("msg");
        List<JsonNode> values = new ArrayList<>();
        if (e.getValue().isArray()) e.getValue().forEach(values::add);
        else values.add(e.getValue());
        for (JsonNode value : values) {
          if (!value.isTextual()) continue; // {"errors": {"msg": "...", "recall": false}}
          out.add(generic ? value.asText() : field + ": " + value.asText());
        }
      });
    }
    for (String key : new String[] {"message", "msg", "error"}) { // {"message"}, {"msg", "recall"}, {"error"}
      if (body.path(key).isTextual()) out.add(body.get(key).asText());
    }
    return out;
  }

  static JsonNode parseBody(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.isBlank()) return null;
    try {
      return JSON.readTree(text);
    } catch (JsonProcessingException e) {
      return JSON.getNodeFactory().textNode(text);
    }
  }

  // ------------------------------------------------------------------ tokens

  /** Fetches a token with HTTP Basic auth and caches it until shortly before it expires. */
  final class AccessToken {
    final String path;
    final String username;
    final String password;
    private String value;
    private Instant refreshAt = Instant.MIN;

    AccessToken(String path, String username, String password) {
      this.path = path;
      this.username = username;
      this.password = password;
    }

    /** One refresh at a time, shared by all threads. */
    synchronized String get(boolean forceRefresh) throws IOException, InterruptedException {
      if (forceRefresh || value == null || !Instant.now().isBefore(refreshAt)) {
        String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(TIMEOUT)
            .header("Authorization", "Basic " + basic)
            .GET()
            .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new ApiException(response.statusCode(), parseBody(response.body()));
        JsonNode data = JSON.readTree(response.body());
        value = data.get("access_token").asText();
        refreshAt = parseExpiresAt(data.get("expires_at").asText()).minus(REFRESH_MARGIN);
      }
      return value;
    }
  }

  static Instant parseExpiresAt(String value) {
    if (value.endsWith(" UTC")) { // pre-registration: "2026-01-15 07:16:21 UTC"
      return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")).toInstant(ZoneOffset.UTC);
    }
    return OffsetDateTime.parse(value).toInstant(); // ISO 8601: "2026-01-15T07:16:21Z"
  }

  // ------------------------------------------------------------------ requests

  /** A call to the booking API: {@code Authorization: Bearer} plus the entity headers. */
  public Request request(String method, String path) {
    return new Request(method, path, token, false);
  }

  public Request get(String path) {
    return request("GET", path);
  }

  public Request post(String path) {
    return request("POST", path);
  }

  public Request patch(String path) {
    return request("PATCH", path);
  }

  /** A call to a pre-registration endpoint: the raw token in {@code X-EKINCARE-KEY}, no Bearer. */
  public Request preRegistration(String method, String path) {
    return new Request(method, path, preRegistrationToken, true);
  }

  private Map<String, String> entityHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    if (entityId == null || entityId.isEmpty()) return headers; // act for the entity tied to the credentials
    headers.put("API-ENTITY-ID", entityId);
    headers.put("API-SECRET-KEY", secretKey);
    return headers;
  }

  /** One request: add a query, a JSON or multipart body and headers, then send it. */
  public final class Request {
    private final String method;
    private final String path;
    private final AccessToken accessToken;
    private final boolean preRegistration;
    private final List<String> query = new ArrayList<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body;
    private String contentType;

    Request(String method, String path, AccessToken accessToken, boolean preRegistration) {
      this.method = method;
      this.path = path;
      this.accessToken = accessToken;
      this.preRegistration = preRegistration;
      if (!preRegistration) headers.putAll(entityHeaders());
    }

    /** A query parameter; bracketed names such as {@code customer_details[api_customer_id]} as is. */
    public Request query(String name, Object value) {
      query.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
      return this;
    }

    /** A JSON body: a Map, a JsonNode, or any object Jackson can write. */
    public Request json(Object value) {
      try {
        body = JSON.writeValueAsBytes(value);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException(e);
      }
      contentType = "application/json";
      return this;
    }

    public Request multipart(Multipart form) {
      body = form.toByteArray();
      contentType = form.contentType();
      return this;
    }

    /** An extra header, for example per-call entity headers or {@code Idempotency-Key}. */
    public Request header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    /** Sends the request and returns the parsed JSON (an empty object for an empty body). */
    public JsonNode send() throws IOException, InterruptedException {
      JsonNode parsed = parseBody(sendForBytes());
      return parsed == null ? JSON.createObjectNode() : parsed;
    }

    /**
     * Sends the request and returns the raw body (vouchers are PDF bytes). Gets a new token and
     * retries once on a 401; throws {@link ApiException} on any other non-2xx response.
     */
    public byte[] sendForBytes() throws IOException, InterruptedException {
      String url = baseUrl + path + (query.isEmpty() ? "" : "?" + String.join("&", query));
      for (int attempt = 1; ; attempt++) {
        String value = accessToken.get(attempt == 2);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
        request.header(preRegistration ? "X-EKINCARE-KEY" : "Authorization", preRegistration ? value : "Bearer " + value);
        headers.forEach(request::header);
        if (contentType != null) request.header("Content-Type", contentType);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status == 401 && attempt == 1) continue; // token expired or revoked: get a new one and retry once
        if (status < 200 || status > 299) throw new ApiException(status, parseBody(response.body()));
        return response.body();
      }
    }
  }

  // ------------------------------------------------------------------ multipart

  /** A multipart/form-data body, built in memory so a retry can send it again. */
  public static final class Multipart {
    private final String boundary = "hlthstack-" + UUID.randomUUID();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** A text field, with the bracketed name the API expects, such as {@code customers[][dob]}. */
    public Multipart field(String name, String value) {
      write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
      return this;
    }

    /** A file field, such as {@code files[]}. Repeat the name to send several files. */
    public Multipart file(String name, String filename, String contentType, byte[] content) {
      write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
          + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
      out.writeBytes(content);
      write("\r\n");
      return this;
    }

    String contentType() {
      return "multipart/form-data; boundary=" + boundary;
    }

    byte[] toByteArray() {
      ByteArrayOutputStream copy = new ByteArrayOutputStream();
      copy.writeBytes(out.toByteArray());
      copy.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      return copy.toByteArray();
    }

    private void write(String text) {
      out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
  }
}
