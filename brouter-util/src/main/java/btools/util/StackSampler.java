package btools.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class StackSampler extends Thread {
  private DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", new Locale("en", "US"));
  private BufferedWriter bw;
  private Random rand = new Random();

  private int interval;
  private int flushCnt = 0;

  private volatile boolean stopped;

  public StackSampler(File logfile, int interval) {
    this.interval = interval;
    try {
      bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logfile, true)));
    } catch (Exception e) {
      printError("StackSampler: " + e.getMessage());
    }
  }

  protected void printError(String msg) {
    System.out.println(msg);
  }

  @Override
  public void run() {
    while (!stopped) {
      dumpThreads();
    }
    if (bw != null) {
      try {
        bw.close();
      } catch (Exception e) {
      }
    }
  }

  public void dumpThreads() {
    try {
      int wait1 = rand.nextInt(interval);
      int wait2 = interval - wait1;
      Thread.sleep(wait1);
      StringBuilder sb = new StringBuilder(df.format(new Date()) + " THREADDUMP\n");
      Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();
      for (Map.Entry<Thread, StackTraceElement[]> e : allThreads.entrySet()) {
        Thread t = e.getKey();
        if (t == Thread.currentThread()) {
          continue; // not me
        }

        StackTraceElement[] stack = e.getValue();
        if (!matchesFilter(stack)) {
          continue;
        }

        sb.append(" (ID=").append(t.getId()).append(" \"").append(t.getName()).append("\" ").append(t.getState()).append("\n");
        for (StackTraceElement line : stack) {
          sb.append("    ").append(line.toString()).append("\n");
        }
        sb.append("\n");
      }
      bw.write(sb.toString());
      if (flushCnt++ >= 0) {
        flushCnt = 0;
        bw.flush();
      }
      Thread.sleep(wait2);
    } catch (Exception e) {
      // ignore
    }
  }

  public void close() {
    stopped = true;
    interrupt();
  }

  private boolean matchesFilter(StackTraceElement[] stack) {
    boolean positiveMatch = false;
    for (StackTraceElement e : stack) {
      String s = e.toString();
      if (s.indexOf("btools") >= 0) {
        positiveMatch = true;
      }
      if (s.indexOf("Thread.sleep") >= 0 || s.indexOf("PlainSocketImpl.socketAccept") >= 0) {
        return false;
      }
    }
    return positiveMatch;
  }

  public static void main(String[] args) throws Exception {
    System.out.println("StackSampler...");
    Class<?> clazz = Class.forName(args[0]);
    String[] args2 = new String[args.length - 1];
    for (int i = 1; i < args.length; i++) {
      args2[i - 1] = args[i];
    }
    StackSampler t = new StackSampler(new File("stacks.log"), 1000);
    t.start();
    try {
      clazz.getMethod("main", String[].class).invoke(null, new Object[]{args2});
    } finally {
      t.close();
    }
  }
}
