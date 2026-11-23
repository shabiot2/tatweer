package com.abudana.tatweer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Keeps one daily alarm at the time chosen in the app's settings. */
final class Reminder {
    static final String PREFS = "tatweer";
    static final String KEY_AT = "at";
    static final String KEY_LOGGED = "logged";
    static final String DEFAULT_AT = "21:30";
    static final long WINDOW_MS = 10 * 60 * 1000L;

    private Reminder() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String at(Context c) {
        String v = prefs(c).getString(KEY_AT, DEFAULT_AT);
        return v != null && v.matches("\\d\\d:\\d\\d") ? v : DEFAULT_AT;
    }

    static void set(Context c, String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d\\d:\\d\\d")) return;
        if (!hhmm.equals(prefs(c).getString(KEY_AT, null))) {
            prefs(c).edit().putString(KEY_AT, hhmm).apply();
        }
        schedule(c);
    }

    static void setLogged(Context c, String date) {
        prefs(c).edit().putString(KEY_LOGGED, date == null ? "" : date).apply();
    }

    static boolean loggedToday(Context c) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        return today.equals(prefs(c).getString(KEY_LOGGED, ""));
    }

    static void schedule(Context c) {
        String[] parts = at(c).split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        Calendar when = Calendar.getInstance();
        when.set(Calendar.HOUR_OF_DAY, hour);
        when.set(Calendar.MINUTE, minute);
        when.set(Calendar.SECOND, 0);
        when.set(Calendar.MILLISECOND, 0);
        if (when.getTimeInMillis() <= System.currentTimeMillis() + 30_000L) {
            when.add(Calendar.DAY_OF_MONTH, 1);
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(c, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        am.setWindow(AlarmManager.RTC_WAKEUP, when.getTimeInMillis(), WINDOW_MS, pi);
    }
}
