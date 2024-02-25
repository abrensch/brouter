package btools.mapcreator;

import java.io.*;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitInterleavedConverter;
import btools.statcoding.BitOutputStream;
import btools.util.CompactLongSet;
import btools.util.FrozenLongSet;

/**
 * NodeEnhancer does 3 steps in map-processing:
 * <p>
 * - unify positions (to avoid position duplicates)
 * - add elevation data
 * - make a border-nodes file containing net data from the border-nids-file
 */
public class NodeEnhancer extends ItemCutter implements ItemListener {
  private BitOutputStream nodesOutStream;
  private BitOutputStream borderNodesOut;
  private CompactLongSet[] positionSets;

  private ElevationRaster elevationRaster;
  private File tmpDir;
  private File srtmDir;

  private CompactLongSet borderNids;

  public static void main(String[] args) throws Exception {
    System.out.println("*** NodeEnhancer: Unify position values and enhance elevation");
    if (args.length != 2) {
      System.out.println("usage: java NodeEnhancer <tmpDir> <srtm-data-dir>");
      return;
    }
    new NodeEnhancer(new File(args[0])).process(new File(args[1]));
  }

  public NodeEnhancer(File tmpDir) {
    super( new File( tmpDir, "unodes55" ) );
    this.tmpDir = tmpDir;
  }

  public void process(File srtmDir) throws Exception {
    this.srtmDir = srtmDir;

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
  public boolean itemFileStart(File nodefile) throws IOException {
    elevationRaster = null;
    File demFile = fileFromTemplate( nodefile, srtmDir, "bef" );
    if (demFile.exists()) {
      System.out.println("*** reading: " + demFile);
      try ( BitInputStream bis = new BitInputStream( new BufferedInputStream(new FileInputStream(demFile))) ){
        elevationRaster = new ElevationRasterCoder().decodeRaster(bis);
      }
    } else {
      System.out.println("*** DEM file not found: " + demFile);
    }
    nodesOutStream = createOutStream(fileFromTemplate(nodefile, outTileDir, "u5d"));
    positionSets = new CompactLongSet[2500];
    return true;
  }

  @Override
  public void nextNode(NodeData n) throws IOException {
    n.sElev = elevationRaster == null ? Short.MIN_VALUE : elevationRaster.getElevation(n.iLon, n.iLat);
    unifyPosition(n);
    n.writeTo(nodesOutStream);
    if (borderNids.contains(n.nid)) {
      n.writeTo(borderNodesOut);
    }
  }

  @Override
  public void itemFileEnd(File nodeFile) throws IOException {
    nodesOutStream.encodeVarBytes(0L);
    nodesOutStream.close();
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

  private void unifyPosition(NodeData n) {
    int lonMod = n.iLon % 1000000;
    int lonDelta = lonMod < 500000 ? 1 : -1;
    int latMod = n.iLat % 1000000;
    int latDelta = latMod < 500000 ? 1 : -1;
    for (int distance = 0; distance < 0xfff; distance++ ) {
      long s32 = BitInterleavedConverter.interleaved2Shift32(distance);
      int lonSteps = BitInterleavedConverter.xFromShift32(s32);
      int latSteps = BitInterleavedConverter.xFromShift32(s32);
      int lon = n.iLon + lonSteps * lonDelta;
      int lat = n.iLat + latSteps * latDelta;
      if (checkAdd(lon, lat)) {
        n.iLon = lon;
        n.iLat = lat;
        return;
      }
    }
    throw new RuntimeException("*** ERROR: cannot unify position for: " + n.iLon + " " + n.iLat);
  }
}
