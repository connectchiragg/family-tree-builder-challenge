package pro.workhero.family;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
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

  public record DeletePerson(
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String person)
      implements Operation {}

  public record RenamePerson(
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String person,
      @NotBlank @Size(max = 120) String name)
      implements Operation {}

  public record AddRelationship(
      @NotNull RelationshipKind kind,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String from,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String to)
      implements Operation {}

  public record RemoveRelationship(
      @NotNull RelationshipKind kind,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String from,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String to)
      implements Operation {}

  public record ReplaceRelationship(
      @NotNull RelationshipKind oldKind,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String oldFrom,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String oldTo,
      @NotNull RelationshipKind newKind,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String newFrom,
      @JsonPropertyDescription("Exact person ID or declared @ref; never a name")
          @NotBlank
          @Size(max = 120)
          String newTo)
      implements Operation {}
}
