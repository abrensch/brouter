/**
 * DatabasePseudoTagProvider reads Pseudo Tags from a database and adds them
 * to the osm-data
 */
package btools.mapcreator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;

public class DatabasePseudoTagProvider {

  long cntOsmWays = 0L;
  long cntWayModified = 0L;

  Map<String, Long> pseudoTagsFound;

  FrozenLongMap<Map<String, String>> dbData;

  public DatabasePseudoTagProvider(String jdbcurl) {

    try (Connection conn = DriverManager.getConnection(jdbcurl)) {
      conn.setAutoCommit(false);

      System.out.println("DatabasePseudoTagProvider start connection to the database........" + jdbcurl);

      Map<String, String> databaseField2Tag = new HashMap<>();
      databaseField2Tag.put("noise_class", "estimated_noise_class");
      databaseField2Tag.put("river_class", "estimated_river_class");
      databaseField2Tag.put("forest_class", "estimated_forest_class");
      databaseField2Tag.put("town_class", "estimated_town_class");
      databaseField2Tag.put("traffic_class", "estimated_traffic_class");

      pseudoTagsFound = new HashMap<>();
      for (String pseudoTag : databaseField2Tag.values()) {
        pseudoTagsFound.put(pseudoTag, 0L);
      }

      Map<Map<String, String>, Map<String, String>> mapUnifier = new HashMap<>();
      CompactLongMap<Map<String, String>> data = new CompactLongMap<>();

      System.out.println("DatabasePseudoTagProvider connect to the database ok........");

      String sql_all_tags = "SELECT * from all_tags";
      try(PreparedStatement psAllTags = conn.prepareStatement(sql_all_tags)) {

        psAllTags.setFetchSize(100);

        // process the results
        ResultSet rsBrouter = psAllTags.executeQuery();

        long dbRows = 0L;
        while (rsBrouter.next()) {
          long osm_id = rsBrouter.getLong("losmid");
          Map<String, String> row = new HashMap<>(5);
          for (String key : databaseField2Tag.keySet()) {
            String value = rsBrouter.getString(key);
            if (value != null && !value.isEmpty()) {
              row.put(databaseField2Tag.get(key), value);
            }
          }

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
      }
      System.out.println("freezing result map..");
      dbData = new FrozenLongMap<>(data);
      System.out.println("read from database: rows =" + dbData.size() + " unique rows=" + mapUnifier.size());

    } catch (SQLException g) {
      System.err.format("DatabasePseudoTagProvider execute sql .. SQL State: %s\n%s\n", g.getSQLState(), g.getMessage());
      System.exit(1);
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
      pseudoTagsFound.put(key, pseudoTagsFound.get(key) + 1L);
    }
  }
}
