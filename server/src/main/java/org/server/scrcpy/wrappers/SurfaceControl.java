package org.server.scrcpy.wrappers;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import org.server.scrcpy.Ln;

import java.lang.reflect.Method;


@SuppressLint("PrivateApi")
public final class SurfaceControl {

    private static final Class<?> CLASS;

    static {
        try {
            CLASS = Class.forName("android.view.SurfaceControl");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private SurfaceControl() {
        // only static methods
    }

    public static void openTransaction() {
        try {
            CLASS.getMethod("openTransaction").invoke(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void closeTransaction() {
        try {
            CLASS.getMethod("closeTransaction").invoke(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplayProjection(IBinder displayToken, int orientation, Rect layerStackRect, Rect displayRect) {
        try {
            CLASS.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(null, displayToken, orientation, layerStackRect, displayRect);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        try {
            CLASS.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, displayToken, layerStack);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        try {
            CLASS.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, displayToken, surface);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static IBinder createDisplay(String name, boolean secure) {
        try {
            return (IBinder) CLASS.getMethod("createDisplay", String.class, boolean.class).invoke(null, name, secure);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void destroyDisplay(IBinder displayToken) {
        try {
            CLASS.getMethod("destroyDisplay", IBinder.class).invoke(null, displayToken);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ===== 画面の電源（パネルだけ消して、ミラーリングは続ける） =====
    // 本家 scrcpy の --turn-screen-off と同じ仕組み

    public static final int POWER_MODE_OFF = 0;
    public static final int POWER_MODE_NORMAL = 2;

    private static Method getBuiltInDisplayMethod() throws NoSuchMethodException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return CLASS.getMethod("getBuiltInDisplay", int.class);
        }
        return CLASS.getMethod("getInternalDisplayToken");
    }

    public static boolean hasGetBuiltInDisplayMethod() {
        try {
            getBuiltInDisplayMethod();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static IBinder getBuiltInDisplay() {
        try {
            Method method = getBuiltInDisplayMethod();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return (IBinder) method.invoke(null, 0);
            }
            return (IBinder) method.invoke(null);
        } catch (Exception e) {
            Ln.e("Could not get built-in display", e);
            return null;
        }
    }

    public static boolean hasGetPhysicalDisplayIdsMethod() {
        try {
            CLASS.getMethod("getPhysicalDisplayIds");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public static long[] getPhysicalDisplayIds() {
        try {
            return (long[]) CLASS.getMethod("getPhysicalDisplayIds").invoke(null);
        } catch (Exception e) {
            Ln.e("Could not get physical display ids", e);
            return null;
        }
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        try {
            return (IBinder) CLASS.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physicalDisplayId);
        } catch (Exception e) {
            Ln.e("Could not get physical display token", e);
            return null;
        }
    }

    public static boolean setDisplayPowerMode(IBinder displayToken, int mode) {
        if (displayToken == null) {
            return false;
        }
        try {
            CLASS.getMethod("setDisplayPowerMode", IBinder.class, int.class).invoke(null, displayToken, mode);
            return true;
        } catch (Exception e) {
            Ln.e("Could not set display power mode", e);
            return false;
        }
    }
}
