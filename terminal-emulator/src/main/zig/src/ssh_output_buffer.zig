const std = @import("std");

pub const pre_callback_capacity_bytes: usize = 64 * 1024;

pub fn append(
    queue: *std.ArrayList(u8),
    allocator: std.mem.Allocator,
    bytes: []const u8,
    callback_registered: bool,
) !usize {
    if (callback_registered) {
        try queue.appendSlice(allocator, bytes);
        return queue.items.len;
    }

    const available = pre_callback_capacity_bytes -| queue.items.len;
    try queue.appendSlice(allocator, bytes[0..@min(bytes.len, available)]);
    return queue.items.len;
}

test "retains shell greeting received before callback registration" {
    var queue: std.ArrayList(u8) = .empty;
    defer queue.deinit(std.testing.allocator);

    _ = try append(&queue, std.testing.allocator, "remote prompt$ ", false);

    try std.testing.expectEqualStrings("remote prompt$ ", queue.items);
}

test "pre-callback shell output is bounded" {
    var queue: std.ArrayList(u8) = .empty;
    defer queue.deinit(std.testing.allocator);
    const oversized = try std.testing.allocator.alloc(u8, pre_callback_capacity_bytes + 1);
    defer std.testing.allocator.free(oversized);
    @memset(oversized, 'x');

    _ = try append(&queue, std.testing.allocator, oversized, false);

    try std.testing.expectEqual(pre_callback_capacity_bytes, queue.items.len);
}
