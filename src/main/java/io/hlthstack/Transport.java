package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** HTTP transport: validation, auth, entity headers, timeouts and error mapping. Internal. */
final class Transport {
  static final String TOKEN_PATH = "/api/get-access-token";
  static final String PRE_REGISTRATION_TOKEN_PATH = "/api/pre_registration/access-token";

  final Environment environment;
  final String baseUrl;
  private final String entityId;
  private final String secretKey;
  private final Duration timeout;
  private final boolean validate;
  private final HttpClient http;
  final Clock clock;
  private final TokenCache tokens;
  private final TokenCache preRegistrationTokens;

  Transport(Hlthstack.Builder b) {
    boolean partner = present(b.username) && present(b.password);
    boolean preRegistration = present(b.preRegistrationUsername) && present(b.preRegistrationPassword);
    if (!partner && !preRegistration) {
      throw new IllegalArgumentException(
          "username and password are required (or preRegistrationUsername and preRegistrationPassword)");
    }
    if (present(b.entityId) && !present(b.secretKey)) {
      throw new IllegalArgumentException("secretKey is required when entityId is set");
    }
    if (b.timeout != null && (b.timeout.isNegative() || b.timeout.isZero())) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    this.environment = b.environment;
    String base = b.baseUrl != null ? b.baseUrl : b.environment.baseUrl();
    this.baseUrl = base.replaceAll("/+$", "");
    this.entityId = present(b.entityId) ? b.entityId : null;
    this.secretKey = present(b.secretKey) ? b.secretKey : null;
    this.timeout = b.timeout != null ? b.timeout : Hlthstack.DEFAULT_TIMEOUT;
    this.validate = b.validate;
    this.http = b.httpClient != null ? b.httpClient : HttpClient.newBuilder().connectTimeout(this.timeout).build();
    this.clock = b.clock != null ? b.clock : Clock.systemUTC();
    String username = b.username;
    String password = b.password;
    String preUsername = b.preRegistrationUsername;
    String prePassword = b.preRegistrationPassword;
    this.tokens =
        new TokenCache(() -> fetchToken(TOKEN_PATH, username, password, "username and password"), clock);
    this.preRegistrationTokens =
        new TokenCache(
            () -> fetchToken(
                PRE_REGISTRATION_TOKEN_PATH, preUsername, prePassword,
                "preRegistrationUsername and preRegistrationPassword"),
            clock);
  }

  private static boolean present(String value) {
    return value != null && !value.isEmpty();
  }

  // ------------------------------------------------------------------ calls

  /** One API call, described by a namespace method. */
  static final class Call {
    final String operationId;
    final String method;
    final String path;
    ObjectNode params;
    JsonNode query;
    JsonNode body;
    /** The multipart body in its nested shape. */
    JsonNode form;
    boolean binary;
    boolean preRegistration;
    UnaryOperator<String> renamePaths;

    /** {@code template} holds {@code {name}} placeholders filled from {@code pathParams}, in order. */
    Call(String operationId, String method, String template, Object... pathParams) {
      this.operationId = operationId;
      this.method = method;
      StringBuilder path = new StringBuilder();
      ObjectNode params = Json.MAPPER.createObjectNode();
      int i = 0;
      int at = 0;
      while (true) {
        int open = template.indexOf('{', at);
        if (open < 0) break;
        int close = template.indexOf('}', open);
        String name = template.substring(open + 1, close);
        Object value = pathParams[i++];
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        params.set(name, Json.toTree(value));
        path.append(template, at, open).append(encodeSegment(String.valueOf(value)));
        at = close + 1;
      }
      if (i != pathParams.length) throw new IllegalStateException("path parameters do not match " + template);
      path.append(template.substring(at));
      this.path = path.toString();
      this.params = i == 0 ? null : params;
    }

    Call query(Map<String, ?> query) {
      if (query == null) throw new IllegalArgumentException("query parameters must not be null");
      this.query = joinLists(Json.toTree(query));
      return this;
    }

    Call body(Object body) {
      if (body == null) throw new IllegalArgumentException("the request body must not be null");
      this.body = Json.toTree(body);
      return this;
    }

    Call form(JsonNode form) {
      this.form = form;
      return this;
    }

    Call binary() {
      this.binary = true;
      return this;
    }

    Call preRegistration() {
      this.preRegistration = true;
      return this;
    }

    Call renamePaths(UnaryOperator<String> rename) {
      this.renamePaths = rename;
      return this;
    }
  }

  /** A multipart body in its nested shape: maps, lists, text and numbers, with files kept as is. */
  static JsonNode formTree(Object value) {
    if (value instanceof FileInput) return Json.MAPPER.getNodeFactory().pojoNode(value);
    if (value instanceof Map) {
      ObjectNode out = Json.MAPPER.createObjectNode();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) out.set(String.valueOf(e.getKey()), formTree(e.getValue()));
      return out;
    }
    if (value instanceof Iterable) {
      ArrayNode out = Json.MAPPER.createArrayNode();
      for (Object item : (Iterable<?>) value) out.add(formTree(item));
      return out;
    }
    return Json.toTree(value);
  }

  /**
   * Lists in a query are sent comma-separated ({@code package_ids=1001,1002}), so they are joined
   * before validation, which then checks the value as it goes out.
   */
  static JsonNode joinLists(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = Json.MAPPER.createObjectNode();
      node.fields().forEachRemaining(e -> out.set(e.getKey(), joinLists(e.getValue())));
      return out;
    }
    if (node.isArray()) {
      List<String> items = new ArrayList<>();
      for (JsonNode item : node) if (!item.isNull()) items.add(scalar(item));
      return TextNode.valueOf(String.join(",", items));
    }
    return node;
  }

  static String encodeSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Validates (unless turned off), then sends an authenticated call. Returns JSON. */
  JsonNode json(Call call, RequestOptions options) {
    return (JsonNode) execute(call, options);
  }

  /** The same for binary responses such as voucher PDFs. */
  byte[] bytes(Call call, RequestOptions options) {
    call.binary();
    return (byte[]) execute(call, options);
  }

  private Object execute(Call call, RequestOptions options) {
    if (options == null) options = RequestOptions.NONE;
    validate(call, options);
    Map<String, String> headers = new LinkedHashMap<>();
    if (!call.preRegistration) headers.putAll(entityHeaders(options));
    headers.putAll(options.headers());
    byte[] payload = null;
    if (call.form != null) {
      Multipart multipart = Multipart.encode(call.form);
      headers.put("Content-Type", multipart.contentType);
      payload = multipart.body;
    } else if (call.body != null) {
      headers.put("Content-Type", "application/json");
      payload = Json.write(call.body);
    }
    String url = baseUrl + call.path + queryString(call.query);
    TokenCache cache = call.preRegistration ? preRegistrationTokens : tokens;
    String token = cache.get(false).accessToken();
    try {
      return send(call.method, url, withAuth(headers, token, call.preRegistration), payload, call.binary, options.timeout());
    } catch (AuthenticationException e) {
      // The token expired or was revoked: get a new one and retry once.
      cache.invalidate(token);
      token = cache.get(false).accessToken();
      return send(call.method, url, withAuth(headers, token, call.preRegistration), payload, call.binary, options.timeout());
    }
  }

  private void validate(Call call, RequestOptions options) {
    boolean enabled = options.validate() != null ? options.validate() : validate;
    if (!enabled || call.operationId == null) return;
    List<ValidationIssue> issues =
        Validator.validate(call.operationId, call.params, call.query, call.form == null ? call.body : null, call.form, clock.instant());
    if (issues.isEmpty()) return;
    if (call.renamePaths != null) {
      List<ValidationIssue> renamed = new ArrayList<>();
      for (ValidationIssue issue : issues) renamed.add(issue.withPath(call.renamePaths.apply(issue.path())));
      issues = renamed;
    }
    throw new ValidationException(call.operationId, issues);
  }

  private static Map<String, String> withAuth(Map<String, String> headers, String token, boolean preRegistration) {
    Map<String, String> out = new LinkedHashMap<>(headers);
    // Pre-registration takes its token raw in X-EKINCARE-KEY, without "Bearer".
    if (preRegistration) out.put("X-EKINCARE-KEY", token);
    else out.put("Authorization", "Bearer " + token);
    return out;
  }

  private Map<String, String> entityHeaders(RequestOptions options) {
    String entity = options.entityIdSet() ? options.entityId() : entityId;
    Map<String, String> out = new LinkedHashMap<>();
    if (entity == null) return out;
    String secret = options.secretKey() != null ? options.secretKey() : secretKey;
    if (!present(secret)) throw new IllegalArgumentException("secretKey is required when entityId is set");
    out.put("API-ENTITY-ID", entity);
    out.put("API-SECRET-KEY", secret);
    return out;
  }

  // ------------------------------------------------------------------ tokens

  AccessToken accessToken(boolean forceRefresh) {
    return tokens.get(forceRefresh);
  }

  AccessToken preRegistrationAccessToken(boolean forceRefresh) {
    return preRegistrationTokens.get(forceRefresh);
  }

  private AccessToken fetchToken(String path, String username, String password, String names) {
    if (!present(username) || !present(password)) {
      throw new IllegalStateException(names + " are required for this call");
    }
    String credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Basic " + credentials); // never entity headers on token calls
    return new AccessToken((JsonNode) send("GET", baseUrl + path, headers, null, false, null));
  }

  // ------------------------------------------------------------------ HTTP

  private Object send(String method, String url, Map<String, String> headers, byte[] payload, boolean binary, Duration callTimeout) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(callTimeout != null ? callTimeout : timeout)
            .header("Accept", binary ? "application/pdf, application/json" : "application/json")
            .header("User-Agent", "hlthstack-java/" + Hlthstack.VERSION);
    for (Map.Entry<String, String> h : headers.entrySet()) request.setHeader(h.getKey(), h.getValue());
    request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));

    HttpResponse<byte[]> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpTimeoutException e) {
      throw new ApiConnectionException(method + " " + HlthstackException.stripQuery(url) + " timed out", true, e);
    } catch (IOException e) {
      throw new ApiConnectionException(method + " " + HlthstackException.stripQuery(url) + " failed: " + e, false, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiConnectionException(method + " " + HlthstackException.stripQuery(url) + " was interrupted", false, e);
    }

    int status = response.statusCode();
    byte[] bytes = response.body() == null ? new byte[0] : response.body();
    boolean ok = status >= 200 && status < 300;
    if (ok && binary) return bytes;
    JsonNode parsed = parseBody(bytes);
    if (!ok) throw HlthstackException.fromResponse(status, parsed, response.headers().map(), method, url);
    return parsed == null ? Json.MAPPER.createObjectNode() : parsed;
  }

  /** JSON as a tree, other text as a text node, an empty body as null. */
  private static JsonNode parseBody(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.trim().isEmpty()) return null;
    JsonNode json = Json.parseOrNull(text);
    return json != null ? json : TextNode.valueOf(text);
  }

  /** Query string: nested objects as {@code key[sub]}, lists comma-separated, nulls left out. */
  static String queryString(JsonNode query) {
    if (query == null) return "";
    List<String> pairs = new ArrayList<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = query.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      addQuery(pairs, e.getKey(), e.getValue());
    }
    return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
  }

  private static void addQuery(List<String> pairs, String key, JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return;
    if (value.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        addQuery(pairs, key + "[" + e.getKey() + "]", e.getValue());
      }
      return;
    }
    String text = value.isArray() ? joinLists(value).asText() : scalar(value);
    pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
  }

  private static String scalar(JsonNode value) {
    return value.isNumber() ? Validator.plain(value) : value.asText();
  }
}
