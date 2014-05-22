package btools.server;

import java.util.List;
import java.util.Map;

import btools.router.OsmNodeNamed;

/**
 * Environment configuration that is initialized at server/service startup
 */
public class ServiceContext
{
  public String segmentDir;
  public String customProfileDir;
  public Map<String,String> profileMap = null;
  public List<OsmNodeNamed> nogoList;
}
