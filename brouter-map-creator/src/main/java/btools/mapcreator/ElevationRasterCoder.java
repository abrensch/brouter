package btools.mapcreator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import btools.util.MixCoderDataInputStream;
import btools.util.MixCoderDataOutputStream;

//
// Encode/decode a raster
//

public class ElevationRasterCoder {
  public void encodeRaster(ElevationRaster raster, OutputStream os) throws IOException {
    DataOutputStream dos = new DataOutputStream(os);

    long t0 = System.currentTimeMillis();

    dos.writeInt(raster.ncols);
    dos.writeInt(raster.nrows);
    dos.writeBoolean(raster.halfcol);
    dos.writeDouble(raster.xllcorner);
    dos.writeDouble(raster.yllcorner);
    dos.writeDouble(raster.cellsize);
    dos.writeShort(raster.noDataValue);

    _encodeRaster(raster, os);
    long t1 = System.currentTimeMillis();

    System.out.println("finished encoding in " + (t1 - t0) + " ms");
  }

  public ElevationRaster decodeRaster(InputStream is) throws IOException {
    DataInputStream dis = new DataInputStream(is);

    long t0 = System.currentTimeMillis();

    ElevationRaster raster = new ElevationRaster();
    raster.ncols = dis.readInt();
    raster.nrows = dis.readInt();
    raster.halfcol = dis.readBoolean();
    raster.xllcorner = dis.readDouble();
    raster.yllcorner = dis.readDouble();
    raster.cellsize = dis.readDouble();
    raster.noDataValue = dis.readShort();
    raster.eval_array = new short[raster.ncols * raster.nrows];

    _decodeRaster(raster, is);

    raster.usingWeights = false; // raster.ncols > 6001;

    long t1 = System.currentTimeMillis();
    System.out.println("finished decoding in " + (t1 - t0) + " ms ncols=" + raster.ncols + " nrows=" + raster.nrows);
    return raster;
  }


  private void _encodeRaster(ElevationRaster raster, OutputStream os) throws IOException {
    MixCoderDataOutputStream mco = new MixCoderDataOutputStream(os);
    int nrows = raster.nrows;
    int ncols = raster.ncols;
    short[] pixels = raster.eval_array;
    int colstep = raster.halfcol ? 2 : 1;

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
        mco.writeMixed(code);
      }
    }
    mco.flush();
  }

  private void _decodeRaster(ElevationRaster raster, InputStream is) throws IOException {
    MixCoderDataInputStream mci = new MixCoderDataInputStream(is);
    int nrows = raster.nrows;
    int ncols = raster.ncols;
    short[] pixels = raster.eval_array;
    int colstep = raster.halfcol ? 2 : 1;

    for (int row = 0; row < nrows; row++) {
      for (int col = 0; col < ncols; col += colstep) {
        int code = mci.readMixed();

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
