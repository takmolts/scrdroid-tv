package org.server.scrcpy;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.server.scrcpy.util.FakeContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 対象端末にインストールされたランチャー可能アプリの一覧を標準出力へ出す。
 *
 * 出力形式（コントローラ側は "APP\t" で始まる行のみ解析する）:
 *   APPLIST_BEGIN
 *   APP\t<packageName>\t<label>
 *   ...
 *   APPLIST_END
 *
 * 失敗時: APPLIST_ERROR\t<message>
 */
public final class AppLister {

    private AppLister() {
        // not instantiable
    }

    private static final class Entry {
        final String pkg;
        final String label;

        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    public static void listAndPrint() {
        try {
            PackageManager pm = FakeContext.get().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

            Set<String> seen = new LinkedHashSet<>();
            List<Entry> entries = new ArrayList<>();
            for (ResolveInfo ri : resolveInfos) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (!seen.add(pkg)) {
                    continue; // 同一パッケージの複数ランチャーは1つにまとめる
                }
                String label = pkg;
                try {
                    CharSequence cs = ri.loadLabel(pm);
                    if (cs != null && cs.length() > 0) {
                        // タブ・改行はデリミタと衝突するため除去
                        label = cs.toString().replace('\t', ' ').replace('\n', ' ').trim();
                    }
                } catch (Throwable ignored) {
                    // ラベル取得に失敗したらパッケージ名で代用
                }
                entries.add(new Entry(pkg, label));
            }

            Collections.sort(entries, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });

            StringBuilder sb = new StringBuilder();
            sb.append("APPLIST_BEGIN\n");
            for (Entry e : entries) {
                sb.append("APP\t").append(e.pkg).append('\t').append(e.label).append('\n');
            }
            sb.append("APPLIST_END\n");
            System.out.print(sb);
            System.out.flush();
        } catch (Throwable e) {
            System.out.println("APPLIST_ERROR\t" + e);
        }
    }
}
