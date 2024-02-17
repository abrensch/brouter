package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.util.DenseLongMap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * WayCutter5 does 2 step in map-processing:
 * <p>
 * - cut the 45*30 way files into 5*5 pieces
 * - create a file containing all border node ids
 *
 * @author ab
 */
public class WayCutter5 extends ItemCutter implements WayListener {
  private BitOutputStream borderNidsOutStream;
  private File tmpDir;
  private DenseLongMap nodeFilter;
  public RelationMerger relMerger;
  public NodeCutter5 nodeCutter5;

  public WayCutter5(File tmpDir) {
    super(new File(tmpDir, "ways55"));
    this.tmpDir = tmpDir;
  }

  public void process(DenseLongMap nodeFilter, File lookupFile, File profileReport, File profileCheck) throws Exception {
    this.nodeFilter = nodeFilter;
    relMerger = new RelationMerger(tmpDir, lookupFile, profileReport, profileCheck);
    borderNidsOutStream = createOutStream(new File( tmpDir, "bordernids.dat" ));

    new WayIterator(this).processDir(new File( tmpDir, "ways" ), ".wtl");
    borderNidsOutStream.encodeVarBytes(0L);
    borderNidsOutStream.close();
  }

  @Override
  public boolean wayFileStart(File wayfile) throws Exception {

    // cut the corresponding node-file, filtering the relevant nodes using nodeFilter
    // (and nodeCutter5 populates it's tile-index-map needed to distribute the ways and restrictions)
    String name = wayfile.getName();
    String nodefilename = name.substring(0, name.length() - 3) + "ntl";
    File nodefile = new File( new File( tmpDir, "nodes" ), nodefilename);
    nodeCutter5 = new NodeCutter5(tmpDir,nodeFilter);
    new NodeIterator(nodeCutter5, true).processFile(nodefile);
    nodeCutter5.closeTileOutStreams();

    // cut the corresponding restrictions-file
    String restrictionFilename = name.substring(0, name.length() - 3) + "rtl";
    File resfile = new File( new File(tmpDir, "restrictions"), restrictionFilename );
    if (resfile.exists()) {
      RestrictionCutter5 restrictionCutter5 = new RestrictionCutter5(tmpDir, nodeCutter5);
      try ( BitInputStream bis = new BitInputStream(new BufferedInputStream(new FileInputStream(resfile))) ) {
        while (bis.decodeVarBytes() == 1L) {
          RestrictionData res = new RestrictionData(bis);
          restrictionCutter5.nextRestriction(res);
        }
      }
      restrictionCutter5.closeTileOutStreams();
    }
    return true;
  }

  @Override
  public void nextWay(WayData data) throws Exception {
    long waytileset = 0;
    int nnodes = data.nodes.size();
    int[] tiForNode = new int[nnodes];

    // determine the tile-index for each node
    for (int i = 0; i < nnodes; i++) {
      int tileIndex = nodeCutter5.getTileIndexForNid(data.nodes.get(i));
      if (tileIndex != -1) {
        waytileset |= (1L << tileIndex);
      }
      tiForNode[i] = tileIndex;
    }

    relMerger.nextWay(data);

    // now write way to all tiles hit
    for (int tileIndex = 0; tileIndex < 54; tileIndex++) {
      if ((waytileset & (1L << tileIndex)) == 0) {
        continue;
      }
      data.writeTo(getOutStreamForTile(tileIndex));
    }

    // and write edge nodes to the border-nid file
    for (int i = 0; i < nnodes; i++) {
      int ti = tiForNode[i];
      if (ti != -1) {
        if ((i > 0 && tiForNode[i - 1] != ti) || (i + 1 < nnodes && tiForNode[i + 1] != ti)) {
          borderNidsOutStream.encodeVarBytes(1L);
          borderNidsOutStream.encodeVarBytes(data.nodes.get(i));
        }
      }
    }
  }

  @Override
  public void wayFileEnd(File wayFile) throws Exception {
    closeTileOutStreams();
  }

  protected String getNameForTile(int tileIndex) {
    return nodeCutter5.getBaseNameForTile55(tileIndex) + ".wt5";
  }
}
