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

  private static int[] crctable = {
          0x00000000, 0x77073096, 0xee0e612c, 0x990951ba, 0x076dc419, 0x706af48f, 0xe963a535, 0x9e6495a3,
          0x0edb8832, 0x79dcb8a4, 0xe0d5e91e, 0x97d2d988, 0x09b64c2b, 0x7eb17cbd, 0xe7b82d07, 0x90bf1d91,
          0x1db71064, 0x6ab020f2, 0xf3b97148, 0x84be41de, 0x1adad47d, 0x6ddde4eb, 0xf4d4b551, 0x83d385c7,
          0x136c9856, 0x646ba8c0, 0xfd62f97a, 0x8a65c9ec, 0x14015c4f, 0x63066cd9, 0xfa0f3d63, 0x8d080df5,
          0x3b6e20c8, 0x4c69105e, 0xd56041e4, 0xa2677172, 0x3c03e4d1, 0x4b04d447, 0xd20d85fd, 0xa50ab56b,
          0x35b5a8fa, 0x42b2986c, 0xdbbbc9d6, 0xacbcf940, 0x32d86ce3, 0x45df5c75, 0xdcd60dcf, 0xabd13d59,
          0x26d930ac, 0x51de003a, 0xc8d75180, 0xbfd06116, 0x21b4f4b5, 0x56b3c423, 0xcfba9599, 0xb8bda50f,
          0x2802b89e, 0x5f058808, 0xc60cd9b2, 0xb10be924, 0x2f6f7c87, 0x58684c11, 0xc1611dab, 0xb6662d3d,
          0x76dc4190, 0x01db7106, 0x98d220bc, 0xefd5102a, 0x71b18589, 0x06b6b51f, 0x9fbfe4a5, 0xe8b8d433,
          0x7807c9a2, 0x0f00f934, 0x9609a88e, 0xe10e9818, 0x7f6a0dbb, 0x086d3d2d, 0x91646c97, 0xe6635c01,
          0x6b6b51f4, 0x1c6c6162, 0x856530d8, 0xf262004e, 0x6c0695ed, 0x1b01a57b, 0x8208f4c1, 0xf50fc457,
          0x65b0d9c6, 0x12b7e950, 0x8bbeb8ea, 0xfcb9887c, 0x62dd1ddf, 0x15da2d49, 0x8cd37cf3, 0xfbd44c65,
          0x4db26158, 0x3ab551ce, 0xa3bc0074, 0xd4bb30e2, 0x4adfa541, 0x3dd895d7, 0xa4d1c46d, 0xd3d6f4fb,
          0x4369e96a, 0x346ed9fc, 0xad678846, 0xda60b8d0, 0x44042d73, 0x33031de5, 0xaa0a4c5f, 0xdd0d7cc9,
          0x5005713c, 0x270241aa, 0xbe0b1010, 0xc90c2086, 0x5768b525, 0x206f85b3, 0xb966d409, 0xce61e49f,
          0x5edef90e, 0x29d9c998, 0xb0d09822, 0xc7d7a8b4, 0x59b33d17, 0x2eb40d81, 0xb7bd5c3b, 0xc0ba6cad,
          0xedb88320, 0x9abfb3b6, 0x03b6e20c, 0x74b1d29a, 0xead54739, 0x9dd277af, 0x04db2615, 0x73dc1683,
          0xe3630b12, 0x94643b84, 0x0d6d6a3e, 0x7a6a5aa8, 0xe40ecf0b, 0x9309ff9d, 0x0a00ae27, 0x7d079eb1,
          0xf00f9344, 0x8708a3d2, 0x1e01f268, 0x6906c2fe, 0xf762575d, 0x806567cb, 0x196c3671, 0x6e6b06e7,
          0xfed41b76, 0x89d32be0, 0x10da7a5a, 0x67dd4acc, 0xf9b9df6f, 0x8ebeeff9, 0x17b7be43, 0x60b08ed5,
          0xd6d6a3e8, 0xa1d1937e, 0x38d8c2c4, 0x4fdff252, 0xd1bb67f1, 0xa6bc5767, 0x3fb506dd, 0x48b2364b,
          0xd80d2bda, 0xaf0a1b4c, 0x36034af6, 0x41047a60, 0xdf60efc3, 0xa867df55, 0x316e8eef, 0x4669be79,
          0xcb61b38c, 0xbc66831a, 0x256fd2a0, 0x5268e236, 0xcc0c7795, 0xbb0b4703, 0x220216b9, 0x5505262f,
          0xc5ba3bbe, 0xb2bd0b28, 0x2bb45a92, 0x5cb36a04, 0xc2d7ffa7, 0xb5d0cf31, 0x2cd99e8b, 0x5bdeae1d,
          0x9b64c2b0, 0xec63f226, 0x756aa39c, 0x026d930a, 0x9c0906a9, 0xeb0e363f, 0x72076785, 0x05005713,
          0x95bf4a82, 0xe2b87a14, 0x7bb12bae, 0x0cb61b38, 0x92d28e9b, 0xe5d5be0d, 0x7cdcefb7, 0x0bdbdf21,
          0x86d3d2d4, 0xf1d4e242, 0x68ddb3f8, 0x1fda836e, 0x81be16cd, 0xf6b9265b, 0x6fb077e1, 0x18b74777,
          0x88085ae6, 0xff0f6a70, 0x66063bca, 0x11010b5c, 0x8f659eff, 0xf862ae69, 0x616bffd3, 0x166ccf45,
          0xa00ae278, 0xd70dd2ee, 0x4e048354, 0x3903b3c2, 0xa7672661, 0xd06016f7, 0x4969474d, 0x3e6e77db,
          0xaed16a4a, 0xd9d65adc, 0x40df0b66, 0x37d83bf0, 0xa9bcae53, 0xdebb9ec5, 0x47b2cf7f, 0x30b5ffe9,
          0xbdbdf21c, 0xcabac28a, 0x53b39330, 0x24b4a3a6, 0xbad03605, 0xcdd70693, 0x54de5729, 0x23d967bf,
          0xb3667a2e, 0xc4614ab8, 0x5d681b02, 0x2a6f2b94, 0xb40bbe37, 0xc30c8ea1, 0x5a05df1b, 0x2d02ef8d,
      };


  public void evaluate( long bitmap, BExpressionReceiver receiver )
  {
     _receiver = receiver;

     if ( currentBitmap != bitmap || currentHashBucket < 0 )
     {
       // calc hash bucket from crc
       int crc  = 0xFFFFFFFF;
       long bm = bitmap;
       for( int j=0; j<8; j++ )
       {
         crc = (crc >>> 8) ^ crctable[(crc ^ (int)bm) & 0xff];
         bm >>= 8;
       }
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
