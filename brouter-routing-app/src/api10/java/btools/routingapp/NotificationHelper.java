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

    public NotificationHelper(Context context)
    {
        if (DEBUG) Log.d("NH", "init " );
        mContext = context;
        createNotificationChannels();
    }

    public void startNotification(Service service) {
        if (DEBUG) Log.d("NH", "startNotification " );

        mNotification = createNotification("BRouter Download", "Download some files");
        if (mNotification != null) {
            NotificationManager mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(NOTIFICATION_ID, mNotification);
        }
    }

    public void progressUpdate(String text) {
        mNotification = createNotification("BRouter Download", text);

        if (mNotification != null) {
            mNotification.flags = Notification.FLAG_NO_CLEAR |
                    Notification.FLAG_ONGOING_EVENT;
            NotificationManager mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(NOTIFICATION_ID, mNotification);
        }
    }


    public Notification createNotification(String title, String desc) {

        Intent resultIntent = new Intent(mContext, BInstallerActivity.class);

        Intent notificationIntent = new Intent();
        mContentIntent = PendingIntent.getActivity(mContext, 0, resultIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder ;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            builder = new Notification.Builder(mContext);
            builder.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(desc)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(mContentIntent);

            if(Build.VERSION.SDK_INT>=11 && Build.VERSION.SDK_INT<=15)
                return  builder.getNotification();
            else if (Build.VERSION.SDK_INT > 15)
                return builder.build();
        }

        return null;

    }



    /**
     * create notification channels
     */
    public  void createNotificationChannels() {
        if (DEBUG) Log.d("NH", "createNotificationChannels " );

     }

    public void stopNotification()    {
        if (DEBUG) Log.d("NH", "stopNotification " );
        NotificationManager mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager.deleteNotificationChannel(BRouterNotificationChannel1);
        }
        mNotificationManager.cancel(NOTIFICATION_ID);
    }
}