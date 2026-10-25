const std = @import("std");
const ssh_output_buffer = @import("ssh_output_buffer.zig");
const ssh_stream_buffer = @import("ssh_stream_buffer.zig");
const ssh_open_queue = @import("ssh_open_queue.zig");
const ssh_shell_exit = @import("ssh_shell_exit.zig");

pub const c = @import("ssh_c");

pub const SshNativeSession = struct {
    // zig 0.16 removed std.Thread.Mutex in favor of std.Io.Mutex, which
    // requires an Io handle for every lock/unlock. The session threads don't
    // have one, so wrap the pthread mutex we already link instead.
    const Mutex = struct {
        // std.c's default is the platform's PTHREAD_MUTEX_INITIALIZER. A zeroed mutex is valid on
        // Android (bionic) but not on Darwin, where lock would silently stop excluding threads.
        // Return values are ignored, so a mis-initialized mutex fails without any signal.
        inner: std.c.pthread_mutex_t = .{},

        pub fn lock(self: *Mutex) void {
            _ = std.c.pthread_mutex_lock(&self.inner);
        }

        pub fn unlock(self: *Mutex) void {
            _ = std.c.pthread_mutex_unlock(&self.inner);
        }
    };

    // Same rationale as Mutex: std.Thread.Semaphore moved to std.Io.Semaphore
    // (needs an Io) in zig 0.16, so wrap a pthread semaphore instead.
    const Semaphore = struct {
        inner: c.sem_t = std.mem.zeroes(c.sem_t),
        initialized: bool = false,

        pub fn init(self: *Semaphore) void {
            _ = c.sem_init(&self.inner, 0, 0);
            self.initialized = true;
        }

        pub fn deinit(self: *Semaphore) void {
            if (self.initialized) _ = c.sem_destroy(&self.inner);
            self.initialized = false;
        }

        pub fn wait(self: *Semaphore) void {
            while (true) {
                if (c.sem_wait(&self.inner) == 0) return;
                if (std.c.errno(-1) != .INTR) return;
            }
        }

        pub fn post(self: *Semaphore) void {
            _ = c.sem_post(&self.inner);
        }
    };

    // Waiters on a stream (JVM threads) sleep here; the loop thread broadcasts.
    const Cond = struct {
        inner: c.pthread_cond_t = std.mem.zeroes(c.pthread_cond_t),

        pub fn init(self: *Cond) void {
            _ = c.pthread_cond_init(&self.inner, null);
        }

        pub fn deinit(self: *Cond) void {
            _ = c.pthread_cond_destroy(&self.inner);
        }

        /// Sleeps until a broadcast or `timeout_ms`, whichever comes first. The caller
        /// holds `mutex` and must re-check its condition, since wakeups can be spurious.
        pub fn timedWait(self: *Cond, mutex: *Mutex, timeout_ms: c_int) void {
            var deadline: c.struct_timespec = undefined;
            _ = c.clock_gettime(c.CLOCK_REALTIME, &deadline);
            deadline.tv_sec += @as(@TypeOf(deadline.tv_sec), @divTrunc(timeout_ms, 1000));
            deadline.tv_nsec += @as(@TypeOf(deadline.tv_nsec), @rem(timeout_ms, 1000)) * 1_000_000;
            if (deadline.tv_nsec >= 1_000_000_000) {
                deadline.tv_sec += 1;
                deadline.tv_nsec -= 1_000_000_000;
            }
            _ = c.pthread_cond_timedwait(&self.inner, @ptrCast(&mutex.inner), &deadline);
        }

        pub fn broadcast(self: *Cond) void {
            _ = c.pthread_cond_broadcast(&self.inner);
        }
    };

    // Use page_allocator for ArrayList buffers to bypass Android Scudo entirely.
    // Scudo's strict chunk-header validation is incompatible with the reallocation
    // patterns from Zig ArrayLists combined with libssh2's own malloc usage.
    const queue_allocator = std.heap.page_allocator;
    const socket_poll_timeout_ms: c_int = 1000;
    const keepalive_interval_seconds: c_uint = 10;
    // Deliver shell output to the JVM in ~64 KiB batches instead of one JNI
    // callback + Handler message per libssh2 read chunk (16 KiB).
    const output_flush_threshold_bytes: usize = 64 * 1024;
    // Each direction of a stream is a bounded ring; a full ring pauses the
    // producer (reads stop, so SSH flow control closes the window) instead of
    // growing. Bytes are never dropped.
    const stream_buffer_capacity: usize = 64 * 1024;
    // Chunk size for the JNI copy, which runs on the caller's stack.
    pub const stream_jni_chunk_bytes: usize = 16 * 1024;
    // Admission limits. A refused start is reported to the JVM, which can retry;
    // nothing is queued without bound.
    const max_queued_opens: usize = 64;
    const max_live_ops: usize = 64;
    const max_live_streams: usize = 64;
    // A channel open holding the slot for longer than this ends the session. libssh2 keeps
    // the in-flight open's state on the session and cannot abandon it, so this bound is only
    // strict if it closes the session rather than re-arming the slot.
    const native_open_deadline_ns: u64 = 30_000_000_000;
    const open_deadline_reason = "A channel open did not finish within 30 s, so the SSH session was closed";

    pub const StartError = error{ NotRunning, TooManyPending, InvalidPath, OutOfMemory };

    /// One interactive PTY channel on the session. The JVM owns its lifetime:
    /// the struct stays valid until `closeShell` or session `deinit`, even after
    /// the remote shell exits, so a stale handle never dangles.
    pub const ShellChannel = struct {
        owner: *SshNativeSession,
        // Only touched on the loop thread; null once the remote side closed.
        channel: ?*c.LIBSSH2_CHANNEL,
        open: std.atomic.Value(bool),
        output_lock: Mutex = .{},
        output_queue: std.ArrayList(u8) = .empty,
        callback_lock: Mutex = .{},
        java_vm: ?*c.JavaVM = null,
        output_callback: ?c.jobject = null,
        output_callback_method: ?c.jmethodID = null,
        output_closed_method: ?c.jmethodID = null,
        // How the remote shell ended. Written once, on the loop thread, before the close callback,
        // and read by the JVM under `exit_lock`. The JVM never reads it through `callback_lock`,
        // which the close callback holds while it runs.
        exit_lock: Mutex = .{},
        exit: ssh_shell_exit.Exit = .{},

        pub fn isRunning(self: *ShellChannel) bool {
            return self.open.load(.acquire) and self.owner.running.load(.acquire);
        }

        pub fn writeKeystrokes(self: *ShellChannel, data: []const u8) void {
            const owner = self.owner;
            const copy = owner.allocator.dupe(u8, data) catch return;
            const command = owner.allocator.create(Command) catch {
                owner.allocator.free(copy);
                return;
            };
            command.* = .{ .kind = .write, .shell = self, .data = copy, .owned = true };
            if (!owner.enqueue(command)) {
                owner.allocator.free(copy);
                owner.allocator.destroy(command);
            }
        }

        pub fn resize(self: *ShellChannel, cols: c_int, rows: c_int) void {
            const owner = self.owner;
            const command = owner.allocator.create(Command) catch return;
            command.* = .{ .kind = .resize, .shell = self, .cols = cols, .rows = rows, .owned = true };
            if (!owner.enqueue(command)) owner.allocator.destroy(command);
        }

        pub fn setOutputCallback(self: *ShellChannel, env: *c.JNIEnv, callback: c.jobject) void {
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

        fn hasCallback(self: *ShellChannel) bool {
            self.callback_lock.lock();
            defer self.callback_lock.unlock();
            return self.output_callback != null;
        }

        fn deliverOutput(self: *ShellChannel, env: ?*c.JNIEnv) void {
            self.callback_lock.lock();
            defer self.callback_lock.unlock();
            self.deliverOutputLocked(env);
        }

        fn deliverOutputLocked(self: *ShellChannel, current_env: ?*c.JNIEnv) void {
            const callback = self.output_callback orelse return;
            const method = self.output_callback_method orelse return;
            var attachment = JniAttachment.acquire(self.java_vm, current_env) orelse return;
            defer attachment.release();
            const env = attachment.env;

            self.output_lock.lock();
            defer self.output_lock.unlock();
            if (self.output_queue.items.len == 0) return;
            const bytes = env.*.*.NewByteArray.?(env, @intCast(self.output_queue.items.len)) orelse return;
            defer env.*.*.DeleteLocalRef.?(env, bytes);
            env.*.*.SetByteArrayRegion.?(env, bytes, 0, @intCast(self.output_queue.items.len), @ptrCast(self.output_queue.items.ptr));
            env.*.*.CallVoidMethod.?(env, callback, method, bytes);
            self.output_queue.clearRetainingCapacity();
        }

        fn deliverClosed(self: *ShellChannel, current_env: ?*c.JNIEnv) void {
            self.callback_lock.lock();
            defer self.callback_lock.unlock();
            const callback = self.output_callback orelse return;
            const method = self.output_closed_method orelse return;
            var attachment = JniAttachment.acquire(self.java_vm, current_env) orelse return;
            defer attachment.release();
            attachment.env.*.*.CallVoidMethod.?(attachment.env, callback, method);
        }

        fn clearOutputCallbackLocked(self: *ShellChannel, env: ?*c.JNIEnv) void {
            if (self.output_callback) |callback| {
                if (env) |jni| jni.*.*.DeleteGlobalRef.?(jni, callback);
            }
            self.java_vm = null;
            self.output_callback = null;
            self.output_callback_method = null;
            self.output_closed_method = null;
        }

        fn destroy(self: *ShellChannel, env: ?*c.JNIEnv) void {
            self.callback_lock.lock();
            self.clearOutputCallbackLocked(env);
            self.callback_lock.unlock();
            self.output_queue.deinit(queue_allocator);
            self.owner.allocator.destroy(self);
        }
    };

    /// One direct Unix-socket channel, used as a bounded byte pipe. The libssh2
    /// channel is touched only by the loop thread. JVM callers never call libssh2:
    /// they move bytes through the rings under `mutex` and sleep on `changed`.
    /// Lifetime: the loop retires the stream once it is closed (unlinks it and frees
    /// the libssh2 channel); the JVM releases it after closing. Whichever happens
    /// second frees the memory, so neither side can touch freed state.
    pub const StreamChannel = struct {
        pub const Phase = enum { pending, opening, open, failed };
        pub const Failure = enum(c_int) {
            none = 0,
            denied = 1,
            unreachable_path = 2,
            disconnected = 3,
            other = 4,
        };
        pub const detail_capacity: usize = 128;

        owner: *SshNativeSession,
        allocator: std.mem.Allocator,
        path: [:0]u8,
        mutex: Mutex = .{},
        changed: Cond = .{},
        // Guarded by `mutex`.
        phase: Phase = .pending,
        failure: Failure = .none,
        detail: [detail_capacity]u8 = undefined,
        detail_len: usize = 0,
        inbound: ssh_stream_buffer.Ring,
        outbound: ssh_stream_buffer.Ring,
        // Byte counters for `flush`: pushed into the ring vs. written through libssh2.
        outbound_pushed: usize = 0,
        outbound_written: usize = 0,
        remote_eof: bool = false,
        close_requested: bool = false,
        // Set when the loop stopped reading because `inbound` was full; a reader
        // that frees space must wake the loop to resume.
        inbound_throttled: bool = false,
        retired: bool = false,
        released: bool = false,
        // Loop thread only. Non-null from the moment the open succeeds until retire.
        channel: ?*c.LIBSSH2_CHANNEL = null,

        fn create(owner: *SshNativeSession, socket_path: []const u8) !*StreamChannel {
            const allocator = owner.allocator;
            const path = try allocator.dupeSentinel(u8, socket_path, 0);
            errdefer allocator.free(path);
            var inbound = try ssh_stream_buffer.Ring.init(queue_allocator, stream_buffer_capacity);
            errdefer inbound.deinit(queue_allocator);
            var outbound = try ssh_stream_buffer.Ring.init(queue_allocator, stream_buffer_capacity);
            errdefer outbound.deinit(queue_allocator);
            const stream = try allocator.create(StreamChannel);
            stream.* = .{
                .owner = owner,
                .allocator = allocator,
                .path = path,
                .inbound = inbound,
                .outbound = outbound,
            };
            stream.changed.init();
            return stream;
        }

        fn destroy(self: *StreamChannel) void {
            self.changed.deinit();
            self.inbound.deinit(queue_allocator);
            self.outbound.deinit(queue_allocator);
            self.allocator.free(self.path);
            self.allocator.destroy(self);
        }

        fn currentPhase(self: *StreamChannel) Phase {
            self.mutex.lock();
            defer self.mutex.unlock();
            return self.phase;
        }

        fn isCloseRequested(self: *StreamChannel) bool {
            self.mutex.lock();
            defer self.mutex.unlock();
            return self.close_requested;
        }

        /// Loop thread: publishes a phase and wakes waiters.
        pub fn setPhase(self: *StreamChannel, phase: Phase, failure: Failure, detail: []const u8) void {
            self.mutex.lock();
            defer self.mutex.unlock();
            self.phase = phase;
            self.failure = failure;
            const length = @min(detail.len, detail_capacity);
            @memcpy(self.detail[0..length], detail[0..length]);
            self.detail_len = length;
            self.changed.broadcast();
        }

        /// Waits up to `timeout_ms` for the open to finish. Returns 1 when open,
        /// 0 while still opening, -1 when closed, and -2 when the open failed.
        pub fn awaitOpen(self: *StreamChannel, timeout_ms: c_int) c_int {
            self.mutex.lock();
            defer self.mutex.unlock();
            if (self.close_requested) return -1;
            if (self.phase == .pending or self.phase == .opening) {
                self.changed.timedWait(&self.mutex, timeout_ms);
            }
            if (self.close_requested) return -1;
            return switch (self.phase) {
                .open => 1,
                .failed => -2,
                .pending, .opening => 0,
            };
        }

        /// Copies buffered inbound bytes into `buffer`. Returns the count, 0 when
        /// nothing arrived within `timeout_ms`, -1 at EOF or after close, and -2
        /// when the stream failed. Buffered bytes are returned before a failure.
        pub fn read(self: *StreamChannel, buffer: []u8, timeout_ms: c_int) c_int {
            self.mutex.lock();
            defer self.mutex.unlock();
            if (self.close_requested) return -1;
            if (self.inbound.len == 0 and !self.remote_eof and self.phase != .failed) {
                self.changed.timedWait(&self.mutex, timeout_ms);
                if (self.close_requested) return -1;
            }
            if (self.inbound.len > 0) {
                const count = self.inbound.pop(buffer);
                if (self.inbound_throttled and !self.retired) {
                    self.inbound_throttled = false;
                    self.owner.signalCommandWake();
                }
                return @intCast(count);
            }
            if (self.phase == .failed) return -2;
            if (self.remote_eof) return -1;
            return 0;
        }

        /// Queues as much of `data` as the outbound ring has room for, waiting up to
        /// `timeout_ms` for room. Returns the count accepted, 0 on timeout, -1 after
        /// close, and -2 when the stream failed or the peer closed it.
        pub fn write(self: *StreamChannel, data: []const u8, timeout_ms: c_int) c_int {
            self.mutex.lock();
            defer self.mutex.unlock();
            if (self.close_requested) return -1;
            if (self.phase == .failed or self.remote_eof) return -2;
            if (self.outbound.freeSpace() == 0) {
                self.changed.timedWait(&self.mutex, timeout_ms);
                if (self.close_requested) return -1;
                if (self.phase == .failed or self.remote_eof) return -2;
            }
            const accepted = self.outbound.push(data);
            self.outbound_pushed += accepted;
            if (accepted > 0 and !self.retired) self.owner.signalCommandWake();
            return @intCast(accepted);
        }

        /// Waits until every byte pushed so far has been written through libssh2, which is
        /// stronger than admission to the ring: a close after this returns cannot drop them.
        /// Returns 1 when drained, 0 when `timeout_ms` passes first, -1 after close, and -2 when
        /// the stream failed or the peer closed it before the bytes went out.
        pub fn flush(self: *StreamChannel, timeout_ms: c_int) c_int {
            self.mutex.lock();
            defer self.mutex.unlock();
            const target = self.outbound_pushed;
            if (self.outbound_written >= target) return 1;
            if (self.close_requested) return -1;
            if (self.phase == .failed or self.remote_eof) return -2;
            self.changed.timedWait(&self.mutex, timeout_ms);
            if (self.outbound_written >= target) return 1;
            if (self.close_requested) return -1;
            if (self.phase == .failed or self.remote_eof) return -2;
            return 0;
        }

        /// Asks the loop to retire the channel and wakes blocked readers and writers.
        /// Never waits on the loop, so it is safe from the main thread.
        pub fn close(self: *StreamChannel) void {
            self.mutex.lock();
            defer self.mutex.unlock();
            if (self.close_requested) return;
            self.close_requested = true;
            self.changed.broadcast();
            if (!self.retired) self.owner.signalCommandWake();
        }

        /// JVM is done with the handle. Call only after `close`, with no call in flight.
        pub fn release(self: *StreamChannel) void {
            self.mutex.lock();
            self.released = true;
            const free_now = self.retired;
            self.mutex.unlock();
            if (free_now) self.destroy();
        }

        pub fn failureCode(self: *StreamChannel) c_int {
            self.mutex.lock();
            defer self.mutex.unlock();
            return @intFromEnum(self.failure);
        }

        /// Copies the failure detail into `out`, NUL-terminated, and returns the length.
        pub fn copyDetail(self: *StreamChannel, out: *[detail_capacity + 1]u8) usize {
            self.mutex.lock();
            defer self.mutex.unlock();
            @memcpy(out[0..self.detail_len], self.detail[0..self.detail_len]);
            out[self.detail_len] = 0;
            return self.detail_len;
        }

        /// Loop thread. Bytes ready to write to libssh2. The slice stays valid unlocked: pushes
        /// write only into free space, and only the loop consumes.
        pub fn outboundPeek(self: *StreamChannel) []const u8 {
            self.mutex.lock();
            defer self.mutex.unlock();
            return self.outbound.peek();
        }

        /// Loop thread: `count` bytes went out through libssh2. Wakes flushers.
        pub fn outboundConsumed(self: *StreamChannel, count: usize) void {
            self.mutex.lock();
            defer self.mutex.unlock();
            self.outbound.consume(count);
            self.outbound_written += count;
            self.changed.broadcast();
        }

        /// Loop thread: free bytes in the inbound ring. A full ring marks the stream throttled,
        /// so the next JVM read wakes the loop to resume.
        pub fn inboundSpaceOrThrottle(self: *StreamChannel) usize {
            self.mutex.lock();
            defer self.mutex.unlock();
            const space = self.inbound.freeSpace();
            if (space == 0) self.inbound_throttled = true;
            return space;
        }

        /// Loop thread: bytes read from libssh2 become readable by the JVM.
        pub fn inboundPush(self: *StreamChannel, bytes: []const u8) void {
            self.mutex.lock();
            defer self.mutex.unlock();
            _ = self.inbound.push(bytes);
            self.changed.broadcast();
        }

        /// Loop thread: the peer closed its side. Buffered inbound bytes stay readable, then
        /// readers see EOF. Writes fail as a disconnect.
        pub fn markRemoteEof(self: *StreamChannel) void {
            self.mutex.lock();
            defer self.mutex.unlock();
            self.remote_eof = true;
            self.failure = .disconnected;
            self.detail_len = 0;
            self.changed.broadcast();
        }

        /// Loop thread: the stream failed. `message` is copied now, because libssh2 reuses its buffer.
        pub fn failWith(self: *StreamChannel, failure: Failure, message: []const u8) void {
            self.setPhase(.failed, failure, message);
        }

        /// Loop thread, or deinit after the loop has exited: unlinks the stream's
        /// libssh2 channel and frees the memory once the JVM has released it too.
        pub fn retire(self: *StreamChannel) void {
            if (self.channel) |channel| {
                self.owner.releaseChannel(channel);
                self.channel = null;
            }
            self.mutex.lock();
            self.close_requested = true;
            self.retired = true;
            self.changed.broadcast();
            const free_now = self.released;
            self.mutex.unlock();
            if (free_now) self.destroy();
        }
    };

    /// One channel open waiting for, or holding, the open slot.
    const OpenJob = union(enum) {
        stream: *StreamChannel,
        op: *ChannelOp,

        /// The JVM has cancelled this open, so it need not run.
        fn isDead(job: OpenJob) bool {
            return switch (job) {
                .stream => |stream| stream.isCloseRequested(),
                .op => |op| op.isAbandoned(),
            };
        }
    };

    /// A shell, exec, or SFTP channel that the loop opens and drives one step at a time.
    /// The JVM starts it, waits on `changed` with bounded timeouts, takes the result, and
    /// releases it. The loop never waits on the JVM, and the JVM never touches libssh2.
    ///
    /// Lifetime: `ops` holds an op until the loop has finished it AND the JVM has released
    /// it. Whichever comes last cleans up what the other left. A session that ends first
    /// fails the op and frees it on release, without touching the session.
    pub const ChannelOp = struct {
        pub const Kind = enum { shell, exec, sftp };
        pub const Stage = enum { queued, opening, pty, env, startup, reading };
        pub const State = enum { pending, done, failed };
        pub const detail_capacity: usize = 128;
        pub const env_names = [_][]const u8{ "LC_ALL", "LANG", "LC_CTYPE" };
        // Same per-variable retry budget the blocking open used.
        pub const env_attempt_limit: u32 = 50;
        // Exec output is held in memory until the command ends, so it needs a cap.
        pub const max_output_bytes: usize = 16 * 1024 * 1024;

        owner: *SshNativeSession,
        allocator: std.mem.Allocator,
        kind: Kind,
        term_type: [:0]u8,
        command: [:0]u8,
        has_command: bool,
        cols: c_int,
        rows: c_int,
        mutex: Mutex = .{},
        changed: Cond = .{},
        // Guarded by `mutex`.
        state: State = .pending,
        detail: [detail_capacity]u8 = undefined,
        detail_len: usize = 0,
        abandoned: bool = false,
        // Set once an exec command is running. The JVM bounds the open with a deadline
        // until this is set, and waits without a deadline afterwards.
        opened: bool = false,
        jvm_released: bool = false,
        owner_gone: bool = false,
        shell_result: ?*ShellChannel = null,
        sftp_result: ?*anyopaque = null,
        exit_status: c_int = -1,
        stdout: std.ArrayList(u8) = .empty,
        stderr: std.ArrayList(u8) = .empty,
        // Loop thread only, under the registry lock.
        stage: Stage = .queued,
        finished: bool = false,
        channel: ?*c.LIBSSH2_CHANNEL = null,
        env_index: usize = 0,
        env_attempts: u32 = 0,
        stdout_done: bool = false,
        stderr_done: bool = false,

        fn create(owner: *SshNativeSession, kind: Kind, term_type: []const u8, command: ?[]const u8, cols: c_int, rows: c_int) !*ChannelOp {
            const allocator = owner.allocator;
            const term = try allocator.dupeSentinel(u8, term_type, 0);
            errdefer allocator.free(term);
            const cmd = try allocator.dupeSentinel(u8, command orelse "", 0);
            errdefer allocator.free(cmd);
            const op = try allocator.create(ChannelOp);
            op.* = .{
                .owner = owner,
                .allocator = allocator,
                .kind = kind,
                .term_type = term,
                .command = cmd,
                .has_command = command != null,
                .cols = cols,
                .rows = rows,
            };
            op.changed.init();
            return op;
        }

        pub fn destroy(self: *ChannelOp) void {
            self.changed.deinit();
            self.stdout.deinit(self.allocator);
            self.stderr.deinit(self.allocator);
            self.allocator.free(self.term_type);
            self.allocator.free(self.command);
            self.allocator.destroy(self);
        }

        fn isAbandoned(self: *ChannelOp) bool {
            self.mutex.lock();
            defer self.mutex.unlock();
            return self.abandoned;
        }

        /// Caller holds `mutex`.
        fn failLocked(self: *ChannelOp, message: []const u8) void {
            self.state = .failed;
            const length = @min(message.len, detail_capacity);
            @memcpy(self.detail[0..length], message[0..length]);
            self.detail_len = length;
            self.changed.broadcast();
        }

        /// Caller holds `mutex`.
        fn completeLocked(self: *ChannelOp) void {
            self.state = .done;
            self.changed.broadcast();
        }

        fn appendOutput(self: *ChannelOp, stderr: bool, bytes: []const u8) error{ OutputTooLarge, OutOfMemory }!void {
            self.mutex.lock();
            defer self.mutex.unlock();
            if (self.stdout.items.len + self.stderr.items.len + bytes.len > max_output_bytes) {
                return error.OutputTooLarge;
            }
            if (stderr) {
                try self.stderr.appendSlice(self.allocator, bytes);
            } else {
                try self.stdout.appendSlice(self.allocator, bytes);
            }
        }

        /// Waits up to `timeout_ms`. Returns 1 when done, -2 when failed, and 0 while pending.
        /// Waits up to `timeout_ms`. With `until_done` false, returns 2 as soon as an exec command
        /// is running, so the JVM can switch from the open deadline to waiting on the output.
        /// Returns 1 when done, -2 when failed, and 0 while still waiting.
        pub fn awaitResult(self: *ChannelOp, timeout_ms: c_int, until_done: bool) c_int {
            self.mutex.lock();
            defer self.mutex.unlock();
            if (!settledLocked(self, until_done)) self.changed.timedWait(&self.mutex, timeout_ms);
            if (self.state == .failed) return -2;
            if (self.state == .done) return 1;
            if (!until_done and self.opened) return 2;
            return 0;
        }

        /// Caller holds `mutex`.
        fn settledLocked(self: *ChannelOp, until_done: bool) bool {
            if (self.state != .pending) return true;
            return !until_done and self.opened;
        }

        /// Exec only: the command is running. Caller holds `mutex`.
        fn markOpenedLocked(self: *ChannelOp) void {
            self.opened = true;
            self.changed.broadcast();
        }

        pub fn copyDetail(self: *ChannelOp, out: *[detail_capacity + 1]u8) usize {
            self.mutex.lock();
            defer self.mutex.unlock();
            @memcpy(out[0..self.detail_len], self.detail[0..self.detail_len]);
            out[self.detail_len] = 0;
            return self.detail_len;
        }

        /// Hands the shell to the caller, who now owns it. Null when none is ready or it was taken.
        pub fn takeShell(self: *ChannelOp) ?*ShellChannel {
            self.mutex.lock();
            defer self.mutex.unlock();
            const shell = self.shell_result;
            self.shell_result = null;
            return shell;
        }

        pub fn takeSftp(self: *ChannelOp) ?*anyopaque {
            self.mutex.lock();
            defer self.mutex.unlock();
            const handle = self.sftp_result;
            self.sftp_result = null;
            return handle;
        }

        pub fn exitStatus(self: *ChannelOp) c_int {
            self.mutex.lock();
            defer self.mutex.unlock();
            return self.exit_status;
        }

        /// The JVM is done with the op. Anything it still holds is cleaned up by the loop,
        /// so this never touches the owner.
        pub fn release(self: *ChannelOp) void {
            self.mutex.lock();
            self.abandoned = true;
            self.jvm_released = true;
            const gone = self.owner_gone;
            self.changed.broadcast();
            // A queued op is pruned on the loop's next pass, so wake the loop now rather than after the
            // poll timeout. Only the owner sets `owner_gone`, and it does so before it frees the owner,
            // so while `gone` is false the owner is still alive.
            if (!gone) self.owner.signalCommandWake();
            self.mutex.unlock();
            if (gone) self.destroy();
        }
    };

    const JniAttachment = struct {
        java_vm: *c.JavaVM,
        env: *c.JNIEnv,
        attached: bool,

        fn acquire(java_vm_opt: ?*c.JavaVM, current_env: ?*c.JNIEnv) ?JniAttachment {
            const java_vm = java_vm_opt orelse return null;
            if (current_env) |env| return .{ .java_vm = java_vm, .env = env, .attached = false };
            var env: ?*c.JNIEnv = null;
            if (java_vm.*.*.AttachCurrentThreadAsDaemon.?(java_vm, @ptrCast(&env), null) != 0) return null;
            return .{ .java_vm = java_vm, .env = env orelse return null, .attached = true };
        }

        fn release(self: *JniAttachment) void {
            if (self.attached) _ = self.java_vm.*.*.DetachCurrentThread.?(self.java_vm);
        }
    };

    allocator: std.mem.Allocator,
    session: *c.LIBSSH2_SESSION,
    socket_fd: c_int,
    wake_pipe: [2]c_int,
    command_lock: Mutex,
    command_queue: std.ArrayList(*Command),
    // Guards `shells`. The loop thread iterates it; open/close/deinit mutate it.
    shells_lock: Mutex = .{},
    shells: std.ArrayList(*ShellChannel) = .empty,
    // Loop-thread only: channels whose close is still waiting on the socket.
    closing_channels: std.ArrayList(*c.LIBSSH2_CHANNEL) = .empty,
    // Set once at init; the loop thread attaches with it.
    java_vm: ?*c.JavaVM,
    thread: ?std.Thread,
    running: std.atomic.Value(bool),
    // SFTP read-ahead: one 256 KiB staging buffer holds the chunk fetched
    // while the JVM drains the previous one, hiding the network RTT behind
    // local disk I/O. libssh2 stays on the single loop thread; at most one
    // read request is ever in flight.
    sftp_staging: ?[]u8 = null,
    sftp_staging_handle: ?*anyopaque = null,
    sftp_staging_len: usize = 0,
    sftp_staging_read_handle: ?*anyopaque = null,
    sftp_staging_armed: bool = false,
    sftp_staging_eof: bool = false,
    sftp_staging_error: bool = false,
    // Guards `streams`, `ops`, and `open_queue`. Lock order: streams_lock before a
    // stream's or operation's mutex.
    streams_lock: Mutex = .{},
    streams: std.ArrayList(*StreamChannel) = .empty,
    ops: std.ArrayList(*ChannelOp) = .empty,
    // Channel opens waiting for the slot, FIFO.
    open_queue: ssh_open_queue.OpenQueue(OpenJob) = .{},
    // Loop thread only: the one job whose libssh2 channel open is in flight. libssh2
    // keeps one channel-open state per session, so every other open waits for it.
    open_slot: ?OpenJob = null,
    // Loop thread only: when the current slot holder took the slot, in monotonic ns.
    slot_started_ns: u64 = 0,
    // Set when a pass had budgeted work left over. The next poll must not sleep.
    more_work: bool = false,
    // Why the session is ending, reported by pending operations that fail on the way out.
    session_end_reason: []const u8 = "The SSH session ended",
    const CommandKind = enum {
        write,
        resize,
        callback,
    };

    const Command = struct {
        kind: CommandKind,
        shell: ?*ShellChannel = null,
        data: ?[]u8 = null,
        cols: c_int = 0,
        rows: c_int = 0,
        callback: ?*const fn (*SshNativeSession, *Command) void = null,
        context: ?*anyopaque = null,
        completion: ?*Semaphore = null,
        owned: bool = false,
    };

    const CloseShellContext = struct {
        shell: *ShellChannel,
        done: bool = false,
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

    const SftpRenameContext = struct {
        handle: *anyopaque,
        old_path: []const u8,
        new_path: []const u8,
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

    fn setSocketOption(socket_fd: c_int, level: c_int, option: c_int, value: c_int) void {
        var option_value = value;
        _ = c.setsockopt(
            socket_fd,
            level,
            option,
            &option_value,
            @intCast(@sizeOf(c_int)),
        );
    }

    fn configureSocketLiveness(socket_fd: c_int) void {
        // SSH is a packet protocol with its own framing: Nagle's algorithm only
        // adds delayed-ACK latency to interactive keystroke round trips, so
        // disable it like OpenSSH does. Set it here on the raw fd so it applies
        // deterministically, independent of how the fd was created.
        if (@hasDecl(c, "TCP_NODELAY")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_NODELAY, 1);
        }

        // Size socket buffers for high-bandwidth-delay-product links so SFTP
        // and bulk shell output aren't limited by the small Android defaults.
        setSocketOption(socket_fd, c.SOL_SOCKET, c.SO_RCVBUF, 1 << 20);
        setSocketOption(socket_fd, c.SOL_SOCKET, c.SO_SNDBUF, 1 << 20);

        setSocketOption(socket_fd, c.SOL_SOCKET, c.SO_KEEPALIVE, 1);

        if (@hasDecl(c, "TCP_KEEPIDLE")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_KEEPIDLE, 15);
        }
        if (@hasDecl(c, "TCP_KEEPINTVL")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_KEEPINTVL, 5);
        }
        if (@hasDecl(c, "TCP_KEEPCNT")) {
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_KEEPCNT, 3);
        }
        if (@hasDecl(c, "TCP_USER_TIMEOUT")) {
            // Bound unacknowledged SSH keepalives on platforms that expose it.
            setSocketOption(socket_fd, c.IPPROTO_TCP, c.TCP_USER_TIMEOUT, 20_000);
        }
    }

    pub fn init(
        allocator: std.mem.Allocator,
        socket_fd: c_int,
        username: []const u8,
        password_or_key: []const u8,
        is_password: bool,
        java_vm: ?*c.JavaVM,
    ) !*SshNativeSession {
        _ = c.libssh2_init(0);

        const session = c.libssh2_session_init_ex(null, null, null, null) orelse return error.SessionInitFailed;
        errdefer _ = c.libssh2_session_free(session);

        _ = c.libssh2_session_set_blocking(session, 0);
        _ = c.libssh2_session_flag(session, c.LIBSSH2_FLAG_COMPRESS, 1);

        const flags = c.fcntl(socket_fd, c.F_GETFL, @as(c_int, 0));
        _ = c.fcntl(socket_fd, c.F_SETFL, flags | c.O_NONBLOCK);
        configureSocketLiveness(socket_fd);

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
            const password_c = try allocator.dupeSentinel(u8, password_or_key, 0);
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

        // Keep idle sessions from surviving a dead Wi-Fi link indefinitely.
        c.libssh2_keepalive_config(session, 1, keepalive_interval_seconds);

        var wake_p: [2]c_int = undefined;
        if (c.pipe(&wake_p) != 0) return error.PipeFailed;
        errdefer {
            _ = c.close(wake_p[0]);
            _ = c.close(wake_p[1]);
        }

        _ = c.fcntl(wake_p[0], c.F_SETFL, c.O_NONBLOCK);
        // Wakeups are sent while holding stream locks the loop also needs, so a
        // blocking write could deadlock on a full pipe. A full pipe already means a
        // wakeup is pending, so dropping the byte loses nothing.
        _ = c.fcntl(wake_p[1], c.F_SETFL, c.O_NONBLOCK);

        const self = try allocator.create(SshNativeSession);
        self.* = .{
            .allocator = allocator,
            .session = session,
            .socket_fd = socket_fd,
            .wake_pipe = wake_p,
            .command_lock = .{},
            .command_queue = .empty,
            .java_vm = java_vm,
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
            _ = c.libssh2_session_disconnect_ex(self.session, c.SSH_DISCONNECT_BY_APPLICATION, "Shutdown", "");
            _ = c.libssh2_session_free(self.session);
        }

        // The loop is gone. Streams still linked must stop touching this session
        // before the wake pipe closes and the struct is freed.
        self.failStreams();
        self.failOps();
        self.retireStreams();
        self.streams.deinit(queue_allocator);
        self.ops.deinit(queue_allocator);
        self.open_queue.deinit(queue_allocator);

        _ = c.close(self.wake_pipe[0]);
        _ = c.close(self.wake_pipe[1]);
        // The loop thread has exited, so nothing else references the shells.
        for (self.shells.items) |shell| shell.destroy(env);
        self.shells.deinit(queue_allocator);
        self.closing_channels.deinit(queue_allocator);
        self.command_queue.deinit(queue_allocator);
        if (self.sftp_staging) |staging| queue_allocator.free(staging);

        const allocator = self.allocator;
        allocator.destroy(self);
    }

    /// Closes the channel and frees `shell`. The caller must not use the handle afterwards.
    pub fn closeShell(self: *SshNativeSession, shell: *ShellChannel, env: ?*c.JNIEnv) void {
        var context = CloseShellContext{ .shell = shell };
        if (!self.dispatchSync(closeShellCallback, &context) or !context.done) {
            // The loop already stopped (or is stopping and cancelled the command).
            // Unlink under the lock so its exit path can't touch the shell; the
            // libssh2 channel itself is released by session_free.
            self.shells_lock.lock();
            _ = self.unlinkShellLocked(shell);
            self.shells_lock.unlock();
        }
        // JNI global refs must be dropped with the caller's env, not the loop thread's.
        shell.destroy(env);
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

    pub fn sftpRename(self: *SshNativeSession, handle: *anyopaque, old_path: []const u8, new_path: []const u8) bool {
        var context = SftpRenameContext{ .handle = handle, .old_path = old_path, .new_path = new_path };
        return self.dispatchSync(sftpRenameCallback, &context) and context.success;
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

    /// Links a Unix-socket channel for the loop to open and returns without waiting
    /// for the remote side. Returns null when the session is no longer running.
    /// Callers poll `StreamChannel.awaitOpen` for the result.
    pub fn openStreamLocal(self: *SshNativeSession, socket_path: []const u8) StartError!*StreamChannel {
        if (!isValidStreamPath(socket_path)) return error.InvalidPath;
        const stream = try StreamChannel.create(self, socket_path);

        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        if (!self.running.load(.acquire)) {
            stream.destroy();
            return error.NotRunning;
        }
        if (self.streams.items.len >= max_live_streams) {
            stream.destroy();
            return error.TooManyPending;
        }
        self.streams.append(queue_allocator, stream) catch {
            stream.destroy();
            return error.OutOfMemory;
        };
        self.open_queue.admit(queue_allocator, .{ .stream = stream }, max_queued_opens) catch |err| {
            _ = self.streams.pop();
            stream.destroy();
            return err;
        };
        self.signalCommandWake();
        return stream;
    }

    fn isValidStreamPath(path: []const u8) bool {
        return path.len > 0 and path[0] == '/' and std.mem.indexOfScalar(u8, path, 0) == null;
    }

    pub fn waitSocket(socket_fd: c_int, session: *c.LIBSSH2_SESSION) !void {
        var fds = [_]c.struct_pollfd{.{ .fd = socket_fd, .events = 0, .revents = 0 }};
        const directions = c.libssh2_session_block_directions(session);
        if ((directions & c.LIBSSH2_SESSION_BLOCK_INBOUND) != 0) fds[0].events |= c.POLLIN;
        if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) fds[0].events |= c.POLLOUT;

        const poll_result = c.poll(&fds, 1, socket_poll_timeout_ms);
        if (poll_result < 0) return error.SocketPollFailed;
        if ((fds[0].revents & (c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) {
            return error.SocketClosed;
        }

        var seconds_to_next: c_int = 0;
        if (c.libssh2_keepalive_send(session, &seconds_to_next) != 0) {
            return error.SocketClosed;
        }
    }

    const SessionError = struct {
        code: c_int,
        message: []const u8,
    };

    /// libssh2 reuses its message buffer on the next call, so copy the message before any other libssh2 call.
    fn lastSessionError(self: *SshNativeSession) SessionError {
        var message: [*c]u8 = null;
        var length: c_int = 0;
        const code = c.libssh2_session_last_error(self.session, &message, &length, 0);
        if (message == null or length <= 0) return .{ .code = code, .message = "" };
        const bytes: [*]const u8 = @ptrCast(message);
        return .{ .code = code, .message = bytes[0..@intCast(length)] };
    }

    fn classifyStreamFailure(code: c_int, message: []const u8) StreamChannel.Failure {
        // Reason strings come from libssh2's channel.c (SSH_MSG_CHANNEL_OPEN_FAILURE).
        if (std.mem.indexOf(u8, message, "administratively prohibited") != null) return .denied;
        if (std.mem.indexOf(u8, message, "unknown channel type") != null) return .denied;
        if (std.mem.indexOf(u8, message, "connect failed") != null) return .unreachable_path;
        return switch (code) {
            c.LIBSSH2_ERROR_SOCKET_DISCONNECT,
            c.LIBSSH2_ERROR_SOCKET_RECV,
            c.LIBSSH2_ERROR_SOCKET_SEND,
            c.LIBSSH2_ERROR_CHANNEL_CLOSED,
            => .disconnected,
            else => .other,
        };
    }

    /// Advances every linked stream one pass. Loop thread only.
    fn processStreams(self: *SshNativeSession) void {
        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        var index: usize = 0;
        while (index < self.streams.items.len) {
            const stream = self.streams.items[index];
            if (!self.serviceStream(stream)) {
                index += 1;
                continue;
            }
            _ = self.streams.orderedRemove(index);
            stream.retire();
        }
    }

    /// Returns true once the stream is closed and must be unlinked. Opens are driven by
    /// `processOpenSlot`, so an opening stream is left alone here.
    fn serviceStream(self: *SshNativeSession, stream: *StreamChannel) bool {
        switch (stream.currentPhase()) {
            .pending => {
                if (!stream.isCloseRequested()) return false;
                // Closed while queued: it never reaches libssh2. Drop it from the queue
                // before it is freed, so the slot can't reach a dangling pointer.
                self.dequeueOpen(.{ .stream = stream });
                return true;
            },
            // Finish the open even if the JVM closed the stream. libssh2 keeps the in-flight
            // state on the session, and abandoning it would make the next channel open resume it.
            .opening => return false,
            .open => {
                if (stream.isCloseRequested()) return true;
                if (self.pumpStream(stream)) self.more_work = true;
                return false;
            },
            .failed => return stream.isCloseRequested(),
        }
    }

    fn driveOpen(self: *SshNativeSession, stream: *StreamChannel) void {
        const channel = c.libssh2_channel_direct_streamlocal_ex(self.session, stream.path.ptr, "", 0);
        if (channel) |value| {
            stream.channel = value;
            self.open_slot = null;
            stream.setPhase(.open, .none, "");
            return;
        }
        const error_detail = self.lastSessionError();
        if (error_detail.code == c.LIBSSH2_ERROR_EAGAIN) return;
        self.open_slot = null;
        stream.setPhase(
            .failed,
            classifyStreamFailure(error_detail.code, error_detail.message),
            error_detail.message,
        );
    }

    /// Moves bytes for an open stream: outbound first, because the peer is usually
    /// waiting on it, then inbound while the ring has room. Each direction stops
    /// after one ring's worth of bytes so a busy stream cannot starve the others.
    fn pumpStream(self: *SshNativeSession, stream: *StreamChannel) bool {
        const channel = stream.channel orelse return false;
        return pumpStreamWith(stream, Libssh2StreamIo{ .owner = self, .channel = channel });
    }

    /// libssh2 I/O for one stream: the only transport the pump touches. The pump takes `io` as
    /// a parameter so host tests can drive the production pump with scripted results.
    const Libssh2StreamIo = struct {
        owner: *SshNativeSession,
        channel: *c.LIBSSH2_CHANNEL,

        fn write(self: Libssh2StreamIo, bytes: []const u8) isize {
            return c.libssh2_channel_write(self.channel, bytes.ptr, bytes.len);
        }

        fn read(self: Libssh2StreamIo, buffer: []u8) isize {
            return c.libssh2_channel_read(self.channel, buffer.ptr, buffer.len);
        }

        fn eof(self: Libssh2StreamIo) bool {
            return c.libssh2_channel_eof(self.channel) != 0;
        }

        fn lastError(self: Libssh2StreamIo) SessionError {
            return self.owner.lastSessionError();
        }

        fn release(self: Libssh2StreamIo) void {
            self.owner.releaseChannel(self.channel);
        }
    };

    /// Moves bytes for one open stream over `io`: outbound first, because the peer is usually
    /// waiting on it, then inbound while the ring has room. Returns true when a budget ran out
    /// with more work pending, so the loop polls again without sleeping. `io` supplies
    /// `write`, `read`, `eof`, `lastError`, and `release`, with libssh2's return conventions.
    pub fn pumpStreamWith(stream: *StreamChannel, io: anytype) bool {
        var budget: usize = stream_buffer_capacity;
        while (true) {
            const pending = stream.outboundPeek();
            if (pending.len == 0) break;
            if (budget == 0) return true;
            const result = io.write(pending);
            if (result == c.LIBSSH2_ERROR_EAGAIN) break;
            if (result <= 0) {
                const error_detail = io.lastError();
                stream.failWith(classifyStreamFailure(error_detail.code, error_detail.message), error_detail.message);
                return false;
            }
            const written: usize = @intCast(result);
            stream.outboundConsumed(written);
            budget -|= written;
        }

        const chunk_bytes = 16 * 1024;
        var chunk: [chunk_bytes]u8 = undefined;
        budget = stream_buffer_capacity;
        while (true) {
            const space = stream.inboundSpaceOrThrottle();
            if (space == 0) break;
            if (budget == 0) return true;
            const result = io.read(chunk[0..@min(space, chunk_bytes)]);
            if (result == c.LIBSSH2_ERROR_EAGAIN) break;
            if (result > 0) {
                const count: usize = @intCast(result);
                stream.inboundPush(chunk[0..count]);
                budget -|= count;
                continue;
            }
            if (result == 0 or result == c.LIBSSH2_ERROR_CHANNEL_CLOSED or io.eof()) {
                // The peer closed its side: buffered bytes stay readable, then readers see EOF.
                stream.channel = null;
                io.release();
                stream.markRemoteEof();
                return false;
            }
            const error_detail = io.lastError();
            stream.failWith(classifyStreamFailure(error_detail.code, error_detail.message), error_detail.message);
            return false;
        }
        return false;
    }

    /// The session is ending. Every stream that has not failed yet fails as
    /// DISCONNECTED, and its libssh2 channel goes away with the session.
    pub fn failStreams(self: *SshNativeSession) void {
        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        self.open_slot = null;
        for (self.streams.items) |stream| {
            stream.channel = null;
            stream.mutex.lock();
            if (stream.phase != .failed) {
                stream.phase = .failed;
                stream.failure = .disconnected;
                const length = @min(self.session_end_reason.len, StreamChannel.detail_capacity);
                @memcpy(stream.detail[0..length], self.session_end_reason[0..length]);
                stream.detail_len = length;
            }
            stream.changed.broadcast();
            stream.mutex.unlock();
        }
    }

    /// Only after the loop has exited. Retires every linked stream, so a later
    /// JVM release is the one that frees it.
    pub fn retireStreams(self: *SshNativeSession) void {
        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        for (self.streams.items) |stream| stream.retire();
        self.streams.clearRetainingCapacity();
    }

    /// Queues a channel op for the loop. Returns null once the session is no longer running.
    fn startOp(
        self: *SshNativeSession,
        kind: ChannelOp.Kind,
        term_type: []const u8,
        cols: c_int,
        rows: c_int,
        command: ?[]const u8,
    ) StartError!*ChannelOp {
        const op = try ChannelOp.create(self, kind, term_type, command, cols, rows);

        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        if (!self.running.load(.acquire)) {
            op.destroy();
            return error.NotRunning;
        }
        if (self.ops.items.len >= max_live_ops) {
            op.destroy();
            return error.TooManyPending;
        }
        self.ops.append(queue_allocator, op) catch {
            op.destroy();
            return error.OutOfMemory;
        };
        self.open_queue.admit(queue_allocator, .{ .op = op }, max_queued_opens) catch |err| {
            _ = self.ops.pop();
            op.destroy();
            return err;
        };
        self.signalCommandWake();
        return op;
    }

    /// Starts a login shell, or one running `command` when given. Poll `ChannelOp.awaitResult`.
    pub fn openShellOp(self: *SshNativeSession, term_type: []const u8, cols: c_int, rows: c_int, command: ?[]const u8) StartError!*ChannelOp {
        return self.startOp(.shell, term_type, cols, rows, command);
    }

    /// Starts `command` on a new exec channel. Its output is collected until the command ends.
    pub fn execOp(self: *SshNativeSession, command: []const u8) StartError!*ChannelOp {
        return self.startOp(.exec, "", 0, 0, command);
    }

    pub fn sftpOp(self: *SshNativeSession) StartError!*ChannelOp {
        return self.startOp(.sftp, "", 0, 0, null);
    }

    /// Gives the open slot to the next queued job and advances the job holding it.
    /// Loop thread only.
    fn processOpenSlot(self: *SshNativeSession) void {
        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        self.pruneDeadOpens();
        if (self.open_slot != null and ssh_open_queue.deadlineExpired(self.slot_started_ns, monotonicNowNs(), native_open_deadline_ns)) {
            self.endSession(open_deadline_reason);
            return;
        }
        // A finished open hands the slot straight to the next job, so the next open does
        // not wait for another poll.
        while (true) {
            if (self.open_slot == null) {
                if (self.nextOpenJob()) |job| {
                    self.open_slot = job;
                    self.slot_started_ns = monotonicNowNs();
                }
            }
            const job = self.open_slot orelse return;
            switch (job) {
                .stream => |stream| self.driveOpen(stream),
                .op => |op| self.driveOpOpen(op),
            }
            if (self.open_slot != null) return;
        }
    }

    /// Drops queued opens the JVM cancelled, so they never take the slot and free their queue
    /// entry now. Closed streams are retired by processStreams, and abandoned ops are reaped by
    /// processOps once they are finished.
    pub fn pruneDeadOpens(self: *SshNativeSession) void {
        var dead: [max_queued_opens]OpenJob = undefined;
        const count = self.open_queue.pruneDead(&dead, OpenJob.isDead);
        for (dead[0..count]) |job| {
            switch (job) {
                .stream => {},
                .op => |op| op.finished = true,
            }
        }
    }

    /// Ends the session from the loop thread. The loop exits, and everything still pending fails
    /// with `reason`.
    fn endSession(self: *SshNativeSession, reason: []const u8) void {
        self.session_end_reason = reason;
        self.running.store(false, .release);
    }

    fn monotonicNowNs() u64 {
        var now: c.struct_timespec = undefined;
        _ = c.clock_gettime(c.CLOCK_MONOTONIC, &now);
        return @as(u64, @intCast(now.tv_sec)) * 1_000_000_000 + @as(u64, @intCast(now.tv_nsec));
    }

    /// Pops the next job that still needs the open, skipping jobs closed or abandoned
    /// while queued. Caller holds streams_lock.
    fn nextOpenJob(self: *SshNativeSession) ?OpenJob {
        while (self.open_queue.popFront()) |job| {
            switch (job) {
                .stream => |stream| {
                    // Closed while queued: processStreams retires it, and it never reaches libssh2.
                    if (stream.isCloseRequested()) continue;
                    stream.setPhase(.opening, .none, "");
                    return job;
                },
                .op => |op| {
                    if (op.isAbandoned()) {
                        op.finished = true;
                        continue;
                    }
                    op.stage = .opening;
                    return job;
                },
            }
        }
        return null;
    }

    /// Removes `job` from the open queue if it is still waiting. Caller holds streams_lock.
    fn dequeueOpen(self: *SshNativeSession, job: OpenJob) void {
        _ = self.open_queue.remove(job);
    }

    /// The slot holder's libssh2 channel open. Like the stream open, it is driven to
    /// completion, because libssh2 keeps the open's state on the session.
    fn driveOpOpen(self: *SshNativeSession, op: *ChannelOp) void {
        if (op.kind == .sftp) {
            const handle = c.libssh2_sftp_init(self.session);
            if (handle) |value| {
                op.finished = true;
                op.mutex.lock();
                op.sftp_result = @ptrCast(value);
                op.completeLocked();
                op.mutex.unlock();
                self.open_slot = null;
                return;
            }
            if (c.libssh2_session_last_error(self.session, null, null, 0) == c.LIBSSH2_ERROR_EAGAIN) return;
            self.failOpFromLastError(op, "Could not start the SFTP subsystem");
            self.open_slot = null;
            return;
        }

        const channel = c.libssh2_channel_open_ex(
            self.session,
            "session",
            @intCast("session".len),
            c.LIBSSH2_CHANNEL_WINDOW_DEFAULT,
            c.LIBSSH2_CHANNEL_PACKET_DEFAULT,
            null,
            0,
        );
        if (channel) |value| {
            op.channel = value;
            op.stage = if (op.kind == .shell) .pty else .startup;
            self.open_slot = null;
            return;
        }
        if (c.libssh2_session_last_error(self.session, null, null, 0) == c.LIBSSH2_ERROR_EAGAIN) return;
        self.failOpFromLastError(op, "Could not open a channel");
        self.open_slot = null;
    }

    /// Advances an op past its open by one step. Loop thread, under the registry lock.
    fn stepOp(self: *SshNativeSession, op: *ChannelOp) void {
        if (op.isAbandoned()) {
            // Nobody wants the result any more: stop, and free the channel.
            self.releaseOpChannel(op);
            op.finished = true;
            return;
        }
        const channel = op.channel orelse {
            op.finished = true;
            return;
        };
        switch (op.stage) {
            .pty => {
                const rc = c.libssh2_channel_request_pty_ex(
                    channel,
                    op.term_type.ptr,
                    @intCast(op.term_type.len),
                    null,
                    0,
                    op.cols,
                    op.rows,
                    0,
                    0,
                );
                if (rc == 0) {
                    op.stage = .env;
                } else if (rc != c.LIBSSH2_ERROR_EAGAIN) {
                    self.failOpFromLastError(op, "Could not request a terminal");
                }
            },
            .env => stepOpEnv(op, channel),
            .startup => self.stepOpStartup(op, channel),
            .reading => self.pumpExecOutput(op, channel),
            .queued, .opening => {},
        }
    }

    fn stepOpEnv(op: *ChannelOp, channel: *c.LIBSSH2_CHANNEL) void {
        while (op.env_index < ChannelOp.env_names.len) {
            const name = ChannelOp.env_names[op.env_index];
            const rc = c.libssh2_channel_setenv_ex(
                channel,
                name.ptr,
                @intCast(name.len),
                "en_US.UTF-8",
                @intCast("en_US.UTF-8".len),
            );
            if (rc == c.LIBSSH2_ERROR_EAGAIN and op.env_attempts + 1 < ChannelOp.env_attempt_limit) {
                op.env_attempts += 1;
                return;
            }
            // The environment is best effort. A refusal, or running out of retries, moves on.
            op.env_index += 1;
            op.env_attempts = 0;
        }
        op.stage = .startup;
    }

    fn stepOpStartup(self: *SshNativeSession, op: *ChannelOp, channel: *c.LIBSSH2_CHANNEL) void {
        const exec_command = op.kind == .exec or (op.kind == .shell and op.has_command);
        const rc = if (exec_command)
            c.libssh2_channel_process_startup(channel, "exec", @intCast("exec".len), op.command.ptr, @intCast(op.command.len))
        else
            c.libssh2_channel_process_startup(channel, "shell", @intCast("shell".len), null, 0);
        if (rc == c.LIBSSH2_ERROR_EAGAIN) return;
        if (rc != 0) {
            self.failOpFromLastError(op, "Could not start the remote command");
            return;
        }
        if (op.kind == .shell) {
            self.publishShell(op);
        } else {
            op.stage = .reading;
            op.mutex.lock();
            op.markOpenedLocked();
            op.mutex.unlock();
        }
    }

    /// Reads what is ready on the exec channel without waiting. A budget of one ring per
    /// pass keeps a chatty command from starving the other streams.
    fn pumpExecOutput(self: *SshNativeSession, op: *ChannelOp, channel: *c.LIBSSH2_CHANNEL) void {
        var budget: usize = stream_buffer_capacity;
        drainExecStream(op, channel, false, &budget) catch |err| {
            self.failExecOutput(op, err);
            return;
        };
        drainExecStream(op, channel, true, &budget) catch |err| {
            self.failExecOutput(op, err);
            return;
        };
        if (!op.stdout_done or !op.stderr_done) {
            if (budget == 0) self.more_work = true;
            return;
        }

        const status = c.libssh2_channel_get_exit_status(channel);
        self.releaseOpChannel(op);
        op.finished = true;
        op.mutex.lock();
        op.exit_status = status;
        op.completeLocked();
        op.mutex.unlock();
    }

    const DrainError = error{ ReadFailed, OutputTooLarge, OutOfMemory };

    /// Reads one of the exec channel's output streams until it would block.
    fn drainExecStream(op: *ChannelOp, channel: *c.LIBSSH2_CHANNEL, stderr: bool, budget: *usize) DrainError!void {
        var buffer: [4096]u8 = undefined;
        while (budget.* > 0) {
            if (if (stderr) op.stderr_done else op.stdout_done) return;
            const result = if (stderr)
                c.libssh2_channel_read_stderr(channel, &buffer, buffer.len)
            else
                c.libssh2_channel_read(channel, &buffer, buffer.len);
            if (result > 0) {
                const count: usize = @intCast(result);
                try op.appendOutput(stderr, buffer[0..count]);
                budget.* -|= count;
            } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                return;
            } else if (result == 0 or result == c.LIBSSH2_ERROR_CHANNEL_CLOSED) {
                if (stderr) {
                    op.stderr_done = true;
                } else {
                    op.stdout_done = true;
                }
            } else {
                return error.ReadFailed;
            }
        }
    }

    fn failExecOutput(self: *SshNativeSession, op: *ChannelOp, err: DrainError) void {
        switch (err) {
            error.ReadFailed => self.failOpFromLastError(op, "Reading the remote command's output failed"),
            error.OutputTooLarge => self.failOp(op, "The remote command wrote more than 16 MiB of output"),
            error.OutOfMemory => self.failOp(op, "Out of memory while reading the remote command's output"),
        }
    }

    /// Wraps the channel the loop just opened in a ShellChannel and hands it to the JVM.
    /// If the JVM has already released the op, undoes the open here instead.
    fn publishShell(self: *SshNativeSession, op: *ChannelOp) void {
        const channel = op.channel orelse return;
        op.channel = null;
        op.finished = true;

        const shell = self.allocator.create(ShellChannel) catch {
            self.releaseChannel(channel);
            op.mutex.lock();
            op.failLocked("Out of memory");
            op.mutex.unlock();
            return;
        };
        shell.* = .{ .owner = self, .channel = channel, .open = std.atomic.Value(bool).init(true) };

        self.shells_lock.lock();
        self.shells.append(queue_allocator, shell) catch {
            self.shells_lock.unlock();
            self.releaseChannel(channel);
            shell.channel = null;
            shell.destroy(null);
            op.mutex.lock();
            op.failLocked("Out of memory");
            op.mutex.unlock();
            return;
        };
        self.shells_lock.unlock();

        op.mutex.lock();
        defer op.mutex.unlock();
        if (op.abandoned) {
            self.shells_lock.lock();
            _ = self.unlinkShellLocked(shell);
            self.shells_lock.unlock();
            self.releaseChannel(channel);
            shell.channel = null;
            shell.destroy(null);
            op.failLocked("The shell was abandoned before it opened");
            return;
        }
        op.shell_result = shell;
        op.completeLocked();
    }

    /// Tears down a shell nobody took. Loop thread only.
    fn discardShell(self: *SshNativeSession, shell: *ShellChannel) void {
        self.shells_lock.lock();
        _ = self.unlinkShellLocked(shell);
        self.shells_lock.unlock();
        if (shell.channel) |channel| {
            shell.open.store(false, .release);
            shell.channel = null;
            self.releaseChannel(channel);
        }
        shell.destroy(null);
    }

    fn releaseOpChannel(self: *SshNativeSession, op: *ChannelOp) void {
        if (op.channel) |channel| {
            op.channel = null;
            self.releaseChannel(channel);
        }
    }

    /// Copies libssh2's message before any other libssh2 call, since releasing the channel
    /// can overwrite it. Falls back to `fallback` when libssh2 has no message.
    fn failOpFromLastError(self: *SshNativeSession, op: *ChannelOp, fallback: []const u8) void {
        var copy: [ChannelOp.detail_capacity]u8 = undefined;
        const error_detail = self.lastSessionError();
        const length = @min(error_detail.message.len, copy.len);
        @memcpy(copy[0..length], error_detail.message[0..length]);
        self.failOp(op, if (length > 0) copy[0..length] else fallback);
    }

    fn failOp(self: *SshNativeSession, op: *ChannelOp, message: []const u8) void {
        self.releaseOpChannel(op);
        op.finished = true;
        op.mutex.lock();
        defer op.mutex.unlock();
        op.failLocked(message);
    }

    /// Frees a finished op once the JVM has released it, and cleans up any shell or SFTP
    /// handle nobody took. Returns false while the SFTP close still needs socket activity.
    fn releaseFinishedOp(self: *SshNativeSession, op: *ChannelOp) bool {
        op.mutex.lock();
        const released = op.jvm_released;
        const shell = op.shell_result;
        const sftp = op.sftp_result;
        op.mutex.unlock();
        // Once released, the JVM never reads these fields again, so they need no lock to clear.
        if (!released) return false;

        if (shell) |value| {
            op.shell_result = null;
            self.discardShell(value);
        }
        if (sftp) |handle| {
            if (!self.closeUnclaimedSftp(handle)) return false;
            op.sftp_result = null;
        }
        return true;
    }

    /// Non-blocking SFTP close for a handle nobody took. Finishes an armed prefetch first,
    /// as `sftpCloseCallback` does, but returns false instead of waiting on the socket.
    fn closeUnclaimedSftp(self: *SshNativeSession, handle: *anyopaque) bool {
        if (self.sftp_staging_armed) {
            if (self.sftp_staging_read_handle) |file_handle| {
                _ = self.tryCompleteSftpPrefetch(file_handle);
                if (self.sftp_staging_armed) return false;
            }
        }
        const sftp_handle: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(handle));
        if (c.libssh2_sftp_shutdown(sftp_handle) == c.LIBSSH2_ERROR_EAGAIN) return false;
        self.sftpResetStaging();
        return true;
    }

    /// Advances every op past its open, and frees the finished ones the JVM has released.
    /// Loop thread.
    fn processOps(self: *SshNativeSession) void {
        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        var index: usize = 0;
        while (index < self.ops.items.len) {
            const op = self.ops.items[index];
            switch (op.stage) {
                .pty, .env, .startup, .reading => {
                    if (!op.finished) self.stepOp(op);
                },
                .queued, .opening => {},
            }
            if (op.finished and self.releaseFinishedOp(op)) {
                _ = self.ops.orderedRemove(index);
                op.destroy();
                continue;
            }
            index += 1;
        }
    }

    /// The session is ending. Every op not yet finished fails, and results nobody took
    /// go with the session's memory. Ops the JVM has released are freed here.
    fn failOps(self: *SshNativeSession) void {
        self.streams_lock.lock();
        defer self.streams_lock.unlock();
        self.open_slot = null;
        self.open_queue.clear();
        for (self.ops.items) |op| {
            // libssh2 frees its channels with the session, so only drop our pointers.
            op.channel = null;
            op.finished = true;
            op.mutex.lock();
            if (op.state == .pending) op.failLocked(self.session_end_reason);
            op.shell_result = null;
            op.sftp_result = null;
            op.owner_gone = true;
            const free_now = op.jvm_released;
            op.mutex.unlock();
            if (free_now) op.destroy();
        }
        self.ops.clearRetainingCapacity();
    }

    fn runLoop(self: *SshNativeSession) void {
        var jni_env: ?*c.JNIEnv = null;
        var attached = false;

        if (self.java_vm) |vm| {
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
            self.more_work = false;
            var seconds_to_next: c_int = 0;
            if (c.libssh2_keepalive_send(self.session, &seconds_to_next) != 0) {
                self.running.store(false, .release);
                break;
            }

            // libssh2 operations can consume socket packets while leaving shell data in the
            // channel's internal buffer. Drain it before poll; otherwise no fd readiness remains
            // to wake an idle shell until a later keypress or resize generates more traffic.
            self.processRead(jni_env);
            if (!self.running.load(.acquire)) break;
            // Opens advance first, so a channel that finished on the last poll starts its
            // next step before the loop sleeps again.
            self.processOpenSlot();
            self.processStreams();
            self.processOps();
            self.processCommands();
            if (!self.running.load(.acquire)) break;

            fds[0].events = c.POLLIN;
            const directions = c.libssh2_session_block_directions(self.session);
            if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) fds[0].events |= c.POLLOUT;

            // An idle session only needs to wake to send keepalives: sleep up
            // to the keepalive deadline instead of a fixed 1 s tick (10x fewer
            // wakeups). Any pending write or inbound data still wakes poll
            // immediately, so latency is unaffected. Clamp the deadline to the
            // configured interval so a libssh2-side anomaly can never overflow
            // the c_int multiply or busy-loop the poll.
            var poll_timeout_ms: c_int = socket_poll_timeout_ms;
            if (seconds_to_next > 0) {
                const clamped_seconds = @min(seconds_to_next, @as(c_int, @intCast(keepalive_interval_seconds)));
                const keepalive_deadline_ms: c_int = clamped_seconds * 1000;
                if (keepalive_deadline_ms > poll_timeout_ms) poll_timeout_ms = keepalive_deadline_ms;
            }
            // A held slot must be re-checked against its deadline, and leftover budgeted work must
            // continue, so neither may sleep for the keepalive interval.
            if (self.open_slot != null) poll_timeout_ms = @min(poll_timeout_ms, socket_poll_timeout_ms);
            if (self.more_work) poll_timeout_ms = 0;

            const poll_result = c.poll(&fds, 2, poll_timeout_ms);
            if (poll_result < 0) {
                self.running.store(false, .release);
                break;
            }
            if (poll_result == 0) continue;

            if ((fds[1].revents & c.POLLIN) != 0) {
                var dummy: [128]u8 = undefined;
                _ = c.read(self.wake_pipe[0], &dummy, dummy.len);
                self.processCommands();
            }

            const socket_events = fds[0].revents;
            if ((socket_events & (c.POLLIN | c.POLLOUT | c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) {
                self.processRead(jni_env);
                self.processCommands();
                // Advance an in-flight SFTP prefetch whenever the socket wakes,
                // so the next chunk is ready by the time the JVM asks for it.
                if (self.sftp_staging_armed) {
                    if (self.sftp_staging_read_handle) |file_handle| {
                        _ = self.tryCompleteSftpPrefetch(file_handle);
                    }
                }
                if ((socket_events & (c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) {
                    self.running.store(false, .release);
                }
            }
        }

        self.cancelQueuedCommands();
        // Before session_free takes the libssh2 channels with it.
        self.failStreams();
        self.failOps();
        // session_free releases the libssh2 channels; the ShellChannel structs
        // stay alive for the JVM until closeShell or deinit.
        self.shells_lock.lock();
        for (self.shells.items) |shell| {
            if (!shell.open.load(.acquire)) continue;
            shell.deliverOutput(jni_env);
            // The session ended with this shell still open, so no exit status can exist for it.
            shell.exit_lock.lock();
            shell.exit.recordTransportLoss();
            shell.exit_lock.unlock();
            shell.open.store(false, .release);
            shell.channel = null;
            shell.deliverClosed(jni_env);
        }
        self.shells_lock.unlock();
        _ = c.libssh2_session_disconnect_ex(self.session, c.SSH_DISCONNECT_BY_APPLICATION, "Shutdown", "");
        _ = c.libssh2_session_free(self.session);
    }

    fn processRead(self: *SshNativeSession, env: ?*c.JNIEnv) void {
        self.retryClosingChannels();
        var buffer: [32768]u8 = undefined;
        self.shells_lock.lock();
        defer self.shells_lock.unlock();
        for (self.shells.items) |shell| {
            if (!self.running.load(.acquire)) return;
            self.readShell(shell, env, &buffer);
        }
    }

    /// Records how the remote shell ended, while its libssh2 channel still exists. Loop thread only.
    /// The signal name is allocated by libssh2 and freed here after it has been copied.
    fn recordShellRemoteClose(self: *SshNativeSession, shell: *ShellChannel, channel: *c.LIBSSH2_CHANNEL) void {
        var name: [*c]u8 = null;
        var name_len: usize = 0;
        const rc = c.libssh2_channel_get_exit_signal(channel, &name, &name_len, null, null, null, null);
        const status = c.libssh2_channel_get_exit_status(channel);
        const signal: ?[]const u8 = if (rc == 0 and name != null) blk: {
            const bytes: [*]const u8 = @ptrCast(name);
            break :blk bytes[0..name_len];
        } else null;
        shell.exit_lock.lock();
        shell.exit.recordRemoteClose(signal, status);
        shell.exit_lock.unlock();
        if (name != null) c.libssh2_free(self.session, name);
    }

    fn readShell(self: *SshNativeSession, shell: *ShellChannel, env: ?*c.JNIEnv, buffer: []u8) void {
        const channel = shell.channel orelse return;
        // A newly opened shell can emit its greeting before the terminal worker
        // registers the JVM callback. Retain that startup window with a strict
        // cap so channel setup cannot lose the prompt or grow without bound.
        const have_callback = shell.hasCallback();
        while (self.running.load(.acquire)) {
            const count = c.libssh2_channel_read(channel, buffer.ptr, buffer.len);
            if (count > 0) {
                shell.output_lock.lock();
                const queued = ssh_output_buffer.append(
                    &shell.output_queue,
                    queue_allocator,
                    buffer[0..@intCast(count)],
                    have_callback,
                ) catch shell.output_queue.items.len;
                shell.output_lock.unlock();
                // Flush mid-drain once a batch has accumulated so a continuous
                // burst doesn't grow an unbounded queue or stall the JVM side;
                // the flush below the loop covers the tail.
                if (queued >= output_flush_threshold_bytes) {
                    shell.deliverOutput(env);
                }
            } else if (count == c.LIBSSH2_ERROR_EAGAIN) {
                break;
            } else if (count == 0 or count == c.LIBSSH2_ERROR_CHANNEL_CLOSED) {
                // The remote shell exited; only this channel ends. Capture the end while the
                // libssh2 channel still exists, because releasing it drops the exit status.
                shell.deliverOutput(env);
                self.recordShellRemoteClose(shell, channel);
                shell.open.store(false, .release);
                shell.channel = null;
                self.releaseChannel(channel);
                shell.deliverClosed(env);
                return;
            } else {
                self.running.store(false, .release);
                break;
            }
        }
        shell.deliverOutput(env);
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
                .write => if (current.shell) |shell| self.processWrite(shell, data_to_free orelse &[_]u8{}),
                .resize => if (current.shell) |shell| self.processResize(shell, current.cols, current.rows),
                .callback => if (current.callback) |callback| callback(self, current),
            }

            if (owned) {
                if (data_to_free) |data| self.allocator.free(data);
                self.allocator.destroy(current);
            }

            if (completion) |comp| comp.post();
        }
    }

    fn processWrite(self: *SshNativeSession, shell: *ShellChannel, data: []const u8) void {
        const channel = shell.channel orelse return;
        var written: usize = 0;
        while (written < data.len and self.running.load(.acquire)) {
            const result = c.libssh2_channel_write(channel, data[written..].ptr, data.len - written);
            if (result > 0) {
                written += @intCast(result);
            } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
                waitSocket(self.socket_fd, self.session) catch break;
            } else {
                break;
            }
        }
    }

    fn processResize(self: *SshNativeSession, shell: *ShellChannel, cols: c_int, rows: c_int) void {
        const channel = shell.channel orelse return;
        while (self.running.load(.acquire)) {
            const result = c.libssh2_channel_request_pty_size_ex(channel, cols, rows, 0, 0);
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
        var completion: Semaphore = .{};
        completion.init();
        defer completion.deinit();
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

    /// Never blocks: a close that would wait on the socket is retried by the
    /// loop, so a dead link can't stall other channels or a closeShell caller.
    fn releaseChannel(self: *SshNativeSession, channel: *c.LIBSSH2_CHANNEL) void {
        if (c.libssh2_channel_free(channel) != c.LIBSSH2_ERROR_EAGAIN) return;
        // If this append fails, session_free still reclaims the channel.
        self.closing_channels.append(queue_allocator, channel) catch {};
    }

    fn retryClosingChannels(self: *SshNativeSession) void {
        var index: usize = 0;
        while (index < self.closing_channels.items.len) {
            if (c.libssh2_channel_free(self.closing_channels.items[index]) == c.LIBSSH2_ERROR_EAGAIN) {
                index += 1;
            } else {
                _ = self.closing_channels.swapRemove(index);
            }
        }
    }

    fn unlinkShellLocked(self: *SshNativeSession, shell: *ShellChannel) bool {
        for (self.shells.items, 0..) |item, index| {
            if (item == shell) {
                _ = self.shells.swapRemove(index);
                return true;
            }
        }
        return false;
    }

    fn closeShellCallback(self: *SshNativeSession, command: *Command) void {
        const context: *CloseShellContext = @ptrCast(@alignCast(command.context.?));
        const shell = context.shell;
        self.shells_lock.lock();
        _ = self.unlinkShellLocked(shell);
        self.shells_lock.unlock();
        if (shell.channel) |channel| {
            shell.open.store(false, .release);
            shell.channel = null;
            self.releaseChannel(channel);
        }
        context.done = true;
    }

    fn sftpCloseCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpCloseContext = @ptrCast(@alignCast(command.context.?));
        const handle: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        // Complete an in-flight file prefetch before tearing the SFTP session
        // down so no orphaned request outlives it.
        if (self.sftp_staging_armed) {
            if (self.sftp_staging_read_handle) |file_handle| self.drainSftpPrefetch(file_handle);
        }
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_shutdown(handle);
            if (result == 0 or result != c.LIBSSH2_ERROR_EAGAIN) break;
            waitSocket(self.socket_fd, self.session) catch return;
        }
        self.sftpResetStaging();
    }

    fn appendJsonEscaped(output: *std.ArrayList(u8), allocator: std.mem.Allocator, str: []const u8) !void {
        for (str) |ch| {
            switch (ch) {
                '"' => try output.appendSlice(allocator, "\\\""),
                '\\' => try output.appendSlice(allocator, "\\\\"),
                '\n' => try output.appendSlice(allocator, "\\n"),
                '\r' => try output.appendSlice(allocator, "\\r"),
                '\t' => try output.appendSlice(allocator, "\\t"),
                else => {
                    if (ch < 0x20) {
                        var buf: [6]u8 = undefined;
                        const s = try std.fmt.bufPrint(&buf, "\\u00{x:0>2}", .{ch});
                        try output.appendSlice(allocator, s);
                    } else {
                        try output.append(allocator, ch);
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

                context.output.appendSlice(self.allocator, "{\"name\":\"") catch return;
                appendJsonEscaped(context.output, self.allocator, name) catch return;
                context.output.appendSlice(self.allocator, "\",\"path\":\"") catch return;
                appendJsonEscaped(context.output, self.allocator, context.path) catch return;
                if (!std.mem.endsWith(u8, context.path, "/")) {
                    context.output.appendSlice(self.allocator, "/") catch return;
                }
                appendJsonEscaped(context.output, self.allocator, name) catch return;
                const entry_suffix = std.fmt.allocPrint(self.allocator, "\",\"isDir\":{s},\"size\":{},\"permissions\":{},\"mtime\":{}}}", .{
                    if (is_dir) "true" else "false",
                    if (is_dir or (attributes.flags & c.LIBSSH2_SFTP_ATTR_SIZE) == 0) 0 else attributes.filesize,
                    if ((attributes.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) == 0) 0 else attributes.permissions,
                    if ((attributes.flags & c.LIBSSH2_SFTP_ATTR_ACMODTIME) == 0) 0 else attributes.mtime * 1000,
                }) catch return;
                defer self.allocator.free(entry_suffix);
                context.output.appendSlice(self.allocator, entry_suffix) catch return;
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

    fn sftpRenameCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpRenameContext = @ptrCast(@alignCast(command.context.?));
        const sftp: *c.LIBSSH2_SFTP = @ptrCast(@alignCast(context.handle));
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_rename_ex(
                sftp,
                context.old_path.ptr,
                @intCast(context.old_path.len),
                context.new_path.ptr,
                @intCast(context.new_path.len),
                c.LIBSSH2_SFTP_RENAME_OVERWRITE | c.LIBSSH2_SFTP_RENAME_ATOMIC | c.LIBSSH2_SFTP_RENAME_NATIVE,
            );
            if (result == 0) {
                context.success = true;
                return;
            }
            if (result != c.LIBSSH2_ERROR_EAGAIN) return;
            waitSocket(self.socket_fd, self.session) catch return;
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
        // Complete any in-flight prefetch for this handle before closing so
        // libssh2 never sees a request for a freed handle.
        self.drainSftpPrefetch(context.handle);
        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_close_handle(handle);
            if (result == 0 or result != c.LIBSSH2_ERROR_EAGAIN) break;
            waitSocket(self.socket_fd, self.session) catch return;
        }
        self.sftpResetStaging();
    }

    fn sftpStagingBuffer(self: *SshNativeSession) ?[]u8 {
        if (self.sftp_staging) |buffer| return buffer;
        const buffer = queue_allocator.alloc(u8, 256 * 1024) catch return null;
        self.sftp_staging = buffer;
        return buffer;
    }

    fn sftpResetStaging(self: *SshNativeSession) void {
        self.sftp_staging_handle = null;
        self.sftp_staging_read_handle = null;
        self.sftp_staging_len = 0;
        self.sftp_staging_armed = false;
        self.sftp_staging_eof = false;
        self.sftp_staging_error = false;
    }

    // Complete an armed (in-flight) prefetch read into staging if the socket
    // has data. Returns true once the staging slot holds a finished state
    // (bytes available, EOF, or error); returns false while still in flight.
    fn tryCompleteSftpPrefetch(self: *SshNativeSession, handle: *anyopaque) bool {
        if (!self.sftp_staging_armed) {
            return self.sftp_staging_len > 0 or self.sftp_staging_eof or self.sftp_staging_error;
        }
        const staging = self.sftpStagingBuffer() orelse {
            self.sftp_staging_error = true;
            self.sftp_staging_armed = false;
            return true;
        };
        const result = c.libssh2_sftp_read(@ptrCast(@alignCast(handle)), staging.ptr, staging.len);
        if (result > 0) {
            self.sftp_staging_len = @intCast(result);
        } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
            return false;
        } else if (result == 0) {
            self.sftp_staging_eof = true;
        } else {
            self.sftp_staging_error = true;
        }
        self.sftp_staging_armed = false;
        return true;
    }

    // Issue the next chunk read into staging without waiting for it, so it
    // completes while the JVM drains the chunk just served.
    fn armSftpPrefetch(self: *SshNativeSession, file_handle: *anyopaque) void {
        const staging = self.sftpStagingBuffer() orelse return;
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(file_handle));
        const result = c.libssh2_sftp_read(handle, staging.ptr, staging.len);
        if (result > 0) {
            self.sftp_staging_len = @intCast(result);
        } else if (result == c.LIBSSH2_ERROR_EAGAIN) {
            self.sftp_staging_armed = true;
            self.sftp_staging_read_handle = file_handle;
        } else if (result == 0) {
            self.sftp_staging_eof = true;
        } else {
            self.sftp_staging_error = true;
        }
    }

    // Block (on the loop thread, via waitSocket) until the armed prefetch
    // request is finished. Used before closing handles/sessions so libssh2
    // never sees an orphaned in-flight request.
    fn drainSftpPrefetch(self: *SshNativeSession, file_handle: *anyopaque) void {
        while (self.running.load(.acquire) and self.sftp_staging_armed) {
            if (self.tryCompleteSftpPrefetch(file_handle)) return;
            waitSocket(self.socket_fd, self.session) catch return;
        }
    }

    // Copy the staged chunk into the caller's buffer, keep any remainder, and
    // arm the next fetch. Returns false when staging held no data.
    fn serveSftpStaging(self: *SshNativeSession, context: *SftpReadContext, file_handle: *anyopaque) bool {
        if (self.sftp_staging_len == 0) return false;
        const available = self.sftp_staging.?[0..self.sftp_staging_len];
        const count = @min(available.len, context.buffer.len);
        @memcpy(context.buffer[0..count], available[0..count]);
        self.sftp_staging_len -= count;
        if (self.sftp_staging_len > 0) {
            std.mem.copyForwards(u8, available[0..self.sftp_staging_len], available[count..]);
        }
        context.result = @intCast(count);
        if (count > 0) self.armSftpPrefetch(file_handle);
        return true;
    }

    fn sftpReadCallback(self: *SshNativeSession, command: *Command) void {
        const context: *SftpReadContext = @ptrCast(@alignCast(command.context.?));
        const file_handle: *anyopaque = context.handle;
        const handle: *c.LIBSSH2_SFTP_HANDLE = @ptrCast(@alignCast(file_handle));

        if (self.sftp_staging_handle != file_handle) {
            // Handle changed (or closed): finish any in-flight request for the
            // old handle before discarding its staging state.
            if (self.sftp_staging_armed) {
                if (self.sftp_staging_read_handle) |old_handle| self.drainSftpPrefetch(old_handle);
            }
            self.sftpResetStaging();
            self.sftp_staging_handle = file_handle;
        }

        // Serve a finished prefetch chunk without touching the socket.
        if (self.serveSftpStaging(context, file_handle)) return;
        if (self.sftp_staging_eof) {
            context.result = 0;
            self.sftpResetStaging();
            return;
        }
        if (self.sftp_staging_error) {
            self.sftpResetStaging();
            return;
        }

        // A read request is already in flight toward staging: wait for it.
        while (self.sftp_staging_armed and self.running.load(.acquire)) {
            if (self.tryCompleteSftpPrefetch(file_handle)) {
                if (self.serveSftpStaging(context, file_handle)) return;
                if (self.sftp_staging_eof) {
                    context.result = 0;
                    self.sftpResetStaging();
                    return;
                }
                self.sftpResetStaging();
                return; // read error
            }
            waitSocket(self.socket_fd, self.session) catch return;
        }

        while (self.running.load(.acquire)) {
            const result = c.libssh2_sftp_read(handle, context.buffer.ptr, context.buffer.len);
            if (result >= 0) {
                context.result = @intCast(result);
                if (result > 0) self.armSftpPrefetch(file_handle);
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
