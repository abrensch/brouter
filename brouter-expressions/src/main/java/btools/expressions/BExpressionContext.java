// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import btools.util.Crc32;

public final class BExpressionContext
{
  private static final String CONTEXT_TAG = "---context:";
  private static final String VERSION_TAG = "---lookupversion:";

  private String context;
  private boolean _inOurContext = false;
  private BufferedReader _br = null;
  private boolean _readerDone = false;

  private BExpressionReceiver _receiver;

  private Map<String,Integer> lookupNumbers = new HashMap<String,Integer>();
  private ArrayList<BExpressionLookupValue[]> lookupValues = new ArrayList<BExpressionLookupValue[]>();
  private ArrayList<String> lookupNames = new ArrayList<String>();
  private ArrayList<int[]> lookupHistograms = new ArrayList<int[]>();

  private boolean lookupDataFrozen = false;

  private int[] lookupData = new int[0];

  private Map<String,Integer> variableNumbers = new HashMap<String,Integer>();

  private float[] variableData;


  // hash-cache for function results
  private long[] _arrayBitmap;
  private int currentHashBucket = -1;
  private long currentBitmap = 0;

  public List<BExpression> expressionList;

  private int minWriteIdx;

  // build-in variable indexes for fast access
  private int costfactorIdx;
  private int turncostIdx;
  private int initialcostIdx;

  private float[] _arrayCostfactor;
  private float[] _arrayTurncost;
  private float[] _arrayInitialcost;

  public float getCostfactor() { return _arrayCostfactor[currentHashBucket]; }
  public float getTurncost() { return _arrayTurncost[currentHashBucket]; }
  public float getInitialcost() { return _arrayInitialcost[currentHashBucket]; }

  private int linenr;

  public short lookupVersion = -1;

  public BExpressionContext( String context )
  {
    this( context, 4096 );
  }

  /**
   * Create an Expression-Context for the given node
   *
   * @param context  global, way or node - context of that instance
   * @param hashSize  size of hashmap for result caching
   */
  public BExpressionContext( String context, int hashSize )
  {
      this.context = context;
    _arrayBitmap = new long[hashSize];

    _arrayCostfactor = new float[hashSize];
    _arrayTurncost = new float[hashSize];
    _arrayInitialcost = new float[hashSize];
  }


  /**
   * encode lookup data to a 64-bit word
   */
  public long encode( int[] ld )
  {
    long w = 0;
    for( int inum = 0; inum < lookupValues.size(); inum++ ) // loop over lookup names
    {
      int n = lookupValues.get(inum).length - 1;
      int d = ld[inum];
      if ( n == 2 ) { n = 1; d = d == 2 ? 1 : 0; } // 1-bit encoding for booleans

      while( n != 0 ) { n >>= 1; w <<= 1; }
      w |= (long)d;
    }
    return w;
  }

  /**
   * decode a 64-bit word into a lookup data array
   */
  public void decode( int[] ld, long w )
  {
    for( int inum = lookupValues.size()-1; inum >= 0; inum-- ) // loop over lookup names
    {
      int nv = lookupValues.get(inum).length;
      int n = nv == 3 ? 1 : nv-1; // 1-bit encoding for booleans
      int m = 0;
      long ww = w;
      while( n != 0 ) { n >>= 1; ww >>= 1; m = m<<1 | 1; }
      int d = (int)(w & m);
      if ( nv == 3 && d == 1 ) d = 2; // 1-bit encoding for booleans
      ld[inum] = d;
      w = ww;
    }
  }

  /**
   * much like decode, but just for counting bits
   */
  private void countBits()
  {
    int bits = 0;
    for( int inum = lookupValues.size()-1; inum >= 0; inum-- ) // loop over lookup names
    {
      int nv = lookupValues.get(inum).length;
      int n = nv == 3 ? 1 : nv-1; // 1-bit encoding for booleans
      while( n != 0 ) { n >>= 1; bits++; }
    }
//    System.out.println( "context=" + context + ",bits=" + bits + " keys=" + lookupValues.size() );
    if ( bits > 64 ) throw new IllegalArgumentException( "lookup table for context " + context + " exceeds 64 bits!" );
  }

  public String getCsvDescription( long bitmap )
  {
     StringBuilder sb = new StringBuilder( 200 );
     decode( lookupData, bitmap );
     for( int inum = 0; inum < lookupValues.size(); inum++ ) // loop over lookup names
     {
       BExpressionLookupValue[] va = lookupValues.get(inum);
       sb.append( '\t' ).append( va[lookupData[inum]].toString() );
     }
     return sb.toString();
  }

  public String getCsvHeader()
  {
     StringBuilder sb = new StringBuilder( 200 );
     for( String name: lookupNames )
     {
       sb.append( '\t' ).append( name );
     }
     return sb.toString();
  }

  public String getKeyValueDescription( long bitmap )
  {
     StringBuilder sb = new StringBuilder( 200 );
     decode( lookupData, bitmap );
     for( int inum = 0; inum < lookupValues.size(); inum++ ) // loop over lookup names
     {
       BExpressionLookupValue[] va = lookupValues.get(inum);
       String value = va[lookupData[inum]].toString();
       if ( value != null && value.length() > 0 )
       {
         sb.append( " " + lookupNames.get( inum ) + "=" + value );
       }
     }
     return sb.toString();
  }

  public void readMetaData( File lookupsFile )
  {
   try
   {
    BufferedReader br = new BufferedReader( new FileReader( lookupsFile ) );

    int parsedLines = 0;
    boolean ourContext = false;
    for(;;)
    {
      String line = br.readLine();
      if ( line == null ) break;
      line = line.trim();
      if ( line.length() == 0 || line.startsWith( "#" ) ) continue;
      if ( line.startsWith( CONTEXT_TAG ) )
      {
        ourContext = line.substring( CONTEXT_TAG.length() ).equals( context );
        continue;
      }
      if ( line.startsWith( VERSION_TAG ) )
      {
        lookupVersion = Short.parseShort( line.substring( VERSION_TAG.length() ) );
        continue;
      }
      if ( !ourContext ) continue;
      parsedLines++;
      StringTokenizer tk = new StringTokenizer( line, " " );
      String name = tk.nextToken();
      String value = tk.nextToken();
      int idx = name.indexOf( ';' );
      if ( idx >= 0 ) name = name.substring( 0, idx );
      BExpressionLookupValue newValue = addLookupValue( name, value, null );

      // add aliases
      while( newValue != null && tk.hasMoreTokens() ) newValue.addAlias( tk.nextToken() );
    }
    br.close();
    if ( parsedLines == 0 && !"global".equals(context) )
    {
      throw new IllegalArgumentException( lookupsFile.getAbsolutePath()
             + " does not contain data for context " + context + " (old version?)" );
    }

    // post-process metadata:
    lookupDataFrozen = true;
    countBits();
   }
   catch( Exception e )
   {
       throw new RuntimeException( e );
   }
  }

  private void evaluate( int[] lookupData2 )
  {
    lookupData = lookupData2;
    for( BExpression exp: expressionList)
    {
      exp.evaluate( this );
    }
  }



  public void evaluate( long bitmap, BExpressionReceiver receiver )
  {
     _receiver = receiver;

     if ( currentBitmap != bitmap || currentHashBucket < 0 )
     {
       // calc hash bucket from crc
       int crc  = Crc32.crc( bitmap );
       int hashSize = _arrayBitmap.length;
       currentHashBucket =  (crc & 0xfffffff) % hashSize;
       currentBitmap = bitmap;
     }

     if ( _arrayBitmap[currentHashBucket] == bitmap )
     {
       return;
     }

     _arrayBitmap[currentHashBucket] = bitmap;

     decode( lookupData, bitmap );
     evaluate( lookupData );

     _arrayCostfactor[currentHashBucket] = variableData[costfactorIdx];
     _arrayTurncost[currentHashBucket] = variableData[turncostIdx];
     _arrayInitialcost[currentHashBucket] = variableData[initialcostIdx];

     _receiver = null;
  }

  public void dumpStatistics()
  {
    TreeMap<String,String> counts = new TreeMap<String,String>();
    // first count
    for( String name: lookupNumbers.keySet() )
    {
      int cnt = 0;
      int inum = lookupNumbers.get(name).intValue();
      int[] histo = lookupHistograms.get(inum);
//    if ( histo.length == 500 ) continue;
      for( int i=2; i<histo.length; i++ )
      {
        cnt += histo[i];
      }
      counts.put( "" + ( 1000000000 + cnt) + "_" + name, name );
    }

    while( counts.size() > 0 )
    {
      String key = counts.lastEntry().getKey();
      String name = counts.get(key);
      counts.remove( key );
      int inum = lookupNumbers.get(name).intValue();
      BExpressionLookupValue[] values = lookupValues.get(inum);
      int[] histo = lookupHistograms.get(inum);
      if ( values.length == 1000 ) continue;
      String[] svalues = new String[values.length];
      for( int i=0; i<values.length; i++ )
      {
        String scnt = "0000000000" + histo[i];
        scnt = scnt.substring( scnt.length() - 10 );
        svalues[i] =  scnt + " " + values[i].toString();
      }
      Arrays.sort( svalues );
      for( int i=svalues.length-1; i>=0; i-- )
      {
        System.out.println( name + ";" + svalues[i] );
      }
    }
  }

  /**
   * @return a new lookupData array, or null if no metadata defined
   */
  public int[] createNewLookupData()
  {
    if ( lookupDataFrozen )
    {
      return new int[lookupValues.size()];
    }
    return null;
  }

  /**
   * add a new lookup-value for the given name to the given lookupData array.
   * If no array is given (null value passed), the value is added to
   * the context-binded array. In that case, unknown names and values are
   * created dynamically.
   *
   * @return a newly created value element, if any, to optionally add aliases
   */
  public BExpressionLookupValue addLookupValue( String name, String value, int[] lookupData2 )
  {
    BExpressionLookupValue newValue = null;
    Integer num = lookupNumbers.get( name );
    if ( num == null )
    {
      if ( lookupData2 != null )
      {
        // do not create unknown name for external data array
        return newValue;
      }

      // unknown name, create
      num = new Integer( lookupValues.size() );
      lookupNumbers.put( name, num );
      lookupNames.add( name );
      lookupValues.add( new BExpressionLookupValue[]{ new BExpressionLookupValue( "" )
                                                    , new BExpressionLookupValue( "unknown" ) } );
      lookupHistograms.add( new int[2] );
      int[] ndata = new int[lookupData.length+1];
      System.arraycopy( lookupData, 0, ndata, 0, lookupData.length );
      lookupData = ndata;
    }

    // look for that value
    int inum = num.intValue();
    BExpressionLookupValue[] values = lookupValues.get( inum );
    int[] histo = lookupHistograms.get( inum );
    int i=0;
    for( ; i<values.length; i++ )
    {
      BExpressionLookupValue v = values[i];
      if ( v.matches( value ) ) break;
    }
    if ( i == values.length )
    {
      if ( lookupData2 != null )
      {
        // do not create unknown value for external data array,
        // record as 'other' instead
        lookupData2[inum] = 1;
        return newValue;
      }

      if ( i == 499 )
      {
        // System.out.println( "value limit reached for: " + name );
      }
      if ( i == 500 )
      {
        return newValue;
      }
      // unknown value, create
      BExpressionLookupValue[] nvalues = new BExpressionLookupValue[values.length+1];
      int[] nhisto = new int[values.length+1];
      System.arraycopy( values, 0, nvalues, 0, values.length );
      System.arraycopy( histo, 0, nhisto, 0, histo.length );
      values = nvalues;
      histo = nhisto;
      newValue = new BExpressionLookupValue( value );
      values[i] = newValue;
      lookupHistograms.set(inum, histo);
      lookupValues.set(inum, values);
    }

    histo[i]++;

    // finally remember the actual data
    if ( lookupData2 != null ) lookupData2[inum] = i;
    else lookupData[inum] = i;
    return newValue;
  }

  public void parseFile( File file, String readOnlyContext )
  {
    try
    {
      if ( readOnlyContext != null )
      {
        linenr = 1;
        String realContext = context;
        context = readOnlyContext;
        expressionList = _parseFile( file );
        variableData = new float[variableNumbers.size()];
        evaluate( 1L, null );
        context = realContext;
      }
      linenr = 1;
      minWriteIdx = variableData == null ? 0 : variableData.length;

      costfactorIdx = getVariableIdx( "costfactor", true );
      turncostIdx = getVariableIdx( "turncost", true );
      initialcostIdx = getVariableIdx( "initialcost", true );

      expressionList = _parseFile( file );
      float[] readOnlyData = variableData;
      variableData = new float[variableNumbers.size()];
      for( int i=0; i<minWriteIdx; i++ )
      {
        variableData[i] = readOnlyData[i];
      }
    }
    catch( Exception e )
    {
      if ( e instanceof IllegalArgumentException )
      {
        throw new IllegalArgumentException( "ParseException at line " + linenr + ": " + e.getMessage() );
      }
      throw new RuntimeException( e );
    }
    if ( expressionList.size() == 0 )
    {
        throw new IllegalArgumentException( file.getAbsolutePath()
             + " does not contain expressions for context " + context + " (old version?)" );
    }
  }

  private List<BExpression> _parseFile( File file ) throws Exception
  {
    _br = new BufferedReader( new FileReader( file ) );
    _readerDone = false;
    List<BExpression> result = new ArrayList<BExpression>();
    for(;;)
    {
      BExpression exp = BExpression.parse( this, 0 );
      if ( exp == null ) break;
      result.add( exp );
    }
    _br.close();
    _br = null;
    return result;
  }


  public float getVariableValue( String name, float defaultValue )
  {
    Integer num = variableNumbers.get( name );
    return num == null ? defaultValue : getVariableValue( num.intValue() );
  }

  public float getVariableValue( String name )
  {
    Integer num = variableNumbers.get( name );
    return num == null ? 0.f : getVariableValue( num.intValue() );
  }

  public float getVariableValue( int variableIdx )
  {
    return variableData[variableIdx];
  }

  public int getVariableIdx( String name, boolean create )
  {
    Integer num = variableNumbers.get( name );
    if ( num == null )
    {
      if ( create )
      {
        num = new Integer( variableNumbers.size() );
        variableNumbers.put( name, num );
      }
      else
      {
        return -1;
      }
    }
    return num.intValue();
  }

  public int getMinWriteIdx()
  {
    return minWriteIdx;
  }

  public float getLookupMatch( int nameIdx, int valueIdx )
  {
    return lookupData[nameIdx] == valueIdx ? 1.0f : 0.0f;
  }

  public int getLookupNameIdx( String name )
  {
    Integer num = lookupNumbers.get( name );
    return num == null ? -1 : num.intValue();
  }

  public int getLookupValueIdx( int nameIdx, String value )
  {
    BExpressionLookupValue[] values = lookupValues.get( nameIdx );
    for( int i=0; i< values.length; i++ )
    {
      if ( values[i].equals( value ) ) return i;
    }
    return -1;
  }


  public String parseToken() throws Exception
  {
    for(;;)
    {
      String token = _parseToken();
      if ( token == null ) return null;
      if ( token.startsWith( CONTEXT_TAG ) )
      {
        _inOurContext = token.substring( CONTEXT_TAG.length() ).equals( context );
      }
      else if ( _inOurContext )
      {
        return token;
      }
    }
  }


  private String _parseToken() throws Exception
  {
    StringBuilder sb = new StringBuilder(32);
    boolean inComment = false;
    for(;;)
    {
      int ic = _readerDone ? -1 : _br.read();
      if ( ic < 0 )
      {
        if ( sb.length() == 0 ) return null;
        _readerDone = true;
         return sb.toString();
      }
      char c = (char)ic;
      if ( c == '\n' ) linenr++;

      if ( inComment )
      {
        if ( c == '\r' || c == '\n' ) inComment = false;
        continue;
      }
      if ( Character.isWhitespace( c ) )
      {
        if ( sb.length() > 0 ) return sb.toString();
        else continue;
      }
      if ( c == '#' && sb.length() == 0 ) inComment = true;
      else sb.append( c );
    }
  }

  public float assign( int variableIdx, float value )
  {
    variableData[variableIdx] = value;
    return value;
  }

  public void expressionWarning( String message )
  {
    _arrayBitmap[currentHashBucket] = 0L; // no caching if warnings
     if ( _receiver != null ) _receiver.expressionWarning( context, message );
  }
}
