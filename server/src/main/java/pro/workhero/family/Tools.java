package pro.workhero.family;

import static pro.workhero.family.Family.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** A small command registry: schema, validation and execution stay together. */
@Component
public class Tools {
  private record Tool(
      String name,
      String description,
      Map<String, String> fields,
      Function<JsonNode, Object> execute) {}

  public record Result(boolean error, Object value) {}

  private final Map<String, Tool> registry = new LinkedHashMap<>();
  private final ObjectMapper json;

  public Tools(FamilyStore store, ObjectMapper json) {
    this.json = json;
    register(
        "get_family_tree",
        "Read all people and relationships; use their stable IDs.",
        Map.of(),
        x -> store.graph());
    register(
        "find_people",
        "Find ALL exact name matches (case insensitive). Read graph relationships to disambiguate; never choose arbitrarily.",
        Map.of("name", "Name to look up"),
        x -> store.find(text(x, "name")));
    register(
        "create_person",
        "Create only an explicitly NEW person, after checking existing people. Names are not unique.",
        Map.of("name", "Explicitly stated name"),
        x -> store.create(text(x, "name")));
    register(
        "update_person",
        "Correct an existing person's name without changing identity or relationships.",
        Map.of("id", "Existing person ID", "name", "Corrected name"),
        x -> store.rename(text(x, "id"), text(x, "name")));
    var relationship =
        Map.of(
            "kind",
            "parent or spouse; parent direction is fromId to toId",
            "fromId",
            "Existing person ID",
            "toId",
            "Existing person ID");
    register(
        "add_relationship",
        "Add an explicitly supported relationship. A spouse never implies a parent.",
        relationship,
        x -> store.add(edge(x, "")));
    register(
        "remove_relationship",
        "Remove a relationship explicitly retracted by the user. For replacements use replace_relationship.",
        relationship,
        x -> store.remove(edge(x, "")));
    register(
        "replace_relationship",
        "Atomically replace an incorrect relationship. Invalid replacements preserve the original.",
        Map.of(
            "oldKind",
            "parent or spouse",
            "oldFromId",
            "Old source ID",
            "oldToId",
            "Old target ID",
            "newKind",
            "parent or spouse",
            "newFromId",
            "Correct source ID",
            "newToId",
            "Correct target ID"),
        x -> store.replace(edge(x, "old"), edge(x, "new")));
  }

  private void register(
      String name, String description, Map<String, String> fields, Function<JsonNode, Object> fn) {
    registry.put(name, new Tool(name, description, fields, fn));
  }

  public JsonNode definitions() {
    return json.valueToTree(
        registry.values().stream()
            .map(
                t -> {
                  var properties = new TreeMap<String, Object>();
                  t.fields.forEach(
                      (name, description) ->
                          properties.put(
                              name,
                              name.toLowerCase(Locale.ROOT).endsWith("kind")
                                  ? Map.of(
                                      "type",
                                      "string",
                                      "description",
                                      description,
                                      "enum",
                                      List.of("parent", "spouse"))
                                  : Map.of(
                                      "type",
                                      "string",
                                      "description",
                                      description,
                                      "minLength",
                                      1,
                                      "maxLength",
                                      120)));
                  return Map.of(
                      "name",
                      t.name,
                      "description",
                      t.description,
                      "input_schema",
                      Map.of(
                          "type",
                          "object",
                          "properties",
                          properties,
                          "required",
                          new TreeSet<>(t.fields.keySet()),
                          "additionalProperties",
                          false));
                })
            .toList());
  }

  public Result execute(String name, JsonNode input) {
    try {
      var tool = registry.get(name);
      require(tool != null, "UNKNOWN_TOOL", "Unknown tool: " + name);
      require(input != null && input.isObject(), "INVALID_INPUT", "Tool input must be an object.");
      var actual = new HashSet<String>();
      input.fieldNames().forEachRemaining(actual::add);
      require(
          actual.equals(tool.fields.keySet()),
          "INVALID_INPUT",
          "Expected fields: " + new TreeSet<>(tool.fields.keySet()));
      tool.fields.keySet().forEach(key -> text(input, key));
      return new Result(false, tool.execute.apply(input));
    } catch (Invalid e) {
      return new Result(true, Map.of("code", e.code, "message", e.getMessage()));
    }
  }

  private static String text(JsonNode input, String field) {
    var value = input.path(field);
    require(
        value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 120,
        "INVALID_INPUT",
        "Invalid field: " + field);
    return value.asText().strip();
  }

  private static Relationship edge(JsonNode input, String prefix) {
    return prefix.isEmpty()
        ? new Relationship(text(input, "kind"), text(input, "fromId"), text(input, "toId"))
        : new Relationship(
            text(input, prefix + "Kind"),
            text(input, prefix + "FromId"),
            text(input, prefix + "ToId"));
  }
}
