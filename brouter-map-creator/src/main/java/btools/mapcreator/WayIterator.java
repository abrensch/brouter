package btools.mapcreator;

import btools.statcoding.BitInputStream;

import java.io.BufferedInputStream;
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
  private boolean descendingSize;

  public WayIterator(WayListener wayListener) {
    listener = wayListener;
  }

  public WayIterator(boolean descendingSize, WayListener wayListener) {
    this(wayListener);
    this.descendingSize = descendingSize;
  }

  public void processDir(File inDir, String inSuffix) throws Exception {
    if (!inDir.isDirectory()) {
      throw new IllegalArgumentException("not a directory: " + inDir);
    }

    File[] af = inDir.listFiles();
    if ( af != null ) {
      sortBySizeAsc(af);
      for (int i = 0; i < af.length; i++) {
        File wayfile = descendingSize ? af[af.length - 1 - i] : af[i];
        if (wayfile.getName().endsWith(inSuffix)) {
          processFile(wayfile);
        }
      }
    }
  }


  public void processFile(File wayfile) throws Exception {
    System.out.println("*** WayIterator reading: " + wayfile);

    if (!listener.wayFileStart(wayfile)) {
      return;
    }

    try ( BitInputStream bis = new BitInputStream(new BufferedInputStream(new FileInputStream(wayfile))) ) {
      for(;;) {
        long type = bis.decodeVarBytes();
        if ( type == 0l) {
          break;
        } else if ( type == 1L ) {
          WayData w = new WayData(bis);
          listener.nextWay(w);
        } else {
          throw new IllegalArgumentException( "unknown object type: " + type );
        }
      }
    }
    listener.wayFileEnd(wayfile);
    if (Boolean.getBoolean("deletetmpfiles")) {
      wayfile.delete();
    }
  }
}
