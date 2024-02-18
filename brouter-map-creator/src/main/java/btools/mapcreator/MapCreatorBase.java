/**
 * common base class for the map-filters
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

import btools.statcoding.BitInputStream;
import btools.statcoding.BitOutputStream;
import btools.util.SortedHeap;

public abstract class MapCreatorBase {

  protected Map<String, String> tags;

  public String getTag(String key) {
    return tags == null ? null : tags.get(key);
  }

  public Map<String, String> getTagsOrNull() {
    return tags;
  }

  public void setTags(Map<String, String> tags) {
    this.tags = tags;
  }

  protected File fileFromTemplate(File template, File dir, String suffix) {
    String filename = template.getName();
    filename = filename.substring(0, filename.length() - 3) + suffix;
    return new File(dir, filename);
  }

  protected BitOutputStream createOutStream(File outFile) throws IOException {
    return new BitOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
  }

}
