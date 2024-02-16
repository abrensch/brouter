package btools.mapcreator;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.mapaccess.*;
import btools.util.*;

/**
 * WayLinker finally puts the pieces together to create the rd5 files. For each
 * 5*5 tile, the corresponding nodefile and wayfile is read, plus the (global)
 * bordernodes file, and an rd5 is written
 *
 * @author ab
 */
public class WayLinker extends MapCreatorBase implements NodeListener, WayListener, Runnable {
  private File nodeTilesIn;
  private File wayTilesIn;
  private File dataTilesOut;
  private File borderFileIn;

  private String dataTilesSuffix;

  private boolean readingBorder;

  private CompactLongMap<OsmNode> nodesMap;
  private List<OsmNode> nodesList;
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

    synchronized boolean setCurrentSlaveSize(long size) throws Exception {
      if (size > currentMasterSize) {
        return false;
      }

      while (size + currentMasterSize + 50000000L > maxFileSize) {
        System.out.println("****** slave thread waiting for permission to process file of size " + size
          + " currentMaster=" + currentMasterSize + " maxFileSize=" + maxFileSize);
        wait(10000);
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

    new WayLinker().process(new File(args[0]), new File(args[1]), new File(args[2]), args[3]);

    System.out.println("dumping bad TRs");
    RestrictionData.dumpBadTRs();
  }

  public void process(File tmpDir, File lookupFile, File profileFile, String dataTilesSuffix) throws Exception {
    WayLinker master = new WayLinker();
    WayLinker slave = new WayLinker();
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
    this.nodeTilesIn = new File( tmpDir, "unodes55");
    this.wayTilesIn = new File( tmpDir, "ways55");
    this.dataTilesOut = new File( tmpDir, "segments");
    this.borderFileIn = new File( tmpDir, "bordernodes.dat");;
    this.dataTilesSuffix = dataTilesSuffix;

    if ( !dataTilesOut.exists() && !dataTilesOut.mkdir() ) {
      throw new RuntimeException( "directory " + dataTilesOut + " cannot be created" );
    }

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
      new WayIterator(!isSlave, this).processDir(wayTilesIn, ".wt5");
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
  public boolean wayFileStart(File wayfile) throws Exception {

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

    // process corresponding node-file, if any
    File nodeFile = fileFromTemplate(wayfile, nodeTilesIn, "u5d");
    if (nodeFile.exists()) {
      reset();

      // read the border file
      readingBorder = true;
      new NodeIterator(this, false).processFile(borderFileIn);
      borderSet = new FrozenLongSet(borderSet);

      // read this tile's nodes
      readingBorder = false;
      new NodeIterator(this, true).processFile(nodeFile);

      // freeze the nodes-map
      FrozenLongMap<OsmNode> nodesMapFrozen = new FrozenLongMap<>(nodesMap);
      nodesMap = nodesMapFrozen;

      File restrictionFile = fileFromTemplate(wayfile, new File(nodeTilesIn.getParentFile(), "restrictions55"), "rt5");
      // read restrictions for nodes in nodesMap
      if (restrictionFile.exists()) {
        DataInputStream di = new DataInputStream(new BufferedInputStream(new FileInputStream(restrictionFile)));
        int ntr = 0;
        try {
          for (; ; ) {
            RestrictionData res = new RestrictionData(di);
            OsmNode n = nodesMap.get(res.viaNid);
            if (n != null) {
              res.viaLon = n.iLon;
              res.viaLat = n.iLat;
              n.addTurnRestriction(res);
              ntr++;
            }
          }
        } catch (EOFException eof) {
          di.close();
        }
        System.out.println("read " + ntr + " turn-restrictions");
      }

      nodesList = nodesMapFrozen.getValueList();
    }
    return true;
  }

  @Override
  public void nextNode(NodeData data) throws Exception {
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
          if (isInnerNode(n2, w)) {
            r.badWayMatch = true;
          }
        }
      }
      if (r.toWid == w.wid) {
        if (r.toLon == 0 || !checkFrom) {
          r.toLon = n1.iLon;
          r.toLat = n1.iLat;
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
  public void nextWay(WayData way) throws Exception {
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
  public void wayFileEnd(File wayfile) throws Exception {

    nodesMap = null;
    borderSet = null;


    int maxLon = minLon + 5000000;
    int maxLat = minLat + 5000000;

    // do Douglas Peuker elimination of too dense transfer nodes
    DPFilter.doDPFilter(nodesList);

    // filter out irrelevant nodes (also those that where dropped in DP-elimination)
    ArrayList<OsmNode> filteredNodesList = new ArrayList<>(nodesList.size());
    for (OsmNode n : nodesList) {
      if (n.linkCount() == 0)
        continue;
      if (n.iLon < minLon || n.iLon >= maxLon || n.iLat < minLat || n.iLat >= maxLat)
        continue;
      filteredNodesList.add(n);

      // filter-out invalid TRs
      TurnRestriction rd = n.firstRestriction;
      n.firstRestriction = null;
      while (rd != null) {
        TurnRestriction tr = ((RestrictionData) rd).validate();
        rd = rd.next;
        if (tr != null) {
          n.addTurnRestriction(tr);
        }
      }
    }
    nodesList = null;
    filteredNodesList.trimToSize();

    // and write the filtered nodes to a file
    File outFile = fileFromTemplate(wayfile, dataTilesOut, dataTilesSuffix);
    new OsmFile( outFile, minLon, minLat, new byte[10*1024*1024]).createWithNodes( filteredNodesList );
  }
}
