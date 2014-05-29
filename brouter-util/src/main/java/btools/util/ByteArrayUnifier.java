package btools.util;

public final class ByteArrayUnifier
{
  private byte[][] byteArrayCache;
  private int size;

  public ByteArrayUnifier( int size )
  {
    this.size = size;
    byteArrayCache = new byte[size][];
  }

  /**
   * Unify a byte array in order to reuse instances when possible.
   * The byte arrays are assumed to be treated as immutable,
   * allowing the reuse
   * @param the byte array to unify
   * @return the cached instance or the input instanced if not cached
   */
  public byte[] unify( byte[] ab )
  {
	  int n = ab.length;
      int crc  = Crc32.crc( ab, 0, n );
      int idx  =  (crc & 0xfffffff) % size;
      byte[] abc = byteArrayCache[idx];
      if ( abc != null && abc.length == n )
      {
    	int i = 0;
        while( i < n )
        {
        	if ( ab[i] != abc[i] ) break;
        	i++;
        }
        if ( i == n ) return abc;
      }
      byteArrayCache[idx] = ab;
	  return ab;
  }
}
