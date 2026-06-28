package btools.router;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static btools.router.VoiceHint.BL;
import static btools.router.VoiceHint.C;
import static btools.router.VoiceHint.EL;
import static btools.router.VoiceHint.END;
import static btools.router.VoiceHint.ER;
import static btools.router.VoiceHint.KL;
import static btools.router.VoiceHint.KR;
import static btools.router.VoiceHint.OFFR;
import static btools.router.VoiceHint.RNDB;
import static btools.router.VoiceHint.RNLB;
import static btools.router.VoiceHint.TL;
import static btools.router.VoiceHint.TLU;
import static btools.router.VoiceHint.TR;
import static btools.router.VoiceHint.TRU;
import static btools.router.VoiceHint.TSHL;
import static btools.router.VoiceHint.TSHR;
import static btools.router.VoiceHint.TSLL;
import static btools.router.VoiceHint.TSLR;
import static btools.router.VoiceHint.TU;

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

  public int getJsonCommandIndex(int cmd, int timode) {
    switch (cmd) {
      case TLU:
        return 10;
      case TU:
        return 15;
      case TSHL:
        return 4;
      case TL:
        return 2;
      case TSLL:
        return 3;
      case KL:
        return 8;
      case C:
        return 1;
      case KR:
        return 9;
      case TSLR:
        return 6;
      case TR:
        return 5;
      case TSHR:
        return 7;
      case TRU:
        return 11;
      case RNDB:
        return 13;
      case RNLB:
        return 14;
      case BL:
        return 16;
      case EL:
        return timode == 2 || timode == 9 ? 17 : 8;
      case ER:
        return timode == 2 || timode == 9 ? 18 : 9;
      case OFFR:
        return 12;
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by comment style, osmand style
   */
  public String getCommandString(int cmd, int roundaboutExit, int timode) {
    switch (cmd) {
      case TLU:
        return "TU";  // should be changed to TLU when osmand uses new voice hint constants
      case TU:
        return "TU";
      case TSHL:
        return "TSHL";
      case TL:
        return "TL";
      case TSLL:
        return "TSLL";
      case KL:
        return "KL";
      case C:
        return "C";
      case KR:
        return "KR";
      case TSLR:
        return "TSLR";
      case TR:
        return "TR";
      case TSHR:
        return "TSHR";
      case TRU:
        return "TRU";
      case RNDB:
        return "RNDB" + roundaboutExit;
      case RNLB:
        return "RNLB" + (-roundaboutExit);
      case BL:
        return "BL";
      case EL:
        return timode == 2 || timode == 9 ? "EL" : "KL";
      case ER:
        return timode == 2 || timode == 9 ? "ER" : "KR";
      case OFFR:
        return "OFFR";
      case END:
        return "END";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by trkpt/sym style
   */
  public String getCommandStringXX(int c, int roundaboutExit, int timode) {
    switch (c) {
      case TLU:
        return "TLU";
      case TU:
        return "TU";
      case TSHL:
        return "TSHL";
      case TL:
        return "TL";
      case TSLL:
        return "TSLL";
      case KL:
        return "KL";
      case C:
        return "C";
      case KR:
        return "KR";
      case TSLR:
        return "TSLR";
      case TR:
        return "TR";
      case TSHR:
        return "TSHR";
      case TRU:
        return "TRU";
      case RNDB:
        return "RNDB" + roundaboutExit;
      case RNLB:
        return "RNLB" + (-roundaboutExit);
      case BL:
        return "BL";
      case EL:
        return timode == 2 || timode == 9 ? "EL" : "KL";
      case ER:
        return timode == 2 || timode == 9 ? "ER" : "KR";
      case OFFR:
        return "OFFR";
      default:
        return "unknown command: " + c;
    }
  }

  /*
   * used by gpsies style
   */
  public String getSymbolString(int cmd, int roundaboutExit, int timode) {
    switch (cmd) {
      case TLU:
      case TRU:
      case TU:
        return "TU";
      case TSHL:
        return "TSHL";
      case TL:
        return "Left";
      case TSLL:
        return "TSLL";
      case KL:
        return "TSLL"; // ?
      case C:
        return "Straight";
      case KR:
        return "TSLR"; // ?
      case TSLR:
        return "TSLR";
      case TR:
        return "Right";
      case TSHR:
        return "TSHR";
      case RNDB:
        return "RNDB" + roundaboutExit;
      case RNLB:
        return "RNLB" + (-roundaboutExit);
      case BL:
        return "BL";
      case EL:
        return timode == 2 || timode == 9 ? "EL" : "KL";
      case ER:
        return timode == 2 || timode == 9 ? "ER" : "KR";
      case OFFR:
        return "OFFR";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by new locus trkpt style
   */
  public String getLocusSymbolString(int cmd, int roundaboutExit) {
    switch (cmd) {
      case TLU:
        return "u-turn_left";
      case TU:
        return "u-turn";
      case TSHL:
        return "left_sharp";
      case TL:
        return "left";
      case TSLL:
        return "left_slight";
      case KL:
        return "stay_left"; // ?
      case C:
        return "straight";
      case KR:
        return "stay_right"; // ?
      case TSLR:
        return "right_slight";
      case TR:
        return "right";
      case TSHR:
        return "right_sharp";
      case TRU:
        return "u-turn_right";
      case RNDB:
        return "roundabout_e" + roundaboutExit;
      case RNLB:
        return "roundabout_e" + (-roundaboutExit);
      case BL:
        return "beeline";
      case EL:
        return "exit_left";
      case ER:
        return "exit_right";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by osmand style
   */
  public String getMessageString(int cmd, int roundaboutExit, int timode) {
    switch (cmd) {
      case TLU:
        return "u-turn"; // should be changed to u-turn-left when osmand uses new voice hint constants
      case TU:
        return "u-turn";
      case TSHL:
        return "sharp left";
      case TL:
        return "left";
      case TSLL:
        return "slight left";
      case KL:
        return "keep left";
      case C:
        return "straight";
      case KR:
        return "keep right";
      case TSLR:
        return "slight right";
      case TR:
        return "right";
      case TSHR:
        return "sharp right";
      case TRU:
        return "u-turn";  // should be changed to u-turn-right when osmand uses new voice hint constants
      case RNDB:
        return "Take exit " + roundaboutExit;
      case RNLB:
        return "Take exit " + (-roundaboutExit);
      case EL:
        return timode == 2 || timode == 9 ? "exit left" : "keep left";
      case ER:
        return timode == 2 || timode == 9 ? "exit right" : "keep right";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by old locus style
   */
  public int getLocusAction(int cmd, int roundaboutExit) {
    switch (cmd) {
      case TLU:
        return 13;
      case TU:
        return 12;
      case TSHL:
        return 5;
      case TL:
        return 4;
      case TSLL:
        return 3;
      case KL:
        return 9; // ?
      case C:
        return 1;
      case KR:
        return 10; // ?
      case TSLR:
        return 6;
      case TR:
        return 7;
      case TSHR:
        return 8;
      case TRU:
        return 14;
      case RNDB:
        return 26 + roundaboutExit;
      case RNLB:
        return 26 - roundaboutExit;
      case EL:
        return 9;
      case ER:
        return 10;
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by orux style
   */
  public int getOruxAction(int cmd, int roundaboutExit) {
    switch (cmd) {
      case TLU:
      case TU:
      case TRU:
        return 1003;
      case TSHL:
        return 1019;
      case TL:
        return 1000;
      case TSLL:
        return 1017;
      case KL:
        return 1015; // ?
      case C:
        return 1002;
      case KR:
        return 1014; // ?
      case TSLR:
        return 1016;
      case TR:
        return 1001;
      case TSHR:
        return 1018;
      case RNDB:
      case RNLB:
        return 1008 + roundaboutExit;
      case EL:
        return 1015;
      case ER:
        return 1014;
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by cruiser, equivalent to getCommandString() - osmand style - when osmand changes the voice hint  constants
   */
  public String getCruiserCommandString(int cmd, int roundaboutExit) {
    switch (cmd) {
      case TLU:
        return "TLU";
      case TU:
        return "TU";
      case TSHL:
        return "TSHL";
      case TL:
        return "TL";
      case TSLL:
        return "TSLL";
      case KL:
        return "KL";
      case C:
        return "C";
      case KR:
        return "KR";
      case TSLR:
        return "TSLR";
      case TR:
        return "TR";
      case TSHR:
        return "TSHR";
      case TRU:
        return "TRU";
      case RNDB:
        return "RNDB" + roundaboutExit;
      case RNLB:
        return "RNLB" + (-roundaboutExit);
      case BL:
        return "BL";
      case EL:
        return "EL";
      case ER:
        return "ER";
      case OFFR:
        return "OFFR";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by cruiser, equivalent to getMessageString() - osmand style - when osmand changes the voice hint  constants
   */
  public String getCruiserMessageString(int cmd, int roundaboutExit) {
    switch (cmd) {
      case TLU:
        return "u-turn left";
      case TU:
        return "u-turn";
      case TSHL:
        return "sharp left";
      case TL:
        return "left";
      case TSLL:
        return "slight left";
      case KL:
        return "keep left";
      case C:
        return "straight";
      case KR:
        return "keep right";
      case TSLR:
        return "slight right";
      case TR:
        return "right";
      case TSHR:
        return "sharp right";
      case TRU:
        return "u-turn right";
      case RNDB:
        return "take exit " + roundaboutExit;
      case RNLB:
        return "take exit " + (-roundaboutExit);
      case BL:
        return "beeline";
      case EL:
        return "exit left";
      case ER:
        return "exit right";
      case OFFR:
        return "offroad";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

}
