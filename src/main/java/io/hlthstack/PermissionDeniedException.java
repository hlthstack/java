package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 403 Forbidden: the booking or appointment isn't yours, the service isn't enabled on your plan, or the pre-registration account's role may not use the API. */
public class PermissionDeniedException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  PermissionDeniedException(String message, int statusCode, JsonNode body, Map<String, List<String>> headers, List<String> messages) {
    super(message, statusCode, body, headers, messages);
  }
}
