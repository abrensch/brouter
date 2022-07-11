package btools.mapcreator;

import btools.util.DiffCoderDataInputStream;
import btools.util.DiffCoderDataOutputStream;

/**
 * Container for node data on the preprocessor level
 *
 * @author ab
 */
public class NodeData extends MapCreatorBase {
  public long nid;
  public int ilon;
  public int ilat;
  public byte[] description;
  public short selev = Short.MIN_VALUE;

  public NodeData(long id, double lon, double lat) {
    nid = id;
    ilat = (int) ((lat + 90.) * 1000000. + 0.5);
    ilon = (int) ((lon + 180.) * 1000000. + 0.5);
  }

  public NodeData(DiffCoderDataInputStream dis) throws Exception {
    nid = dis.readDiffed(0);
    ilon = (int) dis.readDiffed(1);
    ilat = (int) dis.readDiffed(2);
    int mode = dis.readByte();
    if ((mode & 1) != 0) {
      int dlen = dis.readShort();
      description = new byte[dlen];
      dis.readFully(description);
    }
    if ((mode & 2) != 0) selev = dis.readShort();
  }

  public void writeTo(DiffCoderDataOutputStream dos) throws Exception {
    dos.writeDiffed(nid, 0);
    dos.writeDiffed(ilon, 1);
    dos.writeDiffed(ilat, 2);
    int mode = (description == null ? 0 : 1) | (selev == Short.MIN_VALUE ? 0 : 2);
    dos.writeByte((byte) mode);
    if ((mode & 1) != 0) {
      dos.writeShort(description.length);
      dos.write(description);
    }
    if ((mode & 2) != 0) dos.writeShort(selev);
  }
}
