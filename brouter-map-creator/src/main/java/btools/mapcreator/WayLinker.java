package btools.mapcreator;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.mapaccess.*;
import btools.statcoding.BitInputStream;
import btools.util.*;

/**
 * WayLinker finally puts the pieces together to create the rd5 files. For each
 * 5*5 tile, the corresponding files for nodes, turn-restictions and ways are read,
 * plus the (global) bordernodes file, and an rd5 datafile is created
 */
public class WayLinker extends ItemCutter implements ItemListener, Runnable {
  private File tmpDir;
  private String dataTilesSuffix;
  private boolean readingBorder;
  private CompactLongMap<OsmNode> nodesMap;
  private CompactLongSet borderSet;
  private BExpressionContextWay expCtxWay;
  private ByteArrayUnifier abUnifier;
  private int minLon;
  private int minLat;
  private boolean isSlave;
  private ThreadController tc;

  public static final class ThreadController {
    long maxFileSize = 0L;
    long currentSlaveSize;
    long currentMasterSize = 2000000000L;

    Set<String> filesProcessed = new HashSet<>();

    synchronized boolean registerFileProcessed(String name) {
      return filesProcessed.add( name );
    }

    synchronized boolean setCurrentMasterSize(long size) {
      try {
        if (size <= currentSlaveSize) {
          maxFileSize = Long.MAX_VALUE;
          return false;
        }
        currentMasterSize = size;
        if (maxFileSize == 0L) {
          maxFileSize = size;
        }
        return true;
      } finally {
        notify();
      }
    }

    synchronized boolean setCurrentSlaveSize(long size) {
      if (size > currentMasterSize) {
        return false;
      }

      while (size + currentMasterSize + 50000000L > maxFileSize) {
        System.out.println("****** slave thread waiting for permission to process file of size " + size
          + " currentMaster=" + currentMasterSize + " maxFileSize=" + maxFileSize);
        try {
          wait(10000);
        } catch( InterruptedException ie ) { // ignore
        }
      }
      currentSlaveSize = size;
      return true;
    }
  }


  private void reset() {
    minLon = -1;
    minLat = -1;
    nodesMap = new CompactLongMap<>();
    borderSet = new CompactLongSet();
  }

  public static void main(String[] args) throws Exception {
    System.out.println("*** WayLinker: Format a region of an OSM map for routing");
    if (args.length != 4) {
      System.out
        .println("usage: java WayLinker <tmpDir> <lookup-file> <profile-file> <data-tiles-suffix> ");
      return;
    }

    new WayLinker(new File(args[0])).process(new File(args[1]), new File(args[2]), args[3]);

    System.out.println("dumping bad TRs");
    RestrictionData.dumpBadTRs();
  }

  public WayLinker(File tmpDir) {
    super(new File( tmpDir, "segments"));
    this.tmpDir = tmpDir;
  }

  public void process(File lookupFile, File profileFile, String dataTilesSuffix) throws Exception {
    WayLinker master = new WayLinker(tmpDir);
    WayLinker slave = new WayLinker(tmpDir);
    slave.isSlave = true;
    master.isSlave = false;

    ThreadController tc = new ThreadController();
    slave.tc = tc;
    master.tc = tc;

    master._process(tmpDir, lookupFile, profileFile, dataTilesSuffix);
    slave._process(tmpDir, lookupFile, profileFile, dataTilesSuffix);

    Thread m = new Thread(master);
    Thread s = new Thread(slave);
    m.start();
    s.start();
    m.join();
    s.join();
  }

  private void _process(File tmpDir, File lookupFile, File profileFile, String dataTilesSuffix) throws Exception {
    this.dataTilesSuffix = dataTilesSuffix;

    BExpressionMetaData meta = new BExpressionMetaData();

    // read lookup + profile for lookup-version + access-filter
    expCtxWay = new BExpressionContextWay(meta);
    meta.readMetaData(lookupFile);

    expCtxWay.parseFile(profileFile, "global");

    abUnifier = new ByteArrayUnifier(16384, false);
  }

  @Override
  public void run() {
    try {
      // then process all segments
      new ItemIterator(this).processDir(new File( tmpDir, "ways55"), ".wt5", !isSlave);
    } catch (Exception e) {
      System.out.println("******* thread (slave=" + isSlave + ") got Exception: " + e);
      throw new RuntimeException(e);
    } finally {
      if (!isSlave) {
        tc.setCurrentMasterSize(0L);
      }
    }
  }

  @Override
  public boolean itemFileStart(File wayfile) throws IOException {

    // master/slave logic:
    // total memory size should stay below a maximum
    // and no file should be processed twice
    long fileSize = wayfile.length();

    System.out.println("**** wayFileStart() for isSlave=" + isSlave + " size=" + fileSize);

    if (isSlave) {
      if (!tc.setCurrentSlaveSize(fileSize)) {
        return false;
      }
    } else {
      if (!tc.setCurrentMasterSize(fileSize)) {
        return false;
      }
    }
    if ( !tc.registerFileProcessed( wayfile.getName()) ) {
      return false;
    }

    reset();

    // read the border file
    readingBorder = true;
    File borderNodeFile = new File( tmpDir, "bordernodes.dat");
    new ItemIterator(this, false).processFile(borderNodeFile);
    borderSet = new FrozenLongSet(borderSet);

    // read this tile's nodes
    readingBorder = false;
    File nodeFile = fileFromTemplate(wayfile, new File( tmpDir, "unodes55"), "u5d");
    new ItemIterator(this, true).processFile(nodeFile);

    // freeze the nodes-map (=faster access, less memory)
    borderSet = null;
    nodesMap = new FrozenLongMap<>(nodesMap);

    // read turn restrictions and attach to nodes in nodesMap
    File restrictionFile = fileFromTemplate(wayfile, new File(tmpDir, "restrictions55"), "rt5");
    new ItemIterator(this).processFile(restrictionFile);

    return true;
  }

  @Override
  public void nextNode(NodeData data) {
    OsmNode n = new OsmNode();
    n.iLon = data.iLon;
    n.iLat = data.iLat;
    n.sElev = data.sElev;
    n.nodeDescription = data.description;

    if (readingBorder || (!borderSet.contains(data.nid))) {
      nodesMap.fastPut(data.nid, n);
    }

    if (readingBorder) {
      n.setBits( OsmNode.BORDER_BIT );
      borderSet.fastAdd(data.nid);
      return;
    }

    // remember the segment coords
    int min_lon = (n.iLon / 5000000) * 5000000;
    int min_lat = (n.iLat / 5000000) * 5000000;
    if (minLon == -1)
      minLon = min_lon;
    if (minLat == -1)
      minLat = min_lat;
    if (minLat != min_lat || minLon != min_lon)
      throw new IllegalArgumentException("inconsistent node: " + n.iLon + " " + n.iLat);
  }

  @Override
  public void nextRestriction(RestrictionData res) {
    OsmNode n = nodesMap.get(res.viaNid);
    if (n != null) {
      res.viaLon = n.iLon;
      res.viaLat = n.iLat;
      n.addTurnRestriction(res);
    }
  }

  // check if one of the nodes has a turn-restriction with
  // the current way as from or to member.
  // It seems to be required, that each member of a turn-restriction
  // starts or ends at it's via node. However, we allow
  // ways not ending at the via node, and in this case we take
  // the leg according to the mapped direction
  private void checkRestriction(OsmNode n1, OsmNode n2, WayData w) {
    checkRestriction(n1, n2, w, true);
    checkRestriction(n2, n1, w, false);
  }

  private void checkRestriction(OsmNode n1, OsmNode n2, WayData w, boolean checkFrom) {
    TurnRestriction tr = n2.firstRestriction;
    while (tr != null) {
      RestrictionData r = (RestrictionData)tr;
      if (r.fromWid == w.wid) {
        if (r.fromLon == 0 || checkFrom) {
          r.fromLon = n1.iLon;
          r.fromLat = n1.iLat;
          n1.setBits(OsmNode.TR_TARGET_BIT);
          if (isInnerNode(n2, w)) {
            r.badWayMatch = true;
          }
        }
      }
      if (r.toWid == w.wid) {
        if (r.toLon == 0 || !checkFrom) {
          r.toLon = n1.iLon;
          r.toLat = n1.iLat;
          n1.setBits(OsmNode.TR_TARGET_BIT);
          if (isInnerNode(n2, w)) {
            r.badWayMatch = true;
          }
        }
      }
      tr = tr.next;
    }
  }

  private boolean isInnerNode(OsmNode n, WayData w) {
    return n != nodesMap.get(w.nodes.get(0)) && n != nodesMap.get(w.nodes.get(w.nodes.size() - 1));
  }

  @Override
  public void nextWay(WayData way) {
    byte[] wayDescription = abUnifier.unify(way.description);

    // filter according to profile
    expCtxWay.evaluate(false, wayDescription);
    boolean ok = expCtxWay.getCostfactor() < 10000.;
    expCtxWay.evaluate(true, wayDescription);
    ok |= expCtxWay.getCostfactor() < 10000.;
    if (!ok)
      return;

    byte wayBits = 0;
    expCtxWay.decode(wayDescription);
    if (!expCtxWay.getBooleanLookupValue("bridge"))
      wayBits |= OsmNode.NO_BRIDGE_BIT;
    if (!expCtxWay.getBooleanLookupValue("tunnel"))
      wayBits |= OsmNode.NO_TUNNEL_BIT;

    OsmNode n2 = null;
    for (int i = 0; i < way.nodes.size(); i++) {
      long nid = way.nodes.get(i);
      OsmNode n1 = n2;
      n2 = nodesMap.get(nid);

      if (n1 != null && n2 != null && n1 != n2) {
        checkRestriction(n1, n2, way);
        n1.createLink(wayDescription,n2);
      }
      if (n2 != null) {
        n2.setBits(wayBits);
      }
    }
  }

  @Override
  public void itemFileEnd(File wayfile) throws IOException {

    // strip down the nodes-map to it's value-list
    List<OsmNode> nodesList = ((FrozenLongMap)nodesMap).getValueList();
    nodesMap = null;

    // do Douglas Peuker elimination of too dense transfer nodes
    DPFilter.doDPFilter(nodesList);

    long turnRestrictionsTotal = 0;
    long turnRestrictionsValid = 0;

    // filter out irrelevant nodes (also those that where dropped in DP-elimination)
    nodesList = filterNodes( nodesList );

    // filter-out invalid TRs
    for (OsmNode n : nodesList) {
      TurnRestriction rd = n.firstRestriction;
      n.firstRestriction = null;
      while (rd != null) {
        turnRestrictionsTotal++;
        TurnRestriction tr = ((RestrictionData) rd).validate();
        rd = rd.next;
        if (tr != null) {
          n.addTurnRestriction(tr);
          turnRestrictionsValid++;
        }
      }
    }
    System.out.println( "TRs (total/valid): " + turnRestrictionsTotal + "/" + turnRestrictionsValid );


    new TwoNodeLoopResolver( nodesList ).resolve();
    nodesList = filterNodes( nodesList ); // filter again

    // and write the filtered nodes to a file
    File outFile = fileFromTemplate(wayfile, outTileDir, dataTilesSuffix);
    new OsmFile( outFile, minLon, minLat, new byte[10*1024*1024]).createWithNodes( nodesList );
  }

  private List<OsmNode> filterNodes( List<OsmNode> nodes ) {
    int maxLon = minLon + 5000000;
    int maxLat = minLat + 5000000;
    ArrayList<OsmNode> filtered = new ArrayList<>(nodes.size());
    for (OsmNode n : nodes) {
      if (n.linkCount() == 0 || n.iLon < minLon || n.iLon >= maxLon || n.iLat < minLat || n.iLat >= maxLat) {
        continue;
      }
      filtered.add(n);
    }
    filtered.trimToSize();
    return filtered;
  }
}
