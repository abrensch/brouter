package btools.util;

public final class ByteArrayUnifier
{
  private byte[][] byteArrayCache;
  private int[] crcCrosscheck;
  private int size;

  public ByteArrayUnifier( int size, boolean validateImmutability )
  {
    this.size = size;
    
    if ( !Boolean.getBoolean( "disableByteArrayUnifification" ) )
    {
      byteArrayCache = new byte[size][];
    }
    if ( validateImmutability ) crcCrosscheck = new int[size];
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
	  if ( byteArrayCache == null ) return ab;
	  
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
      if ( crcCrosscheck != null )
      {
    	  if ( byteArrayCache[idx] != null )
    	  {
    	    byte[] abold = byteArrayCache[idx];
    	    int crcold = Crc32.crc( abold, 0, abold.length );
    	    if ( crcold != crcCrosscheck[idx] ) throw new IllegalArgumentException( "ByteArrayUnifier: immutablity validation failed!" );
    	  }
    	  crcCrosscheck[idx] = crc;
      }
      byteArrayCache[idx] = ab;
	  return ab;
  }
}
