/**
 * common base class for the OSM items
 *
 * (basically providing lwzy tag-handling)
 */
package btools.mapcreator;

import java.util.Map;

public abstract class ItemData {

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
}
