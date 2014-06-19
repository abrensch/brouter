/**
 * fast data-reading from a byte-array
 *
 * @author ab
 */
package btools.util;


public class ByteDataReader
{
  protected byte[] ab;
  protected int aboffset;

  public ByteDataReader( byte[] byteArray )
  {
	 ab = byteArray;
  }

  public final int readInt()
  {
      int i3 = ab[aboffset++]& 0xff;
      int i2 = ab[aboffset++]& 0xff;
      int i1 = ab[aboffset++]& 0xff;
      int i0 = ab[aboffset++]& 0xff;
      return (i3 << 24) + (i2 << 16) + (i1 << 8) + i0;
  }

  public final long readLong()
  {
      long i7 = ab[aboffset++]& 0xff;
      long i6 = ab[aboffset++]& 0xff;
      long i5 = ab[aboffset++]& 0xff;
      long i4 = ab[aboffset++]& 0xff;
      long i3 = ab[aboffset++]& 0xff;
      long i2 = ab[aboffset++]& 0xff;
      long i1 = ab[aboffset++]& 0xff;
      long i0 = ab[aboffset++]& 0xff;
      return (i7 << 56) + (i6 << 48) + (i5 << 40) + (i4 << 32) + (i3 << 24) + (i2 << 16) + (i1 << 8) + i0;
  }

  public final boolean readBoolean()
  {
      int i0 = ab[aboffset++]& 0xff;
      return i0 != 0;
  }

  public final byte readByte()
  {
      int i0 = ab[aboffset++] & 0xff;
      return (byte)(i0);
  }

  public final short readShort()
  {
      int i1 = ab[aboffset++] & 0xff;
      int i0 = ab[aboffset++] & 0xff;
      return (short)( (i1 << 8) | i0);
  }

  public final int readVarLengthSigned()
  {
	  int v = readVarLengthUnsigned();
	  return ( v & 1 ) == 0 ? v >> 1 : -(v >> 1 );
  }

  public final int readVarLengthUnsigned()
  {
	int v = 0;
	int shift = 0;
	for(;;)
	{
	  int i7 = ab[aboffset++] & 0xff;
	  v |= (( i7 & 0x7f ) << shift);
	  if ( ( i7 & 0x80 ) == 0 ) break;
	  shift += 7;
	}
	return v;
  }

  public final void readFully( byte[] ta )
  {
	  System.arraycopy( ab, aboffset, ta, 0, ta.length );
	  aboffset += ta.length;
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
