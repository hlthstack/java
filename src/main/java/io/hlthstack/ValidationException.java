package io.hlthstack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A request broke the API's input rules and was not sent (no access token was requested
 * either). {@link #errors()} lists every problem found. Checks that need data stored by hlthstack
 * (plan benefits, slot availability, ...) are left to the server and still arrive as HTTP errors.
 */
public class ValidationException extends HlthstackException {
  private static final long serialVersionUID = 1L;

  private final String operationId;
  private final transient List<ValidationIssue> errors;

  ValidationException(String operationId, List<ValidationIssue> errors) {
    super(describe(operationId, errors), lines(errors));
    this.operationId = operationId;
    this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
  }

  /** The operationId of the rejected call, such as {@code createAppointment}. */
  public String operationId() {
    return operationId;
  }

  /** Every problem found, in the order they were found. */
  public List<ValidationIssue> errors() {
    return errors;
  }

  private static List<String> lines(List<ValidationIssue> errors) {
    List<String> out = new ArrayList<>();
    for (ValidationIssue e : errors) out.add(e.path().isEmpty() ? e.message() : e.path() + ": " + e.message());
    return out;
  }

  private static String describe(String operationId, List<ValidationIssue> errors) {
    int n = errors.size();
    return "Invalid " + operationId + " request (" + n + " problem" + (n == 1 ? "" : "s") + "): "
        + String.join("; ", lines(errors));
  }
}
