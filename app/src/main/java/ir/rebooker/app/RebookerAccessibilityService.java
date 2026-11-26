package ir.rebooker.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RebookerAccessibilityService extends AccessibilityService {
    private static RebookerAccessibilityService instance;
    private static final String CHANNEL_ID = "rebooker_capture";
    private static final int NOTIFICATION_ID = 4217;
    private static final int MAX_EVENTS = 1500;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray captured = new JSONArray();
    private boolean recording = false;
    private boolean replaying = false;
    private JSONArray replayEvents;
    private int replayIndex = 0;
    private String activeReplayPackage = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 80;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!recording || event == null || event.getPackageName() == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return;
        String pkg = event.getPackageName().toString();
        if (pkg.equals(getPackageName()) || isSystemOrKeyboard(pkg)) return;
        AccessibilityNodeInfo node = event.getSource();
        if (node == null || isSensitive(node)) return;
        try {
            JSONObject item = new JSONObject();
            item.put("type", type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ? "text" : "click");
            item.put("pkg", pkg);
            item.put("viewId", safe(node.getViewIdResourceName()));
            item.put("class", safe(node.getClassName()));
            item.put("desc", safe(node.getContentDescription()));
            item.put("hint", hintOf(node));
            item.put("label", node.isEditable() ? "" : safe(node.getText()));
            item.put("editable", node.isEditable());
            item.put("time", System.currentTimeMillis());
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            JSONObject box = new JSONObject();
            box.put("l", bounds.left); box.put("t", bounds.top);
            box.put("r", bounds.right); box.put("b", bounds.bottom);
            item.put("bounds", box);
            if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                CharSequence value = node.getText();
                if (value == null && event.getText() != null && !event.getText().isEmpty()) value = event.getText().get(0);
                item.put("value", value == null ? "" : value.toString());
            }
            if (captured.length() < MAX_EVENTS) captured.put(item);
        } catch (JSONException ignored) { }
    }

    private String safe(CharSequence value) { return value == null ? "" : value.toString(); }

    private String hintOf(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT >= 26) return safe(node.getHintText());
        return "";
    }

    private boolean isSensitive(AccessibilityNodeInfo node) {
        if (node.isPassword()) return true;
        String marker = (safe(node.getContentDescription()) + " " + hintOf(node) + " " + safe(node.getText())).toLowerCase(Locale.ROOT);
        String[] blocked = {"password", "passcode", "one-time", "otp", "cvv", "cvc", "pin code", "رمز عبور", "گذرواژه", "رمز پویا", "کد تایید", "کد تأیید", "رمز یکبار", "شماره کارت", "cvv2", "شناسایی بانکی"};
        for (String word : blocked) if (marker.contains(word)) return true;
        return false;
    }

    private boolean isSystemOrKeyboard(String pkg) {
        return pkg.startsWith("com.android.inputmethod") ||
               pkg.startsWith("com.google.android.inputmethod") ||
               pkg.startsWith("com.samsung.android.honeyboard") ||
               pkg.equals("com.android.systemui") ||
               pkg.equals("android");
    }

    public static boolean isRecording() { return instance != null && instance.recording; }

    public static boolean startCapture() {
        if (instance == null || instance.replaying) return false;
        instance.captured.put(new JSONObject());
        instance.captured.remove(instance.captured.length() - 1);
        while (instance.captured.length() > 0) instance.captured.remove(0);
        instance.recording = true;
        instance.showRecordingNotification();
        return true;
    }

    public static String stopCapture() {
        if (instance == null) return "[]";
        instance.recording = false;
        instance.cancelRecordingNotification();
        return instance.captured.toString();
    }

    public static boolean startReplay(String rawEvents) {
        if (instance == null || instance.recording || instance.replaying) return false;
        try {
            JSONArray events = new JSONArray(rawEvents);
            if (events.length() == 0) return false;
            instance.replayEvents = events;
            instance.replayIndex = 0;
            instance.replaying = true;
            instance.activeReplayPackage = "";
            instance.handler.postDelayed(instance::replayNext, 900);
            return true;
        } catch (JSONException e) { return false; }
    }

    public static void stopReplay() {
        if (instance != null) {
            instance.replaying = false;
            instance.handler.removeCallbacksAndMessages(null);
        }
    }

    private void replayNext() {
        if (!replaying || replayEvents == null || replayIndex >= replayEvents.length()) {
            replaying = false;
            return;
        }
        JSONObject item = replayEvents.optJSONObject(replayIndex++);
        if (item == null) {
            handler.postDelayed(this::replayNext, 350);
            return;
        }
        String pkg = item.optString("pkg", "");
        if (!pkg.isEmpty() && !pkg.equals(activeReplayPackage)) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(launch); activeReplayPackage = pkg; }
                catch (Exception ignored) { }
                handler.postDelayed(() -> { performReplay(item); handler.postDelayed(this::replayNext, 700); }, 1400);
                return;
            }
        }
        performReplay(item);
        handler.postDelayed(this::replayNext, 500);
    }

    private void performReplay(JSONObject item) {
        if (!replaying) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo node = findNode(root, item, 0);
        if (node == null) return;
        String type = item.optString("type", "");
        if ("text".equals(type)) {
            if (node.isEditable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, item.optString("value", ""));
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
        } else if ("click".equals(type)) {
            String label = (item.optString("label", "") + " " + item.optString("desc", "")).toLowerCase(Locale.ROOT);
            if (isFinalAction(label)) return;
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable() && target.getParent() != null) target = target.getParent();
            if (target != null && target.isClickable()) target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
    }

    private boolean isFinalAction(String label) {
        String[] blocked = {"ثبت نهایی", "پرداخت", "خرید", "ارسال", "حذف", "تأیید نهایی", "تایید نهایی", "submit", "pay", "purchase", "delete", "place order"};
        for (String word : blocked) if (label.contains(word)) return true;
        return false;
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, JSONObject item, int depth) {
        if (root == null || depth > 18) return null;
        String wantedId = item.optString("viewId", "");
        String wantedClass = item.optString("class", "");
        String wantedDesc = item.optString("desc", "");
        String wantedHint = item.optString("hint", "");
        String wantedLabel = item.optString("label", "");
        String id = safe(root.getViewIdResourceName());
        String cls = safe(root.getClassName());
        String desc = safe(root.getContentDescription());
        String hint = hintOf(root);
        boolean match = !wantedId.isEmpty() && wantedId.equals(id);
        if (!match && wantedId.isEmpty()) {
            boolean classMatch = wantedClass.isEmpty() || wantedClass.equals(cls);
            boolean semanticMatch = (!wantedDesc.isEmpty() && wantedDesc.equals(desc)) ||
                                    (!wantedHint.isEmpty() && wantedHint.equals(hint)) ||
                                    (!wantedLabel.isEmpty() && wantedLabel.equals(safe(root.getText())));
            if (classMatch && semanticMatch) match = true;
            if (classMatch && !match && wantedDesc.isEmpty() && wantedHint.isEmpty() && wantedLabel.isEmpty()) {
                Rect current = new Rect(); root.getBoundsInScreen(current);
                JSONObject saved = item.optJSONObject("bounds");
                if (saved != null) {
                    int cx = (current.left + current.right) / 2;
                    int cy = (current.top + current.bottom) / 2;
                    int sx = (saved.optInt("l") + saved.optInt("r")) / 2;
                    int sy = (saved.optInt("t") + saved.optInt("b")) / 2;
                    match = Math.abs(cx - sx) < 70 && Math.abs(cy - sy) < 70;
                }
            }
        }
        if (match) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(root.getChild(i), item, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private void showRecordingNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "ضبط Rebooker", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("نمایش وضعیت ضبط عملیات کاربر");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("ضبط Rebooker فعال است")
                .setContentText("برای پایان ضبط، به Rebooker بازگردید.")
                .setContentIntent(pending).setOngoing(true).build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void cancelRecordingNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.cancel(NOTIFICATION_ID);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        recording = false;
        replaying = false;
        cancelRecordingNotification();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}