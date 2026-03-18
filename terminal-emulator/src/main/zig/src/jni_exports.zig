const std = @import("std");
const c = @cImport({
    @cInclude("jni.h");
});
const ghostty_log = @import("android_log.zig");
const core = @import("termux_ghostty.zig");

const jint = c.jint;
const jlong = c.jlong;
const jboolean = c.jboolean;
const jfloat = c.jfloat;

const transcript_flag_join_lines: jint = 1;
const transcript_flag_trim: jint = 1 << 1;

pub export fn Java_com_termux_terminal_GhosttyNative_nativeCreate(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    columns: jint,
    rows: jint,
    transcript_rows: jint,
    cell_width_pixels: jint,
    cell_height_pixels: jint,
) jlong {
    _ = env;
    _ = clazz;

    const session = core.termux_ghostty_session_create(columns, rows, transcript_rows, cell_width_pixels, cell_height_pixels) orelse {
        ghostty_log.err("jni nativeCreate failed cols={} rows={} transcript={} cellWidth={} cellHeight={}", .{ columns, rows, transcript_rows, cell_width_pixels, cell_height_pixels });
        return 0;
    };
    const handle: jlong = @intCast(@intFromPtr(session));
    ghostty_log.info("jni nativeCreate cols={} rows={} transcript={} cellWidth={} cellHeight={} handle=0x{x}", .{ columns, rows, transcript_rows, cell_width_pixels, cell_height_pixels, handle });
    return handle;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeDestroy(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    ghostty_log.info("jni nativeDestroy handle=0x{x}", .{ native_handle });
    core.termux_ghostty_session_destroy(sessionFromHandle(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeReset(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    ghostty_log.debug("jni nativeReset handle=0x{x}", .{ native_handle });
    core.termux_ghostty_session_reset(sessionFromHandle(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeResize(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    columns: jint,
    rows: jint,
    cell_width_pixels: jint,
    cell_height_pixels: jint,
) jint {
    _ = env;
    _ = clazz;
    const result = core.termux_ghostty_session_resize(sessionFromHandle(native_handle), columns, rows, cell_width_pixels, cell_height_pixels);
    if (result != 0) {
        ghostty_log.err("jni nativeResize failed handle=0x{x} cols={} rows={} cellWidth={} cellHeight={} result={}", .{ native_handle, columns, rows, cell_width_pixels, cell_height_pixels, result });
    } else {
        ghostty_log.debug("jni nativeResize handle=0x{x} cols={} rows={} cellWidth={} cellHeight={}", .{ native_handle, columns, rows, cell_width_pixels, cell_height_pixels });
    }
    return result;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeQueueMouseEvent(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    action: jint,
    button: jint,
    modifiers: jint,
    surface_x: jfloat,
    surface_y: jfloat,
    screen_width_px: jint,
    screen_height_px: jint,
    cell_width_px: jint,
    cell_height_px: jint,
    padding_top_px: jint,
    padding_right_px: jint,
    padding_bottom_px: jint,
    padding_left_px: jint,
) jint {
    _ = env;
    _ = clazz;

    const result = core.termux_ghostty_session_queue_mouse_event(
        sessionFromHandle(native_handle),
        action,
        button,
        modifiers,
        surface_x,
        surface_y,
        screen_width_px,
        screen_height_px,
        cell_width_px,
        cell_height_px,
        padding_top_px,
        padding_right_px,
        padding_bottom_px,
        padding_left_px,
    );
    if (result < 0) {
        ghostty_log.err("jni nativeQueueMouseEvent failed handle=0x{x} action={} button={} result={}", .{ native_handle, action, button, result });
    }
    return result;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeAppend(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    data: c.jbyteArray,
    offset: jint,
    length: jint,
) jint {
    _ = clazz;

    const jni = env orelse {
        ghostty_log.err("jni nativeAppend missing env handle=0x{x}", .{ native_handle });
        return 0;
    };
    const handle = sessionFromHandle(native_handle) orelse {
        ghostty_log.err("jni nativeAppend invalid handle=0x{x}", .{ native_handle });
        return 0;
    };
    if (length <= 0) {
        return 0;
    }

    const count = std.math.cast(usize, length) orelse return 0;
    var stack_buffer: [4096]u8 = undefined;
    var heap_buffer: ?[]u8 = null;
    const bytes: []u8 = if (count <= stack_buffer.len)
        stack_buffer[0..count]
    else blk: {
        const allocated = std.heap.c_allocator.alloc(u8, count) catch return 0;
        heap_buffer = allocated;
        break :blk allocated;
    };
    defer if (heap_buffer) |allocated| std.heap.c_allocator.free(allocated);

    jni.*.*.GetByteArrayRegion.?(jni, data, offset, length, @ptrCast(bytes.ptr));
    return @intCast(core.termux_ghostty_session_append(handle, bytes.ptr, bytes.len));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeDrainPendingOutput(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    buffer: c.jbyteArray,
    offset: jint,
    length: jint,
) jint {
    _ = clazz;

    const jni = env orelse return 0;
    const handle = sessionFromHandle(native_handle) orelse return 0;
    if (length <= 0) {
        return 0;
    }

    const count = std.math.cast(usize, length) orelse return 0;
    var stack_buffer: [4096]u8 = undefined;
    var heap_buffer: ?[]u8 = null;
    const out: []u8 = if (count <= stack_buffer.len)
        stack_buffer[0..count]
    else blk: {
        const allocated = std.heap.c_allocator.alloc(u8, count) catch return 0;
        heap_buffer = allocated;
        break :blk allocated;
    };
    defer if (heap_buffer) |allocated| std.heap.c_allocator.free(allocated);

    const written = core.termux_ghostty_session_drain_pending_output(handle, out.ptr, out.len);
    if (written > 0) {
        jni.*.*.SetByteArrayRegion.?(jni, buffer, offset, @intCast(written), @ptrCast(out.ptr));
        ghostty_log.debug("jni nativeDrainPendingOutput handle=0x{x} wrote={}", .{ native_handle, written });
    }
    return @intCast(written);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSetViewportTopRow(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    top_row: jint,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_set_viewport_top_row(sessionFromHandle(native_handle), top_row);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeRequestFullSnapshotRefresh(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    core.termux_ghostty_session_request_full_snapshot_refresh(sessionFromHandle(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeFillSnapshotCurrentViewport(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    buffer: c.jobject,
    capacity: jint,
) jint {
    _ = clazz;

    const jni = env orelse {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport missing env handle=0x{x}", .{ native_handle });
        return -1;
    };
    const handle = sessionFromHandle(native_handle) orelse {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport invalid handle=0x{x}", .{ native_handle });
        return -1;
    };
    if (capacity <= 0) {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport invalid capacity handle=0x{x} capacity={}", .{ native_handle, capacity });
        return -1;
    }

    const address = jni.*.*.GetDirectBufferAddress.?(jni, buffer) orelse {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport missing direct buffer address handle=0x{x}", .{ native_handle });
        return -1;
    };
    const count = std.math.cast(usize, capacity) orelse return -1;
    const result = core.termux_ghostty_session_fill_snapshot_current_viewport(handle, @ptrCast(address), count);
    if (result < 0) {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport failed handle=0x{x} capacity={} result={}", .{ native_handle, capacity, result });
    } else if (result > capacity) {
        ghostty_log.warn("jni nativeFillSnapshotCurrentViewport buffer too small handle=0x{x} required={} capacity={}", .{ native_handle, result, capacity });
    }
    return result;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeFillSnapshot(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    top_row: jint,
    buffer: c.jobject,
    capacity: jint,
) jint {
    _ = clazz;

    const jni = env orelse {
        ghostty_log.err("jni nativeFillSnapshot missing env handle=0x{x}", .{ native_handle });
        return -1;
    };
    const handle = sessionFromHandle(native_handle) orelse {
        ghostty_log.err("jni nativeFillSnapshot invalid handle=0x{x}", .{ native_handle });
        return -1;
    };
    if (capacity <= 0) {
        ghostty_log.err("jni nativeFillSnapshot invalid capacity handle=0x{x} capacity={}", .{ native_handle, capacity });
        return -1;
    }

    const address = jni.*.*.GetDirectBufferAddress.?(jni, buffer) orelse {
        ghostty_log.err("jni nativeFillSnapshot missing direct buffer address handle=0x{x}", .{ native_handle });
        return -1;
    };
    const count = std.math.cast(usize, capacity) orelse return -1;
    const result = core.termux_ghostty_session_fill_snapshot(handle, top_row, @ptrCast(address), count);
    if (result < 0) {
        ghostty_log.err("jni nativeFillSnapshot failed handle=0x{x} topRow={} capacity={} result={}", .{ native_handle, top_row, capacity, result });
    } else if (result > capacity) {
        ghostty_log.warn("jni nativeFillSnapshot buffer too small handle=0x{x} topRow={} required={} capacity={}", .{ native_handle, top_row, result, capacity });
    }
    return result;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeConsumeTitle(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) c.jstring {
    _ = clazz;

    const jni = env orelse return null;
    const owned = core.termux_ghostty_session_consume_title(sessionFromHandle(native_handle)) orelse return null;
    defer std.heap.c_allocator.free(owned);
    return newJStringFromUtf8(jni, owned);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeConsumeClipboardText(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) c.jstring {
    _ = clazz;

    const jni = env orelse return null;
    const owned = core.termux_ghostty_session_consume_clipboard_text(sessionFromHandle(native_handle)) orelse return null;
    defer std.heap.c_allocator.free(owned);
    return newJStringFromUtf8(jni, owned);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeConsumeNotificationTitle(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) c.jstring {
    _ = clazz;

    const jni = env orelse return null;
    const owned_ptr = core.termux_ghostty_session_consume_notification_title(sessionFromHandle(native_handle)) orelse return null;
    const owned = std.mem.span(owned_ptr);
    defer std.heap.c_allocator.free(owned);
    return newJStringFromUtf8(jni, owned);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeConsumeNotificationBody(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) c.jstring {
    _ = clazz;

    const jni = env orelse return null;
    const owned_ptr = core.termux_ghostty_session_consume_notification_body(sessionFromHandle(native_handle)) orelse return null;
    const owned = std.mem.span(owned_ptr);
    defer std.heap.c_allocator.free(owned);
    return newJStringFromUtf8(jni, owned);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetProgressState(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_progress_state(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetProgressValue(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_progress_value(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetProgressGeneration(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jlong {
    _ = env;
    _ = clazz;
    return @intCast(core.termux_ghostty_session_get_progress_generation(sessionFromHandleConst(native_handle)));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeClearProgress(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    core.termux_ghostty_session_clear_progress(sessionFromHandle(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetColumns(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_columns(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetRows(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_rows(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetActiveRows(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_active_rows(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetActiveTranscriptRows(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_active_transcript_rows(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetModeBits(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return @intCast(core.termux_ghostty_session_get_mode_bits(sessionFromHandleConst(native_handle)));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetCursorRow(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_cursor_row(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetCursorCol(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_cursor_col(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetCursorStyle(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jint {
    _ = env;
    _ = clazz;
    return core.termux_ghostty_session_get_cursor_style(sessionFromHandleConst(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeIsCursorVisible(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jboolean {
    _ = env;
    _ = clazz;
    return toJBoolean(core.termux_ghostty_session_is_cursor_visible(sessionFromHandleConst(native_handle)));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeIsReverseVideo(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jboolean {
    _ = env;
    _ = clazz;
    return toJBoolean(core.termux_ghostty_session_is_reverse_video(sessionFromHandleConst(native_handle)));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeIsAlternateBufferActive(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) jboolean {
    _ = env;
    _ = clazz;
    return toJBoolean(core.termux_ghostty_session_is_alternate_buffer_active(sessionFromHandleConst(native_handle)));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetSelectedText(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    start_column: jint,
    start_row: jint,
    end_column: jint,
    end_row: jint,
    flags: jint,
) c.jstring {
    _ = clazz;
    _ = flags;

    const jni = env orelse return null;
    const owned = core.termux_ghostty_session_get_selected_text(
        sessionFromHandle(native_handle),
        start_column,
        start_row,
        end_column,
        end_row,
        false,
    ) orelse return null;
    defer std.heap.c_allocator.free(owned);
    return newJStringFromUtf8(jni, owned);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetWordAtLocation(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    column: jint,
    row: jint,
) c.jstring {
    _ = clazz;

    const jni = env orelse return null;
    const owned = core.termux_ghostty_session_get_word_at_location(sessionFromHandle(native_handle), column, row) orelse return null;
    defer std.heap.c_allocator.free(owned);
    return newJStringFromUtf8(jni, owned);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetTranscriptText(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    flags: jint,
) c.jstring {
    _ = clazz;

    const jni = env orelse return null;
    const join_lines = (flags & transcript_flag_join_lines) != 0;
    const trim = (flags & transcript_flag_trim) != 0;
    const owned = core.termux_ghostty_session_get_transcript_text(
        sessionFromHandle(native_handle),
        join_lines,
        trim,
    ) orelse return null;
    defer std.heap.c_allocator.free(owned);
    return newJStringFromUtf8(jni, owned);
}

fn sessionFromHandle(native_handle: jlong) ?*core.Session {
    if (native_handle <= 0) {
        return null;
    }
    return @ptrFromInt(@as(usize, @intCast(native_handle)));
}

fn sessionFromHandleConst(native_handle: jlong) ?*const core.Session {
    if (native_handle <= 0) {
        return null;
    }
    return @ptrFromInt(@as(usize, @intCast(native_handle)));
}

fn toJBoolean(value: bool) jboolean {
    return if (value) c.JNI_TRUE else c.JNI_FALSE;
}

fn newJStringFromUtf8(env: *c.JNIEnv, utf8: []const u8) c.jstring {
    var units: std.ArrayListUnmanaged(c.jchar) = .empty;
    defer units.deinit(std.heap.c_allocator);

    var view = std.unicode.Utf8View.init(utf8) catch return null;
    var iterator = view.iterator();
    while (iterator.nextCodepoint()) |codepoint| {
        if (codepoint <= 0xFFFF) {
            units.append(std.heap.c_allocator, @intCast(codepoint)) catch return null;
            continue;
        }

        const scalar: u32 = codepoint - 0x10000;
        units.append(std.heap.c_allocator, @intCast(0xD800 + (scalar >> 10))) catch return null;
        units.append(std.heap.c_allocator, @intCast(0xDC00 + (scalar & 0x3FF))) catch return null;
    }

    return env.*.*.NewString.?(env, if (units.items.len == 0) null else units.items.ptr, @intCast(units.items.len));
}
