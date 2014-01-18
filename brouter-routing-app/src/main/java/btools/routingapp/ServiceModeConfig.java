package btools.routingapp;

import java.util.StringTokenizer;
import java.util.TreeSet;


/**
 * Decsription of a service config
 */
public class ServiceModeConfig
{
  public String mode;
  public String profile;
  public TreeSet<String> nogoVetos;

  public ServiceModeConfig( String line )
  {
    StringTokenizer tk = new StringTokenizer( line );
    mode = tk.nextToken();
    profile = tk.nextToken();
    nogoVetos = new TreeSet<String>();
    while( tk.hasMoreTokens() )
    {
      nogoVetos.add( tk.nextToken() );
    }
  }

  public ServiceModeConfig( String mode, String profile )
  {
    this.mode = mode;
    this.profile = profile;
    nogoVetos = new TreeSet<String>();
  }
  
  public String toLine()
  {
    StringBuilder sb = new StringBuilder( 100 );
    sb.append( mode ).append( ' ' ).append( profile );
    for( String veto: nogoVetos ) sb.append( ' ' ).append( veto );
    return sb.toString();
  }

  public String toString()
  {
    StringBuilder sb = new StringBuilder( 100 );
    sb.append( mode ).append( "->" ).append( profile );
    sb.append ( " [" + nogoVetos.size() + "]" );
    return sb.toString();
  }
}
