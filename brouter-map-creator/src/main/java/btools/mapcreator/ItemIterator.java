package btools.mapcreator;

import btools.statcoding.BitInputStream;
import btools.util.SortedHeap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Iterate over a singe file or a directory
 * of files containing OSM items and feed the
 * items to the callback listener
 */
public class ItemIterator {
  private ItemListener listener;
  private boolean delete;

  public ItemIterator(ItemListener itemListener, boolean deleteAfterReading) {
    listener = itemListener;
    delete = deleteAfterReading;
  }

  public ItemIterator(ItemListener itemListener) {
    this( itemListener, true );
  }


  public void processDir(File inDir, String inSuffix, boolean descendingSize) throws Exception {
    if (!inDir.isDirectory()) {
      throw new IllegalArgumentException("not a directory: " + inDir);
    }

    File[] af = inDir.listFiles();
    if ( af != null ) {
      sortBySizeAscending(af);
      for (int i = 0; i < af.length; i++) {
        File file = descendingSize ? af[af.length - 1 - i] : af[i];
        if (file.getName().endsWith(inSuffix)) {
          if ( listener.itemFileStart(file) ) {
            processFile(file);
            listener.itemFileEnd(file);
          }
        }
      }
    }
  }


  public void processFile(File file) throws IOException {
    if ( file.exists() ) {
      System.out.println("*** ItemIterator reading: " + file);
    } else {
      System.out.println("*** ItemIterator skipping non-existing file: " + file);
      return;
    }
    try ( BitInputStream bis = new BitInputStream(new BufferedInputStream(new FileInputStream(file))) ){
      for(;;) {
        long type = bis.decodeVarBytes();
        if ( type == 0l) {
          break;
        } else if ( type == NodeData.TYPE ) {
          NodeData n = new NodeData(bis);
          listener.nextNode(n);
        } else if ( type == NodeData.NID_TYPE ) {
          long nid = bis.decodeVarBytes();
          listener.nextNodeId(nid);
        } else if ( type == WayData.TYPE ) {
          WayData w = new WayData(bis);
          listener.nextWay(w);
        } else if ( type == RelationData.TYPE ) {
          RelationData r = new RelationData(bis);
          listener.nextRelation(r);
        } else if ( type == RestrictionData.TYPE ) {
          RestrictionData r = new RestrictionData(bis);
          listener.nextRestriction(r);
        } else {
          throw new IllegalArgumentException( "unknown object type: " + type );
        }
      }
    }
    if (delete && Boolean.getBoolean("deletetmpfiles")) {
      file.delete();
    }
  }

  private static void sortBySizeAscending(File[] files) {
    SortedHeap<File> heap = new SortedHeap<>();
    for( File file : files ) {
      heap.add( file.length(), file );
    }
    for( int i=0; i< files.length; i++ ) {
      files[i] = heap.popLowestKeyValue();
    }
  }
}
