package pro.workhero.family;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import pro.workhero.family.Plan.Operation;

/** Exactly one answer/clarification or one mutation batch; never execute an uncertain answer. */
public record ModelResponse(
    @Size(max = 2000) @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*") String message,
    @NotNull @Size(max = 40) List<@NotNull @Valid Operation> operations,
    @Size(max = 2000) @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*") String answerQuestion) {
  public ModelResponse {
    operations = List.copyOf(operations);
    if (operations.isEmpty() ? message == null || answerQuestion != null : message != null)
      throw new IllegalArgumentException(
          "Return a message without operations, or operations without a message.");
  }

  /** The post-save call cannot request mutations. */
  public record Answer(@NotBlank @Size(max = 2000) String message) {}
}
