package io.hlthstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hlthstack.codegen.BuildValidation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The committed validation tables match openapi/openapi.yaml, and the generator fails loudly. */
class GeneratedValidationTest {
  @Test
  void isUpToDate() throws Exception {
    String committed = new String(Files.readAllBytes(BuildValidation.TARGET), StandardCharsets.UTF_8);
    assertEquals(
        BuildValidation.generate(BuildValidation.SPEC), committed,
        "validation.json is out of date; run ./mvnw -q test-compile exec:java@generate-validation");
  }

  private static String spec() throws Exception {
    return new String(Files.readAllBytes(BuildValidation.SPEC), StandardCharsets.UTF_8);
  }

  private static String failure(Path dir, String spec) throws Exception {
    Path file = dir.resolve("openapi.yaml");
    Files.write(file, spec.getBytes(StandardCharsets.UTF_8));
    return assertThrows(BuildValidation.SpecError.class, () -> BuildValidation.generate(file)).getMessage();
  }

  @Test
  void failsOnAnUnsupportedKeyword(@TempDir Path dir) throws Exception {
    String message = failure(dir, spec().replaceFirst("pattern: '\\^\\[0-9\\]\\{6\\}\\$'", "multipleOf: 2"));
    assertTrue(message.contains("unsupported keyword \"multipleOf\""), message);
  }

  @Test
  void failsOnAnUnsupportedFormat(@TempDir Path dir) throws Exception {
    String message = failure(dir, spec().replaceFirst("format: uuid", "format: email"));
    assertTrue(message.contains("unsupported format \"email\""), message);
  }

  @Test
  void failsWhenARuleChanges(@TempDir Path dir) throws Exception {
    String message = failure(dir, spec().replace("Send api_customer_id or employee_id.", "Send api_customer_id."));
    assertTrue(message.contains("description differs from rules.yaml"), message);
  }

  @Test
  void failsOnAnUnknownRuleKind(@TempDir Path dir) throws Exception {
    String message = failure(dir, spec().replace("  positive_decimal: The value", "  luhn: A card number.\n  positive_decimal: The value"));
    assertTrue(message.contains("x-hlthstack-rule-kinds"), message);
  }
}
