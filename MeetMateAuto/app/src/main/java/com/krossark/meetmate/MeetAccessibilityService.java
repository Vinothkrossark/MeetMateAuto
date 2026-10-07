package com.krossark.meetmate;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.app.NotificationCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MeetAccessibilityService extends AccessibilityService {

    static final String ACTION_LEAVE = "com.krossark.meetmate.ACCESSIBILITY_LEAVE";

    private static final String MEET_PKG = "com.google.android.apps.meetings";

    // Known Meet UI button content descriptions / text (resilient to UI changes)
    private static final String[] JOIN_LABELS = {
        "join", "join now", "join meeting", "join call", "ask to join"
    };
    private static final String[] MIC_OFF_LABELS = {
        "turn off microphone", "mute microphone", "mic off", "mute"
    };
    private static final String[] CAM_OFF_LABELS = {
        "turn off camera", "camera off", "stop video"
    };
    private static final String[] LEAVE_LABELS = {
        "leave call", "leave meeting", "leave", "end call"
    };
    private static final String[] PARTICIPANT_CONTAINER = {
        "participants", "people"
    };

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean micMuted = false;
    private boolean camOff = false;
    private boolean joined = false;
    private Set<String> knownParticipants = new HashSet<>();

    private BroadcastReceiver leaveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_LEAVE.equals(intent.getAction())) {
                handler.postDelayed(() -> pressLeave(), 500);
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter filter = new IntentFilter(ACTION_LEAVE);
        registerReceiver(leaveReceiver, filter);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!MEET_PKG.equals(pkg)) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // Step 1: Join the meeting
            if (!joined) {
                if (tryClickLabel(root, JOIN_LABELS)) {
                    joined = true;
                    logEvent("✅ Clicked Join button");
                    // Step 2: Mute mic and camera after joining (delay for UI to load)
                    handler.postDelayed(() -> {
                        AccessibilityNodeInfo r2 = getRootInActiveWindow();
                        if (r2 != null) {
                            muteAndCameraOff(r2);
                            r2.recycle();
                        }
                    }, 2000);
                }
            }

            // Step 3: Keep checking mic/camera (safe — re-disable if UI resets)
            if (joined) {
                muteAndCameraOff(root);
                // Step 4: Detect new participants
                checkNewParticipants(root);
            }

            root.recycle();
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private boolean tryClickLabel(AccessibilityNodeInfo root, String[] labels) {
        for (String label : labels) {
            // Try by content description
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() || node.isEnabled()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle();
                    return true;
                }
                // Try parent
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null && parent.isClickable()) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    parent.recycle();
                    node.recycle();
                    return true;
                }
                node.recycle();
            }
        }
        return false;
    }

    private void muteAndCameraOff(AccessibilityNodeInfo root) {
        if (!micMuted) {
            if (tryClickLabel(root, MIC_OFF_LABELS)) {
                micMuted = true;
                logEvent("🎙️ Microphone MUTED");
            }
        }
        if (!camOff) {
            if (tryClickLabel(root, CAM_OFF_LABELS)) {
                camOff = true;
                logEvent("📷 Camera OFF");
            }
        }
    }

    private void pressLeave() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (tryClickLabel(root, LEAVE_LABELS)) {
                logEvent("🚪 Left the meeting automatically");
                joined = false;
                micMuted = false;
                camOff = false;
                knownParticipants.clear();
            } else {
                logEvent("⚠️ Leave button not found — retrying in 2s");
                handler.postDelayed(this::pressLeave, 2000);
            }
            root.recycle();
        }
    }

    // ── Participant detection ─────────────────────────────────────────────────

    private void checkNewParticipants(AccessibilityNodeInfo root) {
        SharedPreferences prefs = getSharedPreferences("meetmate_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean(MainActivity.KEY_NOTIFY_PARTICIPANT, true)) return;

        // Scan all text nodes that look like participant names (heuristic)
        for (String container : PARTICIPANT_CONTAINER) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(container);
            for (AccessibilityNodeInfo node : nodes) {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) {
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        AccessibilityNodeInfo child = parent.getChild(i);
                        if (child != null) {
                            CharSequence txt = child.getText();
                            if (txt != null) {
                                String name = txt.toString().trim();
                                if (!name.isEmpty() && !knownParticipants.contains(name)
                                    && !name.equalsIgnoreCase("participants")
                                    && !name.equalsIgnoreCase("people")) {
                                    knownParticipants.add(name);
                                    notifyParticipantJoined(name);
                                }
                            }
                            child.recycle();
                        }
                    }
                    parent.recycle();
                }
                node.recycle();
            }
        }
    }

    private void notifyParticipantJoined(String name) {
        logEvent("👤 " + name + " joined the meeting");

        MeetAlarmReceiver.createNotificationChannel(this);
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "meetmate_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("👤 Participant Joined")
            .setContentText(name + " has joined the meeting")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void logEvent(String msg) {
        SharedPreferences prefs = getSharedPreferences("meetmate_prefs", MODE_PRIVATE);
        String existing = prefs.getString(MainActivity.KEY_LOG, "");
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss",
            java.util.Locale.getDefault()).format(new java.util.Date());
        String line = "[" + timestamp + "] " + msg;
        String updated = line + (existing.isEmpty() ? "" : "\n" + existing);
        prefs.edit().putString(MainActivity.KEY_LOG, updated).apply();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(leaveReceiver); } catch (Exception ignored) {}
    }
}
