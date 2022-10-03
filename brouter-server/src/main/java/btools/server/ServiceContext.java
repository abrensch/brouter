package btools.server;

import java.util.List;
import java.util.Map;
import java.io.File;

import btools.router.OsmNodeNamed;

/**
 * Environment configuration that is initialized at server/service startup
 */
public class ServiceContext {
  public File segmentDir;
  public String profileDir;
  public String customProfileDir;
  public String sharedProfileDir;
  public Map<String, String> profileMap = null;
  public List<OsmNodeNamed> nogoList;
}
