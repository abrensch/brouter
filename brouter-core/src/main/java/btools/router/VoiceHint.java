/**
 * Container for a voice hint
 * (both input- and result data for voice hint processing)
 *
 * @author ab
 */
package btools.router;

import java.util.ArrayList;
import java.util.List;

public class VoiceHint {
  enum Command {
    CONTINUE,
    TURN_LEFT,
    TURN_SLIGHTLY_LEFT,
    TURN_SHARPLY_LEFT,
    TURN_RIGHT,
    TURN_SLIGHTLY_RIGHT,
    TURN_SHARPLY_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    U_TURN_LEFT,
    U_TURN_180,
    U_TURN_RIGHT,
    OFF_ROUTE,
    ROUNDABOUT_RIGHT,
    ROUNDABOUT_LEFT,
    BEELINE,
  }

  int ilon;
  int ilat;
  short selev;
  Command cmd;
  MessageData oldWay;
  MessageData goodWay;
  List<MessageData> badWays;
  double distanceToNext;
  int indexInTrack;

  public float getTime() {
    return oldWay == null ? 0.f : oldWay.time;
  }

  float angle = Float.MAX_VALUE;
  boolean turnAngleConsumed;
  boolean needsRealTurn;

  int roundaboutExit;

  boolean isRoundabout() {
    return roundaboutExit != 0;
  }

  public void addBadWay(MessageData badWay) {
    if (badWay == null) {
      return;
    }
    if (badWays == null) {
      badWays = new ArrayList<>();
    }
    badWays.add(badWay);
  }

  public int getJsonCommandIndex() {
    switch (cmd) {
      case U_TURN_LEFT:
        return 10;
      case U_TURN_180:
        return 15;
      case TURN_SHARPLY_LEFT:
        return 4;
      case TURN_LEFT:
        return 2;
      case TURN_SLIGHTLY_LEFT:
        return 3;
      case KEEP_LEFT:
        return 8;
      case CONTINUE:
        return 1;
      case KEEP_RIGHT:
        return 9;
      case TURN_SLIGHTLY_RIGHT:
        return 6;
      case TURN_RIGHT:
        return 5;
      case TURN_SHARPLY_RIGHT:
        return 7;
      case U_TURN_RIGHT:
        return 11;
      case ROUNDABOUT_RIGHT:
        return 13;
      case ROUNDABOUT_LEFT:
        return 14;
      case BEELINE:
        return 16;
      case OFF_ROUTE:
        return 12;
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  public int getExitNumber() {
    return roundaboutExit;
  }

  /*
   * used by comment style, osmand style
   */
  public String getCommandString() {
    switch (cmd) {
      case U_TURN_LEFT:
        return "TU";  // should be changed to TLU when osmand uses new voice hint constants
      case U_TURN_180:
        return "TU";
      case TURN_SHARPLY_LEFT:
        return "TSHL";
      case TURN_LEFT:
        return "TL";
      case TURN_SLIGHTLY_LEFT:
        return "TSLL";
      case KEEP_LEFT:
        return "KL";
      case CONTINUE:
        return "C";
      case KEEP_RIGHT:
        return "KR";
      case TURN_SLIGHTLY_RIGHT:
        return "TSLR";
      case TURN_RIGHT:
        return "TR";
      case TURN_SHARPLY_RIGHT:
        return "TSHR";
      case U_TURN_RIGHT:
        return "TRU";
      case ROUNDABOUT_RIGHT:
        return "RNDB" + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return "RNLB" + (-roundaboutExit);
      case BEELINE:
        return "BL";
      case OFF_ROUTE:
        return "OFFR";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by trkpt/sym style
   */
  public String getCommandString(Command c) {
    switch (c) {
      case U_TURN_LEFT:
        return "TLU";
      case U_TURN_180:
        return "TU";
      case TURN_SHARPLY_LEFT:
        return "TSHL";
      case TURN_LEFT:
        return "TL";
      case TURN_SLIGHTLY_LEFT:
        return "TSLL";
      case KEEP_LEFT:
        return "KL";
      case CONTINUE:
        return "C";
      case KEEP_RIGHT:
        return "KR";
      case TURN_SLIGHTLY_RIGHT:
        return "TSLR";
      case TURN_RIGHT:
        return "TR";
      case TURN_SHARPLY_RIGHT:
        return "TSHR";
      case U_TURN_RIGHT:
        return "TRU";
      case ROUNDABOUT_RIGHT:
        return "RNDB" + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return "RNLB" + (-roundaboutExit);
      case BEELINE:
        return "BL";
      case OFF_ROUTE:
        return "OFFR";
      default:
        return "unknown command: " + c;
    }
  }

  /*
   * used by gpsies style
   */
  public String getSymbolString() {
    switch (cmd) {
      case U_TURN_LEFT:
        return "TU";
      case U_TURN_180:
        return "TU";
      case TURN_SHARPLY_LEFT:
        return "TSHL";
      case TURN_LEFT:
        return "Left";
      case TURN_SLIGHTLY_LEFT:
        return "TSLL";
      case KEEP_LEFT:
        return "TSLL"; // ?
      case CONTINUE:
        return "Straight";
      case KEEP_RIGHT:
        return "TSLR"; // ?
      case TURN_SLIGHTLY_RIGHT:
        return "TSLR";
      case TURN_RIGHT:
        return "Right";
      case TURN_SHARPLY_RIGHT:
        return "TSHR";
      case U_TURN_RIGHT:
        return "TU";
      case ROUNDABOUT_RIGHT:
        return "RNDB" + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return "RNLB" + (-roundaboutExit);
      case BEELINE:
        return "BL";
      case OFF_ROUTE:
        return "OFFR";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by new locus trkpt style
   */
  public String getLocusSymbolString() {
    switch (cmd) {
      case U_TURN_LEFT:
        return "u-turn_left";
      case U_TURN_180:
        return "u-turn";
      case TURN_SHARPLY_LEFT:
        return "left_sharp";
      case TURN_LEFT:
        return "left";
      case TURN_SLIGHTLY_LEFT:
        return "left_slight";
      case KEEP_LEFT:
        return "stay_left"; // ?
      case CONTINUE:
        return "straight";
      case KEEP_RIGHT:
        return "stay_right"; // ?
      case TURN_SLIGHTLY_RIGHT:
        return "right_slight";
      case TURN_RIGHT:
        return "right";
      case TURN_SHARPLY_RIGHT:
        return "right_sharp";
      case U_TURN_RIGHT:
        return "u-turn_right";
      case ROUNDABOUT_RIGHT:
        return "roundabout_e" + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return "roundabout_e" + (-roundaboutExit);
      case BEELINE:
        return "beeline";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by osmand style
   */
  public String getMessageString() {
    switch (cmd) {
      case U_TURN_LEFT:
        return "u-turn"; // should be changed to u-turn-left when osmand uses new voice hint constants
      case U_TURN_180:
        return "u-turn";
      case TURN_SHARPLY_LEFT:
        return "sharp left";
      case TURN_LEFT:
        return "left";
      case TURN_SLIGHTLY_LEFT:
        return "slight left";
      case KEEP_LEFT:
        return "keep left";
      case CONTINUE:
        return "straight";
      case KEEP_RIGHT:
        return "keep right";
      case TURN_SLIGHTLY_RIGHT:
        return "slight right";
      case TURN_RIGHT:
        return "right";
      case TURN_SHARPLY_RIGHT:
        return "sharp right";
      case U_TURN_RIGHT:
        return "u-turn";  // should be changed to u-turn-right when osmand uses new voice hint constants
      case ROUNDABOUT_RIGHT:
        return "Take exit " + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return "Take exit " + (-roundaboutExit);
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by old locus style
   */
  public int getLocusAction() {
    switch (cmd) {
      case U_TURN_LEFT:
        return 13;
      case U_TURN_180:
        return 12;
      case TURN_SHARPLY_LEFT:
        return 5;
      case TURN_LEFT:
        return 4;
      case TURN_SLIGHTLY_LEFT:
        return 3;
      case KEEP_LEFT:
        return 9; // ?
      case CONTINUE:
        return 1;
      case KEEP_RIGHT:
        return 10; // ?
      case TURN_SLIGHTLY_RIGHT:
        return 6;
      case TURN_RIGHT:
        return 7;
      case TURN_SHARPLY_RIGHT:
        return 8;
      case U_TURN_RIGHT:
        return 14;
      case ROUNDABOUT_RIGHT:
        return 26 + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return 26 - roundaboutExit;
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by orux style
   */
  public int getOruxAction() {
    switch (cmd) {
      case U_TURN_LEFT:
        return 1003;
      case U_TURN_180:
        return 1003;
      case TURN_SHARPLY_LEFT:
        return 1019;
      case TURN_LEFT:
        return 1000;
      case TURN_SLIGHTLY_LEFT:
        return 1017;
      case KEEP_LEFT:
        return 1015; // ?
      case CONTINUE:
        return 1002;
      case KEEP_RIGHT:
        return 1014; // ?
      case TURN_SLIGHTLY_RIGHT:
        return 1016;
      case TURN_RIGHT:
        return 1001;
      case TURN_SHARPLY_RIGHT:
        return 1018;
      case U_TURN_RIGHT:
        return 1003;
      case ROUNDABOUT_RIGHT:
        return 1008 + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return 1008 + roundaboutExit;
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by cruiser, equivalent to getCommandString() - osmand style - when osmand changes the voice hint  constants
   */
  public String getCruiserCommandString() {
    switch (cmd) {
      case U_TURN_LEFT:
        return "TLU";
      case U_TURN_180:
        return "TU";
      case TURN_SHARPLY_LEFT:
        return "TSHL";
      case TURN_LEFT:
        return "TL";
      case TURN_SLIGHTLY_LEFT:
        return "TSLL";
      case KEEP_LEFT:
        return "KL";
      case CONTINUE:
        return "C";
      case KEEP_RIGHT:
        return "KR";
      case TURN_SLIGHTLY_RIGHT:
        return "TSLR";
      case TURN_RIGHT:
        return "TR";
      case TURN_SHARPLY_RIGHT:
        return "TSHR";
      case U_TURN_RIGHT:
        return "TRU";
      case ROUNDABOUT_RIGHT:
        return "RNDB" + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return "RNLB" + (-roundaboutExit);
      case BEELINE:
        return "BL";
      case OFF_ROUTE:
        return "OFFR";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  /*
   * used by cruiser, equivalent to getMessageString() - osmand style - when osmand changes the voice hint  constants
   */
  public String getCruiserMessageString() {
    switch (cmd) {
      case U_TURN_LEFT:
        return "u-turn left";
      case U_TURN_180:
        return "u-turn";
      case TURN_SHARPLY_LEFT:
        return "sharp left";
      case TURN_LEFT:
        return "left";
      case TURN_SLIGHTLY_LEFT:
        return "slight left";
      case KEEP_LEFT:
        return "keep left";
      case CONTINUE:
        return "straight";
      case KEEP_RIGHT:
        return "keep right";
      case TURN_SLIGHTLY_RIGHT:
        return "slight right";
      case TURN_RIGHT:
        return "right";
      case TURN_SHARPLY_RIGHT:
        return "sharp right";
      case U_TURN_RIGHT:
        return "u-turn right";
      case ROUNDABOUT_RIGHT:
        return "take exit " + roundaboutExit;
      case ROUNDABOUT_LEFT:
        return "take exit " + (-roundaboutExit);
      case BEELINE:
        return "beeline";
      case OFF_ROUTE:
        return "offroad";
      default:
        throw new IllegalArgumentException("unknown command: " + cmd);
    }
  }

  public void calcCommand() {
    float lowerBadWayAngle = -181;
    float higherBadWayAngle = 181;
    if (badWays != null) {
      for (MessageData badWay : badWays) {
        if (badWay.isBadOneway()) {
          continue;
        }
        if (lowerBadWayAngle < badWay.turnangle && badWay.turnangle < goodWay.turnangle) {
          lowerBadWayAngle = badWay.turnangle;
        }
        if (higherBadWayAngle > badWay.turnangle && badWay.turnangle > goodWay.turnangle) {
          higherBadWayAngle = badWay.turnangle;
        }
      }
    }

    float cmdAngle = angle;

    // fall back to local angle if otherwise inconsistent
    //if ( lowerBadWayAngle > angle || higherBadWayAngle < angle )
    //{
    //cmdAngle = goodWay.turnangle;
    //}
    if (angle == Float.MAX_VALUE) {
      cmdAngle = goodWay.turnangle;
    }
    if (cmd == Command.BEELINE) return;

    if (roundaboutExit > 0) {
      cmd = Command.ROUNDABOUT_RIGHT;
    } else if (roundaboutExit < 0) {
      cmd = Command.ROUNDABOUT_LEFT;
    } else if (is180DegAngle(cmdAngle) && cmdAngle <= -179.f && higherBadWayAngle == 181.f && lowerBadWayAngle == -181.f) {
      cmd = Command.U_TURN_180;
    } else if (cmdAngle < -159.f) {
      cmd = Command.U_TURN_LEFT;
    } else if (cmdAngle < -135.f) {
      cmd = Command.TURN_SHARPLY_LEFT;
    } else if (cmdAngle < -45.f) {
      // a TURN_LEFT can be pushed in either direction by a close-by alternative
      if (cmdAngle < -95.f && higherBadWayAngle < -30.f && lowerBadWayAngle < -180.f) {
        cmd = Command.TURN_SHARPLY_LEFT;
      } else if (cmdAngle > -85.f && lowerBadWayAngle > -180.f && higherBadWayAngle > -10.f) {
        cmd = Command.TURN_SLIGHTLY_LEFT;
      } else {
        if (cmdAngle < -110.f) {
          cmd = Command.TURN_SHARPLY_LEFT;
        } else if (cmdAngle > -60.f) {
          cmd = Command.TURN_SLIGHTLY_LEFT;
        } else {
          cmd = Command.TURN_LEFT;
        }
      }
    } else if (cmdAngle < -21.f) {
      if (cmd != Command.KEEP_RIGHT) { // don't overwrite KEEP_RIGHT with TURN_SLIGHTLY_LEFT
        cmd = Command.TURN_SLIGHTLY_LEFT;
      }
    } else if (cmdAngle < -5.f) {
      if (lowerBadWayAngle < -100.f && higherBadWayAngle < 45.f) {
        cmd = Command.TURN_SLIGHTLY_LEFT;
      } else if (lowerBadWayAngle >= -100.f && higherBadWayAngle < 45.f) {
        cmd = Command.KEEP_LEFT;
      } else {
        cmd = Command.CONTINUE;
      }
    } else if (cmdAngle < 5.f) {
      if (lowerBadWayAngle > -30.f) {
        cmd = Command.KEEP_RIGHT;
      } else if (higherBadWayAngle < 30.f) {
        cmd = Command.KEEP_LEFT;
      } else {
        cmd = Command.CONTINUE;
      }
    } else if (cmdAngle < 21.f) {
      // a TURN_RIGHT can be pushed in either direction by a close-by alternative
      if (lowerBadWayAngle > -45.f && higherBadWayAngle > 100.f) {
        cmd = Command.TURN_SLIGHTLY_RIGHT;
      } else if (lowerBadWayAngle > -45.f && higherBadWayAngle <= 100.f) {
        cmd = Command.KEEP_RIGHT;
      } else {
        cmd = Command.CONTINUE;
      }
    } else if (cmdAngle < 45.f) {
      cmd = Command.TURN_SLIGHTLY_RIGHT;
    } else if (cmdAngle < 135.f) {
      if (cmdAngle < 85.f && higherBadWayAngle < 180.f && lowerBadWayAngle < 10.f) {
        cmd = Command.TURN_SLIGHTLY_RIGHT;
      } else if (cmdAngle > 95.f && lowerBadWayAngle > 30.f && higherBadWayAngle > 180.f) {
        cmd = Command.TURN_SHARPLY_RIGHT;
      } else {
        if (cmdAngle > 110.) {
          cmd = Command.TURN_SHARPLY_RIGHT;
        } else if (cmdAngle < 60.) {
          cmd = Command.TURN_SLIGHTLY_RIGHT;
        } else {
          cmd = Command.TURN_RIGHT;
        }
      }
    } else if (cmdAngle < 159.f) {
      cmd = Command.TURN_SHARPLY_RIGHT;
    } else if (is180DegAngle(cmdAngle) && cmdAngle >= 179.f && higherBadWayAngle == 181.f && lowerBadWayAngle == -181.f) {
      cmd = Command.U_TURN_180;
    } else {
      cmd = Command.U_TURN_RIGHT;
    }
  }

  static boolean is180DegAngle(float angle) {
    return (Math.abs(angle) <= 180.f && Math.abs(angle) >= 179.f);
  }

  public String formatGeometry() {
    float oldPrio = oldWay == null ? 0.f : oldWay.priorityclassifier;
    StringBuilder sb = new StringBuilder(30);
    sb.append(' ').append((int) oldPrio);
    appendTurnGeometry(sb, goodWay);
    if (badWays != null) {
      for (MessageData badWay : badWays) {
        sb.append(" ");
        appendTurnGeometry(sb, badWay);
      }
    }
    return sb.toString();
  }

  private void appendTurnGeometry(StringBuilder sb, MessageData msg) {
    sb.append("(").append((int) (msg.turnangle + 0.5)).append(")").append((int) (msg.priorityclassifier));
  }

}
