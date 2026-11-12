package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes a multipart body from its nested shape into the bracketed field names the API reads:
 * {@code {customers: [{dob}]}} becomes {@code customers[][dob]}, {@code {files: [file]}} becomes
 * {@code files[]}, {@code {customer_details: {api_customer_id}}} becomes {@code
 * customer_details[api_customer_id]}. The bytes are built once so a request can be sent again
 * after a token refresh.
 */
final class Multipart {
  final String contentType;
  final byte[] body;

  private Multipart(String boundary, byte[] body) {
    this.contentType = "multipart/form-data; boundary=" + boundary;
    this.body = body;
  }

  static Multipart encode(JsonNode form) {
    String boundary = "hlthstack-" + UUID.randomUUID();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Iterator<Map.Entry<String, JsonNode>> it = form.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      write(out, boundary, e.getKey(), e.getValue());
    }
    ascii(out, "--" + boundary + "--\r\n");
    return new Multipart(boundary, out.toByteArray());
  }

  private static void write(ByteArrayOutputStream out, String boundary, String name, JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return;
    if (Validator.isFile(value)) {
      FileInput file = Validator.file(value);
      String type = file.contentType() == null ? "application/octet-stream" : file.contentType();
      part(out, boundary, name, file.filename(), type, file.data());
    } else if (value.isArray()) {
      for (JsonNode item : value) {
        if (item.isObject()) {
          for (Iterator<Map.Entry<String, JsonNode>> it = item.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            write(out, boundary, name + "[][" + e.getKey() + "]", e.getValue());
          }
        } else {
          write(out, boundary, name + "[]", item);
        }
      }
    } else if (value.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        write(out, boundary, name + "[" + e.getKey() + "]", e.getValue());
      }
    } else {
      String text = value.isNumber() ? Validator.plain(value) : value.asText();
      part(out, boundary, name, null, null, text.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void part(
      ByteArrayOutputStream out, String boundary, String name, String filename, String type, byte[] data) {
    ascii(out, "--" + boundary + "\r\n");
    String disposition = "Content-Disposition: form-data; name=\"" + escape(name) + "\"";
    if (filename != null) disposition += "; filename=\"" + escape(filename) + "\"";
    utf8(out, disposition + "\r\n");
    if (type != null) ascii(out, "Content-Type: " + type + "\r\n");
    ascii(out, "\r\n");
    out.write(data, 0, data.length);
    ascii(out, "\r\n");
  }

  private static String escape(String value) {
    return value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
  }

  private static void ascii(ByteArrayOutputStream out, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    out.write(bytes, 0, bytes.length);
  }

  private static void utf8(ByteArrayOutputStream out, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    out.write(bytes, 0, bytes.length);
  }
}
