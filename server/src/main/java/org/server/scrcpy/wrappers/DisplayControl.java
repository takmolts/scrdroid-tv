package org.server.scrcpy.wrappers;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.system.Os;

import org.server.scrcpy.Ln;

import java.lang.reflect.Method;

/**
 * Android 14 以降で物理ディスプレイのトークンを取得するためのラッパー。
 * (SurfaceControl から getPhysicalDisplayIds が削除され、system_server 側の
 *  com.android.server.display.DisplayControl に移ったため)
 * 実装は本家 scrcpy の DisplayControl を参考にしている。
 */
@SuppressLint({"PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
public final class DisplayControl {

    private static final Class<?> CLASS;

    static {
        Class<?> displayControlClass = null;
        try {
            Class<?> classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod("createClassLoader", String.class, String.class,
                    String.class, ClassLoader.class, int.class, boolean.class, String.class);

            String systemServerClasspath = Os.getenv("SYSTEMSERVERCLASSPATH");
            ClassLoader classLoader = (ClassLoader) createClassLoaderMethod.invoke(null, systemServerClasspath, null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);

            displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl");

            Method loadMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadMethod.setAccessible(true);
            loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers");
        } catch (Throwable e) {
            Ln.e("Could not initialize DisplayControl", e);
        }
        CLASS = displayControlClass;
    }

    private DisplayControl() {
        // only static methods
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        try {
            return (IBinder) CLASS.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physicalDisplayId);
        } catch (Exception e) {
            Ln.e("DisplayControl.getPhysicalDisplayToken failed", e);
            return null;
        }
    }

    public static long[] getPhysicalDisplayIds() {
        try {
            return (long[]) CLASS.getMethod("getPhysicalDisplayIds").invoke(null);
        } catch (Exception e) {
            Ln.e("DisplayControl.getPhysicalDisplayIds failed", e);
            return null;
        }
    }
}
