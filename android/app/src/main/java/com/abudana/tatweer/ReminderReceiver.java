package com.abudana.tatweer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Shows the daily reminder, unless today is already logged, then books tomorrow's. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "daily";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent intent) {
        Reminder.schedule(c);
        if (Reminder.loggedToday(c)) return;

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(c.getString(R.string.channel_desc));
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(c, MainActivity.class)
                .putExtra("tab", "today")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(c.getString(R.string.remind_title))
                .setContentText(c.getString(R.string.remind_text))
                .setColor(0xFFE6C47A)
                .setAutoCancel(true)
                .setContentIntent(tap);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_DEFAULT);

        try {
            nm.notify(1, b.build());
        } catch (SecurityException ignored) {
            // Notifications are switched off for the app.
        }
    }
}
