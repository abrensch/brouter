package btools.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Memory efficient Heap to get the lowest-key value of a set of key-object pairs
 * 
 * @author ab
 */
public final class SortedHeap<V>
{
  private int[][] al;
  private int[] lp; // the low pointers

  private Object[][] vla; // value list array

  protected static final int MAXLISTS = 28; // enough for size = ca Integer.MAX_VALUE

  private int size;
  private boolean isClear = false;

  public SortedHeap()
  {
    clear();
  }

  /**
   * @return the lowest key value, or null if none
   */
  public V popLowestKeyValue()
  {
    int minId = 0;
    int minIdx = -1;
    for ( int i = 0;; i++ )
    {
      int[] ali = al[i];
      if ( ali == null )
        break;
      int lpi = lp[i];
      if ( lpi < 4 << i )
      {
        int currentId = ali[lpi];
        if ( minIdx < 0 || currentId < minId )
        {
          minIdx = i;
          minId = currentId;
        }
      }
    }

    if ( minIdx == -1 )
      return null;

    int lp_minIdx = lp[minIdx]++;
    Object[] vla_minIdx = vla[minIdx];
    V res = (V) vla_minIdx[lp_minIdx];
    vla_minIdx[lp_minIdx] = null;
    size--;
    return res;
  }

  /**
   * add a key value pair to the heap
   * 
   * @param id
   *          the key to insert
   * @param value
   *          the value to insert object
   */
  public void add( int key, V value )
  {
    isClear = false;
    size++;

    if ( lp[0] == 0 )
    {
      sortUp();
    }
    int lp0 = lp[0];
    
    int[] al0 = al[0];
    Object[] vla0 = vla[0];
      
    for(;;)
    {
      if ( lp0 == 4 || key < al0[lp0] )
      {
        al0[lp0-1] = key;
        vla0[lp0-1] = value;
        lp[0]--;
        return;
      }
      al0[lp0-1] = al0[lp0];
      vla0[lp0-1] = vla0[lp0];
      lp0++;
    }
  }
  
  private void sortUp()
  {
    // determine the first array big enough to take them all
    int cnt = 4; // value count up to idx
    int idx = 1;
    int n = 8;
    int firstNonEmptyIdx = 0;
    int nonEmptyCount = 1;

    for ( ;; )
    {
      int nentries = n - lp[idx];
      if ( nentries > 0 )
      {
        cnt += n - lp[idx];
        nonEmptyCount++;
      }
      if ( cnt <= n )
      {
        break;
      }
      idx++;
      n <<= 1;
    }

    // create it if not existant
    if ( al[idx] == null )
    {
      al[idx] = new int[n];
      vla[idx] = new Object[n];
    }

    int[] al_t = al[idx];
    Object[] vla_t = vla[idx];
    int tp = n-cnt; // target pointer

    // now merge the contents of arrays 0...idx into idx
    while( nonEmptyCount > 1 )
    {
      int i = firstNonEmptyIdx;
      int minId = al[i][lp[i]];
      int minIdx = i;

      for ( i++; i <= idx; i++ )
      {
        if ( 4 << i > lp[i] )
        {
          int currentId = al[i][lp[i]];
          if ( currentId < minId )
          {
            minIdx = i;
            minId = currentId;
          }
        }
      }

      // current minimum found, copy to target array
      int sp = lp[minIdx]; // source-pointer
      al_t[tp] = minId;      
      vla_t[tp++] = vla[minIdx][sp];
      if ( minIdx != idx )
      {
        vla[minIdx][sp] = null;
      }
      if ( ++lp[minIdx] ==  4 << minIdx )
      {
        nonEmptyCount--;
        if ( minIdx == firstNonEmptyIdx )
        {
          while( lp[firstNonEmptyIdx] == 4 << firstNonEmptyIdx )
          {
            firstNonEmptyIdx++;
          }
        }
      }
    }

    // only one non-empty index left, so just copy the remaining entries
    if ( firstNonEmptyIdx != idx ) // no self-copy needed
    {
      int[] al_s = al[firstNonEmptyIdx];
      Object[] vla_s = vla[firstNonEmptyIdx];
      int sp = lp[firstNonEmptyIdx]; // source-pointer      
      while( sp < 4 << firstNonEmptyIdx )
      {
        al_t[tp] = al_s[sp];     
        vla_t[tp++] = vla_s[sp];
        vla_s[sp++] = null;
      }
      lp[firstNonEmptyIdx] = sp;
    }
    lp[idx] = n-cnt; // new target low pointer
  }

  public void clear()
  {
    if ( !isClear )
    {
      isClear = true;
      size = 0;

      lp = new int[MAXLISTS];

      // allocate key lists
      al = new int[MAXLISTS][];
      al[0] = new int[4]; // make the first array

      // same for the values
      vla = new Object[MAXLISTS][];
      vla[0] = new Object[4];

      int n = 4;
      for ( int idx = 0; idx < MAXLISTS; idx++ )
      {
        lp[idx] = n;
        n <<= 1;
      }
    }
  }

  public List<V> getExtract()
  {
    int div = size / 1000 + 1;

    ArrayList<V> res = new ArrayList<V>( size / div );
    int cnt = 0;
    for ( int i = 1;; i++ )
    {
      int[] ali = al[i];
      if ( ali == null )
        break;
      int lpi = lp[i];
      Object[] vlai = vla[i];
      int n = 4 << i;
      while (lpi < n)
      {
        if ( ( ++cnt ) % div == 0 )
        {
          res.add( (V) vla[i][lpi] );
        }
        lpi++;
      }
    }
    return res;
  }
  
  public static void main(String[] args)
  {
    SortedHeap<String> sh = new SortedHeap<String>();
    Random rnd = new Random();
    for( int i = 0; i< 6; i++ )
    {
      int val = rnd.nextInt( 1000000 );
      sh.add( val, "" + val );
      val = rnd.nextInt( 1000000 );
      sh.add( val, "" + val );
      sh.popLowestKeyValue();
    }

    int cnt = 0;
    int lastval = 0;
    for(;;)
    {
      String s = sh.popLowestKeyValue();
      if ( s == null ) break;
      cnt ++;
      int val = Integer.parseInt( s );
System.out.println( "popLowestKeyValue: " + val);
//      Assert.assertTrue( "sorting test", val >= lastval );
      lastval = val;
    }
//    Assert.assertTrue( "total count test", cnt == 100000 );

  }
  

}
