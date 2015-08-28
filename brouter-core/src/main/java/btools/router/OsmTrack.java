/**
 * Container for a track
 *
 * @author ab
 */
package btools.router;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import btools.mapaccess.OsmPos;
import btools.util.CompactLongMap;
import btools.util.FrozenLongMap;

public final class OsmTrack
{
  // csv-header-line
  private static final String MESSAGES_HEADER = "Longitude\tLatitude\tElevation\tDistance\tCostPerKm\tElevCost\tTurnCost\tNodeCost\tInitialCost\tWayTags\tNodeTags";

  public MatchedWaypoint endPoint;
  public long[] nogoChecksums;
  
  private static class OsmPathElementHolder
  {
    public OsmPathElement node;
    public OsmPathElementHolder nextHolder;
  }
	
	
  public ArrayList<OsmPathElement> nodes = new ArrayList<OsmPathElement>();

  private CompactLongMap<OsmPathElementHolder> nodesMap;

  public String message = null;
  public ArrayList<String> messageList = null;

  public String name = "unset";

  public void addNode( OsmPathElement node )
  {
    nodes.add( 0, node );
  }

  public void buildMap()
  {
	nodesMap = new CompactLongMap<OsmPathElementHolder>();
	for( OsmPathElement node: nodes )
	{
      long id = node.getIdFromPos();
      OsmPathElementHolder nh = new OsmPathElementHolder();
      nh.node = node;
      OsmPathElementHolder h = nodesMap.get( id );
      if ( h != null )
      {
        while( h.nextHolder != null )
        {
          h = h.nextHolder;
        }
        h.nextHolder = nh;
      }
      else
      {
        nodesMap.fastPut( id, nh );
      }
	}
	nodesMap = new FrozenLongMap<OsmPathElementHolder>( nodesMap );
  }
  
  private ArrayList<String> aggregateMessages()
  {
    ArrayList<String> res = new ArrayList<String>();
    MessageData current = null;
    for( OsmPathElement n : nodes )
    {
      if ( n.message != null )
      {
        MessageData md = n.message.copy();
        if ( current != null )
        {
          if ( current.nodeKeyValues != null || !current.wayKeyValues.equals( md.wayKeyValues ) )
          {
            res.add( current.toMessage() );
          }
          else
          {
            md.add( current );
          }
        }
        current = md;
      }
    }
    if ( current != null )
    {
      res.add( current.toMessage() );
    }
    return res;
  }

  /**
   * writes the track in binary-format to a file
   * @param filename the filename to write to
   */
  public void writeBinary( String filename ) throws Exception
  {
    DataOutputStream dos = new DataOutputStream( new BufferedOutputStream( new FileOutputStream( filename ) ) );

    endPoint.writeToStream( dos );
    dos.writeInt( nodes.size() );
    for( OsmPathElement node: nodes )
	{
      node.writeToStream( dos );
	}
    dos.writeLong( nogoChecksums[0] );
    dos.writeLong( nogoChecksums[1] );
    dos.writeLong( nogoChecksums[2] );
    dos.close();
  }

  public static OsmTrack readBinary( String filename, OsmNodeNamed newEp, long[] nogoChecksums )
  {
	OsmTrack t = null;
	if ( filename != null )
	{
	  File f = new File( filename );
	  if ( f.exists() )
	  {
		try
		{
          DataInputStream dis = new DataInputStream( new BufferedInputStream( new FileInputStream( f ) ) );
          MatchedWaypoint ep = MatchedWaypoint.readFromStream( dis );
          int dlon = ep.waypoint.ilon - newEp.ilon;
          int dlat = ep.waypoint.ilat - newEp.ilat;
          if ( dlon < 20 && dlon > -20 && dlat < 20 && dlat > -20 )
          {
        	t = new OsmTrack();
        	t.endPoint = ep;
        	int n = dis.readInt();
        	OsmPathElement last_pe = null;
        	for( int i=0; i<n; i++ )
        	{
              OsmPathElement pe = OsmPathElement.readFromStream( dis );
        	  pe.origin = last_pe;
        	  last_pe = pe;
        	  t.nodes.add( pe );
        	}
        	t.cost = last_pe.cost;
        	t.buildMap();
          }
          long[] al = new long[3];
          try
          {
            al[0] = dis.readLong();
            al[1] = dis.readLong();
            al[2] = dis.readLong();
          } catch( EOFException eof ) { /* kind of expected */ }
          dis.close();
          boolean nogoCheckOk = Math.abs( al[0] - nogoChecksums[0] ) <= 20
                             && Math.abs( al[1] - nogoChecksums[1] ) <= 20
		                     && Math.abs( al[2] - nogoChecksums[2] ) <= 20;
          if ( !nogoCheckOk ) return null;
		}
		catch( Exception e )
		{
	  	  throw new RuntimeException( "Exception reading rawTrack: " + e );
		}
	  }
	}
    return t;
  }

  public void addNodes( OsmTrack t )
  {
    for( OsmPathElement n : t.nodes ) addNode( n );
    buildMap();
  }

  public boolean containsNode( OsmPos node )
  {
    return nodesMap.contains( node.getIdFromPos() );
  }

  public OsmPathElement getLink( long n1, long n2 )
  {
    OsmPathElementHolder h = nodesMap.get( n2 );
    while( h != null )
    {
	  OsmPathElement e1 = h.node.origin;
	  if ( e1 != null && e1.getIdFromPos() == n1 )
	  {
        return h.node;
	  }
	  h = h.nextHolder;
    }
    return null;
  }

  public void appendTrack( OsmTrack t )
  {
    for( int i=0; i<t.nodes.size(); i++ )
    {
      if ( i > 0 || nodes.size() == 0 )
      {
        nodes.add( t.nodes.get(i) );
      }
    }
    distance += t.distance;
    ascend += t.ascend;
    plainAscend += t.plainAscend;
    cost += t.cost;
  }

  public int distance;
  public int ascend;
  public int plainAscend;
  public int cost;

  /**
   * writes the track in gpx-format to a file
   * @param filename the filename to write to
   */
  public void writeGpx( String filename ) throws Exception
  {
    BufferedWriter bw = new BufferedWriter( new FileWriter( filename ) );

    bw.write( formatAsGpx() );
    bw.close();
  }

  public String formatAsGpx()
  {
    StringBuilder sb = new StringBuilder(8192);

    sb.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
    for( int i=messageList.size()-1; i >= 0; i-- )
    {
      String message = messageList.get(i);
      if ( i < messageList.size()-1 ) message = "(alt-index " + i + ": " + message + " )";
      if ( message != null ) sb.append("<!-- ").append(message).append(" -->\n");
    }
    sb.append( "<gpx \n" );
    sb.append( " xmlns=\"http://www.topografix.com/GPX/1/1\" \n" );
    sb.append( " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" \n" );
    sb.append( " xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\" \n" );
    sb.append( " creator=\"BRouter-1.2\" version=\"1.1\">\n" );
    sb.append( " <trk>\n" );
    sb.append("  <name>").append(name).append("</name>\n");
    sb.append( "  <trkseg>\n" );

    for( OsmPathElement n : nodes )
    {
      String sele = n.getSElev() == Short.MIN_VALUE ? "" : "<ele>" + n.getElev() + "</ele>";
      sb.append("   <trkpt lon=\"").append(formatPos(n.getILon() - 180000000)).append("\" lat=\"").append(formatPos(n.getILat() - 90000000)).append("\">").append(sele).append("</trkpt>\n");
    }

    sb.append( "  </trkseg>\n" );
    sb.append( " </trk>\n" );
    sb.append( "</gpx>\n" );

    return sb.toString();
  }

  public void writeKml( String filename ) throws Exception
  {
    BufferedWriter bw = new BufferedWriter( new FileWriter( filename ) );

    bw.write( formatAsKml() );
    bw.close();
  }

  public String formatAsKml()
  {
    StringBuilder sb = new StringBuilder(8192);

    sb.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );

    sb.append( "<kml xmlns=\"http://earth.google.com/kml/2.0\">\n" );
    sb.append( "  <Document>\n" );
    sb.append( "    <name>KML Samples</name>\n" );
    sb.append( "    <open>1</open>\n" );
    sb.append( "    <distance>3.497064</distance>\n" );
    sb.append( "    <traveltime>872</traveltime>\n" );
    sb.append( "    <description>To enable simple instructions add: 'instructions=1' as parameter to the URL</description>\n" );
    sb.append( "    <Folder>\n" );
    sb.append( "      <name>Paths</name>\n" );
    sb.append( "      <visibility>0</visibility>\n" );
    sb.append( "      <description>Examples of paths.</description>\n" );
    sb.append( "      <Placemark>\n" );
    sb.append( "        <name>Tessellated</name>\n" );
    sb.append( "        <visibility>0</visibility>\n" );
    sb.append( "        <description><![CDATA[If the <tessellate> tag has a value of 1, the line will contour to the underlying terrain]]></description>\n" );
    sb.append( "        <LineString>\n" );
    sb.append( "          <tessellate>1</tessellate>\n" );
    sb.append( "         <coordinates> " );


    for( OsmPathElement n : nodes )
    {
      sb.append(formatPos(n.getILon() - 180000000)).append(",").append(formatPos(n.getILat() - 90000000)).append("\n");
    }

    sb.append( "          </coordinates>\n" );
    sb.append( "        </LineString>\n" );
    sb.append( "      </Placemark>\n" );
    sb.append( "    </Folder>\n" );
    sb.append( "  </Document>\n" );
    sb.append( "</kml>\n" );

    return sb.toString();
  }

  public List<String> iternity;
  
  public String formatAsGeoJson()
  {
    StringBuilder sb = new StringBuilder(8192);

    sb.append( "{\n" );
    sb.append( "  \"type\": \"FeatureCollection\",\n" );
    sb.append( "  \"features\": [\n" );
    sb.append( "    {\n" );
    sb.append( "      \"type\": \"Feature\",\n" );
    sb.append( "      \"properties\": {\n" );
    sb.append( "        \"creator\": \"BRouter-1.1\",\n" );
    sb.append( "        \"name\": \"" ).append( name ).append( "\",\n" );
    sb.append( "        \"track-length\": \"" ).append( distance ).append( "\",\n" );
    sb.append( "        \"filtered ascend\": \"" ).append( ascend ).append( "\",\n" );
    sb.append( "        \"plain-ascend\": \"" ).append( plainAscend ).append( "\",\n" );
    sb.append( "        \"cost\": \"" ).append( cost ).append( "\",\n" );
    sb.append( "        \"messages\": [\n" );
    sb.append( "          [\"").append( MESSAGES_HEADER.replaceAll("\t", "\", \"") ).append( "\"],\n" );
    for( String m : aggregateMessages() )
    {
      sb.append( "          [\"").append( m.replaceAll("\t", "\", \"") ).append( "\"],\n" );
    }
    sb.deleteCharAt( sb.lastIndexOf( "," ) );
    sb.append( "        ]\n" );
    
    sb.append( "      },\n" );
    
    if ( iternity != null )
    {
      sb.append( "      \"iternity\": [\n" );
      for( String s : iternity )
      {
    	  sb.append( "        \"").append( s ).append( "\",\n" );
      }
      sb.deleteCharAt( sb.lastIndexOf( "," ) );
      sb.append( "        ],\n" );
    }
    sb.append( "      \"geometry\": {\n" );
    sb.append( "        \"type\": \"LineString\",\n" );
    sb.append( "        \"coordinates\": [\n" );

    for( OsmPathElement n : nodes )
    {
      String sele = n.getSElev() == Short.MIN_VALUE ? "" : ", " + n.getElev();
      sb.append( "          [" ).append(formatPos(n.getILon() - 180000000)).append(", ").append(formatPos(n.getILat() - 90000000)).append(sele).append( "],\n" );
    }
    sb.deleteCharAt( sb.lastIndexOf( "," ) );

    sb.append( "        ]\n" );
    sb.append( "      }\n" );
    sb.append( "    }\n" );
    sb.append( "  ]\n" );
    sb.append( "}\n" );

    return sb.toString();
  }

  private static String formatPos( int p )
  {
    boolean negative = p < 0;
    if ( negative ) p = -p;
    char[] ac = new char[12];
    int i = 11;
    while( p != 0 || i > 3 )
    {
      ac[i--] = (char)('0' + (p % 10));
      p /= 10;
      if ( i == 5 ) ac[i--] = '.';
    }
    if ( negative ) ac[i--] = '-';
    return new String( ac, i+1, 11-i );
  }

  public void dumpMessages( String filename, RoutingContext rc ) throws Exception
  {
    BufferedWriter bw = filename == null ? null : new BufferedWriter( new FileWriter( filename ) );
    writeMessages( bw, rc );
  }

  public void writeMessages( BufferedWriter bw, RoutingContext rc ) throws Exception
  {
    dumpLine( bw, MESSAGES_HEADER );
    for( String m : aggregateMessages() )
    {
      dumpLine( bw, m );
    }
    if ( bw != null ) bw.close();
  }

  private void dumpLine( BufferedWriter bw, String s) throws Exception
  {
    if ( bw == null )
    {
      System.out.println( s );
    }
    else
    {
      bw.write( s );
      bw.write( "\n" );
    }
  }

  public void readGpx( String filename ) throws Exception
  {
      File f = new File( filename );
      if ( !f.exists() ) return;
      BufferedReader br = new BufferedReader(
                           new InputStreamReader(
                            new FileInputStream( f ) ) );

      for(;;)
      {
        String line = br.readLine();
        if ( line == null ) break;

        int idx0 = line.indexOf( "<trkpt lon=\"" );
        if ( idx0 >= 0 )
        {
          idx0 += 12;
          int idx1 = line.indexOf( '"', idx0 );
          int ilon = (int)((Double.parseDouble( line.substring( idx0, idx1 ) ) + 180. )*1000000. + 0.5);
          int idx2 = line.indexOf( " lat=\"" );
          if ( idx2 < 0 ) continue;
          idx2 += 6;
          int idx3 = line.indexOf( '"', idx2 );
          int ilat = (int)((Double.parseDouble( line.substring( idx2, idx3 ) ) + 90. )*1000000. + 0.5);
          nodes.add( OsmPathElement.create( ilon, ilat, (short)0, null, false ) );
        }
      }
      br.close();
  }

  public boolean equalsTrack( OsmTrack t )
  {
    if ( nodes.size() != t.nodes.size() ) return false;
    for( int i=0; i<nodes.size(); i++ )
    {
      OsmPathElement e1 = nodes.get(i);
      OsmPathElement e2 = t.nodes.get(i);
      if ( e1.getILon() != e2.getILon() || e1.getILat() != e2.getILat() ) return false;
    }
    return true;
  }
}
