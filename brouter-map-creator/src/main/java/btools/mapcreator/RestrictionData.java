package btools.mapcreator;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import btools.mapaccess.TurnRestriction;
import btools.util.CheapAngleMeter;

/**
 * Container for a turn restriction
 *
 * @author ab
 */
public class RestrictionData extends TurnRestriction {
  public String restrictionKey;
  public String restriction;
  public long fromWid;
  public long toWid;
  public long viaNid;

  public int viaLon;
  public int viaLat;

  public boolean badWayMatch;

  private static final Map<String, String> names = new HashMap<>();
  private static final Set<Long> badTRs = new TreeSet<>();

  public RestrictionData() {
  }

  public TurnRestriction validate() {
    boolean valid = fromLon != 0 && toLon != 0 && (restriction.startsWith("only_") || restriction.startsWith("no_"));
    valid &= restriction.indexOf("on_red") < 0; // filter out on-red restrictions
    if ((!valid) || badWayMatch || !(checkGeometry())) {
      synchronized (badTRs) {
        badTRs.add(((long) viaLon) << 32 | viaLat);
      }
    }
    valid &= "restriction".equals(restrictionKey);
    if ( !valid ) {
      return null;
    }

    TurnRestriction tr = new TurnRestriction();
    tr.isPositive = restriction.startsWith("only_");;
    tr.exceptions = exceptions;
    tr.fromLon = fromLon;
    tr.fromLat = fromLat;
    tr.toLon = toLon;
    tr.toLat = toLat;
    return tr;
  }

  private boolean checkGeometry() {
    double a = (new CheapAngleMeter()).calcAngle(fromLon, fromLat, viaLon, viaLat, toLon, toLat);
    String t;
    if (restriction.startsWith("only_")) {
      t = restriction.substring("only_".length());
    } else if (restriction.startsWith("no_")) {
      t = restriction.substring("no_".length());
    } else throw new RuntimeException("ups");

    if (restrictionKey.endsWith(":conditional")) {
      int idx = t.indexOf('@');
      if (idx >= 0) {
        t = t.substring(0, idx).trim();
      }
    }

    if ("left_turn".equals(t)) {
      return a < -5. && a > -175.;
    }
    if ("right_turn".equals(t)) {
      return a > 5. && a < 175.;
    }
    if ("straight_on".equals(t)) {
      return a > -85. && a < 85.;
    }
    if ("u_turn".equals(t)) {
      return a < -95. || a > 95.;
    }
    return "entry".equals(t) || "exit".equals(t);
  }

  private static String unifyName(String name) {
    synchronized (names) {
      String n = names.get(name);
      if (n == null) {
        names.put(name, name);
        n = name;
      }
      return n;
    }
  }

  public static void dumpBadTRs() {
    try (BufferedWriter bw = new BufferedWriter(new FileWriter("badtrs.txt"))) {
      for (Long id : badTRs) {
        bw.write("" + id + " 26\n");
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  public RestrictionData(DataInputStream di) throws Exception {
    restrictionKey = unifyName(di.readUTF());
    restriction = unifyName(di.readUTF());
    exceptions = di.readShort();
    fromWid = di.readLong();
    toWid = di.readLong();
    viaNid = di.readLong();
  }

  public void writeTo(DataOutputStream dos) throws Exception {
    dos.writeUTF(restrictionKey);
    dos.writeUTF(restriction);
    dos.writeShort(exceptions);
    dos.writeLong(fromWid);
    dos.writeLong(toWid);
    dos.writeLong(viaNid);
  }
}
