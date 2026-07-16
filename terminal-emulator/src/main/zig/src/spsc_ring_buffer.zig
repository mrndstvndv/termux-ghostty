const std = @import("std");
const Atomic = std.atomic.Value;

pub const SpscRingBuffer = struct {
    buffer: []u8,
    write_ptr: Atomic(usize),
    read_ptr: Atomic(usize),
    capacity: usize,
    allocator: std.mem.Allocator,

    pub fn init(allocator: std.mem.Allocator, capacity: usize) !*SpscRingBuffer {
        // Ensure capacity is a power of 2 for fast masking
        const actual_capacity = std.math.ceilPowerOfTwo(usize, capacity) catch capacity;
        const buf = try allocator.alloc(u8, actual_capacity);
        
        const self = try allocator.create(SpscRingBuffer);
        self.* = .{
            .buffer = buf,
            .write_ptr = Atomic(usize).init(0),
            .read_ptr = Atomic(usize).init(0),
            .capacity = actual_capacity,
            .allocator = allocator,
        };
        return self;
    }

    pub fn deinit(self: *SpscRingBuffer) void {
        self.allocator.free(self.buffer);
        self.allocator.destroy(self);
    }

    /// Called by the Native SSH Thread (Producer)
    pub fn write(self: *SpscRingBuffer, data: []const u8) usize {
        const write_idx = self.write_ptr.load(.monotonic);
        const read_idx = self.read_ptr.load(.acquire); // Acquire reader progress
        
        const mask = self.capacity - 1;
        const available = self.capacity - (write_idx -% read_idx);
        const to_write = @min(data.len, available);
        if (to_write == 0) return 0;

        var i: usize = 0;
        while (i < to_write) : (i += 1) {
            self.buffer[(write_idx +% i) & mask] = data[i];
        }

        // Release the written bytes to the consumer thread
        self.write_ptr.store(write_idx +% to_write, .release);
        return to_write;
    }

    /// Called by the JVM Session Worker Thread (Consumer)
    pub fn readDirectToSession(self: *SpscRingBuffer, session: ?*anyopaque, append_fn: *const fn (?*anyopaque, ?[*]const u8, usize) callconv(.c) u32) u32 {
        const read_idx = self.read_ptr.load(.monotonic);
        const write_idx = self.write_ptr.load(.acquire); // Acquire writer progress
        
        const mask = self.capacity - 1;
        const count = write_idx -% read_idx;
        if (count == 0) return 0;

        // Read in up to two contiguous chunks (handling wrap-around)
        const start_offset = read_idx & mask;
        const chunk1_len = @min(count, self.capacity - start_offset);
        
        var result = append_fn(session, self.buffer[start_offset..].ptr, chunk1_len);
        
        if (chunk1_len < count) {
            const chunk2_len = count - chunk1_len;
            result |= append_fn(session, self.buffer[0..].ptr, chunk2_len);
        }

        // Update read pointer so producer knows space is free
        self.read_ptr.store(read_idx +% count, .release);
        return result;
    }
};
