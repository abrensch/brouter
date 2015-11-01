package btools.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public class Raster2Png extends ByteDataWriter
{
  /** Constants for filter (NONE) */
  public static final int FILTER_NONE = 0;

  /** IHDR tag. */
  protected static final byte IHDR[] = { 73, 72, 68, 82 };

  /** IDAT tag. */
  protected static final byte IDAT[] = { 73, 68, 65, 84 };

  /** IEND tag. */
  protected static final byte IEND[] = { 73, 69, 78, 68 };

  /** geometry */
  protected int width, height;

  protected int[] imagePixels;

  /** CRC. */
  protected CRC32 crc = new CRC32();

  public Raster2Png()
  {
    super( null );
  }

  /**
   * Converts a pixel array to it's PNG equivalent
   */
  public byte[] pngEncode( int width, int height, int[] imagePixels ) throws IOException
  {
    this.width = width;
    this.height = height;
    this.imagePixels = imagePixels;

    // user a buffer large enough to hold the png
    ab = new byte[( ( width + 1 ) * height * 3 ) + 200];

    byte[] pngIdBytes =
    { -119, 80, 78, 71, 13, 10, 26, 10 };
    write( pngIdBytes );
    writeHeader();
    writeImageData();
    return toByteArray();
  }

  /**
   * Write a PNG "IHDR" chunk into the pngBytes array.
   */
  protected void writeHeader()
  {
    writeInt( 13 );
    int startPos = aboffset;
    write( IHDR );
    writeInt( width );
    writeInt( height );
    writeByte( 8 ); // bit depth
    writeByte( 2 ); // direct model
    writeByte( 0 ); // compression method
    writeByte( 0 ); // filter method
    writeByte( 0 ); // no interlace
    crc.reset();
    crc.update( ab, startPos, aboffset - startPos );
    writeInt( (int) crc.getValue() );
  }

  /**
   * Write the image data into the pngBytes array. This will write one or more
   * PNG "IDAT" chunks. In order to conserve memory, this method grabs as many
   * rows as will fit into 32K bytes, or the whole image; whichever is less.
   */
  protected void writeImageData() throws IOException
  {
    int rowsLeft = height; // number of rows remaining to write
    int startRow = 0; // starting row to process this time through
    int nRows; // how many rows to grab at a time

    byte[] scanLines; // the scan lines to be compressed
    int scanPos; // where we are in the scan lines

    byte[] compressedLines; // the resultant compressed lines
    int nCompressed; // how big is the compressed area?

    int bytesPerPixel = 3;

    Deflater scrunch = new Deflater( 6 );
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream( 1024 );

    DeflaterOutputStream compBytes = new DeflaterOutputStream( outBytes, scrunch );
    while (rowsLeft > 0)
    {
      nRows = Math.min( 32767 / ( width * ( bytesPerPixel + 1 ) ), rowsLeft );
      nRows = Math.max( nRows, 1 );

      int[] pixels = new int[width * nRows];

      getPixels( startRow, nRows, pixels );

      /*
       * Create a data chunk. scanLines adds "nRows" for the filter bytes.
       */
      scanLines = new byte[width * nRows * bytesPerPixel + nRows];

      scanPos = 0;
      for ( int i = 0; i < width * nRows; i++ )
      {
        if ( i % width == 0 )
        {
          scanLines[scanPos++] = (byte) FILTER_NONE;
        }
        scanLines[scanPos++] = (byte) ( ( pixels[i] >> 16 ) & 0xff );
        scanLines[scanPos++] = (byte) ( ( pixels[i] >> 8 ) & 0xff );
        scanLines[scanPos++] = (byte) ( ( pixels[i] ) & 0xff );
      }

      /*
       * Write these lines to the output area
       */
      compBytes.write( scanLines, 0, scanPos );

      startRow += nRows;
      rowsLeft -= nRows;
    }
    compBytes.close();

    /*
     * Write the compressed bytes
     */
    compressedLines = outBytes.toByteArray();
    nCompressed = compressedLines.length;

    crc.reset();
    writeInt( nCompressed );
    write( IDAT );
    crc.update( IDAT );
    write( compressedLines );
    crc.update( compressedLines, 0, nCompressed );

    writeInt( (int) crc.getValue() );
    scrunch.finish();

    // Write a PNG "IEND" chunk into the pngBytes array.
    writeInt( 0 );
    write( IEND );
    crc.reset();
    crc.update( IEND );
    writeInt( (int) crc.getValue() );
  }

  private void getPixels( int startRow, int nRows, int[] pixels )
  {
    for ( int i = 0; i < nRows; i++ )
    {
      int ir = i + startRow;
      for ( int ic = 0; ic < width; ic++ )
      {
        pixels[i * width + ic] = imagePixels[ir * width + ic];
      }
    }
  }
}
