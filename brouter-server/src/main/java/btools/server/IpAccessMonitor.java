package btools.server;

import java.util.HashMap;
import java.util.Map;

public class IpAccessMonitor {
  private static Object sync = new Object();
  private static Map<String, Long> ipAccess = new HashMap<>();
  private static long MAX_IDLE = 900000; // 15 minutes
  private static long CLEANUP_INTERVAL = 10000; // 10 seconds
  private static long lastCleanup;

  public static boolean touchIpAccess(String ip) {
    long t = System.currentTimeMillis();
    synchronized (sync) {
      Long lastTime = ipAccess.get(ip);
      ipAccess.put(ip, t);
      return lastTime == null || t - lastTime > MAX_IDLE;
    }
  }

  public static int getSessionCount() {
    long t = System.currentTimeMillis();
    synchronized (sync) {
      if (t - lastCleanup > CLEANUP_INTERVAL) {
        cleanup(t);
        lastCleanup = t;
      }
      return ipAccess.size();
    }
  }

  private static void cleanup(long t) {
    Map<String, Long> newMap = new HashMap<>(ipAccess.size());
    for (Map.Entry<String, Long> e : ipAccess.entrySet()) {
      if (t - e.getValue() <= MAX_IDLE) {
        newMap.put(e.getKey(), e.getValue());
      }
    }
    ipAccess = newMap;
  }

}
