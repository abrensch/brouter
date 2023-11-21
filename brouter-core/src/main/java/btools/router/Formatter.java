package btools.router;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public abstract class Formatter {

  static final String MESSAGES_HEADER = "Longitude\tLatitude\tElevation\tDistance\tCostPerKm\tElevCost\tTurnCost\tNodeCost\tInitialCost\tWayTags\tNodeTags\tTime\tEnergy";

  RoutingContext rc;

  Formatter() {
  }

  Formatter(RoutingContext rc) {
    this.rc = rc;
  }

  /**
   * writes the track in gpx-format to a file
   *
   * @param filename the filename to write to
   * @param t        the track to write
   */
  public void write(String filename, OsmTrack t) throws Exception {
    BufferedWriter bw = new BufferedWriter(new FileWriter(filename));
    bw.write(format(t));
    bw.close();
  }

  public OsmTrack read(String filename) throws Exception {
    return null;
  }

  /**
   * writes the track in a selected output format to a string
   *
   * @param t the track to format
   * @return the formatted string
   */
  public abstract String format(OsmTrack t);


  static String formatILon(int ilon) {
    return formatPos(ilon - 180000000);
  }

  static String formatILat(int ilat) {
    return formatPos(ilat - 90000000);
  }

  private static String formatPos(int p) {
    boolean negative = p < 0;
    if (negative)
      p = -p;
    char[] ac = new char[12];
    int i = 11;
    while (p != 0 || i > 3) {
      ac[i--] = (char) ('0' + (p % 10));
      p /= 10;
      if (i == 5)
        ac[i--] = '.';
    }
    if (negative)
      ac[i--] = '-';
    return new String(ac, i + 1, 11 - i);
  }

  public static String getFormattedTime2(int s) {
    int seconds = (int) (s + 0.5);
    int hours = seconds / 3600;
    int minutes = (seconds - hours * 3600) / 60;
    seconds = seconds - hours * 3600 - minutes * 60;
    String time = "";
    if (hours != 0)
      time = "" + hours + "h ";
    if (minutes != 0)
      time = time + minutes + "m ";
    if (seconds != 0)
      time = time + seconds + "s";
    return time;
  }

  static public String getFormattedEnergy(int energy) {
    return format1(energy / 3600000.) + "kwh";
  }

  static private String format1(double n) {
    String s = "" + (long) (n * 10 + 0.5);
    int len = s.length();
    return s.substring(0, len - 1) + "." + s.charAt(len - 1);
  }


  static final String dateformat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

  static public String getFormattedTime3(float time) {
    SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat(dateformat, Locale.US);
    TIMESTAMP_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    // yyyy-mm-ddThh:mm:ss.SSSZ
    Date d = new Date((long) (time * 1000f));
    return TIMESTAMP_FORMAT.format(d);
  }


}
