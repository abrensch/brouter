package btools.codec;


public interface TagValueValidator
{
  /**
   * @param tagValueSet the way description to check
   * @return true if access is allowed in the current profile 
   */
  public boolean accessAllowed( byte[] tagValueSet );
}
