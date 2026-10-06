/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.headless;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The headless wire format after the request/response lines: frames of
 * {@code type:u8 | len:u32be | payload} in both directions
 * (docs/headless-api.md).
 */
public final class Frames {
    private Frames() {}

    /** ⇄ raw bytes: stdin up; PTY output or stdout down. */
    public static final int DATA = 0;
    /** ↓ exec stderr. */
    public static final int STDERR = 1;
    /** ↑ {@code cols:u16 rows:u16}. */
    public static final int RESIZE = 2;
    /** ↑ close the child's stdin (exec). */
    public static final int EOF = 3;
    /** ↑ {@code sig:u8}, sent to the exec'd process group. */
    public static final int SIGNAL = 4;
    /** ↓ {@code code:i32} (exit status, or -signal); the server closes after it. */
    public static final int EXIT = 5;
    /** ↓ one JSON object (install progress). */
    public static final int PROGRESS = 6;

    /** Largest payload accepted from a client. */
    public static final int MAX_PAYLOAD = 1 << 20;

    /** One received frame. */
    public static final class Frame {
        public final int type;
        public final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /** Reads one frame; null on a clean EOF at a frame boundary. */
    public static Frame read(DataInputStream in) throws IOException {
        int type = in.read();
        if (type < 0) return null;
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("frame too large: " + len);
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Frame(type, payload);
    }

    /** Serialized writes of lines, frames and raw bytes to one stream. */
    public static final class Writer {
        private final OutputStream out;
        private final byte[] header = new byte[5];

        public Writer(OutputStream out) {
            this.out = out;
        }

        public synchronized void line(String s) throws IOException {
            out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        public synchronized void frame(int type, byte[] b, int off, int len)
                throws IOException {
            header[0] = (byte) type;
            header[1] = (byte) (len >>> 24);
            header[2] = (byte) (len >>> 16);
            header[3] = (byte) (len >>> 8);
            header[4] = (byte) len;
            out.write(header);
            out.write(b, off, len);
            out.flush();
        }

        public synchronized void raw(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            out.flush();
        }

        public void exit(int code) throws IOException {
            frame(EXIT, new byte[] {(byte) (code >>> 24), (byte) (code >>> 16),
                    (byte) (code >>> 8), (byte) code}, 0, 4);
        }

        public void progress(String json) throws IOException {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            frame(PROGRESS, b, 0, b.length);
        }
    }

    /** Builds a frame as bytes (clients and tests). */
    public static byte[] encode(int type, byte[] payload) {
        byte[] f = new byte[5 + payload.length];
        f[0] = (byte) type;
        f[1] = (byte) (payload.length >>> 24);
        f[2] = (byte) (payload.length >>> 16);
        f[3] = (byte) (payload.length >>> 8);
        f[4] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 5, payload.length);
        return f;
    }
}
