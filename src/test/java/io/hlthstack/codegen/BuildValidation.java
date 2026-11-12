package io.hlthstack.codegen;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Builds {@code src/main/resources/io/hlthstack/validation.json} from {@code openapi/openapi.yaml}.
 *
 * <pre>
 * ./mvnw -q test-compile exec:java@generate-validation                 # rewrite the file
 * ./mvnw -q test-compile exec:java@generate-validation -Dexec.args=--check  # fail if stale
 * </pre>
 *
 * <p>For every operation it resolves the path, query and request-body schemas, keeps only the
 * validation keywords, and rewrites multipart field names ({@code customers[][dob]}) into the
 * nested shape the server parses them into ({@code customers[].dob}). The operation's
 * {@code x-hlthstack-rules} describe their conditions in prose, so {@code rules.yaml} restates each
 * one as parameters for the validator; this class fails if the two disagree.
 *
 * <p>It fails loudly on anything {@code io.hlthstack.Validator} does not implement: an unknown
 * schema keyword, type, format, rule kind, parameter location or content type. Extend the
 * validator and this class together.
 */
public final class BuildValidation {
  public static final Path SPEC = Paths.get("openapi", "openapi.yaml");
  public static final Path TARGET =
      Paths.get("src", "main", "resources", "io", "hlthstack", "validation.json");

  /** Keywords the validator implements: exactly those the request schemas use. */
  static final Set<String> KEYWORDS =
      Set.of(
          "type", "properties", "required", "items", "enum", "pattern", "minLength", "maxLength",
          "minimum", "maximum", "exclusiveMinimum", "minItems", "maxItems", "format", "oneOf",
          "anyOf", "allOf");
  /** Keywords that do not constrain the value. */
  static final Set<String> ANNOTATIONS =
      Set.of(
          "description", "example", "examples", "default", "deprecated", "title", "readOnly",
          "writeOnly", "contentEncoding");
  static final Set<String> FORMATS = Set.of("date", "date-time", "uri", "uuid");
  static final Set<String> TYPES =
      Set.of("string", "number", "integer", "boolean", "object", "array", "null");
  static final Set<String> RULE_KINDS =
      Set.of(
          "required_if", "at_least_one_of", "mutually_exclusive", "allowed_only_if", "unique_by",
          "relative_date", "no_conflicting_pincode", "file_limits", "item_count",
          "positive_decimal");
  static final List<String> METHODS = List.of("get", "put", "post", "patch", "delete");

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final JsonNode spec;
  private final JsonNode table;

  BuildValidation(JsonNode spec, JsonNode table) {
    this.spec = spec;
    this.table = table;
  }

  /** The generated file's content for the spec at {@code specPath}. */
  public static String generate(Path specPath) throws IOException {
    YAMLMapper yaml = new YAMLMapper();
    JsonNode spec = yaml.readTree(specPath.toFile());
    JsonNode table;
    try (InputStream in = BuildValidation.class.getResourceAsStream("rules.yaml")) {
      if (in == null) throw new IllegalStateException("rules.yaml not found on the classpath");
      table = yaml.readTree(in);
    }
    ObjectNode operations = new BuildValidation(spec, table).build();
    DefaultPrettyPrinter printer =
        new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
    return new ObjectMapper().writer(printer).writeValueAsString(operations) + "\n";
  }

  public static void main(String[] args) throws IOException {
    boolean check = Arrays.asList(args).contains("--check");
    String output;
    try {
      output = generate(SPEC);
    } catch (SpecError e) {
      System.err.println("BuildValidation: " + e.getMessage());
      System.exit(2);
      return;
    }
    if (check) {
      String current =
          Files.exists(TARGET) ? new String(Files.readAllBytes(TARGET), StandardCharsets.UTF_8) : "";
      if (!current.equals(output)) {
        System.err.println(
            TARGET + " is out of date; run ./mvnw -q test-compile exec:java@generate-validation");
        System.exit(1);
      }
      System.out.println(TARGET + " is up to date");
      return;
    }
    Files.write(TARGET, output.getBytes(StandardCharsets.UTF_8));
    System.out.println("BuildValidation: wrote " + TARGET);
  }

  /** A spec construct the validator does not support. */
  public static final class SpecError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SpecError(String where, String message) {
      super(where + ": " + message);
    }
  }

  // ------------------------------------------------------------------ operations

  ObjectNode build() {
    Set<String> kinds = new TreeSet<>();
    spec.path("x-hlthstack-rule-kinds").fieldNames().forEachRemaining(kinds::add);
    if (!kinds.equals(new TreeSet<>(RULE_KINDS))) {
      throw new SpecError(
          "x-hlthstack-rule-kinds", "expected " + new TreeSet<>(RULE_KINDS) + ", found " + kinds);
    }
    ObjectNode operations = NODES.objectNode();
    for (Iterator<Map.Entry<String, JsonNode>> paths = spec.path("paths").fields();
        paths.hasNext(); ) {
      Map.Entry<String, JsonNode> pathItem = paths.next();
      JsonNode item = pathItem.getValue();
      for (String method : METHODS) {
        JsonNode op = item.get(method);
        if (op == null) continue;
        String id = op.path("operationId").asText(null);
        if (id == null) throw new SpecError(method.toUpperCase() + " " + pathItem.getKey(), "no operationId");
        if (operations.has(id)) throw new SpecError(id, "duplicate operationId");
        operations.set(id, operation(id, method, pathItem.getKey(), item, op));
      }
      Iterator<String> keys = item.fieldNames();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!METHODS.contains(key) && !key.equals("parameters") && !key.startsWith("x-")
            && !ANNOTATIONS.contains(key) && !key.equals("summary")) {
          throw new SpecError(pathItem.getKey(), "unsupported path item key " + key);
        }
      }
    }
    for (Iterator<String> ids = table.fieldNames(); ids.hasNext(); ) {
      String id = ids.next();
      if (!operations.has(id)) throw new SpecError("rules.yaml", id + " is not an operation in the spec");
    }
    return operations;
  }

  private ObjectNode operation(String id, String method, String route, JsonNode item, JsonNode op) {
    ObjectNode entry = NODES.objectNode();
    entry.put("method", method.toUpperCase());
    entry.put("path", route);

    List<JsonNode> parameters = new ArrayList<>();
    for (JsonNode raw : item.path("parameters")) parameters.add(resolve(raw, id));
    for (JsonNode raw : op.path("parameters")) parameters.add(resolve(raw, id));
    List<Field> path = new ArrayList<>();
    List<Field> query = new ArrayList<>();
    for (JsonNode p : parameters) {
      String in = p.path("in").asText();
      String name = p.path("name").asText();
      if (in.equals("header")) continue; // set by the client, not by method arguments
      if (!p.has("schema")) throw new SpecError(id, "parameter " + name + " has no schema");
      Field field = new Field(name, convert(p.get("schema"), id + "." + name), p.path("required").asBoolean());
      if (in.equals("path")) path.add(field);
      else if (in.equals("query")) query.add(field);
      else throw new SpecError(id, "unsupported parameter location " + in);
    }
    if (!path.isEmpty()) entry.set("params", nest(path, id));
    if (!query.isEmpty()) entry.set("query", nest(query, id));

    if (op.has("requestBody")) {
      JsonNode body = resolve(op.get("requestBody"), id);
      if (body.path("required").asBoolean()) entry.put("bodyRequired", true);
      for (Iterator<Map.Entry<String, JsonNode>> it = body.path("content").fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> content = it.next();
        String type = content.getKey();
        JsonNode schema = convert(content.getValue().path("schema"), id + ".body");
        if (type.equals("application/json")) {
          entry.set("body", schema);
        } else if (type.equals("multipart/form-data")) {
          entry.set("form", nestForm(schema, id + ".form"));
        } else {
          throw new SpecError(id, "unsupported request content type " + type);
        }
      }
    }

    List<JsonNode> schemas = new ArrayList<>();
    for (String key : List.of("params", "query", "body", "form")) {
      if (entry.has(key)) schemas.add(entry.get(key));
    }
    ArrayNode rules = rules(id, op.path("x-hlthstack-rules"), schemas);
    if (rules.size() > 0) entry.set("rules", rules);
    return entry;
  }

  // ------------------------------------------------------------------ schemas

  private JsonNode resolve(JsonNode node, String where) {
    Set<String> seen = new LinkedHashSet<>();
    while (node != null && node.has("$ref")) {
      String ref = node.get("$ref").asText();
      if (!seen.add(ref)) throw new SpecError(where, "recursive $ref " + ref);
      for (Iterator<String> keys = node.fieldNames(); keys.hasNext(); ) {
        String key = keys.next();
        if (!key.equals("$ref") && !ANNOTATIONS.contains(key)) {
          throw new SpecError(where, "$ref with sibling keyword " + key);
        }
      }
      if (!ref.startsWith("#/")) throw new SpecError(where, "external $ref " + ref);
      JsonNode target = spec;
      for (String part : ref.substring(2).split("/")) {
        target = target.get(part.replace("~1", "/").replace("~0", "~"));
        if (target == null) throw new SpecError(where, "unresolved $ref " + ref);
      }
      node = target;
    }
    return node;
  }

  /** Resolves $refs and keeps only validation keywords; a file part becomes {"file": true}. */
  JsonNode convert(JsonNode raw, String where) {
    JsonNode schema = resolve(raw, where);
    if (schema == null || !schema.isObject()) throw new SpecError(where, "schema is not an object");
    ObjectNode out = NODES.objectNode();
    if (schema.has("contentMediaType")) {
      out.put("file", true);
      return out;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = schema.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String key = e.getKey();
      JsonNode value = e.getValue();
      if (ANNOTATIONS.contains(key) || key.startsWith("x-")) continue;
      if (!KEYWORDS.contains(key)) throw new SpecError(where, "unsupported keyword \"" + key + "\"");
      switch (key) {
        case "type":
          for (JsonNode t : value.isArray() ? value : NODES.arrayNode().add(value)) {
            if (!TYPES.contains(t.asText())) throw new SpecError(where, "unknown type " + t);
          }
          out.set(key, value);
          break;
        case "format":
          if (!FORMATS.contains(value.asText())) {
            throw new SpecError(where, "unsupported format \"" + value.asText() + "\"");
          }
          out.set(key, value);
          break;
        case "pattern":
          try {
            Pattern.compile(value.asText());
          } catch (PatternSyntaxException ex) {
            throw new SpecError(where, "pattern does not compile in Java: " + ex.getMessage());
          }
          out.set(key, value);
          break;
        case "properties":
          ObjectNode properties = out.putObject("properties");
          value.fields().forEachRemaining(p -> properties.set(p.getKey(), convert(p.getValue(), where + "." + p.getKey())));
          break;
        case "items":
          out.set("items", convert(value, where + "[]"));
          break;
        case "oneOf":
        case "anyOf":
        case "allOf":
          ArrayNode branches = out.putArray(key);
          int i = 0;
          for (JsonNode branch : value) branches.add(convert(branch, where + "." + key + "[" + i++ + "]"));
          break;
        default:
          out.set(key, value);
      }
    }
    return out;
  }

  private static final class Field {
    final String name;
    final JsonNode schema;
    final boolean required;

    Field(String name, JsonNode schema, boolean required) {
      this.name = name;
      this.schema = schema;
      this.required = required;
    }
  }

  private static final Pattern FIELD_NAME = Pattern.compile("([^\\[\\]]+)((?:\\[[^\\[\\]]*\\])*)");
  private static final Pattern BRACKET = Pattern.compile("\\[([^\\[\\]]*)\\]");

  /** {@code customers[][dob]} -> [customers, [], dob]. */
  static List<String> steps(String name, String where) {
    Matcher m = FIELD_NAME.matcher(name);
    if (!m.matches()) throw new SpecError(where, "cannot parse field name \"" + name + "\"");
    List<String> steps = new ArrayList<>();
    steps.add(m.group(1));
    Matcher b = BRACKET.matcher(m.group(2));
    while (b.find()) steps.add(b.group(1).isEmpty() ? "[]" : b.group(1));
    return steps;
  }

  /**
   * Builds an object schema from flat field names (query parameters, multipart fields):
   * {@code a[b]} -> {@code a.b}, {@code a[]} -> array {@code a}, {@code a[][b]} -> array {@code a} of
   * objects with {@code b}.
   */
  private ObjectNode nest(List<Field> fields, String where) {
    ObjectNode top = objectSchema();
    for (Field field : fields) {
      List<String> steps = steps(field.name, where);
      ObjectNode node = top;
      for (int i = 0; i < steps.size(); i++) {
        String key = steps.get(i);
        if (key.equals("[]")) throw new SpecError(where, "cannot parse field name \"" + field.name + "\"");
        if (field.required) addRequired(node, key);
        String next = i + 1 < steps.size() ? steps.get(i + 1) : null;
        ObjectNode properties = (ObjectNode) node.get("properties");
        if (next == null) {
          properties.set(key, field.schema);
        } else if (next.equals("[]") && i + 2 == steps.size()) {
          // files[]: an array of the schema, unless the schema already describes the array.
          if (field.schema.path("type").asText().equals("array")) {
            properties.set(key, field.schema);
          } else {
            ObjectNode array = NODES.objectNode().put("type", "array");
            array.set("items", field.schema);
            properties.set(key, array);
          }
          break;
        } else if (next.equals("[]")) {
          if (!properties.has(key)) {
            ObjectNode array = NODES.objectNode().put("type", "array");
            array.set("items", objectSchema());
            properties.set(key, array);
          }
          JsonNode items = properties.get(key).get("items");
          if (items == null || !items.has("properties")) {
            throw new SpecError(where, "\"" + field.name + "\" mixes array and object fields");
          }
          node = (ObjectNode) items;
          i++;
        } else {
          if (!properties.has(key)) properties.set(key, objectSchema());
          if (!properties.get(key).has("properties")) {
            throw new SpecError(where, "\"" + field.name + "\" mixes field shapes");
          }
          node = (ObjectNode) properties.get(key);
        }
      }
    }
    prune(top);
    return top;
  }

  private ObjectNode nestForm(JsonNode flat, String where) {
    for (Iterator<String> keys = flat.fieldNames(); keys.hasNext(); ) {
      String key = keys.next();
      if (!key.equals("type") && !key.equals("properties") && !key.equals("required")) {
        throw new SpecError(where, "multipart schema keyword " + key + " is not supported");
      }
    }
    Set<String> required = new LinkedHashSet<>();
    flat.path("required").forEach(r -> required.add(r.asText()));
    List<Field> fields = new ArrayList<>();
    flat.path("properties")
        .fields()
        .forEachRemaining(e -> fields.add(new Field(e.getKey(), e.getValue(), required.contains(e.getKey()))));
    return nest(fields, where);
  }

  private static ObjectNode objectSchema() {
    ObjectNode node = NODES.objectNode().put("type", "object");
    node.putObject("properties");
    node.putArray("required");
    return node;
  }

  private static void addRequired(ObjectNode node, String key) {
    ArrayNode required = (ArrayNode) node.get("required");
    for (JsonNode r : required) if (r.asText().equals(key)) return;
    required.add(key);
  }

  private static void prune(JsonNode schema) {
    if (!(schema instanceof ObjectNode)) return;
    ObjectNode node = (ObjectNode) schema;
    if (node.has("required") && node.get("required").size() == 0) node.remove("required");
    node.path("properties").forEach(BuildValidation::prune);
    prune(node.get("items"));
  }

  // ------------------------------------------------------------------ rules

  /** Whether a dotted field path ({@code customers[].dob}) names a value in one of the schemas. */
  static boolean exists(List<JsonNode> schemas, String field) {
    for (JsonNode schema : schemas) {
      if (lookup(schema, Arrays.asList(field.split("\\.")))) return true;
    }
    return false;
  }

  private static boolean lookup(JsonNode schema, List<String> parts) {
    if (schema == null || !schema.isObject()) return false;
    if (parts.isEmpty()) return true;
    for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
      for (JsonNode branch : schema.path(keyword)) if (lookup(branch, parts)) return true;
    }
    String head = parts.get(0);
    List<String> rest = parts.subList(1, parts.size());
    if (head.endsWith("[]")) {
      JsonNode property = schema.path("properties").get(head.substring(0, head.length() - 2));
      return property != null && property.has("items") && lookup(property.get("items"), rest);
    }
    JsonNode property = schema.path("properties").get(head);
    return property != null && lookup(property, rest);
  }

  /** {@code customers[][dob]} -> {@code customers[].dob}; {@code files[]} -> {@code files}. */
  static String normalizeField(String field) {
    return field.replaceAll("\\[([^\\[\\]]+)\\]", ".$1").replaceAll("\\[\\]$", "");
  }

  private ArrayNode rules(String id, JsonNode specRules, List<JsonNode> schemas) {
    JsonNode mine = table.path(id);
    if (specRules.size() != mine.size()) {
      throw new SpecError(
          id, "the spec has " + specRules.size() + " x-hlthstack-rules, rules.yaml has " + mine.size());
    }
    ArrayNode out = NODES.arrayNode();
    for (int i = 0; i < specRules.size(); i++) {
      JsonNode rule = specRules.get(i);
      JsonNode restated = mine.get(i);
      String where = id + " x-hlthstack-rules[" + i + "]";
      for (String key : List.of("rule", "description", "fields")) {
        if (!rule.path(key).equals(restated.path(key))) {
          throw new SpecError(
              where,
              key + " differs from rules.yaml; restate the rule there\n  spec:       " + rule.path(key)
                  + "\n  rules.yaml: " + restated.path(key));
        }
      }
      if (!RULE_KINDS.contains(rule.path("rule").asText())) {
        throw new SpecError(where, "rule kind " + rule.path("rule") + " is not implemented");
      }
      for (JsonNode field : rule.path("fields")) {
        if (!exists(schemas, normalizeField(field.asText()))) {
          throw new SpecError(where, "rule field " + field + " is not in the operation's schemas");
        }
      }
      checkParameters(where, (ObjectNode) restated, schemas);
      out.add(restated.deepCopy());
    }
    return out;
  }

  /** The parameters each rule kind needs, and that the paths they name exist. */
  private static void checkParameters(String where, ObjectNode rule, List<JsonNode> schemas) {
    String kind = rule.path("rule").asText();
    List<String> paths = new ArrayList<>();
    Set<String> allowed = new LinkedHashSet<>(List.of("rule", "description", "fields"));
    switch (kind) {
      case "required_if":
        need(where, rule, "when", "required");
        allowed.addAll(List.of("when", "required"));
        rule.get("required").forEach(f -> paths.add(f.asText()));
        paths.add(condition(where, rule.get("when")));
        break;
      case "allowed_only_if":
        need(where, rule, "when", "allowed");
        allowed.addAll(List.of("when", "allowed"));
        rule.get("allowed").forEach(f -> paths.add(f.asText()));
        paths.add(condition(where, rule.get("when")));
        break;
      case "at_least_one_of":
        need(where, rule, "groups");
        allowed.addAll(List.of("groups", "scope"));
        String scope = rule.path("scope").asText("");
        for (JsonNode group : rule.get("groups")) {
          for (JsonNode f : group) paths.add(scope.isEmpty() ? f.asText() : scope + "." + f.asText());
        }
        break;
      case "mutually_exclusive":
        need(where, rule, "exclusive");
        allowed.add("exclusive");
        rule.get("exclusive").forEach(f -> paths.add(f.asText()));
        break;
      case "unique_by":
        need(where, rule, "array", "key");
        allowed.addAll(List.of("array", "key"));
        paths.add(rule.get("array").asText() + "[]." + rule.get("key").asText());
        break;
      case "relative_date":
        need(where, rule, "targets");
        allowed.addAll(List.of("targets", "before", "years", "after", "defaultOffset", "unless"));
        boolean before = rule.path("before").asText().equals("today") && rule.has("years");
        boolean after = rule.path("after").asText().equals("now");
        if (before == after) throw new SpecError(where, "relative_date needs before: today + years, or after: now");
        rule.get("targets").forEach(f -> paths.add(f.asText()));
        if (rule.has("unless")) paths.add(condition(where, rule.get("unless")));
        break;
      case "no_conflicting_pincode":
        need(where, rule, "address", "text", "pincode");
        allowed.addAll(List.of("address", "text", "pincode", "when"));
        String address = rule.get("address").asText();
        rule.get("text").forEach(f -> paths.add(address + "." + f.asText()));
        rule.get("pincode").forEach(f -> paths.add(address + "." + f.asText()));
        if (rule.has("when")) paths.add(condition(where, rule.get("when")));
        break;
      case "file_limits":
        need(where, rule, "files");
        allowed.addAll(List.of("files", "maxBytes", "contentTypes", "extensions", "match"));
        if (!rule.has("maxBytes") && !rule.has("contentTypes") && !rule.has("extensions")) {
          throw new SpecError(where, "file_limits needs maxBytes, contentTypes or extensions");
        }
        String files = rule.get("files").asText();
        paths.add(files.endsWith("[]") ? files.substring(0, files.length() - 2) : files);
        break;
      case "item_count":
        need(where, rule, "arrays");
        allowed.addAll(List.of("arrays", "min", "max"));
        if (!rule.has("min") && !rule.has("max")) throw new SpecError(where, "item_count needs min or max");
        rule.get("arrays").forEach(f -> paths.add(f.asText()));
        break;
      case "positive_decimal":
        need(where, rule, "targets");
        allowed.add("targets");
        rule.get("targets").forEach(f -> paths.add(f.asText()));
        break;
      default:
        throw new SpecError(where, "rule kind " + kind + " is not implemented");
    }
    for (Iterator<String> keys = rule.fieldNames(); keys.hasNext(); ) {
      String key = keys.next();
      if (!allowed.contains(key)) throw new SpecError(where, "unknown rules.yaml key " + key + " for " + kind);
    }
    for (String path : paths) {
      if (!exists(schemas, path)) throw new SpecError(where, "rules.yaml path " + path + " is not in the operation's schemas");
    }
  }

  private static void need(String where, JsonNode rule, String... keys) {
    for (String key : keys) {
      if (!rule.has(key)) throw new SpecError(where, "rules.yaml entry needs " + key);
    }
  }

  private static String condition(String where, JsonNode when) {
    int kinds = (when.has("blank") ? 1 : 0) + (when.has("equals") ? 1 : 0) + (when.has("notEquals") ? 1 : 0);
    if (!when.has("field") || kinds != 1) {
      throw new SpecError(where, "a condition needs field and one of blank, equals, notEquals");
    }
    return when.get("field").asText();
  }

}
