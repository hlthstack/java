package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** 401 Unauthorized: wrong credentials, or an access token that was still rejected after one refresh. */
public class AuthenticationException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  AuthenticationException(String message, int statusCode, JsonNode body, Map<String, List<String>> headers, List<String> messages) {
    super(message, statusCode, body, headers, messages);
  }
}
