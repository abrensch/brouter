package btools.mapcreator;
import org.openstreetmap.osmosis.osmbinary.BinaryParser;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import org.openstreetmap.osmosis.osmbinary.file.BlockInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
/**
 * OsmPbfDecoder decodes a .osm.pbf file containing OSM data.
 * <p>
 * The PBF block handling (blob decompression, string tables, delta decoding,
 * coordinate granularity) is done by the osmosis {@link BinaryParser}, so this
 * class only maps the decoded primitives to the data classes of the MapCreator.
 * <p>
 * It contains additional logic for reading from a file while it is written,
 * to support pipelining in BRouter map creation.
 */
public class OsmPbfDecoder extends BinaryParser {
  private static final List<String> SUPPORTED_FEATURES = Arrays.asList("OsmSchema-V0.6", "DenseNodes");
  private OsmParser parser;
  public void readMap(File mapFile, OsmParser parser) throws Exception {
    this.parser = parser;
    System.out.println("*** PBF Parsing: " + mapFile);
    boolean avoidMapPolling = Boolean.getBoolean("avoidMapPolling");
    if (!avoidMapPolling) {
      // wait for file to become available
      while (!mapFile.exists()) {
        System.out.println("--- waiting for " + mapFile + " to become available");
        Thread.sleep(10000);
      }
    }
    try (InputStream in = new BufferedInputStream(new GrowingFileInputStream(mapFile, !avoidMapPolling))) {
      new BlockInputStream(in, this).process();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
  @Override
  public void complete() {
    // nothing to do
  }
  @Override
  protected void parse(Osmformat.HeaderBlock header) {
    // we can't continue if there are unsupported features, so collect all of them for the message
    List<String> unsupported = header.getRequiredFeaturesList().stream()
      .filter(f -> !SUPPORTED_FEATURES.contains(f))
      .collect(Collectors.toList());
    if (!unsupported.isEmpty()) {
      throw new RuntimeException("PBF file contains unsupported features " + unsupported);
    }
  }
  @Override
  protected void parseNodes(List<Osmformat.Node> nodes) {
    for (Osmformat.Node node : nodes) {
      NodeData n = new NodeData(node.getId(), parseLon(node.getLon()), parseLat(node.getLat()));
      n.setTags(buildTags(node.getKeysList(), node.getValsList()));
      addNode(n);
    }
  }
  @Override
  protected void parseDense(Osmformat.DenseNodes nodes) {
    long nodeId = 0;
    long lat = 0;
    long lon = 0;
    int kvIndex = 0;
    List<Integer> kvs = nodes.getKeysValsList();
    for (int i = 0; i < nodes.getIdCount(); i++) {
      nodeId += nodes.getId(i);
      lat += nodes.getLat(i);
      lon += nodes.getLon(i);
      // key/value string indexes are sequential, each node's tags are terminated by a 0
      Map<String, String> tags = null;
      while (kvIndex < kvs.size()) {
        int key = kvs.get(kvIndex++);
        if (key == 0) {
          break;
        }
        if (tags == null) {
          tags = new HashMap<>();
        }
        tags.put(getStringById(key), getStringById(kvs.get(kvIndex++)));
      }
      NodeData n = new NodeData(nodeId, parseLon(lon), parseLat(lat));
      n.setTags(tags);
      addNode(n);
    }
  }
  @Override
  protected void parseWays(List<Osmformat.Way> ways) {
    for (Osmformat.Way way : ways) {
      List<Long> wayNodes = new ArrayList<>(16);
      long nodeId = 0;
      for (long delta : way.getRefsList()) {
        nodeId += delta;
        wayNodes.add(nodeId);
      }
      WayData w = new WayData(way.getId(), wayNodes);
      w.setTags(buildTags(way.getKeysList(), way.getValsList()));
      try {
        parser.addWay(w);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
  @Override
  protected void parseRelations(List<Osmformat.Relation> relations) {
    for (Osmformat.Relation relation : relations) {
      List<Long> wayIds = new ArrayList<>(16);
      List<Long> fromWid = null;
      List<Long> toWid = null;
      List<Long> viaNid = null;
      long refId = 0;
      for (int i = 0; i < relation.getMemidsCount(); i++) {
        refId += relation.getMemids(i);
        String role = getStringById(relation.getRolesSid(i));
        Osmformat.Relation.MemberType type = relation.getTypes(i);
        if (type == Osmformat.Relation.MemberType.WAY) { // currently just way members
          wayIds.add(refId);
          if ("from".equals(role)) fromWid = addLong(fromWid, refId);
          if ("to".equals(role)) toWid = addLong(toWid, refId);
        } else if (type == Osmformat.Relation.MemberType.NODE && "via".equals(role)) {
          viaNid = addLong(viaNid, refId);
        }
      }
      parser.addRelation(relation.getId(), buildTags(relation.getKeysList(), relation.getValsList()),
        wayIds, fromWid, toWid, viaNid);
    }
  }
  private void addNode(NodeData n) {
    try {
      parser.addNode(n);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
  private static List<Long> addLong(List<Long> ll, long l) {
    List<Long> list = ll == null ? new ArrayList<>(1) : ll;
    list.add(l);
    return list;
  }
  private Map<String, String> buildTags(List<Integer> keys, List<Integer> values) {
    if (keys.isEmpty()) {
      return null;
    }
    Map<String, String> tags = new HashMap<>();
    for (int i = 0; i < keys.size(); i++) {
      tags.put(getStringById(keys.get(i)), getStringById(values.get(i)));
    }
    return tags;
  }
  /**
   * Reads a file that may still be written by another process: reading
   * continues if either more than 100 MB are unread, or the file size
   * is known to be unchanged for more than 2 minutes.
   */
  private static final class GrowingFileInputStream extends InputStream {
    private static final long MIN_UNREAD = 100_000_000L;
    private static final long STABLE_MILLIS = 120_000L;
    private final File file;
    private final InputStream in;
    private final boolean poll;
    private long bytesRead;
    private long currentSize;
    private long currentSizeTime = System.currentTimeMillis();
    GrowingFileInputStream(File file, boolean poll) throws IOException {
      this.file = file;
      this.poll = poll;
      this.currentSize = file.length();
      this.in = new FileInputStream(file);
    }
    private void waitForData() throws IOException {
      try {
        while (currentSize - bytesRead < MIN_UNREAD) {
          long newSize = file.length();
          if (newSize != currentSize) {
            currentSize = newSize;
            currentSizeTime = System.currentTimeMillis();
          } else if (System.currentTimeMillis() - currentSizeTime > STABLE_MILLIS) {
            break;
          }
          if (currentSize - bytesRead < MIN_UNREAD) {
            System.out.println("--- waiting for more data, currentSize=" + currentSize + " bytesRead=" + bytesRead);
            Thread.sleep(10000);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while waiting for data", e);
      }
    }
    @Override
    public int read() throws IOException {
      if (poll) {
        waitForData();
      }
      int b = in.read();
      if (b >= 0) {
        bytesRead++;
      }
      return b;
    }
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (poll) {
        waitForData();
      }
      int n = in.read(b, off, len);
      if (n > 0) {
        bytesRead += n;
      }
      return n;
    }
    @Override
    public void close() throws IOException {
      in.close();
    }
  }
}
