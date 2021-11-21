package btools.routingapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;


import static android.content.Context.NOTIFICATION_SERVICE;

public class NotificationHelper {

  private static final boolean DEBUG = false;

  public static String BRouterNotificationChannel1 = "brouter_channel_01";

  private Context mContext;
  private int NOTIFICATION_ID = 111;
  private Notification mNotification;
  private NotificationManager mNotificationManager;
  private PendingIntent mContentIntent;
  private CharSequence mContentTitle;

  public NotificationHelper(Context context) {
    if (DEBUG) Log.d("NH", "init ");
    mContext = context;
    createNotificationChannels();
  }

  public void startNotification(Service service) {
    if (DEBUG) Log.d("NH", "startNotification ");

    mNotification = createNotification("BRouter Download", "Download some files");

    if (service != null) service.startForeground(NOTIFICATION_ID, mNotification);

    mNotificationManager.notify(NOTIFICATION_ID, mNotification);

  }

  public void progressUpdate(String text) {
    mNotification = createNotification("BRouter Download", text);
    mNotification.flags = Notification.FLAG_NO_CLEAR |
      Notification.FLAG_ONGOING_EVENT;

    mNotificationManager.notify(NOTIFICATION_ID, mNotification);
  }


  public Notification createNotification(String title, String desc) {

    Intent resultIntent = new Intent(mContext, BInstallerActivity.class);

    Intent notificationIntent = new Intent();
    mContentIntent = PendingIntent.getActivity(mContext, 0, resultIntent, PendingIntent.FLAG_IMMUTABLE);

    mNotificationManager = (NotificationManager) mContext.getSystemService(NOTIFICATION_SERVICE);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {


      final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, BRouterNotificationChannel1);
      builder.setSmallIcon(android.R.drawable.stat_sys_download)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentTitle(title)
        .setContentText(desc)
        .setTicker(desc)
        .setOngoing(true)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(mContentIntent);

      return builder.build();

    } else {
      final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext);
      builder.setSmallIcon(android.R.drawable.stat_sys_download)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentTitle(title)
        .setContentText(desc)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(mContentIntent);

      return builder.build();
    }

  }

  /**
   * create notification channels
   */
  public void createNotificationChannels() {
    if (DEBUG) Log.d("NH", "createNotificationChannels ");

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

      NotificationManager sNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
      // Sound channel
      CharSequence name = "BRouter Download";
      // The user-visible description of the channel.
      String description = "BRouter Download Channel"; //getString(R.string.channel_description);

      NotificationChannel channel = new NotificationChannel(BRouterNotificationChannel1, name, NotificationManager.IMPORTANCE_LOW);
      channel.setDescription(description);
      AudioAttributes att = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_UNKNOWN)
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .build();
      channel.setSound(null, null);
      channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

      sNotificationManager.createNotificationChannel(channel);

    }
  }

  public void stopNotification() {
    if (DEBUG) Log.d("NH", "stopNotification ");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      mNotificationManager.deleteNotificationChannel(BRouterNotificationChannel1);
    }
    mNotificationManager.cancel(NOTIFICATION_ID);
  }
}
