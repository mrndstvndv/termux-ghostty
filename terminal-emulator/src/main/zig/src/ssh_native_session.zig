const std = @import("std");

const c = @cImport({
    @cInclude("jni.h");
    @cInclude("libssh2.h");
    @cInclude("libssh2_sftp.h");
    @cInclude("unistd.h");
    @cInclude("poll.h");
    @cInclude("fcntl.h");
    @cInclude("pthread.h");
});

pub const SshNativeSession = struct {
    // Use page_allocator for ArrayList buffers to bypass Android Scudo entirely.
    // Scudo's strict chunk-header validation is incompatible with the reallocation
    // patterns from Zig ArrayLists combined with libssh2's own malloc usage.
    const queue_allocator = std.heap.page_allocator;

    allocator: std.mem.Allocator,
    session: *c.LIBSSH2_SESSION,
    channel: *c.LIBSSH2_CHANNEL,
    socket_fd: c_int,
    wake_pipe: [2]c_int,
    output_lock: std.Thread.Mutex,
    output_queue: std.ArrayList(u8),
    command_lock: std.Thread.Mutex,
    command_queue: std.ArrayList(*Command),
    callback_lock: std.Thread.Mutex,
    java_vm: ?*c.JavaVM,
    output_callback: ?c.jobject,
    output_callback_method: ?c.jmethodID,
    output_closed_method: ?c.jmethodID,
    thread: ?std.Thread,
    running: std.atomic.Value(bool),
    const CommandKind = enum {
        write,
        resize,
        callback,
    };

    const Command = struct {
        kind: CommandKind,
        data: ?[]u8 = null,
        cols: c_int = 0,
        rows: c_int = 0,
        callback: ?*const fn (*SshNativeSession, *Command) void = null,
        context: ?*anyopaque = null,
        completion: ?*std.Thread.Semaphore = null,
        owned: bool = false,
    };

    const ExecContext = struct {
        command: []const u8,
        output: *std.ArrayList(u8),
        success: bool = false,
    };

    const SftpInitContext = struct {
        result: ?*anyopaque = null,
    };

    const SftpCloseContext = struct {
        handle: *anyopaque,
    };

    const SftpListContext = struct {
        handle: *anyopaque,
        path: []const u8,
        output: *std.ArrayList(u8),
        success: bool = false,
    };

    const SftpMkdirContext = struct {
        handle: *anyopaque,
        path: []const u8,
        permissions: c_int,
        success: bool = false,
    };

    const SftpDeleteContext = struct {
        handle: *anyopaque,
        path: []const u8,
        success: bool = false,
    };

    const SftpOpenContext = struct {
        sftp: *anyopaque,
        path: []const u8,
        flags: c_int,
        mode: c_int,
        result: ?*anyopaque = null,
    };

    const SftpFileCloseContext = struct {
        handle: *anyopaque,
    };

    const SftpReadContext = struct {
        handle: *anyopaque,
        buffer: []u8,
        result: isize = -1,
    };

    const SftpWriteContext = struct {
        handle: *anyopaque,
        buffer: []const u8,
        result: isize = -1,
    };

    pub fn init(
        allocator: std.mem.Allocator,
        socket_fd: c_int,
        username: []const u8,
        password_or_key: []const u8,
        is_password: bool,
        term_type: []const u8,
        cols: c_int,
        rows: c_int,
        herdr_integration: bool,
        java_vm: ?*c.JavaVM,
    ) !*SshNativeSession {
        _ = c.libssh2_init(0);

        const session = c.libssh2_session_init_ex(null, null, null, null) orelse return error.SessionInitFailed;
        errdefer _ = c.libssh2_session_free(session);

        _ = c.libssh2_session_set_blocking(session, 0);
        _ = c.libssh2_session_flag(session, c.LIBSSH2_FLAG_COMPRESS, 1);

        const flags = c.fcntl(socket_fd, c.F_GETFL, @as(c_int, 0));
        _ = c.fcntl(socket_fd, c.F_SETFL, flags | c.O_NONBLOCK);

        while (true) {
            const rc = c.libssh2_session_handshake(session, socket_fd);
            if (rc == 0) break;
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.HandshakeFailed;
        }

        if (is_password) {
            const password_c = try allocator.dupeZ(u8, password_or_key);
            defer allocator.free(password_c);
            while (true) {
                const rc = c.libssh2_userauth_password_ex(
                    session,
                    username.ptr,
                    @intCast(username.len),
                    password_c.ptr,
                    @intCast(password_or_key.len),
                    null,
                );
                if (rc == 0) break;
                if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                    try waitSocket(socket_fd, session);
                    continue;
                }
                return error.AuthFailed;
            }
        } else {
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

        const channel = while (true) {
            const ch = c.libssh2_channel_open_ex(
                session,
                "session",
                @intCast("session".len),
                c.LIBSSH2_CHANNEL_WINDOW_DEFAULT,
                c.LIBSSH2_CHANNEL_PACKET_DEFAULT,
                null,
                0,
            );
            if (ch) |value| break value;
            const rc = c.libssh2_session_last_error(session, null, null, 0);
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.ChannelOpenFailed;
        };
        errdefer _ = c.libssh2_channel_free(channel);

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

        for ([_][]const u8{ "LC_ALL", "LANG", "LC_CTYPE" }) |env_name| {
            var attempt: i32 = 0;
            while (attempt < 50) : (attempt += 1) {
                const rc = c.libssh2_channel_setenv_ex(channel, env_name.ptr, @intCast(env_name.len), "en_US.UTF-8", @intCast("en_US.UTF-8".len));
                if (rc == 0) break;
                if (rc != c.LIBSSH2_ERROR_EAGAIN) break;
                try waitSocket(socket_fd, session);
            }
        }

        while (true) {
            const rc = if (herdr_integration) blk: {
                const cmd = "sh -c \"if command -v herdr >/dev/null 2>&1; then exec herdr; else exec \\${SHELL:-/bin/sh} -l; fi\"";
                break :blk c.libssh2_channel_process_startup(channel, "exec", 4, cmd.ptr, @intCast(cmd.len));
            } else c.libssh2_channel_process_startup(channel, "shell", @intCast("shell".len), null, 0);
            if (rc == 0) break;
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                try waitSocket(socket_fd, session);
                continue;
            }
            return error.ShellStartFailed;
        }

        var wake_p: [2]c_int = undefined;
        if (c.pipe(&wake_p) != 0) return error.PipeFailed;
        errdefer {
            _ = c.close(wake_p[0]);
            _ = c.close(wake_p[1]);
        }

        _ = c.fcntl(wake_p[0], c.F_SETFL, c.O_NONBLOCK);

        const self = try allocator.create(SshNativeSession);
        self.* = .{
            .allocator = allocator,
            .session = session,
            .channel = channel,
            .socket_fd = socket_fd,
            .wake_pipe = wake_p,
            .output_lock = .{},
            .output_queue = .empty,
            .command_lock = .{},
            .command_queue = .empty,
            .callback_lock = .{},
            .java_vm = java_vm,
            .output_callback = null,
            .output_callback_method = null,
            .output_closed_method = null,
            .thread = null,
            .running = std.atomic.Value(bool).init(true),
        };
        return self;
    }

    pub fn start(self: *SshNativeSession) !void {
        self.thread = try std.Thread.spawn(.{ .allocator = self.allocator }, runLoop, .{self});
    }

    pub fn deinit(self: *SshNativeSession, env: ?*c.JNIEnv) void {
        self.running.store(false, .release);
        self.signalCommandWake();
        if (self.thread) |thread| {
            const thread_id: c.pthread_t = @intCast(@intFromPtr(thread.getHandle()));
            if (c.pthread_equal(thread_id, c.pthread_self()) == 0) {
                thread.join();
            }
        } else {
            _ = c.libssh2_channel_free(self.channel);
            _ = c.libssh2_session_disconnect_ex(self.session, c.SSH_DISCONNECT_BY_APPLICATION, "Shutdown", "");
            _ = c.libssh2_session_free(self.session);
        }

        _ = c.close(self.wake_pipe[0]);
        _ = c.close(self.wake_pipe[1]);
        self.callback_lock.lock();
        self.clearOutputCallbackLocked(env);
        self.callback_lock.unlock();
        self.output_queue.deinit(queue_allocator);
        self.command_queue.deinit(queue_allocator);

        const allocator = self.allocator;
        allocator.destroy(self);
    }

    pub fn writeKeystrokes(self: *SshNativeSession, data: []const u8) void {
        const copy = self.allocator.dupe(u8, data) catch return;
        const command = self.allocator.create(Command) catch {
            self.allocator.free(copy);
            return;
        };
        command.* = .{ .kind = .write, .data = copy, .owned = true };
        if (!self.enqueue(command)) {
            self.allocator.free(copy);
            self.allocator.destroy(command);
        }
    }

    pub fn resizeChannel(self: *SshNativeSession, cols: c_int, rows: c_int) void {
        const command = self.allocator.create(Command) catch return;
        command.* = .{ .kind = .resize, .cols = cols, .rows = rows, .owned = true };
        if (!self.enqueue(command)) self.allocator.destroy(command);
    }

    pub fn setOutputCallback(self: *SshNativeSession, env: *c.JNIEnv, callback: c.jobject) void {
        self.callback_lock.lock();
        defer self.callback_lock.unlock();
        self.clearOutputCallbackLocked(env);
        if (callback == null) return;

        var java_vm: ?*c.JavaVM = null;
        if (env.*.*.GetJavaVM.?(env, &java_vm) != 0) return;
        const global_callback = env.*.*.NewGlobalRef.?(env, callback) orelse return;
        const callback_class = env.*.*.GetObjectClass.?(env, callback) orelse {
            env.*.*.DeleteGlobalRef.?(env, global_callback);
            return;
        };
        const callback_method = env.*.*.GetMethodID.?(env, callback_class, "onNativeSshOutput", "([B)V") orelse {
            env.*.*.DeleteLocalRef.?(env, callback_class);
            env.*.*.DeleteGlobalRef.?(env, global_callback);
            return;
        };
        const closed_method = env.*.*.GetMethodID.?(env, callback_class, "onNativeSshClosed", "()V") orelse {
            env.*.*.DeleteLocalRef.?(env, callback_class);
            env.*.*.DeleteGlobalRef.?(env, global_callback);
            return;
        };
        env.*.*.DeleteLocalRef.?(env, callback_class);
        self.java_vm = java_vm;
        self.output_callback = global_callback;
        self.output_callback_method = callback_method;
        self.output_closed_method = closed_method;
        self.deliverOutputLocked(env);
    }

    pub fn drainOutput(self: *SshNativeSession, context: ?*anyopaque, append_fn: *const fn (?*anyopaque, ?[*]const u8, usize) callconv(.c) u32) u32 {
        self.output_lock.lock();
        defer self.output_lock.unlock();
        if (self.output_queue.items.len == 0) return 0;

        const result = append_fn(context, self.output_queue.items.ptr, self.output_queue.items.len);
        self.output_queue.clearRetainingCapacity();
        return result;
    }

    pub fn execCommand(self: *SshNativeSession, command: []const u8, output: *std.ArrayList(u8)) !void {
        var context = ExecContext{ .command = command, .output = output };
        if (!self.dispatchSync(execCommandCallback, &context) or !context.success) return error.CommandExecFailed;
    }

    pub fn sftpInit(self: *SshNativeSession) ?*anyopaque {
        var context = SftpInitContext{};
        if (!self.dispatchSync(sftpInitCallback, &context)) return null;
        return context.result;
    }

    pub fn sftpClose(self: *SshNativeSession, handle: *anyopaque) void {
        var context = SftpCloseContext{ .handle = handle };
        _ = self.dispatchSync(sftpCloseCallback, &context);
    }

    pub fn sftpListFiles(self: *SshNativeSession, handle: *anyopaque, path: []const u8, output: *std.ArrayList(u8)) bool {
        var context = SftpListContext{ .handle = handle, .path = path, .output = output };
        return self.dispatchSync(sftpListCallback, &context) and context.success;
    }

    pub fn sftpMkdir(self: *SshNativeSession, handle: *anyopaque, path: []const u8, permissions: c_int) bool {
        var context = SftpMkdirContext{ .handle = handle, .path = path, .permissions = permissions };
        return self.dispatchSync(sftpMkdirCallback, &context) and context.success;
    }

    pub fn sftpDelete(self: *SshNativeSession, handle: *anyopaque, path: []const u8) bool {
        var context = SftpDeleteContext{ .handle = handle, .path = path };
        return self.dispatchSync(sftpDeleteCallback, &context) and context.success;
    }

    pub fn sftpFileOpen(self: *SshNativeSession, handle: *anyopaque, path: []const u8, flags: c_int, mode: c_int) ?*anyopaque {
        var context = SftpOpenContext{ .sftp = handle, .path = path, .flags = flags, .mode = mode };
        if (!self.dispatchSync(sftpOpenCallback, &context)) return null;
        return context.result;
    }

    pub fn sftpFileClose(self: *SshNativeSession, handle: *anyopaque) void {
        var context = SftpFileCloseContext{ .handle = handle };
        _ = self.dispatchSync(sftpFileCloseCallback, &context);
    }

    pub fn sftpFileRead(self: *SshNativeSession, handle: *anyopaque, buffer: []u8) isize {
        var context = SftpReadContext{ .handle = handle, .buffer = buffer };
        if (!self.dispatchSync(sftpReadCallback, &context)) return -1;
        return context.result;
    }

    pub fn sftpFileWrite(self: *SshNativeSession, handle: *anyopaque, buffer: []const u8) isize {
        var context = SftpWriteContext{ .handle = handle, .buffer = buffer };
        if (!self.dispatchSync(sftpWriteCallback, &context)) return -1;
        return context.result;
    }

    pub fn waitSocket(socket_fd: c_int, session: *c.LIBSSH2_SESSION) !void {
        var fds = [_]c.struct_pollfd{.{ .fd = socket_fd, .events = 0, .revents = 0 }};
        const directions = c.libssh2_session_block_directions(session);
        if ((directions & c.LIBSSH2_SESSION_BLOCK_INBOUND) != 0) fds[0].events |= c.POLLIN;
        if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) fds[0].events |= c.POLLOUT;
        _ = c.poll(&fds, 1, 50);
    }

    fn runLoop(self: *SshNativeSession) void {
        var jni_env: ?*c.JNIEnv = null;
        var attached = false;

        self.callback_lock.lock();
        const vm_opt = self.java_vm;
        self.callback_lock.unlock();

        if (vm_opt) |vm| {
            if (vm.*.*.AttachCurrentThreadAsDaemon.?(vm, @ptrCast(&jni_env), null) == 0) {
                attached = true;
            }
        }
        defer {
            if (attached) {
                if (self.java_vm) |vm| {
                    _ = vm.*.*.DetachCurrentThread.?(vm);
                }
            }
        }

        var fds = [_]c.struct_pollfd{
            .{ .fd = self.socket_fd, .events = c.POLLIN, .revents = 0 },
            .{ .fd = self.wake_pipe[0], .events = c.POLLIN, .revents = 0 },
        };

        while (self.running.load(.acquire)) {
            fds[0].events = c.POLLIN;
            const directions = c.libssh2_session_block_directions(self.session);
            if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) fds[0].events |= c.POLLOUT;

            const poll_result = c.poll(&fds, 2, -1);
            if (poll_result < 0) continue;

            if ((fds[1].revents & c.POLLIN) != 0) {
                var dummy: [128]u8 = undefined;
                _ = c.read(self.wake_pipe[0], &dummy, dummy.len);
                self.processCommands();
            }

            if ((fds[0].revents & (c.POLLIN | c.POLLOUT | c.POLLERR | c.POLLHUP)) != 0) {
                self.processRead(jni_env);
                self.processCommands();
            }
        }

        self.cancelQueuedCommands();
        _ = c.libssh2_channel_free(self.channel);
        _ = c.libssh2_session_disconnect_ex(self.session, c.SSH_DISCONNECT_BY_APPLICATION, "Shutdown", "");
        _ = c.libssh2_session_free(self.session);
        self.deliverClosed(jni_env);
    }

    fn processRead(self: *SshNativeSession, env: ?*c.JNIEnv) void {
        var buffer: [16384]u8 = undefined;
        while (self.running.load(.acquire)) {
            const count = c.libssh2_channel_read(self.channel, &buffer, buffer.len);
            if (count > 0) {
                self.output_lock.lock();
                self.output_queue.appendSlice(queue_allocator, buffer[0..@intCast(count)]) catch {};
                self.output_lock.unlock();
                self.deliverOutput(env);
            } else if (count == c.LIBSSH2_ERROR_EAGAIN) {
                break;
            } else {
                self.running.store(false, .release);
                break;
            }
        }
    }

    fn processCommands(self: *SshNativeSession) void {
        while (true) {
            self.command_lock.lock();
            const command = if (self.command_queue.items.len == 0) null else self.command_queue.orderedRemove(0);
            self.command_lock.unlock();
            const current = command orelse break;

            const owned = current.owned;
            const data_to_free = current.data;
            const completion = current.completion;

            switch (current.kind) {
                .write => self.processWrite(data_to_free orelse &[_]u8{}),
                .resize => self.processResize(current.cols, current.rows),
                .callback => if (current.callback) |callback| callback(self, current),
            }

            if (owned) {
                if (data_to_free) |data| self.allocator.free(data);
                self.allocator.destroy(current);
            }

            if (completion) |comp| comp.post();
        }
    }

    fn processWrite(self: *SshNativeSession, data: []const u8) void {
        var written: usize = 0;
        while (written < data.len and self.running.load(.acquire)) {
            const result = c.libssh2_channel_write(self.channel, data[written..].ptr, data.len - written);
            if (result > 0) {
                written += @intCast(result);
            } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch break;
            } else {
                break;
            }
        }
    }

    fn processResize(self: *SshNativeSession, cols: c_int, rows: c_int) void {
        while (self.running.load(.acquire)) {
            const result = c.libssh2_channel_request_pty_size_ex(self.channel, cols, rows, 0, 0);
            if (result == 0) return;
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn enqueue(self: *SshNativeSession, command: *Command) bool {
        self.command_lock.lock();
        defer self.command_lock.unlock();
        if (!self.running.load(.acquire)) return false;
        self.command_queue.append(queue_allocator, command) catch return false;
        self.signalCommandWake();
        return true;
    }

    fn dispatchSync(self: *SshNativeSession, callback: *const fn (*SshNativeSession, *Command) void, context: *anyopaque) bool {
        var completion: std.Thread.Semaphore = .{};
        var command = Command{ .kind = .callback, .callback = callback, .context = context, .completion = &completion };
        if (!self.enqueue(&command)) return false;
        completion.wait();
        return true;
    }

    fn cancelQueuedCommands(self: *SshNativeSession) void {
        while (true) {
            self.command_lock.lock();
            const command = if (self.command_queue.items.len == 0) null else self.command_queue.orderedRemove(0);
            self.command_lock.unlock();
            const current = command orelse break;

            const owned = current.owned;
            const data_to_free = current.data;
            const completion = current.completion;

            if (owned) {
                if (data_to_free) |data| self.allocator.free(data);
                self.allocator.destroy(current);
            }

            if (completion) |comp| comp.post();
        }
    }

    fn signalCommandWake(self: *SshNativeSession) void {
        const dummy: u8 = 1;
        _ = c.write(self.wake_pipe[1], &dummy, 1);
    }

    fn deliverOutput(self: *SshNativeSession, env: ?*c.JNIEnv) void {
        self.callback_lock.lock();
        defer self.callback_lock.unlock();
        self.deliverOutputLocked(env);
    }

    fn deliverOutputLocked(self: *SshNativeSession, current_env: ?*c.JNIEnv) void {
        const java_vm = self.java_vm orelse return;
        const callback = self.output_callback orelse return;
        const method = self.output_callback_method orelse return;
        var env: ?*c.JNIEnv = current_env;
        var attached = false;
        if (env == null) {
            if (java_vm.*.*.AttachCurrentThreadAsDaemon.?(
                java_vm,
                @ptrCast(&env),
                null,
            ) != 0) return;
            attached = true;
        }
        defer {
            if (attached) _ = java_vm.*.*.DetachCurrentThread.?(java_vm);
        }

        self.output_lock.lock();
        defer self.output_lock.unlock();
        if (self.output_queue.items.len == 0) return;
        const bytes = env.?.*.*.NewByteArray.?(env.?, @intCast(self.output_queue.items.len)) orelse return;
        defer env.?.*.*.DeleteLocalRef.?(env.?, bytes);
        env.?.*.*.SetByteArrayRegion.?(env.?, bytes, 0, @intCast(self.output_queue.items.len), @ptrCast(self.output_queue.items.ptr));
        env.?.*.*.CallVoidMethod.?(env.?, callback, method, bytes);
        self.output_queue.clearRetainingCapacity();
    }

    fn deliverClosed(self: *SshNativeSession, current_env: ?*c.JNIEnv) void {
        self.callback_lock.lock();
        defer self.callback_lock.unlock();
        const java_vm = self.java_vm orelse return;
        const callback = self.output_callback orelse return;
        const method = self.output_closed_method orelse return;
        var env: ?*c.JNIEnv = current_env;
        var attached = false;
        if (env == null) {
            if (java_vm.*.*.AttachCurrentThreadAsDaemon.?(
                java_vm,
                @ptrCast(&env),
                null,
            ) != 0) return;
            attached = true;
        }
        defer {
            if (attached) _ = java_vm.*.*.DetachCurrentThread.?(java_vm);
        }
        env.?.*.*.CallVoidMethod.?(env.?, callback, method);
    }

    fn clearOutputCallbackLocked(self: *SshNativeSession, env: ?*c.JNIEnv) void {
        if (self.output_callback) |callback| {
            if (env) |jni| jni.*.*.DeleteGlobalRef.?(jni, callback);
        }
        self.java_vm = null;
        self.output_callback = null;
        self.output_callback_method = null;
        self.output_closed_method = null;
    }

    fn execCommandCallback(self: *SshNativeSession, command: *Command) void {
        const context: *ExecContext = @ptrCast(@alignCast(command.context.?));
        self.execCommandOwned(context.command, context.output) catch return;
        context.success = true;
    }

    fn execCommandOwned(self: *SshNativeSession, command: []const u8, output: *std.ArrayList(u8)) !void {
        const cmd_c = try self.allocator.dupeZ(u8, command);
        defer self.allocator.free(cmd_c);

        const exec_channel = while (true) {
            const channel = c.libssh2_channel_open_ex(self.session, "session", @intCast("session".len), c.LIBSSH2_CHANNEL_WINDOW_DEFAULT, c.LIBSSH2_CHANNEL_PACKET_DEFAULT, null, 0);
            if (channel) |value| break value;
            const err = c.libssh2_session_last_error(self.session, null, null, 0);
            if (err == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch return error.ChannelOpenFailed;
                continue;
            }
            return error.ChannelOpenFailed;
        };
        defer _ = c.libssh2_channel_free(exec_channel);

        while (true) {
            const result = c.libssh2_channel_process_startup(exec_channel, "exec", 4, cmd_c.ptr, @intCast(command.len));
            if (result == 0) break;
            if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch return error.CommandExecFailed;
                continue;
            }
            return error.CommandExecFailed;
        }

        var buffer: [4096]u8 = undefined;
        while (true) {
            const result = c.libssh2_channel_read(exec_channel, &buffer, buffer.len);
            if (result > 0) {
                try output.appendSlice(self.allocator, buffer[0..@intCast(result)]);
            } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch return error.ReadFailed;
            } else if (result == 0 or result == c.LIBSSH2_ERROR_CHANNEL_CLOSED) {
                break;
            } else {
                return error.ReadFailed;
            }
        }
    }

    fn sftpInitCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpInitContext = @ptrCast(@alignCast(command.context.?));
        while (self.running.load(.acquire)) {
            const handle = c.libssh2_sftp_init(self.session);
            if (handle) |value| {
                context.result = @ptrCast(value);
                return;
            }
            if (c.libssh2_session_last_error(self.session, null, null, 0) != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpCloseCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpCloseContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_shutdown(handle);
            if (result == 0 or result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn appendJsonEscaped(output: *std.ArrayList(u8), allocator: std.mem.Allocator, str: []const u8) !void {
        const writer = output.writer(allocator);
        for (str) |ch| {
            switch (ch) {
                '"' => try writer.writeAll("\\\""),
                '\\' => try writer.writeAll("\\\\"),
                '\n' => try writer.writeAll("\\n"),
                '\r' => try writer.writeAll("\\r"),
                '\t' => try writer.writeAll("\\t"),
                else => {
                    if (ch < 0x20) {
                        try writer.print("\\u00{x:0>2}", .{ch});
                    } else {
                        try writer.writeByte(ch);
                    }
                },
            }
        }
    }

    fn sftpListCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpListContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        const dir = while (self.running.load(.acquire)) {
            const handle = c.libssh2_sftp_opendir(sftp, context.path.ptr);
            if (handle) |value| break value;
            if (c.libssh2_session_last_error(self.session, null, null, 0) != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        } else return;
        defer _ = c.libssh2_sftp_closedir(dir);

        context.output.appendSlice(self.allocator, "[") catch return;
        var filename: [1024]u8 = undefined;
        var longentry: [1024]u8 = undefined;
        var attributes: c.LIBSSH2_SFTP_ATTRIBUTES = undefined;
        var first = true;
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_readdir_ex(dir, &filename, filename.len, &longentry, longentry.len, &attributes);
            if (result > 0) {
                const name = filename[0..@intCast(result)];
                if (std.mem.eql(u8, name, ".") or std.mem.eql(u8, name, "..")) continue;
                const is_dir = (attributes.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) != 0 and (attributes.permissions & 0xF000) == 0x4000;
                if (!first) context.output.appendSlice(self.allocator, ",") catch return;
                first = false;

                const writer = context.output.writer(self.allocator);
                writer.writeAll("{\"name\":\"") catch return;
                appendJsonEscaped(context.output, self.allocator, name) catch return;
                writer.writeAll("\",\"path\":\"") catch return;
                appendJsonEscaped(context.output, self.allocator, context.path) catch return;
                if (!std.mem.endsWith(u8, context.path, "/")) {
                    writer.writeAll("/") catch return;
                }
                appendJsonEscaped(context.output, self.allocator, name) catch return;
                writer.print("\",\"isDir\":{s},\"size\":{},\"permissions\":{},\"mtime\":{}}}", .{
                    if (is_dir) "true" else "false",
                    if (is_dir or (attributes.flags & c.LIBSSH2_SFTP_ATTR_SIZE) == 0) 0 else attributes.filesize,
                    if ((attributes.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) == 0) 0 else attributes.permissions,
                    if ((attributes.flags & c.LIBSSH2_SFTP_ATTR_ACMODTIME) == 0) 0 else attributes.mtime * 1000,
                }) catch return;
            } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch return;
            } else if (result == 0) {
                context.output.appendSlice(self.allocator, "]") catch return;
                context.success = true;
                return;
            } else return;
        }
    }

    fn sftpMkdirCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpMkdirContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_mkdir_ex(sftp, context.path.ptr, @intCast(context.path.len), @intCast(context.permissions));
            if (result == 0) context.success = true;
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpDeleteCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpDeleteContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        for (0..2) |attempt| {
            while (self.running.load(.acquire)) {
                const result = if (attempt == 0)
                    c.libssh2_sftp_unlink_ex(sftp, context.path.ptr, @intCast(context.path.len))
                else
                    c.libssh2_sftp_rmdir_ex(sftp, context.path.ptr, @intCast(context.path.len));
                if (result == 0) {
                    context.success = true;
                    return;
                }
                if (result != c.LIBSSH2_ERROR_EAGAIN) break;
                waitSocket(self.socket_fd, self.session) catch return;
            }
        }
    }

    fn sftpOpenCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpOpenContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.sftp));
        while (self.running.load(.acquire)) {
            const handle = c.libssh2_sftp_open_ex(sftp, context.path.ptr, @intCast(context.path.len), @intCast(context.flags), @intCast(context.mode), c.LIBSSH2_SFTP_OPENFILE);
            if (handle) |value| {
                context.result = @ptrCast(value);
                return;
            }
            if (c.libssh2_session_last_error(self.session, null, null, 0) != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpFileCloseCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpFileCloseContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_close_handle(handle);
            if (result == 0 or result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpReadCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpReadContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_read(handle, context.buffer.ptr, context.buffer.len);
            if (result >= 0) {
                context.result = @intCast(result);
                return;
            }
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    fn sftpWriteCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpWriteContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_write(handle, context.buffer.ptr, context.buffer.len);
            if (result >= 0) {
                context.result = @intCast(result);
                return;
            }
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }
};
