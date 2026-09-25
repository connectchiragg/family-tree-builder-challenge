package pro.workhero.family;

/** A rejected graph operation or request, with a stable error code for callers. */
public final class InvalidFamilyOperationException extends RuntimeException {
  public final String code;

  public InvalidFamilyOperationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public static void require(boolean condition, String code, String message) {
    if (!condition) throw new InvalidFamilyOperationException(code, message);
  }
}
