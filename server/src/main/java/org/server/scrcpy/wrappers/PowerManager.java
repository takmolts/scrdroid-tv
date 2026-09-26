package org.server.scrcpy.wrappers;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.IInterface;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
public final class PowerManager {
    private final IInterface manager;
    private final Method isScreenOnMethod;

    public PowerManager(IInterface manager) {
        this.manager = manager;
        try {
            @SuppressLint("ObsoleteSdkInt") // we may lower minSdkVersion in the future
                    String methodName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH ? "isInteractive" : "isScreenOn";
            isScreenOnMethod = manager.getClass().getMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    public boolean isScreenOn() {
        try {
            return (Boolean) isScreenOnMethod.invoke(manager);
        } catch (InvocationTargetException | IllegalAccessException e) {
            // throw new AssertionError(e);
            return false;
        }
    }

    /**
     * ユーザー操作があったことにしてスリープまでのタイマーをリセットする。
     * TV で見ている間はスマホに触らないため、これをしないと画面タイムアウトで
     * スマホがスリープし、映像が止まる。
     */
    public boolean userActivity() {
        long now = android.os.SystemClock.uptimeMillis();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // userActivity(int displayId, long time, int event, int flags)
                manager.getClass().getMethod("userActivity", int.class, long.class, int.class, int.class)
                        .invoke(manager, 0, now, 0, 0);
            } else {
                // userActivity(long time, int event, int flags)
                manager.getClass().getMethod("userActivity", long.class, int.class, int.class)
                        .invoke(manager, now, 0, 0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
