package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Container for waydata on the preprocessor level
 *
 * @author ab
 */
public class WayData extends ItemData {
  public static final long TYPE = 3L;
  public long wid;
  public byte[] description;
  public List<Long> nodes;

  public WayData(long id, List<Long> nodes) {
    wid = id;
    this.nodes = nodes;
  }

  public WayData(BitInputStream bis) throws IOException {
    nodes = new ArrayList<>(16);
    wid = bis.decodeVarBytes();
    description = bis.decodeSizedByteArray();
    for (; ; ) {
      long nid = bis.decodeVarBytes();
      if (nid == -1L) break;
      nodes.add(nid);
    }
  }

  public void writeTo(BitOutputStream bos) throws IOException {
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
