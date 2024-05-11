package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import btools.codec.DataBuffers;
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
  private byte elevationType;

  private BExpressionContextWay expctxWay;

  private ByteArrayUnifier abUnifier;

  private int minLon;
  private int minLat;

  private int microCacheEncoding = 2;
  private int divisor = 32;
  private int cellsize = 1000000 / divisor;

  private boolean skipEncodingCheck;

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
        .println("usage: java WayLinker <node-tiles-in> <way-tiles-in> <bordernodes> <restrictions> <lookup-file> <profile-file> <data-tiles-out> <data-tiles-suffix> ");
      return;
    }

    new WayLinker().process(new File(args[0]), new File(args[1]), new File(args[2]), new File(args[3]), new File(args[4]), new File(args[5]), new File(
      args[6]), args[7]);

    System.out.println("dumping bad TRs");
    RestrictionData.dumpBadTRs();
  }

  public void process(File nodeTilesIn, File wayTilesIn, File borderFileIn, File restrictionsFileIn, File lookupFile, File profileFile, File dataTilesOut,
                      String dataTilesSuffix) throws Exception {
    WayLinker master = new WayLinker();
    WayLinker slave = new WayLinker();
    slave.isSlave = true;
    master.isSlave = false;

    ThreadController tc = new ThreadController();
    slave.tc = tc;
    master.tc = tc;

    master._process(nodeTilesIn, wayTilesIn, borderFileIn, restrictionsFileIn, lookupFile, profileFile, dataTilesOut, dataTilesSuffix);
    slave._process(nodeTilesIn, wayTilesIn, borderFileIn, restrictionsFileIn, lookupFile, profileFile, dataTilesOut, dataTilesSuffix);

    Thread m = new Thread(master);
    Thread s = new Thread(slave);
    m.start();
    s.start();
    m.join();
    s.join();
  }

  private void _process(File nodeTilesIn, File wayTilesIn, File borderFileIn, File restrictionsFileIn, File lookupFile, File profileFile, File dataTilesOut,
                        String dataTilesSuffix) throws Exception {
    this.nodeTilesIn = nodeTilesIn;
    this.wayTilesIn = wayTilesIn;
    this.dataTilesOut = dataTilesOut;
    this.borderFileIn = borderFileIn;
    this.dataTilesSuffix = dataTilesSuffix;

    BExpressionMetaData meta = new BExpressionMetaData();

    // read lookup + profile for lookup-version + access-filter
    expctxWay = new BExpressionContextWay(meta);
    meta.readMetaData(lookupFile);

    lookupVersion = meta.lookupVersion;
    lookupMinorVersion = meta.lookupMinorVersion;

    expctxWay.parseFile(profileFile, "global");

    creationTimeStamp = System.currentTimeMillis();

    abUnifier = new ByteArrayUnifier(16384, false);

    skipEncodingCheck = Boolean.getBoolean("skipEncodingCheck");

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

    long filesize = wayfile.length();

    System.out.println("**** wayFileStart() for isSlave=" + isSlave + " size=" + filesize);

    if (isSlave) {
      if (!tc.setCurrentSlaveSize(filesize)) {
        return false;
      }
    } else {
      if (!tc.setCurrentMasterSize(filesize)) {
        return false;
      }
    }


    // process corresponding node-file, if any
    elevationType = 3;
    File nodeFile = fileFromTemplate(wayfile, nodeTilesIn, "u5d_1");
    if (nodeFile.exists()) {
      elevationType = 1;
    } else {
      nodeFile = fileFromTemplate(wayfile, nodeTilesIn, "u5d_3");
      if (!nodeFile.exists()) nodeFile = fileFromTemplate(wayfile, nodeTilesIn, "u5d");
    }
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
          if (!isEndNode(n2, w)) {
            r.badWayMatch = true;
          }
        }
      }
      if (r.toWid == w.wid) {
        if (r.toLon == 0 || !checkFrom) {
          r.toLon = n1.ilon;
          r.toLat = n1.ilat;
          n1.bits |= OsmNodeP.DP_SURVIVOR_BIT;
          if (!isEndNode(n2, w)) {
            r.badWayMatch = true;
          }
        }
      }
      r = r.next;
    }
  }

  private boolean isEndNode(OsmNodeP n, WayData w) {
    return n == nodesMap.get(w.nodes.get(0)) || n == nodesMap.get(w.nodes.get(w.nodes.size() - 1));
  }

  @Override
  public void nextWay(WayData way) throws Exception {
    byte[] description = abUnifier.unify(way.description);

    // filter according to profile
    expctxWay.evaluate(false, description);
    boolean ok = expctxWay.getCostfactor() < 10000.;
    expctxWay.evaluate(true, description);
    ok |= expctxWay.getCostfactor() < 10000.;
    if (!ok)
      return;

    byte wayBits = 0;
    expctxWay.decode(description);
    if (!expctxWay.getBooleanLookupValue("bridge"))
      wayBits |= OsmNodeP.NO_BRIDGE_BIT;
    if (!expctxWay.getBooleanLookupValue("tunnel"))
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

        if (n1.ilon / cellsize != n2.ilon / cellsize || n1.ilat / cellsize != n2.ilat / cellsize) {
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
    int ncaches = divisor * divisor;
    int indexsize = ncaches * 4;

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

    // write segment data to individual files
    {
      int nLonSegs = (maxLon - minLon) / 1000000;
      int nLatSegs = (maxLat - minLat) / 1000000;

      // sort the nodes into segments
      LazyArrayOfLists<OsmNodeP> seglists = new LazyArrayOfLists<>(nLonSegs * nLatSegs);
      for (OsmNodeP n : nodesList) {
        if (n == null || n.getFirstLink() == null || n.isTransferNode())
          continue;
        if (n.ilon < minLon || n.ilon >= maxLon || n.ilat < minLat || n.ilat >= maxLat)
          continue;
        int lonIdx = (n.ilon - minLon) / 1000000;
        int latIdx = (n.ilat - minLat) / 1000000;

        int tileIndex = lonIdx * nLatSegs + latIdx;
        seglists.getList(tileIndex).add(n);
      }
      nodesList = null;
      seglists.trimAll();

      // open the output file
      File outfile = fileFromTemplate(wayfile, dataTilesOut, dataTilesSuffix);
      DataOutputStream os = createOutStream(outfile);

      long[] fileIndex = new long[25];
      int[] fileHeaderCrcs = new int[25];

      // write 5*5 index dummy
      for (int i55 = 0; i55 < 25; i55++) {
        os.writeLong(0);
      }
      long filepos = 200L;

      // sort further in 1/divisor-degree squares
      for (int lonIdx = 0; lonIdx < nLonSegs; lonIdx++) {
        for (int latIdx = 0; latIdx < nLatSegs; latIdx++) {
          int tileIndex = lonIdx * nLatSegs + latIdx;
          if (seglists.getSize(tileIndex) > 0) {
            List<OsmNodeP> nlist = seglists.getList(tileIndex);

            LazyArrayOfLists<OsmNodeP> subs = new LazyArrayOfLists<>(ncaches);
            byte[][] subByteArrays = new byte[ncaches][];
            for (int ni = 0; ni < nlist.size(); ni++) {
              OsmNodeP n = nlist.get(ni);
              int subLonIdx = (n.ilon - minLon) / cellsize - divisor * lonIdx;
              int subLatIdx = (n.ilat - minLat) / cellsize - divisor * latIdx;
              int si = subLatIdx * divisor + subLonIdx;
              subs.getList(si).add(n);
            }
            subs.trimAll();
            int[] posIdx = new int[ncaches];
            int pos = indexsize;

            for (int si = 0; si < ncaches; si++) {
              List<OsmNodeP> subList = subs.getList(si);
              int size = subList.size();
              if (size > 0) {
                OsmNodeP n0 = subList.get(0);
                int lonIdxDiv = n0.ilon / cellsize;
                int latIdxDiv = n0.ilat / cellsize;
                MicroCache mc = new MicroCache2(size, abBuf2, lonIdxDiv, latIdxDiv, divisor);

                // sort via treemap
                Map<Integer, OsmNodeP> sortedList = new TreeMap<>();
                for (OsmNodeP n : subList) {
                  long longId = n.getIdFromPos();
                  int shrinkid = mc.shrinkId(longId);
                  if (mc.expandId(shrinkid) != longId) {
                    throw new IllegalArgumentException("inconstistent shrinking: " + longId);
                  }
                  sortedList.put(shrinkid, n);
                }

                for (OsmNodeP n : sortedList.values()) {
                  n.writeNodeData(mc);
                }
                if (mc.getSize() > 0) {
                  byte[] subBytes;
                  for (; ; ) {
                    int len = mc.encodeMicroCache(abBuf1);
                    subBytes = new byte[len];
                    System.arraycopy(abBuf1, 0, subBytes, 0, len);

                    if (skipEncodingCheck) {
                      break;
                    }
                    // cross-check the encoding: re-instantiate the cache
                    MicroCache mc2 = new MicroCache2(new StatCoderContext(subBytes), new DataBuffers(null), lonIdxDiv, latIdxDiv, divisor, null, null);
                    // ..and check if still the same
                    String diffMessage = mc.compareWith(mc2);
                    if (diffMessage != null) {
                      if (MicroCache.debug)
                        throw new RuntimeException("encoding crosscheck failed: " + diffMessage);
                      else
                        MicroCache.debug = true;
                    } else
                      break;
                  }
                  pos += subBytes.length + 4; // reserve 4 bytes for crc
                  subByteArrays[si] = subBytes;
                }
              }
              posIdx[si] = pos;
            }

            byte[] abSubIndex = compileSubFileIndex(posIdx);
            fileHeaderCrcs[tileIndex] = Crc32.crc(abSubIndex, 0, abSubIndex.length);
            os.write(abSubIndex, 0, abSubIndex.length);
            for (int si = 0; si < ncaches; si++) {
              byte[] ab = subByteArrays[si];
              if (ab != null) {
                os.write(ab);
                os.writeInt(Crc32.crc(ab, 0, ab.length) ^ microCacheEncoding);
              }
            }
            filepos += pos;
          }
          fileIndex[tileIndex] = filepos;
        }
      }

      byte[] abFileIndex = compileFileIndex(fileIndex, lookupVersion, lookupMinorVersion);

      // write extra data: timestamp + index-checksums
      os.writeLong(creationTimeStamp);
      os.writeInt(Crc32.crc(abFileIndex, 0, abFileIndex.length) ^ microCacheEncoding);
      for (int i55 = 0; i55 < 25; i55++) {
        os.writeInt(fileHeaderCrcs[i55]);
      }
      os.writeByte(elevationType);

      os.close();

      // re-open random-access to write file-index
      RandomAccessFile ra = new RandomAccessFile(outfile, "rw");
      ra.write(abFileIndex, 0, abFileIndex.length);
      ra.close();
    }
    System.out.println("**** codec stats: *******\n" + StatCoderContext.getBitReport());
  }

  private byte[] compileFileIndex(long[] fileIndex, short lookupVersion, short lookupMinorVersion) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    for (int i55 = 0; i55 < 25; i55++) {
      long versionPrefix = i55 == 1 ? lookupMinorVersion : lookupVersion;
      versionPrefix <<= 48;
      dos.writeLong(fileIndex[i55] | versionPrefix);
    }
    dos.close();
    return bos.toByteArray();
  }

  private byte[] compileSubFileIndex(int[] posIdx) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    for (int si = 0; si < posIdx.length; si++) {
      dos.writeInt(posIdx[si]);
    }
    dos.close();
    return bos.toByteArray();
  }
}
