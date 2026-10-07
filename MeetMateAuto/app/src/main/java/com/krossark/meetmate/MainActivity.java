package com.krossark.meetmate;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "meetmate_prefs";
    static final String KEY_ENABLED = "automation_enabled";
    static final String KEY_MEET_LINK = "meet_link";
    static final String KEY_JOIN_HOUR = "join_hour";
    static final String KEY_JOIN_MIN = "join_min";
    static final String KEY_LEAVE_HOUR = "leave_hour";
    static final String KEY_LEAVE_MIN = "leave_min";
    static final String KEY_NOTIFY_PARTICIPANT = "notify_participant";
    static final String KEY_NOTIFY_REMINDER = "notify_reminder";
    static final String KEY_LOG = "activity_log";

    static final String DEFAULT_MEET_LINK = "https://meet.google.com/otz-qwwh-esi";

    private SharedPreferences prefs;
    private Handler countdownHandler;
    private Runnable countdownRunnable;

    // Views
    private SwitchMaterial masterToggle;
    private SwitchMaterial participantNotifyToggle;
    private SwitchMaterial reminderToggle;
    private TextView statusText;
    private TextView statusDot;
    private TextView countdownText;
    private TextView countdownLabel;
    private TextView meetLinkText;
    private TextView accessibilityStatus;
    private TextView batteryStatus;
    private TextView alarmStatus;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        countdownHandler = new Handler(Looper.getMainLooper());

        bindViews();
        loadState();
        setListeners();
        startCountdown();
        updatePermissionStatuses();
        appendLog("App started — " + getTimestamp());
    }

    private void bindViews() {
        masterToggle           = findViewById(R.id.masterToggle);
        participantNotifyToggle = findViewById(R.id.participantNotifyToggle);
        reminderToggle         = findViewById(R.id.reminderToggle);
        statusText             = findViewById(R.id.statusText);
        statusDot              = (TextView) findViewById(R.id.statusDot); // View not TextView — handled below
        countdownText          = findViewById(R.id.countdownText);
        countdownLabel         = findViewById(R.id.countdownLabel);
        meetLinkText           = findViewById(R.id.meetLinkText);
        accessibilityStatus    = findViewById(R.id.accessibilityStatus);
        batteryStatus          = findViewById(R.id.batteryStatus);
        alarmStatus            = findViewById(R.id.alarmStatus);
        logText                = findViewById(R.id.logText);
    }

    private void loadState() {
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        String link = prefs.getString(KEY_MEET_LINK, DEFAULT_MEET_LINK);
        boolean notifyParticipant = prefs.getBoolean(KEY_NOTIFY_PARTICIPANT, true);
        boolean notifyReminder = prefs.getBoolean(KEY_NOTIFY_REMINDER, true);

        masterToggle.setChecked(enabled);
        participantNotifyToggle.setChecked(notifyParticipant);
        reminderToggle.setChecked(notifyReminder);

        String displayLink = link.replace("https://", "").replace("http://", "");
        meetLinkText.setText(displayLink);

        updateStatusUI(enabled);

        String log = prefs.getString(KEY_LOG, "");
        logText.setText(log.isEmpty() ? "No activity yet. Enable automation to begin." : log);
    }

    private void setListeners() {
        // Master toggle
        masterToggle.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean(KEY_ENABLED, isChecked).apply();
            updateStatusUI(isChecked);
            if (isChecked) {
                if (!isAccessibilityEnabled()) {
                    showAccessibilityDialog();
                }
                scheduleAlarms();
                appendLog("✅ Automation ENABLED");
                Toast.makeText(this, "MeetMate Auto enabled — daily 12:00 PM", Toast.LENGTH_SHORT).show();
            } else {
                cancelAlarms();
                appendLog("⛔ Automation DISABLED");
                Toast.makeText(this, "MeetMate Auto disabled", Toast.LENGTH_SHORT).show();
            }
        });

        // Enable button
        findViewById(R.id.enableBtn).setOnClickListener(v -> {
            masterToggle.setChecked(!masterToggle.isChecked());
        });

        // Test button — immediately launches Meet
        findViewById(R.id.testBtn).setOnClickListener(v -> {
            appendLog("🧪 Test triggered manually");
            launchMeet();
        });

        // Edit Meet link
        findViewById(R.id.editLinkBtn).setOnClickListener(v -> showEditLinkDialog());

        // Accessibility row
        findViewById(R.id.accessibilityRow).setOnClickListener(v -> openAccessibilitySettings());

        // Battery row
        findViewById(R.id.batteryRow).setOnClickListener(v -> openBatterySettings());

        // Alarm row
        findViewById(R.id.alarmRow).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(this, "Cannot open alarm settings", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Participant notify toggle
        participantNotifyToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_NOTIFY_PARTICIPANT, checked).apply();
            appendLog(checked ? "🔔 Participant notifications ON" : "🔕 Participant notifications OFF");
        });

        // Reminder toggle
        reminderToggle.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_NOTIFY_REMINDER, checked).apply();
        });

        // Clear log
        findViewById(R.id.clearLogBtn).setOnClickListener(v -> {
            prefs.edit().putString(KEY_LOG, "").apply();
            logText.setText("Log cleared.");
        });
    }

    // ── Schedule Alarms ───────────────────────────────────────────────────────

    void scheduleAlarms() {
        int joinHour  = prefs.getInt(KEY_JOIN_HOUR, 12);
        int joinMin   = prefs.getInt(KEY_JOIN_MIN, 0);
        int leaveHour = prefs.getInt(KEY_LEAVE_HOUR, 12);
        int leaveMin  = prefs.getInt(KEY_LEAVE_MIN, 40);

        scheduleAlarm(MeetAlarmReceiver.ACTION_JOIN,  joinHour,  joinMin,  1001);
        scheduleAlarm(MeetAlarmReceiver.ACTION_LEAVE, leaveHour, leaveMin, 1002);

        // Reminder 2 minutes before
        if (prefs.getBoolean(KEY_NOTIFY_REMINDER, true)) {
            int reminderMin = joinMin - 2;
            int reminderHour = joinHour;
            if (reminderMin < 0) { reminderMin += 60; reminderHour--; }
            scheduleAlarm(MeetAlarmReceiver.ACTION_REMINDER, reminderHour, reminderMin, 1003);
        }

        appendLog("⏰ Alarms set: join " + pad(joinHour) + ":" + pad(joinMin) +
                  " / leave " + pad(leaveHour) + ":" + pad(leaveMin));
    }

    private void scheduleAlarm(String action, int hour, int minute, int requestCode) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(this, MeetAlarmReceiver.class);
        intent.setAction(action);

        PendingIntent pi = PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // If time already passed today, set for tomorrow
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    void cancelAlarms() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        for (int rc : new int[]{1001, 1002, 1003}) {
            Intent intent = new Intent(this, MeetAlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                this, rc, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            am.cancel(pi);
        }
    }

    // ── Launch Meet ───────────────────────────────────────────────────────────

    void launchMeet() {
        String link = prefs.getString(KEY_MEET_LINK, DEFAULT_MEET_LINK);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            i.setPackage("com.google.android.apps.meetings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            appendLog("🚀 Launched Meet: " + link);
        } catch (Exception e) {
            // Fallback to browser
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                appendLog("🌐 Opened Meet in browser (fallback)");
            } catch (Exception ex) {
                appendLog("❌ Failed to open Meet: " + ex.getMessage());
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateStatusUI(boolean enabled) {
        if (enabled) {
            statusText.setText("Automation Active");
            statusText.setTextColor(getColor(R.color.green_success));
            statusDot.setBackground(getDrawable(R.drawable.dot_active));
        } else {
            statusText.setText("Automation Disabled");
            statusText.setTextColor(getColor(R.color.text_secondary));
            statusDot.setBackground(getDrawable(R.drawable.dot_inactive));
        }
    }

    private void startCountdown() {
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
                if (!enabled) {
                    countdownText.setText("--:--:--");
                    countdownLabel.setText("Enable automation to start countdown");
                    countdownHandler.postDelayed(this, 1000);
                    return;
                }

                int joinHour = prefs.getInt(KEY_JOIN_HOUR, 12);
                int joinMin  = prefs.getInt(KEY_JOIN_MIN, 0);

                Calendar now = Calendar.getInstance();
                Calendar target = Calendar.getInstance();
                target.set(Calendar.HOUR_OF_DAY, joinHour);
                target.set(Calendar.MINUTE, joinMin);
                target.set(Calendar.SECOND, 0);
                target.set(Calendar.MILLISECOND, 0);

                if (target.getTimeInMillis() <= now.getTimeInMillis()) {
                    target.add(Calendar.DAY_OF_YEAR, 1);
                }

                long diff = target.getTimeInMillis() - now.getTimeInMillis();
                long hours = diff / 3600000;
                long minutes = (diff % 3600000) / 60000;
                long seconds = (diff % 60000) / 1000;

                countdownText.setText(String.format(Locale.getDefault(),
                        "%02d:%02d:%02d", hours, minutes, seconds));
                countdownLabel.setText("Until next auto-join at " + pad(joinHour) + ":" + pad(joinMin) + " PM");

                countdownHandler.postDelayed(this, 1000);
            }
        };
        countdownHandler.post(countdownRunnable);
    }

    private void updatePermissionStatuses() {
        // Accessibility
        if (isAccessibilityEnabled()) {
            accessibilityStatus.setText("✓ ON");
            accessibilityStatus.setTextColor(getColor(R.color.green_success));
            accessibilityStatus.setBackground(getDrawable(R.drawable.badge_ok));
        } else {
            accessibilityStatus.setText("ENABLE");
            accessibilityStatus.setTextColor(getColor(R.color.amber_warn));
            accessibilityStatus.setBackground(getDrawable(R.drawable.badge_warn));
        }

        // Battery
        batteryStatus.setText("FIX");
        batteryStatus.setTextColor(getColor(R.color.amber_warn));

        // Alarm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && am.canScheduleExactAlarms()) {
                alarmStatus.setText("✓ OK");
                alarmStatus.setTextColor(getColor(R.color.green_success));
                alarmStatus.setBackground(getDrawable(R.drawable.badge_ok));
            } else {
                alarmStatus.setText("GRANT");
                alarmStatus.setTextColor(getColor(R.color.amber_warn));
                alarmStatus.setBackground(getDrawable(R.drawable.badge_warn));
            }
        }
    }

    boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> services =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC);
        for (AccessibilityServiceInfo info : services) {
            if (info.getId().contains(getPackageName())) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open accessibility settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ex) {
                Toast.makeText(this, "Go to Settings > Battery > App Management > MeetMate > No restriction", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showAccessibilityDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage("MeetMate Auto needs Accessibility permission to:\n\n• Tap 'Join' button in Google Meet\n• Turn off Mic & Camera automatically\n• Leave meeting at 12:40 PM\n\nIn the next screen:\n1. Find 'MeetMate Auto'\n2. Toggle it ON\n3. Tap Allow")
            .setPositiveButton("Open Settings", (d, w) -> openAccessibilitySettings())
            .setNegativeButton("Later", null)
            .show();
    }

    private void showEditLinkDialog() {
        String current = prefs.getString(KEY_MEET_LINK, DEFAULT_MEET_LINK);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(current);
        input.setTextColor(getColor(R.color.text_primary));
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.indigo_primary)));
        input.setPadding(16, 16, 16, 16);

        new AlertDialog.Builder(this)
            .setTitle("Edit Meet Link")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String newLink = input.getText().toString().trim();
                if (!newLink.isEmpty()) {
                    prefs.edit().putString(KEY_MEET_LINK, newLink).apply();
                    String displayLink = newLink.replace("https://", "").replace("http://", "");
                    meetLinkText.setText(displayLink);
                    appendLog("🔗 Meet link updated");
                    Toast.makeText(this, "Link saved!", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Log helper ────────────────────────────────────────────────────────────

    void appendLog(String msg) {
        String existing = prefs.getString(KEY_LOG, "");
        String line = "[" + getTimestamp() + "] " + msg;
        String updated = line + (existing.isEmpty() ? "" : "\n" + existing);
        // Keep log to last 30 lines
        String[] lines = updated.split("\n");
        if (lines.length > 30) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                if (i > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            updated = sb.toString();
        }
        prefs.edit().putString(KEY_LOG, updated).apply();
        if (logText != null) logText.setText(updated);
    }

    private String getTimestamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private String pad(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatuses();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countdownHandler != null && countdownRunnable != null) {
            countdownHandler.removeCallbacks(countdownRunnable);
        }
    }
}
