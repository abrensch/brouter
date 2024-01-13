package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.TreeMap;

import btools.codec.MicroCache;
import btools.codec.MicroCache2;
import btools.codec.StatCoderContext;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;
import btools.util.ByteArrayUnifier;
import btools.util.CompactLongMap;
import btools.util.CompactLongSet;
import btools.util.Crc32;
import btools.util.FrozenLongMap;
import btools.util.FrozenLongSet;
import btools.util.LazyArrayOfLists;

/**
 * WayLinker finally puts the pieces together to create the rd5 files. For each
 * 5*5 tile, the corresponding nodefile and wayfile is read, plus the (global)
 * bordernodes file, and an rd5 is written
 *
 * @author ab
 */
public class WayLinker extends MapCreatorBase implements Runnable {
  private File nodeTilesIn;
  private File wayTilesIn;
  private File dataTilesOut;
  private File borderFileIn;

  private String dataTilesSuffix;

  private boolean readingBorder;

  private CompactLongMap<OsmNodeP> nodesMap;
  private List<OsmNodeP> nodesList;
  private CompactLongSet borderSet;
  private short lookupVersion;
  private short lookupMinorVersion;

  private long creationTimeStamp;

  private BExpressionContextWay expCtxWay;

  private ByteArrayUnifier abUnifier;

  private int minLon;
  private int minLat;
  private final int divisor = 160;
  private final int cellSize = 5000000 / divisor;

  private boolean isSlave;
  private ThreadController tc;

  public static final class ThreadController {
    long maxFileSize = 0L;
    long currentSlaveSize;
    long currentMasterSize = 2000000000L;

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
      if (size >= currentMasterSize) {
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
    if (args.length != 8) {
      System.out
        .println("usage: java WayLinker <node-tiles-in> <way-tiles-in> <border-nodes> <lookup-file> <profile-file> <data-tiles-out> <data-tiles-suffix> ");
      return;
    }

    new WayLinker().process(new File(args[0]), new File(args[1]), new File(args[2]), new File(args[3]), new File(args[4]), new File(args[5]), args[6]);

    System.out.println("dumping bad TRs");
    RestrictionData.dumpBadTRs();
  }

  public void process(File nodeTilesIn, File wayTilesIn, File borderFileIn, File lookupFile, File profileFile, File dataTilesOut,
                      String dataTilesSuffix) throws Exception {
    WayLinker master = new WayLinker();
    WayLinker slave = new WayLinker();
    slave.isSlave = true;
    master.isSlave = false;

    ThreadController tc = new ThreadController();
    slave.tc = tc;
    master.tc = tc;

    master._process(nodeTilesIn, wayTilesIn, borderFileIn, lookupFile, profileFile, dataTilesOut, dataTilesSuffix);
    slave._process(nodeTilesIn, wayTilesIn, borderFileIn, lookupFile, profileFile, dataTilesOut, dataTilesSuffix);

    Thread m = new Thread(master);
    Thread s = new Thread(slave);
    m.start();
    s.start();
    m.join();
    s.join();
  }

  private void _process(File nodeTilesIn, File wayTilesIn, File borderFileIn, File lookupFile, File profileFile, File dataTilesOut,
                        String dataTilesSuffix) throws Exception {
    this.nodeTilesIn = nodeTilesIn;
    this.wayTilesIn = wayTilesIn;
    this.dataTilesOut = dataTilesOut;
    this.borderFileIn = borderFileIn;
    this.dataTilesSuffix = dataTilesSuffix;

    BExpressionMetaData meta = new BExpressionMetaData();

    // read lookup + profile for lookup-version + access-filter
    expCtxWay = new BExpressionContextWay(meta);
    meta.readMetaData(lookupFile);

    lookupVersion = meta.lookupVersion;
    lookupMinorVersion = meta.lookupMinorVersion;

    expCtxWay.parseFile(profileFile, "global");

    creationTimeStamp = System.currentTimeMillis();

    abUnifier = new ByteArrayUnifier(16384, false);
  }

  @Override
  public void run() {
    try {
      // then process all segments
      new WayIterator(this, true, !isSlave).processDir(wayTilesIn, ".wt5");
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
      FrozenLongMap<OsmNodeP> nodesMapFrozen = new FrozenLongMap<>(nodesMap);
      nodesMap = nodesMapFrozen;

      File restrictionFile = fileFromTemplate(wayfile, new File(nodeTilesIn.getParentFile(), "restrictions55"), "rt5");
      // read restrictions for nodes in nodesMap
      if (restrictionFile.exists()) {
        DataInputStream di = new DataInputStream(new BufferedInputStream(new FileInputStream(restrictionFile)));
        int ntr = 0;
        try {
          for (; ; ) {
            RestrictionData res = new RestrictionData(di);
            OsmNodeP n = nodesMap.get(res.viaNid);
            if (n != null) {
              if (!(n instanceof OsmNodePT)) {
                n = new OsmNodePT(n);
                nodesMap.put(res.viaNid, n);
              }
              OsmNodePT nt = (OsmNodePT) n;
              res.viaLon = nt.ilon;
              res.viaLat = nt.ilat;
              res.next = nt.firstRestriction;
              nt.firstRestriction = res;
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
    OsmNodeP n = data.description == null ? new OsmNodeP() : new OsmNodePT(data.description);
    n.ilon = data.ilon;
    n.ilat = data.ilat;
    n.selev = data.selev;

    if (readingBorder || (!borderSet.contains(data.nid))) {
      nodesMap.fastPut(data.nid, n);
    }

    if (readingBorder) {
      n.bits |= OsmNodeP.BORDER_BIT;
      borderSet.fastAdd(data.nid);
      return;
    }

    // remember the segment coords
    int min_lon = (n.ilon / 5000000) * 5000000;
    int min_lat = (n.ilat / 5000000) * 5000000;
    if (minLon == -1)
      minLon = min_lon;
    if (minLat == -1)
      minLat = min_lat;
    if (minLat != min_lat || minLon != min_lon)
      throw new IllegalArgumentException("inconsistent node: " + n.ilon + " " + n.ilat);
  }

  // check if one of the nodes has a turn-restriction with
  // the current way as from or to member.
  // It seems to be required, that each member of a turn-restriction
  // starts or ends at it's via node. However, we allow
  // ways not ending at the via node, and in this case we take
  // the leg according to the mapped direction
  private void checkRestriction(OsmNodeP n1, OsmNodeP n2, WayData w) {
    checkRestriction(n1, n2, w, true);
    checkRestriction(n2, n1, w, false);
  }

  private void checkRestriction(OsmNodeP n1, OsmNodeP n2, WayData w, boolean checkFrom) {
    RestrictionData r = n2.getFirstRestriction();
    while (r != null) {
      if (r.fromWid == w.wid) {
        if (r.fromLon == 0 || checkFrom) {
          r.fromLon = n1.ilon;
          r.fromLat = n1.ilat;
          n1.bits |= OsmNodeP.DP_SURVIVOR_BIT;
          if (isInnerNode(n2, w)) {
            r.badWayMatch = true;
          }
        }
      }
      if (r.toWid == w.wid) {
        if (r.toLon == 0 || !checkFrom) {
          r.toLon = n1.ilon;
          r.toLat = n1.ilat;
          n1.bits |= OsmNodeP.DP_SURVIVOR_BIT;
          if (isInnerNode(n2, w)) {
            r.badWayMatch = true;
          }
        }
      }
      r = r.next;
    }
  }

  private boolean isInnerNode(OsmNodeP n, WayData w) {
    return n != nodesMap.get(w.nodes.get(0)) && n != nodesMap.get(w.nodes.get(w.nodes.size() - 1));
  }

  @Override
  public void nextWay(WayData way) throws Exception {
    byte[] description = abUnifier.unify(way.description);

    // filter according to profile
    expCtxWay.evaluate(false, description);
    boolean ok = expCtxWay.getCostfactor() < 10000.;
    expCtxWay.evaluate(true, description);
    ok |= expCtxWay.getCostfactor() < 10000.;
    if (!ok)
      return;

    byte wayBits = 0;
    expCtxWay.decode(description);
    if (!expCtxWay.getBooleanLookupValue("bridge"))
      wayBits |= OsmNodeP.NO_BRIDGE_BIT;
    if (!expCtxWay.getBooleanLookupValue("tunnel"))
      wayBits |= OsmNodeP.NO_TUNNEL_BIT;

    OsmNodeP n1 = null;
    OsmNodeP n2 = null;
    for (int i = 0; i < way.nodes.size(); i++) {
      long nid = way.nodes.get(i);
      n1 = n2;
      n2 = nodesMap.get(nid);

      if (n1 != null && n2 != null && n1 != n2) {
        checkRestriction(n1, n2, way);

        OsmLinkP link = n2.createLink(n1);

        link.descriptionBitmap = description;

        if (n1.ilon / cellSize != n2.ilon / cellSize || n1.ilat / cellSize != n2.ilat / cellSize) {
          n2.incWayCount(); // force first node after cell-change to be a
          // network node
        }
      }
      if (n2 != null) {
        n2.bits |= wayBits;
        n2.incWayCount();
      }
    }
  }

  @Override
  public void wayFileEnd(File wayfile) throws Exception {
    int nCaches = divisor * divisor;
    int indexSize = nCaches * 4;

    nodesMap = null;
    borderSet = null;

    byte[] abBuf1 = new byte[10 * 1024 * 1024];
    byte[] abBuf2 = new byte[10 * 1024 * 1024];

    int maxLon = minLon + 5000000;
    int maxLat = minLat + 5000000;

    // cleanup duplicate targets
    for (OsmNodeP n : nodesList) {
      if (n == null || n.getFirstLink() == null || n.isTransferNode())
        continue;
      n.checkDuplicateTargets();
    }

    {
      // open the output file
      File outfile = fileFromTemplate(wayfile, dataTilesOut, dataTilesSuffix);
      DataOutputStream os = createOutStream(outfile);

      LazyArrayOfLists<OsmNodeP> subs = new LazyArrayOfLists<>(nCaches);
      byte[][] subByteArrays = new byte[nCaches][];
      for (OsmNodeP n : nodesList) {
        if (n == null || n.getFirstLink() == null || n.isTransferNode())
          continue;
        if (n.ilon < minLon || n.ilon >= maxLon || n.ilat < minLat || n.ilat >= maxLat)
          continue;
        int subLonIdx = (n.ilon - minLon) / cellSize;
        int subLatIdx = (n.ilat - minLat) / cellSize;
        int si = subLatIdx * divisor + subLonIdx;
        subs.getList(si).add(n);
      }
      nodesList = null;
      subs.trimAll();
      int[] posIdx = new int[nCaches];
      int pos = 0;

      for (int si = 0; si < nCaches; si++) {
        List<OsmNodeP> subList = subs.getList(si);
        int size = subList.size();
        if (size > 0) {
          OsmNodeP n0 = subList.get(0);
          int lonIdxDiv = n0.ilon / cellSize;
          int latIdxDiv = n0.ilat / cellSize;
          MicroCache mc = new MicroCache2(size, abBuf2, lonIdxDiv, latIdxDiv, cellSize);

          // sort via treemap
          TreeMap<Integer, OsmNodeP> sortedList = new TreeMap<>();
          for (OsmNodeP n : subList) {
            long longId = n.getIdFromPos();
            int shrinkId = mc.shrinkId(longId);
            if (mc.expandId(shrinkId) != longId) {
              throw new IllegalArgumentException("inconstistent shrinking: " + longId + "<->" + mc.expandId(shrinkId) );
            }
            sortedList.put(shrinkId, n);
          }

          for (OsmNodeP n : sortedList.values()) {
            n.writeNodeData(mc);
          }
          if (mc.getSize() > 0) {
            int len = mc.encodeMicroCache(abBuf1);
            byte[] subBytes = new byte[len];
            System.arraycopy(abBuf1, 0, subBytes, 0, len);
            pos += subBytes.length + 4; // reserve 4 bytes for crc
            subByteArrays[si] = subBytes;
          }
        }
        posIdx[si] = pos;
      }
      byte[] abSubIndex = compileIndex(posIdx);
      os.write(abSubIndex, 0, abSubIndex.length);
      for (int si = 0; si < nCaches; si++) {
        byte[] ab = subByteArrays[si];
        if (ab != null) {
          os.write(ab);
          os.writeInt(Crc32.crc(ab, 0, ab.length));
        }
      }
      os.close();
    }
    System.out.println("**** codec stats: *******\n" + StatCoderContext.getBitReport());
  }

  private byte[] compileIndex(int[] posIdx) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    for (int pos : posIdx) {
      dos.writeInt(pos);
    }
    dos.close();
    return bos.toByteArray();
  }
}
