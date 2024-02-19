package btools.mapcreator;

import java.io.EOFException;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.statcoding.BitInputStream;
import btools.util.CompactLongSet;
import btools.util.FrozenLongSet;

/**
 * RelationMerger does 1 step in map processing:
 * <p>
 * - enrich ways with relation information
 *
 * @author ab
 */
public class RelationMerger implements ItemListener {
  private Map<String, CompactLongSet> routesets;
  private CompactLongSet routesetall;
  private BExpressionContextWay expctxReport;
  private BExpressionContextWay expctxCheck;

  public RelationMerger(File tmpDir, File lookupFile, File reportProfile, File checkProfile) throws Exception {
    // read lookup + profile for relation access-check
    BExpressionMetaData metaReport = new BExpressionMetaData();
    expctxReport = new BExpressionContextWay(metaReport);
    metaReport.readMetaData(lookupFile);

    BExpressionMetaData metaCheck = new BExpressionMetaData();
    expctxCheck = new BExpressionContextWay(metaCheck);
    metaCheck.readMetaData(lookupFile);

    expctxReport.parseFile(reportProfile, "global");
    expctxCheck.parseFile(checkProfile, "global");

    // *** read the relation file into sets for each processed tag
    routesets = new HashMap<>();
    routesetall = new CompactLongSet();

    new ItemIterator(this).processFile(new File( tmpDir, "relations.dat") );

    // freeze the routesets for faster access
    for (String key : routesets.keySet()) {
      CompactLongSet routeset = new FrozenLongSet(routesets.get(key));
      routesets.put(key, routeset);
      System.out.println("marked " + routeset.size() + " routes for key: " + key);
    }
  }

  @Override
  public void nextRelation(RelationData r) {
    int value = "proposed".equals(r.state) ? 3 : 2; // 2=yes, 3=proposed

    String tagname = "route_" + r.route + "_" + r.network;

    CompactLongSet routeset = null;
    if (expctxCheck.getLookupNameIdx(tagname) >= 0) {
      String key = tagname + "_" + value;
      routeset = routesets.get(key);
      if (routeset == null) {
        routeset = new CompactLongSet();
        routesets.put(key, routeset);
      }
    }

    int size = r.ways.size();
    for (int i = 0; i < size; i++) {
      long wid = r.ways.get(i);
      // expctxStat.addLookupValue( tagname, "yes", null );
      if (routeset != null && !routeset.contains(wid)) {
        routeset.add(wid);
        routesetall.add(wid);
      }
    }
  }

  public void addRelationDataToWay(WayData data) {
    // propagate the route-bits
    if (routesetall.contains(data.wid)) {
      boolean ok = true;
      // check access and log a warning for conflicts
      expctxReport.evaluate(false, data.description);
      boolean warn = expctxReport.getCostfactor() >= 10000.;
      if (warn) {
        expctxCheck.evaluate(false, data.description);
        ok = expctxCheck.getCostfactor() < 10000.;

        System.out.println("** relation access conflict for wid = " + data.wid + " tags:" + expctxReport.getKeyValueDescription(false, data.description) + " (ok=" + ok + ")");
      }

      if (ok) {
        expctxReport.decode(data.description);
        for (String key : routesets.keySet()) {
          CompactLongSet routeset = routesets.get(key);
          if (routeset.contains(data.wid)) {
            int sepIdx = key.lastIndexOf('_');
            String tagname = key.substring(0, sepIdx);
            int val = Integer.parseInt(key.substring(sepIdx + 1));
            expctxReport.addSmallestLookupValue(tagname, val);
          }
        }
        data.description = expctxReport.encode();
      }
    }
  }

}
