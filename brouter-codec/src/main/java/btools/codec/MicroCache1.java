package btools.codec;

import btools.util.ByteDataWriter;

/**
 * MicroCache1 is the old data format as of brouter 1.1 that does not allow to
 * filter out unaccessable nodes at the beginning of the cache pipeline
 * 
 * Kept for backward compatibility
 */
public final class MicroCache1 extends MicroCache
{
  private int lonIdxBase;
  private int latIdxBase;

  public MicroCache1( int size, byte[] databuffer, int lonIdx80, int latIdx80 ) throws Exception
  {
    super( databuffer ); // sets ab=databuffer, aboffset=0
    faid = new int[size];
    fapos = new int[size];
    this.size = 0;
    lonIdxBase = ( lonIdx80 / 5 ) * 62500 + 31250;
    latIdxBase = ( latIdx80 / 5 ) * 62500 + 31250;
  }

  public MicroCache1( byte[] databuffer, int lonIdx80, int latIdx80 ) throws Exception
  {
    super( databuffer ); // sets ab=databuffer, aboffset=0
    lonIdxBase = ( lonIdx80 / 5 ) * 62500 + 31250;
    latIdxBase = ( latIdx80 / 5 ) * 62500 + 31250;

    size = readInt();

    // get net size
    int nbytes = 0;
    for ( int i = 0; i < size; i++ )
    {
      aboffset += 4;
      int bodySize = readVarLengthUnsigned();
      aboffset += bodySize;
      nbytes += bodySize;
    }

    // new array with only net data
    byte[] nab = new byte[nbytes];
    aboffset = 4;
    int noffset = 0;
    faid = new int[size];
    fapos = new int[size];

    for ( int i = 0; i < size; i++ )
    {
      faid[i] = readInt() ^ 0x8000; // flip lat-sign for correct ordering

      int bodySize = readVarLengthUnsigned();
      System.arraycopy( ab, aboffset, nab, noffset, bodySize );
      aboffset += bodySize;
      noffset += bodySize;
      fapos[i] = noffset;
    }

    ab = nab;
    aboffset = noffset;
    init( size );
  }

  @Override
  public long expandId( int id32 )
  {
    int lon32 = lonIdxBase + (short) ( id32 >> 16 );
    int lat32 = latIdxBase + (short) ( ( id32 & 0xffff ) ^ 0x8000 );
    return ( (long) lon32 ) << 32 | lat32;
  }

  @Override
  public int shrinkId( long id64 )
  {
    int lon32 = (int) ( id64 >> 32 );
    int lat32 = (int) ( id64 & 0xffffffff );
    return ( lon32 - lonIdxBase ) << 16 | ( ( ( lat32 - latIdxBase ) & 0xffff ) ^ 0x8000 );
  }

  @Override
  public int encodeMicroCache( byte[] buffer )
  {
    ByteDataWriter dos = new ByteDataWriter( buffer );
    dos.writeInt( size );
    for ( int n = 0; n < size; n++ )
    {
      dos.writeInt( faid[n] ^ 0x8000 );
      int start = n > 0 ? fapos[n - 1] : 0;
      int end = fapos[n];
      int len = end - start;
      dos.writeVarLengthUnsigned( len );
      dos.write( ab, start, len );
    }
    return dos.size();
  }
}
