package org.server.scrcpy;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import org.server.scrcpy.wrappers.ServiceManager;
import org.server.scrcpy.wrappers.SurfaceControl;


public class ScreenCapture {

    // Android VirtualDisplay 用フラグ (android.hardware.display.DisplayManager 定義値)
    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1;
    private static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    // Android 11 (R) 以降の非公開フラグ。アプリ起動・入力注入を許可するには TRUSTED が必要
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 14;
    // Android 13 (T) 以降。独立したディスプレイグループ + 独自フォーカスにすることで
    // 物理画面のフォーカスを奪わずに仮想画面へ入力できるようにする
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 18;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 19;

    private final Device device;
    private IBinder display;
    private VirtualDisplay virtualDisplay;
    // 仮想ディスプレイ上へアプリを起動済みの displayId（重複起動防止）
    private int launchedDisplayId = -1;

    public ScreenCapture(Device device) {
        this.device = device;
    }

    public void start(Surface surface) {
        ScreenInfo screenInfo = device.getScreenInfo();

        Rect deviceRect = device.getScreenInfo().getDeviceSize().toRect();
        Rect videoRect = device.getScreenInfo().getVideoSize().toRect();


        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        Options options = device.getOptions();
        if (options != null && options.isVirtualDisplayMode()) {
            startVirtualDisplay(options, surface, videoRect);
            return;
        }

        try {
            virtualDisplay = ServiceManager.getDisplayManager()
                    .createVirtualDisplay("scrcpy", videoRect.width(), videoRect.height(), 0, surface);
            Ln.d("Display: using DisplayManager API");
        } catch (Exception displayManagerException) {
            try {
                display = createDisplay();
                setDisplaySurface(display, surface, deviceRect, videoRect);
            } catch (Exception surfaceControlException) {
                throw new AssertionError("Could not create display");
            }
        }
    }

    /**
     * 仮想ディスプレイモードの生成処理。
     * 物理画面と独立した VirtualDisplay を作り、その上にアプリ/ホームを起動する。
     * 端末・OS バージョンによってはフラグに権限が必要なため、フラグを段階的に落として再試行する。
     */
    private void startVirtualDisplay(Options options, Surface surface, Rect videoRect) {
        int dpi = options.getVirtualDpi() > 0 ? options.getVirtualDpi() : 320;
        int width = videoRect.width();
        int height = videoRect.height();

        // フラグを強い順に並べ、失敗したら順に弱くして再試行する
        int[] flagCandidates = buildFlagCandidates();

        Exception lastError = null;
        for (int i = 0; i < flagCandidates.length; i++) {
            int flags = flagCandidates[i];
            try {
                virtualDisplay = ServiceManager.getDisplayManager()
                        .createVirtualDisplayWithFlags("scrcpy-virtual", width, height, dpi, surface, flags);
                int displayId = -1;
                if (virtualDisplay != null && virtualDisplay.getDisplay() != null) {
                    displayId = virtualDisplay.getDisplay().getDisplayId();
                }
                device.setVirtualDisplayId(displayId);
                // release ビルドでも見えるよう INFO レベルで出す（Ln.d は release で抑制される）
                Ln.i("Display: virtual display mode (id=" + displayId
                        + ", flags=0x" + Integer.toHexString(flags)
                        + ", size=" + width + "x" + height + ", dpi=" + dpi + ")");

                // 生成できたディスプレイにアプリ/ホームを起動（同一 displayId への二重起動は防ぐ）
                if (displayId >= 0 && displayId != launchedDisplayId) {
                    VirtualDisplayLauncher.launch(displayId, options.getVirtualLaunchPackage());
                    launchedDisplayId = displayId;
                }
                return;
            } catch (Exception e) {
                lastError = e;
                Log.e("ScreenCapture", "createVirtualDisplayWithFlags failed (flags=0x"
                        + Integer.toHexString(flags) + "), fallback...", e);
                if (virtualDisplay != null) {
                    try {
                        virtualDisplay.release();
                    } catch (Exception ignored) {
                        // ignore
                    }
                    virtualDisplay = null;
                }
            }
        }
        throw new AssertionError("Could not create virtual display", lastError);
    }

    /**
     * OS バージョンに応じた VirtualDisplay フラグ候補を、強い順に返す。
     * 先頭が最も機能的（TRUSTED 等付き）で、末尾に向かって権限要求の少ない安全側になる。
     */
    private static int[] buildFlagCandidates() {
        int base = VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

        // compileSdk が古く VERSION_CODES.TIRAMISU 等が無いため数値で判定する
        // (R=30 / Android 11, TIRAMISU=33 / Android 13)
        int trusted = base;
        if (Build.VERSION.SDK_INT >= 30) {
            trusted |= VIRTUAL_DISPLAY_FLAG_TRUSTED | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
        }

        int full = trusted;
        if (Build.VERSION.SDK_INT >= 33) {
            full |= VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP | VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;
        }

        if (full != trusted) {
            // full → trusted → base の順にフォールバック
            return new int[]{full, trusted, base};
        } else if (trusted != base) {
            return new int[]{trusted, base};
        } else {
            return new int[]{base};
        }
    }

    public void release() {
        device.setRotationListener(null);
        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    private static IBinder createDisplay() throws Exception {
        // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
        // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
        boolean secure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(
                Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, 0);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }
}
