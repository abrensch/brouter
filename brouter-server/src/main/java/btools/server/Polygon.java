package btools.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public class Polygon {
  private int[] ax;
  private int[] ay;

  private int minx = Integer.MAX_VALUE;
  private int miny = Integer.MAX_VALUE;
  private int maxx = Integer.MIN_VALUE;
  private int maxy = Integer.MIN_VALUE;

  public Polygon(BufferedReader br) throws IOException {
    List<String> lines = new ArrayList<>();

    for (; ; ) {
      String line = br.readLine();
      if (line == null || "END".equals(line)) {
        break;
      }
      lines.add(line);
    }
    int n = lines.size();
    ax = new int[n];
    ay = new int[n];
    for (int i = 0; i < n; i++) {
      String line = lines.get(i);
      StringTokenizer tk = new StringTokenizer(line);
      double lon = Double.parseDouble(tk.nextToken());
      double lat = Double.parseDouble(tk.nextToken());

      int x = ax[i] = (int) (lon * 1000000. + 180000000);
      int y = ay[i] = (int) (lat * 1000000. + 90000000);

      if (x < minx) minx = x;
      if (y < miny) miny = y;
      if (x > maxx) maxx = x;
      if (y > maxy) maxy = y;
    }
  }

  public boolean isInPolygon(long id) {
    int x = (int) (id >> 32);
    int y = (int) (id & 0xffffffff);

    if (x < minx || x > maxx || y < miny || y > maxy) {
      return false;
    }

    int n = ax.length - 1; // these are closed polygons

    boolean inside = false;
    int j = n - 1;
    for (int i = 0; i < n; j = i++) {
      if ((ay[i] > y) != (ay[j] > y)) {
        long v = ax[j] - ax[i];
        v *= y - ay[i];
        v /= ay[j] - ay[i];
        if (x <= v + ax[i]) {
          inside = !inside;
        }
      }
    }
    return inside;
  }

  public boolean isInBoundingBox(long id) {
    int x = (int) (id >> 32);
    int y = (int) (id & 0xffffffff);

    return x >= minx && x <= maxx && y >= miny && y <= maxy;
  }

}
