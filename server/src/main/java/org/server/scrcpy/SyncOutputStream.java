package org.server.scrcpy;

import java.io.IOException;
import java.io.OutputStream;

/**
 * write 呼び出し単位で排他する OutputStream。
 * 1回の write(byte[]) が1パケットになっているので、パケット同士が混ざらなくなる。
 */
final class SyncOutputStream extends OutputStream {

    private final OutputStream out;

    SyncOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        out.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
