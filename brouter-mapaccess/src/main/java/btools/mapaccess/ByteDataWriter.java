/**
 * fast data-reading from a byte-array
 *
 * @author ab
 */
package btools.mapaccess;


final class ByteDataWriter
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
}
