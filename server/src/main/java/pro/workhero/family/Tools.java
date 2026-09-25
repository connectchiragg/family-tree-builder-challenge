package pro.workhero.family;

import static pro.workhero.family.Family.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Tools {
  public record Result(boolean error, Object value) {
    static Result failure(String code, String message) {
      return new Result(true, Map.of("code", code, "message", message, "saved", false));
    }
  }

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
    var input = json.createObjectNode().put("type", "object").put("additionalProperties", false);
    input.putArray("required").add("operations");
    input
        .putObject("properties")
        .putObject("operations")
        .put("type", "array")
        .put("minItems", 1)
        .put("maxItems", 40)
        .putObject("items")
        .set("oneOf", json.valueToTree(variants));
    var tool =
        json.createObjectNode()
            .put("name", "apply_family_changes")
            .put(
                "description",
                "Submit one complete ordered plan. Declare new people with unique @refs; existing people use graph IDs. Invalid plans save nothing. Clarify ambiguous names before calling.");
    tool.set("input_schema", input);
    return json.createArrayNode().add(tool);
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
      return Result.failure(e.code, e.getMessage());
    } catch (org.springframework.dao.DataAccessException e) {
      return Result.failure(
          "PERSISTENCE_FAILED",
          "Saving failed; the database transaction was rolled back. Please retry.");
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
      return Result.failure(
          "INVALID_INPUT", "The plan has malformed or unsupported operations. Nothing was saved.");
    }
  }
}
