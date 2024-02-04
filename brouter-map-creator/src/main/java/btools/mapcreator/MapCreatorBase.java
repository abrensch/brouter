/**
 * common base class for the map-filters
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import btools.util.DiffCoderDataOutputStream;

public abstract class MapCreatorBase implements WayListener, NodeListener, RelationListener {
  private DiffCoderDataOutputStream[] tileOutStreams;
  protected File outTileDir;

  protected Map<String, String> tags;

  public void putTag(String key, String value) {
    if (tags == null) tags = new HashMap<>();
    tags.put(key, value);
  }

  public String getTag(String key) {
    return tags == null ? null : tags.get(key);
  }

  public Map<String, String> getTagsOrNull() {
    return tags;
  }

  public void setTags(Map<String, String> tags) {
    this.tags = tags;
  }

  protected static long readId(DataInputStream is) throws IOException {
    int offset = is.readByte();
    if (offset == 32) return -1;
    long i = is.readInt();
    i = i << 5;
    return i | offset;
  }

  protected static void writeId(DataOutputStream o, long id) throws IOException {
    if (id == -1) {
      o.writeByte(32);
      return;
    }
    int offset = (int) (id & 0x1f);
    int i = (int) (id >> 5);
    o.writeByte(offset);
    o.writeInt(i);
  }


  protected static File[] sortBySizeAsc(File[] files) {
    int n = files.length;
    long[] sizes = new long[n];
    File[] sorted = new File[n];
    for (int i = 0; i < n; i++) sizes[i] = files[i].length();
    for (int nf = 0; nf < n; nf++) {
      int idx = -1;
      long min = -1;
      for (int i = 0; i < n; i++) {
        if (sizes[i] != -1 && (idx == -1 || sizes[i] < min)) {
          min = sizes[i];
          idx = i;
        }
      }
      sizes[idx] = -1;
      sorted[nf] = files[idx];
    }
    return sorted;
  }

  protected File fileFromTemplate(File template, File dir, String suffix) {
    String filename = template.getName();
    filename = filename.substring(0, filename.length() - 3) + suffix;
    return new File(dir, filename);
  }

  protected DataInputStream createInStream(File inFile) throws IOException {
    return new DataInputStream(new BufferedInputStream(new FileInputStream(inFile)));
  }

  protected DiffCoderDataOutputStream createOutStream(File outFile) throws IOException {
    return new DiffCoderDataOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
  }

  protected DiffCoderDataOutputStream getOutStreamForTile(int tileIndex) throws Exception {
    if (tileOutStreams == null) {
      tileOutStreams = new DiffCoderDataOutputStream[64];
    }

    if (tileOutStreams[tileIndex] == null) {
      tileOutStreams[tileIndex] = createOutStream(new File(outTileDir, getNameForTile(tileIndex)));
    }
    return tileOutStreams[tileIndex];
  }

  protected String getNameForTile(int tileIndex) {
    throw new IllegalArgumentException("getNameForTile not implemented");
  }

  protected void closeTileOutStreams() throws Exception {
    if (tileOutStreams == null) {
      return;
    }
    for (int tileIndex = 0; tileIndex < tileOutStreams.length; tileIndex++) {
      if (tileOutStreams[tileIndex] != null) tileOutStreams[tileIndex].close();
      tileOutStreams[tileIndex] = null;
    }
  }


  // interface dummys

  @Override
  public void nodeFileStart(File nodefile) throws Exception {
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
  }

  @Override
  public void nodeFileEnd(File nodefile) throws Exception {
  }

  @Override
  public boolean wayFileStart(File wayfile) throws Exception {
    return true;
  }

  @Override
  public void nextWay(WayData data) throws Exception {
  }

  @Override
  public void wayFileEnd(File wayfile) throws Exception {
  }

  @Override
  public void nextRelation(RelationData data) throws Exception {
  }

  @Override
  public void nextRestriction(RelationData data, long fromWid, long toWid, long viaNid) throws Exception {
  }

}
