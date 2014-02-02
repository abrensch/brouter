package btools.server;

import java.util.*;

import org.junit.Assert;
import org.junit.Test;
import java.net.URL;
import java.io.File;

import btools.router.*;
import btools.mapaccess.*;

public class RouterTest
{
  @Test
  public void routerTest() throws Exception
  {
    URL resulturl = this.getClass().getResource( "/testtrack0.gpx" );
    Assert.assertTrue( "reference result not found: ", resulturl != null );
    File resultfile = new File(resulturl.getFile());
    File workingDir = resultfile.getParentFile();

    String wd = workingDir.getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
    OsmNodeNamed n;
    n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = 180000000 +  8720897;
    n.ilat =  90000000 + 50002515;
    wplist.add( n );

    n = new OsmNodeNamed();
    n.name = "to";
    n.ilon = 180000000 +  8723658;
    n.ilat =  90000000 + 49997510;
    wplist.add( n );

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = wd + "/../../../misc/profiles2/trekking.brf";
    //   c.setAlternativeIdx( 1 );

    RoutingEngine re = new RoutingEngine(
        wd + "/testtrack",
        wd + "/testlog",
        wd + "/../../../brouter-map-creator/target/test-classes/tmp/segments", wplist, rctx );
    re.doRun( 0 );

    // error message from router?
    Assert.assertTrue( "routing failed: " + re.getErrorMessage(), re.getErrorMessage() == null  );

    // if the track didn't change, we expect the first alternative also
    File a1 = new File( workingDir, "testtrack1.gpx" );
    Assert.assertTrue( "result content missmatch", a1.exists() );
  }
}
