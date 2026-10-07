package com.krossark.meetmate;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class MeetAlarmReceiver extends BroadcastReceiver {

    static final String ACTION_JOIN     = "com.krossark.meetmate.ACTION_JOIN";
    static final String ACTION_LEAVE    = "com.krossark.meetmate.ACTION_LEAVE";
    static final String ACTION_REMINDER = "com.krossark.meetmate.ACTION_REMINDER";

    private static final String CHANNEL_ID = "meetmate_channel";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences prefs = ctx.getSharedPreferences("meetmate_prefs", Context.MODE_PRIVATE);

        boolean enabled = prefs.getBoolean(MainActivity.KEY_ENABLED, false);
        if (!enabled) return;

        String action = intent.getAction();
        if (action == null) return;

        createNotificationChannel(ctx);

        switch (action) {
            case ACTION_REMINDER:
                showNotification(ctx, "Meeting in 2 minutes",
                    "Auto-joining Google Meet at 12:00 PM", 10);
                break;

            case ACTION_JOIN:
                // Start foreground service to open Meet + control mic/camera
                Intent svc = new Intent(ctx, MeetService.class);
                svc.setAction(MeetService.ACTION_START_MEETING);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
                showNotification(ctx, "Joining Google Meet",
                    "Opening meet.google.com/otz-qwwh-esi", 11);

                // Re-schedule for tomorrow (daily repeat)
                rescheduleFor(ctx, prefs);
                break;

            case ACTION_LEAVE:
                Intent leaveSvc = new Intent(ctx, MeetService.class);
                leaveSvc.setAction(MeetService.ACTION_LEAVE_MEETING);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(leaveSvc);
                } else {
                    ctx.startService(leaveSvc);
                }
                showNotification(ctx, "Left Google Meet",
                    "Automatically left at 12:40 PM", 12);
                break;
        }
    }

    private void rescheduleFor(Context ctx, SharedPreferences prefs) {
        // We rely on MainActivity.scheduleAlarms() logic — it already sets
        // next-day alarm when today's time has passed. The Boot receiver
        // re-calls this on every restart, so nothing extra needed here.
    }

    private void showNotification(Context ctx, String title, String body, int id) {
        Intent openApp = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, builder.build());
    }

    static void createNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "MeetMate Auto",
                NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Meeting automation notifications");
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
