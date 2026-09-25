package pro.workhero.family;

import static pro.workhero.family.Family.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Tools {
  public record Result(boolean error, Object value) {}

  private final FamilyStore store;
  private final ObjectMapper json;

  public Tools(FamilyStore store, ObjectMapper json) {
    this.store = store;
    this.json =
        json.copy()
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    var textCoercion =
        this.json.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
    for (var shape :
        List.of(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
      textCoercion.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
  }

  public JsonNode definitions() {
    var variants =
        List.of(
            schema("create_person", "ref", "name"),
            schema("rename_person", "person", "name"),
            schema("delete_person", "person"),
            schema("add_relationship", "kind", "from", "to"),
            schema("remove_relationship", "kind", "from", "to"),
            schema(
                "replace_relationship",
                "oldKind",
                "oldFrom",
                "oldTo",
                "newKind",
                "newFrom",
                "newTo"));
    return json.valueToTree(
        List.of(
            Map.of(
                "name",
                "apply_family_changes",
                "description",
                "Submit ONE complete, ordered mutation plan. Declare new people with unique @refs, then reference them in later operations. Existing people use exact graph IDs. All operations are validated before any writes; invalid plans save nothing. Never guess between same-name people: ask the user instead.",
                "input_schema",
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                        "operations",
                        Map.of(
                            "type",
                            "array",
                            "minItems",
                            1,
                            "maxItems",
                            40,
                            "items",
                            Map.of("oneOf", variants))),
                    "required",
                    List.of("operations"),
                    "additionalProperties",
                    false))));
  }

  private Map<String, Object> schema(String type, String... fields) {
    var props = new LinkedHashMap<String, Object>();
    props.put("type", Map.of("type", "string", "enum", List.of(type)));
    for (var field : fields)
      props.put(
          field,
          field.toLowerCase(Locale.ROOT).endsWith("kind")
              ? Map.of("type", "string", "enum", List.of("parent", "spouse"))
              : Map.of("type", "string", "minLength", 1, "maxLength", 120));
    return Map.of(
        "type",
        "object",
        "properties",
        props,
        "required",
        new ArrayList<>(props.keySet()),
        "additionalProperties",
        false);
  }

  public Result execute(String name, JsonNode input) {
    try {
      require(
          "apply_family_changes".equals(name),
          "UNKNOWN_TOOL",
          "Only one apply_family_changes plan is supported.");
      require(input != null && input.isObject(), "INVALID_INPUT", "A plan must be an object.");
      var plan = json.treeToValue(input, Plan.class);
      return new Result(false, store.apply(plan));
    } catch (Invalid e) {
      return new Result(true, Map.of("code", e.code, "message", e.getMessage(), "saved", false));
    } catch (org.springframework.dao.DataAccessException e) {
      return new Result(
          true,
          Map.of(
              "code",
              "PERSISTENCE_FAILED",
              "message",
              "Saving failed; the database transaction was rolled back. Please retry.",
              "saved",
              false));
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
      return new Result(
          true,
          Map.of(
              "code",
              "INVALID_INPUT",
              "message",
              "The plan has malformed or unsupported operations. Nothing was saved.",
              "saved",
              false));
    }
  }
}
