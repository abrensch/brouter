/**
 * Set holding pairs of osm nodes
 *
 * @author ab
 */
package btools.mapaccess;

import btools.util.CompactLongMap;

public class NodePairSet {
  private long[] n1a;
  private long[] n2a;
  private int tempNodes = 0;
  private int maxTempNodes = 0;
  private int npairs = 0;
  private int freezecount = 0;

  public NodePairSet(int maxTempNodeCount) {
    maxTempNodes = maxTempNodeCount;
    n1a = new long[maxTempNodes];
    n2a = new long[maxTempNodes];
  }

  private static class NodePair {
    public long node2;
    public NodePair next;
  }

  private CompactLongMap<NodePair> map;

  public void addTempPair(long n1, long n2) {
    if (tempNodes < maxTempNodes) {
      n1a[tempNodes] = n1;
      n2a[tempNodes] = n2;
      tempNodes++;
    }
  }

  public void freezeTempPairs() {
    freezecount++;
    for (int i = 0; i < tempNodes; i++) {
      addPair(n1a[i], n2a[i]);
    }
    tempNodes = 0;
  }

  public void clearTempPairs() {
    tempNodes = 0;
  }

  private void addPair(long n1, long n2) {
    if (map == null) {
      map = new CompactLongMap<>();
    }
    npairs++;

    NodePair e = getElement(n1, n2);
    if (e == null) {
      e = new NodePair();
      e.node2 = n2;

      NodePair e0 = map.get(n1);
      if (e0 != null) {
        while (e0.next != null) {
          e0 = e0.next;
        }
        e0.next = e;
      } else {
        map.fastPut(n1, e);
      }
    }
  }

  public int size() {
    return npairs;
  }

  public int getFreezeCount() {
    return freezecount;
  }

  public boolean hasPair(long n1, long n2) {
    return map != null && (getElement(n1, n2) != null || getElement(n2, n1) != null);
  }

  private NodePair getElement(long n1, long n2) {
    NodePair e = map.get(n1);
    while (e != null) {
      if (e.node2 == n2) {
        return e;
      }
      e = e.next;
    }
    return null;
  }
}
