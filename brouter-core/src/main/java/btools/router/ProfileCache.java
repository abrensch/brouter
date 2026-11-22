/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import java.io.File;

import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;

public final class ProfileCache {

  private static File lastLookupFile;
  private static long lastLookupTimestamp;

  private BExpressionContextWay expctxWay;
  private BExpressionContextNode expctxNode;
  private File lastProfileFile;
  private long lastProfileTimestamp;
  private boolean profilesBusy;
  private long lastUseTime;

  private static ProfileCache[] apc = new ProfileCache[1];

  public static synchronized void setSize(int size) {
    apc = new ProfileCache[size];
  }

  public static synchronized boolean parseProfile(File profileDir, RoutingContext rc) {
    File profileFile = new File(profileDir, rc.profileName + ".brf" ) ;

    rc.profileTimestamp = profileFile.lastModified() + rc.getKeyValueChecksum() << 24;
    File lookupFile = new File(profileDir, "lookups.dat");

    // invalidate cache at lookup-table update
    if (!(lookupFile.equals(lastLookupFile) && lookupFile.lastModified() == lastLookupTimestamp)) {
      apc = new ProfileCache[apc.length];
      lastLookupFile = lookupFile;
      lastLookupTimestamp = lookupFile.lastModified();
    }

    ProfileCache lru = null;
    int unusedSlot = -1;

    // check for re-use
    for (int i = 0; i < apc.length; i++) {
      ProfileCache pc = apc[i];

      if (pc != null) {
        if ((!pc.profilesBusy) && profileFile.equals(pc.lastProfileFile)) {
          if (rc.profileTimestamp == pc.lastProfileTimestamp) {
            rc.expCtxWay = pc.expctxWay;
            rc.expCtxNode = pc.expctxNode;
            rc.readGlobalConfig();
            pc.profilesBusy = true;
            return true;
          }
          lru = pc; // name-match but timestamp-mismatch -> we overide this one
          unusedSlot = -1;
          break;
        }
        if (lru == null || lru.lastUseTime > pc.lastUseTime) {
          lru = pc;
        }
      } else if (unusedSlot < 0) {
        unusedSlot = i;
      }
    }

    BExpressionMetaData meta = new BExpressionMetaData();

    rc.expCtxWay = new BExpressionContextWay(rc.memoryclass * 512, meta);
    rc.expCtxNode = new BExpressionContextNode(meta);
    rc.expCtxNode.setForeignContext(rc.expCtxWay);

    meta.readMetaData(new File(profileDir, "lookups.dat"));

    rc.expCtxWay.parseFile(profileFile, "global", rc.keyValues);
    rc.expCtxNode.parseFile(profileFile, "global", rc.keyValues);

    rc.readGlobalConfig();

    if (rc.processUnusedTags) {
      rc.expCtxWay.setAllTagsUsed();
    }

    if (lru == null || unusedSlot >= 0) {
      lru = new ProfileCache();
      if (unusedSlot >= 0) {
        apc[unusedSlot] = lru;
      }
    }

    lru.lastProfileTimestamp = rc.profileTimestamp;
    lru.lastProfileFile = profileFile;
    lru.expctxWay = rc.expCtxWay;
    lru.expctxNode = rc.expCtxNode;
    lru.profilesBusy = true;
    lru.lastUseTime = System.currentTimeMillis();
    return false;
  }

  public static synchronized void releaseProfile(RoutingContext rc) {
    for (int i = 0; i < apc.length; i++) {
      ProfileCache pc = apc[i];

      if (pc != null) {
        // only the thread that holds the cached instance can release it
        if (rc.expCtxWay == pc.expctxWay && rc.expCtxNode == pc.expctxNode) {
          pc.profilesBusy = false;
          break;
        }
      }
    }
    rc.expCtxWay = null;
    rc.expCtxNode = null;
  }

}
