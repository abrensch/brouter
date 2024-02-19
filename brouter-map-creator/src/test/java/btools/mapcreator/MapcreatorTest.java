package btools.mapcreator;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.net.URL;

public class MapcreatorTest {
  @Test
  public void mapcreatorTest() throws Exception {
    System.setProperty("avoidMapPolling", "true");

    URL mapurl = this.getClass().getResource("/dreieich.pbf");
    Assert.assertNotNull("test-osm-map dreieich.pbf not found", mapurl);
    File mapFile = new File(mapurl.getFile());
    File workingDir = mapFile.getParentFile();
    File profileDir = new File(workingDir, "/../../../../misc/profiles2");
    File tmpDir = new File(workingDir, "tmp");
    tmpDir.mkdir();

    File lookupFile = new File(profileDir, "lookups.dat");
    File profileAll = new File(profileDir, "all.brf");
    File profileReport = new File(profileDir, "trekking.brf");
    File profileCheck = new File(profileDir, "softaccess.brf");

    OsmParser.doParse(lookupFile, tmpDir, profileAll, profileReport, profileCheck, mapFile, null);

    new NodeEnhancer(tmpDir).process(workingDir);

    new WayLinker(tmpDir).process(lookupFile, profileAll, "rd5");
  }
}
