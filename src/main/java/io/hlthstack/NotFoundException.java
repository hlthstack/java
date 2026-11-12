package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 404 Not Found: member, cart or order not found, or a staging-only endpoint called in production. */
public class NotFoundException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  NotFoundException(String message, int statusCode, JsonNode body, Map<String, List<String>> headers, List<String> messages) {
    super(message, statusCode, body, headers, messages);
  }
}
