package btools.mapsplitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import btools.util.LongList;

/**
 * Parser for OSM data
 *
 * @author ab
 */
public class OsmParser2 extends MapCreatorBase
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

    if ( mapFile == null )
    {
      _br = new BufferedReader(new InputStreamReader(System.in, "UTF8" ));
    }
    else
    {
      if ( mapFile.getName().endsWith( ".gz" ) )
      {
        _br = new BufferedReader(new InputStreamReader( new GZIPInputStream( new FileInputStream( mapFile ) ),"UTF8" ) );
      }
      else
      {
        _br = new BufferedReader(new InputStreamReader( new FileInputStream( mapFile ) , "UTF8" )  );
      }
    }

    for(;;)
    {
      String line = _br.readLine();
      if ( line == null ) break;

      if ( checkNode( line ) ) continue;
      if ( checkWay( line ) ) continue;
      if ( checkRelation( line ) ) continue;
      if ( checkChangeset( line ) ) continue;
    }

    if ( mapFile != null )
    {
      _br.close();
    }
  }


  private boolean checkNode( String line ) throws Exception
  {
    int idx0 = line.indexOf( "<node id=\"" );
    if ( idx0 < 0 ) return false;
    idx0 += 10;
    int idx1 = line.indexOf( '"', idx0 );

    long nodeId = Long.parseLong( line.substring( idx0, idx1 ) );

    int idx2 = line.indexOf( " lat=\"" );
    if ( idx2 < 0 ) return false;
    idx2 += 6;
    int idx3 = line.indexOf( '"', idx2 );
    double lat = Double.parseDouble( line.substring( idx2, idx3 ) );
    int idx4 = line.indexOf( " lon=\"" );
    if ( idx4 < 0 ) return false;
    idx4 += 6;
    int idx5 = line.indexOf( '"', idx4 );
    double lon = Double.parseDouble( line.substring( idx4, idx5 ) );

    NodeData n = new NodeData( nodeId, lon, lat );

    if ( !line.endsWith( "/>" ) )
    {
      // read additional tags
      for(;;)
      {
        String l2 = _br.readLine();
        if ( l2 == null ) return false;

        int i2;
        if ( (i2 = l2.indexOf( "<tag k=\"" )) >= 0 )
        { // property-tag
          i2 += 8;
          int ri2 =  l2.indexOf( '"', i2 );
          String key = l2.substring( i2, ri2 );
          i2 = l2.indexOf( " v=\"", ri2 );
          if ( i2 >= 0 )
          {
            i2 += 4;
            int ri3 = l2.indexOf( '"', i2 );
            String value = l2.substring( i2, ri3 );

            n.putTag( key, value );
          }
        }
        else if ( l2.indexOf( "</node>" ) >= 0 )
        { // end-tag
           break;
        }
      }
    }
    nListener.nextNode( n );
    return true;
  }


  private boolean checkWay( String line ) throws Exception
  {
    int idx0 = line.indexOf( "<way id=\"" );
    if ( idx0 < 0 ) return false;

    idx0 += 9;
    int idx1 = line.indexOf( '"', idx0 );
    long id = Long.parseLong( line.substring( idx0, idx1 ) );

    WayData w = new WayData( id );

    // read the nodes
    for(;;)
    {
      String l2 = _br.readLine();
      if ( l2 == null ) return false;

      int i2;
      if ( (i2 = l2.indexOf( "<nd ref=\"" )) >= 0 )
      { // node reference
        i2 += 9;
        int ri2 =  l2.indexOf( '"', i2 );
        long nid = Long.parseLong( l2.substring( i2, ri2 ) );
        w.nodes.add( nid );
      }
      else if ( (i2 = l2.indexOf( "<tag k=\"" )) >= 0 )
      { // property-tag
        i2 += 8;
        int ri2 =  l2.indexOf( '"', i2 );
        String key = l2.substring( i2, ri2 );
        i2 = l2.indexOf( " v=\"", ri2 );
        if ( i2 >= 0 )
        {
          i2 += 4;
          int ri3 = l2.indexOf( '"', i2 );
          String value = l2.substring( i2, ri3 );
          w.putTag( key, value );
        }
      }
      else if ( l2.indexOf( "</way>" ) >= 0 )
      { // end-tag
        break;
      }
    }
    wListener.nextWay( w );
    return true;
  }

  private boolean checkChangeset( String line ) throws Exception
  {
    int idx0 = line.indexOf( "<changeset id=\"" );
    if ( idx0 < 0 ) return false;

    if ( !line.endsWith( "/>" ) )
    {
        int loopcheck = 0;
        for(;;)
        {
            String l2 = _br.readLine();
            if ( l2.indexOf("</changeset>") >= 0 || ++loopcheck > 10000 ) break;
        }
    }
    return true;
  }

  private boolean checkRelation( String line ) throws Exception
  {
    int idx0 = line.indexOf( "<relation id=\"" );
    if ( idx0 < 0 ) return false;

    idx0 += 14;
    int idx1 = line.indexOf( '"', idx0 );
    long rid = Long.parseLong( line.substring( idx0, idx1 ) );

    LongList wayIds = new LongList( 16 );
    List<String> roles = new ArrayList<String>(16);
    RelationData r = new RelationData( rid, wayIds, roles );

    // read the nodes
    for(;;)
    {
      String l2 = _br.readLine();
      if ( l2 == null ) return false;

      int i2;
      if ( (i2 = l2.indexOf( "<member type=\"way\" ref=\"" )) >= 0 )  // <member type="relation" ref="452156" role="backward"/>
      { // node reference
        i2 += 24;
        int ri2 =  l2.indexOf( '"', i2 );
        long wid = Long.parseLong( l2.substring( i2, ri2 ) );
        
        int role1 = ri2 + 8;
        int role2 = l2.indexOf( '"', role1 );
        String role = l2.substring( role1, role2 );

        r.ways.add( wid );
        r.roles.add( role );
      }
      else if ( (i2 = l2.indexOf( "<tag k=\"" )) >= 0 )
      { // property-tag
        i2 += 8;
        int ri2 =  l2.indexOf( '"', i2 );
        String key = l2.substring( i2, ri2 );
        i2 = l2.indexOf( " v=\"", ri2 );
        if ( i2 >= 0 )
        {
          i2 += 4;
          int ri3 = l2.indexOf( '"', i2 );
          String value = l2.substring( i2, ri3 );
          r.putTag( key, value );
        }
      }
      else if ( l2.indexOf( "</relation>" ) >= 0 )
      { // end-tag
        break;
      }
    }
    rListener.nextRelation( r );
    return true;
  }

}
