package btools.util;

  public final class BitCoderContext
  {
	  private byte[] ab;
	  private int idx = -1;
	  private int bm = 0x100 ; // byte mask
	  private int b;

  	  public BitCoderContext( byte[] ab )
  	  {
  	  	this.ab = ab;
  	  }
  	
	  // encode a distance with a variable bit length
	  // (poor mans huffman tree)
	  // 1 -> 0
	  // 01 -> 1 + following 1-bit word ( 1..2 )
	  // 001 -> 3 + following 2-bit word ( 3..6 )
	  // 0001 -> 7 + following 3-bit word ( 7..14 ) etc.
	  
      public void encodeDistance( int value )
      {
         int range = 0;
         while ( value > range )
         {
           encodeBit( false );
           value -= range+1;
           range = 2*range + 1;
         }
         encodeBit( true );
         encode( range, value );
      }

      // twin to encodeDistance
      public int decodeDistance()
      {
          int range = 0;
          int value = 0;
          while ( !decodeBit() )
          {
            value += range+1;
            range = 2*range + 1;
          }
          return value + decode( range );
      }

      public void encodeBit( boolean value )
      {
          	if ( bm == 0x100 ) { bm = 1; ab[++idx] = 0; }
          	if ( value ) ab[idx] |= bm;
          	bm <<= 1;
      }

      public boolean decodeBit()
      {
        	if ( bm == 0x100 ) { bm = 1; b = ab[++idx]; }
        	boolean value = ( (b & bm) != 0 );
        	bm <<= 1;
        	return value;
      }

      // encode a symbol with number of bits according to maxvalue
      public void encode( int max, int value )
      {
          int im = 1; // integer mask
          while( max != 0 )
          {
          	if ( bm == 0x100 ) { bm = 1; ab[++idx] = 0; }
          	if ( (value & im) != 0 ) ab[idx] |= bm;
          	max >>= 1;
          	bm <<= 1;
          	im <<= 1;
          }
      }
      
      public int getEncodedLength()
      {
      	return idx+1;
      }

      public int decode( int max )
      {
          int value = 0;
          int im = 1; // integer mask
          while( max != 0 )
          {
          	if ( bm == 0x100 ) { bm = 1; b = ab[++idx]; }
          	if ( (b & bm) != 0 ) value |= im;
          	max >>= 1;
          	bm <<= 1;
          	im <<= 1;
          }
          return value;
      }
  }
