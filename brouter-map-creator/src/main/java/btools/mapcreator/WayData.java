package btools.mapcreator;

import java.io.DataInputStream;
import java.io.DataOutputStream;

import btools.util.LongList;

/**
 * Container for waydata on the preprocessor level
 *
 * @author ab
 */
public class WayData extends MapCreatorBase {
  public long wid;
  public byte[] description;
  public LongList nodes;

  public WayData(long id) {
    wid = id;
    nodes = new LongList(16);
  }

  public WayData(long id, LongList nodes) {
    wid = id;
    this.nodes = nodes;
  }

  public WayData(DataInputStream di) throws Exception {
    nodes = new LongList(16);
    wid = readId(di);
    int dlen = di.readByte();
    description = new byte[dlen];
    di.readFully(description);
    for (; ; ) {
      long nid = readId(di);
      if (nid == -1) break;
      nodes.add(nid);
    }
  }

  public void writeTo(DataOutputStream dos) throws Exception {
    writeId(dos, wid);
    dos.writeByte(description.length);
    dos.write(description);
    int size = nodes.size();
    for (int i = 0; i < size; i++) {
      writeId(dos, nodes.get(i));
    }
    writeId(dos, -1); // stopbyte
  }
}
