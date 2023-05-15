/**
 * Container for link between two Osm nodes (pre-pocessor version)
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import btools.expressions.BExpressionContextWay;
import btools.util.CheapRuler;
import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;


public class OsmTrafficMap {
  int minLon;
  int minLat;
  int maxLon;
  int maxLat;

  private BExpressionContextWay expctxWay;

  private OsmTrafficMap oldTrafficClasses;
  private DataOutputStream newTrafficDos;
  private File oldTrafficFile;
  private File newTrafficFile;

  private int totalChanges = 0;
  private int supressedChanges = 0;

  private boolean doNotAdd = false;
  private boolean debug = false;

  public OsmTrafficMap(BExpressionContextWay expctxWay) {
    this.expctxWay = expctxWay;
    debug = Boolean.getBoolean("debugTrafficMap");
  }

  public static class OsmTrafficElement {
    public long node2;
    public int traffic;
    public OsmTrafficElement next;
  }

  private CompactLongMap<OsmTrafficElement> map = new CompactLongMap<>();

  public void loadAll(File file, int minLon, int minLat, int maxLon, int maxLat, boolean includeMotorways) throws Exception {
    load(file, minLon, minLat, maxLon, maxLat, includeMotorways);

    // check for old traffic data
    oldTrafficFile = new File(file.getParentFile(), file.getName() + "_old");
    if (oldTrafficFile.exists()) {
      oldTrafficClasses = new OsmTrafficMap(null);
      oldTrafficClasses.doNotAdd = true;
      oldTrafficClasses.load(oldTrafficFile, minLon, minLat, maxLon, maxLat, false);
    }

    // check for old traffic data
    newTrafficFile = new File(file.getParentFile(), file.getName() + "_new");
    newTrafficDos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(newTrafficFile)));
  }

  public void finish() throws Exception {
    if (newTrafficDos != null) {
      newTrafficDos.close();
      newTrafficDos = null;
      oldTrafficFile.delete();
      newTrafficFile.renameTo(oldTrafficFile);
      System.out.println("TrafficMap: changes total=" + totalChanges + " supressed=" + supressedChanges);
    }
  }

  public void load(File file, int minLon, int minLat, int maxLon, int maxLat, boolean includeMotorways) throws Exception {
    this.minLon = minLon;
    this.minLat = minLat;
    this.maxLon = maxLon;
    this.maxLat = maxLat;

    int trafficElements = 0;
    DataInputStream is = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
    try {
      for (; ; ) {
        long n1 = is.readLong();
        long n2 = is.readLong();
        int traffic = is.readInt();
        if (traffic == -1 && !includeMotorways) {
          continue;
        }
        if (isInsideBounds(n1) || isInsideBounds(n2)) {
          if (addElement(n1, n2, traffic)) {
            trafficElements++;
          }
        }
      }
    } catch (EOFException eof) {
    } finally {
      is.close();
    }

    map = new FrozenLongMap<>(map);
    System.out.println("read traffic-elements: " + trafficElements);
  }


  public boolean addElement(long n1, long n2, int traffic) {
    OsmTrafficElement e = getElement(n1, n2);
    if (e == null) {
      e = new OsmTrafficElement();
      e.node2 = n2;
      e.traffic = traffic;

      OsmTrafficElement e0 = map.get(n1);
      if (e0 != null) {
        while (e0.next != null) {
          e0 = e0.next;
        }
        e0.next = e;
      } else {
        map.fastPut(n1, e);
      }
      return true;
    }
    if (doNotAdd) {
      e.traffic = Math.max(e.traffic, traffic);
    } else {
      e.traffic = e.traffic == -1 || traffic == -1 ? -1 : e.traffic + traffic;
    }
    return false;
  }

  private boolean isInsideBounds(long id) {
    int ilon = (int) (id >> 32);
    int ilat = (int) (id & 0xffffffff);

    return ilon >= minLon && ilon < maxLon && ilat >= minLat && ilat < maxLat;
  }

  public int getTrafficClass(long n1, long n2) {
    // used for the old data, where we stpre traffic-classes, not volumes
    OsmTrafficElement e = getElement(n1, n2);
    return e == null ? 0 : e.traffic;
  }

  public int getTrafficClassForTraffic(int traffic) {
    if (traffic < 0) return -1;
    if (traffic < 40000) return 0;
    if (traffic < 80000) return 2;
    if (traffic < 160000) return 3;
    if (traffic < 320000) return 4;
    if (traffic < 640000) return 5;
    if (traffic < 1280000) return 6;
    return 7;
  }

  private int getTraffic(long n1, long n2) {
    OsmTrafficElement e1 = getElement(n1, n2);
    int traffic1 = e1 == null ? 0 : e1.traffic;
    OsmTrafficElement e2 = getElement(n2, n1);
    int traffic2 = e2 == null ? 0 : e2.traffic;
    return traffic1 == -1 || traffic2 == -1 ? -1 : traffic1 > traffic2 ? traffic1 : traffic2;
  }

  public void freeze() {
  }

  private OsmTrafficElement getElement(long n1, long n2) {
    OsmTrafficElement e = map.get(n1);
    while (e != null) {
      if (e.node2 == n2) {
        return e;
      }
      e = e.next;
    }
    return null;
  }

  public OsmTrafficElement getElement(long n) {
    return map.get(n);
  }

  public byte[] addTrafficClass(List<OsmNodeP> linkNodes, byte[] description) throws IOException {
    double distance = 0.;
    double sum = 0.;

    for (int i = 0; i < linkNodes.size() - 1; i++) {
      OsmNodeP n1 = linkNodes.get(i);
      OsmNodeP n2 = linkNodes.get(i + 1);
      int traffic = getTraffic(n1.getIdFromPos(), n2.getIdFromPos());
      double dist = CheapRuler.distance(n1.ilon, n1.ilat, n2.ilon, n2.ilat);
      distance += dist;
      sum += dist * traffic;
    }

    if (distance == 0.) {
      return description;
    }
    int traffic = (int) (sum / distance + 0.5);

    long id0 = linkNodes.get(0).getIdFromPos();
    long id1 = linkNodes.get(linkNodes.size() - 1).getIdFromPos();

    int trafficClass = getTrafficClassForTraffic(traffic);

    // delta suppression: keep old traffic classes within some buffer range
    if (oldTrafficClasses != null) {
      int oldTrafficClass = oldTrafficClasses.getTrafficClass(id0, id1);
      if (oldTrafficClass != trafficClass) {
        totalChanges++;
        boolean supressChange =
          oldTrafficClass == getTrafficClassForTraffic((int) (traffic * 1.3))
            || oldTrafficClass == getTrafficClassForTraffic((int) (traffic * 0.77));

        if (debug) {
          System.out.println("traffic class change " + oldTrafficClass + "->" + trafficClass + " supress=" + supressChange);
        }
        if (supressChange) {
          trafficClass = oldTrafficClass;
          supressedChanges++;
        }
      }
    }

    if (trafficClass > 0) {
      newTrafficDos.writeLong(id0);
      newTrafficDos.writeLong(id1);
      newTrafficDos.writeInt(trafficClass);

      expctxWay.decode(description);
      expctxWay.addLookupValue("estimated_traffic_class", trafficClass + 1);
      return expctxWay.encode();
    }
    return description;
  }

}
