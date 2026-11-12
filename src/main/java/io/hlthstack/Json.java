package io.hlthstack;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.io.IOException;
import java.io.UncheckedIOException;

/** The library's Jackson setup: exact decimals in and out. */
final class Json {
  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
          .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

  private Json() {}

  /** Any JSON-compatible value (Map, List, String, Number, Boolean, null, JsonNode) as a tree. */
  static JsonNode toTree(Object value) {
    if (value instanceof JsonNode) return ((JsonNode) value).deepCopy();
    try {
      return MAPPER.valueToTree(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "request values must be JSON-compatible (Map, List, String, Number, Boolean, null or"
              + " JsonNode): " + e.getMessage(),
          e);
    }
  }

  static byte[] write(JsonNode tree) {
    try {
      return MAPPER.writeValueAsBytes(tree);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot serialize the request body: " + e.getMessage(), e);
    }
  }

  /** Parses JSON, or returns null when {@code text} is not JSON. */
  static JsonNode parseOrNull(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (IOException e) {
      return null;
    }
  }

  static JsonNode parse(byte[] bytes) {
    try {
      return MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
