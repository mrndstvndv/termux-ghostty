const std = @import("std");
const testing = std.testing;
const ghostty = @import("ghostty-vt");
const ghostty_log = @import("android_log.zig");

pub const append_result_screen_changed: u32 = 1 << 0;
pub const append_result_cursor_changed: u32 = 1 << 1;
pub const append_result_title_changed: u32 = 1 << 2;
pub const append_result_bell: u32 = 1 << 3;
pub const append_result_clipboard_copy: u32 = 1 << 4;
pub const append_result_colors_changed: u32 = 1 << 5;
pub const append_result_reply_bytes_available: u32 = 1 << 6;
pub const append_result_desktop_notification: u32 = 1 << 7;
pub const append_result_progress: u32 = 1 << 8;

pub const mode_cursor_keys_application: u32 = 1 << 0;
pub const mode_keypad_application: u32 = 1 << 1;
pub const mode_mouse_tracking: u32 = 1 << 2;
pub const mode_bracketed_paste: u32 = 1 << 3;
pub const mode_mouse_protocol_sgr: u32 = 1 << 4;

const ProgressState = enum(i32) {
    none = 0,
    set = 1,
    @"error" = 2,
    indeterminate = 3,
    pause = 4,
};

const text_style_bold: u16 = 1 << 0;
const text_style_italic: u16 = 1 << 1;
const text_style_underline: u16 = 1 << 2;
const text_style_blink: u16 = 1 << 3;
const text_style_inverse: u16 = 1 << 4;
const text_style_invisible: u16 = 1 << 5;
const text_style_strikethrough: u16 = 1 << 6;
const text_style_dim: u16 = 1 << 8;

const termux_default_foreground: u32 = 256;
const termux_default_background: u32 = 257;
const termux_default_cursor: u32 = 258;
const termux_palette_len: usize = 259;

const snapshot_magic: u32 = 0x54475832;
const snapshot_flag_full_rebuild: u32 = 1 << 0;
const snapshot_metadata_palette: u32 = 1 << 0;
const snapshot_metadata_render: u32 = 1 << 1;
const snapshot_metadata_mode_bits: u32 = 1 << 2;
const snapshot_header_bytes: usize = @sizeOf(u32) + @sizeOf(i32) + (5 * @sizeOf(u32));
const snapshot_render_metadata_bytes: usize = (3 * @sizeOf(i32)) + (2 * @sizeOf(u32));
const snapshot_mode_bits_bytes: usize = @sizeOf(u32);
const snapshot_row_header_bytes: usize = 2 * @sizeOf(u32);
const snapshot_cell_bytes: usize = @sizeOf(i32) + @sizeOf(u16) + 2 + @sizeOf(u64);
const viewport_link_magic: u32 = 0x54474c31;
const viewport_link_header_bytes: usize = @sizeOf(u32) + @sizeOf(i32) + (4 * @sizeOf(u32));
const viewport_link_record_bytes: usize = 5 * @sizeOf(u32);
const log_append_summary = false;
const log_fill_snapshot_summary = false;
const log_fill_snapshot_rows = false;
const perf_log_interval_frames: u64 = 120;
const slow_scroll_sync_ns: u64 = 1_000_000;
const slow_render_state_update_ns: u64 = 4_000_000;
const slow_snapshot_serialize_ns: u64 = 4_000_000;

const RenderStateTiming = struct {
    scroll_sync_ns: u64,
    render_state_update_ns: u64,
};

const SerializedRenderMetadata = struct {
    cursor_col: i32,
    cursor_row: i32,
    cursor_style: i32,
    cursor_visible: bool,
    reverse_video: bool,
};

const SnapshotMetadata = struct {
    flags: u32 = 0,
    render: SerializedRenderMetadata = .{
        .cursor_col = 0,
        .cursor_row = 0,
        .cursor_style = 0,
        .cursor_visible = false,
        .reverse_video = false,
    },
    mode_bits: u32 = 0,
    palette: [termux_palette_len]u32 = [_]u32{0} ** termux_palette_len,
};

const ViewportLinkRecord = struct {
    row: u32,
    start_column: u32,
    end_column_exclusive: u32,
    string_offset: u32,
    string_length: u32,
};

const ViewportLinkStringEntry = struct {
    uri: []const u8,
    offset: u32,
};

const ViewportCellLink = struct {
    id: ghostty.size.HyperlinkCountInt,
    uri: []const u8,
};

const FramePerfCounters = struct {
    frames: u64 = 0,
    full_rebuilds: u64 = 0,
    partial_updates: u64 = 0,
    dirty_rows_total: u64 = 0,
    max_dirty_rows: u32 = 0,
    scroll_sync_ns_total: u64 = 0,
    render_state_update_ns_total: u64 = 0,
    snapshot_serialize_ns_total: u64 = 0,

    fn record(
        self: *FramePerfCounters,
        dirty: ghostty.RenderState.Dirty,
        dirty_rows: u32,
        timing: RenderStateTiming,
        snapshot_serialize_ns: u64,
    ) void {
        self.frames += 1;
        switch (dirty) {
            .full => self.full_rebuilds += 1,
            .partial => self.partial_updates += 1,
            .false => {},
        }
        self.dirty_rows_total += dirty_rows;
        self.max_dirty_rows = @max(self.max_dirty_rows, dirty_rows);
        self.scroll_sync_ns_total += timing.scroll_sync_ns;
        self.render_state_update_ns_total += timing.render_state_update_ns;
        self.snapshot_serialize_ns_total += snapshot_serialize_ns;
    }
};

pub const Session = struct {
    alloc: std.mem.Allocator,
    terminal: ghostty.Terminal,
    handler: Handler,
    stream: ghostty.Stream(*Handler),
    render_state: ghostty.RenderState = .empty,
    pending_output: std.ArrayListUnmanaged(u8) = .empty,
    pending_title: std.ArrayListUnmanaged(u8) = .empty,
    pending_clipboard: std.ArrayListUnmanaged(u8) = .empty,
    pending_notification_title: std.ArrayListUnmanaged(u8) = .empty,
    pending_notification_body: std.ArrayListUnmanaged(u8) = .empty,
    scratch_utf16: std.ArrayListUnmanaged(u16) = .empty,
    scratch_cell_starts: std.ArrayListUnmanaged(i32) = .empty,
    scratch_cell_lengths: std.ArrayListUnmanaged(u16) = .empty,
    scratch_cell_widths: std.ArrayListUnmanaged(u8) = .empty,
    scratch_cell_styles: std.ArrayListUnmanaged(u64) = .empty,
    scratch_viewport_link_records: std.ArrayListUnmanaged(ViewportLinkRecord) = .empty,
    scratch_viewport_link_strings: std.ArrayListUnmanaged(ViewportLinkStringEntry) = .empty,
    colors_changed: bool = false,
    bell_pending: bool = false,
    progress_state: ProgressState = .none,
    progress_value: i32 = -1,
    progress_generation: u64 = 0,
    progress_changed: bool = false,
    viewport_top_row: i32 = 0,
    render_state_needs_update: bool = true,
    serialized_metadata_valid: bool = false,
    last_serialized_render_metadata: SerializedRenderMetadata = .{
        .cursor_col = 0,
        .cursor_row = 0,
        .cursor_style = 0,
        .cursor_visible = false,
        .reverse_video = false,
    },
    last_serialized_mode_bits: u32 = 0,
    last_serialized_palette: [termux_palette_len]u32 = [_]u32{0} ** termux_palette_len,
    frame_perf: FramePerfCounters = .{},
    mouse_pressed_buttons: u16 = 0,
    mouse_last_cell: ghostty.Coordinate = .{ .x = 0, .y = 0 },
    mouse_last_cell_valid: bool = false,

    fn deinit(self: *Session) void {
        self.render_state.deinit(self.alloc);
        self.stream.deinit();
        self.terminal.deinit(self.alloc);
        self.pending_output.deinit(self.alloc);
        self.pending_title.deinit(self.alloc);
        self.pending_clipboard.deinit(self.alloc);
        self.pending_notification_title.deinit(self.alloc);
        self.pending_notification_body.deinit(self.alloc);
        self.scratch_utf16.deinit(self.alloc);
        self.scratch_cell_starts.deinit(self.alloc);
        self.scratch_cell_lengths.deinit(self.alloc);
        self.scratch_cell_widths.deinit(self.alloc);
        self.scratch_cell_styles.deinit(self.alloc);
        self.scratch_viewport_link_records.deinit(self.alloc);
        self.scratch_viewport_link_strings.deinit(self.alloc);
    }

    fn resetTransientState(self: *Session) void {
        self.pending_output.clearRetainingCapacity();
        self.pending_title.clearRetainingCapacity();
        self.pending_clipboard.clearRetainingCapacity();
        self.clearPendingNotification();
        self.clearProgress();
        self.colors_changed = false;
        self.bell_pending = false;
        self.progress_changed = false;
        self.mouse_pressed_buttons = 0;
        self.mouse_last_cell = .{ .x = 0, .y = 0 };
        self.mouse_last_cell_valid = false;
    }

    fn modeBits(self: *const Session) u32 {
        var bits: u32 = 0;
        if (self.terminal.modes.get(.cursor_keys)) {
            bits |= mode_cursor_keys_application;
        }
        if (self.terminal.modes.get(.keypad_keys)) {
            bits |= mode_keypad_application;
        }
        if (self.terminal.flags.mouse_event != .none) {
            bits |= mode_mouse_tracking;
        }
        if (self.terminal.modes.get(.bracketed_paste)) {
            bits |= mode_bracketed_paste;
        }
        if (self.terminal.flags.mouse_format == .sgr or self.terminal.flags.mouse_format == .sgr_pixels) {
            bits |= mode_mouse_protocol_sgr;
        }
        return bits;
    }

    fn activeTranscriptRows(self: *const Session) usize {
        const total_rows = self.terminal.screens.active.pages.total_rows;
        const visible_rows: usize = self.terminal.rows;
        return total_rows -| visible_rows;
    }

    fn activeRows(self: *const Session) usize {
        return self.activeTranscriptRows() + self.terminal.rows;
    }

    fn cursorRow(self: *const Session) i32 {
        return @intCast(self.terminal.screens.active.cursor.y);
    }

    fn cursorCol(self: *const Session) i32 {
        return @intCast(self.terminal.screens.active.cursor.x);
    }

    fn cursorStyle(self: *const Session) i32 {
        return switch (self.terminal.screens.active.cursor.cursor_style) {
            .underline => 1,
            .bar => 2,
            else => 0,
        };
    }

    fn cursorVisible(self: *const Session) bool {
        return self.terminal.modes.get(.cursor_visible);
    }

    fn reverseVideo(self: *const Session) bool {
        return self.terminal.modes.get(.reverse_colors);
    }

    fn alternateBufferActive(self: *const Session) bool {
        return self.terminal.screens.active_key == .alternate;
    }

    fn clampExternalTopRow(self: *const Session, top_row: i32) i32 {
        if (top_row > 0) {
            return 0;
        }

        const history = std.math.cast(i32, self.activeTranscriptRows()) orelse std.math.maxInt(i32);
        return @max(-history, top_row);
    }

    fn absoluteScreenRowForExternal(self: *const Session, external_row: i32) u32 {
        const history = std.math.cast(i32, self.activeTranscriptRows()) orelse std.math.maxInt(i32);
        const rows = std.math.cast(i32, self.terminal.rows) orelse std.math.maxInt(i32);
        const max_row = history + rows - 1;
        const clamped = std.math.clamp(external_row + history, 0, max_row);
        return @intCast(clamped);
    }

    fn syncViewportToExternalTopRow(self: *Session, top_row: i32) void {
        const clamped_top_row = self.clampExternalTopRow(top_row);
        const absolute_row = self.absoluteScreenRowForExternal(clamped_top_row);
        self.terminal.scrollViewport(.{ .delta = 0 });
        self.terminal.screens.active.scroll(.{ .row = absolute_row });
    }

    fn markRenderStateDirty(self: *Session) void {
        self.render_state_needs_update = true;
    }

    fn setViewportTopRow(self: *Session, top_row: i32) i32 {
        const clamped_top_row = self.clampExternalTopRow(top_row);
        if (self.viewport_top_row == clamped_top_row) {
            return self.viewport_top_row;
        }

        self.viewport_top_row = clamped_top_row;
        self.markRenderStateDirty();
        return self.viewport_top_row;
    }

    fn requestFullSnapshotRefresh(self: *Session) void {
        self.render_state.dirty = .full;
        self.serialized_metadata_valid = false;
    }

    fn updateRenderStateIfNeeded(self: *Session) !RenderStateTiming {
        if (!self.render_state_needs_update) {
            return .{
                .scroll_sync_ns = 0,
                .render_state_update_ns = 0,
            };
        }

        const scroll_start_ns = std.time.nanoTimestamp();
        self.syncViewportToExternalTopRow(self.viewport_top_row);
        const scroll_sync_ns = elapsedNanos(scroll_start_ns);

        const render_update_start_ns = std.time.nanoTimestamp();
        try self.render_state.update(self.alloc, &self.terminal);
        self.render_state_needs_update = false;
        return .{
            .scroll_sync_ns = scroll_sync_ns,
            .render_state_update_ns = elapsedNanos(render_update_start_ns),
        };
    }

    fn dirtyRowCount(self: *const Session) u32 {
        return switch (self.render_state.dirty) {
            .false => 0,
            .full => std.math.cast(u32, self.render_state.rows) orelse std.math.maxInt(u32),
            .partial => blk: {
                const row_data = self.render_state.row_data.slice();
                var count: u32 = 0;
                for (row_data.items(.dirty)) |dirty| {
                    if (dirty) count += 1;
                }
                break :blk count;
            },
        };
    }

    fn partialDirtyRowCount(self: *const Session) u32 {
        return switch (self.render_state.dirty) {
            .partial => self.dirtyRowCount(),
            else => 0,
        };
    }

    fn shouldSerializeRow(self: *const Session, row_index: usize) bool {
        return switch (self.render_state.dirty) {
            .full => true,
            .partial => self.render_state.row_data.slice().items(.dirty)[row_index],
            .false => false,
        };
    }

    fn clearRenderStateDirty(self: *Session) void {
        self.render_state.dirty = .false;
        const row_data = self.render_state.row_data.slice();
        for (row_data.items(.dirty)) |*dirty| {
            dirty.* = false;
        }
    }

    fn logSnapshotPerfIfNeeded(
        self: *Session,
        top_row: i32,
        required_bytes: usize,
        dirty_rows: u32,
        timing: RenderStateTiming,
        snapshot_serialize_ns: u64,
    ) void {
        self.frame_perf.record(self.render_state.dirty, dirty_rows, timing, snapshot_serialize_ns);

        const slow = timing.scroll_sync_ns >= slow_scroll_sync_ns
            or timing.render_state_update_ns >= slow_render_state_update_ns
            or snapshot_serialize_ns >= slow_snapshot_serialize_ns;
        const periodic = ghostty_log.enabled() and (self.frame_perf.frames % perf_log_interval_frames) == 0;
        if (!slow and !periodic) {
            return;
        }

        const dirty_state = switch (self.render_state.dirty) {
            .false => "false",
            .partial => "partial",
            .full => "full",
        };
        const frame_count = self.frame_perf.frames;
        const avg_dirty_rows = if (frame_count == 0) 0 else self.frame_perf.dirty_rows_total / frame_count;
        const avg_scroll_us = if (frame_count == 0) 0 else (self.frame_perf.scroll_sync_ns_total / frame_count) / 1_000;
        const avg_render_update_us = if (frame_count == 0) 0 else (self.frame_perf.render_state_update_ns_total / frame_count) / 1_000;
        const avg_serialize_us = if (frame_count == 0) 0 else (self.frame_perf.snapshot_serialize_ns_total / frame_count) / 1_000;

        if (slow) {
            ghostty_log.warn("core perf fillSnapshot session=0x{x} topRow={} dirty={s} dirtyRows={} requiredBytes={} scrollUs={} renderUpdateUs={} serializeUs={} avgDirtyRows={} full={} partial={}", .{
                @intFromPtr(self),
                top_row,
                dirty_state,
                dirty_rows,
                required_bytes,
                timing.scroll_sync_ns / 1_000,
                timing.render_state_update_ns / 1_000,
                snapshot_serialize_ns / 1_000,
                avg_dirty_rows,
                self.frame_perf.full_rebuilds,
                self.frame_perf.partial_updates,
            });
            return;
        }

        ghostty_log.debug("core perf fillSnapshot session=0x{x} frames={} avgDirtyRows={} maxDirtyRows={} full={} partial={} avgScrollUs={} avgRenderUpdateUs={} avgSerializeUs={} lastTopRow={} lastDirty={s} lastDirtyRows={} requiredBytes={}", .{
            @intFromPtr(self),
            frame_count,
            avg_dirty_rows,
            self.frame_perf.max_dirty_rows,
            self.frame_perf.full_rebuilds,
            self.frame_perf.partial_updates,
            avg_scroll_us,
            avg_render_update_us,
            avg_serialize_us,
            top_row,
            dirty_state,
            dirty_rows,
            required_bytes,
        });
    }

    fn currentRenderMetadata(self: *const Session) SerializedRenderMetadata {
        return .{
            .cursor_col = self.cursorCol(),
            .cursor_row = self.cursorRow(),
            .cursor_style = self.cursorStyle(),
            .cursor_visible = self.cursorVisible(),
            .reverse_video = self.reverseVideo(),
        };
    }

    fn buildSnapshotMetadata(self: *const Session, full_rebuild: bool) SnapshotMetadata {
        var metadata: SnapshotMetadata = .{};
        metadata.render = self.currentRenderMetadata();
        metadata.mode_bits = self.modeBits();

        if (full_rebuild or !self.serialized_metadata_valid or !std.meta.eql(metadata.render, self.last_serialized_render_metadata)) {
            metadata.flags |= snapshot_metadata_render;
        }
        if (full_rebuild or !self.serialized_metadata_valid or metadata.mode_bits != self.last_serialized_mode_bits) {
            metadata.flags |= snapshot_metadata_mode_bits;
        }

        fillSnapshotPalette(&metadata.palette, self);
        if (full_rebuild or !self.serialized_metadata_valid or !std.mem.eql(u32, metadata.palette[0..], self.last_serialized_palette[0..])) {
            metadata.flags |= snapshot_metadata_palette;
        }

        return metadata;
    }

    fn commitSerializedMetadata(self: *Session, metadata: *const SnapshotMetadata) void {
        if ((metadata.flags & snapshot_metadata_render) != 0) {
            self.last_serialized_render_metadata = metadata.render;
        }
        if ((metadata.flags & snapshot_metadata_mode_bits) != 0) {
            self.last_serialized_mode_bits = metadata.mode_bits;
        }
        if ((metadata.flags & snapshot_metadata_palette) != 0) {
            self.last_serialized_palette = metadata.palette;
        }
        self.serialized_metadata_valid = true;
    }

    fn snapshotRequiredBytes(self: *Session, metadata: *const SnapshotMetadata) !usize {
        const rows: usize = self.render_state.rows;

        var total: usize = snapshot_header_bytes;
        if ((metadata.flags & snapshot_metadata_palette) != 0) {
            total += termux_palette_len * @sizeOf(u32);
        }
        if ((metadata.flags & snapshot_metadata_render) != 0) {
            total += snapshot_render_metadata_bytes;
        }
        if ((metadata.flags & snapshot_metadata_mode_bits) != 0) {
            total += snapshot_mode_bits_bytes;
        }
        total += @as(usize, self.partialDirtyRowCount()) * @sizeOf(u32);
        for (0..rows) |row_index| {
            if (!self.shouldSerializeRow(row_index)) {
                continue;
            }

            total += try self.snapshotRowRequiredBytes(row_index);
        }

        return total;
    }

    fn snapshotRowRequiredBytes(self: *const Session, row_index: usize) !usize {
        const cols: usize = self.render_state.cols;
        const row_data = self.render_state.row_data.slice();
        const row_cells = row_data.items(.cells);
        const cells = row_cells[row_index].slice();
        const raw_cells = cells.items(.raw);
        const grapheme_cells = cells.items(.grapheme);

        var total: usize = snapshot_row_header_bytes + (cols * snapshot_cell_bytes);
        for (0..cols) |column| {
            const raw_cell = raw_cells[column];
            if (raw_cell.wide == .spacer_tail or raw_cell.wide == .spacer_head) {
                continue;
            }
            if (!raw_cell.hasText()) {
                continue;
            }

            total += utf16LengthForCodepoint(raw_cell.codepoint()) * @sizeOf(u16);
            if (!raw_cell.hasGrapheme()) {
                continue;
            }

            for (grapheme_cells[column]) |cp| {
                total += utf16LengthForCodepoint(cp) * @sizeOf(u16);
            }
        }

        return total;
    }

    fn writeSnapshotRow(self: *Session, writer: *BufferWriter, row_index: usize) !void {
        self.scratch_utf16.clearRetainingCapacity();
        self.scratch_cell_starts.clearRetainingCapacity();
        self.scratch_cell_lengths.clearRetainingCapacity();
        self.scratch_cell_widths.clearRetainingCapacity();
        self.scratch_cell_styles.clearRetainingCapacity();

        const row_data = self.render_state.row_data.slice();
        const row_rows = row_data.items(.raw);
        const row_pins = row_data.items(.pin);
        const row_cells = row_data.items(.cells);
        const row_pin = row_pins[row_index];
        const cells = row_cells[row_index].slice();
        const raw_cells = cells.items(.raw);
        const grapheme_cells = cells.items(.grapheme);
        if (raw_cells.len < self.render_state.cols or grapheme_cells.len < self.render_state.cols) {
            ghostty_log.err("core fillSnapshot cell data mismatch session=0x{x} row={} renderCols={} raw={} grapheme={}", .{
                @intFromPtr(self),
                row_index,
                self.render_state.cols,
                raw_cells.len,
                grapheme_cells.len,
            });
            return error.InvalidSnapshotRow;
        }
        if (log_fill_snapshot_rows) {
            ghostty_log.debug("core fillSnapshot row session=0x{x} row={} wrap={} managedTextCols={}", .{
                @intFromPtr(self),
                row_index,
                row_rows[row_index].wrap,
                raw_cells.len,
            });
        }

        for (0..self.render_state.cols) |column| {
            const raw_cell = raw_cells[column];
            const effective_style: ghostty.Style = if (raw_cell.hasStyling()) row_pin.style(&raw_cell) else .{};
            const width = snapshotCellWidth(raw_cell);
            try self.scratch_cell_starts.append(self.alloc, std.math.cast(i32, self.scratch_utf16.items.len) orelse return error.InvalidSnapshotRow);
            try self.scratch_cell_widths.append(self.alloc, width);

            if (width == 0 or !raw_cell.hasText()) {
                try self.scratch_cell_lengths.append(self.alloc, 0);
                try self.scratch_cell_styles.append(self.alloc, encodeTermuxStyle(raw_cell, effective_style));
                continue;
            }

            try appendCodepointUtf16(&self.scratch_utf16, self.alloc, raw_cell.codepoint());
            if (raw_cell.hasGrapheme()) {
                for (grapheme_cells[column]) |cp| {
                    try appendCodepointUtf16(&self.scratch_utf16, self.alloc, cp);
                }
            }

            const length = self.scratch_utf16.items.len - @as(usize, @intCast(self.scratch_cell_starts.items[self.scratch_cell_starts.items.len - 1]));
            try self.scratch_cell_lengths.append(self.alloc, std.math.cast(u16, length) orelse return error.InvalidSnapshotRow);
            try self.scratch_cell_styles.append(self.alloc, encodeTermuxStyle(raw_cell, effective_style));
        }

        if (self.scratch_cell_starts.items.len != self.render_state.cols
            or self.scratch_cell_lengths.items.len != self.render_state.cols
            or self.scratch_cell_widths.items.len != self.render_state.cols
            or self.scratch_cell_styles.items.len != self.render_state.cols)
        {
            ghostty_log.err(
                "core fillSnapshot cell scratch mismatch session=0x{x} row={} cols={} starts={} lengths={} widths={} styles={}",
                .{
                    @intFromPtr(self),
                    row_index,
                    self.render_state.cols,
                    self.scratch_cell_starts.items.len,
                    self.scratch_cell_lengths.items.len,
                    self.scratch_cell_widths.items.len,
                    self.scratch_cell_styles.items.len,
                },
            );
            return error.InvalidSnapshotRow;
        }

        try writer.writeU32(std.math.cast(u32, self.scratch_utf16.items.len) orelse return error.InvalidSnapshotRow);
        try writer.writeU32(@intFromBool(row_rows[row_index].wrap));
        for (0..self.render_state.cols) |column| {
            try writer.writeI32(self.scratch_cell_starts.items[column]);
            try writer.writeU16(self.scratch_cell_lengths.items[column]);
            try writer.writeU8(self.scratch_cell_widths.items[column]);
            try writer.writeU8(0);
            try writer.writeU64(self.scratch_cell_styles.items[column]);
        }
        for (self.scratch_utf16.items) |unit| {
            try writer.writeU16(unit);
        }
    }

    fn buildViewportLinkScratch(self: *Session) !usize {
        self.scratch_viewport_link_records.clearRetainingCapacity();
        self.scratch_viewport_link_strings.clearRetainingCapacity();

        const row_data = self.render_state.row_data.slice();
        const row_pins = row_data.items(.pin);
        const row_cells = row_data.items(.cells);
        if (row_pins.len < self.render_state.rows or row_cells.len < self.render_state.rows) {
            ghostty_log.err("core fillViewportLinks row data mismatch session=0x{x} renderRows={} pinRows={} cellRows={}", .{
                @intFromPtr(self),
                self.render_state.rows,
                row_pins.len,
                row_cells.len,
            });
            return error.InvalidViewportLinks;
        }

        var string_table_bytes: usize = 0;
        const cols: usize = self.render_state.cols;
        for (0..self.render_state.rows) |row_index| {
            const pin = row_pins[row_index];
            const page = &pin.node.data;
            const cells = row_cells[row_index].slice().items(.raw);
            if (cells.len < cols) {
                ghostty_log.err("core fillViewportLinks cell data mismatch session=0x{x} row={} renderCols={} raw={}", .{
                    @intFromPtr(self),
                    row_index,
                    self.render_state.cols,
                    cells.len,
                });
                return error.InvalidViewportLinks;
            }

            var active_link: ?ViewportCellLink = null;
            var active_segment_start: usize = 0;
            var column: usize = 0;
            while (column < cols) {
                const raw_cell = cells[column];
                const display_width = snapshotCellWidth(raw_cell);
                if (display_width == 0) {
                    column += 1;
                    continue;
                }

                const link = viewportCellLinkForRowCell(page, pin.y, column, raw_cell);
                if (active_link) |current| {
                    if (link == null or link.?.id != current.id) {
                        try self.appendViewportLinkRecord(row_index, active_segment_start, column, current.uri, &string_table_bytes);
                        active_link = null;
                    }
                }
                if (active_link == null and link != null) {
                    active_link = link;
                    active_segment_start = column;
                }

                column += display_width;
            }

            if (active_link) |current| {
                try self.appendViewportLinkRecord(row_index, active_segment_start, cols, current.uri, &string_table_bytes);
            }
        }

        return string_table_bytes;
    }

    fn appendViewportLinkRecord(
        self: *Session,
        row_index: usize,
        start_column: usize,
        end_column_exclusive: usize,
        uri: []const u8,
        string_table_bytes: *usize,
    ) !void {
        if (end_column_exclusive <= start_column or uri.len == 0) {
            return;
        }

        const string_ref = try self.internViewportLinkUri(uri, string_table_bytes);
        try self.scratch_viewport_link_records.append(self.alloc, .{
            .row = std.math.cast(u32, row_index) orelse return error.InvalidViewportLinks,
            .start_column = std.math.cast(u32, start_column) orelse return error.InvalidViewportLinks,
            .end_column_exclusive = std.math.cast(u32, end_column_exclusive) orelse return error.InvalidViewportLinks,
            .string_offset = string_ref.offset,
            .string_length = string_ref.length,
        });
    }

    fn internViewportLinkUri(
        self: *Session,
        uri: []const u8,
        string_table_bytes: *usize,
    ) !struct { offset: u32, length: u32 } {
        for (self.scratch_viewport_link_strings.items) |entry| {
            if (std.mem.eql(u8, entry.uri, uri)) {
                return .{
                    .offset = entry.offset,
                    .length = std.math.cast(u32, entry.uri.len) orelse return error.InvalidViewportLinks,
                };
            }
        }

        const offset = std.math.cast(u32, string_table_bytes.*) orelse return error.InvalidViewportLinks;
        try self.scratch_viewport_link_strings.append(self.alloc, .{
            .uri = uri,
            .offset = offset,
        });
        string_table_bytes.* += uri.len;
        return .{
            .offset = offset,
            .length = std.math.cast(u32, uri.len) orelse return error.InvalidViewportLinks,
        };
    }

    fn appendPendingOutput(self: *Session, bytes: []const u8) !void {
        if (bytes.len == 0) {
            return;
        }

        try self.pending_output.appendSlice(self.alloc, bytes);
        if (ghostty_log.enabled()) {
            ghostty_log.debug("core queued reply bytes={} pending={}", .{ bytes.len, self.pending_output.items.len });
        }
    }

    fn clearPendingNotification(self: *Session) void {
        self.pending_notification_title.clearRetainingCapacity();
        self.pending_notification_body.clearRetainingCapacity();
    }

    fn replacePendingTitle(self: *Session, title: []const u8) !void {
        self.pending_title.clearRetainingCapacity();
        try self.pending_title.appendSlice(self.alloc, title);
        ghostty_log.debug("core queued title bytes={}", .{ title.len });
    }

    fn replacePendingClipboard(self: *Session, text: []const u8) !void {
        self.pending_clipboard.clearRetainingCapacity();
        try self.pending_clipboard.appendSlice(self.alloc, text);
        ghostty_log.debug("core queued clipboard bytes={}", .{ text.len });
    }

    fn replacePendingNotification(self: *Session, title: []const u8, body: []const u8) !void {
        self.clearPendingNotification();
        try self.pending_notification_title.appendSlice(self.alloc, title);
        try self.pending_notification_body.appendSlice(self.alloc, body);
        ghostty_log.debug("core queued desktop notification titleBytes={} bodyBytes={}", .{ title.len, body.len });
    }

    fn setProgress(self: *Session, state: ProgressState, progress: ?u8) void {
        self.progress_state = state;
        self.progress_value = if (progress) |value| value else -1;
        self.progress_generation +%= 1;
        self.progress_changed = true;
        ghostty_log.debug("core progress state={} value={} generation={}", .{ @intFromEnum(state), self.progress_value, self.progress_generation });
    }

    fn clearProgress(self: *Session) void {
        if (self.progress_state == .none and self.progress_value == -1) {
            return;
        }

        self.progress_state = .none;
        self.progress_value = -1;
        self.progress_generation +%= 1;
        self.progress_changed = true;
        ghostty_log.debug("core progress cleared generation={}", .{ self.progress_generation });
    }

    fn consumeOwnedUtf8(buffer: *std.ArrayListUnmanaged(u8), alloc: std.mem.Allocator) ?[:0]u8 {
        if (buffer.items.len == 0) {
            return null;
        }

        const owned = alloc.allocSentinel(u8, buffer.items.len, 0) catch return null;
        @memcpy(owned[0..buffer.items.len], buffer.items);
        buffer.clearRetainingCapacity();
        return owned;
    }

    fn consumeTitle(self: *Session) ?[:0]u8 {
        return consumeOwnedUtf8(&self.pending_title, self.alloc);
    }

    fn consumeClipboard(self: *Session) ?[:0]u8 {
        return consumeOwnedUtf8(&self.pending_clipboard, self.alloc);
    }

    fn consumeNotificationTitle(self: *Session) ?[:0]u8 {
        return consumeOwnedUtf8(&self.pending_notification_title, self.alloc);
    }

    fn consumeNotificationBody(self: *Session) ?[:0]u8 {
        return consumeOwnedUtf8(&self.pending_notification_body, self.alloc);
    }

    fn copyOwnedUtf8(alloc: std.mem.Allocator, text: [:0]const u8) ?[:0]u8 {
        const owned = alloc.allocSentinel(u8, text.len, 0) catch return null;
        @memcpy(owned[0..text.len], text);
        return owned;
    }

    fn drainPendingOutput(self: *Session, buffer: []u8) usize {
        if (buffer.len == 0 or self.pending_output.items.len == 0) {
            return 0;
        }

        const written = @min(buffer.len, self.pending_output.items.len);
        @memcpy(buffer[0..written], self.pending_output.items[0..written]);
        if (written == self.pending_output.items.len) {
            self.pending_output.clearRetainingCapacity();
            ghostty_log.debug("core drained pending output wrote={} remaining=0", .{ written });
            return written;
        }

        const remaining = self.pending_output.items.len - written;
        std.mem.copyForwards(u8, self.pending_output.items[0..remaining], self.pending_output.items[written..]);
        self.pending_output.shrinkRetainingCapacity(remaining);
        ghostty_log.debug("core drained pending output wrote={} remaining={}", .{ written, remaining });
        return written;
    }

    fn hasPressedMouseButtons(self: *const Session) bool {
        return self.mouse_pressed_buttons != 0;
    }

    fn updatePressedMouseButtonsBeforeEncode(
        self: *Session,
        action: ghostty.input.MouseAction,
        button: ?ghostty.input.MouseButton,
    ) void {
        const resolved_button = button orelse return;
        if (resolved_button == .four or resolved_button == .five or resolved_button == .six or resolved_button == .seven) {
            return;
        }

        const shift: u4 = @intCast(@intFromEnum(resolved_button));
        const mask: u16 = @as(u16, 1) << shift;
        switch (action) {
            .press => self.mouse_pressed_buttons |= mask,
            .release => self.mouse_pressed_buttons &= ~mask,
            .motion => {},
        }
    }

    fn transcriptPoint(self: *const Session, column: i32, row: i32) ?ghostty.Point {
        const cols = std.math.cast(i32, self.terminal.cols) orelse return null;
        if (column < 0 or column >= cols) {
            return null;
        }

        return .{ .screen = .{
            .x = @intCast(column),
            .y = self.absoluteScreenRowForExternal(row),
        } };
    }

    fn selectionForExternalRange(
        self: *const Session,
        start_column: i32,
        start_row: i32,
        end_column: i32,
        end_row: i32,
    ) ?ghostty.Selection {
        const cols = std.math.cast(i32, self.terminal.cols) orelse return null;
        if (cols <= 0) {
            return null;
        }

        const clamped_start_col = std.math.clamp(start_column, 0, cols - 1);
        const clamped_end_col = std.math.clamp(end_column, 0, cols - 1);
        const start_pin = self.terminal.screens.active.pages.pin(.{ .screen = .{
            .x = @intCast(clamped_start_col),
            .y = self.absoluteScreenRowForExternal(start_row),
        } }) orelse return null;
        const end_pin = self.terminal.screens.active.pages.pin(.{ .screen = .{
            .x = @intCast(clamped_end_col),
            .y = self.absoluteScreenRowForExternal(end_row),
        } }) orelse return null;
        return ghostty.Selection.init(start_pin, end_pin, false);
    }

    fn selectedText(
        self: *Session,
        start_column: i32,
        start_row: i32,
        end_column: i32,
        end_row: i32,
        trim: bool,
    ) ?[:0]u8 {
        const selection = self.selectionForExternalRange(start_column, start_row, end_column, end_row) orelse return null;
        const text = self.terminal.screens.active.selectionString(self.alloc, .{
            .sel = selection,
            .trim = trim,
        }) catch return null;
        return copyOwnedUtf8(self.alloc, text);
    }

    fn wordAtLocation(self: *Session, column: i32, row: i32) ?[:0]u8 {
        const point = self.transcriptPoint(column, row) orelse return null;
        const pin = self.terminal.screens.active.pages.pin(point) orelse return null;
        const selection = self.terminal.screens.active.selectWord(pin, &.{ ' ', '\t' }) orelse return null;
        const text = self.terminal.screens.active.selectionString(self.alloc, .{
            .sel = selection,
            .trim = true,
        }) catch return null;
        return copyOwnedUtf8(self.alloc, text);
    }

    fn transcriptText(self: *Session, join_lines: bool, trim: bool) ?[:0]u8 {
        var writer: std.Io.Writer.Allocating = .init(self.alloc);
        defer writer.deinit();

        const top_left = self.terminal.screens.active.pages.getTopLeft(.screen);
        const bottom_right = self.terminal.screens.active.pages.getBottomRight(.screen) orelse return null;
        self.terminal.screens.active.dumpString(&writer.writer, .{
            .tl = top_left,
            .br = bottom_right,
            .unwrap = join_lines,
        }) catch return null;

        const raw = writer.toOwnedSlice() catch return null;
        errdefer self.alloc.free(raw);

        if (!trim) {
            const with_sentinel = self.alloc.allocSentinel(u8, raw.len, 0) catch return null;
            @memcpy(with_sentinel[0..raw.len], raw);
            self.alloc.free(raw);
            return with_sentinel;
        }

        const trimmed = std.mem.trim(u8, raw, &std.ascii.whitespace);
        const result = self.alloc.allocSentinel(u8, trimmed.len, 0) catch return null;
        @memcpy(result[0..trimmed.len], trimmed);
        self.alloc.free(raw);
        return result;
    }
};

fn elapsedNanos(start_ns: i128) u64 {
    return std.math.cast(u64, std.time.nanoTimestamp() - start_ns) orelse 0;
}

const Handler = struct {
    session: *Session,

    pub fn deinit(self: *Handler) void {
        _ = self;
    }

    pub fn vt(
        self: *Handler,
        comptime action: ghostty.StreamAction.Tag,
        value: ghostty.StreamAction.Value(action),
    ) void {
        self.vtFallible(action, value) catch {};
    }

    fn vtFallible(
        self: *Handler,
        comptime action: ghostty.StreamAction.Tag,
        value: ghostty.StreamAction.Value(action),
    ) !void {
        switch (action) {
            .print => try self.session.terminal.print(value.cp),
            .print_repeat => try self.session.terminal.printRepeat(value),
            .bell => self.session.bell_pending = true,
            .backspace => self.session.terminal.backspace(),
            .horizontal_tab => self.horizontalTab(value),
            .horizontal_tab_back => self.horizontalTabBack(value),
            .linefeed => try self.session.terminal.index(),
            .carriage_return => self.session.terminal.carriageReturn(),
            .enquiry => {},
            .invoke_charset => self.session.terminal.invokeCharset(value.bank, value.charset, value.locking),
            .cursor_up => self.session.terminal.cursorUp(value.value),
            .cursor_down => self.session.terminal.cursorDown(value.value),
            .cursor_left => self.session.terminal.cursorLeft(value.value),
            .cursor_right => self.session.terminal.cursorRight(value.value),
            .cursor_pos => self.session.terminal.setCursorPos(value.row, value.col),
            .cursor_col => self.session.terminal.setCursorPos(self.session.terminal.screens.active.cursor.y + 1, value.value),
            .cursor_row => self.session.terminal.setCursorPos(value.value, self.session.terminal.screens.active.cursor.x + 1),
            .cursor_col_relative => self.session.terminal.setCursorPos(
                self.session.terminal.screens.active.cursor.y + 1,
                self.session.terminal.screens.active.cursor.x + 1 +| value.value,
            ),
            .cursor_row_relative => self.session.terminal.setCursorPos(
                self.session.terminal.screens.active.cursor.y + 1 +| value.value,
                self.session.terminal.screens.active.cursor.x + 1,
            ),
            .cursor_style => self.setCursorStyle(value),
            .erase_display_below => self.session.terminal.eraseDisplay(.below, value),
            .erase_display_above => self.session.terminal.eraseDisplay(.above, value),
            .erase_display_complete => self.session.terminal.eraseDisplay(.complete, value),
            .erase_display_scrollback => self.session.terminal.eraseDisplay(.scrollback, value),
            .erase_display_scroll_complete => self.session.terminal.eraseDisplay(.scroll_complete, value),
            .erase_line_right => self.session.terminal.eraseLine(.right, value),
            .erase_line_left => self.session.terminal.eraseLine(.left, value),
            .erase_line_complete => self.session.terminal.eraseLine(.complete, value),
            .erase_line_right_unless_pending_wrap => self.session.terminal.eraseLine(.right_unless_pending_wrap, value),
            .delete_chars => self.session.terminal.deleteChars(value),
            .erase_chars => self.session.terminal.eraseChars(value),
            .insert_lines => self.session.terminal.insertLines(value),
            .insert_blanks => self.session.terminal.insertBlanks(value),
            .delete_lines => self.session.terminal.deleteLines(value),
            .scroll_up => try self.session.terminal.scrollUp(value),
            .scroll_down => self.session.terminal.scrollDown(value),
            .tab_clear_current => self.session.terminal.tabClear(.current),
            .tab_clear_all => self.session.terminal.tabClear(.all),
            .tab_set => self.session.terminal.tabSet(),
            .tab_reset => self.session.terminal.tabReset(),
            .index => try self.session.terminal.index(),
            .next_line => {
                try self.session.terminal.index();
                self.session.terminal.carriageReturn();
            },
            .reverse_index => self.session.terminal.reverseIndex(),
            .full_reset => self.session.terminal.fullReset(),
            .set_mode => try self.setMode(value.mode, true),
            .reset_mode => try self.setMode(value.mode, false),
            .save_mode => self.session.terminal.modes.save(value.mode),
            .restore_mode => {
                const enabled = self.session.terminal.modes.restore(value.mode);
                try self.setMode(value.mode, enabled);
            },
            .request_mode => try self.requestMode(value.mode),
            .request_mode_unknown => try self.requestModeUnknown(value.mode, value.ansi),
            .top_and_bottom_margin => self.session.terminal.setTopAndBottomMargin(value.top_left, value.bottom_right),
            .left_and_right_margin => self.session.terminal.setLeftAndRightMargin(value.top_left, value.bottom_right),
            .left_and_right_margin_ambiguous => {
                if (self.session.terminal.modes.get(.enable_left_and_right_margin)) {
                    self.session.terminal.setLeftAndRightMargin(0, 0);
                } else {
                    self.session.terminal.saveCursor();
                }
            },
            .save_cursor => self.session.terminal.saveCursor(),
            .restore_cursor => self.session.terminal.restoreCursor(),
            .modify_key_format => self.session.terminal.flags.modify_other_keys_2 = value == .other_keys_numeric,
            .mouse_shift_capture => self.session.terminal.flags.mouse_shift_capture = if (value) .true else .false,
            .protected_mode_off => self.session.terminal.setProtectedMode(.off),
            .protected_mode_iso => self.session.terminal.setProtectedMode(.iso),
            .protected_mode_dec => self.session.terminal.setProtectedMode(.dec),
            .size_report => try self.sendSizeReport(value),
            .xtversion => try self.session.appendPendingOutput("\x1BP>|ghostty termux\x1B\\"),
            .device_attributes => try self.deviceAttributes(value),
            .device_status => try self.deviceStatus(value.request),
            .kitty_keyboard_query => try self.queryKittyKeyboard(),
            .kitty_keyboard_push => self.session.terminal.screens.active.kitty_keyboard.push(value.flags),
            .kitty_keyboard_pop => self.session.terminal.screens.active.kitty_keyboard.pop(@intCast(value)),
            .kitty_keyboard_set => self.session.terminal.screens.active.kitty_keyboard.set(.set, value.flags),
            .kitty_keyboard_set_or => self.session.terminal.screens.active.kitty_keyboard.set(.@"or", value.flags),
            .kitty_keyboard_set_not => self.session.terminal.screens.active.kitty_keyboard.set(.not, value.flags),
            .dcs_hook, .dcs_put, .dcs_unhook => {},
            .apc_start, .apc_end, .apc_put => {},
            .end_hyperlink => self.session.terminal.screens.active.endHyperlink(),
            .active_status_display => self.session.terminal.status_display = value,
            .decaln => try self.session.terminal.decaln(),
            .window_title => try self.session.replacePendingTitle(value.title),
            .report_pwd => {},
            .show_desktop_notification => try self.session.replacePendingNotification(value.title, value.body),
            .progress_report => self.progressReport(value),
            .start_hyperlink => try self.session.terminal.screens.active.startHyperlink(value.uri, value.id),
            .clipboard_contents => try self.clipboardContents(value.kind, value.data),
            .mouse_shape => self.session.terminal.mouse_shape = value,
            .configure_charset => self.session.terminal.configureCharset(value.slot, value.charset),
            .set_attribute => switch (value) {
                .unknown => {},
                else => try self.session.terminal.setAttribute(value),
            },
            .kitty_color_report => {},
            .color_operation => try self.colorOperation(value.op, &value.requests, value.terminator),
            .semantic_prompt => try self.session.terminal.semanticPrompt(value),
            .title_push, .title_pop => {},
        }
    }

    fn horizontalTab(self: *Handler, count: u16) void {
        for (0..count) |_| {
            const x = self.session.terminal.screens.active.cursor.x;
            self.session.terminal.horizontalTab();
            if (x == self.session.terminal.screens.active.cursor.x) {
                break;
            }
        }
    }

    fn horizontalTabBack(self: *Handler, count: u16) void {
        for (0..count) |_| {
            const x = self.session.terminal.screens.active.cursor.x;
            self.session.terminal.horizontalTabBack();
            if (x == self.session.terminal.screens.active.cursor.x) {
                break;
            }
        }
    }

    fn setCursorStyle(self: *Handler, style: anytype) void {
        switch (style) {
            .default => {
                self.session.terminal.screens.active.cursor.cursor_style = .block;
                self.session.terminal.modes.set(.cursor_blinking, true);
            },
            .blinking_block => {
                self.session.terminal.screens.active.cursor.cursor_style = .block;
                self.session.terminal.modes.set(.cursor_blinking, true);
            },
            .steady_block => {
                self.session.terminal.screens.active.cursor.cursor_style = .block;
                self.session.terminal.modes.set(.cursor_blinking, false);
            },
            .blinking_underline => {
                self.session.terminal.screens.active.cursor.cursor_style = .underline;
                self.session.terminal.modes.set(.cursor_blinking, true);
            },
            .steady_underline => {
                self.session.terminal.screens.active.cursor.cursor_style = .underline;
                self.session.terminal.modes.set(.cursor_blinking, false);
            },
            .blinking_bar => {
                self.session.terminal.screens.active.cursor.cursor_style = .bar;
                self.session.terminal.modes.set(.cursor_blinking, true);
            },
            .steady_bar => {
                self.session.terminal.screens.active.cursor.cursor_style = .bar;
                self.session.terminal.modes.set(.cursor_blinking, false);
            },
        }
    }

    fn setMode(self: *Handler, mode: ghostty.Mode, enabled: bool) !void {
        self.session.terminal.modes.set(mode, enabled);
        switch (mode) {
            .reverse_colors => {
                self.session.terminal.flags.dirty.reverse_colors = true;
                self.session.colors_changed = true;
            },
            .origin => self.session.terminal.setCursorPos(1, 1),
            .enable_left_and_right_margin => if (!enabled) {
                self.session.terminal.scrolling_region.left = 0;
                self.session.terminal.scrolling_region.right = self.session.terminal.cols - 1;
            },
            .alt_screen_legacy => try self.session.terminal.switchScreenMode(.@"47", enabled),
            .alt_screen => try self.session.terminal.switchScreenMode(.@"1047", enabled),
            .alt_screen_save_cursor_clear_enter => try self.session.terminal.switchScreenMode(.@"1049", enabled),
            .save_cursor => if (enabled) {
                self.session.terminal.saveCursor();
            } else {
                self.session.terminal.restoreCursor();
            },
            .mouse_event_x10 => self.session.terminal.flags.mouse_event = if (enabled) .x10 else .none,
            .mouse_event_normal => self.session.terminal.flags.mouse_event = if (enabled) .normal else .none,
            .mouse_event_button => self.session.terminal.flags.mouse_event = if (enabled) .button else .none,
            .mouse_event_any => self.session.terminal.flags.mouse_event = if (enabled) .any else .none,
            .mouse_format_utf8 => self.session.terminal.flags.mouse_format = if (enabled) .utf8 else .x10,
            .mouse_format_sgr => self.session.terminal.flags.mouse_format = if (enabled) .sgr else .x10,
            .mouse_format_urxvt => self.session.terminal.flags.mouse_format = if (enabled) .urxvt else .x10,
            .mouse_format_sgr_pixels => self.session.terminal.flags.mouse_format = if (enabled) .sgr_pixels else .x10,
            else => {},
        }
    }

    fn requestMode(self: *Handler, mode: ghostty.Mode) !void {
        const tag: ghostty.modes.ModeTag = @bitCast(@intFromEnum(mode));
        const code: u8 = if (self.session.terminal.modes.get(mode)) 1 else 2;
        var small: [32]u8 = undefined;
        const response = try std.fmt.bufPrint(
            &small,
            "\x1B[{s}{};{}$y",
            .{ if (tag.ansi) "" else "?", tag.value, code },
        );
        try self.session.appendPendingOutput(response);
    }

    fn requestModeUnknown(self: *Handler, mode_raw: u16, ansi: bool) !void {
        var small: [32]u8 = undefined;
        const response = try std.fmt.bufPrint(
            &small,
            "\x1B[{s}{};0$y",
            .{ if (ansi) "" else "?", mode_raw },
        );
        try self.session.appendPendingOutput(response);
    }

    fn deviceAttributes(self: *Handler, req: ghostty.DeviceAttributeReq) !void {
        switch (req) {
            .primary => try self.session.appendPendingOutput("\x1B[?64;1;2;6;9;15;18;21;22c"),
            .secondary => try self.session.appendPendingOutput("\x1B[>41;320;0c"),
            .tertiary => {},
        }
    }

    fn deviceStatus(self: *Handler, req: ghostty.device_status.Request) !void {
        switch (req) {
            .operating_status => try self.session.appendPendingOutput("\x1B[0n"),
            .cursor_position => {
                const row = self.session.terminal.screens.active.cursor.y + 1;
                const col = self.session.terminal.screens.active.cursor.x + 1;
                var small: [32]u8 = undefined;
                const response = try std.fmt.bufPrint(&small, "\x1B[{};{}R", .{ row, col });
                try self.session.appendPendingOutput(response);
            },
            .color_scheme => {},
        }
    }

    fn sendSizeReport(self: *Handler, style: ghostty.SizeReportStyle) !void {
        switch (style) {
            .csi_18_t => {
                var small: [32]u8 = undefined;
                const response = try std.fmt.bufPrint(&small, "\x1B[8;{};{}t", .{
                    self.session.terminal.rows,
                    self.session.terminal.cols,
                });
                try self.session.appendPendingOutput(response);
            },
            .csi_14_t => try self.session.appendPendingOutput("\x1B[4;0;0t"),
            .csi_16_t => try self.session.appendPendingOutput("\x1B[6;0;0t"),
            .csi_21_t => {
                if (self.session.pending_title.items.len == 0) {
                    try self.session.appendPendingOutput("\x1B]l\x1B\\");
                } else {
                    var writer: std.Io.Writer.Allocating = .init(self.session.alloc);
                    defer writer.deinit();
                    try writer.writer.writeAll("\x1B]l");
                    try writer.writer.writeAll(self.session.pending_title.items);
                    try writer.writer.writeAll("\x1B\\");
                    const owned = try writer.toOwnedSlice();
                    defer self.session.alloc.free(owned);
                    try self.session.appendPendingOutput(owned);
                }
            },
        }
    }

    fn queryKittyKeyboard(self: *Handler) !void {
        var small: [32]u8 = undefined;
        const response = try std.fmt.bufPrint(&small, "\x1b[?{}u", .{
            self.session.terminal.screens.active.kitty_keyboard.current().int(),
        });
        try self.session.appendPendingOutput(response);
    }

    fn clipboardContents(self: *Handler, kind: u8, data: []const u8) !void {
        _ = kind;
        if (data.len == 1 and data[0] == '?') {
            return;
        }
        if (data.len == 0) {
            try self.session.replacePendingClipboard("");
            return;
        }

        const decoder = std.base64.standard.Decoder;
        const decoded_len = decoder.calcSizeForSlice(data) catch |err| {
            ghostty_log.warn("core clipboard invalid base64 size session=0x{x} err={any}", .{ @intFromPtr(self.session), err });
            return;
        };

        const decoded = try self.session.alloc.alloc(u8, decoded_len);
        defer self.session.alloc.free(decoded);
        decoder.decode(decoded, data) catch |err| switch (err) {
            error.InvalidPadding => {},
            else => {
                ghostty_log.warn("core clipboard invalid base64 decode session=0x{x} err={any}", .{ @intFromPtr(self.session), err });
                return;
            },
        };

        try self.session.replacePendingClipboard(decoded);
    }

    fn progressReport(self: *Handler, value: ghostty.osc.Command.ProgressReport) void {
        switch (value.state) {
            .remove => self.session.clearProgress(),
            .set => self.session.setProgress(.set, value.progress),
            .@"error" => self.session.setProgress(.@"error", value.progress),
            .indeterminate => self.session.setProgress(.indeterminate, value.progress),
            .pause => self.session.setProgress(.pause, value.progress),
        }
    }

    fn colorOperation(
        self: *Handler,
        op: ghostty.osc.color.Operation,
        requests: *const ghostty.osc.color.List,
        terminator: ghostty.osc.Terminator,
    ) !void {
        _ = op;

        var response: std.ArrayListUnmanaged(u8) = .empty;
        defer response.deinit(self.session.alloc);
        const writer = response.writer(self.session.alloc);

        var it = requests.constIterator(0);
        while (it.next()) |req| {
            switch (req.*) {
                .set => |set| {
                    switch (set.target) {
                        .palette => |index| {
                            self.session.terminal.flags.dirty.palette = true;
                            self.session.terminal.colors.palette.set(index, set.color);
                        },
                        .dynamic => |dynamic| switch (dynamic) {
                            .foreground => self.session.terminal.colors.foreground.set(set.color),
                            .background => self.session.terminal.colors.background.set(set.color),
                            .cursor => self.session.terminal.colors.cursor.set(set.color),
                            else => {},
                        },
                        .special => {},
                    }
                    self.session.colors_changed = true;
                },
                .reset => |target| {
                    switch (target) {
                        .palette => |index| {
                            self.session.terminal.flags.dirty.palette = true;
                            self.session.terminal.colors.palette.reset(index);
                            self.session.colors_changed = true;
                        },
                        .dynamic => |dynamic| switch (dynamic) {
                            .foreground => {
                                self.session.terminal.colors.foreground.reset();
                                self.session.colors_changed = true;
                            },
                            .background => {
                                self.session.terminal.colors.background.reset();
                                self.session.colors_changed = true;
                            },
                            .cursor => {
                                self.session.terminal.colors.cursor.reset();
                                self.session.colors_changed = true;
                            },
                            else => {},
                        },
                        .special => {},
                    }
                },
                .reset_palette => {
                    self.session.terminal.flags.dirty.palette = true;
                    self.session.terminal.colors.palette.resetAll();
                    self.session.colors_changed = true;
                },
                .reset_special => {},
                .query => |kind| {
                    const color = switch (kind) {
                        .palette => |index| self.session.terminal.colors.palette.current[index],
                        .dynamic => |dynamic| switch (dynamic) {
                            .foreground => self.session.terminal.colors.foreground.get() orelse continue,
                            .background => self.session.terminal.colors.background.get() orelse continue,
                            .cursor => self.session.terminal.colors.cursor.get() orelse self.session.terminal.colors.foreground.get() orelse continue,
                            else => continue,
                        },
                        .special => continue,
                    };

                    switch (kind) {
                        .palette => |index| try writer.print(
                            "\x1B]4;{d};rgb:{x:0>2}/{x:0>2}/{x:0>2}{s}",
                            .{ index, color.r, color.g, color.b, terminator.string() },
                        ),
                        .dynamic => |dynamic| try writer.print(
                            "\x1B]{d};rgb:{x:0>2}/{x:0>2}/{x:0>2}{s}",
                            .{ @intFromEnum(dynamic), color.r, color.g, color.b, terminator.string() },
                        ),
                        .special => {},
                    }
                },
            }
        }

        if (response.items.len > 0) {
            try self.session.appendPendingOutput(response.items);
        }
    }
};

pub export fn termux_ghostty_session_create(
    columns: i32,
    rows: i32,
    transcript_rows: i32,
    cell_width_pixels: i32,
    cell_height_pixels: i32,
) ?*Session {
    const parsed_columns = parseCellCount(columns) orelse {
        ghostty_log.err("core create invalid columns={}", .{ columns });
        return null;
    };
    const parsed_rows = parseCellCount(rows) orelse {
        ghostty_log.err("core create invalid rows={}", .{ rows });
        return null;
    };
    const parsed_transcript_rows = parseTranscriptRows(transcript_rows) orelse {
        ghostty_log.err("core create invalid transcriptRows={}", .{ transcript_rows });
        return null;
    };
    const parsed_cell_width_pixels = parsePixelCount(cell_width_pixels) orelse {
        ghostty_log.err("core create invalid cellWidthPixels={}", .{ cell_width_pixels });
        return null;
    };
    const parsed_cell_height_pixels = parsePixelCount(cell_height_pixels) orelse {
        ghostty_log.err("core create invalid cellHeightPixels={}", .{ cell_height_pixels });
        return null;
    };

    const session = std.heap.c_allocator.create(Session) catch {
        ghostty_log.err("core create allocation failed cols={} rows={} transcript={}", .{ columns, rows, transcript_rows });
        return null;
    };
    errdefer std.heap.c_allocator.destroy(session);

    const scrollback_bytes = estimatedScrollbackBytes(parsed_columns, parsed_transcript_rows);
    const colors = defaultTerminalColors();
    session.* = undefined;
    session.alloc = std.heap.c_allocator;
    session.terminal = ghostty.Terminal.init(session.alloc, .{
        .cols = parsed_columns,
        .rows = parsed_rows,
        .max_scrollback = scrollback_bytes,
        .colors = colors,
    }) catch |err| {
        ghostty_log.err("core create terminal init failed err={any}", .{ err });
        return null;
    };
    errdefer session.terminal.deinit(session.alloc);

    session.handler = .{ .session = session };
    session.stream = ghostty.Stream(*Handler).initAlloc(session.alloc, &session.handler);
    session.render_state = .empty;
    session.pending_output = .empty;
    session.pending_title = .empty;
    session.pending_clipboard = .empty;
    session.pending_notification_title = .empty;
    session.pending_notification_body = .empty;
    session.scratch_utf16 = .empty;
    session.scratch_cell_starts = .empty;
    session.scratch_cell_lengths = .empty;
    session.scratch_cell_widths = .empty;
    session.scratch_cell_styles = .empty;
    session.scratch_viewport_link_records = .empty;
    session.scratch_viewport_link_strings = .empty;
    session.colors_changed = false;
    session.bell_pending = false;
    session.progress_state = .none;
    session.progress_value = -1;
    session.progress_generation = 0;
    session.progress_changed = false;
    session.viewport_top_row = 0;
    session.render_state_needs_update = true;
    session.serialized_metadata_valid = false;
    session.last_serialized_render_metadata = .{
        .cursor_col = 0,
        .cursor_row = 0,
        .cursor_style = 0,
        .cursor_visible = false,
        .reverse_video = false,
    };
    session.last_serialized_mode_bits = 0;
    session.last_serialized_palette = [_]u32{0} ** termux_palette_len;
    session.frame_perf = .{};
    session.mouse_pressed_buttons = 0;
    session.mouse_last_cell = .{ .x = 0, .y = 0 };
    session.mouse_last_cell_valid = false;
    session.terminal.width_px = @as(u32, parsed_columns) * parsed_cell_width_pixels;
    session.terminal.height_px = @as(u32, parsed_rows) * parsed_cell_height_pixels;

    ghostty_log.info("core create session=0x{x} cols={} rows={} transcript={} cellWidth={} cellHeight={} scrollbackBytes={}", .{ @intFromPtr(session), columns, rows, transcript_rows, cell_width_pixels, cell_height_pixels, scrollback_bytes });
    return session;
}

pub export fn termux_ghostty_session_destroy(session: ?*Session) void {
    const handle = session orelse return;
    ghostty_log.info("core destroy session=0x{x}", .{ @intFromPtr(handle) });
    handle.deinit();
    std.heap.c_allocator.destroy(handle);
}

pub export fn termux_ghostty_session_reset(session: ?*Session) void {
    const handle = session orelse return;
    ghostty_log.debug("core reset session=0x{x}", .{ @intFromPtr(handle) });
    handle.terminal.fullReset();
    handle.viewport_top_row = 0;
    handle.markRenderStateDirty();
    handle.resetTransientState();
}

pub export fn termux_ghostty_session_set_color_scheme(
    session: ?*Session,
    colors: [*]const i32,
    length: usize,
) i32 {
    const handle = session orelse return -1;
    if (length < termux_palette_len) {
        ghostty_log.err("core set_color_scheme invalid length={} session=0x{x}", .{ length, @intFromPtr(handle) });
        return -1;
    }

    var palette: @TypeOf(handle.terminal.colors.palette.original) = undefined;
    for (&palette, 0..) |*entry, index| {
        entry.* = argbToRgb(@bitCast(colors[index]));
    }

    handle.terminal.colors.palette = .init(palette);
    handle.terminal.colors.foreground = .init(argbToRgb(@bitCast(colors[@intCast(termux_default_foreground)])));
    handle.terminal.colors.background = .init(argbToRgb(@bitCast(colors[@intCast(termux_default_background)])));
    handle.terminal.colors.cursor = .init(argbToRgb(@bitCast(colors[@intCast(termux_default_cursor)])));
    handle.terminal.flags.dirty.palette = true;
    handle.markRenderStateDirty();
    handle.requestFullSnapshotRefresh();
    ghostty_log.debug("core set_color_scheme session=0x{x}", .{ @intFromPtr(handle) });
    return 0;
}

pub export fn termux_ghostty_session_resize(
    session: ?*Session,
    columns: i32,
    rows: i32,
    cell_width_pixels: i32,
    cell_height_pixels: i32,
) i32 {
    const handle = session orelse return -1;
    const parsed_columns = parseCellCount(columns) orelse {
        ghostty_log.err("core resize invalid columns={} session=0x{x}", .{ columns, @intFromPtr(handle) });
        return -1;
    };
    const parsed_rows = parseCellCount(rows) orelse {
        ghostty_log.err("core resize invalid rows={} session=0x{x}", .{ rows, @intFromPtr(handle) });
        return -1;
    };
    const parsed_cell_width_pixels = parsePixelCount(cell_width_pixels) orelse {
        ghostty_log.err("core resize invalid cellWidthPixels={} session=0x{x}", .{ cell_width_pixels, @intFromPtr(handle) });
        return -1;
    };
    const parsed_cell_height_pixels = parsePixelCount(cell_height_pixels) orelse {
        ghostty_log.err("core resize invalid cellHeightPixels={} session=0x{x}", .{ cell_height_pixels, @intFromPtr(handle) });
        return -1;
    };

    handle.terminal.resize(handle.alloc, parsed_columns, parsed_rows) catch |err| {
        ghostty_log.err("core resize failed session=0x{x} cols={} rows={} err={any}", .{ @intFromPtr(handle), columns, rows, err });
        return -1;
    };
    handle.terminal.width_px = @as(u32, parsed_columns) * parsed_cell_width_pixels;
    handle.terminal.height_px = @as(u32, parsed_rows) * parsed_cell_height_pixels;
    handle.viewport_top_row = handle.clampExternalTopRow(handle.viewport_top_row);
    handle.markRenderStateDirty();
    ghostty_log.debug("core resize session=0x{x} cols={} rows={} cellWidth={} cellHeight={}", .{ @intFromPtr(handle), columns, rows, cell_width_pixels, cell_height_pixels });
    return 0;
}

pub export fn termux_ghostty_session_queue_mouse_event(
    session: ?*Session,
    action: i32,
    button: i32,
    modifiers: i32,
    surface_x: f32,
    surface_y: f32,
    screen_width_px: i32,
    screen_height_px: i32,
    cell_width_px: i32,
    cell_height_px: i32,
    padding_top_px: i32,
    padding_right_px: i32,
    padding_bottom_px: i32,
    padding_left_px: i32,
) i32 {
    const handle = session orelse return -1;

    const mapped_action = mouseActionFromJava(action) catch |err| {
        ghostty_log.err("core queueMouse invalid action={} session=0x{x} err={any}", .{ action, @intFromPtr(handle), err });
        return -1;
    };
    const mapped_button = mouseButtonFromJava(button) catch |err| {
        ghostty_log.err("core queueMouse invalid button={} session=0x{x} err={any}", .{ button, @intFromPtr(handle), err });
        return -1;
    };
    const size = mouseSizeFromJava(
        screen_width_px,
        screen_height_px,
        cell_width_px,
        cell_height_px,
        padding_top_px,
        padding_right_px,
        padding_bottom_px,
        padding_left_px,
    ) catch |err| {
        ghostty_log.err("core queueMouse invalid size session=0x{x} err={any}", .{ @intFromPtr(handle), err });
        return -1;
    };

    handle.updatePressedMouseButtonsBeforeEncode(mapped_action, mapped_button);

    var last_cell: ?ghostty.Coordinate = if (handle.mouse_last_cell_valid) handle.mouse_last_cell else null;
    var options: ghostty.input.MouseEncodeOptions = .fromTerminal(&handle.terminal, size);
    options.any_button_pressed = handle.hasPressedMouseButtons();
    options.last_cell = &last_cell;

    var buffer: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&buffer);
    ghostty.input.encodeMouse(&writer, .{
        .action = mapped_action,
        .button = mapped_button,
        .mods = keyModsFromJava(modifiers),
        .pos = .{
            .x = surface_x,
            .y = surface_y,
        },
    }, options) catch |err| {
        ghostty_log.err("core queueMouse encode failed session=0x{x} err={any}", .{ @intFromPtr(handle), err });
        return -1;
    };

    const written = writer.buffered();
    if (written.len == 0) {
        return 0;
    }

    handle.appendPendingOutput(written) catch |err| {
        ghostty_log.err("core queueMouse append failed session=0x{x} err={any}", .{ @intFromPtr(handle), err });
        return -1;
    };
    if (last_cell) |cell| {
        handle.mouse_last_cell = cell;
        handle.mouse_last_cell_valid = true;
    } else {
        handle.mouse_last_cell = .{ .x = 0, .y = 0 };
        handle.mouse_last_cell_valid = false;
    }

    return std.math.cast(i32, written.len) orelse -1;
}

pub export fn termux_ghostty_session_append(
    session: ?*Session,
    data: ?[*]const u8,
    len: usize,
) u32 {
    const handle = session orelse return 0;
    const bytes = data orelse return 0;
    if (len == 0) {
        return 0;
    }

    const old_cursor_row = handle.cursorRow();
    const old_cursor_col = handle.cursorCol();
    const old_mode_bits = handle.modeBits();
    const old_reverse = handle.reverseVideo();
    const old_alt = handle.alternateBufferActive();
    const old_transcript_rows = handle.activeTranscriptRows();

    handle.colors_changed = false;
    handle.bell_pending = false;
    handle.progress_changed = false;

    handle.stream.nextSlice(bytes[0..len]);
    handle.markRenderStateDirty();

    var result: u32 = append_result_screen_changed;
    if (old_cursor_row != handle.cursorRow() or old_cursor_col != handle.cursorCol()) {
        result |= append_result_cursor_changed;
    }
    if (old_mode_bits != handle.modeBits() or old_reverse != handle.reverseVideo() or old_alt != handle.alternateBufferActive()) {
        result |= append_result_cursor_changed;
    }
    if (old_transcript_rows != handle.activeTranscriptRows()) {
        result |= append_result_screen_changed;
    }
    if (handle.pending_title.items.len > 0) {
        result |= append_result_title_changed;
    }
    if (handle.pending_clipboard.items.len > 0) {
        result |= append_result_clipboard_copy;
    }
    if (handle.pending_notification_title.items.len > 0 or handle.pending_notification_body.items.len > 0) {
        result |= append_result_desktop_notification;
    }
    if (handle.colors_changed) {
        result |= append_result_colors_changed;
    }
    if (handle.bell_pending) {
        result |= append_result_bell;
    }
    if (handle.progress_changed) {
        result |= append_result_progress;
    }
    if (handle.pending_output.items.len > 0) {
        result |= append_result_reply_bytes_available;
    }

    if (log_append_summary and ghostty_log.enabled()) {
        ghostty_log.debug(
            "core append session=0x{x} len={} result=0x{x} cursor={}x{} transcriptRows={} pendingOut={} titleBytes={} clipboardBytes={} notificationTitleBytes={} notificationBodyBytes={} progressState={} progressValue={} progressGeneration={}",
            .{
                @intFromPtr(handle),
                len,
                result,
                handle.cursorRow(),
                handle.cursorCol(),
                handle.activeTranscriptRows(),
                handle.pending_output.items.len,
                handle.pending_title.items.len,
                handle.pending_clipboard.items.len,
                handle.pending_notification_title.items.len,
                handle.pending_notification_body.items.len,
                @intFromEnum(handle.progress_state),
                handle.progress_value,
                handle.progress_generation,
            },
        );
    }

    return result;
}

pub export fn termux_ghostty_session_drain_pending_output(
    session: ?*Session,
    buffer: ?[*]u8,
    capacity: usize,
) usize {
    const handle = session orelse return 0;
    const out = buffer orelse return 0;
    return handle.drainPendingOutput(out[0..capacity]);
}

pub export fn termux_ghostty_session_set_viewport_top_row(
    session: ?*Session,
    top_row: i32,
) i32 {
    const handle = session orelse return 0;
    return handle.setViewportTopRow(top_row);
}

pub export fn termux_ghostty_session_request_full_snapshot_refresh(session: ?*Session) void {
    const handle = session orelse return;
    handle.requestFullSnapshotRefresh();
}

pub export fn termux_ghostty_session_fill_snapshot_current_viewport(
    session: ?*Session,
    buffer: ?[*]u8,
    capacity: usize,
) i32 {
    const handle = session orelse return -1;
    const out = buffer orelse return -1;
    return fillSnapshotCurrentViewport(handle, out[0..capacity]);
}

pub export fn termux_ghostty_session_fill_viewport_links(
    session: ?*Session,
    buffer: ?[*]u8,
    capacity: usize,
) i32 {
    const handle = session orelse return -1;
    const out = buffer orelse return -1;
    return fillViewportLinksCurrentViewport(handle, out[0..capacity]);
}

pub export fn termux_ghostty_session_fill_snapshot(
    session: ?*Session,
    top_row: i32,
    buffer: ?[*]u8,
    capacity: usize,
) i32 {
    const handle = session orelse return -1;
    const out = buffer orelse return -1;
    _ = handle.setViewportTopRow(top_row);
    return fillSnapshotCurrentViewport(handle, out[0..capacity]);
}

fn fillSnapshotCurrentViewport(handle: *Session, out: []u8) i32 {
    const top_row = handle.viewport_top_row;

    if (log_fill_snapshot_summary) {
        ghostty_log.debug("core fillSnapshot start session=0x{x} topRow={} capacity={} terminalRows={} terminalCols={} activeRows={} transcriptRows={} alt={}", .{
            @intFromPtr(handle),
            top_row,
            out.len,
            handle.terminal.rows,
            handle.terminal.cols,
            handle.activeRows(),
            handle.activeTranscriptRows(),
            handle.alternateBufferActive(),
        });
    }

    const render_timing = handle.updateRenderStateIfNeeded() catch |err| {
        ghostty_log.err("core fillSnapshot render state failed session=0x{x} topRow={} err={any}", .{ @intFromPtr(handle), top_row, err });
        return -1;
    };
    const dirty_rows = handle.dirtyRowCount();
    const partial_dirty_rows = handle.partialDirtyRowCount();
    if (log_fill_snapshot_summary) {
        ghostty_log.debug("core fillSnapshot render state ready session=0x{x} renderRows={} renderCols={} dirty={any} dirtyRows={} scrollUs={} renderUpdateUs={}", .{
            @intFromPtr(handle),
            handle.render_state.rows,
            handle.render_state.cols,
            handle.render_state.dirty,
            dirty_rows,
            render_timing.scroll_sync_ns / 1_000,
            render_timing.render_state_update_ns / 1_000,
        });
    }

    const full_rebuild = handle.render_state.dirty == .full;
    var snapshot_metadata = handle.buildSnapshotMetadata(full_rebuild);
    const required_bytes = handle.snapshotRequiredBytes(&snapshot_metadata) catch |err| {
        ghostty_log.err("core fillSnapshot size failed session=0x{x} topRow={} err={any}", .{ @intFromPtr(handle), top_row, err });
        return -1;
    };
    const required_i32 = std.math.cast(i32, required_bytes) orelse return -1;
    if (log_fill_snapshot_summary) {
        ghostty_log.debug("core fillSnapshot required session=0x{x} required={} capacity={} metadataFlags=0x{x}", .{ @intFromPtr(handle), required_bytes, out.len, snapshot_metadata.flags });
    }
    if (out.len < required_bytes) {
        ghostty_log.warn("core fillSnapshot buffer too small session=0x{x} topRow={} required={} capacity={}", .{ @intFromPtr(handle), top_row, required_bytes, out.len });
        return required_i32;
    }

    const snapshot_flags: u32 = switch (handle.render_state.dirty) {
        .full => snapshot_flag_full_rebuild,
        else => 0,
    };
    const snapshot_serialize_start_ns = std.time.nanoTimestamp();
    var writer = BufferWriter.init(out);
    writer.writeU32(snapshot_magic) catch return -1;
    writer.writeI32(top_row) catch return -1;
    writer.writeU32(handle.render_state.rows) catch return -1;
    writer.writeU32(handle.render_state.cols) catch return -1;
    writer.writeU32(snapshot_flags) catch return -1;
    writer.writeU32(partial_dirty_rows) catch return -1;
    writer.writeU32(snapshot_metadata.flags) catch return -1;

    if ((snapshot_metadata.flags & snapshot_metadata_palette) != 0) {
        writeSnapshotPalette(&writer, snapshot_metadata.palette[0..]) catch return -1;
    }
    if ((snapshot_metadata.flags & snapshot_metadata_render) != 0) {
        writeSnapshotRenderMetadata(&writer, snapshot_metadata.render) catch return -1;
    }
    if ((snapshot_metadata.flags & snapshot_metadata_mode_bits) != 0) {
        writer.writeU32(snapshot_metadata.mode_bits) catch return -1;
    }

    const row_data = handle.render_state.row_data.slice();
    if (handle.render_state.dirty == .partial) {
        for (row_data.items(.dirty), 0..) |dirty, row_index| {
            if (!dirty) {
                continue;
            }

            writer.writeU32(std.math.cast(u32, row_index) orelse return -1) catch return -1;
        }
    }

    const row_rows = row_data.items(.raw);
    const row_pins = row_data.items(.pin);
    const row_cells = row_data.items(.cells);
    if (row_rows.len < handle.render_state.rows or row_pins.len < handle.render_state.rows or row_cells.len < handle.render_state.rows) {
        ghostty_log.err("core fillSnapshot row data mismatch session=0x{x} renderRows={} rawRows={} pinRows={} cellRows={}", .{
            @intFromPtr(handle),
            handle.render_state.rows,
            row_rows.len,
            row_pins.len,
            row_cells.len,
        });
        return -1;
    }
    for (0..handle.render_state.rows) |row_index| {
        if (!handle.shouldSerializeRow(row_index)) {
            continue;
        }

        handle.writeSnapshotRow(&writer, row_index) catch return -1;
    }

    const snapshot_serialize_ns = elapsedNanos(snapshot_serialize_start_ns);
    handle.commitSerializedMetadata(&snapshot_metadata);
    handle.logSnapshotPerfIfNeeded(top_row, required_bytes, dirty_rows, render_timing, snapshot_serialize_ns);
    handle.clearRenderStateDirty();
    return required_i32;
}

fn fillViewportLinksCurrentViewport(handle: *Session, out: []u8) i32 {
    const render_timing = handle.updateRenderStateIfNeeded() catch |err| {
        ghostty_log.err("core fillViewportLinks render state failed session=0x{x} topRow={} err={any}", .{
            @intFromPtr(handle),
            handle.viewport_top_row,
            err,
        });
        return -1;
    };
    _ = render_timing;

    const string_table_bytes = handle.buildViewportLinkScratch() catch |err| {
        ghostty_log.err("core fillViewportLinks build failed session=0x{x} topRow={} err={any}", .{
            @intFromPtr(handle),
            handle.viewport_top_row,
            err,
        });
        return -1;
    };

    const segment_count = handle.scratch_viewport_link_records.items.len;
    var required_bytes: usize = viewport_link_header_bytes;
    required_bytes += segment_count * viewport_link_record_bytes;
    required_bytes += string_table_bytes;

    const required_i32 = std.math.cast(i32, required_bytes) orelse return -1;
    if (out.len < required_bytes) {
        ghostty_log.warn("core fillViewportLinks buffer too small session=0x{x} topRow={} required={} capacity={}", .{
            @intFromPtr(handle),
            handle.viewport_top_row,
            required_bytes,
            out.len,
        });
        return required_i32;
    }

    var writer = BufferWriter.init(out);
    writer.writeU32(viewport_link_magic) catch return -1;
    writer.writeI32(handle.viewport_top_row) catch return -1;
    writer.writeU32(handle.render_state.rows) catch return -1;
    writer.writeU32(handle.render_state.cols) catch return -1;
    writer.writeU32(std.math.cast(u32, segment_count) orelse return -1) catch return -1;
    writer.writeU32(std.math.cast(u32, string_table_bytes) orelse return -1) catch return -1;

    for (handle.scratch_viewport_link_records.items) |record| {
        writer.writeU32(record.row) catch return -1;
        writer.writeU32(record.start_column) catch return -1;
        writer.writeU32(record.end_column_exclusive) catch return -1;
        writer.writeU32(record.string_offset) catch return -1;
        writer.writeU32(record.string_length) catch return -1;
    }
    for (handle.scratch_viewport_link_strings.items) |entry| {
        writer.writeBytes(entry.uri) catch return -1;
    }

    return required_i32;
}

pub export fn termux_ghostty_session_get_columns(session: ?*const Session) i32 {
    const handle = session orelse return 0;
    return @intCast(handle.terminal.cols);
}

pub export fn termux_ghostty_session_get_rows(session: ?*const Session) i32 {
    const handle = session orelse return 0;
    return @intCast(handle.terminal.rows);
}

pub export fn termux_ghostty_session_get_active_rows(session: ?*const Session) i32 {
    const handle = session orelse return 0;
    return std.math.cast(i32, handle.activeRows()) orelse std.math.maxInt(i32);
}

pub export fn termux_ghostty_session_get_active_transcript_rows(session: ?*const Session) i32 {
    const handle = session orelse return 0;
    return std.math.cast(i32, handle.activeTranscriptRows()) orelse std.math.maxInt(i32);
}

pub export fn termux_ghostty_session_get_mode_bits(session: ?*const Session) u32 {
    const handle = session orelse return 0;
    return handle.modeBits();
}

pub export fn termux_ghostty_session_get_cursor_row(session: ?*const Session) i32 {
    const handle = session orelse return 0;
    return handle.cursorRow();
}

pub export fn termux_ghostty_session_get_cursor_col(session: ?*const Session) i32 {
    const handle = session orelse return 0;
    return handle.cursorCol();
}

pub export fn termux_ghostty_session_get_cursor_style(session: ?*const Session) i32 {
    const handle = session orelse return 0;
    return handle.cursorStyle();
}

pub export fn termux_ghostty_session_is_cursor_visible(session: ?*const Session) bool {
    const handle = session orelse return false;
    return handle.cursorVisible();
}

pub export fn termux_ghostty_session_is_reverse_video(session: ?*const Session) bool {
    const handle = session orelse return false;
    return handle.reverseVideo();
}

pub export fn termux_ghostty_session_is_alternate_buffer_active(session: ?*const Session) bool {
    const handle = session orelse return false;
    return handle.alternateBufferActive();
}

pub export fn termux_ghostty_session_get_progress_state(session: ?*const Session) i32 {
    const handle = session orelse return @intFromEnum(ProgressState.none);
    return @intFromEnum(handle.progress_state);
}

pub export fn termux_ghostty_session_get_progress_value(session: ?*const Session) i32 {
    const handle = session orelse return -1;
    return handle.progress_value;
}

pub export fn termux_ghostty_session_get_progress_generation(session: ?*const Session) u64 {
    const handle = session orelse return 0;
    return handle.progress_generation;
}

pub export fn termux_ghostty_session_clear_progress(session: ?*Session) void {
    const handle = session orelse return;
    handle.clearProgress();
    handle.progress_changed = false;
}

pub export fn termux_ghostty_session_consume_notification_title(session: ?*Session) ?[*:0]u8 {
    const handle = session orelse return null;
    const title = handle.consumeNotificationTitle() orelse return null;
    ghostty_log.debug("core consumeNotificationTitle session=0x{x} bytes={}", .{ @intFromPtr(handle), title.len });
    return title.ptr;
}

pub export fn termux_ghostty_session_consume_notification_body(session: ?*Session) ?[*:0]u8 {
    const handle = session orelse return null;
    const body = handle.consumeNotificationBody() orelse return null;
    ghostty_log.debug("core consumeNotificationBody session=0x{x} bytes={}", .{ @intFromPtr(handle), body.len });
    return body.ptr;
}

pub fn termux_ghostty_session_consume_title(session: ?*Session) ?[:0]u8 {
    const handle = session orelse return null;
    const title = handle.consumeTitle();
    if (title) |owned| {
        ghostty_log.debug("core consumeTitle session=0x{x} bytes={}", .{ @intFromPtr(handle), owned.len });
    }
    return title;
}

pub fn termux_ghostty_session_consume_clipboard_text(session: ?*Session) ?[:0]u8 {
    const handle = session orelse return null;
    const text = handle.consumeClipboard();
    if (text) |owned| {
        ghostty_log.debug("core consumeClipboard session=0x{x} bytes={}", .{ @intFromPtr(handle), owned.len });
    }
    return text;
}

pub fn termux_ghostty_session_get_selected_text(
    session: ?*Session,
    start_column: i32,
    start_row: i32,
    end_column: i32,
    end_row: i32,
    trim: bool,
) ?[:0]u8 {
    const handle = session orelse return null;
    return handle.selectedText(start_column, start_row, end_column, end_row, trim);
}

pub fn termux_ghostty_session_get_word_at_location(
    session: ?*Session,
    column: i32,
    row: i32,
) ?[:0]u8 {
    const handle = session orelse return null;
    return handle.wordAtLocation(column, row);
}

pub fn termux_ghostty_session_get_transcript_text(
    session: ?*Session,
    join_lines: bool,
    trim: bool,
) ?[:0]u8 {
    const handle = session orelse return null;
    return handle.transcriptText(join_lines, trim);
}

fn viewportCellLinkForRowCell(
    page: *ghostty.Page,
    row_y: usize,
    column: usize,
    raw_cell: ghostty.Cell,
) ?ViewportCellLink {
    if (!raw_cell.hyperlink) {
        return null;
    }

    const rac = page.getRowAndCell(column, row_y);
    const link_id = page.lookupHyperlink(rac.cell) orelse return null;
    const entry = page.hyperlink_set.get(page.memory, link_id);
    const uri = entry.uri.slice(page.memory);
    if (uri.len == 0) {
        return null;
    }

    return .{
        .id = link_id,
        .uri = uri,
    };
}

const MouseSize = @FieldType(ghostty.input.MouseEncodeOptions, "size");

fn mouseActionFromJava(value: i32) error{InvalidMouseAction}!ghostty.input.MouseAction {
    return switch (value) {
        0 => .press,
        1 => .release,
        2 => .motion,
        else => error.InvalidMouseAction,
    };
}

fn mouseButtonFromJava(value: i32) error{InvalidMouseButton}!?ghostty.input.MouseButton {
    return switch (value) {
        0 => null,
        1 => .left,
        2 => .middle,
        3 => .right,
        4 => .four,
        5 => .five,
        6 => .six,
        7 => .seven,
        8 => .eight,
        9 => .nine,
        else => error.InvalidMouseButton,
    };
}

fn keyModsFromJava(value: i32) ghostty.input.KeyMods {
    return .{
        .shift = (value & (1 << 0)) != 0,
        .alt = (value & (1 << 1)) != 0,
        .ctrl = (value & (1 << 2)) != 0,
    };
}

fn mouseSizeFromJava(
    screen_width_px: i32,
    screen_height_px: i32,
    cell_width_px: i32,
    cell_height_px: i32,
    padding_top_px: i32,
    padding_right_px: i32,
    padding_bottom_px: i32,
    padding_left_px: i32,
) error{InvalidMouseSize}!MouseSize {
    return .{
        .screen = .{
            .width = parsePixelCount(screen_width_px) orelse return error.InvalidMouseSize,
            .height = parsePixelCount(screen_height_px) orelse return error.InvalidMouseSize,
        },
        .cell = .{
            .width = parsePixelCount(cell_width_px) orelse return error.InvalidMouseSize,
            .height = parsePixelCount(cell_height_px) orelse return error.InvalidMouseSize,
        },
        .padding = .{
            .top = parsePaddingPixels(padding_top_px) orelse return error.InvalidMouseSize,
            .right = parsePaddingPixels(padding_right_px) orelse return error.InvalidMouseSize,
            .bottom = parsePaddingPixels(padding_bottom_px) orelse return error.InvalidMouseSize,
            .left = parsePaddingPixels(padding_left_px) orelse return error.InvalidMouseSize,
        },
    };
}

fn parseCellCount(value: i32) ?u16 {
    if (value <= 0) {
        return null;
    }

    return std.math.cast(u16, value);
}

fn parseTranscriptRows(value: i32) ?usize {
    if (value < 0) {
        return null;
    }

    return std.math.cast(usize, value);
}

fn parsePixelCount(value: i32) ?u32 {
    if (value <= 0) {
        return null;
    }

    return std.math.cast(u32, value);
}

fn parsePaddingPixels(value: i32) ?u32 {
    if (value < 0) {
        return null;
    }

    return std.math.cast(u32, value);
}

fn estimatedScrollbackBytes(columns: u16, transcript_rows: usize) usize {
    if (transcript_rows == 0) {
        return 0;
    }

    const row_bytes = (@sizeOf(ghostty.Cell) * @as(usize, columns)) + @sizeOf(ghostty.page.Row) + 64;
    return std.math.mul(usize, row_bytes, transcript_rows) catch std.math.maxInt(usize);
}

fn defaultTerminalColors() ghostty.Terminal.Colors {
    const background = ghostty.color.RGB{ .r = 0x00, .g = 0x00, .b = 0x00 };
    const foreground = ghostty.color.RGB{ .r = 0xFF, .g = 0xFF, .b = 0xFF };
    const cursor = ghostty.color.RGB{ .r = 0xFF, .g = 0xFF, .b = 0xFF };
    return .{
        .background = .init(background),
        .foreground = .init(foreground),
        .cursor = .init(cursor),
        .palette = .default,
    };
}

fn utf16LengthForCodepoint(cp: u21) usize {
    return if (cp <= 0xFFFF) 1 else 2;
}

fn appendCodepointUtf16(list: *std.ArrayListUnmanaged(u16), alloc: std.mem.Allocator, cp: u21) !void {
    if (cp <= 0xFFFF) {
        try list.append(alloc, @intCast(cp));
        return;
    }

    const scalar: u32 = cp - 0x10000;
    try list.append(alloc, @intCast(0xD800 + (scalar >> 10)));
    try list.append(alloc, @intCast(0xDC00 + (scalar & 0x3FF)));
}

fn snapshotCellWidth(cell: ghostty.Cell) u8 {
    return switch (cell.wide) {
        .wide => 2,
        .spacer_tail, .spacer_head => 0,
        else => 1,
    };
}

fn rgbToArgb(rgb: ghostty.color.RGB) u32 {
    return 0xFF000000 | (@as(u32, rgb.r) << 16) | (@as(u32, rgb.g) << 8) | @as(u32, rgb.b);
}

fn argbToRgb(argb: u32) ghostty.color.RGB {
    return .{
        .r = @intCast((argb >> 16) & 0xFF),
        .g = @intCast((argb >> 8) & 0xFF),
        .b = @intCast(argb & 0xFF),
    };
}

fn encodeTermuxColor(color: ghostty.Style.Color, default_index: u32) u32 {
    return switch (color) {
        .none => default_index,
        .palette => |index| index,
        .rgb => |rgb| rgbToArgb(rgb),
    };
}

fn encodeTermuxStyle(cell: ghostty.Cell, style: ghostty.Style) u64 {
    var effect: u16 = 0;
    if (style.flags.bold) effect |= text_style_bold;
    if (style.flags.italic) effect |= text_style_italic;
    if (style.flags.faint) effect |= text_style_dim;
    if (style.flags.blink) effect |= text_style_blink;
    if (style.flags.inverse) effect |= text_style_inverse;
    if (style.flags.invisible) effect |= text_style_invisible;
    if (style.flags.strikethrough) effect |= text_style_strikethrough;
    if (style.flags.underline != .none) effect |= text_style_underline;

    const foreground = encodeTermuxColor(style.fg_color, termux_default_foreground);
    const background = encodeBackgroundColor(cell, style);

    var result: u64 = effect;
    if ((foreground & 0xFF000000) == 0xFF000000) {
        result |= (1 << 9) | ((@as(u64, foreground & 0x00FFFFFF)) << 40);
    } else {
        result |= (@as(u64, foreground & 0x1FF) << 40);
    }
    if ((background & 0xFF000000) == 0xFF000000) {
        result |= (1 << 10) | ((@as(u64, background & 0x00FFFFFF)) << 16);
    } else {
        result |= (@as(u64, background & 0x1FF) << 16);
    }
    return result;
}

fn encodeBackgroundColor(cell: ghostty.Cell, style: ghostty.Style) u32 {
    return switch (cell.content_tag) {
        .bg_color_palette => cell.content.color_palette,
        .bg_color_rgb => rgbToArgb(.{
            .r = cell.content.color_rgb.r,
            .g = cell.content.color_rgb.g,
            .b = cell.content.color_rgb.b,
        }),
        else => encodeTermuxColor(style.bg_color, termux_default_background),
    };
}

fn fillSnapshotPalette(out: *[termux_palette_len]u32, session: *const Session) void {
    var index: usize = 0;
    for (session.terminal.colors.palette.current) |rgb| {
        out[index] = rgbToArgb(rgb);
        index += 1;
    }

    const foreground = session.terminal.colors.foreground.get() orelse ghostty.color.RGB{ .r = 0xFF, .g = 0xFF, .b = 0xFF };
    const background = session.terminal.colors.background.get() orelse ghostty.color.RGB{ .r = 0x00, .g = 0x00, .b = 0x00 };
    const cursor = session.terminal.colors.cursor.get() orelse foreground;
    out[index] = rgbToArgb(foreground);
    out[index + 1] = rgbToArgb(background);
    out[index + 2] = rgbToArgb(cursor);
}

fn writeSnapshotPalette(writer: *BufferWriter, palette: []const u32) !void {
    for (palette) |color| {
        try writer.writeU32(color);
    }
}

fn writeSnapshotRenderMetadata(writer: *BufferWriter, metadata: SerializedRenderMetadata) !void {
    try writer.writeI32(metadata.cursor_col);
    try writer.writeI32(metadata.cursor_row);
    try writer.writeI32(metadata.cursor_style);
    try writer.writeU32(@intFromBool(metadata.cursor_visible));
    try writer.writeU32(@intFromBool(metadata.reverse_video));
}

const BufferWriter = struct {
    bytes: []u8,
    offset: usize = 0,

    fn init(bytes: []u8) BufferWriter {
        return .{ .bytes = bytes };
    }

    fn write(self: *BufferWriter, value: anytype) !void {
        const bytes = std.mem.asBytes(&value);
        if (self.offset + bytes.len > self.bytes.len) {
            return error.NoSpaceLeft;
        }
        @memcpy(self.bytes[self.offset .. self.offset + bytes.len], bytes);
        self.offset += bytes.len;
    }

    fn writeU8(self: *BufferWriter, value: u8) !void {
        try self.write(value);
    }

    fn writeU16(self: *BufferWriter, value: u16) !void {
        try self.write(value);
    }

    fn writeU32(self: *BufferWriter, value: u32) !void {
        try self.write(value);
    }

    fn writeI32(self: *BufferWriter, value: i32) !void {
        try self.write(value);
    }

    fn writeU64(self: *BufferWriter, value: u64) !void {
        try self.write(value);
    }

    fn writeBytes(self: *BufferWriter, value: []const u8) !void {
        if (self.offset + value.len > self.bytes.len) {
            return error.NoSpaceLeft;
        }

        @memcpy(self.bytes[self.offset .. self.offset + value.len], value);
        self.offset += value.len;
    }
};

const ExpectedViewportLink = struct {
    row: u32,
    start_column: u32,
    end_column_exclusive: u32,
    url: []const u8,
};

fn appendTestBytes(session: *Session, bytes: []const u8) u32 {
    return termux_ghostty_session_append(session, bytes.ptr, bytes.len);
}

fn expectViewportLinks(
    session: *Session,
    expected_top_row: i32,
    expected_rows: u32,
    expected_columns: u32,
    expected_segments: []const ExpectedViewportLink,
) !void {
    var buffer: [4096]u8 = undefined;
    const written_i32 = termux_ghostty_session_fill_viewport_links(session, buffer[0..].ptr, buffer.len);
    try testing.expect(written_i32 > 0);

    const written: usize = @intCast(written_i32);
    var offset: usize = 0;
    try testing.expectEqual(viewport_link_magic, readU32Native(buffer[0..written], &offset));
    try testing.expectEqual(expected_top_row, readI32Native(buffer[0..written], &offset));
    try testing.expectEqual(expected_rows, readU32Native(buffer[0..written], &offset));
    try testing.expectEqual(expected_columns, readU32Native(buffer[0..written], &offset));

    const segment_count = readU32Native(buffer[0..written], &offset);
    const string_table_bytes = readU32Native(buffer[0..written], &offset);
    try testing.expectEqual(expected_segments.len, segment_count);

    const records_start = offset;
    const string_table_start = records_start + (segment_count * viewport_link_record_bytes);
    try testing.expectEqual(written, string_table_start + string_table_bytes);
    const string_table = buffer[string_table_start..written];

    for (expected_segments, 0..) |expected, index| {
        var record_offset = records_start + (index * viewport_link_record_bytes);
        const row = readU32Native(buffer[0..written], &record_offset);
        const start_column = readU32Native(buffer[0..written], &record_offset);
        const end_column_exclusive = readU32Native(buffer[0..written], &record_offset);
        const string_offset = readU32Native(buffer[0..written], &record_offset);
        const string_length = readU32Native(buffer[0..written], &record_offset);

        try testing.expectEqual(expected.row, row);
        try testing.expectEqual(expected.start_column, start_column);
        try testing.expectEqual(expected.end_column_exclusive, end_column_exclusive);
        try testing.expectEqualSlices(u8, expected.url,
            string_table[string_offset .. string_offset + string_length]);
    }
}

fn readU32Native(bytes: []const u8, offset: *usize) u32 {
    const start = offset.*;
    offset.* = start + @sizeOf(u32);
    const raw: [4]u8 = .{ bytes[start], bytes[start + 1], bytes[start + 2], bytes[start + 3] };
    return @bitCast(raw);
}

fn readI32Native(bytes: []const u8, offset: *usize) i32 {
    const start = offset.*;
    offset.* = start + @sizeOf(i32);
    const raw: [4]u8 = .{ bytes[start], bytes[start + 1], bytes[start + 2], bytes[start + 3] };
    return @bitCast(raw);
}

fn expectOwnedText(actual: ?[*:0]u8, expected: ?[]const u8) !void {
    if (expected) |expected_text| {
        const owned_ptr = actual orelse return error.ExpectedText;
        const owned = std.mem.span(owned_ptr);
        defer std.heap.c_allocator.free(owned);
        try testing.expectEqualSlices(u8, expected_text, owned);
        return;
    }

    if (actual) |owned_ptr| {
        const owned = std.mem.span(owned_ptr);
        defer std.heap.c_allocator.free(owned);
        try testing.expectEqual(@as(usize, 0), owned.len);
        return;
    }

    try testing.expect(actual == null);
}

test "osc 9 stores pending desktop notification body" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    const result = appendTestBytes(session, "\x1B]9;Build finished\x07");
    try testing.expect((result & append_result_desktop_notification) != 0);
    try expectOwnedText(termux_ghostty_session_consume_notification_title(session), null);
    try expectOwnedText(termux_ghostty_session_consume_notification_body(session), "Build finished");
}

test "osc 777 stores pending desktop notification title and body" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    const result = appendTestBytes(session, "\x1B]777;notify;Build;Finished\x1B\\");
    try testing.expect((result & append_result_desktop_notification) != 0);
    try expectOwnedText(termux_ghostty_session_consume_notification_title(session), "Build");
    try expectOwnedText(termux_ghostty_session_consume_notification_body(session), "Finished");
    try testing.expect(termux_ghostty_session_consume_notification_title(session) == null);
    try testing.expect(termux_ghostty_session_consume_notification_body(session) == null);
}

test "osc 9;4 set stores progress state and value" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    const result = appendTestBytes(session, "\x1B]9;4;1;50\x07");
    try testing.expect((result & append_result_progress) != 0);
    try testing.expectEqual(@as(i32, @intFromEnum(ProgressState.set)), termux_ghostty_session_get_progress_state(session));
    try testing.expectEqual(@as(i32, 50), termux_ghostty_session_get_progress_value(session));
    try testing.expectEqual(@as(u64, 1), termux_ghostty_session_get_progress_generation(session));
}

test "osc 9;4 remove clears progress state" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    _ = appendTestBytes(session, "\x1B]9;4;3\x07");
    const result = appendTestBytes(session, "\x1B]9;4;0\x07");
    try testing.expect((result & append_result_progress) != 0);
    try testing.expectEqual(@as(i32, @intFromEnum(ProgressState.none)), termux_ghostty_session_get_progress_state(session));
    try testing.expectEqual(@as(i32, -1), termux_ghostty_session_get_progress_value(session));
    try testing.expect(termux_ghostty_session_get_progress_generation(session) >= 2);
}

test "reset clears progress and pending notification state" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    _ = appendTestBytes(session, "\x1B]777;notify;Build;Finished\x1B\\");
    _ = appendTestBytes(session, "\x1B]9;4;2;75\x07");

    termux_ghostty_session_reset(session);

    try testing.expect(termux_ghostty_session_consume_notification_title(session) == null);
    try testing.expect(termux_ghostty_session_consume_notification_body(session) == null);
    try testing.expectEqual(@as(i32, @intFromEnum(ProgressState.none)), termux_ghostty_session_get_progress_state(session));
    try testing.expectEqual(@as(i32, -1), termux_ghostty_session_get_progress_value(session));
}

test "queue mouse event updates pressed state and emits sgr bytes" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    session.terminal.flags.mouse_event = .button;
    session.terminal.flags.mouse_format = .sgr;

    const press_expected = "\x1B[<0;1;1M";
    try testing.expectEqual(
        @as(i32, @intCast(press_expected.len)),
        termux_ghostty_session_queue_mouse_event(session, 0, 1, 0, 5, 15, 100, 105, 10, 20, 5, 0, 0, 0),
    );
    try testing.expect(session.hasPressedMouseButtons());
    try testing.expect(session.mouse_last_cell_valid);
    try testing.expectEqual(@as(@TypeOf(session.mouse_last_cell.x), 0), session.mouse_last_cell.x);
    try testing.expectEqual(@as(@TypeOf(session.mouse_last_cell.y), 0), session.mouse_last_cell.y);

    var out: [64]u8 = undefined;
    const press_written = termux_ghostty_session_drain_pending_output(session, out[0..].ptr, out.len);
    try testing.expectEqualSlices(u8, press_expected, out[0..press_written]);

    const release_expected = "\x1B[<0;1;1m";
    try testing.expectEqual(
        @as(i32, @intCast(release_expected.len)),
        termux_ghostty_session_queue_mouse_event(session, 1, 1, 0, 5, 15, 100, 105, 10, 20, 5, 0, 0, 0),
    );
    try testing.expect(!session.hasPressedMouseButtons());

    const release_written = termux_ghostty_session_drain_pending_output(session, out[0..].ptr, out.len);
    try testing.expectEqualSlices(u8, release_expected, out[0..release_written]);
}

test "queue mouse event wheel buttons do not persist" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    session.terminal.flags.mouse_event = .normal;
    session.terminal.flags.mouse_format = .sgr;

    const expected = "\x1B[<64;1;1M";
    try testing.expectEqual(
        @as(i32, @intCast(expected.len)),
        termux_ghostty_session_queue_mouse_event(session, 0, 4, 0, 5, 15, 100, 105, 10, 20, 5, 0, 0, 0),
    );
    try testing.expect(!session.hasPressedMouseButtons());

    var out: [64]u8 = undefined;
    const written = termux_ghostty_session_drain_pending_output(session, out[0..].ptr, out.len);
    try testing.expectEqualSlices(u8, expected, out[0..written]);
}

test "queue mouse event ignores out of viewport motion without buttons" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    session.terminal.flags.mouse_event = .any;
    session.terminal.flags.mouse_format = .sgr;

    try testing.expectEqual(
        @as(i32, 0),
        termux_ghostty_session_queue_mouse_event(session, 2, 0, 0, -1, 15, 100, 105, 10, 20, 5, 0, 0, 0),
    );
    try testing.expect(!session.mouse_last_cell_valid);
}

test "queue mouse event encodes sgr pixels with padding" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    session.terminal.flags.mouse_event = .normal;
    session.terminal.flags.mouse_format = .sgr_pixels;

    const expected = "\x1B[<0;15;20M";
    try testing.expectEqual(
        @as(i32, @intCast(expected.len)),
        termux_ghostty_session_queue_mouse_event(session, 0, 1, 0, 15, 25, 100, 105, 10, 20, 5, 0, 0, 0),
    );

    var out: [64]u8 = undefined;
    const written = termux_ghostty_session_drain_pending_output(session, out[0..].ptr, out.len);
    try testing.expectEqualSlices(u8, expected, out[0..written]);
}

test "queue mouse event motion deduplicates by cell" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    session.terminal.flags.mouse_event = .any;
    session.terminal.flags.mouse_format = .sgr;

    const expected = "\x1B[<35;1;1M";
    try testing.expectEqual(
        @as(i32, @intCast(expected.len)),
        termux_ghostty_session_queue_mouse_event(session, 2, 0, 0, 5, 15, 100, 105, 10, 20, 5, 0, 0, 0),
    );
    try testing.expect(session.mouse_last_cell_valid);

    try testing.expectEqual(
        @as(i32, 0),
        termux_ghostty_session_queue_mouse_event(session, 2, 0, 0, 9, 19, 100, 105, 10, 20, 5, 0, 0, 0),
    );

    var out: [64]u8 = undefined;
    const written = termux_ghostty_session_drain_pending_output(session, out[0..].ptr, out.len);
    try testing.expectEqualSlices(u8, expected, out[0..written]);
}

test "fill viewport links serializes single osc8 segment" {
    const session = termux_ghostty_session_create(10, 5, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    _ = appendTestBytes(session, "\x1B]8;;https://example.com\x07abc\x1B]8;;\x07");

    try expectViewportLinks(session, 0, 5, 10, &.{
        .{
            .row = 0,
            .start_column = 0,
            .end_column_exclusive = 3,
            .url = "https://example.com",
        },
    });
}

test "fill viewport links serializes wrapped osc8 segments" {
    const session = termux_ghostty_session_create(4, 2, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    _ = appendTestBytes(session, "\x1B]8;;https://wrapped.example\x07abcdef\x1B]8;;\x07");

    try expectViewportLinks(session, 0, 2, 4, &.{
        .{
            .row = 0,
            .start_column = 0,
            .end_column_exclusive = 4,
            .url = "https://wrapped.example",
        },
        .{
            .row = 1,
            .start_column = 0,
            .end_column_exclusive = 2,
            .url = "https://wrapped.example",
        },
    });
}

test "fill viewport links keeps adjacent osc8 links separate" {
    const session = termux_ghostty_session_create(10, 2, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    _ = appendTestBytes(session,
        "\x1B]8;;https://one.example\x07ab\x1B]8;;\x07"
        ++ "\x1B]8;;https://two.example\x07cd\x1B]8;;\x07");

    try expectViewportLinks(session, 0, 2, 10, &.{
        .{
            .row = 0,
            .start_column = 0,
            .end_column_exclusive = 2,
            .url = "https://one.example",
        },
        .{
            .row = 0,
            .start_column = 2,
            .end_column_exclusive = 4,
            .url = "https://two.example",
        },
    });
}

test "fill viewport links tracks scrolled viewport rows" {
    const session = termux_ghostty_session_create(4, 2, 100, 10, 20) orelse return error.OutOfMemory;
    defer termux_ghostty_session_destroy(session);

    _ = appendTestBytes(session,
        "\x1B]8;;https://one.example\x07a\x1B]8;;\x07\r\n"
        ++ "\x1B]8;;https://two.example\x07b\x1B]8;;\x07\r\n"
        ++ "\x1B]8;;https://three.example\x07c\x1B]8;;\x07");
    _ = termux_ghostty_session_set_viewport_top_row(session, -1);

    try expectViewportLinks(session, -1, 2, 4, &.{
        .{
            .row = 0,
            .start_column = 0,
            .end_column_exclusive = 1,
            .url = "https://one.example",
        },
        .{
            .row = 1,
            .start_column = 0,
            .end_column_exclusive = 1,
            .url = "https://two.example",
        },
    });
}
