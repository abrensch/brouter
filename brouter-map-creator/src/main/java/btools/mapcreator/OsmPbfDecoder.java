package btools.mapcreator;

import com.google.protobuf.InvalidProtocolBufferException;

import org.openstreetmap.osmosis.osmbinary.Fileformat;
import org.openstreetmap.osmosis.osmbinary.Osmformat;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import btools.util.LongList;

/**
 * OsmPbfDecoder decodes a .osm.pbf file containing OSM data
 *
 * The code ist mostly copied from the Osmmosis project
 * (see PbfBlobDecoder/PbfFieldDecoder in https://github.com/openstreetmap/osmosis )
 * and adapted for the needs of our MapCreator.
 * It needs the dependency to the osmosis project, which implies a transient
 * dependency to Google protobuf
 *
 * OsmPbfDecoder contains additional logic for reading from a file
 * while it is written, to support pipelining in BRouter map creation.
 */
public class OsmPbfDecoder {
  private String blobType;
  private byte[] rawBlob;

  private OsmParser parser;

  public void readMap(File mapFile, OsmParser parser) throws Exception {
    this.parser = parser;

    System.out.println("*** PBF Parsing: " + mapFile);

    long bytesRead = 0L;
    boolean avoidMapPolling = Boolean.getBoolean("avoidMapPolling");

    if (!avoidMapPolling) {
      // wait for file to become available
      while (!mapFile.exists()) {
        System.out.println("--- waiting for " + mapFile + " to become available");
        Thread.sleep(10000);
      }
    }

    long currentSize = mapFile.length();
    long currentSizeTime = System.currentTimeMillis();

    DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(mapFile)));


    for (; ; ) {
      if (!avoidMapPolling) {
        // continue reading if either more then a 100 MB unread, or the current-size is known for more than 2 Minutes
        while (currentSize - bytesRead < 100000000L) {
          long newSize = mapFile.length();
          if (newSize != currentSize) {
            currentSize = newSize;
            currentSizeTime = System.currentTimeMillis();
          } else if (System.currentTimeMillis() - currentSizeTime > 120000) {
            break;
          }
          if (currentSize - bytesRead < 100000000L) {
            System.out.println("--- waiting for more data, currentSize=" + currentSize + " bytesRead=" + bytesRead);
            Thread.sleep(10000);
          }
        }
      }

      int headerLength;
      try {
        headerLength = dis.readInt();
        bytesRead += 4;
      } catch (EOFException e) {
        break;
      }

      byte[] headerBuffer = new byte[headerLength];
      dis.readFully(headerBuffer);
      bytesRead += headerLength;
      Fileformat.BlobHeader blobHeader = Fileformat.BlobHeader.parseFrom(headerBuffer);

      byte[] blobData = new byte[blobHeader.getDatasize()];
      dis.readFully(blobData);
      bytesRead += blobData.length;

      blobType = blobHeader.getType();
      rawBlob = blobData;

      processBlob();
    }
    dis.close();
  }

  public void processBlob() throws Exception {
    if ("OSMHeader".equals(blobType)) {
      processOsmHeader(readBlobContent());
    } else if ("OSMData".equals(blobType)) {
      processOsmPrimitives(readBlobContent());
    } else {
      System.out.println("Skipping unrecognised blob type " + blobType);
    }
  }

  private byte[] readBlobContent() throws IOException {
    Fileformat.Blob blob = Fileformat.Blob.parseFrom(rawBlob);
    byte[] blobData;

    if (blob.hasRaw()) {
      blobData = blob.getRaw().toByteArray();
    } else if (blob.hasZlibData()) {
      Inflater inflater = new Inflater();
      inflater.setInput(blob.getZlibData().toByteArray());
      blobData = new byte[blob.getRawSize()];
      try {
        inflater.inflate(blobData);
      } catch (DataFormatException e) {
        throw new RuntimeException("Unable to decompress PBF blob.", e);
      }
      if (!inflater.finished()) {
        throw new RuntimeException("PBF blob contains incomplete compressed data.");
      }
    } else {
      throw new RuntimeException("PBF blob uses unsupported compression, only raw or zlib may be used.");
    }

    return blobData;
  }

  private void processOsmHeader(byte[] data) throws InvalidProtocolBufferException {
    // look for unsupported features in the file.
    List<String> supportedFeatures = Arrays.asList("OsmSchema-V0.6", "DenseNodes");
    List<String> unsupportedFeatures = new ArrayList<>();
    for (String feature : Osmformat.HeaderBlock.parseFrom(data).getRequiredFeaturesList()) {
      if (!supportedFeatures.contains(feature)) {
        unsupportedFeatures.add(feature);
      }
    }
    // We can't continue if there are any unsupported features. We wait
    // until now so that we can display all unsupported features instead of
    // just the first one we encounter.
    if (!unsupportedFeatures.isEmpty()) {
      throw new RuntimeException("PBF file contains unsupported features " + unsupportedFeatures);
    }
  }

  private Map<String, String> buildTags(List<Integer> keys, List<Integer> values, BPbfFieldDecoder fieldDecoder) {

    Iterator<Integer> keyIterator = keys.iterator();
    Iterator<Integer> valueIterator = values.iterator();
    if (keyIterator.hasNext()) {
      Map<String, String> tags = new HashMap<>();
      while (keyIterator.hasNext()) {
        String key = fieldDecoder.decodeString(keyIterator.next());
        String value = fieldDecoder.decodeString(valueIterator.next());
        tags.put(key, value);
      }
      return tags;
    }
    return null;
  }

  private void processNodes(List<Osmformat.Node> nodes, BPbfFieldDecoder fieldDecoder) throws IOException {
    for (Osmformat.Node node : nodes) {
      Map<String, String> tags = buildTags(node.getKeysList(), node.getValsList(), fieldDecoder);

      NodeData n = new NodeData(node.getId(),
        fieldDecoder.decodeLongitude(node.getLat()), fieldDecoder.decodeLatitude(node.getLon()));
      n.setTags(tags);


      parser.addNode(n);
    }
  }

  private void processNodes(Osmformat.DenseNodes nodes, BPbfFieldDecoder fieldDecoder) throws IOException {
    List<Long> idList = nodes.getIdList();
    List<Long> latList = nodes.getLatList();
    List<Long> lonList = nodes.getLonList();

    Iterator<Integer> keysValuesIterator = nodes.getKeysValsList().iterator();

    long nodeId = 0;
    long latitude = 0;
    long longitude = 0;

    for (int i = 0; i < idList.size(); i++) {
      // Delta decode node fields.
      nodeId += idList.get(i);
      latitude += latList.get(i);
      longitude += lonList.get(i);

      // Build the tags. The key and value string indexes are sequential
      // in the same PBF array. Each set of tags is delimited by an index
      // with a value of 0.
      Map<String, String> tags = null;
      while (keysValuesIterator.hasNext()) {
        int keyIndex = keysValuesIterator.next();
        if (keyIndex == 0) {
          break;
        }
        int valueIndex = keysValuesIterator.next();

        if (tags == null) {
          tags = new HashMap<>();
        }

        tags.put(fieldDecoder.decodeString(keyIndex), fieldDecoder.decodeString(valueIndex));
      }

      NodeData n = new NodeData(nodeId,
        ((double) longitude) / 10000000, ((double) latitude) / 10000000);
      n.setTags(tags);
      parser.addNode(n);
    }
  }

  private void processWays(List<Osmformat.Way> ways, BPbfFieldDecoder fieldDecoder) throws IOException {
    for (Osmformat.Way way : ways) {
      Map<String, String> tags = buildTags(way.getKeysList(), way.getValsList(), fieldDecoder);

      // Build up the list of way nodes for the way. The node ids are
      // delta encoded meaning that each id is stored as a delta against
      // the previous one.
      long nodeId = 0;
      LongList wayNodes = new LongList(16);
      for (long nodeIdOffset : way.getRefsList()) {
        nodeId += nodeIdOffset;
        wayNodes.add(nodeId);
      }
      WayData w = new WayData(way.getId(), wayNodes);
      w.setTags(tags);
      parser.addWay(w);
    }
  }

  private LongList fromWid;
  private LongList toWid;
  private LongList viaNid;

  private LongList addLong(LongList ll, long l) {
    if (ll == null) {
      ll = new LongList(1);
    }
    ll.add(l);
    return ll;
  }

  private LongList buildRelationMembers(
    List<Long> memberIds, List<Integer> memberRoles, List<Osmformat.Relation.MemberType> memberTypes,
    BPbfFieldDecoder fieldDecoder) {
    LongList wayIds = new LongList(16);

    fromWid = toWid = viaNid = null;

    Iterator<Long> memberIdIterator = memberIds.iterator();
    Iterator<Integer> memberRoleIterator = memberRoles.iterator();
    Iterator<Osmformat.Relation.MemberType> memberTypeIterator = memberTypes.iterator();

    // Build up the list of relation members for the way. The member ids are
    // delta encoded meaning that each id is stored as a delta against
    // the previous one.
    long refId = 0;
    while (memberIdIterator.hasNext()) {
      Osmformat.Relation.MemberType memberType = memberTypeIterator.next();
      refId += memberIdIterator.next();

      String role = fieldDecoder.decodeString(memberRoleIterator.next());

      if (memberType == Osmformat.Relation.MemberType.WAY) { // currently just waymembers
        wayIds.add(refId);
        if ("from".equals(role)) fromWid = addLong(fromWid, refId);
        if ("to".equals(role)) toWid = addLong(toWid, refId);
      }
      if (memberType == Osmformat.Relation.MemberType.NODE) { // currently just waymembers
        if ("via".equals(role)) viaNid = addLong(viaNid, refId);
      }
    }
    return wayIds;
  }

  private void processRelations(List<Osmformat.Relation> relations, BPbfFieldDecoder fieldDecoder) {
    for (Osmformat.Relation relation : relations) {
      Map<String, String> tags = buildTags(relation.getKeysList(), relation.getValsList(), fieldDecoder);

      LongList wayIds = buildRelationMembers(relation.getMemidsList(), relation.getRolesSidList(),
        relation.getTypesList(), fieldDecoder);

      parser.addRelation(relation.getId(), tags, wayIds, fromWid, toWid, viaNid);
    }
  }

  private void processOsmPrimitives(byte[] data) throws Exception {
    Osmformat.PrimitiveBlock block = Osmformat.PrimitiveBlock.parseFrom(data);
    BPbfFieldDecoder fieldDecoder = new BPbfFieldDecoder(block);

    for (Osmformat.PrimitiveGroup primitiveGroup : block.getPrimitivegroupList()) {
      processNodes(primitiveGroup.getDense(), fieldDecoder);
      processNodes(primitiveGroup.getNodesList(), fieldDecoder);
      processWays(primitiveGroup.getWaysList(), fieldDecoder);
      processRelations(primitiveGroup.getRelationsList(), fieldDecoder);
    }
  }

  private static class BPbfFieldDecoder {
    private static final double COORDINATE_SCALING_FACTOR = 0.000000001;
    private final String[] strings;
    private final int coordGranularity;
    private final long coordLatitudeOffset;
    private final long coordLongitudeOffset;

    /**
     * @param primitiveBlock The primitive block containing the fields to be decoded.
     */
    public BPbfFieldDecoder(Osmformat.PrimitiveBlock primitiveBlock) {
      this.coordGranularity = primitiveBlock.getGranularity();
      this.coordLatitudeOffset = primitiveBlock.getLatOffset();
      this.coordLongitudeOffset = primitiveBlock.getLonOffset();

      Osmformat.StringTable stringTable = primitiveBlock.getStringtable();
      strings = new String[stringTable.getSCount()];
      for (int i = 0; i < strings.length; i++) {
        strings[i] = stringTable.getS(i).toStringUtf8();
      }
    }

    /**
     * Decodes a raw latitude value into degrees.
     *
     * @param rawLatitude The PBF encoded value.
     * @return The latitude in degrees.
     */
    public double decodeLatitude(long rawLatitude) {
      return COORDINATE_SCALING_FACTOR * (coordLatitudeOffset + (coordGranularity * rawLatitude));
    }

    /**
     * Decodes a raw longitude value into degrees.
     *
     * @param rawLongitude The PBF encoded value.
     * @return The longitude in degrees.
     */
    public double decodeLongitude(long rawLongitude) {
      return COORDINATE_SCALING_FACTOR * (coordLongitudeOffset + (coordGranularity * rawLongitude));
    }

    /**
     * Decodes a raw string into a String.
     *
     * @param rawString The PBF encoding string.
     * @return The string as a String.
     */
    public String decodeString(int rawString) {
      return strings[rawString];
    }
  }
}
