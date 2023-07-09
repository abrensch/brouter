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

  private long cntOsmWays = 0L;
  private long cntWayModified = 0L;

  private Map<String, Long> pseudoTagsFound = new HashMap<>();

  FrozenLongMap<Map<String, String>> dbData;

  public static void main(String[] args) {
    String jdbcurl = args[0];
    String filename = args[1];

    try (Connection conn = DriverManager.getConnection(jdbcurl);
         BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
           filename.endsWith(".gz") ? new GZIPOutputStream(new FileOutputStream(filename)) : new FileOutputStream(filename)))) {

      conn.setAutoCommit(false);

      System.out.println("DatabasePseudoTagProvider dumping data from " + jdbcurl + " to file " + filename);

      bw.write("losmid;noise_class;river_class;forest_class;town_class;traffic_class\n");

      String sql_all_tags = "SELECT * from all_tags";
      try(PreparedStatement psAllTags = conn.prepareStatement(sql_all_tags)) {

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
          if (dbRows % 1000000L == 0L) {
            System.out.println(".. from database: rows =" + dbRows);
          }
        }
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

  public DatabasePseudoTagProvider(String filename) {

    try (BufferedReader br = new BufferedReader(new InputStreamReader(
           filename.endsWith(".gz") ? new GZIPInputStream(new FileInputStream(filename)) : new FileInputStream(filename)))) {

      System.out.println("DatabasePseudoTagProvider reading from file: " + filename);

      br.readLine(); // skip header line

      Map<Map<String, String>, Map<String, String>> mapUnifier = new HashMap<>();
      CompactLongMap<Map<String, String>> data = new CompactLongMap<>();

      long dbRows = 0L;
      for (;;) {
        String line = br.readLine();
        if (line == null) {
          break;
        }
        List<String> tokens = tokenize(line);
        long osm_id = Long.parseLong(tokens.get(0));
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
        data.put(osm_id, row);
        dbRows++;

        if (dbRows % 1000000L == 0L) {
          System.out.println(".. from database: rows =" + data.size() + " unique rows=" + mapUnifier.size());
        }
      }
      System.out.println("freezing result map..");
      dbData = new FrozenLongMap<>(data);
      System.out.println("read from file: rows =" + dbData.size() + " unique rows=" + mapUnifier.size());
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

  public void addTags(long osm_id, Map<String, String> map) {

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

    Map<String, String> dbTags = dbData.get(osm_id);
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
  
  
}
