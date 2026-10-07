package com.krossark.meetmate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.Build;

import java.util.Calendar;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !"android.intent.action.MY_PACKAGE_REPLACED".equals(action) &&
            !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        SharedPreferences prefs = ctx.getSharedPreferences("meetmate_prefs", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(MainActivity.KEY_ENABLED, false);

        if (!enabled) return;

        // Re-schedule alarms after boot
        int joinHour  = prefs.getInt(MainActivity.KEY_JOIN_HOUR, 12);
        int joinMin   = prefs.getInt(MainActivity.KEY_JOIN_MIN, 0);
        int leaveHour = prefs.getInt(MainActivity.KEY_LEAVE_HOUR, 12);
        int leaveMin  = prefs.getInt(MainActivity.KEY_LEAVE_MIN, 40);

        scheduleAlarm(ctx, MeetAlarmReceiver.ACTION_JOIN,  joinHour,  joinMin,  1001);
        scheduleAlarm(ctx, MeetAlarmReceiver.ACTION_LEAVE, leaveHour, leaveMin, 1002);

        boolean reminder = prefs.getBoolean(MainActivity.KEY_NOTIFY_REMINDER, true);
        if (reminder) {
            int rMin = joinMin - 2;
            int rHour = joinHour;
            if (rMin < 0) { rMin += 60; rHour--; }
            scheduleAlarm(ctx, MeetAlarmReceiver.ACTION_REMINDER, rHour, rMin, 1003);
        }

        MeetAlarmReceiver.createNotificationChannel(ctx);
    }

    private void scheduleAlarm(Context ctx, String action, int hour, int minute, int rc) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(ctx, MeetAlarmReceiver.class);
        intent.setAction(action);

        PendingIntent pi = PendingIntent.getBroadcast(
            ctx, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }
}
