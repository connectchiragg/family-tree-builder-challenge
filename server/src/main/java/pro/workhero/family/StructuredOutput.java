package pro.workhero.family;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.*;
import com.github.victools.jsonschema.module.jakarta.validation.*;
import jakarta.validation.Validator;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The same annotated Java types drive provider schemas and local response validation. */
@Component
public class StructuredOutput {
  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
              DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();
  private final Validator validator;
  private final SchemaGenerator generator;
  private final ConcurrentHashMap<Class<?>, JsonNode> schemas = new ConcurrentHashMap<>();

  public StructuredOutput(Validator validator) {
    this.validator = validator;
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) json.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
    var config =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(
                new JacksonModule(
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY))
            .with(
                new JakartaValidationModule(
                    JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                    JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
            .with(
                Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT,
                Option.NULLABLE_FIELDS_BY_DEFAULT);
    generator = new SchemaGenerator(config.build());
  }

  public JsonNode schema(Class<?> type) {
    return schemas.computeIfAbsent(type, generator::generateSchema).deepCopy();
  }

  public <T> T read(JsonNode input, Class<T> type) {
    try {
      T value = json.treeToValue(input, type);
      if (value == null || !validator.validate(value).isEmpty())
        throw new IllegalArgumentException("Response violates its constraints.");
      return value;
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
      throw new HttpModelClient.Unavailable(
          "Model returned an invalid response. Nothing from this response was applied.");
    }
  }
}
