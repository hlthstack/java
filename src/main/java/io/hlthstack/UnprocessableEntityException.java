package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 422 Unprocessable Entity: a business rule or processing failure, or an unexpected server-side error. */
public class UnprocessableEntityException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  UnprocessableEntityException(String message, int statusCode, JsonNode body, Map<String, List<String>> headers, List<String> messages) {
    super(message, statusCode, body, headers, messages);
  }
}
