package com.tao7.nasremote;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

public class AccessibilityHelper {

    public static boolean isServiceRunning(Context context) {
        if (KeyRelayAccessibilityService.isServiceConnected) {
            return true;
        }

        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null) {
            List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (services != null) {
                for (AccessibilityServiceInfo info : services) {
                    if (info.getId() != null && info.getId().contains(context.getPackageName())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean isServiceInSettings(Context context) {
        try {
            String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) return false;
            String myShortService = new ComponentName(context, KeyRelayAccessibilityService.class).flattenToShortString();
            String myLongService = new ComponentName(context, KeyRelayAccessibilityService.class).flattenToString();
            return current.contains(myShortService) || current.contains(myLongService);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean enableServiceViaSettings(Context context) {
        try {
            String myShortService = new ComponentName(context, KeyRelayAccessibilityService.class).flattenToShortString();
            String myLongService = new ComponentName(context, KeyRelayAccessibilityService.class).flattenToString();
            String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || current.isEmpty()) {
                current = myShortService;
            } else if (!current.contains(myShortService) && !current.contains(myLongService)) {
                current = current + ":" + myShortService;
            }
            Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current);
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean disableServiceViaSettings(Context context) {
        try {
            String myService = new ComponentName(context, KeyRelayAccessibilityService.class).flattenToString();
            String myShortService = new ComponentName(context, KeyRelayAccessibilityService.class).flattenToShortString();
            String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current != null) {
                current = current.replace(myService, "").replace(myShortService, "").replace("::", ":");
                while (current.startsWith(":")) current = current.substring(1);
                while (current.endsWith(":")) current = current.substring(0, current.length() - 1);
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void openAccessibilitySettings(Context context) {
        // 1. Android TV / Google TV 原生无障碍设置
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        } catch (Exception ignored) {}

        // 2. 小米电视系统设置入口
        try {
            Intent miIntent = new Intent();
            miIntent.setComponent(new ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
            miIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(miIntent);
            return;
        } catch (Exception ignored) {}

        // 3. Android TV 原生通用设置
        try {
            Intent tvIntent = new Intent();
            tvIntent.setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            tvIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(tvIntent);
            return;
        } catch (Exception ignored) {}

        // 4. 标准设置兜底
        try {
            Intent fallback = new Intent(Settings.ACTION_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
        } catch (Exception ignored) {}
    }
}
