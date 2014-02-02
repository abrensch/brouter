/**
 * Efficient cache or osmnodes
 *
 * @author ab
 */
package btools.mapaccess;

import java.util.*;
import java.io.*;

public final class NodesCache
{
  private String segmentDir;
  private OsmNodesMap nodesMap;
  private int lookupVersion;
  private boolean carMode;
  private String currentFileName;

  private HashMap<String,PhysicalFile> fileCache;
  private byte[] iobuffer;
  
  private OsmFile[][] fileRows = new OsmFile[180][];
  private ArrayList<MicroCache> segmentList = new ArrayList<MicroCache>();

  public DistanceChecker distanceChecker;
  
  public boolean oom_carsubset_hint = false;

  public NodesCache( String segmentDir, OsmNodesMap nodesMap, int lookupVersion, boolean carMode, NodesCache oldCache )
  {
    this.segmentDir = segmentDir;
    this.nodesMap = nodesMap;
    this.lookupVersion = lookupVersion;
    this.carMode = carMode;

    if ( oldCache != null )
    {
      fileCache = oldCache.fileCache;
      iobuffer = oldCache.iobuffer;
      oom_carsubset_hint = oldCache.oom_carsubset_hint;
    }
    else
    {
      fileCache = new HashMap<String,PhysicalFile>(4);
      iobuffer = new byte[65636];
    }
  }

  public int loadSegmentFor( int ilon, int ilat )
  {
    MicroCache mc = getSegmentFor( ilon, ilat );
    return mc == null ? 0 : mc.getSize();
  }

  public MicroCache getSegmentFor( int ilon, int ilat )
  {
    try
    {
        int lonIdx80 = ilon/12500;
        int latIdx80 = ilat/12500;
        int lonDegree = lonIdx80/80;
        int latDegree = latIdx80/80;
        OsmFile osmf = null;
        OsmFile[] fileRow = fileRows[latDegree];
        int ndegrees = fileRow == null ? 0 : fileRow.length;
        for( int i=0; i<ndegrees; i++ )
        {
          if ( fileRow[i].lonDegree == lonDegree )
          {
            osmf = fileRow[i];
            break;
          }
        }
        if ( osmf == null )
        {
          osmf = fileForSegment( lonDegree, latDegree );
          OsmFile[] newFileRow = new OsmFile[ndegrees+1];
          for( int i=0; i<ndegrees; i++ )
          {
            newFileRow[i] = fileRow[i];
          }
          newFileRow[ndegrees] = osmf;
          fileRows[latDegree] = newFileRow;
        }
        currentFileName = osmf.filename;
        if ( osmf.microCaches == null )
        {
          return null;
        }
        int subIdx = (latIdx80-80*latDegree)*80 + (lonIdx80-80*lonDegree);
        MicroCache segment = osmf.microCaches[subIdx];
        if ( segment == null )
        {
          // nodesMap.removeCompleteNodes();

          segment = new MicroCache( osmf, lonIdx80, latIdx80, iobuffer );
          osmf.microCaches[subIdx] = segment;
          segmentList.add( segment );
        }
        return segment;
    }
    catch( RuntimeException re )
    {
      throw re;
    }
    catch( Exception e )
    {
      throw new RuntimeException( "error reading datafile " + currentFileName + ": " + e );
    }
  }


  public boolean obtainNonHollowNode( OsmNode node )
  {
    if ( !node.isHollow() ) return true;

    MicroCache segment = getSegmentFor( node.ilon, node.ilat );
    if ( segment == null )
    {
      return false;
    }
    segment.fillNode( node, nodesMap, distanceChecker );
    return !node.isHollow();
  }

  private OsmFile fileForSegment( int lonDegree, int latDegree ) throws Exception
  {
    File base = new File( segmentDir );
    if ( !base.isDirectory() ) throw new RuntimeException( "segment directory " + segmentDir + " does not exist" );

    int lonMod5 = lonDegree % 5;
    int latMod5 = latDegree % 5;
    int tileIndex = lonMod5 * 5 + latMod5;

    int lon = lonDegree - 180 - lonMod5;
    String slon = lon < 0 ? "W" + (-lon) : "E" + lon;
    int lat = latDegree - 90 - latMod5;

    String slat = lat < 0 ? "S" + (-lat) : "N" + lat;
    String filenameBase = slon + "_" + slat;

    currentFileName = filenameBase + ".rd5/cd5";

    PhysicalFile ra = null;
    if ( !fileCache.containsKey( filenameBase ) )
    {
      File f = null;
      if ( carMode )
      {
        File carFile = new File( new File( base, "carsubset" ), filenameBase + ".cd5" );
    	if ( carFile.exists() ) f = carFile;
      }
      if ( f == null )
      {
        File fullFile = new File( base, filenameBase + ".rd5" );
        if ( fullFile.exists() ) f = fullFile;
        if ( carMode && f != null ) oom_carsubset_hint = true;
      }
      if ( f != null )
      {
        currentFileName = f.getName();
        ra = new PhysicalFile( f, iobuffer, lookupVersion );
      }
      fileCache.put( filenameBase, ra );
    }
    ra = fileCache.get( filenameBase );
    OsmFile osmf = new OsmFile( ra, tileIndex, iobuffer );
    osmf.lonDegree = lonDegree;
    osmf.latDegree = latDegree;
    return osmf;
  }

  public List<OsmNode> getAllNodes()
  {
    List<OsmNode> all = new ArrayList<OsmNode>();
    for( MicroCache segment :  segmentList )
    {
      List<OsmNode> positions = segment.getPositions( nodesMap );
      all.addAll( positions );
    }
    return all;
  }


  public void close()
  {
    for( PhysicalFile f: fileCache.values() )
    {
      try
      {
        f.ra.close();
      }
      catch( IOException ioe )
      {
        // ignore
      }
    }
  }
}
