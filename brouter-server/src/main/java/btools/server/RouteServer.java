package btools.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.StringTokenizer;
import java.util.zip.GZIPOutputStream;

import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.ProfileCache;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.router.RoutingParamCollector;
import btools.server.request.ProfileUploadHandler;
import btools.server.request.RequestHandler;
import btools.server.request.ServerHandler;
import btools.util.StackSampler;

public class RouteServer extends Thread implements Comparable<RouteServer> {
  public static final String PROFILE_UPLOAD_URL = "/brouter/profile";
  static final String HTTP_STATUS_OK = "200 OK";
  static final String HTTP_STATUS_BAD_REQUEST = "400 Bad Request";
  static final String HTTP_STATUS_FORBIDDEN = "403 Forbidden";
  static final String HTTP_STATUS_NOT_FOUND = "404 Not Found";
  static final String HTTP_STATUS_INTERNAL_SERVER_ERROR = "500 Internal Server Error";

  public ServiceContext serviceContext;

  private Socket clientSocket = null;
  private RoutingEngine cr = null;
  private volatile boolean terminated;
  private long starttime;

  private static Object threadPoolSync = new Object();
  private static boolean debug = Boolean.getBoolean("debugThreadPool");

  public void stopRouter() {
    RoutingEngine e = cr;
    if (e != null) e.terminate();
  }

  private static DateFormat tsFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", new Locale("en", "US"));

  private static String formattedTimeStamp(long t) {
    synchronized (tsFormat) {
      return tsFormat.format(new Date(System.currentTimeMillis()));
    }
  }

  public void run() {
    BufferedReader br = null;
    BufferedWriter bw = null;

    // first line
    String getline = null;
    String sessionInfo = null;
    String sIp = null;

    try {
      br = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
      bw = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"));

      String agent = null;
      String encodings = null;
      String xff = null; // X-Forwarded-For

      // more headers until first empty line
      for (; ; ) {
        // headers
        String line = br.readLine();
        if (line == null) {
          writeHttpHeader(bw, HTTP_STATUS_BAD_REQUEST);
          bw.flush();
          return;
        }
        if (line.length() == 0) {
          break;
        }
        if (getline == null) {
          getline = line;
        }
        line = line.toLowerCase();
        if (line.startsWith("user-agent: ")) {
          agent = line.substring("user-agent: ".length());
        }
        if (line.startsWith("accept-encoding: ")) {
          encodings = line.substring("accept-encoding: ".length());
        }
        if (line.startsWith("x-forwarded-for: ")) {
          xff = line.substring("x-forwarded-for: ".length());
        }
      }

      InetAddress ip = clientSocket.getInetAddress();
      sIp = xff == null ? (ip == null ? "null" : ip.toString()) : xff;
      boolean newSession = IpAccessMonitor.touchIpAccess(sIp);
      sessionInfo = " new";
      if (!newSession) {
        int sessionCount = IpAccessMonitor.getSessionCount();
        sessionInfo = "    " + Math.min(sessionCount, 999);
        sessionInfo = sessionInfo.substring(sessionInfo.length() - 4);
      }

      String excludedAgents = System.getProperty("excludedAgents");
      if (agent != null && excludedAgents != null) {
        StringTokenizer tk = new StringTokenizer(excludedAgents, ",");
        while (tk.hasMoreTokens()) {
          if (agent.indexOf(tk.nextToken()) >= 0) {
            writeHttpHeader(bw, HTTP_STATUS_FORBIDDEN);
            bw.write("Bad agent: " + agent);
            bw.flush();
            return;
          }
        }
      }

      if (getline.startsWith("GET /favicon.ico")) {
        writeHttpHeader(bw, HTTP_STATUS_NOT_FOUND);
        bw.flush();
        return;
      }
      if (getline.startsWith("GET /robots.txt")) {
        writeHttpHeader(bw, HTTP_STATUS_OK);
        bw.write("User-agent: *\n");
        bw.write("Disallow: /\n");
        bw.flush();
        return;
      }

      String url = getline.split(" ")[1];

      RoutingParamCollector routingParamCollector = new RoutingParamCollector();
      Map<String, String> params = routingParamCollector.getUrlParams(url);

      long maxRunningTime = getMaxRunningTime();

      RequestHandler handler;
      if (params.containsKey("lonlats") && params.containsKey("profile")) {
        handler = new ServerHandler(serviceContext, params);
      } else if (url.startsWith(PROFILE_UPLOAD_URL)) {
        if (getline.startsWith("OPTIONS")) {
          // handle CORS preflight request (Safari)
          String corsHeaders = "Access-Control-Allow-Methods: GET, POST\n"
            + "Access-Control-Allow-Headers: Content-Type\n";
          writeHttpHeader(bw, "text/plain", null, corsHeaders, HTTP_STATUS_OK);
          bw.flush();
          return;
        } else {
          writeHttpHeader(bw, "application/json", HTTP_STATUS_OK);

          String profileId = null;
          if (url.length() > PROFILE_UPLOAD_URL.length() + 1) {
            // e.g. /brouter/profile/custom_1400767688382
            profileId = url.substring(PROFILE_UPLOAD_URL.length() + 1);
          }

          ProfileUploadHandler uploadHandler = new ProfileUploadHandler(serviceContext);
          uploadHandler.handlePostRequest(profileId, br, bw);

          bw.flush();
          return;
        }
      } else if (url.startsWith("/brouter/suspects")) {
        writeHttpHeader(bw, url.endsWith(".json") ? "application/json" : "text/html", HTTP_STATUS_OK);
        SuspectManager.process(url, bw);
        return;
      } else {
        writeHttpHeader(bw, HTTP_STATUS_NOT_FOUND);
        bw.flush();
        return;
      }
      RoutingContext rc = handler.readRoutingContext();
      List<OsmNodeNamed> wplist = routingParamCollector.getWayPointList(params.get("lonlats"));

      if (wplist.size() < 10) {
        SuspectManager.nearRecentWps.add(wplist);
      }
      if (params.containsKey("profile")) {
        // already handled in readRoutingContext
        params.remove("profile");
      }
      int engineMode = 0;
      if (params.containsKey("engineMode")) {
        engineMode = Integer.parseInt(params.get("engineMode"));
      }
      routingParamCollector.setParams(rc, wplist, params);

      cr = new RoutingEngine(null, null, serviceContext.segmentDir, wplist, rc, engineMode);
      cr.quite = true;
      cr.doRun(maxRunningTime);

      if (cr.getErrorMessage() != null) {
        writeHttpHeader(bw, HTTP_STATUS_BAD_REQUEST);
        bw.write(cr.getErrorMessage());
        bw.write("\n");
      } else {
        OsmTrack track = cr.getFoundTrack();

        if (engineMode == 2) {
          // no zip for this engineMode
          encodings = null;
        }
        String headers = encodings == null || encodings.indexOf("gzip") < 0 ? null : "Content-Encoding: gzip\n";
        writeHttpHeader(bw, handler.getMimeType(), handler.getFileName(), headers, HTTP_STATUS_OK);
        if (engineMode == 0) {
          if (track != null) {
            if (headers != null) { // compressed
              ByteArrayOutputStream baos = new ByteArrayOutputStream();
              Writer w = new OutputStreamWriter(new GZIPOutputStream(baos), "UTF-8");
              w.write(handler.formatTrack(track));
              w.close();
              bw.flush();
              clientSocket.getOutputStream().write(baos.toByteArray());
            } else {
              bw.write(handler.formatTrack(track));
            }
          }
        } else if (engineMode == 2) {
          String s = cr.getFoundInfo();
          if (s != null) {
            bw.write(s);
          }
        }
      }
      bw.flush();
    } catch (Throwable e) {
      try {
        writeHttpHeader(bw, HTTP_STATUS_INTERNAL_SERVER_ERROR);
        bw.flush();
      } catch (IOException _ignore) {
      }
      System.out.println("RouteServer got exception (will continue): " + e);
      e.printStackTrace();
    } finally {
      cr = null;
      if (br != null)
        try {
          br.close();
        } catch (Exception e) {
        }
      if (bw != null)
        try {
          bw.close();
        } catch (Exception e) {
        }
      if (clientSocket != null)
        try {
          clientSocket.close();
        } catch (Exception e) {
        }
      terminated = true;
      synchronized (threadPoolSync) {
        threadPoolSync.notifyAll();
      }
      long t = System.currentTimeMillis();
      long ms = t - starttime;
      System.out.println(formattedTimeStamp(t) + sessionInfo + " ip=" + sIp + " ms=" + ms + " -> " + getline);
    }
  }


  public static void main(String[] args) throws Exception {
    System.out.println("BRouter " + OsmTrack.version + " / " + OsmTrack.versionDate);
    if (args.length != 5 && args.length != 6) {
      System.out.println("serve BRouter protocol");
      System.out.println("usage: java RouteServer <segmentdir> <profiledir> <customprofiledir> <port> <maxthreads> [bindaddress]");
      return;
    }

    ServiceContext serviceContext = new ServiceContext();
    serviceContext.segmentDir = new File(args[0]);
    serviceContext.profileDir = args[1];
    System.setProperty("profileBaseDir", serviceContext.profileDir);
    String dirs = args[2];
    StringTokenizer tk = new StringTokenizer(dirs, ",");
    serviceContext.customProfileDir = tk.nextToken();
    serviceContext.sharedProfileDir = tk.hasMoreTokens() ? tk.nextToken() : serviceContext.customProfileDir;

    int maxthreads = Integer.parseInt(args[4]);

    ProfileCache.setSize(2 * maxthreads);

    Queue<RouteServer> threadQueue = new PriorityQueue<>();

    ServerSocket serverSocket = args.length > 5 ? new ServerSocket(Integer.parseInt(args[3]), 100, InetAddress.getByName(args[5])) : new ServerSocket(Integer.parseInt(args[3]));

    // stacksample for performance profiling
    // ( caution: start stacksampler only after successfully creating the server socket
    //   because that thread prevents the process from terminating, so the start-attempt
    //   by the watchdog cron would create zombies )
    File stackLog = new File("stacks.txt");
    if (stackLog.exists()) {
      StackSampler stackSampler = new StackSampler(stackLog, 1000);
      stackSampler.start();
      System.out.println("*** sampling stacks into stacks.txt *** ");
    }

    for (; ; ) {
      Socket clientSocket = serverSocket.accept();
      RouteServer server = new RouteServer();
      server.serviceContext = serviceContext;
      server.clientSocket = clientSocket;
      server.starttime = System.currentTimeMillis();

      // kill an old thread if thread limit reached

      cleanupThreadQueue(threadQueue);

      if (debug) System.out.println("threadQueue.size()=" + threadQueue.size());
      if (threadQueue.size() >= maxthreads) {
        synchronized (threadPoolSync) {
          // wait up to 2000ms (maybe notified earlier)
          // to prevent killing short-running threads
          long maxage = server.starttime - threadQueue.peek().starttime;
          long maxWaitTime = 2000L - maxage;
          if (debug) System.out.println("maxage=" + maxage + " maxWaitTime=" + maxWaitTime);
          if (debug) {
            for (RouteServer t : threadQueue) {
              System.out.println("age=" + (server.starttime - t.starttime));
            }
          }
          if (maxWaitTime > 0) {
            threadPoolSync.wait(maxWaitTime);
          }
          long t = System.currentTimeMillis();
          System.out.println(formattedTimeStamp(t) + " contention! ms waited " + (t - server.starttime));
        }
        cleanupThreadQueue(threadQueue);
        if (threadQueue.size() >= maxthreads) {
          if (debug) System.out.println("stopping oldest thread...");
          // no way... stop the oldest thread
          RouteServer oldest = threadQueue.poll();
          oldest.stopRouter();
          long t = System.currentTimeMillis();
          System.out.println(formattedTimeStamp(t) + " contention! ms killed " + (t - oldest.starttime));
        }
      }

      threadQueue.add(server);

      server.start();
      if (debug) System.out.println("thread started...");
    }
  }


  private static Map<String, String> getUrlParams(String url) throws UnsupportedEncodingException {
    Map<String, String> params = new HashMap<>();
    String decoded = URLDecoder.decode(url, "UTF-8");
    StringTokenizer tk = new StringTokenizer(decoded, "?&");
    while (tk.hasMoreTokens()) {
      String t = tk.nextToken();
      StringTokenizer tk2 = new StringTokenizer(t, "=");
      if (tk2.hasMoreTokens()) {
        String key = tk2.nextToken();
        if (tk2.hasMoreTokens()) {
          String value = tk2.nextToken();
          params.put(key, value);
        }
      }
    }
    return params;
  }

  private static long getMaxRunningTime() {
    long maxRunningTime = 60000;
    String sMaxRunningTime = System.getProperty("maxRunningTime");
    if (sMaxRunningTime != null) {
      maxRunningTime = Integer.parseInt(sMaxRunningTime) * 1000;
    }
    return maxRunningTime;
  }

  private static void writeHttpHeader(BufferedWriter bw, String status) throws IOException {
    writeHttpHeader(bw, "text/plain", status);
  }

  private static void writeHttpHeader(BufferedWriter bw, String mimeType, String status) throws IOException {
    writeHttpHeader(bw, mimeType, null, status);
  }

  private static void writeHttpHeader(BufferedWriter bw, String mimeType, String fileName, String status) throws IOException {
    writeHttpHeader(bw, mimeType, fileName, null, status);
  }

  private static void writeHttpHeader(BufferedWriter bw, String mimeType, String fileName, String headers, String status) throws IOException {
    // http-header
    bw.write(String.format("HTTP/1.1 %s\n", status));
    bw.write("Connection: close\n");
    bw.write("Content-Type: " + mimeType + "; charset=utf-8\n");
    if (fileName != null) {
      bw.write("Content-Disposition: attachment; filename=\"" + fileName + "\"\n");
    }
    bw.write("Access-Control-Allow-Origin: *\n");
    if (headers != null) {
      bw.write(headers);
    }
    bw.write("\n");
  }

  private static void cleanupThreadQueue(Queue<RouteServer> threadQueue) {
    for (; ; ) {
      boolean removedItem = false;
      for (RouteServer t : threadQueue) {
        if (t.terminated) {
          threadQueue.remove(t);
          removedItem = true;
          break;
        }
      }
      if (!removedItem) {
        break;
      }
    }
  }

  @Override
  public int compareTo(RouteServer t) {
    return starttime < t.starttime ? -1 : (starttime > t.starttime ? 1 : 0);
  }

}
