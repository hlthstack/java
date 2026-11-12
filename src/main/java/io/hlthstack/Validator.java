package io.hlthstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side request validation.
 *
 * <p>{@code validation.json} holds, per operationId, the request schemas and the
 * x-hlthstack-rules of the OpenAPI spec (built by {@code BuildValidation} in the tests). This class
 * applies them: the subset of JSON Schema the spec uses, plus each rule kind. It collects every
 * problem instead of stopping at the first.
 *
 * <p>Inputs are JSON trees. Multipart bodies are given in their nested shape ({@code
 * customers[][dob]} is {@code customers[].dob}) with files as {@link POJONode}s holding a {@link
 * FileInput}.
 */
final class Validator {
  /** The API's calendar: India Standard Time (UTC+05:30, no daylight saving). */
  static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static volatile JsonNode operations;
  private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

  private Validator() {}

  /** The generated tables, per operationId. */
  static JsonNode operations() {
    JsonNode ops = operations;
    if (ops == null) {
      try (InputStream in = Validator.class.getResourceAsStream("validation.json")) {
        if (in == null) throw new IllegalStateException("io/hlthstack/validation.json is missing");
        ops = Json.MAPPER.readTree(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      operations = ops;
    }
    return ops;
  }

  /**
   * Every problem with a request (empty when it is valid).
   *
   * @param params path parameters by their spec names, or null
   * @param query query parameters (nested objects for bracketed names), or null
   * @param body the JSON body, or null when none is sent
   * @param form the multipart body in its nested shape, or null when the request is not multipart
   * @param now the clock for date rules
   */
  static List<ValidationIssue> validate(
      String operationId, JsonNode params, JsonNode query, JsonNode body, JsonNode form, Instant now) {
    JsonNode spec = operations().get(operationId);
    if (spec == null) throw new IllegalArgumentException("Unknown operation " + operationId);
    List<ValidationIssue> issues = new ArrayList<>();
    JsonNode cleanQuery = query == null ? null : stripNulls(query);

    if (spec.has("params")) check(spec.get("params"), orEmpty(params), "", issues, true);
    if (spec.has("query")) check(spec.get("query"), orEmpty(cleanQuery), "", issues, true);
    boolean multipart = form != null;
    JsonNode bodySchema = spec.get(multipart ? "form" : "body");
    JsonNode value = multipart ? form : body;
    if (bodySchema != null) {
      if (value == null || value.isMissingNode()) {
        if (spec.path("bodyRequired").asBoolean()) {
          issues.add(new ValidationIssue("", "a request body is required", "required"));
        }
      } else {
        check(bodySchema, value, "", issues, multipart);
      }
    }

    JsonNode rules = spec.get("rules");
    if (rules != null) {
      ObjectNode root = Json.MAPPER.createObjectNode();
      for (JsonNode part : new JsonNode[] {params, cleanQuery, value}) {
        if (part != null && part.isObject()) root.setAll((ObjectNode) part);
      }
      for (JsonNode rule : rules) applyRule(rule, root, issues, now);
    }
    return issues;
  }

  private static JsonNode orEmpty(JsonNode node) {
    return node == null ? Json.MAPPER.createObjectNode() : node;
  }

  /** Query values that are null are not sent, so they count as absent. */
  private static JsonNode stripNulls(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = Json.MAPPER.createObjectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                if (!e.getValue().isNull()) out.set(e.getKey(), stripNulls(e.getValue()));
              });
      return out;
    }
    if (node.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode out = Json.MAPPER.createArrayNode();
      for (JsonNode item : node) if (!item.isNull()) out.add(stripNulls(item));
      return out;
    }
    return node;
  }

  // ------------------------------------------------------------------ schemas

  static boolean isFile(JsonNode value) {
    return value instanceof POJONode && ((POJONode) value).getPojo() instanceof FileInput;
  }

  static FileInput file(JsonNode value) {
    return (FileInput) ((POJONode) value).getPojo();
  }

  private static String join(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  private static String index(String path, int i) {
    return path + "[" + i + "]";
  }

  private static boolean isIntegral(JsonNode value) {
    if (!value.isNumber()) return false;
    if (value.isIntegralNumber()) return true;
    if (value.isDouble() || value.isFloat()) {
      double d = value.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) return false;
    }
    BigDecimal d = value.decimalValue();
    return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0;
  }

  private static boolean isFiniteNumber(JsonNode value) {
    if (!value.isNumber()) return false;
    if (value.isDouble() || value.isFloat()) {
      double d = value.doubleValue();
      return !Double.isNaN(d) && !Double.isInfinite(d);
    }
    return true;
  }

  static String typeOf(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return "null";
    if (isFile(value)) return "file";
    if (value.isArray()) return "array";
    if (value.isObject()) return "object";
    if (value.isBoolean()) return "boolean";
    if (value.isTextual()) return "string";
    if (value.isNumber()) return isIntegral(value) ? "integer" : "number";
    return value.getNodeType().toString().toLowerCase(Locale.ROOT);
  }

  private static boolean hasType(String type, JsonNode value) {
    switch (type) {
      case "null":
        return value.isNull();
      case "boolean":
        return value.isBoolean();
      case "string":
        return value.isTextual();
      case "number":
        return isFiniteNumber(value);
      case "integer":
        return isIntegral(value);
      case "array":
        return value.isArray();
      case "object":
        return value.isObject();
      default:
        throw new IllegalStateException("unknown type " + type);
    }
  }

  private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?[0-9]+");
  private static final Pattern NUMBER_TEXT =
      Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

  /**
   * Query, path and multipart values travel as text. A number or boolean is accepted where a
   * string is expected (checked as its text), and text where a number, integer or boolean is
   * expected when it reads as one, as the server parses it. Returns null when nothing fits.
   */
  private static JsonNode coerce(List<String> types, JsonNode value) {
    if ((value.isNumber() || value.isBoolean()) && types.contains("string")) {
      return TextNode.valueOf(value.isNumber() ? plain(value) : value.asText());
    }
    if (value.isTextual()) {
      String text = value.asText();
      if (types.contains("integer") && INTEGER_TEXT.matcher(text).matches()) {
        return DecimalNode.valueOf(new BigDecimal(text));
      }
      if (types.contains("number") && NUMBER_TEXT.matcher(text).matches()) {
        return DecimalNode.valueOf(new BigDecimal(text));
      }
      if (types.contains("boolean") && (text.equals("true") || text.equals("false"))) {
        return BooleanNode.valueOf(text.equals("true"));
      }
    }
    return null;
  }

  /** A number as plain text (no exponent). */
  static String plain(JsonNode number) {
    if (number.isIntegralNumber()) return number.bigIntegerValue().toString();
    if (number.isDouble() || number.isFloat()) return number.asText();
    return number.decimalValue().toPlainString();
  }

  private static Pattern regex(String pattern) {
    return PATTERNS.computeIfAbsent(pattern, Pattern::compile);
  }

  private static String show(JsonNode value) {
    if (value.isTextual()) return Json.MAPPER.valueToTree(value.asText()).toString();
    if (value.isNumber()) return plain(value);
    return value.toString();
  }

  private static String listValues(JsonNode values) {
    List<String> shown = new ArrayList<>();
    for (int i = 0; i < values.size() && i < 8; i++) shown.add(show(values.get(i)));
    String out = String.join(", ", shown);
    return values.size() > 8 ? out + ", ... (" + values.size() + " values)" : out;
  }

  private static boolean sameValue(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue()) == 0;
    return a.equals(b);
  }

  private static void add(List<ValidationIssue> issues, String path, String message, String rule) {
    issues.add(new ValidationIssue(path, message, rule));
  }

  /**
   * Checks {@code value} against {@code schema}, appending problems to {@code issues}. {@code
   * loose} is for path, query and multipart values (see {@link #coerce}).
   */
  static void check(JsonNode schema, JsonNode value, String path, List<ValidationIssue> issues, boolean loose) {
    if (schema.path("file").asBoolean()) {
      if (!isFile(value)) {
        add(issues, path, "must be a file (FileInput), got " + typeOf(value), "type");
      }
      return;
    }

    JsonNode type = schema.get("type");
    if (type != null) {
      List<String> types = new ArrayList<>();
      if (type.isArray()) type.forEach(t -> types.add(t.asText()));
      else types.add(type.asText());
      boolean matches = false;
      for (String t : types) matches |= !isFile(value) && hasType(t, value);
      if (!matches) {
        JsonNode coerced = loose && !isFile(value) ? coerce(types, value) : null;
        if (coerced == null) {
          add(issues, path, "must be " + String.join(" or ", types) + ", got " + typeOf(value), "type");
          return;
        }
        value = coerced;
      }
    }

    JsonNode allowed = schema.get("enum");
    if (allowed != null) {
      boolean found = false;
      for (JsonNode e : allowed) found |= sameValue(e, value);
      if (!found) add(issues, path, "must be one of " + listValues(allowed), "enum");
    }

    if (value.isTextual()) checkString(schema, value.asText(), path, issues);
    if (isFiniteNumber(value)) checkNumber(schema, value.decimalValue(), path, issues);
    if (value.isArray()) {
      if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
        add(issues, path, "must have at least " + schema.get("minItems").asInt() + " item(s)", "minItems");
      }
      if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
        add(issues, path, "must have at most " + schema.get("maxItems").asInt() + " item(s)", "maxItems");
      }
      if (schema.has("items")) {
        for (int i = 0; i < value.size(); i++) check(schema.get("items"), value.get(i), index(path, i), issues, loose);
      }
    }
    if (value.isObject()) checkObject(schema, value, path, issues, loose);

    for (JsonNode branch : schema.path("allOf")) check(branch, value, path, issues, loose);
    if (schema.has("anyOf")) checkAlternatives(schema.get("anyOf"), "anyOf", value, path, issues, loose);
    if (schema.has("oneOf")) checkAlternatives(schema.get("oneOf"), "oneOf", value, path, issues, loose);
  }

  private static void checkString(JsonNode schema, String value, String path, List<ValidationIssue> issues) {
    int length = value.codePointCount(0, value.length());
    if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
      add(issues, path, "must be at least " + schema.get("minLength").asInt() + " characters", "minLength");
    }
    if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
      add(issues, path, "must be at most " + schema.get("maxLength").asInt() + " characters", "maxLength");
    }
    if (schema.has("pattern")) {
      // Unanchored, like the server's own checks: the spec anchors patterns where it means to.
      String pattern = schema.get("pattern").asText();
      if (!regex(pattern).matcher(value).find()) add(issues, path, "must match " + pattern, "pattern");
    }
    if (schema.has("format")) {
      String format = schema.get("format").asText();
      if (!validFormat(format, value)) add(issues, path, "must be " + formatName(format), "format");
    }
  }

  private static void checkNumber(JsonNode schema, BigDecimal value, String path, List<ValidationIssue> issues) {
    if (schema.has("minimum") && value.compareTo(schema.get("minimum").decimalValue()) < 0) {
      add(issues, path, "must be >= " + plain(schema.get("minimum")), "minimum");
    }
    if (schema.has("maximum") && value.compareTo(schema.get("maximum").decimalValue()) > 0) {
      add(issues, path, "must be <= " + plain(schema.get("maximum")), "maximum");
    }
    if (schema.has("exclusiveMinimum") && value.compareTo(schema.get("exclusiveMinimum").decimalValue()) <= 0) {
      add(issues, path, "must be > " + plain(schema.get("exclusiveMinimum")), "exclusiveMinimum");
    }
  }

  private static void checkObject(
      JsonNode schema, JsonNode value, String path, List<ValidationIssue> issues, boolean loose) {
    for (JsonNode key : schema.path("required")) {
      if (!value.has(key.asText())) add(issues, join(path, key.asText()), "is required", "required");
    }
    JsonNode properties = schema.path("properties");
    for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode property = properties.get(e.getKey());
      if (property != null) check(property, e.getValue(), join(path, e.getKey()), issues, loose);
    }
  }

  private static void checkAlternatives(
      JsonNode branches, String keyword, JsonNode value, String path, List<ValidationIssue> issues, boolean loose) {
    List<List<ValidationIssue>> results = new ArrayList<>();
    int passing = 0;
    for (JsonNode branch : branches) {
      List<ValidationIssue> found = new ArrayList<>();
      check(branch, value, path, found, loose);
      results.add(found);
      if (found.isEmpty()) passing++;
    }
    if (keyword.equals("oneOf") && passing > 1) {
      add(issues, path, "matches more than one of the accepted forms", "oneOf");
      return;
    }
    if (passing > 0) return;
    // When only one branch accepts the value's type, its problems are the useful ones.
    List<List<ValidationIssue>> typed = new ArrayList<>();
    for (List<ValidationIssue> r : results) {
      boolean wrongType = false;
      for (ValidationIssue i : r) wrongType |= i.rule().equals("type") && i.path().equals(path);
      if (!wrongType) typed.add(r);
    }
    if (typed.size() == 1) {
      issues.addAll(typed.get(0));
      return;
    }
    add(issues, path, "does not match any of the accepted forms", keyword);
  }

  // ------------------------------------------------------------------ formats

  private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  /** The offset is optional: the API reads a date-time without one as IST. */
  private static final Pattern DATE_TIME =
      Pattern.compile(
          "(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?([Zz]|[+-]\\d{2}:?\\d{2})?");
  private static final Pattern URI = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:[^\\s]*");
  private static final Pattern UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static boolean validDay(int y, int m, int d) {
    try {
      LocalDate.of(y, m, d);
      return true;
    } catch (DateTimeException e) {
      return false;
    }
  }

  private static int num(Matcher m, int group) {
    return Integer.parseInt(m.group(group));
  }

  static boolean validFormat(String format, String value) {
    Matcher m;
    switch (format) {
      case "date":
        m = DATE.matcher(value);
        return m.matches() && validDay(num(m, 1), num(m, 2), num(m, 3));
      case "date-time":
        m = DATE_TIME.matcher(value);
        if (!m.matches() || !validDay(num(m, 1), num(m, 2), num(m, 3))) return false;
        int seconds = m.group(6) == null ? 0 : num(m, 6);
        return num(m, 4) < 24 && num(m, 5) < 60 && seconds < 61;
      case "uri":
        return URI.matcher(value).matches();
      case "uuid":
        return UUID.matcher(value).matches();
      default:
        throw new IllegalStateException("unsupported format " + format);
    }
  }

  private static String formatName(String format) {
    switch (format) {
      case "date":
        return "a date (YYYY-MM-DD)";
      case "date-time":
        return "an ISO 8601 date-time (2026-01-15T03:30:00Z)";
      case "uri":
        return "a URI";
      case "uuid":
        return "a UUID";
      default:
        return format;
    }
  }

  // ------------------------------------------------------------------ rules

  private static final class Located {
    final String path;
    /** null when the field is not sent. */
    final JsonNode value;

    Located(String path, JsonNode value) {
      this.path = path;
      this.value = value;
    }
  }

  /**
   * Resolves a dotted path ({@code customers[].dob}, {@code address.pincode}) to every value it
   * names. Missing parents yield nothing; a missing leaf yields a null value.
   */
  private static List<Located> select(JsonNode root, String field) {
    List<Located> current = new ArrayList<>();
    current.add(new Located("", root));
    for (String part : field.split("\\.")) {
      boolean array = part.endsWith("[]");
      String key = array ? part.substring(0, part.length() - 2) : part;
      List<Located> next = new ArrayList<>();
      for (Located located : current) {
        if (located.value == null || !located.value.isObject()) continue;
        JsonNode child = located.value.get(key);
        String childPath = join(located.path, key);
        if (!array) {
          next.add(new Located(childPath, child));
        } else if (child != null && child.isArray()) {
          for (int i = 0; i < child.size(); i++) next.add(new Located(index(childPath, i), child.get(i)));
        }
      }
      current = next;
    }
    return current;
  }

  /** The objects a rule applies to: the root, or every object at {@code scope}. */
  private static List<Located> containers(JsonNode root, String scope) {
    List<Located> out = new ArrayList<>();
    if (scope == null || scope.isEmpty()) {
      out.add(new Located("", root));
      return out;
    }
    for (Located c : select(root, scope)) if (c.value != null && c.value.isObject()) out.add(c);
    return out;
  }

  /** Not sent, null, blank text, or an empty list or object. */
  static boolean blank(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return true;
    if (value.isTextual()) return value.asText().trim().isEmpty();
    if (value.isContainerNode()) return value.size() == 0;
    return false;
  }

  private static String text(JsonNode value) {
    return value.isNumber() ? plain(value) : value.asText();
  }

  private static boolean holds(JsonNode condition, JsonNode scope) {
    JsonNode value = scope.get(condition.get("field").asText());
    if (condition.has("blank")) return blank(value) == condition.get("blank").asBoolean();
    boolean equal;
    String expected = condition.has("equals") ? condition.get("equals").asText() : condition.get("notEquals").asText();
    equal = !blank(value) && text(value).trim().toLowerCase(Locale.ROOT).equals(expected.toLowerCase(Locale.ROOT));
    return condition.has("equals") ? equal : !equal;
  }

  private static String describeCondition(JsonNode condition) {
    String field = condition.get("field").asText();
    if (condition.has("blank")) return "when " + field + " is not sent";
    if (condition.has("notEquals")) return "unless " + field + " is " + condition.get("notEquals").asText();
    return "when " + field + " is " + condition.get("equals").asText();
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null) array.forEach(n -> out.add(n.asText()));
    return out;
  }

  private static void applyRule(JsonNode rule, JsonNode root, List<ValidationIssue> issues, Instant now) {
    String kind = rule.get("rule").asText();
    switch (kind) {
      case "required_if":
        if (!holds(rule.get("when"), root)) return;
        for (String field : strings(rule.get("required"))) {
          if (blank(root.get(field))) add(issues, field, "is required " + describeCondition(rule.get("when")), kind);
        }
        return;

      case "allowed_only_if":
        if (holds(rule.get("when"), root)) return;
        for (String field : strings(rule.get("allowed"))) {
          if (!blank(root.get(field))) {
            JsonNode when = rule.get("when");
            add(issues, field, "is accepted only when " + when.get("field").asText() + " is " + when.get("equals").asText(), kind);
          }
        }
        return;

      case "at_least_one_of":
        for (Located c : containers(root, rule.path("scope").asText(""))) {
          boolean satisfied = false;
          List<String> options = new ArrayList<>();
          boolean single = true;
          for (JsonNode group : rule.get("groups")) {
            boolean complete = true;
            for (JsonNode f : group) complete &= !blank(c.value.get(f.asText()));
            satisfied |= complete;
            options.add(String.join(" and ", strings(group)));
            single &= group.size() == 1;
          }
          if (!satisfied) {
            String message = single
                ? "send at least one of " + String.join(", ", options)
                : "send " + String.join(", or ", options);
            add(issues, c.path, message, kind);
          }
        }
        return;

      case "mutually_exclusive":
        for (Located c : containers(root, rule.path("scope").asText(""))) {
          int sent = 0;
          for (String f : strings(rule.get("exclusive"))) if (!blank(c.value.get(f))) sent++;
          if (sent > 1) add(issues, c.path, "send only one of " + String.join(", ", strings(rule.get("exclusive"))), kind);
        }
        return;

      case "unique_by": {
        Map<String, String> seen = new HashMap<>();
        for (Located l : select(root, rule.get("array").asText() + "[]." + rule.get("key").asText())) {
          if (l.value == null || l.value.isNull()) continue;
          String key = text(l.value);
          String first = seen.get(key);
          if (first != null) add(issues, l.path, "duplicates " + first + " (" + show(l.value) + ")", kind);
          else seen.put(key, l.path);
        }
        return;
      }

      case "positive_decimal":
        for (String target : strings(rule.get("targets"))) {
          for (Located l : select(root, target)) {
            if (l.value == null || l.value.isNull()) continue;
            if (!isPositiveDecimal(l.value)) add(issues, l.path, "must be a decimal number greater than 0", kind);
          }
        }
        return;

      case "item_count": {
        List<String> arrays = strings(rule.get("arrays"));
        int total = 0;
        for (String array : arrays) {
          List<Located> found = select(root, array);
          if (!found.isEmpty() && found.get(0).value != null && found.get(0).value.isArray()) {
            total += found.get(0).value.size();
          }
        }
        String what = String.join(" and ", arrays);
        String path = arrays.size() == 1 ? arrays.get(0) : "";
        String together = arrays.size() > 1 ? " together" : "";
        Integer min = rule.has("min") ? rule.get("min").asInt() : null;
        Integer max = rule.has("max") ? rule.get("max").asInt() : null;
        if (min != null && min.equals(max) && total != min) {
          add(issues, path, what + " must have exactly " + min + " item(s)" + together + ", got " + total, kind);
        } else if (min != null && total < min) {
          add(issues, path, what + " must have at least " + min + " item(s)" + together + ", got " + total, kind);
        } else if (max != null && total > max) {
          add(issues, path, what + " must have at most " + max + " item(s)" + together + ", got " + total, kind);
        }
        return;
      }

      case "relative_date":
        if (rule.has("unless") && holds(rule.get("unless"), root)) return;
        for (String target : strings(rule.get("targets"))) {
          for (Located l : select(root, target)) {
            if (blank(l.value) || !l.value.isTextual()) continue;
            String problem = checkRelativeDate(rule, l.value.asText(), now);
            if (problem != null) add(issues, l.path, problem, kind);
          }
        }
        return;

      case "no_conflicting_pincode":
        if (rule.has("when") && !holds(rule.get("when"), root)) return;
        for (Located address : containers(root, rule.get("address").asText())) {
          JsonNode country = address.value.get("country");
          if (!blank(country) && !country.asText().trim().equalsIgnoreCase("india")) continue;
          List<String> pincodes = new ArrayList<>();
          for (String f : strings(rule.get("pincode"))) {
            JsonNode p = address.value.get(f);
            if (!blank(p)) pincodes.add(text(p).trim());
          }
          if (pincodes.isEmpty()) continue;
          for (String f : strings(rule.get("text"))) {
            JsonNode value = address.value.get(f);
            if (value == null || !value.isTextual()) continue;
            Matcher m = SIX_DIGITS.matcher(value.asText());
            while (m.find()) {
              if (!pincodes.contains(m.group())) {
                add(issues, join(address.path, f),
                    "contains the pincode " + m.group() + ", which differs from the address pincode " + pincodes.get(0), kind);
                break;
              }
            }
          }
        }
        return;

      case "file_limits":
        for (Located l : select(root, rule.get("files").asText())) {
          if (l.value == null || !isFile(l.value)) continue;
          checkFile(rule, file(l.value), l.path, issues);
        }
        return;

      default:
        throw new IllegalStateException("rule kind " + kind + " is not implemented");
    }
  }

  private static final Pattern SIX_DIGITS = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
  private static final Pattern DECIMAL_TEXT = Pattern.compile("\\s*[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)\\s*");

  private static boolean isPositiveDecimal(JsonNode value) {
    if (value.isNumber()) return isFiniteNumber(value) && value.decimalValue().signum() > 0;
    if (!value.isTextual() || !DECIMAL_TEXT.matcher(value.asText()).matches()) return false;
    return new BigDecimal(value.asText().trim()).signum() > 0;
  }

  private static void checkFile(JsonNode rule, FileInput file, String path, List<ValidationIssue> issues) {
    String kind = "file_limits";
    if (rule.has("maxBytes") && file.size() > rule.get("maxBytes").asLong()) {
      add(issues, path, "is " + file.size() + " bytes; at most " + rule.get("maxBytes").asLong() + " bytes are accepted", kind);
    }
    List<String> extensions = rule.has("extensions") ? strings(rule.get("extensions")) : null;
    List<String> types = rule.has("contentTypes") ? strings(rule.get("contentTypes")) : null;
    String name = file.filename();
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? null : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    String type = file.contentType() == null ? null : file.contentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
    boolean extensionOk = extensions == null || (extension != null && extensions.contains(extension));
    boolean typeOk = types == null || (type != null && types.contains(type));
    String gotName = show(TextNode.valueOf(name));
    String gotType = type == null ? "no content type" : show(TextNode.valueOf(type));
    if (rule.path("match").asText("all").equals("any") && extensions != null && types != null) {
      if (!extensionOk && !typeOk) {
        add(issues, path, "must be " + String.join(", ", types) + " (a filename ending in ." + String.join(", .", extensions)
            + "); got " + gotName + " with " + gotType, kind);
      }
      return;
    }
    if (!extensionOk) {
      add(issues, path, "needs a filename ending in ." + String.join(", .", extensions) + " (got " + gotName + ")", kind);
    }
    if (!typeOk) {
      add(issues, path, "must be " + String.join(", ", types) + " (got " + gotType
          + "; give the FileInput a filename with a known extension or a content type)", kind);
    }
  }

  private static final Pattern YMD = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern DMY = Pattern.compile("(\\d{2})[-/](\\d{2})[-/](\\d{4})");
  private static final Pattern HAS_OFFSET = Pattern.compile("(?:Z|[+-]\\d{2}:?\\d{2})$");

  /** {@code YYYY-MM-DD}, {@code DD-MM-YYYY} or {@code DD/MM/YYYY}, or null. */
  private static LocalDate parseDay(String value) {
    String v = value.trim();
    Matcher m = YMD.matcher(v);
    if (m.matches() && validDay(num(m, 1), num(m, 2), num(m, 3))) return LocalDate.of(num(m, 1), num(m, 2), num(m, 3));
    m = DMY.matcher(v);
    if (m.matches() && validDay(num(m, 3), num(m, 2), num(m, 1))) return LocalDate.of(num(m, 3), num(m, 2), num(m, 1));
    return null;
  }

  private static String checkRelativeDate(JsonNode rule, String value, Instant now) {
    if (rule.has("after")) {
      if (!validFormat("date-time", value)) return null; // the format check reports it
      String text = value.trim().toUpperCase(Locale.ROOT).replace(' ', 'T').replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
      boolean offset = HAS_OFFSET.matcher(text).find();
      String defaultOffset = rule.path("defaultOffset").asText("Z");
      if (!offset) text += defaultOffset;
      Instant at;
      try {
        at = OffsetDateTime.parse(text).toInstant();
      } catch (DateTimeException e) {
        return null;
      }
      if (at.isAfter(now)) return null;
      String note = !offset && rule.has("defaultOffset") ? "; without an offset it is read as " + defaultOffset : "";
      return "must be later than now (" + now + ")" + note;
    }
    LocalDate day = parseDay(value);
    if (day == null) return null; // the format or pattern check reports it
    int years = rule.path("years").asInt(0);
    LocalDate limit = now.atOffset(IST).toLocalDate().plusYears(years); // 29 Feb -> 28 Feb
    if (!day.isAfter(limit)) return null;
    return years != 0
        ? "must be on or before " + limit + " (at least " + -years + " year(s) ago)"
        : "must not be after today (" + limit + ")";
  }
}
