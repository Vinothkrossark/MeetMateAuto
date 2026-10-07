package com.krossark.meetmate;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

public class MeetService extends Service {

    static final String ACTION_START_MEETING = "com.krossark.meetmate.START_MEETING";
    static final String ACTION_LEAVE_MEETING = "com.krossark.meetmate.LEAVE_MEETING";

    private static final int NOTIF_ID = 99;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        MeetAlarmReceiver.createNotificationChannel(this);
        startForeground(NOTIF_ID, buildForegroundNotification("MeetMate running..."));

        String action = intent.getAction();
        if (ACTION_START_MEETING.equals(action)) {
            handleJoin();
        } else if (ACTION_LEAVE_MEETING.equals(action)) {
            handleLeave();
        }

        return START_NOT_STICKY;
    }

    private void handleJoin() {
        SharedPreferences prefs = getSharedPreferences("meetmate_prefs", MODE_PRIVATE);
        String link = prefs.getString(MainActivity.KEY_MEET_LINK, MainActivity.DEFAULT_MEET_LINK);

        updateNotification("Joining Google Meet...");

        // Small delay to let phone fully wake
        handler.postDelayed(() -> {
            try {
                Intent meetIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                meetIntent.setPackage("com.google.android.apps.meetings");
                meetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(meetIntent);
                logEvent("🚀 Launched Google Meet app");
            } catch (Exception e) {
                // Fallback: open in browser
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    logEvent("🌐 Opened Meet in browser (fallback)");
                } catch (Exception ex) {
                    logEvent("❌ Failed to open Meet: " + ex.getMessage());
                }
            }

            // Accessibility service will take over from here (join, mute, camera off)
            updateNotification("In meeting — mic & camera OFF");

            // Stop foreground after 5 seconds; accessibility service handles the rest
            handler.postDelayed(() -> {
                stopForeground(true);
                stopSelf();
            }, 5000);

        }, 1500);
    }

    private void handleLeave() {
        updateNotification("Leaving meeting...");
        // Tell accessibility service to press Leave
        Intent broadcast = new Intent(MeetAccessibilityService.ACTION_LEAVE);
        sendBroadcast(broadcast);
        logEvent("🚪 Leave command sent to accessibility service");

        handler.postDelayed(() -> {
            stopForeground(true);
            stopSelf();
        }, 3000);
    }

    private void logEvent(String msg) {
        SharedPreferences prefs = getSharedPreferences("meetmate_prefs", MODE_PRIVATE);
        String existing = prefs.getString(MainActivity.KEY_LOG, "");
        String line = "[" + new java.text.SimpleDateFormat("HH:mm:ss",
            java.util.Locale.getDefault()).format(new java.util.Date()) + "] " + msg;
        String updated = line + (existing.isEmpty() ? "" : "\n" + existing);
        prefs.edit().putString(MainActivity.KEY_LOG, updated).apply();
    }

    private Notification buildForegroundNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, "meetmate_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MeetMate Auto")
            .setContentText(text)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        Notification n = buildForegroundNotification(text);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
