package btools.server;

import java.util.Map;
import java.io.File;

/**
 * Environment configuration that is initialized at server/service startup
 */
public class ServiceContext {
  public File segmentDir;
  public String profileDir;
  public Map<String, String> profileMap = null;
}
