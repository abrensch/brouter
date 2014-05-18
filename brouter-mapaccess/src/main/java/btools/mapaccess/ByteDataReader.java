/**
 * fast data-reading from a byte-array
 *
 * @author ab
 */
package btools.mapaccess;


final class ByteDataReader
{
  private byte[] ab;
  private int aboffset;

  public ByteDataReader( byte[] byteArray )
  {
	 ab = byteArray;
  }

  public int readInt()
  {
      int i3 = ab[aboffset++]& 0xff;
      int i2 = ab[aboffset++]& 0xff;
      int i1 = ab[aboffset++]& 0xff;
      int i0 = ab[aboffset++]& 0xff;
      return (i3 << 24) + (i2 << 16) + (i1 << 8) + i0;
  }

  public long readLong()
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

  public boolean readBoolean()
  {
      int i0 = ab[aboffset++]& 0xff;
      return i0 != 0;
  }

  public byte readByte()
  {
      int i0 = ab[aboffset++] & 0xff;
      return (byte)(i0);
  }

  public short readShort()
  {
      int i1 = ab[aboffset++] & 0xff;
      int i0 = ab[aboffset++] & 0xff;
      return (short)( (i1 << 8) | i0);
  }

  public void readFully( byte[] ta )
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
