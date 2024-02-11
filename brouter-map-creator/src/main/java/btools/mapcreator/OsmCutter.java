/**
 * This program
 * - reads an *.osm from stdin
 * - writes 45*30 degree node tiles + a way file + a rel file
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;

public class OsmCutter extends MapCreatorBase implements NodeListener, WayListener, RelationListener {
  private long recordCnt;
  private long nodesParsed;
  private long waysParsed;
  private long relationsParsed;

  private DataOutputStream relationsDos;

  public WayCutter wayCutter;
  public RestrictionCutter restrictionCutter;
  public NodeFilter nodeFilter;

  private DatabasePseudoTagProvider dbPseudoTagProvider;

  private BExpressionContextWay expCtxWay;
  private BExpressionContextNode expCtxNode;

  public void process(File lookupFile, File nodeTileDir, File relFile, File profileFile, File mapFile) throws Exception {
    if (!lookupFile.exists()) {
      throw new IllegalArgumentException("lookup-file: " + lookupFile + " does not exist");
    }

    BExpressionMetaData meta = new BExpressionMetaData();

    expCtxWay = new BExpressionContextWay(meta);
    expCtxNode = new BExpressionContextNode(meta);
    meta.readMetaData(lookupFile);
    expCtxWay.parseFile(profileFile, "global");

    this.outTileDir = nodeTileDir;
    if (!nodeTileDir.isDirectory())
      throw new RuntimeException("out tile directory " + nodeTileDir + " does not exist");

    relationsDos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(relFile)));

    // read the osm map into memory
    long t0 = System.currentTimeMillis();
    new OsmParser().readMap(mapFile, this, this, this);
    long t1 = System.currentTimeMillis();

    System.out.println("parsing time (ms) =" + (t1 - t0));

    // close all files
    closeTileOutStreams();
    relationsDos.close();

//    System.out.println( "-------- way-statistics -------- " );
//    _expctxWayStat.dumpStatistics();
//    System.out.println( "-------- node-statistics -------- " );
//    _expctxNodeStat.dumpStatistics();

    System.out.println(statsLine());
  }

  private void checkStats() {
    if ((++recordCnt % 100000) == 0) System.out.println(statsLine());
  }

  private String statsLine() {
    return "records read: " + recordCnt + " nodes=" + nodesParsed + " ways=" + waysParsed + " rels=" + relationsParsed;
  }

  public void setDbTagFilename(String filename) {
    dbPseudoTagProvider = new DatabasePseudoTagProvider(filename);
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    nodesParsed++;
    checkStats();

    if (n.getTagsOrNull() != null) {
      int[] lookupData = expCtxNode.createNewLookupData();
      for (Map.Entry<String, String> e : n.getTagsOrNull().entrySet()) {
        expCtxNode.addLookupValue(e.getKey(), e.getValue(), lookupData);
        // _expctxNodeStat.addLookupValue( key, value, null );
      }
      n.description = expCtxNode.encode(lookupData);
    }
    // write node to file
    int tileIndex = getTileIndex(n.iLon, n.iLat);
    if (tileIndex >= 0) {
      n.writeTo(getOutStreamForTile(tileIndex));
      if (wayCutter != null) {
        wayCutter.nextNode(n);
      }
    }
  }

  private void generatePseudoTags(Map<String, String> map) {
    // add pseudo.tags for concrete:lanes and concrete:plates

    String concrete = null;
    for (Map.Entry<String, String> e : map.entrySet()) {
      String key = e.getKey();

      if ("concrete".equals(key)) {
        return;
      }
      if ("surface".equals(key)) {
        String value = e.getValue();
        if (value.startsWith("concrete:")) {
          concrete = value.substring("concrete:".length());
        }
      }
    }
    if (concrete != null) {
      map.put("concrete", concrete);
    }
  }


  @Override
  public void nextWay(WayData w) throws Exception {
    waysParsed++;
    checkStats();

    // encode tags
    if (w.getTagsOrNull() == null) return;

    if (dbPseudoTagProvider != null) {
      dbPseudoTagProvider.addTags(w.wid, w.getTagsOrNull());
    }

    generatePseudoTags(w.getTagsOrNull());

    int[] lookupData = expCtxWay.createNewLookupData();
    for (String key : w.getTagsOrNull().keySet()) {
      String value = w.getTag(key);
      expCtxWay.addLookupValue(key, value.replace(' ', '_'), lookupData);
    }
    w.description = expCtxWay.encode(lookupData);

    if (w.description == null) return;

    // filter according to profile
    expCtxWay.evaluate(false, w.description);
    boolean ok = expCtxWay.getCostfactor() < 10000.;
    expCtxWay.evaluate(true, w.description);
    ok |= expCtxWay.getCostfactor() < 10000.;
    if (!ok) return;

    wayCutter.nextWay(w);
    nodeFilter.nextWay(w);
  }

  @Override
  public void nextRelation(RelationData r) throws IOException {
    relationsParsed++;
    checkStats();

    String route = r.getTag("route");
    // filter out non-route relations
    if (route == null) {
      return;
    }

    String network = r.getTag("network");
    if (network == null) network = "";
    String state = r.getTag("state");
    if (state == null) state = "";
    writeId(relationsDos, r.rid);
    relationsDos.writeUTF(route);
    relationsDos.writeUTF(network);
    relationsDos.writeUTF(state);
    for (int i = 0; i < r.ways.size(); i++) {
      long wid = r.ways.get(i);
      writeId(relationsDos, wid);
    }
    writeId(relationsDos, -1);
  }

  @Override
  public void nextRestriction(RelationData r, long fromWid, long toWid, long viaNid) throws Exception {
    String type = r.getTag("type");
    if (type == null || !"restriction".equals(type)) {
      return;
    }
    short exceptions = 0;
    String except = r.getTag("except");
    if (except != null) {
      exceptions |= toBit("bicycle", 0, except);
      exceptions |= toBit("motorcar", 1, except);
      exceptions |= toBit("agricultural", 2, except);
      exceptions |= toBit("forestry", 2, except);
      exceptions |= toBit("psv", 3, except);
      exceptions |= toBit("hgv", 4, except);
    }

    for (String restrictionKey : r.getTagsOrNull().keySet()) {
      if (!(restrictionKey.equals("restriction") || restrictionKey.startsWith("restriction:"))) {
        continue;
      }
      String restriction = r.getTag(restrictionKey);

      RestrictionData res = new RestrictionData();
      res.restrictionKey = restrictionKey;
      res.restriction = restriction;
      res.exceptions = exceptions;
      res.fromWid = fromWid;
      res.toWid = toWid;
      res.viaNid = viaNid;

      restrictionCutter.nextRestriction(res);
    }
  }

  private static short toBit(String tag, int bitpos, String s) {
    return (short) (s.indexOf(tag) < 0 ? 0 : 1 << bitpos);
  }

  private int getTileIndex(int ilon, int ilat) {
    int lon = ilon / 45000000;
    int lat = ilat / 30000000;
    if (lon < 0 || lon > 7 || lat < 0 || lat > 5) {
      System.out.println("warning: ignoring illegal pos: " + ilon + "," + ilat);
      return -1;
    }
    return lon * 6 + lat;
  }

  protected String getNameForTile(int tileIndex) {
    int lon = (tileIndex / 6) * 45 - 180;
    int lat = (tileIndex % 6) * 30 - 90;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    return slon + "_" + slat + ".ntl";
  }
}
