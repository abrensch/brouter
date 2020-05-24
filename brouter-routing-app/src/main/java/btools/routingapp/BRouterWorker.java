package btools.routingapp;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import android.os.Bundle;
import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

public class BRouterWorker
{
  public String baseDir;
  public File segmentDir;
  public String profileName;
  public String profilePath;
  public String rawTrackPath;
  public List<OsmNodeNamed> waypoints;
  public List<OsmNodeNamed> nogoList;

  public String getTrackFromParams(Bundle params)
  {
	String pathToFileResult = params.getString("pathToFileResult");
	
	if (pathToFileResult != null)
	{
	  File f = new File (pathToFileResult);
	  File dir = f.getParentFile();
	  if (!dir.exists() || !dir.canWrite()){
		  return "file folder does not exists or can not be written!";
	  }
	}

    long maxRunningTime = 60000;
    String sMaxRunningTime = params.getString( "maxRunningTime" );
    if ( sMaxRunningTime != null )
    {
      maxRunningTime = Integer.parseInt( sMaxRunningTime ) * 1000;
    }
	
    RoutingContext rc = new RoutingContext();
    rc.rawTrackPath = rawTrackPath;
    rc.localFunction = profilePath;

    String tiFormat = params.getString( "turnInstructionFormat" );
    if ( tiFormat != null )
    {
      if ( "osmand".equalsIgnoreCase( tiFormat ) )
      {
        rc.turnInstructionMode = 3;
      }
      else if ( "locus".equalsIgnoreCase( tiFormat ) )
      {
        rc.turnInstructionMode = 2;
      }
    }

    if ( params.containsKey( "direction" ) )
    {
      rc.startDirection = Integer.valueOf( params.getInt( "direction" ) );
    }

    readNogos( params ); // add interface provided nogos
    RoutingContext.prepareNogoPoints( nogoList );
    rc.nogopoints = nogoList;

    waypoints = readPositions(params);

    try
    {
      writeTimeoutData( rc );
    }
    catch( Exception e ) {}

    RoutingEngine cr = new RoutingEngine( null, null, segmentDir, waypoints, rc );
    cr.quite = true;
    cr.doRun( maxRunningTime );
	
    // store new reference track if any
    // (can exist for timed-out search)
    if ( cr.getFoundRawTrack() != null )
    {
      try
      {
        cr.getFoundRawTrack().writeBinary( rawTrackPath );
      }
      catch( Exception e ) {}
    }
    
    if ( cr.getErrorMessage() != null )
    {
      return cr.getErrorMessage();
    }
    
	String format = params.getString("trackFormat");
	boolean writeKml = format != null && "kml".equals( format );

    OsmTrack track = cr.getFoundTrack();
    if ( track != null )
    {
	  if ( pathToFileResult == null )
	  {
        if ( writeKml ) return track.formatAsKml();
        return track.formatAsGpx();
      }
	  try
	  {
        if ( writeKml ) track.writeKml(pathToFileResult);
        else track.writeGpx(pathToFileResult);
	  }
	  catch( Exception e )
	  {
	    return "error writing file: " + e;
	  }
	}
	return null;
  }

  private List<OsmNodeNamed> readPositions( Bundle params )
  {
    List<OsmNodeNamed> wplist = new ArrayList<OsmNodeNamed>();
    
	double[] lats = params.getDoubleArray("lats");
	double[] lons = params.getDoubleArray("lons");
			
	if (lats == null || lats.length < 2 || lons == null || lons.length < 2)
	{
	  throw new IllegalArgumentException( "we need two lat/lon points at least!" );
	}
    
	for( int i=0; i<lats.length && i<lons.length; i++ )
	{
      OsmNodeNamed n = new OsmNodeNamed();
      n.name = "via" + i;
      n.ilon = (int)( ( lons[i] + 180. ) *1000000. + 0.5);
      n.ilat = (int)( ( lats[i] +  90. ) *1000000. + 0.5);
      wplist.add( n );
	}
    wplist.get(0).name = "from";
    wplist.get(wplist.size()-1).name = "to";
	
    return wplist;
  }

  private void readNogos( Bundle params )
  {
	double[] lats = params.getDoubleArray("nogoLats");
	double[] lons = params.getDoubleArray("nogoLons");
	double[] radi = params.getDoubleArray("nogoRadi");
	
	if ( lats == null || lons == null || radi == null ) return;
			
	for( int i=0; i<lats.length && i<lons.length && i<radi.length; i++ )
	{
      OsmNodeNamed n = new OsmNodeNamed();
      n.name = "nogo" + (int)radi[i];
      n.ilon = (int)( ( lons[i] + 180. ) *1000000. + 0.5);
      n.ilat = (int)( ( lats[i] +  90. ) *1000000. + 0.5);
      n.isNogo = true;
      n.nogoWeight = Double.NaN;
      AppLogger.log( "added interface provided nogo: " + n );
      nogoList.add( n );
	}
  }
  
  private void writeTimeoutData( RoutingContext rc ) throws Exception
  {
    String timeoutFile = baseDir + "/brouter/modes/timeoutdata.txt";
    
    BufferedWriter bw = new BufferedWriter( new FileWriter( timeoutFile ) );
    bw.write( profileName );
    bw.write( "\n" );
    bw.write( rc.rawTrackPath );
    bw.write( "\n" );
    writeWPList( bw, waypoints );
    writeWPList( bw, nogoList );
    bw.close();
  }  

  private void writeWPList( BufferedWriter bw, List<OsmNodeNamed> wps ) throws Exception
  {
    bw.write( wps.size() + "\n" );
    for( OsmNodeNamed wp : wps )
    {
      bw.write( wp.toString() );
      bw.write( "\n" );
    }
  }
}
