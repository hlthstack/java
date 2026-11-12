package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Base class of every exception this library throws.
 *
 * <p>For a non-2xx HTTP response (thrown as one of the subclasses per status) it carries the
 * status code, the parsed body, the response headers and {@link #messages()}: the readable
 * messages pulled out of whichever error shape the endpoint uses. For other errors
 * ({@link ApiConnectionException}, {@link ValidationException}, {@link
 * WebhookVerificationException}) {@link #statusCode()} is 0 and {@link #body()} is null.
 */
public class HlthstackException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int statusCode;
  private final transient JsonNode body;
  private final transient Map<String, List<String>> headers;
  private final transient List<String> messages;

  HlthstackException(String message) {
    this(message, (Throwable) null);
  }

  HlthstackException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.body = null;
    this.headers = Collections.emptyMap();
    this.messages = Collections.emptyList();
  }

  HlthstackException(String message, List<String> messages) {
    super(message);
    this.statusCode = 0;
    this.body = null;
    this.headers = Collections.emptyMap();
    this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
  }

  HlthstackException(
      String message,
      int statusCode,
      JsonNode body,
      Map<String, List<String>> headers,
      List<String> messages) {
    super(message);
    this.statusCode = statusCode;
    this.body = body;
    Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (headers != null) copy.putAll(headers);
    this.headers = Collections.unmodifiableMap(copy);
    this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
  }

  /** The HTTP status code, or 0 when there was no HTTP response. */
  public int statusCode() {
    return statusCode;
  }

  /**
   * The parsed response body: JSON as a tree, a plain-text body (such as {@code HTTP Token: Access
   * denied.}) as a text node, or null when the body was empty or there was no response.
   */
  public JsonNode body() {
    return body;
  }

  /** The response headers (case-insensitive names); empty when there was no response. */
  public Map<String, List<String>> headers() {
    return headers;
  }

  /** The {@code x-request-id} response header, or null. */
  public String requestId() {
    List<String> values = headers.get("x-request-id");
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  /** Human-readable messages extracted from the body (for validation errors: "path: message"). */
  public List<String> messages() {
    return messages;
  }

  /** Builds the exception for a non-2xx response: one subclass per documented status. */
  static HlthstackException fromResponse(
      int status, JsonNode body, Map<String, List<String>> headers, String method, String url) {
    List<String> messages = extractMessages(body);
    String summary = messages.isEmpty() ? "Request failed" : String.join("; ", messages);
    String message = status + " " + method + " " + stripQuery(url) + ": " + summary;
    switch (status) {
      case 400:
        return new BadRequestException(message, status, body, headers, messages);
      case 401:
        return new AuthenticationException(message, status, body, headers, messages);
      case 403:
        return new PermissionDeniedException(message, status, body, headers, messages);
      case 404:
        return new NotFoundException(message, status, body, headers, messages);
      case 409:
        return new ConflictException(message, status, body, headers, messages);
      case 422:
        return new UnprocessableEntityException(message, status, body, headers, messages);
      default:
        if (status >= 500) return new ServerException(message, status, body, headers, messages);
        return new HlthstackException(message, status, body, headers, messages);
    }
  }

  static String stripQuery(String url) {
    int i = url.indexOf('?');
    return i < 0 ? url : url.substring(0, i);
  }

  /**
   * Flattens the API's error shapes into a list of messages: {@code {errors: {field: [..]}}},
   * {@code {errors: {messages: [..]}}}, {@code {errors: [..]}}, {@code {message}}, {@code {success:
   * false, message}}, {@code {msg, recall}}, {@code {error}}, {@code {errors: {msg}}}, {@code
   * {errors: {base}}}, or a plain-text body.
   */
  static List<String> extractMessages(JsonNode body) {
    List<String> out = new ArrayList<>();
    if (body == null) return out;
    if (body.isTextual()) {
      String text = body.asText().trim();
      if (!text.isEmpty()) out.add(text);
      return out;
    }
    if (!body.isObject()) return out;
    JsonNode errors = body.get("errors");
    if (errors != null && errors.isArray()) {
      out.addAll(strings(errors));
    } else if (errors != null && errors.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = errors.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String key = e.getKey();
        boolean plain = key.equals("messages") || key.equals("msg") || key.equals("base");
        for (String m : strings(e.getValue())) out.add(plain ? m : key + ": " + m);
      }
    }
    for (String key : new String[] {"message", "msg", "error"}) out.addAll(strings(body.get(key)));
    return out;
  }

  private static List<String> strings(JsonNode value) {
    List<String> out = new ArrayList<>();
    if (value == null) return out;
    if (value.isTextual()) out.add(value.asText());
    if (value.isArray()) {
      for (JsonNode item : value) if (item.isTextual()) out.add(item.asText());
    }
    return out;
  }
}
