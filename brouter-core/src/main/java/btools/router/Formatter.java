package btools.router;

import java.io.BufferedWriter;
import java.io.FileWriter;

public abstract class Formatter {
  private static final int OUTPUT_FORMAT_GPX = 0;
  private static final int OUTPUT_FORMAT_KML = 1;
  private static final int OUTPUT_FORMAT_JSON = 2;
  private static final int OUTPUT_FORMAT_CSV = 3;

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

}
