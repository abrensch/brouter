package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;

/**
 * Iterate over a singe wayfile or a directory
 * of waytiles and feed the ways to the callback listener
 *
 * @author ab
 */
public class WayIterator extends MapCreatorBase {
  private WayListener listener;
  private boolean delete;
  private boolean descendingSize;

  public WayIterator(WayListener wayListener, boolean deleteAfterReading) {
    listener = wayListener;
    delete = deleteAfterReading;
  }

  public WayIterator(WayListener wayListener, boolean deleteAfterReading, boolean descendingSize) {
    this(wayListener, deleteAfterReading);
    this.descendingSize = descendingSize;
  }

  public void processDir(File indir, String inSuffix) throws Exception {
    if (!indir.isDirectory()) {
      throw new IllegalArgumentException("not a directory: " + indir);
    }

    File[] af = sortBySizeAsc(indir.listFiles());
    for (int i = 0; i < af.length; i++) {
      File wayfile = descendingSize ? af[af.length - 1 - i] : af[i];
      if (wayfile.getName().endsWith(inSuffix)) {
        processFile(wayfile);
      }
    }
  }


  public void processFile(File wayfile) throws Exception {
    System.out.println("*** WayIterator reading: " + wayfile);

    if (!listener.wayFileStart(wayfile)) {
      return;
    }

    DataInputStream di = new DataInputStream(new BufferedInputStream(new FileInputStream(wayfile)));
    try {
      for (; ; ) {
        WayData w = new WayData(di);
        listener.nextWay(w);
      }
    } catch (EOFException eof) {
      di.close();
    }
    listener.wayFileEnd(wayfile);
    if (delete && "true".equals(System.getProperty("deletetmpfiles"))) {
      wayfile.delete();
    }
  }
}
