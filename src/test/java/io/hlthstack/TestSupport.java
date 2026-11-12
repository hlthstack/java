package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared test helpers. */
public final class TestSupport {
  private static JsonNode spec;

  private TestSupport() {}

  /** An ordered map from alternating keys and values (values may be null). */
  public static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    return out;
  }

  public static List<Object> list(Object... items) {
    return new ArrayList<>(Arrays.asList(items));
  }

  /** A client for the mock server, on staging, with both sets of credentials. */
  public static Hlthstack.Builder builder(MockServer server) {
    return Hlthstack.builder()
        .username(MockServer.USERNAME)
        .password(MockServer.PASSWORD)
        .preRegistrationUsername(MockServer.PREREG_USERNAME)
        .preRegistrationPassword(MockServer.PREREG_PASSWORD)
        .environment(Environment.STAGING)
        .baseUrl(server.url());
  }

  public static Hlthstack client(MockServer server) {
    return builder(server).build();
  }

  /** openapi/openapi.yaml as a tree. */
  public static synchronized JsonNode spec() {
    if (spec == null) {
      try {
        spec = new YAMLMapper().readTree(Paths.get("openapi", "openapi.yaml").toFile());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return spec;
  }

  public static JsonNode json(String text) {
    try {
      return Json.MAPPER.readTree(text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
