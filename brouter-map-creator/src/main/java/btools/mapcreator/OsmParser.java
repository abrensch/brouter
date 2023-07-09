package btools.mapcreator;

import org.openstreetmap.osmosis.osmbinary.Fileformat;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import btools.util.LongList;

/**
 * Parser for OSM data
 *
 * @author ab
 */
public class OsmParser extends MapCreatorBase {
  private BufferedReader _br;

  private NodeListener nListener;
  private WayListener wListener;
  private RelationListener rListener;

  public void readMap(File mapFile,
                      NodeListener nListener,
                      WayListener wListener,
                      RelationListener rListener) throws Exception {
    this.nListener = nListener;
    this.wListener = wListener;
    this.rListener = rListener;

    System.out.println("*** PBF Parsing: " + mapFile);

    // once more for testing
    int rawBlobCount = 0;

    long bytesRead = 0L;
    Boolean avoidMapPolling = Boolean.getBoolean("avoidMapPolling");

    if (!avoidMapPolling) {
      // wait for file to become available
      while (!mapFile.exists()) {
        System.out.println("--- waiting for " + mapFile + " to become available");
        Thread.sleep(10000);
      }
    }

    long currentSize = mapFile.length();
    long currentSizeTime = System.currentTimeMillis();

    DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(mapFile)));


    for (; ; ) {
      if (!avoidMapPolling) {
        // continue reading if either more then a 100 MB unread, or the current-size is known for more than 2 Minutes
        while (currentSize - bytesRead < 100000000L) {
          long newSize = mapFile.length();
          if (newSize != currentSize) {
            currentSize = newSize;
            currentSizeTime = System.currentTimeMillis();
          } else if (System.currentTimeMillis() - currentSizeTime > 120000) {
            break;
          }
          if (currentSize - bytesRead < 100000000L) {
            System.out.println("--- waiting for more data, currentSize=" + currentSize + " bytesRead=" + bytesRead);
            Thread.sleep(10000);
          }
        }
      }

      int headerLength;
      try {
        headerLength = dis.readInt();
        bytesRead += 4;
      } catch (EOFException e) {
        break;
      }

      byte[] headerBuffer = new byte[headerLength];
      dis.readFully(headerBuffer);
      bytesRead += headerLength;
      Fileformat.BlobHeader blobHeader = Fileformat.BlobHeader.parseFrom(headerBuffer);

      byte[] blobData = new byte[blobHeader.getDatasize()];
      dis.readFully(blobData);
      bytesRead += blobData.length;

      new BPbfBlobDecoder(blobHeader.getType(), blobData, this).process();

      rawBlobCount++;
    }
    dis.close();
    System.out.println("read raw blobs: " + rawBlobCount);
  }


  public void addNode(long nid, Map<String, String> tags, double lat, double lon) {
    NodeData n = new NodeData(nid, lon, lat);
    n.setTags((HashMap<String, String>) tags);
    try {
      nListener.nextNode(n);
    } catch (Exception e) {
      throw new RuntimeException("error writing node: " + e, e);
    }
  }

  public void addWay(long wid, Map<String, String> tags, LongList nodes) {
    WayData w = new WayData(wid, nodes);
    w.setTags((HashMap<String, String>) tags);

    try {
      wListener.nextWay(w);
    } catch (Exception e) {
      throw new RuntimeException("error writing way: " + e, e);
    }
  }

  public void addRelation(long rid, Map<String, String> tags, LongList wayIds, LongList fromWid, LongList toWid, LongList viaNid) {
    RelationData r = new RelationData(rid, wayIds);
    r.setTags((HashMap<String, String>) tags);

    try {
      rListener.nextRelation(r);
      if (fromWid == null || toWid == null || viaNid == null || viaNid.size() != 1) {
        // dummy-TR for each viaNid
        for (int vi = 0; vi < (viaNid == null ? 0 : viaNid.size()); vi++) {
          rListener.nextRestriction(r, 0L, 0L, viaNid.get(vi));
        }
        return;
      }
      for (int fi = 0; fi < fromWid.size(); fi++) {
        for (int ti = 0; ti < toWid.size(); ti++) {
          rListener.nextRestriction(r, fromWid.get(fi), toWid.get(ti), viaNid.get(0));
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("error writing relation", e);
    }
  }

}
