package btools.mapcreator;

import java.io.*;
import btools.util.*;

//
// Encode/decode a raster
//

public class RasterCoder
{
  public void encodeRaster(SrtmRaster raster, OutputStream os) throws IOException
  {
    DataOutputStream dos = new DataOutputStream(os);

    long t0 = System.currentTimeMillis();

    dos.writeInt(raster.ncols);
    dos.writeInt(raster.nrows);
    dos.writeDouble(raster.xllcorner);
    dos.writeDouble(raster.yllcorner);
    dos.writeDouble(raster.cellsize);
    dos.writeShort(raster.noDataValue);

    _encodeRaster(raster, os);
    long t1 = System.currentTimeMillis();

    System.out.println("finished encoding in " + (t1 - t0) + " ms");
  }

  public SrtmRaster decodeRaster(InputStream is) throws IOException
  {
    DataInputStream dis = new DataInputStream(is);

    long t0 = System.currentTimeMillis();

    SrtmRaster raster = new SrtmRaster();
    raster.ncols = dis.readInt();
    raster.nrows = dis.readInt();
    raster.xllcorner = dis.readDouble();
    raster.yllcorner = dis.readDouble();
    raster.cellsize = dis.readDouble();
    raster.noDataValue = dis.readShort();
    raster.eval_array = new short[raster.ncols * raster.nrows];

    _decodeRaster(raster, is);
    
    raster.usingWeights = true;

    long t1 = System.currentTimeMillis();
    System.out.println("finished decoding in " + (t1 - t0) + " ms");
    return raster;
  }


  private void _encodeRaster(SrtmRaster raster, OutputStream os) throws IOException
  {
    MixCoderDataOutputStream mco = new MixCoderDataOutputStream(os);
    int nrows = raster.nrows;
    int ncols = raster.ncols;
    short[] pixels = raster.eval_array;

    for (int row = 0; row < nrows; row++)
    {
      for (int col = 0; col < ncols; col++)
      {
        mco.writeMixed(pixels[row * ncols + col]);
      }
    }
    mco.flush();
  }

  private void _decodeRaster(SrtmRaster raster, InputStream is) throws IOException
  {
    MixCoderDataInputStream mci = new MixCoderDataInputStream(is);
    int nrows = raster.nrows;
    int ncols = raster.ncols;
    short[] pixels = raster.eval_array;

    for (int row = 0; row < nrows; row++)
    {
      for (int col = 0; col < ncols; col++)
      {
        pixels[row * ncols + col] = (short) mci.readMixed();
      }
    }
  }
}
