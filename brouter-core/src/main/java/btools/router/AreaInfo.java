package btools.router;

import btools.expressions.BExpressionContext;

/**
* The AreaInfo class is a specialized geographical data structure designed for routing and terrain analysis 
* in the btools.router package. It captures and quantifies spatial characteristics of an area, tracking key metrics such as 
* ways, forest and river coverage, and elevation. The class supports directional-based geographical assessment by storing 
* information like the number of forest and river ways, total ways, and elevation data. With predefined 
* result types (NONE, ELEV50, GREEN, RIVER), it enables systematic computation and reporting of terrain features, 
* facilitating detailed geographical analysis and routing calculations. The constructor initializes the 
* area information with a specific direction, and the class is prepared to aggregate and process spatial metrics 
* for routing and environmental evaluation purposes.
*/

public class AreaInfo {
  final static int RESULT_TYPE_NONE = 0;
  final static int RESULT_TYPE_ELEV50 = 1;
  final static int RESULT_TYPE_GREEN = 4;
  final static int RESULT_TYPE_RIVER = 5;

  public int direction;
  public int numForest = -1;
  public int numRiver = -1;

  public OsmNogoPolygon polygon;

  public int ways = 0;
  public int greenWays = 0;
  public int riverWays = 0;
  public double elevStart = 0;
  public int elev50 = 0;

  public AreaInfo(int dir) {
    direction = dir;
  }

  void checkAreaInfo(BExpressionContext expctxWay, double elev, byte[] ab) {
    ways++;

    double test = elevStart - elev;
    if (Math.abs(test) < 50) elev50++;

    int[] ld2 = expctxWay.createNewLookupData();
    expctxWay.decode(ld2, false, ab);

    if (numForest != -1 && ld2[numForest] > 1) {
      greenWays++;
    }

    if (numRiver != -1 && ld2[numRiver] > 1) {
      riverWays++;
    }

  }

  public int getElev50Weight() {
    if (ways == 0) return 0;
    return (int) (elev50 * 100. / ways);
  }

  public int getGreen() {
    if (ways == 0) return 0;
    return (int) (greenWays * 100. / ways);
  }

  public int getRiver() {
    if (ways == 0) return 0;
    return (int) (riverWays * 100. / ways);
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Area ").append(direction).append(" ").append(elevStart).append("m ways ").append(ways);
    if (ways > 0) {
      sb.append("\nArea ways <50m  ").append(elev50).append(" ").append(getElev50Weight()).append("%");
      sb.append("\nArea ways green ").append(greenWays).append(" ").append(getGreen()).append("%");
      sb.append("\nArea ways river ").append(riverWays).append(" ").append(getRiver()).append("%");
    }
    return sb.toString();
  }
}
