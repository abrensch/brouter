package btools.routingapp;

import java.io.Serializable;

public class RoutingParam implements Serializable {
  public String name;
  public String description;
  public String type;
  public String value;

  public String toString() {
    return "RoutingParam " + name + " = " + value +" type: " + type + " txt: " + description;
  }
}
