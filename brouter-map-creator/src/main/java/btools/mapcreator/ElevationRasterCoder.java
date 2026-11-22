package btools.mapcreator;

import java.io.*;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.statcoding.PrefixedBitInputStream;
import btools.statcoding.PrefixedBitOutputStream;

/**
 * Encode/decode a raster.
 * <p>
 * File layout: magic word, a length-prefixed header block carrying major/minor version,
 * then the pixel data directly on the main stream.
 * Readers accept any minor version of a known major version and ignore trailing data
 * in the header block, so header fields can be appended in later minor versions.
 */
public class ElevationRasterCoder {
  private static final int MAGIC = 51423; // BEF
  private static final long MAJOR_VERSION = 1;
  private static final long MINOR_VERSION = 1;

  public void encodeRaster(ElevationRaster raster, BitOutputStream out) throws IOException {
    long t0 = System.currentTimeMillis();

    out.writeInt(MAGIC);
    try (PrefixedBitOutputStream bos = new PrefixedBitOutputStream(out, MAJOR_VERSION, MINOR_VERSION)) {
      bos.writeInt(raster.ncols);
      bos.writeInt(raster.nrows);
      bos.writeBoolean(raster.halfcol);
      bos.writeDouble(raster.xllcorner);
      bos.writeDouble(raster.yllcorner);
      bos.writeDouble(raster.cellsize);
      bos.writeShort(raster.noDataValue);
    }

    // pixel data follows the header block directly on the main stream (too large to be buffered)
    encodeRasterData(raster, out);
    long t1 = System.currentTimeMillis();

    System.out.println("finished encoding in " + (t1 - t0) + " ms");
  }

  public ElevationRaster decodeRaster(BitInputStream in) throws IOException {
    if (in.readInt() != MAGIC) {
      throw new IllegalArgumentException("this is not a BRouter Elevation File");
    }
    ElevationRaster raster = new ElevationRaster();
    // throws if the major version is newer than we know
    try (PrefixedBitInputStream bis = new PrefixedBitInputStream(in, MAJOR_VERSION)) {
      raster.ncols = bis.readInt();
      raster.nrows = bis.readInt();
      raster.halfcol = bis.readBoolean();
      raster.xllcorner = bis.readDouble();
      raster.yllcorner = bis.readDouble();
      raster.cellsize = bis.readDouble();
      raster.noDataValue = bis.readShort();
    }
    long arraySize = (long) raster.ncols * (long) raster.nrows;
    if ( arraySize > Integer.MAX_VALUE / 2 ) { // we allow 2 GB
      throw new IllegalArgumentException("raster too large: " + arraySize + " pixels");
    }
    raster.eval_array = new short[(int) arraySize];

    decodeRasterData(raster, in);
    return raster;
  }


  private void encodeRasterData(ElevationRaster raster, BitOutputStream bos) throws IOException {
    int nrows = raster.nrows;
    int ncols = raster.ncols;
    short[] pixels = raster.eval_array;
    int colstep = raster.halfcol ? 2 : 1;

    int lastValue = 0;
    int lastLastValue = 0;
    int repCount = 0;
    int diffshift = 0;

    for (int row = 0; row < nrows; row++) {
      short lastval = Short.MIN_VALUE; // nodata
      for (int col = 0; col < ncols; col += colstep) {
        short val = pixels[row * ncols + col];
        if (val == -32766) {
          val = lastval; // replace remaining (border) skips
        } else {
          lastval = val;
        }

        // remap nodata
        int code = val == Short.MIN_VALUE ? -1 : (val < 0 ? val - 1 : val);

        // encode in a repcount/delta format
        if (code != lastValue && repCount > 0) {
          int d = lastValue - lastLastValue;
          lastLastValue = lastValue;

          bos.encodeBit(d < 0);
          bos.encodeUnsignedVarBits((d < 0 ? -d : d) - diffshift);
          bos.encodeUnsignedVarBits(repCount - 1);

          diffshift = 1;
          repCount = 0;
        }
        lastValue = code;
        repCount++;
      }
    }

    // flush the diff/rep sequence
    int d = lastValue - lastLastValue;
    bos.encodeBit(d < 0);
    bos.encodeUnsignedVarBits((d < 0 ? -d : d) - diffshift);
    bos.encodeUnsignedVarBits(repCount - 1);
  }

  private void decodeRasterData(ElevationRaster raster, BitInputStream bis) throws IOException {
    int nrows = raster.nrows;
    int ncols = raster.ncols;
    short[] pixels = raster.eval_array;
    int colstep = raster.halfcol ? 2 : 1;

    int repCount = 0;
    int diffshift = 0;
    int code = 0;

    for (int row = 0; row < nrows; row++) {
      for (int col = 0; col < ncols; col += colstep) {

        // decode from a repcount/delta format
        if (repCount == 0) {
          boolean negative = bis.decodeBit();
          int d = diffshift + (int)bis.decodeUnsignedVarBits();
          repCount = 1 + (int)bis.decodeUnsignedVarBits();
          code += negative ? -d : d;
          diffshift = 1;
        }
        repCount--;

        // remap nodata
        int v30 = code == -1 ? Short.MIN_VALUE : (code < 0 ? code + 1 : code);
        pixels[row * ncols + col] = (short) (v30);
      }
      if (raster.halfcol) {
        for (int col = 1; col < ncols - 1; col += colstep) {
          int l = pixels[row * ncols + col - 1];
          int r = pixels[row * ncols + col + 1];
          short v30 = Short.MIN_VALUE; // nodata
          if (l > -32766 && r > -32766) {
            v30 = (short) ((l + r) / 2);
          }
          pixels[row * ncols + col] = v30;
        }
      }
    }
  }
}
