package pro.workhero.family;

import java.util.List;

public final class Family {
  private Family() {}

  public record Person(String id, String name) {}

  public record ParentEdge(String parentId, String childId) {}

  public record SpouseEdge(String personAId, String personBId) {}

  public record Graph(
      List<Person> people, List<ParentEdge> parentEdges, List<SpouseEdge> spouseEdges) {}

  public record Relationship(String kind, String fromId, String toId) {}

  public static final class Invalid extends RuntimeException {
    public final String code;

    public Invalid(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  public static void require(boolean condition, String code, String message) {
    if (!condition) throw new Invalid(code, message);
  }
}
