package btools.util;


public interface TagValueValidator {
  /**
   * @param tagValueSet the way description to check
   * @return 0 = nothing, 1=no matching, 2=normal
   */
  int accessType(byte[] tagValueSet);

  /**
   * Remove all tag-values that are not used by this validator.
   * Knowledge about the inner structure of the description lives here.
   *
   * @param tagValueSet the way description (raw)
   * @return the reduced description, or null if nothing is left
   */
  byte[] reduce(byte[] tagValueSet);
}
