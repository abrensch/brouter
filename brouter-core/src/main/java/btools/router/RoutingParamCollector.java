package btools.router;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

public class RoutingParamCollector {

  /**
   * get a list of points and optional extra info for the points
   *
   * @param lonLats  linked list separated by ';' or '|'
   * @return         a list
   */
  public List<GraphNodeNamed> getWayPointList(String lonLats) {
    if (lonLats == null) throw new IllegalArgumentException("lonlats parameter not set");

    String[] coords = lonLats.split(";|\\|"); // use both variantes
    if (coords.length < 1 || !coords[0].contains(","))
      throw new IllegalArgumentException("we need one lat/lon point at least!");

    List<GraphNodeNamed> wplist = new ArrayList<>();
    for (int i = 0; i < coords.length; i++) {
      String[] lonLat = coords[i].split(",");
      if (lonLat.length < 1)
        throw new IllegalArgumentException("we need one lat/lon point at least!");
      wplist.add(readPosition(lonLat[0], lonLat[1], "via" + i));
      if (lonLat.length > 2) {
        wplist.get(wplist.size() - 1).name = lonLat[2];
      }
    }

    if (wplist.get(0).name.startsWith("via")) wplist.get(0).name = "from";
    if (wplist.get(wplist.size() - 1).name.startsWith("via")) {
      wplist.get(wplist.size() - 1).name = "to";
    }

    return wplist;
  }

  /**
   * get a list of points (old style, positions only)
   *
   * @param lons  array with longitudes
   * @param lats  array with latitudes
   * @return      a list
   */
  public List<GraphNodeNamed> readPositions(double[] lons, double[] lats) {
    List<GraphNodeNamed> wplist = new ArrayList<>();

    if (lats == null || lats.length < 2 || lons == null || lons.length < 2) {
      return wplist;
    }

    for (int i = 0; i < lats.length && i < lons.length; i++) {
      GraphNodeNamed n = new GraphNodeNamed();
      n.name = "via" + i;
      n.iLon = (int) ((lons[i] + 180.) * 1000000. + 0.5);
      n.iLat = (int) ((lats[i] + 90.) * 1000000. + 0.5);
      wplist.add(n);
    }

    if (wplist.get(0).name.startsWith("via")) wplist.get(0).name = "from";
    if (wplist.get(wplist.size() - 1).name.startsWith("via")) {
      wplist.get(wplist.size() - 1).name = "to";
    }

    return wplist;
  }

  private GraphNodeNamed readPosition(String vlon, String vlat, String name) {
    if (vlon == null) throw new IllegalArgumentException("lon " + name + " not found in input");
    if (vlat == null) throw new IllegalArgumentException("lat " + name + " not found in input");

    return readPosition(Double.parseDouble(vlon), Double.parseDouble(vlat), name);
  }

  private GraphNodeNamed readPosition(double lon, double lat, String name) {
    GraphNodeNamed n = new GraphNodeNamed();
    n.name = name;
    n.iLon = (int) ((lon + 180.) * 1000000. + 0.5);
    n.iLat = (int) ((lat + 90.) * 1000000. + 0.5);
    return n;
  }

  /**
   * read a url like parameter list linked with '&'
   *
   * @param url  parameter list
   * @return     a hashmap of the parameter
   * @throws     UnsupportedEncodingException
   */
  public Map<String, String> getUrlParams(String url) throws UnsupportedEncodingException {
    HashMap<String, String> params = new HashMap<>();
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

  /**
   * fill a parameter map into the routing context
   *
   * @param rctx    the context
   * @param wplist  the list of way points needed for 'straight' parameter
   * @param params  the list of parameters
   */
  public void setParams(RoutingContext rctx, List<GraphNodeNamed> wplist, Map<String, String> params) {
    if (params != null) {
      if (params.size() == 0) return;

      // prepare nogos extra
      if (params.containsKey("profile")) {
        rctx.profileName = params.get("profile" );
      }
      if (params.containsKey("nogoLats") && params.get("nogoLats").length() > 0) {
        List<GraphNodeNamed> nogoList = readNogos(params.get("nogoLons"), params.get("nogoLats"), params.get("nogoRadi"));
        if (nogoList != null) {
          RoutingContext.prepareNogoPoints(nogoList);
          if (rctx.nogopoints == null) {
            rctx.nogopoints = nogoList;
          } else {
            rctx.nogopoints.addAll(nogoList);
          }
        }
        params.remove("nogoLats");
        params.remove("nogoLons");
        params.remove("nogoRadi");
      }
      if (params.containsKey("nogos")) {
        List<GraphNodeNamed> nogoList = readNogoList(params.get("nogos"));
        if (nogoList != null) {
          RoutingContext.prepareNogoPoints(nogoList);
          if (rctx.nogopoints == null) {
            rctx.nogopoints = nogoList;
          } else {
            rctx.nogopoints.addAll(nogoList);
          }
        }
        params.remove("nogos");
      }

      for (Map.Entry<String, String> e : params.entrySet()) {
        String key = e.getKey();
        String value = e.getValue();

        if (key.equals("alternativeidx")) {
          rctx.setAlternativeIdx(Integer.parseInt(value));
        } else if (key.equals("turnInstructionMode")) {
          rctx.turnInstructionMode = Integer.parseInt(value);
        } else if (key.equals("timode")) {
          rctx.turnInstructionMode = Integer.parseInt(value);
        } else if (key.equals("turnInstructionFormat")) {
          if ("osmand".equalsIgnoreCase(value)) {
            rctx.turnInstructionMode = 3;
          } else if ("locus".equalsIgnoreCase(value)) {
            rctx.turnInstructionMode = 2;
          }
        } else if (key.equals("format")) {
          rctx.outputFormat = ((String) value).toLowerCase();
        } else if (key.equals("trackFormat")) {
          rctx.outputFormat = ((String) value).toLowerCase();
        } else if (key.startsWith("profile:")) {
          if (rctx.keyValues == null) rctx.keyValues = new HashMap<>();
          rctx.keyValues.put(key.substring(8), value);
        }
        // ignore other params
      }
    }
  }

  public List<GraphNodeNamed> readNogoList(String nogos) {
    // lon,lat,radius|...

    if (nogos == null) return null;

    String[] lonLatRadList = nogos.split("\\|");

    List<GraphNodeNamed> nogoList = new ArrayList<>();
    for (int i = 0; i < lonLatRadList.length; i++) {
      String[] lonLatRad = lonLatRadList[i].split(",");
      nogoList.add(readNogo(lonLatRad[0], lonLatRad[1], lonLatRad[2]));
    }

    return nogoList;
  }

  public List<GraphNodeNamed> readNogos(String nogoLons, String nogoLats, String nogoRadi) {
    if (nogoLons == null || nogoLats == null || nogoRadi == null) return null;
    List<GraphNodeNamed> nogoList = new ArrayList<>();

    String[] lons = nogoLons.split(",");
    String[] lats = nogoLats.split(",");
    String[] radi = nogoRadi.split(",");
    for (int i = 0; i < lons.length && i < lats.length && i < radi.length; i++) {
      GraphNodeNamed n = readNogo(lons[i].trim(), lats[i].trim(), radi[i].trim() );
      nogoList.add(n);
    }
    return nogoList;
  }


  private GraphNodeNamed readNogo(String lon, String lat, String radius) {
    return readNogo(Double.parseDouble(lon), Double.parseDouble(lat), (int) Double.parseDouble(radius) );
  }

  private GraphNodeNamed readNogo(double lon, double lat, int radius) {
    GraphNodeNamed n = new GraphNodeNamed();
    n.name = "nogo" + radius;
    n.iLon = (int) ((lon + 180.) * 1000000. + 0.5);
    n.iLat = (int) ((lat + 90.) * 1000000. + 0.5);
    n.isNogo = true;
    return n;
  }


}
