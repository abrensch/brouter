/**
 * fast data-writing to a byte-array
 *
 * @author ab
 */
package btools.util;


public final class ByteDataWriter
{
  private byte[] ab;
  private int aboffset;

  public ByteDataWriter( byte[] byteArray )
  {
	 ab = byteArray;
  }

  public void writeInt( int v )
  {
	ab[aboffset++] = (byte)( (v >> 24) & 0xff );
    ab[aboffset++] = (byte)( (v >> 16) & 0xff );
	ab[aboffset++] = (byte)( (v >>  8) & 0xff );
    ab[aboffset++] = (byte)( (v      ) & 0xff );
  }

  public void writeLong( long v )
  {
	ab[aboffset++] = (byte)( (v >> 56) & 0xff );
    ab[aboffset++] = (byte)( (v >> 48) & 0xff );
	ab[aboffset++] = (byte)( (v >> 40) & 0xff );
    ab[aboffset++] = (byte)( (v >> 32) & 0xff );
	ab[aboffset++] = (byte)( (v >> 24) & 0xff );
    ab[aboffset++] = (byte)( (v >> 16) & 0xff );
	ab[aboffset++] = (byte)( (v >>  8) & 0xff );
    ab[aboffset++] = (byte)( (v      ) & 0xff );
  }

  public void writeBoolean( boolean v)
  {
    ab[aboffset++] = (byte)( v ? 1 : 0 );
  }

  public void writeByte( int v )
  {
    ab[aboffset++] = (byte)( (v     ) & 0xff );
  }

  public void writeShort( int v )
  {
    ab[aboffset++] = (byte)( (v >> 8) & 0xff );
    ab[aboffset++] = (byte)( (v     ) & 0xff );
  }
  
  public void write( byte[] sa )
  {
    System.arraycopy( sa, 0, ab, aboffset, sa.length );
    aboffset += sa.length;
  }
  
  public void write( byte[] sa, int offset, int len )
  {
    System.arraycopy( sa, offset, ab, aboffset, len );
    aboffset += len;
  }

  public void ensureCapacity( int len )
  {
	  // TODO
  }

  public byte[] toByteArray()
  {
    byte[] c = new byte[aboffset];
    System.arraycopy( ab, 0, c, 0, aboffset );
    return c;
  }
  
  public int writeVarLengthSigned( int v )
  {
    return writeVarLengthUnsigned( v < 0 ? ( (-v) << 1 ) | 1 : v << 1 );
  }

  public int writeVarLengthUnsigned( int v )
  {
	int start = aboffset;
	do
	{
	  int i7 = v & 0x7f;
	  v >>= 7;
	  if ( v != 0 ) i7 |= 0x80;
      ab[aboffset++] = (byte)( i7 & 0xff );
	}
	while( v != 0 );
	return aboffset - start;
  }

  public int size()
  {
    return aboffset;
  }
  
  @Override
  public String toString()
  {
	  StringBuilder sb = new StringBuilder( "[" );
	  for( int i=0; i<ab.length; i++ ) sb.append( i == 0 ? " " : ", " ).append( Integer.toString( ab[i] ) );
      sb.append( " ]" );
      return sb.toString();
  }
}