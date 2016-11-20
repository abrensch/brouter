package btools.mapcreator;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import btools.util.*;

import org.openstreetmap.osmosis.osmbinary.Fileformat;

/**
 * Parser for OSM data
 *
 * @author ab
 */
public class OsmParser extends MapCreatorBase
{
  private BufferedReader _br;

  private NodeListener nListener;
  private WayListener wListener;
  private RelationListener rListener;

  public void readMap( File mapFile,
                        NodeListener nListener,
                        WayListener wListener,
                        RelationListener rListener ) throws Exception
  {
    this.nListener = nListener;
    this.wListener = wListener;
    this.rListener = rListener;

    System.out.println( "*** PBF Parsing: " + mapFile );

    // once more for testing
    int rawBlobCount = 0;
    DataInputStream dis = new DataInputStream( new BufferedInputStream ( new FileInputStream( mapFile ) ) );
    for(;;)
    {
      int headerLength;
      try
      {
        headerLength = dis.readInt();
      }
      catch (EOFException e)
      {
        break;
      }

      byte[] headerBuffer = new byte[headerLength];
      dis.readFully(headerBuffer);
      Fileformat.BlobHeader blobHeader = Fileformat.BlobHeader.parseFrom(headerBuffer);

      byte[] blobData = new byte[blobHeader.getDatasize()];
      dis.readFully(blobData);

      new BPbfBlobDecoder( blobHeader.getType(), blobData, this ).process();

      rawBlobCount++;
    }
    dis.close();
    System.out.println( "read raw blobs: " + rawBlobCount );
  }



  public void addNode( long nid, Map<String, String> tags, double lat, double lon )
  {
    NodeData n = new NodeData( nid, lon, lat );
    n.setTags( (HashMap<String,String>)tags );
    try
    {
      nListener.nextNode( n );
    }
    catch( Exception e )
    {
      throw new RuntimeException( "error writing node: " + e );
    } 
  }

  public void addWay( long wid, Map<String, String> tags, LongList nodes )
  {
    WayData w = new WayData( wid, nodes );
    w.setTags( (HashMap<String,String>)tags );

    try
    {
      wListener.nextWay( w );
    }
    catch( Exception e )
    {
      throw new RuntimeException( "error writing way: " + e );
    } 
  }

  public void addRelation( long rid, Map<String, String> tags, LongList wayIds, long fromWid, long toWid, long viaNid )
  {
    RelationData r = new RelationData( rid, wayIds );
    r.setTags( (HashMap<String,String>)tags );

    try
    {
      rListener.nextRelation( r );
      rListener.nextRestriction( r, fromWid, toWid, viaNid );
    }
    catch( Exception e )
    {
      throw new RuntimeException( "error writing relation: " + e );
    } 
  }

}
