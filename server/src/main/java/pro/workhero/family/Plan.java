package pro.workhero.family;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import pro.workhero.family.Family.RelationshipKind;

/** A complete turn's mutations. New references start with @; existing references are IDs. */
public record Plan(@NotNull @Size(min = 1, max = 40) List<@NotNull @Valid Operation> operations) {
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

  public record CreatePerson(
      @NotBlank @Pattern(regexp = "^@[A-Za-z][A-Za-z0-9_-]{0,39}$") String ref,
      @NotBlank @Size(max = 120) String name)
      implements Operation {}

  public record DeletePerson(@NotBlank @Size(max = 120) String person) implements Operation {}

  public record RenamePerson(
      @NotBlank @Size(max = 120) String person, @NotBlank @Size(max = 120) String name)
      implements Operation {}

  public record AddRelationship(
      @NotNull RelationshipKind kind,
      @NotBlank @Size(max = 120) String from,
      @NotBlank @Size(max = 120) String to)
      implements Operation {}

  public record RemoveRelationship(
      @NotNull RelationshipKind kind,
      @NotBlank @Size(max = 120) String from,
      @NotBlank @Size(max = 120) String to)
      implements Operation {}

  public record ReplaceRelationship(
      @NotNull RelationshipKind oldKind,
      @NotBlank @Size(max = 120) String oldFrom,
      @NotBlank @Size(max = 120) String oldTo,
      @NotNull RelationshipKind newKind,
      @NotBlank @Size(max = 120) String newFrom,
      @NotBlank @Size(max = 120) String newTo)
      implements Operation {}
}
