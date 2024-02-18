package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;

/**
 * Container for node data on the preprocessor level
 *
 * @author ab
 */
public class NodeData extends MapCreatorBase {

  public static final long TYPE = 1L;
  public static final long NID_TYPE = 5L;
  public long nid;
  public int iLon;
  public int iLat;
  public byte[] description;
  public short sElev = Short.MIN_VALUE;

  public NodeData(long id, double lon, double lat) {
    nid = id;
    iLat = (int) ((lat + 90.) * 1000000. + 0.5);
    iLon = (int) ((lon + 180.) * 1000000. + 0.5);
  }

  public NodeData(BitInputStream bis) throws Exception {
    nid = bis.readDiffed(0);
    iLon = (int) bis.readDiffed(1);
    iLat = (int) bis.readDiffed(2);
    int mode = bis.readByte();
    if ((mode & 1) != 0) {
      description = bis.decodeSizedByteArray();
    }
    if ((mode & 2) != 0) sElev = bis.readShort();
  }

  public void writeTo(BitOutputStream bos) throws Exception {
    bos.encodeVarBytes(TYPE);
    bos.writeDiffed(nid, 0);
    bos.writeDiffed(iLon, 1);
    bos.writeDiffed(iLat, 2);
    int mode = (description == null ? 0 : 1) | (sElev == Short.MIN_VALUE ? 0 : 2);
    bos.writeByte((byte) mode);
    if ((mode & 1) != 0) {
      bos.encodeSizedByteArray(description);
    }
    if ((mode & 2) != 0) bos.writeShort(sElev);
  }
}
