package btools.codec;


public interface TagValueValidator
{
  /**
   * @param tagValueSet the way description to check
   * @return 0 = nothing, 1=no matching, 2=normal
   */
  public int accessType( byte[] tagValueSet );
}
