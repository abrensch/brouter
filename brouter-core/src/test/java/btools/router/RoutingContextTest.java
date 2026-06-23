package btools.router;

import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import btools.mapaccess.OsmNode;

/**
 * Unit tests for {@link RoutingContext#copyRequestFields()}, the request-field
 * copy used by the AUTO round-trip competition to build isolated child engine
 * contexts.
 */
public class RoutingContextTest {

  /**
   * Regression guard: a child context produced by {@link RoutingContext#copyRequestFields()}
   * must own an independent {@code nogopoints} list. The competition spawns child
   * engines, and a child running a {@code continueStraight} profile appends synthetic
   * nogo points to {@code routingContext.nogopoints} during routing
   * ({@code RoutingEngine.doRouting}). If the list were aliased to the parent's, those
   * child-generated nogos would leak back into the parent and contaminate every
   * subsequent candidate and the final adopted route.
   */
  @Test
  public void copyRequestFieldsDoesNotAliasNogoList() {
    RoutingContext parent = new RoutingContext();
    parent.nogopoints = new ArrayList<>();
    parent.nogopoints.add(new OsmNodeNamed(new OsmNode(1, 2)));
    int parentSizeBefore = parent.nogopoints.size();

    RoutingContext child = parent.copyRequestFields();

    // The child must have its own list (same content), not the same instance.
    Assert.assertNotNull("child nogopoints copied", child.nogopoints);
    Assert.assertNotSame("child must not share the parent's nogo list instance",
      parent.nogopoints, child.nogopoints);
    Assert.assertEquals("child starts with the same nogos",
      parentSizeBefore, child.nogopoints.size());

    // Simulate the child engine's continueStraight nogo injection.
    child.nogopoints.add(new OsmNodeNamed(new OsmNode(3, 4)));

    Assert.assertEquals("parent nogo list must not grow when the child adds a nogo",
      parentSizeBefore, parent.nogopoints.size());
  }

  /** A null nogo list must copy as null without throwing. */
  @Test
  public void copyRequestFieldsHandlesNullNogoList() {
    RoutingContext parent = new RoutingContext();
    parent.nogopoints = null;
    RoutingContext child = parent.copyRequestFields();
    Assert.assertNull("null nogo list copies as null", child.nogopoints);
  }
}
