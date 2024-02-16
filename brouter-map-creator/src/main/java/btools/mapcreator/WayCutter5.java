package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
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
  private DataOutputStream borderNidsOutStream;
  private File nodeTilesIn;

  private File tmpDir;
  public RelationMerger relMerger;
  public NodeCutter5 nodeCutter5;
  public RestrictionCutter5 restrictionCutter5;

  public WayCutter5(File tmpDir) {
    super(new File(tmpDir, "ways55"));
    this.tmpDir = tmpDir;
  }

  public void process(NodeFilter nodeFilter, File lookupFile, File profileReport, File profileCheck) throws Exception {
    nodeTilesIn = new File( tmpDir, "nodes" );
    relMerger = new RelationMerger(tmpDir, lookupFile, profileReport, profileCheck);
    nodeCutter5 = new NodeCutter5(tmpDir,nodeFilter);
    restrictionCutter5 = new RestrictionCutter5(tmpDir, nodeCutter5);
    borderNidsOutStream = createOutStream(new File( tmpDir, "bordernids.dat" ));

    new WayIterator(this).processDir(new File( tmpDir, "ways" ), ".wtl");

    borderNidsOutStream.close();
  }

  @Override
  public boolean wayFileStart(File wayfile) throws Exception {
    fileStart();
    // read corresponding node-file into tileIndexMap
    String name = wayfile.getName();
    String nodefilename = name.substring(0, name.length() - 3) + "ntl";
    File nodefile = new File(nodeTilesIn, nodefilename);

    new NodeIterator(nodeCutter5, true).processFile(nodefile);

    String restrictionFilename = name.substring(0, name.length() - 3) + "rtl";
    File resfile = new File( new File(tmpDir, "restrictions"), restrictionFilename );
    if (resfile.exists()) {
      // read restrictions for nodes in nodesMap
      int ntr = 0;
      try ( DataInputStream di = new DataInputStream(new BufferedInputStream(new FileInputStream(resfile))) ) {
        for (; ; ) {
          RestrictionData res = new RestrictionData(di);
          restrictionCutter5.nextRestriction(res);
          ntr++;
        }
      } catch (EOFException eof) { // expected
      }
      System.out.println("read " + ntr + " turn-restrictions");
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
          MapCreatorBase.writeId(borderNidsOutStream, data.nodes.get(i));
        }
      }
    }
  }

  @Override
  public void wayFileEnd(File wayFile) throws Exception {
    closeTileOutStreams();
    nodeCutter5.nodeFileEnd(null);
    restrictionCutter5.finish();
  }

  protected String getNameForTile(int tileIndex) {
    return nodeCutter5.getBaseNameForTile55(tileIndex) + ".wt5";
  }
}
