package btools.util;

public abstract class LruMapNode
{
  LruMapNode nextInBin; // next entry for hash-bin
  LruMapNode next; // next in lru sequence (towards mru)
  LruMapNode previous; // previous in lru sequence (towards lru)
  
  public int hash;
}
