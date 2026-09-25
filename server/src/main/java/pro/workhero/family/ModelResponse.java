package pro.workhero.family;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import pro.workhero.family.Plan.Operation;

/** Exactly one answer/clarification or one mutation batch; never execute an uncertain answer. */
public record ModelResponse(
    @Size(max = 2000) @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*") String message,
    @NotNull @Size(max = 40) List<@NotNull @Valid Operation> operations,
    @Size(max = 2000) @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*") String answerQuestion) {
  public ModelResponse {
    operations = List.copyOf(operations);
    boolean hasEdits = !operations.isEmpty();
    boolean hasMessage = message != null;
    // Clarifications cannot also edit the tree; follow-up answers require a saved batch.
    if (hasEdits == hasMessage || (!hasEdits && answerQuestion != null))
      throw new IllegalArgumentException(
          "Return a message without operations, or operations without a message.");
  }

  /** Fixed contract for our six operations; no runtime schema-generation framework. */
  static Map<String, Object> schema() {
    var variants =
        List.of(
            operation("create_person", "ref", "name"),
            operation("delete_person", "person"),
            operation("rename_person", "person", "name"),
            operation("add_relationship", "kind", "from", "to"),
            operation("remove_relationship", "kind", "from", "to"),
            operation(
                "replace_relationship",
                "oldKind",
                "oldFrom",
                "oldTo",
                "newKind",
                "newFrom",
                "newTo"));
    var text = Map.of("type", List.of("string", "null"), "minLength", 1, "maxLength", 2000);
    return Map.of(
        "type",
        "object",
        "additionalProperties",
        false,
        "required",
        List.of("operations"),
        "properties",
        Map.of(
            "message",
            text,
            "answerQuestion",
            text,
            "operations",
            Map.of("type", "array", "maxItems", 40, "items", Map.of("oneOf", variants))));
  }

  private static Map<String, Object> operation(String type, String... fields) {
    var properties = new LinkedHashMap<String, Object>();
    properties.put("type", Map.of("type", "string", "enum", List.of(type)));
    for (var field : fields) {
      var property = new LinkedHashMap<String, Object>();
      property.put("type", "string");
      if (field.toLowerCase(Locale.ROOT).endsWith("kind"))
        property.put("enum", List.of("parent", "spouse"));
      else {
        property.put("minLength", 1);
        property.put("maxLength", 120);
        if (field.equals("ref")) property.put("pattern", "^@[A-Za-z][A-Za-z0-9_-]{0,39}$");
        else if (!field.equals("name"))
          property.put("description", "Exact person ID or declared @ref; never a name");
      }
      properties.put(field, property);
    }
    return Map.of(
        "type",
        "object",
        "additionalProperties",
        false,
        "required",
        new ArrayList<>(properties.keySet()),
        "properties",
        properties);
  }
}
