package org.server.scrcpy;

import android.os.Build;
import android.os.IBinder;

import org.server.scrcpy.wrappers.DisplayControl;
import org.server.scrcpy.wrappers.ServiceManager;
import org.server.scrcpy.wrappers.SurfaceControl;

/**
 * スマホ側の画面（パネル）の電源制御と、スリープ防止。
 * 画面を消してもスマホは「起きている」扱いのままなので、ミラーリングは続く。
 * 本家 scrcpy の Device.setDisplayPower() と同じ手順。
 */
public final class ScreenPower {

    /** クライアントから画面OFFを要求されている間 true（回転時の再適用に使う） */
    private static volatile boolean offRequested = false;
    /** 一度でも OFF にしたか（終了時に元へ戻すため） */
    private static volatile boolean everTurnedOff = false;

    private static Thread keepAwakeThread;

    private ScreenPower() {
    }

    public static synchronized boolean setDisplayPower(boolean on) {
        offRequested = !on;
        if (!on) {
            everTurnedOff = true;
        }
        boolean ok = apply(on);
        Ln.i("setDisplayPower(" + on + ") -> " + ok);
        return ok;
    }

    public static boolean isOffRequested() {
        return offRequested;
    }

    /** 回転などで画面が点き直すことがあるため、OFF 要求中なら再度 OFF にする */
    public static void reapplyIfNeeded() {
        if (offRequested) {
            apply(false);
        }
    }

    /** サーバー終了時: 自分で消した画面は点け直しておく */
    public static void restoreOnExit() {
        if (everTurnedOff) {
            offRequested = false;
            apply(true);
        }
    }

    private static boolean apply(boolean on) {
        int mode = on ? SurfaceControl.POWER_MODE_NORMAL : SurfaceControl.POWER_MODE_OFF;
        try {
            boolean multi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
            if (multi && Build.VERSION.SDK_INT >= 34
                    && Build.BRAND.equalsIgnoreCase("honor")
                    && SurfaceControl.hasGetBuiltInDisplayMethod()) {
                multi = false;
            }
            if (multi) {
                boolean useDisplayControl = Build.VERSION.SDK_INT >= 34
                        && !SurfaceControl.hasGetPhysicalDisplayIdsMethod();
                long[] ids = useDisplayControl
                        ? DisplayControl.getPhysicalDisplayIds()
                        : SurfaceControl.getPhysicalDisplayIds();
                if (ids == null) {
                    return false;
                }
                boolean allOk = true;
                for (long id : ids) {
                    IBinder token = useDisplayControl
                            ? DisplayControl.getPhysicalDisplayToken(id)
                            : SurfaceControl.getPhysicalDisplayToken(id);
                    allOk &= SurfaceControl.setDisplayPowerMode(token, mode);
                }
                return allOk;
            }
            return SurfaceControl.setDisplayPowerMode(SurfaceControl.getBuiltInDisplay(), mode);
        } catch (Throwable e) {
            Ln.e("display power control failed", e);
            return false;
        }
    }

    /**
     * ミラーリング中はスマホをスリープさせない（一定間隔でユーザー操作扱いにする）。
     * 画面タイムアウトの設定値は書き換えないので、終了後は元の動作に戻る。
     */
    public static synchronized void startKeepAwake() {
        if (keepAwakeThread != null) {
            return;
        }
        keepAwakeThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ServiceManager.getPowerManager().userActivity();
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    Ln.w("userActivity failed: " + e);
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }, "keep-awake");
        keepAwakeThread.setDaemon(true);
        keepAwakeThread.start();
    }

    public static synchronized void stopKeepAwake() {
        if (keepAwakeThread != null) {
            keepAwakeThread.interrupt();
            keepAwakeThread = null;
        }
    }
}
