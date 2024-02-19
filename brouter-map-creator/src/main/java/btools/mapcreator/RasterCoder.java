package btools.mapcreator;

import java.io.*;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;

//
// Encode/decode a raster
//

public class RasterCoder {
  public void encodeRaster(SrtmRaster raster, BitOutputStream bos) throws IOException {
    long t0 = System.currentTimeMillis();

    bos.writeInt(51423); // magic word BEF version 2
    bos.writeInt(raster.ncols);
    bos.writeInt(raster.nrows);
    bos.writeBoolean(raster.halfcol);
    bos.writeDouble(raster.xllcorner);
    bos.writeDouble(raster.yllcorner);
    bos.writeDouble(raster.cellsize);
    bos.writeShort(raster.noDataValue);

    encodeRasterData(raster, bos);
    long t1 = System.currentTimeMillis();

    System.out.println("finished encoding in " + (t1 - t0) + " ms");
  }

  public SrtmRaster decodeRaster(BitInputStream bis) throws IOException {
    SrtmRaster raster = new SrtmRaster();
    int befMagic =  bis.readInt();
    if ( befMagic != 51423 ) {
      throw new IllegalArgumentException( "this is not a BRouter Elevation File in BEF Version 2" );
    }
    raster.ncols = bis.readInt();
    raster.nrows = bis.readInt();
    raster.halfcol = bis.readBoolean();
    raster.xllcorner = bis.readDouble();
    raster.yllcorner = bis.readDouble();
    raster.cellsize = bis.readDouble();
    raster.noDataValue = bis.readShort();
    raster.eval_array = new short[raster.ncols * raster.nrows];

    decodeRasterData(raster, bis);

    raster.usingWeights = raster.ncols > 6001;
    return raster;
  }


  private void encodeRasterData(SrtmRaster raster, BitOutputStream bos) throws IOException {
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

  private void decodeRasterData(SrtmRaster raster, BitInputStream bis) throws IOException {
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
        if (raster.usingWeights && v30 > -32766) {
          v30 *= 2;
        }
        pixels[row * ncols + col] = (short) (v30);
      }
      if (raster.halfcol) {
        for (int col = 1; col < ncols - 1; col += colstep) {
          int l = (int) pixels[row * ncols + col - 1];
          int r = (int) pixels[row * ncols + col + 1];
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
