package btools.mapcreator;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import btools.mapaccess.TurnRestriction;
import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;

/**
 * Container for a turn restriction
 *
 * @author ab
 */
public class RestrictionData extends TurnRestriction {

  public static final long TYPE = 5L;

  public String restrictionKey;
  public String restriction;
  public long fromWid;
  public long toWid;
  public long viaNid;

  public int viaLon;
  public int viaLat;

  private static final Map<String, String> names = new HashMap<>();

  public RestrictionData() {
  }

  public TurnRestriction validate() {
    isPositive = restriction.startsWith("only_");

    boolean valid = fromLon != 0 && toLon != 0 && (restriction.startsWith("only_") || restriction.startsWith("no_"));
    valid &= restriction.indexOf("on_red") < 0; // filter out on-red restrictions
    boolean trivialNoUturn  = !isPositive && fromLon == toLon && fromLat == toLat;
    valid &= !trivialNoUturn; // don't need direct no-uturns, they are implicit

    valid &= "restriction".equals(restrictionKey);
    if ( !valid ) {
      return null;
    }
    return createCopy();
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

  public RestrictionData(BitInputStream bis) throws IOException {
    restrictionKey = unifyName(bis.readUTF());
    restriction = unifyName(bis.readUTF());
    exceptions = bis.readShort();
    fromWid = bis.readLong();
    toWid = bis.readLong();
    viaNid = bis.readLong();
  }

  public void writeTo(BitOutputStream bos) throws IOException {
    bos.encodeVarBytes(TYPE);
    bos.writeUTF(restrictionKey);
    bos.writeUTF(restriction);
    bos.writeShort(exceptions);
    bos.writeLong(fromWid);
    bos.writeLong(toWid);
    bos.writeLong(viaNid);
  }
}
