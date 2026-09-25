package pro.workhero.family;

import org.springframework.stereotype.Component;

/** Executes a typed plan; persistence owns validation and the transaction. */
@Component
public class Tools {
  public record Result(FamilyStore.Applied applied, String error) {}

  public Result execute(Plan plan, java.util.function.Function<Plan, FamilyStore.Applied> apply) {
    try {
      return new Result(apply.apply(plan), null);
    } catch (InvalidFamilyOperationException e) {
      return new Result(null, e.getMessage());
    } catch (org.springframework.dao.DataAccessException e) {
      return new Result(
          null, "Saving failed; the database transaction was rolled back. Please retry.");
    }
  }
}
