const std = @import("std");

/// How a remote shell ended. Only the SSH transport decides this, never text on the terminal.
/// The numeric values are the JNI contract with `GhosttyNative.nativeSshShellExitState`.
pub const State = enum(c_int) {
    running = 0,
    /// The server reported a nonzero exit status. A nonzero status can only come from an
    /// exit-status request, so it is evidence of an exit.
    exited = 1,
    /// The server reported that the process was killed by a signal. The name is stored without SIG.
    signaled = 2,
    /// The remote channel closed and no exit evidence exists. libssh2 1.11.1 cannot tell an unreported
    /// exit status from 0, so a zero status is not treated as success.
    ended_unknown = 3,
    /// The session ended while the shell was still open. The shell has no exit status at all.
    transport_lost = 4,
};

pub const signal_name_capacity: usize = 32;

/// The recorded end of one shell. A shell ends exactly once: the first recorded end wins.
pub const Exit = struct {
    state: State = .running,
    code: c_int = 0,
    signal_name: [signal_name_capacity]u8 = undefined,
    signal_len: usize = 0,

    pub fn signalName(self: *const Exit) []const u8 {
        return self.signal_name[0..self.signal_len];
    }

    /// The remote side closed the channel. Evidence decides the state: a signal name wins, then a
    /// nonzero status. A zero status without a signal is ENDED_UNKNOWN, never a success. Ignored once
    /// any end is recorded.
    pub fn recordRemoteClose(self: *Exit, signal: ?[]const u8, status: c_int) void {
        if (self.state != .running) return;
        if (signal) |name| {
            const length = @min(name.len, signal_name_capacity);
            @memcpy(self.signal_name[0..length], name[0..length]);
            self.signal_len = length;
            self.state = .signaled;
        } else if (status != 0) {
            self.code = status;
            self.state = .exited;
        } else {
            self.state = .ended_unknown;
        }
    }

    /// The session ended before this shell's channel closed. Ignored once any end is recorded, so a
    /// process that already exited is never reported as lost, and vice versa.
    pub fn recordTransportLoss(self: *Exit) void {
        if (self.state != .running) return;
        self.state = .transport_lost;
    }
};

test "a nonzero remote exit status is recorded as an exit" {
    var failed: Exit = .{};
    failed.recordRemoteClose(null, 7);
    try std.testing.expectEqual(State.exited, failed.state);
    try std.testing.expectEqual(@as(c_int, 7), failed.code);
}

test "a zero status without evidence is unknown, never a success" {
    var exit: Exit = .{};
    exit.recordRemoteClose(null, 0);
    try std.testing.expectEqual(State.ended_unknown, exit.state);
}

test "a signal end keeps the name and never reports an exit code" {
    var exit: Exit = .{};
    exit.recordRemoteClose("HUP", 0);
    try std.testing.expectEqual(State.signaled, exit.state);
    try std.testing.expectEqualStrings("HUP", exit.signalName());
}

test "a signal wins over a status the server also sent" {
    var exit: Exit = .{};
    exit.recordRemoteClose("KILL", 9);
    try std.testing.expectEqual(State.signaled, exit.state);
}

test "long signal names are truncated to the fixed capacity" {
    var exit: Exit = .{};
    const long_name: [40]u8 = @splat('X');
    exit.recordRemoteClose(&long_name, 0);
    try std.testing.expectEqual(signal_name_capacity, exit.signalName().len);
    try std.testing.expectEqualStrings(long_name[0..signal_name_capacity], exit.signalName());
}

test "transport loss while running is never an exit" {
    var exit: Exit = .{};
    exit.recordTransportLoss();
    try std.testing.expectEqual(State.transport_lost, exit.state);
    try std.testing.expectEqual(@as(c_int, 0), exit.code);

    // A late remote close after the loss must not turn it into an exit or a success.
    exit.recordRemoteClose(null, 5);
    try std.testing.expectEqual(State.transport_lost, exit.state);
}

test "a recorded end is not overwritten by a later transport loss" {
    var exit: Exit = .{};
    exit.recordRemoteClose(null, 3);
    exit.recordTransportLoss();
    try std.testing.expectEqual(State.exited, exit.state);
    try std.testing.expectEqual(@as(c_int, 3), exit.code);

    var unknown: Exit = .{};
    unknown.recordRemoteClose(null, 0);
    unknown.recordTransportLoss();
    try std.testing.expectEqual(State.ended_unknown, unknown.state);
}

test "state numbers are the JNI contract" {
    try std.testing.expectEqual(@as(c_int, 0), @intFromEnum(State.running));
    try std.testing.expectEqual(@as(c_int, 1), @intFromEnum(State.exited));
    try std.testing.expectEqual(@as(c_int, 2), @intFromEnum(State.signaled));
    try std.testing.expectEqual(@as(c_int, 3), @intFromEnum(State.ended_unknown));
    try std.testing.expectEqual(@as(c_int, 4), @intFromEnum(State.transport_lost));
}
