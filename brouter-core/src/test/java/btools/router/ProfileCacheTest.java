package btools.router;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import org.junit.After;
import org.junit.Test;

/**
 * Regression tests for {@link ProfileCache} profile-file resolution, guarding
 * against a process-global {@code profileBaseDir} system property (set by a
 * long-lived HTTP server and never cleared) hijacking an unrelated caller's
 * absolute profile path within the same JVM.
 */
public class ProfileCacheTest {

  @After
  public void clearLeakedBaseDir() {
    System.clearProperty("profileBaseDir");
  }

  /**
   * An absolute {@link RoutingContext#localFunction} points at a concrete profile
   * file and must resolve against its own directory, even when an ambient
   * {@code profileBaseDir} system property (e.g. leaked by a server started
   * earlier in the JVM) points somewhere without a {@code lookups.dat}. Before
   * the fix this threw {@code FileNotFoundException} for
   * {@code /no/such/server/dir/lookups.dat}.
   */
  @Test
  public void absoluteProfilePathIgnoresLeakedSystemProperty() {
    // simulate a server having leaked its own base dir into the JVM
    System.setProperty("profileBaseDir", new File("/no/such/server/dir").getAbsolutePath());

    RoutingContext rc = new RoutingContext();
    rc.localFunction = RoundTripFixture.profileFile("trekking").getAbsolutePath();
    try {
      ProfileCache.parseProfile(rc);
      assertNotNull("absolute profile path did not resolve against its own dir", rc.expctxWay);
    } finally {
      ProfileCache.releaseProfile(rc);
    }
  }

  /**
   * A per-context {@link RoutingContext#profileBaseDir} resolves a bare profile
   * NAME against that directory (the server's mode) and takes precedence over any
   * ambient system property.
   */
  @Test
  public void perContextBaseDirResolvesProfileName() {
    // a stale/foreign system property must not win over the explicit context field
    System.setProperty("profileBaseDir", new File("/no/such/server/dir").getAbsolutePath());

    RoutingContext rc = new RoutingContext();
    rc.profileBaseDir = new File(RoundTripFixture.projectDir(), "misc/profiles2").getAbsolutePath();
    rc.localFunction = "trekking";
    try {
      ProfileCache.parseProfile(rc);
      assertNotNull("profile name did not resolve against the context base dir", rc.expctxWay);
    } finally {
      ProfileCache.releaseProfile(rc);
    }
  }
}
