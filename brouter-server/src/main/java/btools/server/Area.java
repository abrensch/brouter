package btools.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Area
{
  private List<Polygon> poslist  = new ArrayList<Polygon>();
  private List<Polygon> neglist  = new ArrayList<Polygon>();

  public static void main( String[] args ) throws IOException
  {
    Area a = new Area( new File( args[0] ) );
    
    System.out.println( args[1] + " is in " + args[0] + "=" + a.isInArea( Long.parseLong( args[1] ) ) );
  }
  
  public Area( File f ) throws IOException
  {
    BufferedReader br = new BufferedReader( new FileReader( f ) );
    br.readLine();
    
    for(;;)
    {
      String head = br.readLine();
      if ( head == null || "END".equals( head ) )
      {
        break;
      }
      Polygon pol = new Polygon( br );
      if ( head.startsWith( "!" ) )
      {
        neglist.add( pol );
      }
      else
      {
        poslist.add( pol );
      }
    }
  }
  
  public boolean isInArea( long id )
  {
    for( int i=0; i<poslist.size(); i++)
    {
      if ( poslist.get(i).isInPolygon( id ) )
      {
        for( int j=0; j<neglist.size(); j++)
        {
          if ( neglist.get(j).isInPolygon( id ) )
          {
            return false;
          }
        }
        return true;
      }
    }
    return false;
  }

  public boolean isInBoundingBox( long id )
  {
    for( int i=0; i<poslist.size(); i++)
    {
      if ( poslist.get(i).isInBoundingBox( id ) )
      {
        return true;
      }
    }
    return false;
  }
}
