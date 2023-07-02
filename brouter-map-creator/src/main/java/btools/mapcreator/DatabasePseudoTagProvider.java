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
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;

public class DatabasePseudoTagProvider {

  long cntOsmWays = 0L;
  long cntWayModified = 0L;

  Map<String, Long> pseudoTagsFound;

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
        ResultSet rsBrouter = psAllTags.executeQuery();

        long dbRows = 0L;
        while (rsBrouter.next()) {
          StringBuilder line = new StringBuilder();
          line.append(rsBrouter.getLong("losmid"))
            .append(';').append(rsBrouter.getString("noise_class"))
            .append(';').append(rsBrouter.getString("river_class"))
            .append(';').append(rsBrouter.getString("forest_class"))
            .append(';').append(rsBrouter.getString("town_class"))
            .append(';').append(rsBrouter.getString("traffic_class"))
            .append('\n');
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
        StringTokenizer tk = new StringTokenizer(line, ";");
        long osm_id = Long.parseLong(tk.nextToken());
        Map<String, String> row = new HashMap<>(5);
        row.put("estimated_noise_class", tk.nextToken());
        row.put("estimated_river_class", tk.nextToken());
        row.put("estimated_forest_class", tk.nextToken());
        row.put("estimated_town_class", tk.nextToken());
        row.put("estimated_traffic_class", tk.nextToken());

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

    cntWayModified = cntWayModified + 1;
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
