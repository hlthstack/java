package io.hlthstack;

import java.util.Objects;

/** One problem client-side validation found in a request. */
public final class ValidationIssue {
  private final String path;
  private final String message;
  private final String rule;

  public ValidationIssue(String path, String message, String rule) {
    this.path = Objects.requireNonNull(path, "path");
    this.message = Objects.requireNonNull(message, "message");
    this.rule = Objects.requireNonNull(rule, "rule");
  }

  /**
   * Where the problem is, using the method's own field names, such as {@code
   * customers[0].api_customer_id}. Empty when it concerns the request as a whole.
   */
  public String path() {
    return path;
  }

  /** What is wrong, such as {@code must match ^[0-9]{10}$}. */
  public String message() {
    return message;
  }

  /**
   * The JSON Schema keyword ({@code required}, {@code pattern}, ...) or the spec's rule kind
   * ({@code required_if}, {@code unique_by}, ...) that failed.
   */
  public String rule() {
    return rule;
  }

  ValidationIssue withPath(String newPath) {
    return new ValidationIssue(newPath, message, rule);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ValidationIssue)) return false;
    ValidationIssue other = (ValidationIssue) o;
    return path.equals(other.path) && message.equals(other.message) && rule.equals(other.rule);
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, message, rule);
  }

  /** {@code path: message [rule]}. */
  @Override
  public String toString() {
    return (path.isEmpty() ? "" : path + ": ") + message + " [" + rule + "]";
  }
}
