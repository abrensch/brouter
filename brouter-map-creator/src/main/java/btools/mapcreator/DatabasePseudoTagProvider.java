/**
 * DatabasePseudoTagProvider reads Pseudo Tags from a database and adds them
 * to the osm-data
 */
package btools.mapcreator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;

public class DatabasePseudoTagProvider {

  static final int IMPORT_TYPE_START = -1;
  static final int IMPORT_TYPE_NONE = 0;
  static final int IMPORT_TYPE_NODES = 1;
  static final int IMPORT_TYPE_WAYS = 2;

  private long cntOsmWays = 0L;
  private long cntWayModified = 0L;

  private long cntOsmNodes = 0L;
  private long cntNodesModified = 0L;

  private Map<String, Long> pseudoTagsFound = new HashMap<>();

  FrozenLongMap<Map<String, String>> dbWayData;
  FrozenLongMap<Map<String, String>> dbNodeData;

  public static void main(String[] args) {
    String jdbcurl = args[0];
    String filename = args[1];

    try (Connection conn = DriverManager.getConnection(jdbcurl);
         BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
           filename.endsWith(".gz") ? new GZIPOutputStream(new FileOutputStream(filename)) : new FileOutputStream(filename)))) {

      conn.setAutoCommit(false);

      System.out.println("DatabasePseudoTagProvider dumping data from database to file " + filename);

      String sql_node_tags = "select node_id, crossing_class from crossing_tags";
      try(PreparedStatement psAllTags = conn.prepareStatement(sql_node_tags)) {

        bw.write("#####nodetags#####\n");
        bw.write("node_id;crossing_class\n");

        psAllTags.setFetchSize(100);

        // process the results
        ResultSet rs = psAllTags.executeQuery();

        long dbRows = 0L;
        while (rs.next()) {
          StringBuilder line = new StringBuilder();
          line.append(rs.getLong("node_id"));
          appendDBTag(line, rs, "crossing_class");
          line.append('\n');
          bw.write(line.toString());
          dbRows++;
        }
        System.out.println(".. from database: node tag rows = " + dbRows);
      }

      String sql_all_tags = "SELECT * from all_tags";
      try(PreparedStatement psAllTags = conn.prepareStatement(sql_all_tags)) {

        bw.write("#####waytags#####\n");
        bw.write("losmid;noise_class;river_class;forest_class;town_class;traffic_class\n");

        psAllTags.setFetchSize(100);

        // process the results
        ResultSet rs = psAllTags.executeQuery();

        long dbRows = 0L;
        while (rs.next()) {
          StringBuilder line = new StringBuilder();
          line.append(rs.getLong("losmid"));
          appendDBTag(line, rs, "noise_class");
          appendDBTag(line, rs, "river_class");
          appendDBTag(line, rs, "forest_class");
          appendDBTag(line, rs, "town_class");
          appendDBTag(line, rs, "traffic_class");
          line.append('\n');
          bw.write(line.toString());
          dbRows++;
        }
        System.out.println(".. from database: way tag rows = " + dbRows);
      }
    } catch (SQLException g) {
      System.err.format("DatabasePseudoTagProvider execute sql .. SQL State: %s\n%s\n", g.getSQLState(), g.getMessage());
      System.exit(1);
    } catch (Exception f) {
      f.printStackTrace();
      System.exit(1);
    }
  }

  private static void appendDBTag(StringBuilder sb, ResultSet rs, String name) throws SQLException {
    sb.append(';');
    String v = rs.getString(name);
    if (v != null) {
      sb.append(v);
    }
  }

  public DatabasePseudoTagProvider(String filename, String jdbcurl) {
    if (filename != null) doFileImport(filename);
    if (jdbcurl != null) doDatabaseImport(jdbcurl);
  }

  private void doDatabaseImport(String jdbcurl) {

    try (Connection conn = DriverManager.getConnection(jdbcurl)) {

      System.out.println("DatabasePseudoTagProvider reading from database");
      conn.setAutoCommit(false);


      Map<Map<String, String>, Map<String, String>> mapUnifier = new HashMap<>();


      String sql_all_waytags = "SELECT * from all_tags";
      try (PreparedStatement psAllTags = conn.prepareStatement(sql_all_waytags)) {

        psAllTags.setFetchSize(100);

        CompactLongMap<Map<String, String>> data = new CompactLongMap<>();

        // process the results
        ResultSet rs = psAllTags.executeQuery();

        long dbRows = 0L;
        while (rs.next()) {
          long osm_id = rs.getLong("losmid");
          Map<String, String> row = new HashMap<>(5);
          addDBTag(row, rs, "noise_class");
          addDBTag(row, rs, "river_class");
          addDBTag(row, rs, "forest_class");
          addDBTag(row, rs, "town_class");
          addDBTag(row, rs, "traffic_class");

          // apply the instance-unifier for the row-map
          Map<String, String> knownRow = mapUnifier.get(row);
          if (knownRow != null) {
            row = knownRow;
          } else {
            mapUnifier.put(row, row);
          }
          data.fastPut(osm_id, row);
          dbRows++;
        }
        dbWayData = new FrozenLongMap<>(data);
        System.out.println("read from database: way rows =" + dbWayData.size() + " unique rows=" + mapUnifier.size());
      }

      String sql_all_nodetags = "SELECT node_id, crossing_class from crossing_tags";
      try (PreparedStatement psAllTags = conn.prepareStatement(sql_all_nodetags)) {

        psAllTags.setFetchSize(100);

        mapUnifier.clear();
        CompactLongMap<Map<String, String>> data = new CompactLongMap<>();

        // process the results
        ResultSet rs = psAllTags.executeQuery();

        long dbRows = 0L;
        while (rs.next()) {
          long osm_id = rs.getLong("node_id");
          Map<String, String> row = new HashMap<>();
          addDBTag(row, rs, "crossing_class");

          // apply the instance-unifier for the row-map
          Map<String, String> knownRow = mapUnifier.get(row);
          if (knownRow != null) {
            row = knownRow;
          } else {
            mapUnifier.put(row, row);
          }
          data.fastPut(osm_id, row);
          dbRows++;
        }
        dbNodeData = new FrozenLongMap<>(data);
        System.out.println("read from database: node rows =" + dbNodeData.size() + " unique rows=" + mapUnifier.size());
      }


    } catch (SQLException g) {
      System.err.format("DatabasePseudoTagProvider execute sql .. SQL State: %s\n%s\n", g.getSQLState(), g.getMessage());
      System.exit(1);
    } catch (Exception f) {
      f.printStackTrace();
      System.exit(1);
    }

  }

  private void doFileImport(String filename) {

    try (BufferedReader br = new BufferedReader(new InputStreamReader(
           filename.endsWith(".gz") ? new GZIPInputStream(new FileInputStream(filename)) : new FileInputStream(filename)))) {

      System.out.println("DatabasePseudoTagProvider reading from file: " + filename);


      Map<Map<String, String>, Map<String, String>> mapUnifier = new HashMap<>();
      CompactLongMap<Map<String, String>> data = null; // = new CompactLongMap<>();

      int importType = IMPORT_TYPE_NONE;
      int lastImportType = IMPORT_TYPE_START;

      long dbRows = 0L;
      for (;;) {
        String line = br.readLine();
        if (line == null) {
          break;
        }
        if (line.equals("#####nodetags#####")) {
          importType = IMPORT_TYPE_NODES;
          continue;
        }
        if (line.equals("#####waytags#####")) {
          importType = IMPORT_TYPE_WAYS;
          continue;
        }

        if (importType != lastImportType) {
          if (lastImportType == IMPORT_TYPE_START) {
            // prepare data new input
            data = new CompactLongMap<>();
            dbRows = 0;
          }
          if (lastImportType == IMPORT_TYPE_NODES) {
            dbNodeData = new FrozenLongMap<>(data);
            System.out.println("read from file: node rows =" + dbNodeData.size() + " unique rows=" + mapUnifier.size());
            // prepare data new input
            data = new CompactLongMap<>();
            dbRows = 0;
          }
          else if (lastImportType == IMPORT_TYPE_WAYS) {
            dbWayData = new FrozenLongMap<>(data);
            System.out.println("read from file: way rows =" + dbWayData.size() + " unique rows=" + mapUnifier.size());
          }
          lastImportType = importType;
        }

        if (importType == IMPORT_TYPE_NODES) {
          List<String> tokens = tokenize(line);
          long osm_id = -1L;
          try {
            osm_id = Long.parseLong(tokens.get(0));
          } catch (NumberFormatException e) {
            // ignore could be a header
            continue;
          }
          if (osm_id == -1L) continue;
          Map<String, String> row = new HashMap<>(2);
          addTag(row, tokens.get(1), "estimated_crossing_class");

          // apply the instance-unifier for the row-map
          Map<String, String> knownRow = mapUnifier.get(row);
          if (knownRow != null) {
            row = knownRow;
          } else {
            mapUnifier.put(row, row);
          }
          data.fastPut(osm_id, row);
          dbRows++;

        }

        if (importType == IMPORT_TYPE_WAYS || importType == IMPORT_TYPE_NONE) {
          List<String> tokens = tokenize(line);
          long osm_id = -1L;
          try {
            osm_id = Long.parseLong(tokens.get(0));
          } catch (NumberFormatException e) {
            // ignore could be a header
            continue;
          }
          if (osm_id == -1L) continue;
          Map<String, String> row = new HashMap<>(5);
          addTag(row, tokens.get(1), "estimated_noise_class");
          addTag(row, tokens.get(2), "estimated_river_class");
          addTag(row, tokens.get(3), "estimated_forest_class");
          addTag(row, tokens.get(4), "estimated_town_class");
          addTag(row, tokens.get(5), "estimated_traffic_class");

          // apply the instance-unifier for the row-map
          Map<String, String> knownRow = mapUnifier.get(row);
          if (knownRow != null) {
            row = knownRow;
          } else {
            mapUnifier.put(row, row);
          }
          data.fastPut(osm_id, row);
          dbRows++;

        }
      }

      // last actions
      if (lastImportType == IMPORT_TYPE_NODES) {
        dbNodeData = new FrozenLongMap<>(data);
        System.out.println("read from file: node rows =" + dbNodeData.size() + " unique rows=" + mapUnifier.size());
      }
      else if (lastImportType == IMPORT_TYPE_WAYS) {
        dbWayData = new FrozenLongMap<>(data);
        System.out.println("read from file: way rows =" + dbWayData.size() + " unique rows=" + mapUnifier.size());
      }
      else if (data != null) {
        dbWayData = new FrozenLongMap<>(data);
        System.out.println("read from file: way rows =" + dbWayData.size() + " unique rows=" + mapUnifier.size());
      }

    } catch (Exception f) {
      f.printStackTrace();
      System.exit(1);
    }
  }

  // use own tokenizer as String.split, StringTokenizer
  // etc. have issues with empty elements
  private List<String> tokenize(String s) {
    List<String> l = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    for (int i=0; i<s.length(); i++) {
      char c = s.charAt(i);
      if (c == ';') {
        l.add(sb.toString());
        sb.setLength(0);
      } else {
        sb.append(c);
      }
    }
    l.add(sb.toString());
    return l;
  }

  private static void addTag(Map<String, String> row, String s, String name) {
    if (!s.isEmpty()) {
      row.put(name, s);
    }
  }

  private static void addDBTag(Map<String, String> row, ResultSet rs, String name) {
    String v = null;
    try {
      v = rs.getString(name);
    } catch (Exception e) {}
    if (v != null) {
      row.put("estimated_" + name, v);
    }
  }

  public void addWayTags(long osm_id, Map<String, String> map) {

    if (dbWayData == null) return;

    if (map == null || !map.containsKey("highway")) {
      return;
    }

    cntOsmWays++;
    if ((cntOsmWays % 1000000L) == 0) {
      String out = "Osm Ways processed=" + cntOsmWays + " way modifs=" + cntWayModified;
      for (String key : pseudoTagsFound.keySet()) {
        out += " " + key + "=" + pseudoTagsFound.get(key);
      }
      System.out.println(out);
    }

    Map<String, String> dbTags = dbWayData.get(osm_id);
    if (dbTags == null) {
      return;
    }

    cntWayModified++;
    for (String key : dbTags.keySet()) {
      map.put(key, dbTags.get(key));
      Long cnt = pseudoTagsFound.get(key);
      if (cnt == null) {
        cnt = 0L;
      }
      pseudoTagsFound.put(key, cnt + 1L);
    }
  }

  public void addNodeTags(NodeData n) {

    if (dbNodeData == null) return;

    Map<String, String> dbTags = dbNodeData.get(n.nid);
    if (dbTags == null) {
      return;
    }

    if (n.tags == null) {
      n.tags = new HashMap<>();
    }

    cntOsmNodes++;
    if ((cntOsmNodes % 1000000L) == 0) {
      String out = "Osm Nodes processed=" + cntOsmNodes + " node modifs=" + cntNodesModified;
      for (String key : pseudoTagsFound.keySet()) {
        out += " " + key + "=" + pseudoTagsFound.get(key);
      }
      System.out.println(out);
    }

    cntNodesModified++;
    for (String key : dbTags.keySet()) {
      n.tags.put(key, dbTags.get(key));
      Long cnt = pseudoTagsFound.get(key);
      if (cnt == null) {
        cnt = 0L;
      }
      pseudoTagsFound.put(key, cnt + 1L);
    }
  }


}
