package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Container for relation data on the preprocessor level
 *
 * @author ab
 */
public class RelationData extends ItemData {
  public static final long TYPE = 4L;
  public long rid;
  public String route;
  public String network;
  public String state;
  public List<Long> ways;

  public RelationData(long id, List<Long> ways) {
    rid = id;
    this.ways = ways;
  }

  public RelationData(BitInputStream bis) throws IOException {
    ways = new ArrayList<>(16);
    rid = bis.decodeVarBytes();
    route = bis.readUTF();
    network = bis.readUTF();
    state = bis.readUTF();
    for (; ; ) {
      long wid = bis.decodeVarBytes();
      if (wid == -1L) break;
      ways.add(wid);
    }
  }

  public void writeTo(BitOutputStream bos) throws IOException {
    bos.encodeVarBytes(TYPE);
    bos.encodeVarBytes(rid);
    bos.writeUTF(route);
    bos.writeUTF(network);
    bos.writeUTF(state);
    int size = ways.size();
    for (int i = 0; i < size; i++) {
      bos.encodeVarBytes(ways.get(i));
    }
    bos.encodeVarBytes(-1L); // stopbyte
  }

}
