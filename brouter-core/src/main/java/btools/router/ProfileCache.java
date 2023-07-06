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
  private static boolean debug = Boolean.getBoolean("debugProfileCache");

  public static synchronized void setSize(int size) {
    apc = new ProfileCache[size];
  }

  public static synchronized boolean parseProfile(RoutingContext rc) {
    String profileBaseDir = System.getProperty("profileBaseDir");
    File profileDir;
    File profileFile;
    if (profileBaseDir == null) {
      profileDir = new File(rc.localFunction).getParentFile();
      profileFile = new File(rc.localFunction);
    } else {
      profileDir = new File(profileBaseDir);
      profileFile = new File(profileDir, rc.localFunction + ".brf");
    }

    rc.profileTimestamp = profileFile.lastModified() + rc.getKeyValueChecksum() << 24;
    File lookupFile = new File(profileDir, "lookups.dat");

    // invalidate cache at lookup-table update
    if (!(lookupFile.equals(lastLookupFile) && lookupFile.lastModified() == lastLookupTimestamp)) {
      if (lastLookupFile != null) {
        System.out.println("******** invalidating profile-cache after lookup-file update ******** ");
      }
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
            rc.expctxWay = pc.expctxWay;
            rc.expctxNode = pc.expctxNode;
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

    rc.expctxWay = new BExpressionContextWay(rc.memoryclass * 512, meta);
    rc.expctxNode = new BExpressionContextNode(0, meta);
    rc.expctxNode.setForeignContext(rc.expctxWay);

    meta.readMetaData(new File(profileDir, "lookups.dat"));

    rc.expctxWay.parseFile(profileFile, "global", rc.keyValues);
    rc.expctxNode.parseFile(profileFile, "global", rc.keyValues);

    rc.readGlobalConfig();

    if (rc.processUnusedTags) {
      rc.expctxWay.setAllTagsUsed();
    }

    if (lru == null || unusedSlot >= 0) {
      lru = new ProfileCache();
      if (unusedSlot >= 0) {
        apc[unusedSlot] = lru;
        if (debug)
          System.out.println("******* adding new profile at idx=" + unusedSlot + " for " + profileFile);
      }
    }

    if (lru.lastProfileFile != null) {
      if (debug)
        System.out.println("******* replacing profile of age " + ((System.currentTimeMillis() - lru.lastUseTime) / 1000L) + " sec " + lru.lastProfileFile + "->" + profileFile);
    }

    lru.lastProfileTimestamp = rc.profileTimestamp;
    lru.lastProfileFile = profileFile;
    lru.expctxWay = rc.expctxWay;
    lru.expctxNode = rc.expctxNode;
    lru.profilesBusy = true;
    lru.lastUseTime = System.currentTimeMillis();
    return false;
  }

  public static synchronized void releaseProfile(RoutingContext rc) {
    for (int i = 0; i < apc.length; i++) {
      ProfileCache pc = apc[i];

      if (pc != null) {
        // only the thread that holds the cached instance can release it
        if (rc.expctxWay == pc.expctxWay && rc.expctxNode == pc.expctxNode) {
          pc.profilesBusy = false;
          break;
        }
      }
    }
    rc.expctxWay = null;
    rc.expctxNode = null;
  }

}
