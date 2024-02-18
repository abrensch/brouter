/**
 * This program
 * - reads an *.osm from stdin
 * - writes 45*30 degree node tiles + a way file + a rel file
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.statcoding.BitOutputStream;
import btools.util.DenseLongMap;
import btools.util.TinyDenseLongMap;

public class OsmCutter implements ItemListener {
  private long recordCnt;
  private long nodesParsed;
  private long waysParsed;
  private long relationsParsed;

  private BitOutputStream relationsBos;

  public WayCutter wayCutter;
  public NodeCutter nodeCutter;
  public RestrictionCutter restrictionCutter;
  public DenseLongMap nodeFilter;

  private DatabasePseudoTagProvider dbPseudoTagProvider;

  private BExpressionContextWay expCtxWay;
  private BExpressionContextNode expCtxNode;

  public static void main(String[] args) throws Exception {
    System.out.println("*** OsmCutter: cut an osm map in node-tiles + way-tiles");
    if (args.length != 6 && args.length != 7) {
      String common = "java OsmCutter <lookup-file> <tmp-dir> <filter-profile> <report-profile> <check-profile> <map-file> [db-tag-filename]";
      System.out.println("usage: " + common);
      return;
    }

    doCut(
      new File(args[0])
      , new File(args[1])
      , new File(args[2])
      , new File(args[3])
      , new File(args[4])
      , new File(args[5])
      , args.length > 6 ? args[6] : null
    );
  }

  public static void doCut(File lookupFile, File tmpDir, File profileAll, File profileReport, File profileCheck, File mapFile, String dbTagFilename) throws Exception {

    // first step: parse and cut to 45*30 degree tiles
    DenseLongMap nodeFilter = new OsmCutter().process(lookupFile, tmpDir, profileAll, mapFile, dbTagFilename);

    // second step: cut to 5*5 degree tiles
    new WayCutter5(tmpDir).process(nodeFilter, lookupFile, profileReport, profileCheck);
  }

  public DenseLongMap process(File lookupFile, File tmpDir, File profileFile, File mapFile, String dbTagFilename ) throws Exception {

    if (dbTagFilename != null) {
      setDbTagFilename(dbTagFilename);
    }
    nodeCutter = new NodeCutter(tmpDir);
    wayCutter = new WayCutter(tmpDir,nodeCutter);
    restrictionCutter = new RestrictionCutter(tmpDir,nodeCutter);
    nodeFilter = Boolean.getBoolean("useDenseMaps") ? new DenseLongMap(512) : new TinyDenseLongMap();

    if (!lookupFile.exists()) {
      throw new IllegalArgumentException("lookup-file: " + lookupFile + " does not exist");
    }

    BExpressionMetaData meta = new BExpressionMetaData();

    expCtxWay = new BExpressionContextWay(meta);
    expCtxNode = new BExpressionContextNode(meta);
    meta.readMetaData(lookupFile);
    expCtxWay.parseFile(profileFile, "global");
    relationsBos = new BitOutputStream(new BufferedOutputStream(new FileOutputStream(new File( tmpDir, "relations.dat" ))));

    // parse the osm map to create intermediate files for nodes, ways, relations and restrictions
    new OsmParser().readMap(mapFile, this);

    // close all files
    nodeCutter.closeTileOutStreams();
    relationsBos.encodeVarBytes(0L);
    relationsBos.close();
    wayCutter.closeTileOutStreams();
    restrictionCutter.closeTileOutStreams();

//    System.out.println( "-------- way-statistics -------- " );
//    _expctxWayStat.dumpStatistics();
//    System.out.println( "-------- node-statistics -------- " );
//    _expctxNodeStat.dumpStatistics();

    System.out.println(statsLine());

    return nodeFilter;
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
    nodeCutter.nextNode(n);
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

    int nNodes = w.nodes.size();
    for (int i = 0; i < nNodes; i++) {
      nodeFilter.put(w.nodes.get(i), 0);
    }
  }

  @Override
  public void nextRelation(RelationData r) throws IOException {
    relationsParsed++;
    checkStats();

    r.route = r.getTag("route");
    // filter out non-route relations
    if (r.route == null) {
      return;
    }

    r.network = r.getTag("network");
    if (r.network == null) r.network = "";
    r.state = r.getTag("state");
    if (r.state == null) r.state = "";
    r.writeTo(relationsBos);
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
}
