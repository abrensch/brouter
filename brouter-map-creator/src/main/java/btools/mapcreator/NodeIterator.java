package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;

import btools.statcoding.BitInputStream;

/**
 * Iterate over a singe nodefile or a directory
 * of nodetiles and feed the nodes to the callback listener
 *
 * @author ab
 */
public class NodeIterator extends MapCreatorBase {
  private NodeListener listener;
  private boolean delete;

  public NodeIterator(NodeListener nodeListener, boolean deleteAfterReading) {
    listener = nodeListener;
    delete = deleteAfterReading;
  }

  public void processDir(File inDir, String inSuffix) throws Exception {
    if (!inDir.isDirectory()) {
      throw new IllegalArgumentException("not a directory: " + inDir);
    }

    File[] af = inDir.listFiles();
    if ( af != null ) {
      sortBySizeAsc(af);
      for (File nodeFile : af) {
        if (nodeFile.getName().endsWith(inSuffix)) {
          processFile(nodeFile);
        }
      }
    }
  }


  public void processFile(File nodefile) throws Exception {
    System.out.println("*** NodeIterator reading: " + nodefile);

    listener.nodeFileStart(nodefile);

    try ( BitInputStream bis = new BitInputStream(new BufferedInputStream(new FileInputStream(nodefile))) ){
      for(;;) {
        long type = bis.decodeVarBytes();
        if ( type == 0l) {
          break;
        } else if ( type == 1L ) {
          NodeData n = new NodeData(bis);
          listener.nextNode(n);
        } else {
          throw new IllegalArgumentException( "unknown object type: " + type );
        }
      }
    }
    listener.nodeFileEnd(nodefile);
    if (delete && Boolean.getBoolean("deletetmpfiles")) {
      nodefile.delete();
    }
  }
}
