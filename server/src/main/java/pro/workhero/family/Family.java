package pro.workhero.family;

import java.util.List;

public final class Family {
  private Family() {}

  public record Person(String id, String name) {}

  public record ParentEdge(String parentId, String childId) {}

  public record SpouseEdge(String personAId, String personBId) {}

  public record Graph(
      List<Person> people, List<ParentEdge> parentEdges, List<SpouseEdge> spouseEdges) {}

  public enum RelationshipKind {
    @com.fasterxml.jackson.annotation.JsonProperty("parent")
    PARENT,
    @com.fasterxml.jackson.annotation.JsonProperty("spouse")
    SPOUSE
  }

  public record Relationship(RelationshipKind kind, String fromId, String toId) {}
}
