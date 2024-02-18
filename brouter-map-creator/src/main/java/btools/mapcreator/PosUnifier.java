package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.util.CompactLongSet;
import btools.util.FrozenLongSet;

/**
 * PosUnifier does 3 steps in map-processing:
 * <p>
 * - unify positions - add srtm elevation data - make a bordernodes file
 * containing net data from the bordernids-file just containing ids
 *
 * @author ab
 */
public class PosUnifier extends MapCreatorBase implements ItemListener {
  private BitOutputStream nodesOutStream;
  private BitOutputStream borderNodesOut;
  private CompactLongSet[] positionSets;

  private Map<String, SrtmRaster> srtmmap;
  private int lastSrtmLonIdx;
  private int lastSrtmLatIdx;
  private SrtmRaster lastSrtmRaster;
  private String srtmdir;

  private CompactLongSet borderNids;

  private File dataTilesOut;

  public static void main(String[] args) throws Exception {
    System.out.println("*** PosUnifier: Unify position values and enhance elevation");
    if (args.length != 2) {
      System.out.println("usage: java PosUnifier <tmpDir> <srtm-data-dir>");
      return;
    }
    new PosUnifier(new File(args[0])).process(new File(args[0]), args[1]);
  }

  public PosUnifier(File tmpDir) {

    dataTilesOut = new File( tmpDir, "unodes55" );
    if ( !dataTilesOut.exists() && !dataTilesOut.mkdir() ) {
      throw new RuntimeException( "directory " + dataTilesOut + " cannot be created" );
    }
  }

  public void process(File tmpDir, String srtmdir) throws Exception {
    this.srtmdir = srtmdir;

    // read border nids set
    borderNids = new CompactLongSet();
    new ItemIterator(this).processFile(new File( tmpDir, "bordernids.dat"));
    borderNids = new FrozenLongSet(borderNids);

    // process all files
    borderNodesOut = createOutStream(new File( tmpDir, "bordernodes.dat"));
    new ItemIterator(this).processDir(new File( tmpDir, "nodes55"), ".n5d", false);
    borderNodesOut.encodeVarBytes(0L);
    borderNodesOut.close();
  }

  @Override
  public void nextNodeId(long nid) {
    if (!borderNids.contains(nid)) {
      borderNids.fastAdd(nid);
    }
  }

  @Override
  public boolean itemFileStart(File nodefile) throws Exception {
    resetSrtm();
    nodesOutStream = createOutStream(fileFromTemplate(nodefile, dataTilesOut, "u5d"));
    positionSets = new CompactLongSet[2500];
    return true;
  }

  @Override
  public void nextNode(NodeData n) throws Exception {
    n.sElev = Short.MIN_VALUE;

    // TODO: performance bottleneck from hgtForNode

/*    SrtmRaster srtm = hgtForNode(n.ilon, n.ilat);
    if (srtm == null) {
      srtm = srtmForNode(n.ilon, n.ilat);
    } */

    SrtmRaster srtm = srtmForNode(n.iLon, n.iLat);

    if (srtm != null) n.sElev = srtm.getElevation(n.iLon, n.iLat);
    findUniquePos(n);

    n.writeTo(nodesOutStream);
    if (borderNids.contains(n.nid)) {
      n.writeTo(borderNodesOut);
    }
  }

  @Override
  public void itemFileEnd(File nodeFile) throws Exception {
    nodesOutStream.encodeVarBytes(0L);
    nodesOutStream.close();
    resetSrtm();
  }

  private boolean checkAdd(int lon, int lat) {
    int slot = ((lon % 5000000) / 100000) * 50 + ((lat % 5000000) / 100000);
    long id = ((long) lon) << 32 | lat;
    CompactLongSet set = positionSets[slot];
    if (set == null) {
      positionSets[slot] = set = new CompactLongSet();
    }
    if (!set.contains(id)) {
      set.fastAdd(id);
      return true;
    }
    return false;
  }


  private void findUniquePos(NodeData n) {
    if (!checkAdd(n.iLon, n.iLat)) {
      _findUniquePos(n);
    }
  }

  private void _findUniquePos(NodeData n) {
    // fix the position for uniqueness
    int lonmod = n.iLon % 1000000;
    int londelta = lonmod < 500000 ? 1 : -1;
    int latmod = n.iLat % 1000000;
    int latdelta = latmod < 500000 ? 1 : -1;
    for (int latsteps = 0; latsteps < 100; latsteps++) {
      for (int lonsteps = 0; lonsteps <= latsteps; lonsteps++) {
        int lon = n.iLon + lonsteps * londelta;
        int lat = n.iLat + latsteps * latdelta;
        if (checkAdd(lon, lat)) {
          n.iLon = lon;
          n.iLat = lat;
          return;
        }
      }
    }
    System.out.println("*** WARNING: cannot unify position for: " + n.iLon + " " + n.iLat);
  }

  /**
   * get the srtm data set for a position srtm coords are
   * srtm_<srtmLon>_<srtmLat> where srtmLon = 180 + lon, srtmLat = 60 - lat
   */
  private SrtmRaster srtmForNode(int ilon, int ilat) throws Exception {
    int srtmLonIdx = (ilon + 5000000) / 5000000;
    int srtmLatIdx = (654999999 - ilat) / 5000000 - 100; // ugly negative rounding...

    if (srtmLonIdx == lastSrtmLonIdx && srtmLatIdx == lastSrtmLatIdx) {
      return lastSrtmRaster;
    }
    lastSrtmLonIdx = srtmLonIdx;
    lastSrtmLatIdx = srtmLatIdx;

    String slonidx = "0" + srtmLonIdx;
    String slatidx = "0" + srtmLatIdx;
    String filename = "srtm_" + slonidx.substring(slonidx.length() - 2) + "_" + slatidx.substring(slatidx.length() - 2);

    lastSrtmRaster = srtmmap.get(filename);
    if (lastSrtmRaster == null && !srtmmap.containsKey(filename)) {
      File f = new File(new File(srtmdir), filename + ".bef");
      if (f.exists()) {
        System.out.println("*** reading: " + f);
        try {
          InputStream isc = new BufferedInputStream(new FileInputStream(f));
          lastSrtmRaster = new RasterCoder().decodeRaster(isc);
          isc.close();
        } catch (Exception e) {
          System.out.println("**** ERROR reading " + f + " ****");
        }
        srtmmap.put(filename, lastSrtmRaster);
        return lastSrtmRaster;
      }

      f = new File(new File(srtmdir), filename + ".zip");
      // System.out.println("reading: " + f + " ilon=" + ilon + " ilat=" + ilat);
      if (f.exists()) {
        try {
          lastSrtmRaster = new SrtmData(f).getRaster();
          srtmmap.put(filename, lastSrtmRaster);
          return lastSrtmRaster;
        } catch (Exception e) {
          System.out.println("**** ERROR reading " + f + " ****");
        }
      }
      srtmmap.put(filename, lastSrtmRaster);
    }
    return lastSrtmRaster;
  }

  private SrtmRaster hgtForNode(int ilon, int ilat) throws Exception {
    double lon = (ilon - 180000000) / 1000000.;
    double lat = (ilat - 90000000) / 1000000.;

    String filename = buildHgtFilename(lat, lon);
    // don't block lastSrtmRaster
    SrtmRaster srtm = srtmmap.get(filename);
    if (srtm == null) {
      File f = new File(new File(srtmdir), filename + ".hgt");
      if (f.exists()) {
        srtm = new ConvertLidarTile().getRaster(f, lon, lat);
        srtmmap.put(filename, srtm);
        return srtm;
      }
      f = new File(new File(srtmdir), filename + ".zip");
      if (f.exists()) {
        srtm = new ConvertLidarTile().getRaster(f, lon, lat);
        srtmmap.put(filename, srtm);
        return srtm;
      }
    }
    return srtm;
  }

  private String buildHgtFilename(double llat, double llon) {
    int lat = (int) llat;
    int lon = (int) llon;

    String latPref = "N";
    if (lat < 0) {
      latPref = "S";
      lat = -lat + 1;
    }
    String lonPref = "E";
    if (lon < 0) {
      lonPref = "W";
      lon = -lon + 1;
    }

    return String.format(Locale.US, "%s%02d%s%03d", latPref, lat, lonPref, lon);
  }

  private void resetSrtm() {
    srtmmap = new HashMap<>();
    lastSrtmLonIdx = -1;
    lastSrtmLatIdx = -1;
    lastSrtmRaster = null;
  }

}
