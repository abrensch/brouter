/**
 * Access to the storageconfig.txt config file
 *
 * @author ab
 */
package btools.mapaccess;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class StorageConfigHelper {
  public static File getSecondarySegmentDir(File segmentDir) {
    return getStorageLocation(segmentDir, "secondary_segment_dir=");
  }

  public static File getAdditionalMaptoolDir(File segmentDir) {
    return getStorageLocation(segmentDir, "additional_maptool_dir=");
  }

  private static File getStorageLocation(File segmentDir, String tag) {
    File res = null;
    BufferedReader br = null;
    File configFile = new File(segmentDir, "storageconfig.txt");
    try {
      br = new BufferedReader(new FileReader(configFile));
      for (; ; ) {
        String line = br.readLine();
        if (line == null) break;
        line = line.trim();
        if (line.startsWith("#")) continue;
        if (line.startsWith(tag)) {
          String path = line.substring(tag.length()).trim();
          res = path.startsWith("/") ? new File(path) : new File(segmentDir, path);
          if (!res.exists()) res = null;
          break;
        }
      }
    } catch (Exception e) { /* ignore */ } finally {
      if (br != null) {
        try {
          br.close();
        } catch (Exception ee) { /* ignore */ }
      }
    }
    return res;
  }

}
