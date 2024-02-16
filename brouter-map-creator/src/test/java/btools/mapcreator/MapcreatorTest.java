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
    File tmpdir = new File(workingDir, "tmp");
    tmpdir.mkdir();

    File lookupFile = new File(profileDir, "lookups.dat");
    File profileAll = new File(profileDir, "all.brf");
    File profileReport = new File(profileDir, "trekking.brf");
    File profileCheck = new File(profileDir, "softaccess.brf");

    OsmCutter.doCut(lookupFile, tmpdir, profileAll, profileReport, profileCheck, mapFile, null);


    // run PosUnifier
    new PosUnifier(tmpdir).process(tmpdir, workingDir.getAbsolutePath());

    // run WayLinker
    new WayLinker().process(tmpdir, lookupFile, profileAll, "rd5");
  }
}
