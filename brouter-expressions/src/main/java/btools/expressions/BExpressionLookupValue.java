/**
 * A lookup value with optional aliases
 * <p>
 * toString just gives the primary value,
 * equals just compares against primary value
 * matches() also compares aliases
 *
 * @author ab
 */
package btools.expressions;

import java.util.ArrayList;
import java.util.List;

final class BExpressionLookupValue {
  String value;
  List<String> aliases;

  @Override
  public String toString() {
    return value;
  }

  public BExpressionLookupValue(String value) {
    this.value = value;
  }

  public void addAlias(String alias) {
    if (aliases == null) aliases = new ArrayList<>();
    aliases.add(alias);
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof String) {
      String v = (String) o;
      return value.equals(v);
    }
    if (o instanceof BExpressionLookupValue) {
      BExpressionLookupValue v = (BExpressionLookupValue) o;

      return value.equals(v.value);
    }
    return false;
  }

  public boolean matches(String s) {
    if (value.equals(s)) return true;
    if (aliases != null) {
      for (String alias : aliases) {
        if (alias.equals(s)) return true;
      }
    }
    return false;
  }

}
