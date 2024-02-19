package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.util.DenseLongMap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * WayCutter5 does some steps in map-processing:
 * <p>
 * - enrich way data with relation information
 * - cut the 45*30 way files further into 5*5 pieces
 * - do the same for nodes and restrictions by
 *   calling NodeCutter5 and RestrictionCutter5
 * - create a file containing all border node ids
 */
public class WayCutter5 extends ItemCutter implements ItemListener {
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

    new ItemIterator(this).processDir(new File( tmpDir, "ways" ), ".wtl", false);
    borderNidsOutStream.encodeVarBytes(0L);
    borderNidsOutStream.close();
  }

  @Override
  public boolean itemFileStart(File wayfile) throws IOException {

    // cut the corresponding node-file, filtering the relevant nodes using nodeFilter
    // (and nodeCutter5 populates it's tile-index-map needed to distribute the ways and restrictions)
    File nodefile = fileFromTemplate(wayfile, new File(tmpDir, "nodes"), "ntl");
    nodeCutter5 = new NodeCutter5(tmpDir,nodeFilter);
    new ItemIterator(nodeCutter5).processFile(nodefile);
    nodeCutter5.closeTileOutStreams();

    // cut the corresponding restrictions-file
    File restrictionFile = fileFromTemplate(wayfile, new File(tmpDir, "restrictions"), "rtl");
    RestrictionCutter5 restrictionCutter5 = new RestrictionCutter5(tmpDir, nodeCutter5);
    new ItemIterator(restrictionCutter5).processFile(restrictionFile);
    restrictionCutter5.closeTileOutStreams();

    return true;
  }

  @Override
  public void nextWay(WayData data) throws IOException {
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

    relMerger.addRelationDataToWay(data);

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
          borderNidsOutStream.encodeVarBytes(NodeData.NID_TYPE);
          borderNidsOutStream.encodeVarBytes(data.nodes.get(i));
        }
      }
    }
  }

  @Override
  public void itemFileEnd(File wayFile) throws IOException {
    closeTileOutStreams();
  }

  protected String getNameForTile(int tileIndex) {
    return nodeCutter5.getBaseNameForTile55(tileIndex) + ".wt5";
  }
}
