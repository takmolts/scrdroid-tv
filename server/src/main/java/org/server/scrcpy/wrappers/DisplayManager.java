package org.server.scrcpy.wrappers;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.os.IInterface;
import android.view.Display;
import android.view.Surface;

import org.server.scrcpy.DisplayInfo;
import org.server.scrcpy.Ln;
import org.server.scrcpy.Size;
import org.server.scrcpy.util.Command;
import org.server.scrcpy.util.FakeContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class DisplayManager {
    private final IInterface manager;

    private Method createVirtualDisplayMethod;

    public DisplayManager(IInterface manager) {
        this.manager = manager;
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Command.execReadOutput("dumpsys", "display");
            return parseDisplayInfo(dumpsysDisplayOutput, displayId);
        } catch (Exception e) {
            Ln.e("Could not get display info from \"dumpsys display\" output", e);
            return null;
        }
    }

    public static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher m = regex.matcher(dumpsysDisplayOutput);
        if (!m.find()) {
            return null;
        }
        int flags = parseDisplayFlags(m.group(1));
        int width = Integer.parseInt(m.group(2));
        int height = Integer.parseInt(m.group(3));
        int rotation = Integer.parseInt(m.group(4));
        int layerStack = Integer.parseInt(m.group(5));

        return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags);
    }

    private static int parseDisplayFlags(String text) {
        Pattern regex = Pattern.compile("FLAG_[A-Z_]+");
        if (text == null) {
            return 0;
        }

        int flags = 0;
        Matcher m = regex.matcher(text);
        while (m.find()) {
            String flagString = m.group();
            try {
                Field filed = Display.class.getDeclaredField(flagString);
                flags |= filed.getInt(null);
            } catch (ReflectiveOperationException e) {
                // Silently ignore, some flags reported by "dumpsys display" are @TestApi
            }
        }
        return flags;
    }


    public DisplayInfo getDisplayInfo() {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, 0);
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            return new DisplayInfo(new Size(width, height), rotation);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
            if (displayInfo == null) {
                // fallback when displayInfo is null
                return getDisplayInfoFromDumpsysDisplay(displayId);
            }
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    public int[] getDisplayIds() {
        try {
            return (int[]) manager.getClass().getMethod("getDisplayIds").invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Method getCreateVirtualDisplayMethod() throws NoSuchMethodException {
        if (createVirtualDisplayMethod == null) {
            createVirtualDisplayMethod = android.hardware.display.DisplayManager.class
                    .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        }
        return createVirtualDisplayMethod;
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int displayIdToMirror, Surface surface) throws Exception {
        Method method = getCreateVirtualDisplayMethod();
        return (VirtualDisplay) method.invoke(null, name, width, height, displayIdToMirror, surface);
    }

    /**
     * 仮想ディスプレイモード用
     * 既存 createVirtualDisplay (5引数版) は static reflection 呼び出しで動作しているが、
     * 独立画面用の 6引数版 (flags 付き) は android.hardware.display.DisplayManager の
     * インスタンスメソッドを直接呼ぶ必要がある。
     *
     * 注意: FakeContext.getSystemService(DISPLAY_SERVICE) は base(system) context に委譲され、
     * 生成される DisplayManager が package="android"(uid 1000) で動作するため、実際の呼び出し元
     * shell(uid 2000) と不一致になり
     *   SecurityException: packageName must match the calling uid
     * で失敗する。これを避けるため、FakeContext(package="com.android.shell", uid=SHELL) を
     * 直接束ねた DisplayManager インスタンスを生成して呼び出す。
     *
     * 使用フラグ例:
     *   VIRTUAL_DISPLAY_FLAG_PUBLIC = 1
     *   VIRTUAL_DISPLAY_FLAG_PRESENTATION = 2
     *   VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 8
     *   VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10 (Android 11+)
     */
    public VirtualDisplay createVirtualDisplayWithFlags(String name, int width, int height, int dpi, Surface surface, int flags)
            throws Exception {
        Context ctx = FakeContext.get();
        android.hardware.display.DisplayManager dm = newDisplayManagerWithContext(ctx);
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
    }

    /**
     * 指定 Context を束ねた android.hardware.display.DisplayManager を生成する。
     * DisplayManager(Context) は @hide だがコンストラクタ自体は存在するためリフレクションで呼ぶ。
     * 失敗時は getSystemService にフォールバックする。
     */
    private static android.hardware.display.DisplayManager newDisplayManagerWithContext(Context ctx) throws Exception {
        try {
            java.lang.reflect.Constructor<android.hardware.display.DisplayManager> ctor =
                    android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ctx);
        } catch (ReflectiveOperationException e) {
            Ln.w("newDisplayManagerWithContext: constructor reflection failed, fallback to getSystemService (" + e + ")");
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                throw new IllegalStateException("DISPLAY_SERVICE unavailable from FakeContext");
            }
            return dm;
        }
    }
}
