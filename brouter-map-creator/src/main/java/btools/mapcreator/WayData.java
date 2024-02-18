package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.util.LongList;

/**
 * Container for waydata on the preprocessor level
 *
 * @author ab
 */
public class WayData extends MapCreatorBase {
  public static final long TYPE = 2l;
  public long wid;
  public byte[] description;
  public LongList nodes;

  public WayData(long id, LongList nodes) {
    wid = id;
    this.nodes = nodes;
  }

  public WayData(BitInputStream bis) throws Exception {
    nodes = new LongList(16);
    wid = bis.decodeVarBytes();
    description = bis.decodeSizedByteArray();
    for (; ; ) {
      long nid = bis.decodeVarBytes();
      if (nid == -1L) break;
      nodes.add(nid);
    }
  }

  public void writeTo(BitOutputStream bos) throws Exception {
    bos.encodeVarBytes(TYPE);
    bos.encodeVarBytes(wid);
    bos.encodeSizedByteArray(description);
    int size = nodes.size();
    for (int i = 0; i < size; i++) {
      bos.encodeVarBytes(nodes.get(i));
    }
    bos.encodeVarBytes(-1L); // stopbyte
  }
}
