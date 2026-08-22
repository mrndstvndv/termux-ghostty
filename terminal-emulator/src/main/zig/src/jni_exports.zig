const std = @import("std");
const ghostty_log = @import("android_log.zig");
const core = @import("termux_ghostty.zig");
const ssh = @import("ssh_native_session.zig");
// Shared cImport: zig 0.16 gives each @cImport call its own type namespace, so
// JNIEnv/JavaVM/etc. passed to ssh would otherwise be distinct types.
const c = ssh.c;

// Android Scudo rejects the 8-byte pointers returned by c_allocator's posix_memalign path.
const native_allocator = std.heap.c_allocator;

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
    ghostty_log.info("jni nativeDestroy handle=0x{x}", .{native_handle});
    core.termux_ghostty_session_destroy(sessionFromHandle(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeReset(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    ghostty_log.debug("jni nativeReset handle=0x{x}", .{native_handle});
    core.termux_ghostty_session_reset(sessionFromHandle(native_handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSetColorScheme(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    colors: c.jintArray,
) jint {
    _ = clazz;

    const jni = env orelse {
        ghostty_log.err("jni nativeSetColorScheme missing env handle=0x{x}", .{native_handle});
        return -1;
    };
    const handle = sessionFromHandle(native_handle) orelse {
        ghostty_log.err("jni nativeSetColorScheme invalid handle=0x{x}", .{native_handle});
        return -1;
    };

    const color_count = jni.*.*.GetArrayLength.?(jni, colors);
    if (color_count < 259) {
        ghostty_log.err("jni nativeSetColorScheme invalid color count handle=0x{x} count={}", .{ native_handle, color_count });
        return -1;
    }

    var color_buffer: [259]jint = undefined;
    jni.*.*.GetIntArrayRegion.?(jni, colors, 0, 259, &color_buffer);
    const colors_ptr: [*]const i32 = @ptrCast(color_buffer[0..].ptr);
    return @intCast(core.termux_ghostty_session_set_color_scheme(handle, colors_ptr, color_buffer.len));
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

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSetFocus(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    focused: jboolean,
) jint {
    _ = env;
    _ = clazz;
    return @intCast(core.termux_ghostty_session_set_focus(
        sessionFromHandle(native_handle),
        focused != c.JNI_FALSE,
    ));
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

    const jni = env orelse return 0;
    const handle = sessionFromHandle(native_handle) orelse return 0;
    if (data == null or length <= 0 or offset < 0) {
        return 0;
    }

    const count = std.math.cast(usize, length) orelse return 0;
    const offset_u = std.math.cast(usize, offset) orelse return 0;

    const raw_ptr = jni.*.*.GetPrimitiveArrayCritical.?(jni, data, null) orelse return 0;
    defer jni.*.*.ReleasePrimitiveArrayCritical.?(jni, data, raw_ptr, c.JNI_ABORT);

    const bytes_ptr: [*]const u8 = @ptrCast(raw_ptr);
    const bytes = bytes_ptr[offset_u .. offset_u + count];
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
    if (buffer == null or length <= 0 or offset < 0) {
        return 0;
    }

    const count = std.math.cast(usize, length) orelse return 0;
    const offset_u = std.math.cast(usize, offset) orelse return 0;

    const raw_ptr = jni.*.*.GetPrimitiveArrayCritical.?(jni, buffer, null) orelse return 0;
    defer jni.*.*.ReleasePrimitiveArrayCritical.?(jni, buffer, raw_ptr, 0);

    const bytes_ptr: [*]u8 = @ptrCast(raw_ptr);
    const out = bytes_ptr[offset_u .. offset_u + count];

    const written = core.termux_ghostty_session_drain_pending_output(handle, out.ptr, out.len);
    if (written > 0) {
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
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport missing env handle=0x{x}", .{native_handle});
        return -1;
    };
    const handle = sessionFromHandle(native_handle) orelse {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport invalid handle=0x{x}", .{native_handle});
        return -1;
    };
    if (capacity <= 0) {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport invalid capacity handle=0x{x} capacity={}", .{ native_handle, capacity });
        return -1;
    }

    const address = jni.*.*.GetDirectBufferAddress.?(jni, buffer) orelse {
        ghostty_log.err("jni nativeFillSnapshotCurrentViewport missing direct buffer address handle=0x{x}", .{native_handle});
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

pub export fn Java_com_termux_terminal_GhosttyNative_nativeFillViewportLinks(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
    buffer: c.jobject,
    capacity: jint,
) jint {
    _ = clazz;

    const jni = env orelse {
        ghostty_log.err("jni nativeFillViewportLinks missing env handle=0x{x}", .{native_handle});
        return -1;
    };
    const handle = sessionFromHandle(native_handle) orelse {
        ghostty_log.err("jni nativeFillViewportLinks invalid handle=0x{x}", .{native_handle});
        return -1;
    };
    if (capacity <= 0) {
        ghostty_log.err("jni nativeFillViewportLinks invalid capacity handle=0x{x} capacity={}", .{ native_handle, capacity });
        return -1;
    }

    const address = jni.*.*.GetDirectBufferAddress.?(jni, buffer) orelse {
        ghostty_log.err("jni nativeFillViewportLinks missing direct buffer address handle=0x{x}", .{native_handle});
        return -1;
    };
    const count = std.math.cast(usize, capacity) orelse return -1;
    const result = core.termux_ghostty_session_fill_viewport_links(handle, @ptrCast(address), count);
    if (result < 0) {
        ghostty_log.err("jni nativeFillViewportLinks failed handle=0x{x} capacity={} result={}", .{ native_handle, capacity, result });
    } else if (result > capacity) {
        ghostty_log.warn("jni nativeFillViewportLinks buffer too small handle=0x{x} required={} capacity={}", .{ native_handle, result, capacity });
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
        ghostty_log.err("jni nativeFillSnapshot missing env handle=0x{x}", .{native_handle});
        return -1;
    };
    const handle = sessionFromHandle(native_handle) orelse {
        ghostty_log.err("jni nativeFillSnapshot invalid handle=0x{x}", .{native_handle});
        return -1;
    };
    if (capacity <= 0) {
        ghostty_log.err("jni nativeFillSnapshot invalid capacity handle=0x{x} capacity={}", .{ native_handle, capacity });
        return -1;
    }

    const address = jni.*.*.GetDirectBufferAddress.?(jni, buffer) orelse {
        ghostty_log.err("jni nativeFillSnapshot missing direct buffer address handle=0x{x}", .{native_handle});
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
    defer native_allocator.free(owned);
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
    defer native_allocator.free(owned);
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
    const owned_slice = std.mem.span(owned_ptr);
    const owned_sentinel_slice = owned_ptr[0..owned_slice.len :0];
    defer native_allocator.free(owned_sentinel_slice);
    return newJStringFromUtf8(jni, owned_slice);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeConsumeNotificationBody(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) c.jstring {
    _ = clazz;

    const jni = env orelse return null;
    const owned_ptr = core.termux_ghostty_session_consume_notification_body(sessionFromHandle(native_handle)) orelse return null;
    const owned_slice = std.mem.span(owned_ptr);
    const owned_sentinel_slice = owned_ptr[0..owned_slice.len :0];
    defer native_allocator.free(owned_sentinel_slice);
    return newJStringFromUtf8(jni, owned_slice);
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
    defer native_allocator.free(owned);
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
    defer native_allocator.free(owned);
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
    defer native_allocator.free(owned);
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
    defer units.deinit(native_allocator);

    var i: usize = 0;
    while (i < utf8.len) {
        const seq_len = std.unicode.utf8ByteSequenceLength(utf8[i]) catch {
            units.append(native_allocator, 0xFFFD) catch return null;
            i += 1;
            continue;
        };
        if (i + seq_len > utf8.len) {
            units.append(native_allocator, 0xFFFD) catch return null;
            break;
        }
        const codepoint = std.unicode.utf8Decode(utf8[i .. i + seq_len]) catch {
            units.append(native_allocator, 0xFFFD) catch return null;
            i += 1;
            continue;
        };
        i += seq_len;

        if (codepoint <= 0xFFFF) {
            units.append(native_allocator, @intCast(codepoint)) catch return null;
            continue;
        }

        const scalar: u32 = codepoint - 0x10000;
        units.append(native_allocator, @intCast(0xD800 + (scalar >> 10))) catch return null;
        units.append(native_allocator, @intCast(0xDC00 + (scalar & 0x3FF))) catch return null;
    }

    return env.*.*.NewString.?(env, if (units.items.len == 0) null else units.items.ptr, @intCast(units.items.len));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshInit(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    socket_fd: jint,
    username: c.jstring,
    password_or_key: c.jstring,
    is_password: jboolean,
    term_type: c.jstring,
    cols: jint,
    rows: jint,
    herdr_integration: jboolean,
) jlong {
    _ = clazz;
    const jni = env orelse return 0;
    if (username == null or password_or_key == null or term_type == null) return 0;

    const username_chars = jni.*.*.GetStringUTFChars.?(jni, username, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, username, username_chars);
    const username_slice = std.mem.span(username_chars);

    const pass_key_chars = jni.*.*.GetStringUTFChars.?(jni, password_or_key, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, password_or_key, pass_key_chars);
    const pass_key_slice = std.mem.span(pass_key_chars);

    const term_type_chars = jni.*.*.GetStringUTFChars.?(jni, term_type, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, term_type, term_type_chars);
    const term_type_slice = std.mem.span(term_type_chars);

    const is_pass = (is_password != c.JNI_FALSE);
    const is_herdr = (herdr_integration != c.JNI_FALSE);

    var java_vm: ?*c.JavaVM = null;
    _ = jni.*.*.GetJavaVM.?(jni, &java_vm);

    const session = ssh.SshNativeSession.init(
        native_allocator,
        socket_fd,
        username_slice,
        pass_key_slice,
        is_pass,
        term_type_slice,
        cols,
        rows,
        is_herdr,
        java_vm,
    ) catch |err| {
        ghostty_log.err("nativeSshInit failed: {}", .{err});
        return 0;
    };

    return @intCast(@intFromPtr(session));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshStart(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    if (session_handle <= 0) return;
    const session = @as(*ssh.SshNativeSession, @ptrFromInt(@as(usize, @intCast(session_handle))));
    session.start() catch |err| {
        ghostty_log.err("nativeSshStart failed: {}", .{err});
    };
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshDeinit(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
) void {
    _ = clazz;
    if (session_handle <= 0) return;
    const session = @as(*ssh.SshNativeSession, @ptrFromInt(@as(usize, @intCast(session_handle))));
    session.deinit(env);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshWrite(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    data: c.jbyteArray,
    offset: jint,
    length: jint,
) void {
    _ = clazz;
    const jni = env orelse return;
    if (session_handle <= 0 or data == null or length <= 0 or offset < 0) return;

    const session = @as(*ssh.SshNativeSession, @ptrFromInt(@as(usize, @intCast(session_handle))));

    const count = std.math.cast(usize, length) orelse return;
    const offset_u = std.math.cast(usize, offset) orelse return;

    const raw_ptr = jni.*.*.GetPrimitiveArrayCritical.?(jni, data, null) orelse return;
    defer jni.*.*.ReleasePrimitiveArrayCritical.?(jni, data, raw_ptr, c.JNI_ABORT);

    const bytes_ptr: [*]const u8 = @ptrCast(raw_ptr);
    const bytes = bytes_ptr[offset_u .. offset_u + count];
    session.writeKeystrokes(bytes);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeGetKittyKeyboardFlags(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    native_handle: jlong,
) c_int {
    _ = env;
    _ = clazz;
    const session = sessionFromHandle(native_handle) orelse return 0;
    const screens = &session.terminal.screens;
    // OR primary + alternate so kitty is considered active whenever either
    // screen has it (the alternate screen may be created fresh by a full-screen
    // app such as tmux without inheriting the default we push at init).
    var flags: u5 = screens.get(.primary).?.kitty_keyboard.current().int();
    if (screens.get(.alternate)) |alt_screen| {
        flags |= alt_screen.kitty_keyboard.current().int();
    }
    return @intCast(flags);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshSetOutputCallback(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    callback: c.jobject,
) void {
    _ = clazz;
    const jni = env orelse return;
    if (session_handle <= 0) return;
    const session = @as(*ssh.SshNativeSession, @ptrFromInt(@as(usize, @intCast(session_handle))));
    session.setOutputCallback(jni, callback);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshResize(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    cols: jint,
    rows: jint,
) void {
    _ = env;
    _ = clazz;
    if (session_handle <= 0) return;
    const session = @as(*ssh.SshNativeSession, @ptrFromInt(@as(usize, @intCast(session_handle))));
    session.resizeChannel(cols, rows);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshIsRunning(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
) jboolean {
    _ = env;
    _ = clazz;
    if (session_handle <= 0) return c.JNI_FALSE;
    const session = @as(*ssh.SshNativeSession, @ptrFromInt(@as(usize, @intCast(session_handle))));
    return toJBoolean(session.running.load(.acquire));
}

// --- SSH Exec & SFTP Support ---

fn sshSessionFromHandle(session_handle: jlong) ?*ssh.SshNativeSession {
    if (session_handle <= 0) return null;
    return @as(*ssh.SshNativeSession, @ptrFromInt(@as(usize, @intCast(session_handle))));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSshExec(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    command: c.jstring,
) c.jstring {
    _ = clazz;
    const jni = env orelse return null;
    const session = sshSessionFromHandle(session_handle) orelse return null;
    if (command == null) return null;

    const chars = jni.*.*.GetStringUTFChars.?(jni, command, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, command, chars);
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(native_allocator);
    session.execCommand(std.mem.span(chars), &output) catch return null;
    return newJStringFromUtf8(jni, output.items);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpInit(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
) jlong {
    _ = env;
    _ = clazz;
    const session = sshSessionFromHandle(session_handle) orelse return 0;
    const handle = session.sftpInit() orelse return 0;
    return @intCast(@intFromPtr(handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpClose(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    sftp_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    const session = sshSessionFromHandle(session_handle) orelse return;
    if (sftp_handle <= 0) return;
    session.sftpClose(@ptrFromInt(@as(usize, @intCast(sftp_handle))));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpListFiles(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    sftp_handle: jlong,
    path: c.jstring,
) c.jstring {
    _ = clazz;
    const jni = env orelse return null;
    const session = sshSessionFromHandle(session_handle) orelse return null;
    if (sftp_handle <= 0 or path == null) return null;
    const chars = jni.*.*.GetStringUTFChars.?(jni, path, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, path, chars);
    var output: std.ArrayList(u8) = .empty;
    defer output.deinit(native_allocator);
    const handle: *anyopaque = @ptrFromInt(@as(usize, @intCast(sftp_handle)));
    if (!session.sftpListFiles(handle, std.mem.span(chars), &output)) return null;
    return newJStringFromUtf8(jni, output.items);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpMkdir(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    sftp_handle: jlong,
    path: c.jstring,
    permissions: jint,
) jboolean {
    _ = clazz;
    const jni = env orelse return c.JNI_FALSE;
    const session = sshSessionFromHandle(session_handle) orelse return c.JNI_FALSE;
    if (sftp_handle <= 0 or path == null) return c.JNI_FALSE;
    const chars = jni.*.*.GetStringUTFChars.?(jni, path, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, path, chars);
    const handle: *anyopaque = @ptrFromInt(@as(usize, @intCast(sftp_handle)));
    return if (session.sftpMkdir(handle, std.mem.span(chars), permissions)) c.JNI_TRUE else c.JNI_FALSE;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpDelete(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    sftp_handle: jlong,
    path: c.jstring,
) jboolean {
    _ = clazz;
    const jni = env orelse return c.JNI_FALSE;
    const session = sshSessionFromHandle(session_handle) orelse return c.JNI_FALSE;
    if (sftp_handle <= 0 or path == null) return c.JNI_FALSE;
    const chars = jni.*.*.GetStringUTFChars.?(jni, path, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, path, chars);
    const handle: *anyopaque = @ptrFromInt(@as(usize, @intCast(sftp_handle)));
    return if (session.sftpDelete(handle, std.mem.span(chars))) c.JNI_TRUE else c.JNI_FALSE;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpRename(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    sftp_handle: jlong,
    old_path: c.jstring,
    new_path: c.jstring,
) jboolean {
    _ = clazz;
    const jni = env orelse return c.JNI_FALSE;
    const session = sshSessionFromHandle(session_handle) orelse return c.JNI_FALSE;
    if (sftp_handle <= 0 or old_path == null or new_path == null) return c.JNI_FALSE;
    const old_chars = jni.*.*.GetStringUTFChars.?(jni, old_path, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, old_path, old_chars);
    const new_chars = jni.*.*.GetStringUTFChars.?(jni, new_path, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, new_path, new_chars);
    const handle: *anyopaque = @ptrFromInt(@as(usize, @intCast(sftp_handle)));
    return if (session.sftpRename(handle, std.mem.span(old_chars), std.mem.span(new_chars))) c.JNI_TRUE else c.JNI_FALSE;
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpFileOpen(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    sftp_handle: jlong,
    path: c.jstring,
    flags: jint,
    mode: jint,
) jlong {
    _ = clazz;
    const jni = env orelse return 0;
    const session = sshSessionFromHandle(session_handle) orelse return 0;
    if (sftp_handle <= 0 or path == null) return 0;
    const chars = jni.*.*.GetStringUTFChars.?(jni, path, null);
    defer jni.*.*.ReleaseStringUTFChars.?(jni, path, chars);
    const sftp: *anyopaque = @ptrFromInt(@as(usize, @intCast(sftp_handle)));
    const handle = session.sftpFileOpen(sftp, std.mem.span(chars), flags, mode) orelse return 0;
    return @intCast(@intFromPtr(handle));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpFileClose(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    file_handle: jlong,
) void {
    _ = env;
    _ = clazz;
    const session = sshSessionFromHandle(session_handle) orelse return;
    if (file_handle <= 0) return;
    session.sftpFileClose(@ptrFromInt(@as(usize, @intCast(file_handle))));
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpFileRead(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    file_handle: jlong,
    buffer: c.jbyteArray,
    offset: jint,
    length: jint,
) jint {
    _ = clazz;
    const jni = env orelse return -1;
    const session = sshSessionFromHandle(session_handle) orelse return -1;
    if (file_handle <= 0 or buffer == null or length <= 0 or offset < 0) return 0;

    const count = std.math.cast(usize, length) orelse return -1;
    const offset_u = std.math.cast(usize, offset) orelse return -1;

    const raw_ptr = jni.*.*.GetPrimitiveArrayCritical.?(jni, buffer, null) orelse return -1;
    defer jni.*.*.ReleasePrimitiveArrayCritical.?(jni, buffer, raw_ptr, 0);

    const bytes_ptr: [*]u8 = @ptrCast(raw_ptr);
    const bytes = bytes_ptr[offset_u .. offset_u + count];

    const file: *anyopaque = @ptrFromInt(@as(usize, @intCast(file_handle)));
    const result = session.sftpFileRead(file, bytes);
    return @intCast(result);
}

pub export fn Java_com_termux_terminal_GhosttyNative_nativeSftpFileWrite(
    env: ?*c.JNIEnv,
    clazz: c.jclass,
    session_handle: jlong,
    file_handle: jlong,
    buffer: c.jbyteArray,
    offset: jint,
    length: jint,
) jint {
    _ = clazz;
    const jni = env orelse return -1;
    const session = sshSessionFromHandle(session_handle) orelse return -1;
    if (file_handle <= 0 or buffer == null or length <= 0 or offset < 0) return 0;

    const count = std.math.cast(usize, length) orelse return -1;
    const offset_u = std.math.cast(usize, offset) orelse return -1;

    const raw_ptr = jni.*.*.GetPrimitiveArrayCritical.?(jni, buffer, null) orelse return -1;
    defer jni.*.*.ReleasePrimitiveArrayCritical.?(jni, buffer, raw_ptr, c.JNI_ABORT);

    const bytes_ptr: [*]const u8 = @ptrCast(raw_ptr);
    const bytes = bytes_ptr[offset_u .. offset_u + count];

    const file: *anyopaque = @ptrFromInt(@as(usize, @intCast(file_handle)));
    return @intCast(session.sftpFileWrite(file, bytes));
}
