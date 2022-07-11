package btools.server;

import java.util.List;

import btools.mapaccess.OsmNode;
import btools.router.OsmNodeNamed;

public class NearRecentWps {
  private OsmNodeNamed[] recentWaypoints = new OsmNodeNamed[2000];
  private int nextRecentIndex = 0;

  public void add(List<OsmNodeNamed> wplist) {
    synchronized (recentWaypoints) {
      for (OsmNodeNamed wp : wplist) {
        add(wp);
      }
    }
  }

  public void add(OsmNodeNamed wp) {
    recentWaypoints[nextRecentIndex++] = wp;
    if (nextRecentIndex >= recentWaypoints.length) {
      nextRecentIndex = 0;
    }
  }

  public int count(long id) {
    int cnt = 0;
    OsmNode n = new OsmNode(id);
    synchronized (recentWaypoints) {
      for (int i = 0; i < recentWaypoints.length; i++) {
        OsmNodeNamed nn = recentWaypoints[i];
        if (nn != null) {
          if (nn.calcDistance(n) < 4000) {
            cnt++;
          }
        }
      }
    }
    return cnt;
  }

  public OsmNodeNamed closest(long id) {
    int dmin = 0;
    OsmNodeNamed nc = null;
    OsmNode n = new OsmNode(id);
    synchronized (recentWaypoints) {
      for (int i = 0; i < recentWaypoints.length; i++) {
        OsmNodeNamed nn = recentWaypoints[i];
        if (nn != null) {
          int d = nn.calcDistance(n);
          if (d < 4000 && (nc == null || d < dmin)) {
            dmin = d;
            nc = nn;
          }
        }
      }
    }
    return nc;
  }

}
