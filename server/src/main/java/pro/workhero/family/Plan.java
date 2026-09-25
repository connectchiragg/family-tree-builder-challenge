package pro.workhero.family;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/** A complete turn's mutations. New references start with @; existing references are IDs. */
public record Plan(List<Operation> operations) {
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = CreatePerson.class, name = "create_person"),
    @JsonSubTypes.Type(value = DeletePerson.class, name = "delete_person"),
    @JsonSubTypes.Type(value = RenamePerson.class, name = "rename_person"),
    @JsonSubTypes.Type(value = AddRelationship.class, name = "add_relationship"),
    @JsonSubTypes.Type(value = RemoveRelationship.class, name = "remove_relationship"),
    @JsonSubTypes.Type(value = ReplaceRelationship.class, name = "replace_relationship")
  })
  public sealed interface Operation
      permits CreatePerson,
          RenamePerson,
          DeletePerson,
          AddRelationship,
          RemoveRelationship,
          ReplaceRelationship {}

  public record CreatePerson(String ref, String name) implements Operation {}

  public record DeletePerson(String person) implements Operation {}

  public record RenamePerson(String person, String name) implements Operation {}

  public record AddRelationship(String kind, String from, String to) implements Operation {}

  public record RemoveRelationship(String kind, String from, String to) implements Operation {}

  public record ReplaceRelationship(
      String oldKind, String oldFrom, String oldTo, String newKind, String newFrom, String newTo)
      implements Operation {}
}
