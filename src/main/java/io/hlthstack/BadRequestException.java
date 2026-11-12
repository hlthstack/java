package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 400 Bad Request: invalid parameters, plan or subscription, bad entity headers, or an action not allowed in the current state. */
public class BadRequestException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  BadRequestException(String message, int statusCode, JsonNode body, Map<String, List<String>> headers, List<String> messages) {
    super(message, statusCode, body, headers, messages);
  }
}
