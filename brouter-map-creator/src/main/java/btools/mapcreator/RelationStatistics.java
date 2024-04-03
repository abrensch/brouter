package btools.mapcreator;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * WayCutter does 2 step in map-processing:
 * <p>
 * - cut the way file into 45*30 - pieces
 * - enrich ways with relation information
 *
 * @author ab
 */
public class RelationStatistics extends MapCreatorBase {
  public static void main(String[] args) throws Exception {
    System.out.println("*** RelationStatistics: count relation networks");
    if (args.length != 1) {
      System.out.println("usage: java WayCutter <relation-file>");

      return;
    }
    new RelationStatistics().process(new File(args[0]));
  }

  public void process(File relationFileIn) throws Exception {
    Map<String, long[]> relstats = new HashMap<>();

    DataInputStream dis = createInStream(relationFileIn);
    try {
      for (; ; ) {
        long rid = readId(dis);
        String network = dis.readUTF();
        int waycount = 0;
        for (; ; ) {
          long wid = readId(dis);
          if (wid == -1) break;
          waycount++;
        }

        long[] stat = relstats.get(network);
        if (stat == null) {
          stat = new long[2];
          relstats.put(network, stat);
        }
        stat[0]++;
        stat[1] += waycount;
      }
    } catch (EOFException eof) {
      dis.close();
    }
    for (String network : relstats.keySet()) {
      long[] stat = relstats.get(network);
      System.out.println("network: " + network + " has " + stat[0] + " relations with " + stat[1] + " ways");
    }
  }


}
