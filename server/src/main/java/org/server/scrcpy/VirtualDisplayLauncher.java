package org.server.scrcpy;

import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;

import org.server.scrcpy.wrappers.ServiceManager;

/**
 * 仮想ディスプレイモードで、生成した VirtualDisplay 上にアプリ（またはホーム/ランチャー）を起動する。
 *
 * VirtualDisplay は OWN_CONTENT_ONLY で作られており、明示的に何かを配置しない限り黒画面のままになる。
 * ここで ActivityOptions.setLaunchDisplayId(displayId) を使い、対象ディスプレイにアクティビティを起動する。
 */
public final class VirtualDisplayLauncher {

    private VirtualDisplayLauncher() {
        // not instantiable
    }

    /**
     * 指定ディスプレイにアプリ/ホームを起動する。
     *
     * @param displayId 起動先の VirtualDisplay の displayId
     * @param launchPackage 起動するパッケージ名。null/空/"-" ならホーム（ランチャー）を起動
     */
    public static void launch(int displayId, String launchPackage) {
        if (displayId < 0) {
            Ln.e("VirtualDisplayLauncher: invalid displayId=" + displayId);
            return;
        }

        boolean home = launchPackage == null || launchPackage.isEmpty() || "-".equals(launchPackage);

        Intent intent = new Intent(Intent.ACTION_MAIN);
        // 新しいタスクとして対象ディスプレイに起動する
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (home) {
            intent.addCategory(Intent.CATEGORY_HOME);
        } else {
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            // component は指定せず、ActivityManagerService 側でランチャーアクティビティを解決させる
            intent.setPackage(launchPackage);
        }

        Bundle optionsBundle = null;
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            optionsBundle = options.toBundle();
        } catch (Throwable e) {
            Ln.e("VirtualDisplayLauncher: could not build ActivityOptions (displayId=" + displayId + ")", e);
            // options なしでも一応試す（既定ディスプレイに出てしまう可能性あり）
        }

        try {
            int result = ServiceManager.getActivityManager().startActivity(intent, optionsBundle);
            // release ビルドでも見えるよう INFO レベルで出す
            Ln.i("VirtualDisplayLauncher: startActivity result=" + result
                    + " displayId=" + displayId
                    + " target=" + (home ? "HOME" : launchPackage));
        } catch (Throwable e) {
            Ln.e("VirtualDisplayLauncher: startActivity failed (displayId=" + displayId
                    + ", target=" + (home ? "HOME" : launchPackage) + ")", e);
        }
    }
}
