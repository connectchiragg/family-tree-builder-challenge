package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class StructuredOutputTest {
  static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
  final StructuredOutput output = new StructuredOutput(factory.getValidator());
  final ObjectMapper json = new ObjectMapper();

  @AfterAll
  static void close() {
    factory.close();
  }

  ModelResponse read(String input) throws Exception {
    return output.read(json.readTree(input), ModelResponse.class);
  }

  @Test
  void acceptsClarificationAndTypedOperations() throws Exception {
    assertTrue(read("{\"message\":\"Which Ravi?\",\"operations\":[]}").operations().isEmpty());
    var response =
        read(
            """
        {"operations":[
          {"type":"create_person","ref":"@a","name":"Alice"},
          {"type":"add_relationship","kind":"parent","from":"@a","to":"existing"}
        ],"message":null,"answerQuestion":null}
        """);
    assertInstanceOf(Plan.CreatePerson.class, response.operations().get(0));
    assertEquals(
        Family.RelationshipKind.PARENT,
        ((Plan.AddRelationship) response.operations().get(1)).kind());
    assertThrows(UnsupportedOperationException.class, () -> response.operations().clear());
    assertEquals("\"spouse\"", json.writeValueAsString(Family.RelationshipKind.SPOUSE));
  }

  @Test
  void rejectsMalformedOrAmbiguousEnvelopesAndOperations() throws Exception {
    for (String input :
        List.of(
            "{}",
            "null",
            "[]",
            "{\"message\":\"Hi\"}",
            "{\"message\":\"Hi\",\"operations\":null}",
            "{\"message\":42,\"operations\":[]}",
            "{\"message\":\" \",\"operations\":[]}",
            "{\"message\":null,\"operations\":[]}",
            "{\"message\":\"Hi\",\"operations\":[],\"extra\":true}",
            "{\"message\":\"Which Alice?\",\"operations\":[{\"type\":\"delete_person\",\"person\":\"a\"}]}",
            "{\"message\":\"Hi\",\"operations\":[],\"answerQuestion\":\"Who?\"}",
            "{\"operations\":[null]}",
            "{\"operations\":[{\"type\":\"create_person\",\"ref\":\"@a\",\"name\":42}]}",
            "{\"operations\":[{\"type\":\"create_person\",\"ref\":\"bad\",\"name\":\"A\"}]}",
            "{\"operations\":[{\"type\":\"create_person\",\"ref\":\"@a\"}]}",
            "{\"operations\":[{\"type\":\"delete_person\",\"person\":null}]}",
            "{\"operations\":[{\"type\":\"delete_person\",\"person\":\"a\",\"extra\":1}]}",
            "{\"operations\":[{\"type\":\"unknown\"}]}",
            "{\"operations\":[{\"type\":\"add_relationship\",\"kind\":\"sibling\",\"from\":\"a\",\"to\":\"b\"}]}",
            "{\"operations\":[{\"type\":\"add_relationship\",\"kind\":0,\"from\":\"a\",\"to\":\"b\"}]}"))
      assertThrows(HttpModelClient.Unavailable.class, () -> read(input), input);
  }

  @Test
  void enforcesLimitsAtBoundary() throws Exception {
    var input = json.createObjectNode();
    input.putArray("operations");
    input.put("message", "a".repeat(2000));
    output.read(input, ModelResponse.class);
    input.put("message", "a".repeat(2001));
    assertThrows(HttpModelClient.Unavailable.class, () -> output.read(input, ModelResponse.class));
    input.remove("message");
    var operations = input.withArray("operations");
    for (int i = 0; i < 40; i++)
      operations.addObject().put("type", "delete_person").put("person", "id" + i);
    assertEquals(40, output.read(input, ModelResponse.class).operations().size());
    operations.addObject().put("type", "delete_person").put("person", "41");
    assertThrows(HttpModelClient.Unavailable.class, () -> output.read(input, ModelResponse.class));
    assertThrows(
        HttpModelClient.Unavailable.class,
        () ->
            read(
                "{\"operations\":[{\"type\":\"create_person\",\"ref\":\"@a\",\"name\":\""
                    + "a".repeat(121)
                    + "\"}]}"));
    assertThrows(
        HttpModelClient.Unavailable.class,
        () ->
            output.read(
                json.readTree("{\"message\":\"Hi\",\"operations\":[]}"),
                ModelResponse.Answer.class));
  }

  @Test
  void schemaComesFromTypesAndConstraints() throws Exception {
    var schema = output.schema(ModelResponse.class);
    assertEquals(40, schema.path("properties").path("operations").path("maxItems").asInt());
    assertTrue(schema.path("required").toString().contains("operations"));
    assertFalse(schema.path("required").toString().contains("message"));
    assertEquals(2000, schema.path("properties").path("message").path("maxLength").asInt());
    assertTrue(schema.toString().contains("create_person"));
    assertTrue(schema.toString().contains("parent"));
    assertFalse(schema.path("additionalProperties").asBoolean(true));
    Files.writeString(Path.of("target/model-response-schema.json"), schema.toPrettyString());
  }
}
