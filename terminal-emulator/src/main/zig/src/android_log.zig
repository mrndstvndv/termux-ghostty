const std = @import("std");
const builtin = @import("builtin");
const build_options = @import("termux_ghostty_build_options");

const is_android = builtin.target.abi.isAndroid();
const c = if (is_android) @cImport({
    @cInclude("android/log.h");
}) else struct {};

const Level = enum {
    debug,
    info,
    warn,
    err,
};

const log_tag = "TermuxGhostty";
const buffer_size = 1024;

pub fn enabled() bool {
    return build_options.enable_logging;
}

pub fn debug(comptime fmt: []const u8, args: anytype) void {
    if (!enabled()) return;
    log(.debug, fmt, args);
}

pub fn info(comptime fmt: []const u8, args: anytype) void {
    if (!enabled()) return;
    log(.info, fmt, args);
}

pub fn warn(comptime fmt: []const u8, args: anytype) void {
    log(.warn, fmt, args);
}

pub fn err(comptime fmt: []const u8, args: anytype) void {
    log(.err, fmt, args);
}

fn log(comptime level: Level, comptime fmt: []const u8, args: anytype) void {
    var buffer: [buffer_size]u8 = undefined;
    const message = std.fmt.bufPrintZ(&buffer, fmt, args) catch return;

    if (is_android) {
        _ = c.__android_log_write(androidPriority(level), log_tag.ptr, message.ptr);
        return;
    }

    std.debug.print("[{s}] {s}\n", .{ log_tag, message });
}

fn androidPriority(comptime level: Level) i32 {
    return switch (level) {
        .debug => c.ANDROID_LOG_DEBUG,
        .info => c.ANDROID_LOG_INFO,
        .warn => c.ANDROID_LOG_WARN,
        .err => c.ANDROID_LOG_ERROR,
    };
}
