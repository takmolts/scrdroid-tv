package org.server.scrcpy;

public class Options {
    private int maxSize;
    private int bitRate;
    private boolean tunnelForward;

    // 仮想ディスプレイモード（拡張ディスプレイモード）
    // true の場合、対象端末の物理画面とは独立した VirtualDisplay を生成しキャプチャする
    private boolean virtualDisplayMode;
    // 仮想ディスプレイサイズ・DPI。0 の場合は物理画面の値を使う
    private int virtualWidth;
    private int virtualHeight;
    private int virtualDpi;
    // 仮想ディスプレイ上に起動するアプリのパッケージ名。
    // null / 空 の場合はホーム（ランチャー）を起動する
    private String virtualLaunchPackage;
    // 最大フレームレート (0=制限なし)。低遅延化・帯域節約用
    private int maxFps;
    // 音声転送の有無
    private boolean audioEnabled = true;

    public int getMaxFps() {
        return maxFps;
    }

    public void setMaxFps(int maxFps) {
        this.maxFps = maxFps;
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public void setAudioEnabled(boolean audioEnabled) {
        this.audioEnabled = audioEnabled;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getBitRate() {
        return bitRate;
    }

    public void setBitRate(int bitRate) {
        this.bitRate = bitRate;
    }

    public boolean isTunnelForward() {
        return tunnelForward;
    }

    public void setTunnelForward(boolean tunnelForward) {
        this.tunnelForward = tunnelForward;
    }

    public boolean isVirtualDisplayMode() {
        return virtualDisplayMode;
    }

    public void setVirtualDisplayMode(boolean virtualDisplayMode) {
        this.virtualDisplayMode = virtualDisplayMode;
    }

    public int getVirtualWidth() {
        return virtualWidth;
    }

    public void setVirtualWidth(int virtualWidth) {
        this.virtualWidth = virtualWidth;
    }

    public int getVirtualHeight() {
        return virtualHeight;
    }

    public void setVirtualHeight(int virtualHeight) {
        this.virtualHeight = virtualHeight;
    }

    public int getVirtualDpi() {
        return virtualDpi;
    }

    public void setVirtualDpi(int virtualDpi) {
        this.virtualDpi = virtualDpi;
    }

    public String getVirtualLaunchPackage() {
        return virtualLaunchPackage;
    }

    public void setVirtualLaunchPackage(String virtualLaunchPackage) {
        this.virtualLaunchPackage = virtualLaunchPackage;
    }

    /**
     * 仮想ディスプレイでホーム（ランチャー）を起動するか。
     * パッケージ未指定（null / 空 / "-"）ならホームを起動する。
     */
    public boolean isVirtualLaunchHome() {
        return virtualLaunchPackage == null
                || virtualLaunchPackage.isEmpty()
                || "-".equals(virtualLaunchPackage);
    }
}
