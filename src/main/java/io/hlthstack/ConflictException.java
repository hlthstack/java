package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 409 Conflict: a concurrent partner-payment confirmation. Safe to retry with backoff. */
public class ConflictException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  ConflictException(String message, int statusCode, JsonNode body, Map<String, List<String>> headers, List<String> messages) {
    super(message, statusCode, body, headers, messages);
  }
}
