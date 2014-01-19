import java.io.*;

public class BRouterTests
{
  public static void main( String[] args ) throws Exception
  {
    BufferedReader br = new BufferedReader( new FileReader( args[0] ) );
 
    String lastname = "ups";
    for(;;)
    {
 
      String line = br.readLine();
      if ( line == null ) break;
      line = line.trim();
      if ( line.length() == 0 ) continue;
      if ( !Character.isDigit( line.charAt( 0 ) ) )
      {
        lastname = line;
        continue;
      }

      System.out.println( "/java/bin/java -Xmx32m -jar brouter.jar segments " + line + " /var/www/brouter/profiles2/trekking.brf" );
      System.out.println( "mv mytrack0.gpx gpx/" + lastname + ".gpx" );  
      System.out.println( "mv mylog0.csv csv/" + lastname + ".csv" );  
    }
  }
}
