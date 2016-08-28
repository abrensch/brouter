package btools.expressions;

import java.util.Arrays;

import btools.util.LruMapNode;

public final class CacheNode extends LruMapNode
{
  int crc;
  byte[] ab;
  float[] vars;

  @Override
  public int hashCode()
  {
    return crc;
  }

  @Override
  public boolean equals( Object o )
  {
    CacheNode n = (CacheNode) o;
    if ( crc != n.crc )
    {
      return false;
    }
    if ( ab == null )
    {
      return true; // hack: null = crc match only
    }
    return Arrays.equals( ab, n.ab );
  }
}
