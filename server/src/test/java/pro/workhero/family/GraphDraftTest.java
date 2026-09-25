package pro.workhero.family;

import static org.junit.jupiter.api.Assertions.*;
import static pro.workhero.family.Family.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphDraftTest {
  @Test
  void cycleRejectionIncludesActualAncestryAndPreservesGraph() {
    var graph =
        new Graph(
            List.of(new Person("a", "Ravi"), new Person("b", "Maya"), new Person("c", "Aanya")),
            List.of(new ParentEdge("a", "b"), new ParentEdge("b", "c")),
            List.of());
    var draft = new GraphDraft(graph);
    var error =
        assertThrows(
            InvalidFamilyOperationException.class,
            () -> draft.add(new Relationship(Family.RelationshipKind.PARENT, "c", "a")));
    assertEquals("CYCLE_DETECTED", error.code);
    assertTrue(error.getMessage().contains("Ravi → Maya → Aanya"));
    assertTrue(error.getMessage().contains("Cannot make Aanya a parent of Ravi"));
    assertEquals(graph, draft.graph());
  }
}
