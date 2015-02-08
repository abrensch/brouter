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
  private File workingDir;

  @Test
  public void routerTest() throws Exception
  {
    URL resulturl = this.getClass().getResource( "/testtrack0.gpx" );
    Assert.assertTrue( "reference result not found: ", resulturl != null );
    File resultfile = new File(resulturl.getFile());
    workingDir = resultfile.getParentFile();

    String msg;

    // first test: route within dreiech test-map crossing tile border
    
    msg = calcRoute( 8.720897, 50.002515, 8.723658, 49.997510, "testtrack" );

    // error message from router?
    Assert.assertTrue( "routing failed: " + msg, msg == null  );

    // if the track didn't change, we expect the first alternative also
    File a1 = new File( workingDir, "testtrack1.gpx" );
    Assert.assertTrue( "result content missmatch", a1.exists() );

    // second test: to-point far off

    msg = calcRoute( 8.720897, 50.002515, 16.723658, 49.997510, "notrack" );

    Assert.assertTrue( msg, msg != null && msg.indexOf( "not found" ) >= 0 );
  }

  private String calcRoute( double flon, double flat, double tlon, double tlat, String trackname ) throws Exception
  {
    String wd = workingDir.getAbsolutePath();

    List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
    OsmNodeNamed n;
    n = new OsmNodeNamed();
    n.name = "from";
    n.ilon = 180000000 +  (int)(flon*1000000 + 0.5);
    n.ilat =  90000000 + (int)(flat*1000000 + 0.5);
    wplist.add( n );

    n = new OsmNodeNamed();
    n.name = "to";
    n.ilon = 180000000 +  (int)(tlon*1000000 + 0.5);
    n.ilat =  90000000 + (int)(tlat*1000000 + 0.5);
    wplist.add( n );

    RoutingContext rctx = new RoutingContext();
    rctx.localFunction = wd + "/../../../misc/profiles2/trekking.brf";
    //   c.setAlternativeIdx( 1 );

    RoutingEngine re = new RoutingEngine(
        wd + "/" + trackname,
        wd + "/" + trackname,
        wd + "/../../../brouter-map-creator/target/test-classes/tmp/segments", wplist, rctx );
    re.doRun( 0 );
    
    return re.getErrorMessage();
  }

}
