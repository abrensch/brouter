package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.util.LongList;

import java.io.IOException;

/**
 * Container for relation data on the preprocessor level
 *
 * @author ab
 */
public class RelationData extends MapCreatorBase {
  public static final long TYPE = 3L;
  public long rid;
  public String route;
  public String network;
  public String state;
  public LongList ways;

  public RelationData(long id, LongList ways) {
    rid = id;
    this.ways = ways;
  }

  public RelationData(BitInputStream bis) throws IOException {
    ways = new LongList(16);
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
