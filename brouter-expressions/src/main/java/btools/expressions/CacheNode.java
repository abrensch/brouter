package btools.expressions;

import java.util.Arrays;

import btools.util.LruMapNode;

public final class CacheNode extends LruMapNode {
  byte[] ab;
  float[] vars;

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object o) {
    CacheNode n = (CacheNode) o;
    return hash == n.hash && Arrays.equals(ab, n.ab);
  }
}
