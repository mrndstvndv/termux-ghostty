const std = @import("std");
const SpscRingBuffer = @import("spsc_ring_buffer.zig").SpscRingBuffer;

const c = @cImport({
    @cInclude("libssh2.h");
    @cInclude("unistd.h");
    @cInclude("poll.h");
    @cInclude("fcntl.h");
});

pub const SshNativeSession = struct {
    allocator: std.mem.Allocator,
    session: *c.LIBSSH2_SESSION,
    channel: *c.LIBSSH2_CHANNEL,
    socket_fd: c_int,
    wake_pipe: [2]c_int,
    jvm_wake_pipe: [2]c_int,
    spsc_buffer: *SpscRingBuffer,
    write_lock: std.Thread.Mutex,
    write_queue: std.ArrayList(u8),
    thread: ?std.Thread,
    running: std.atomic.Value(bool),
    pending_cols: c_int,
    pending_rows: c_int,

    pub fn init(
        allocator: std.mem.Allocator,
        socket_fd: c_int,
        username: []const u8,
        password_or_key: []const u8,
        is_password: bool,
        term_type: []const u8,
        cols: c_int,
        rows: c_int,
        buffer_capacity: usize,
        herdr_integration: bool,
    ) !*SshNativeSession {
        _ = c.libssh2_init(0);

        const session = c.libssh2_session_init_ex(null, null, null, null) orelse return error.SessionInitFailed;
        errdefer _ = c.libssh2_session_free(session);

        _ = c.libssh2_session_set_blocking(session, 0);

        // Set underlying socket to non-blocking mode in the OS
        const flags = c.fcntl(socket_fd, c.F_GETFL, @as(c_int, 0));
        _ = c.fcntl(socket_fd, c.F_SETFL, flags | c.O_NONBLOCK);

        // Handshake
        while (true) {
            const rc = c.libssh2_session_handshake(session, socket_fd);
            if (rc == 0) break;
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.HandshakeFailed;
        }

        // Authenticate
        if (is_password) {
            // Null-terminate password
            const password_c = try allocator.dupeZ(u8, password_or_key);
            defer allocator.free(password_c);
            while (true) {
                const rc = c.libssh2_userauth_password_ex(session, username.ptr, @intCast(username.len), password_c.ptr, @intCast(password_or_key.len), null);
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                return error.AuthFailed;
            }
        } else {
            // Public key auth from memory
            while (true) {
                const rc = c.libssh2_userauth_publickey_frommemory(
                    session,
                    username.ptr,
                    username.len,
                    null,
                    0,
                    password_or_key.ptr,
                    password_or_key.len,
                    null,
                );
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                return error.AuthFailed;
            }
        }

        // Open shell channel
        const channel = while (true) {
            const ch = c.libssh2_channel_open_ex(session, "session", @intCast("session".len), c.LIBSSH2_CHANNEL_WINDOW_DEFAULT, c.LIBSSH2_CHANNEL_PACKET_DEFAULT, null, 0);
            if (ch) |val| break val;
            const rc = c.libssh2_session_last_error(session, null, null, 0);
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.ChannelOpenFailed;
        };
        errdefer _ = c.libssh2_channel_free(channel);

        // Request PTY
        const term_c = try allocator.dupeZ(u8, term_type);
        defer allocator.free(term_c);
        while (true) {
            const rc = c.libssh2_channel_request_pty_ex(channel, term_c.ptr, @intCast(term_type.len), null, 0, cols, rows, 0, 0);
            if (rc == 0) break;
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.PtyRequestFailed;
        }

        // Set locale on remote side so libc's wcwidth() gives correct widths for braille etc.
        {
            const env_name = "LC_ALL";
            const env_val = "en_US.UTF-8";
            var attempt: i32 = 0;
            while (attempt < 50) : (attempt += 1) {
                const rc = c.libssh2_channel_setenv_ex(channel, env_name, @intCast(env_name.len), env_val, @intCast(env_val.len));
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                break;
            }
        }
        {
            const env_name = "LANG";
            const env_val = "en_US.UTF-8";
            var attempt: i32 = 0;
            while (attempt < 50) : (attempt += 1) {
                const rc = c.libssh2_channel_setenv_ex(channel, env_name, @intCast(env_name.len), env_val, @intCast(env_val.len));
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                break;
            }
        }
        {
            const env_name = "LC_CTYPE";
            const env_val = "en_US.UTF-8";
            var attempt: i32 = 0;
            while (attempt < 50) : (attempt += 1) {
                const rc = c.libssh2_channel_setenv_ex(channel, env_name, @intCast(env_name.len), env_val, @intCast(env_val.len));
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                break;
            }
        }

        // Optional environment variables passing (Approach B)
        if (herdr_integration) {
            // Set TERM_PROGRAM and LC_TERM_PROGRAM variables on the channel.
            // Some servers with lax AcceptEnv config might accept TERM_PROGRAM,
            // and most accept LC_TERM_PROGRAM.
            const vars = [_]struct { k: []const u8, v: []const u8 }{
                .{ .k = "TERM_PROGRAM", .v = "ghostty" },
                .{ .k = "LC_TERM_PROGRAM", .v = "ghostty" },
            };
            for (vars) |v| {
                var attempt: i32 = 0;
                while (attempt < 50) : (attempt += 1) {
                    const rc = c.libssh2_channel_setenv_ex(channel, v.k.ptr, @intCast(v.k.len), v.v.ptr, @intCast(v.v.len));
                    if (rc == 0) break;
                    if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                        try waitSocket(socket_fd, session);
                        continue;
                    }
                    break;
                }
            }
        }

        while (true) {
            const rc = if (herdr_integration) blk: {
                const cmd = "env TERM_PROGRAM=ghostty sh -c \"if command -v herdr >/dev/null 2>&1; then exec herdr; else exec \\${SHELL:-/bin/sh} -l; fi\"";
                break :blk c.libssh2_channel_process_startup(channel, "exec", 4, cmd.ptr, @intCast(cmd.len));
            } else blk: {
                break :blk c.libssh2_channel_process_startup(channel, "shell", @intCast("shell".len), null, 0);
            };
            if (rc == 0) break;
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.ShellStartFailed;
        }

        // Pipes
        var wake_p: [2]c_int = undefined;
        if (c.pipe(&wake_p) != 0) return error.PipeFailed;
        errdefer {
            _ = c.close(wake_p[0]);
            _ = c.close(wake_p[1]);
        }

        var jvm_wake_p: [2]c_int = undefined;
        if (c.pipe(&jvm_wake_p) != 0) return error.PipeFailed;
        errdefer {
            _ = c.close(jvm_wake_p[0]);
            _ = c.close(jvm_wake_p[1]);
        }

        _ = c.fcntl(wake_p[0], c.F_SETFL, c.O_NONBLOCK);
        _ = c.fcntl(jvm_wake_p[0], c.F_SETFL, c.O_NONBLOCK);

        const spsc_buffer = try SpscRingBuffer.init(allocator, buffer_capacity);
        errdefer spsc_buffer.deinit();

        const self = try allocator.create(SshNativeSession);
        self.* = .{
            .allocator = allocator,
            .session = session,
            .channel = channel,
            .socket_fd = socket_fd,
            .wake_pipe = wake_p,
            .jvm_wake_pipe = jvm_wake_p,
            .spsc_buffer = spsc_buffer,
            .write_lock = .{},
            .write_queue = .empty,
            .thread = null,
            .running = std.atomic.Value(bool).init(true),
            .pending_cols = -1,
            .pending_rows = -1,
        };

        return self;
    }

    pub fn start(self: *SshNativeSession) !void {
        self.thread = try std.Thread.spawn(.{}, runLoop, .{self});
    }

    pub fn deinit(self: *SshNativeSession) void {
        self.running.store(false, .release);
        
        // Signal to wake up poll loop
        const dummy: u8 = 1;
        _ = c.write(self.wake_pipe[1], &dummy, 1);

        if (self.thread) |t| {
            t.join();
        }

        // Cleanup libssh2
        _ = c.libssh2_channel_free(self.channel);
        _ = c.libssh2_session_disconnect_ex(self.session, c.SSH_DISCONNECT_BY_APPLICATION, "Shutdown", "");
        _ = c.libssh2_session_free(self.session);

        // Close pipes
        _ = c.close(self.wake_pipe[0]);
        _ = c.close(self.wake_pipe[1]);
        _ = c.close(self.jvm_wake_pipe[0]);
        _ = c.close(self.jvm_wake_pipe[1]);

        // Cleanup memory
        self.write_queue.deinit(self.allocator);
        self.spsc_buffer.deinit();
        
        const alloc = self.allocator;
        alloc.destroy(self);
    }

    pub fn writeKeystrokes(self: *SshNativeSession, data: []const u8) void {
        self.write_lock.lock();
        self.write_queue.appendSlice(self.allocator, data) catch {};
        self.write_lock.unlock();

        const dummy: u8 = 1;
        _ = c.write(self.wake_pipe[1], &dummy, 1);
    }

    pub fn ackJvmWakeup(self: *SshNativeSession) void {
        var dummy: [128]u8 = undefined;
        _ = c.read(self.jvm_wake_pipe[0], &dummy, dummy.len);
    }

    pub fn resizeChannel(self: *SshNativeSession, cols: c_int, rows: c_int) void {
        self.write_lock.lock();
        self.pending_cols = cols;
        self.pending_rows = rows;
        self.write_lock.unlock();

        const dummy: u8 = 1;
        _ = c.write(self.wake_pipe[1], &dummy, 1);
    }

    fn processResize(self: *SshNativeSession) void {
        self.write_lock.lock();
        const cols = self.pending_cols;
        const rows = self.pending_rows;
        self.pending_cols = -1;
        self.pending_rows = -1;
        self.write_lock.unlock();

        if (cols != -1 and rows != -1) {
            while (self.running.load(.acquire)) {
                const rc = c.libssh2_channel_request_pty_size_ex(self.channel, cols, rows, 0, 0);
                if (rc == 0) {
                    break;
                } else if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    var fds = [_]c.struct_pollfd{
                        .{
                            .fd = self.socket_fd,
                            .events = c.POLLOUT,
                            .revents = 0,
                        },
                    };
                    _ = c.poll(&fds, 1, 10);
                } else {
                    break;
                }
            }
        }
    }

    fn waitSocket(socket_fd: c_int, session: *c.LIBSSH2_SESSION) !void {
        var fds = [_]c.struct_pollfd{
            .{
                .fd = socket_fd,
                .events = 0,
                .revents = 0,
            },
        };
        const directions = c.libssh2_session_block_directions(session);
        if ((directions & c.LIBSSH2_SESSION_BLOCK_INBOUND) != 0) {
            fds[0].events |= c.POLLIN;
        }
        if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) {
            fds[0].events |= c.POLLOUT;
        }
        _ = c.poll(&fds, 1, -1);
    }

    fn runLoop(self: *SshNativeSession) void {
        var fds = [_]c.struct_pollfd{
            .{
                .fd = self.socket_fd,
                .events = c.POLLIN,
                .revents = 0,
            },
            .{
                .fd = self.wake_pipe[0],
                .events = c.POLLIN,
                .revents = 0,
            },
        };

        while (self.running.load(.acquire)) {
            fds[0].events = c.POLLIN;
            const directions = c.libssh2_session_block_directions(self.session);
            if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) {
                fds[0].events |= c.POLLOUT;
            }

            const poll_res = c.poll(&fds, 2, -1);
            if (poll_res < 0) {
                continue;
            }

            if ((fds[1].revents & c.POLLIN) != 0) {
                var dummy: [128]u8 = undefined;
                _ = c.read(self.wake_pipe[0], &dummy, dummy.len);
                self.processResize();
                self.processWriteQueue();
            }

            if ((fds[0].revents & (c.POLLIN | c.POLLOUT | c.POLLERR | c.POLLHUP)) != 0) {
                var temp_buf: [16384]u8 = undefined;
                while (self.running.load(.acquire)) {
                    const read_bytes = c.libssh2_channel_read(self.channel, &temp_buf, temp_buf.len);
                    if (read_bytes > 0) {
                        const written = self.spsc_buffer.write(temp_buf[0..@intCast(read_bytes)]);
                        if (written > 0) {
                            self.signalJvmWorker();
                        }
                    } else if (read_bytes == c.LIBSSH2_ERROR_EAGAIN) {
                        break;
                    } else {
                        self.running.store(false, .release);
                        self.signalJvmWorker();
                        break;
                    }
                }

                self.processWriteQueue();
            }
        }
    }

    fn processWriteQueue(self: *SshNativeSession) void {
        self.write_lock.lock();
        if (self.write_queue.items.len == 0) {
            self.write_lock.unlock();
            return;
        }

        const copy = self.allocator.dupe(u8, self.write_queue.items) catch {
            self.write_lock.unlock();
            return;
        };
        self.write_queue.clearRetainingCapacity();
        self.write_lock.unlock();
        defer self.allocator.free(copy);

        var written: usize = 0;
        while (written < copy.len and self.running.load(.acquire)) {
            const res = c.libssh2_channel_write(self.channel, copy[written..].ptr, copy.len - written);
            if (res > 0) {
                written += @intCast(res);
            } else if (res == c.LIBSSH2_ERROR_EAGAIN) {
                var fds = [_]c.struct_pollfd{
                    .{
                        .fd = self.socket_fd,
                        .events = c.POLLOUT,
                        .revents = 0,
                    },
                };
                _ = c.poll(&fds, 1, 10);
            } else {
                break;
            }
        }
    }

    fn signalJvmWorker(self: *SshNativeSession) void {
        const dummy: u8 = 1;
        _ = c.write(self.jvm_wake_pipe[1], &dummy, 1);
    }
};
