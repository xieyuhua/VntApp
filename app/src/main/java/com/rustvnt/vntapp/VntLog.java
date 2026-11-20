package com.rustvnt.vntapp;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 进程内运行日志。
 *
 * <p>VNT 的状态全部来自 Rust 核心的回调与 Service 生命周期，没有独立的日志文件。
 * 这里用一个有界环形缓冲保存最近的运行记录，供"运行日志"页面展示。
 * 同时写一份到 logcat，方便 adb 排查。
 *
 * <p>Service 与 Activity 在同一进程，因此用静态实例即可，无需跨进程通信；
 * 所有访问都在锁内完成，回调线程与主线程可以并发读写。
 */
final class VntLog {
    /** 单条记录最多保留的字符数，避免异常信息把缓冲撑爆。 */
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int CAPACITY = 500;

    enum Level { INFO, WARN, ERROR }

    record Entry(long timestamp, Level level, String tag, String message) {
        String time() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timestamp));
        }
    }

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>(CAPACITY);
    private static long version;

    private VntLog() {}

    static void info(String tag, String message) { add(Level.INFO, tag, message); }

    static void warn(String tag, String message) { add(Level.WARN, tag, message); }

    static void error(String tag, String message) { add(Level.ERROR, tag, message); }

    static void error(String tag, String message, Throwable error) {
        String detail = error == null ? "" : "：" + rootMessage(error);
        add(Level.ERROR, tag, message + detail);
    }

    private static void add(Level level, String tag, String message) {
        String text = message == null ? "" : message;
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH) + "…";
        }
        int priority = switch (level) {
            case ERROR -> android.util.Log.ERROR;
            case WARN -> android.util.Log.WARN;
            default -> android.util.Log.INFO;
        };
        android.util.Log.println(priority, "VNT/" + tag, text);

        Entry entry = new Entry(System.currentTimeMillis(), level, tag, text);
        synchronized (ENTRIES) {
            if (ENTRIES.size() >= CAPACITY) ENTRIES.removeFirst();
            ENTRIES.addLast(entry);
            version++;
        }
    }

    /** 返回从旧到新的快照，调用方可以安全遍历。 */
    static List<Entry> snapshot() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }

    /** 单调递增的版本号，用于判断日志是否有变化。 */
    static long version() {
        synchronized (ENTRIES) {
            return version;
        }
    }

    static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            version++;
        }
    }

    static int size() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
