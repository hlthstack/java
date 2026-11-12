package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for the hlthstack API ({@code com.sun.net.httpserver}). Records every request,
 * answers the token endpoints (token-1, token-2, ...; pre-registration: prereg-1, ...) when the
 * credentials match, replays queued replies, and answers anything else with {@code {"ok": true}}.
 */
public final class MockServer implements AutoCloseable {
  public static final String USERNAME = "test-user";
  public static final String PASSWORD = "test-password";
  public static final String PREREG_USERNAME = "prereg@example.com";
  public static final String PREREG_PASSWORD = "prereg-password";
  public static final String TOKEN_PATH = "/api/get-access-token";
  public static final String PREREG_TOKEN_PATH = "/api/pre_registration/access-token";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One recorded request. */
  public static final class Request {
    public final String method;
    /** The raw (still percent-encoded) path. */
    public final String rawPath;
    /** The decoded path. */
    public final String path;
    public final String rawQuery;
    /** Decoded query parameters, in order. */
    public final Map<String, List<String>> query;
    /** Headers with lower-case names (first value). */
    public final Map<String, String> headers;
    public final byte[] body;

    Request(String method, String rawPath, String path, String rawQuery, Map<String, String> headers, byte[] body) {
      this.method = method;
      this.rawPath = rawPath;
      this.path = path;
      this.rawQuery = rawQuery;
      this.query = parseQuery(rawQuery);
      this.headers = headers;
      this.body = body;
    }

    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public JsonNode json() {
      try {
        return JSON.readTree(body);
      } catch (IOException e) {
        throw new AssertionError("body is not JSON: " + new String(body, StandardCharsets.UTF_8), e);
      }
    }

    /** Multipart parts, in order. */
    public List<Part> form() {
      return Part.parse(header("content-type"), body);
    }
  }

  /** One part of a multipart body. */
  public static final class Part {
    public final String name;
    public final String filename;
    public final String contentType;
    public final byte[] content;

    Part(String name, String filename, String contentType, byte[] content) {
      this.name = name;
      this.filename = filename;
      this.contentType = contentType;
      this.content = content;
    }

    public String text() {
      return new String(content, StandardCharsets.UTF_8);
    }

    /** {@code name=text} for text parts, {@code name=@filename(type):content} for files. */
    @Override
    public String toString() {
      return filename == null ? name + "=" + text() : name + "=@" + filename + "(" + contentType + "):" + text();
    }

    static List<Part> parse(String contentType, byte[] body) {
      if (contentType == null || !contentType.startsWith("multipart/form-data; boundary=")) {
        throw new AssertionError("not multipart: " + contentType);
      }
      String boundary = "--" + contentType.substring(contentType.indexOf('=') + 1);
      String text = new String(body, StandardCharsets.ISO_8859_1);
      List<Part> parts = new ArrayList<>();
      int at = text.indexOf(boundary);
      while (true) {
        int start = at + boundary.length();
        if (text.startsWith("--", start)) break;
        int headersEnd = text.indexOf("\r\n\r\n", start);
        int next = text.indexOf("\r\n" + boundary, headersEnd);
        String headerBlock = text.substring(start + 2, headersEnd);
        String name = null;
        String filename = null;
        String type = null;
        for (String line : headerBlock.split("\r\n")) {
          String lower = line.toLowerCase(Locale.ROOT);
          if (lower.startsWith("content-disposition:")) {
            name = param(line, "name");
            filename = param(line, "filename");
          } else if (lower.startsWith("content-type:")) {
            type = line.substring(line.indexOf(':') + 1).trim();
          }
        }
        byte[] content = text.substring(headersEnd + 4, next).getBytes(StandardCharsets.ISO_8859_1);
        parts.add(new Part(utf8(name), utf8(filename), type, content));
        at = next + 2;
      }
      return parts;
    }

    private static String param(String header, String key) {
      int i = header.indexOf("; " + key + "=\"");
      if (i < 0) return null;
      int start = i + key.length() + 4;
      return header.substring(start, header.indexOf('"', start));
    }

    private static String utf8(String latin1) {
      return latin1 == null ? null : new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }
  }

  /** A queued reply: a JsonNode/Map/List body is sent as JSON, byte[] as a PDF, String as text. */
  public static final class Reply {
    final int status;
    final Object body;
    final long delayMillis;
    final Map<String, String> headers = new LinkedHashMap<>();

    public Reply(int status, Object body) {
      this(status, body, 0);
    }

    public Reply(int status, Object body, long delayMillis) {
      this.status = status;
      this.body = body;
      this.delayMillis = delayMillis;
    }

    public Reply header(String name, String value) {
      headers.put(name, value);
      return this;
    }
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();
  private final AtomicInteger tokensIssued = new AtomicInteger();
  private final AtomicInteger preRegistrationTokensIssued = new AtomicInteger();
  /** Seconds until issued tokens expire. */
  public volatile long tokenLifetimeSeconds = 3600;
  /** Delay before answering token requests, to make concurrent callers overlap. */
  public volatile long tokenDelayMillis = 0;
  /** Requests carrying this bearer token get {@code 401 HTTP Token: Access denied.} */
  public volatile String rejectedToken;

  public MockServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    server.setExecutor(executor);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void reply(String method, String path, Reply... queued) {
    Deque<Reply> deque = replies.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>());
    synchronized (deque) {
      for (Reply r : queued) deque.add(r);
    }
  }

  public void reply(String method, String path, int status, Object body) {
    reply(method, path, new Reply(status, body));
  }

  public List<Request> requests() {
    return new ArrayList<>(requests);
  }

  /** Requests to this decoded path. */
  public List<Request> calls(String path) {
    List<Request> out = new ArrayList<>();
    for (Request r : requests) if (r.path.equals(path)) out.add(r);
    return out;
  }

  /** Requests other than token requests. */
  public List<Request> apiCalls() {
    List<Request> out = new ArrayList<>();
    for (Request r : requests) if (!r.path.equals(TOKEN_PATH) && !r.path.equals(PREREG_TOKEN_PATH)) out.add(r);
    return out;
  }

  public int tokensIssued() {
    return tokensIssued.get();
  }

  public int preRegistrationTokensIssued() {
    return preRegistrationTokensIssued.get();
  }

  public static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    byte[] body;
    try (InputStream in = exchange.getRequestBody()) {
      body = in.readAllBytes();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
    Request request = new Request(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getRawPath(),
        exchange.getRequestURI().getPath(),
        exchange.getRequestURI().getRawQuery(),
        headers,
        body);
    requests.add(request);
    Reply reply = respond(request);
    try {
      if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    byte[] payload;
    String type;
    if (reply.body == null) {
      payload = new byte[0];
      type = "application/json";
    } else if (reply.body instanceof byte[]) {
      payload = (byte[]) reply.body;
      type = "application/pdf";
    } else if (reply.body instanceof String) {
      payload = ((String) reply.body).getBytes(StandardCharsets.UTF_8);
      type = "text/plain";
    } else {
      payload = JSON.writeValueAsBytes(reply.body);
      type = "application/json";
    }
    exchange.getResponseHeaders().set("Content-Type", type);
    reply.headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
    exchange.sendResponseHeaders(reply.status, payload.length == 0 ? -1 : payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  private Reply respond(Request request) {
    if (rejectedToken != null && ("Bearer " + rejectedToken).equals(request.header("authorization"))) {
      return new Reply(401, "HTTP Token: Access denied.");
    }
    Deque<Reply> queued = replies.get(request.method + " " + request.path);
    if (queued != null) {
      synchronized (queued) {
        if (!queued.isEmpty()) return queued.poll();
      }
    }
    Instant expires = Instant.now().plusSeconds(tokenLifetimeSeconds).truncatedTo(ChronoUnit.SECONDS);
    Map<String, Object> token = new LinkedHashMap<>();
    if (request.path.equals(TOKEN_PATH)) {
      if (!basic(USERNAME, PASSWORD).equals(request.header("authorization"))) {
        return new Reply(401, Map.of("message", "Wrong credentials"));
      }
      token.put("access_token", "token-" + tokensIssued.incrementAndGet());
      token.put("expires_at", expires.toString());
      return new Reply(200, token, tokenDelayMillis);
    }
    if (request.path.equals(PREREG_TOKEN_PATH)) {
      if (!basic(PREREG_USERNAME, PREREG_PASSWORD).equals(request.header("authorization"))) {
        return new Reply(401, Map.of("message", "Wrong credentials"));
      }
      token.put("access_token", "prereg-" + preRegistrationTokensIssued.incrementAndGet());
      token.put("expires_at", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(expires));
      return new Reply(200, token, tokenDelayMillis);
    }
    return new Reply(200, Map.of("ok", true));
  }

  static Map<String, List<String>> parseQuery(String rawQuery) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) return out;
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
    return out;
  }
}
