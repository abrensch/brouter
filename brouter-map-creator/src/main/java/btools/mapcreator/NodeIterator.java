package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;

import btools.util.DiffCoderDataInputStream;

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

  public void processDir(File indir, String [] inSuffixes) throws Exception {
    if (!indir.isDirectory()) {
      throw new IllegalArgumentException("not a directory: " + indir);
    }

    int filesProcessed = 0;
    File[] af = sortBySizeAsc(indir.listFiles());
    for (int s = 0; s < inSuffixes.length; s++) {
      String inSuffix = inSuffixes[s];
      for (int i = 0; i < af.length; i++) {
        File nodefile = af[i];
        if (nodefile.getName().endsWith(inSuffix)) {
          processFile(nodefile);
          filesProcessed++;
        }
      }
    }
    if(filesProcessed == 0) {
      throw new RuntimeException("no files with suffixes " + String.join(", ", inSuffixes) + " found in directory: " + indir);
    }
  }


  public void processFile(File nodefile) throws Exception {
    System.out.println("*** NodeIterator reading: " + nodefile);

    listener.nodeFileStart(nodefile);

    DiffCoderDataInputStream di = new DiffCoderDataInputStream(new BufferedInputStream(new FileInputStream(nodefile)));
    try {
      for (; ; ) {
        NodeData n = new NodeData(di);
        listener.nextNode(n);
      }
    } catch (EOFException eof) {
      di.close();
    }
    listener.nodeFileEnd(nodefile);
    if (delete && "true".equals(System.getProperty("deletetmpfiles"))) {
      nodefile.delete();
    }
  }
}
