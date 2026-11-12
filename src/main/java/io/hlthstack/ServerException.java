package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 5xx: a server error, or a provider's system could not be reached. Retry with backoff. */
public class ServerException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  ServerException(String message, int statusCode, JsonNode body, Map<String, List<String>> headers, List<String> messages) {
    super(message, statusCode, body, headers, messages);
  }
}
