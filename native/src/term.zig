//! One terminal session's VT state: a Ghostty terminal, its persistent
//! stream (so escape sequences may span writes), effect collection and
//! frame export.
//!
//! A `Session` is not thread safe. The Kotlin side owns each session from
//! a single serial executor (docs/design.md §6.2).
const std = @import("std");
const vt = @import("ghostty-vt");
const frame = @import("frame.zig");

const Allocator = std.mem.Allocator;
const Terminal = vt.Terminal;
const Stream = vt.TerminalStream;
const Handler = Stream.Handler;

/// Bits returned by `feed` describing effects raised while parsing.
pub const Event = struct {
    pub const replies: u32 = 1 << 0;
    pub const title: u32 = 1 << 1;
    pub const bell: u32 = 1 << 2;
    pub const clipboard: u32 = 1 << 3;
    pub const pwd: u32 = 1 << 4;
    pub const render_hold: u32 = 1 << 5;
    pub const reset: u32 = 1 << 6;
};

pub const Options = struct {
    cols: u16,
    rows: u16,
    scrollback_lines: usize = 10_000,
    /// Native memory budget for scrollback; lines alone do not bound memory.
    scrollback_bytes: usize = 64 * 1024 * 1024,
};

pub const Session = struct {
    alloc: Allocator,
    io: vt.TinyIo,
    terminal: *Terminal,
    stream: Stream,
    render: vt.RenderState = .empty,

    /// Bytes the terminal wants written back to the pty (DA, DSR, ...).
    replies: std.ArrayList(u8) = .empty,
    /// Last OSC 52 text the program asked to place on the clipboard.
    clipboard: std.ArrayList(u8) = .empty,
    events: u32 = 0,

    cell_width: u32 = 1,
    cell_height: u32 = 1,
    held: bool = false,
    /// False when the user's policy denies OSC 52 writes; programs are told so.
    clipboard_write_allowed: bool = true,
    force_full: bool = true,
    last_mouse_cell: ?vt.Coordinate = null,

    pub fn create(alloc: Allocator, opts: Options) !*Session {
        if (opts.cols == 0 or opts.rows == 0) return error.InvalidSize;

        const self = try alloc.create(Session);
        errdefer alloc.destroy(self);

        const t = try alloc.create(Terminal);
        errdefer alloc.destroy(t);

        self.* = .{
            .alloc = alloc,
            .io = .init,
            .terminal = t,
            .stream = undefined,
        };

        t.* = try .init(self.io.io(), alloc, .{
            .cols = opts.cols,
            .rows = opts.rows,
            .max_scrollback_bytes = opts.scrollback_bytes,
            .max_scrollback_lines = opts.scrollback_lines,
        });
        errdefer t.deinit(alloc);
        // We don't install Ghostty's shell integration.
        t.flags.shell_redraws_prompt = .false;

        var handler: Handler = t.vtHandler();
        handler.effects = .{
            .write_pty = &writePty,
            .bell = &bell,
            .color_scheme = null,
            .desktop_notification = null,
            .drag_and_drop = null,
            .device_attributes = &deviceAttributes,
            .enquiry = null,
            .xtversion = &xtversion,
            .title_changed = &titleChanged,
            .pwd_changed = &pwdChanged,
            .progress_report = null,
            .program_status = null,
            .semantic_prompt = null,
            .reset = &resetEffect,
            .size = &sizeEffect,
            .render_hold = &renderHold,
            .resize_window = null,
            .clipboard_write = &clipboardWrite,
            // Remote reads of the local clipboard are refused (design §7.3).
            .clipboard_read = null,
        };
        self.stream = .init(.{ .allocator = alloc, .handler = handler });
        return self;
    }

    pub fn destroy(self: *Session) void {
        const alloc = self.alloc;
        self.stream.deinit();
        self.render.deinit(alloc);
        self.terminal.deinit(alloc);
        alloc.destroy(self.terminal);
        self.replies.deinit(alloc);
        self.clipboard.deinit(alloc);
        alloc.destroy(self);
    }

    /// Parses raw pty output. Returns and clears the accumulated `Event` bits.
    pub fn feed(self: *Session, bytes: []const u8) u32 {
        self.stream.nextSlice(bytes);
        return self.takeEvents();
    }

    pub fn takeEvents(self: *Session) u32 {
        const ev = self.events;
        self.events = 0;
        return ev;
    }

    pub fn resize(self: *Session, cols: u16, rows: u16, cell_width: u32, cell_height: u32) !void {
        if (cols == 0 or rows == 0) return error.InvalidSize;
        self.cell_width = @max(cell_width, 1);
        self.cell_height = @max(cell_height, 1);
        try self.stream.handler.resize(.{
            .cols = cols,
            .rows = rows,
            .cell_size_px = .{ .width = self.cell_width, .height = self.cell_height },
        });
    }

    /// Sets the theme's default colors. Colors a program set through OSC
    /// stay in effect, so a cancelled preview restores only the defaults.
    pub fn setDefaultColors(
        self: *Session,
        background: vt.color.RGB,
        foreground: vt.color.RGB,
        cursor: ?vt.color.RGB,
        palette: ?*const vt.color.Palette,
    ) !void {
        const t = self.terminal;
        t.colors.background.default = background;
        t.colors.foreground.default = foreground;
        t.colors.cursor.default = cursor;
        if (palette) |p| {
            try t.colors.palette.changeDefault(self.alloc, p.*);
        } else {
            t.colors.palette.resetDefault(self.alloc);
        }
        self.invalidate();
    }

    /// Drops cached render state so the next snapshot is a full frame.
    pub fn invalidate(self: *Session) void {
        self.render.deinit(self.alloc);
        self.render = .empty;
        self.force_full = true;
    }

    pub const Scroll = union(enum) { delta: isize, top, bottom, row: usize };

    pub fn scroll(self: *Session, s: Scroll) void {
        self.terminal.scrollViewport(switch (s) {
            .delta => |d| .{ .delta = d },
            .top => .top,
            .bottom => .bottom,
            .row => |r| .{ .row = r },
        });
    }

    pub fn title(self: *const Session) ?[]const u8 {
        return self.terminal.getTitle();
    }

    pub fn pwd(self: *const Session) ?[]const u8 {
        return self.terminal.getPwd();
    }

    pub fn modeFlags(self: *const Session) u32 {
        const t = self.terminal;
        var flags: u32 = 0;
        if (t.screens.active_key == .alternate) flags |= frame.Flag.alt_screen;
        if (t.flags.mouse_event != .none) flags |= frame.Flag.mouse_tracking;
        if (t.modes.get(.bracketed_paste)) flags |= frame.Flag.bracketed_paste;
        if (t.modes.get(.focus_event)) flags |= frame.Flag.focus_events;
        if (t.modes.get(.cursor_keys)) flags |= frame.Flag.cursor_keys;
        if (self.held) flags |= frame.Flag.render_hold;
        if (self.terminal.screens.active.pages.viewport != .active) flags |= frame.Flag.scrolled_back;
        return flags;
    }

    /// Writes a frame into `out` if anything changed since the last one
    /// (or `force`). Returns the number of bytes written, 0 when there is
    /// nothing new, or an error if `out` is too small.
    pub fn snapshot(self: *Session, out: []u8, force: bool) !usize {
        if (self.held and !force) return 0;
        try self.render.update(self.alloc, self.terminal);
        if (!force and !self.force_full and self.render.dirty == .false) return 0;
        const n = try frame.write(out, &self.render, self.terminal, self.modeFlags());
        self.render.dirty = .false;
        self.force_full = false;
        return n;
    }

    pub fn encodeKey(
        self: *const Session,
        w: *std.Io.Writer,
        key_name: []const u8,
        utf8: []const u8,
        mods: u16,
        consumed_mods: u16,
        action: u8,
        unshifted: u21,
    ) !void {
        const KeyAction = vt.input.KeyAction;
        const event: vt.input.KeyEvent = .{
            .action = std.enums.fromInt(KeyAction, action) orelse .press,
            .key = std.meta.stringToEnum(vt.input.Key, key_name) orelse .unidentified,
            .mods = @bitCast(mods),
            .consumed_mods = @bitCast(consumed_mods),
            .utf8 = utf8,
            .unshifted_codepoint = unshifted,
        };
        try vt.input.encodeKey(w, event, .fromTerminal(self.terminal));
    }

    pub fn encodeMouse(
        self: *Session,
        w: *std.Io.Writer,
        action: u8,
        button: u8,
        mods: u16,
        x: f32,
        y: f32,
        screen_width: u32,
        screen_height: u32,
        any_pressed: bool,
    ) !void {
        const t = self.terminal;
        const MouseAction = vt.input.MouseAction;
        const MouseButton = vt.input.MouseButton;
        try vt.input.encodeMouse(w, .{
            .action = std.enums.fromInt(MouseAction, action) orelse .press,
            .button = if (button == 0) null else std.enums.fromInt(MouseButton, button),
            .mods = @bitCast(mods),
            .pos = .{ .x = x, .y = y },
        }, .{
            .event = t.flags.mouse_event,
            .format = t.flags.mouse_format,
            .size = .{
                .screen = .{ .width = screen_width, .height = screen_height },
                .cell = .{ .width = self.cell_width, .height = self.cell_height },
                .padding = .{},
            },
            .any_button_pressed = any_pressed,
            .last_cell = &self.last_mouse_cell,
        });
    }

    pub fn encodePaste(self: *const Session, w: *std.Io.Writer, data: []const u8) !void {
        try vt.input.encodePasteWriter(w, data, .fromTerminal(self.terminal));
    }

    pub fn encodeFocus(self: *const Session, w: *std.Io.Writer, gained: bool) !void {
        if (!self.terminal.modes.get(.focus_event)) return;
        try vt.input.encodeFocus(w, if (gained) .gained else .lost);
    }

    fn fromHandler(handler: *Handler) *Session {
        const stream: *Stream = @fieldParentPtr("handler", handler);
        return @fieldParentPtr("stream", stream);
    }

    fn writePty(handler: *Handler, data: []const u8) void {
        const self = fromHandler(handler);
        self.replies.appendSlice(self.alloc, data) catch return;
        self.events |= Event.replies;
    }

    fn bell(handler: *Handler) void {
        fromHandler(handler).events |= Event.bell;
    }

    fn titleChanged(handler: *Handler) void {
        fromHandler(handler).events |= Event.title;
    }

    fn pwdChanged(handler: *Handler) void {
        fromHandler(handler).events |= Event.pwd;
    }

    fn resetEffect(handler: *Handler) void {
        fromHandler(handler).events |= Event.reset | Event.title;
    }

    fn deviceAttributes(_: *Handler) vt.device_attributes.Attributes {
        return .{};
    }

    fn xtversion(_: *Handler) []const u8 {
        return "puppy(ghostty-vt)";
    }

    fn sizeEffect(handler: *Handler) ?vt.size_report.Size {
        const self = fromHandler(handler);
        const t = self.terminal;
        return .{
            .rows = t.rows,
            .columns = t.cols,
            .cell_width = self.cell_width,
            .cell_height = self.cell_height,
        };
    }

    fn renderHold(handler: *Handler, held: bool) void {
        const self = fromHandler(handler);
        if (held) {
            // Capture the complete frame the program wants kept on screen.
            self.render.update(self.alloc, self.terminal) catch {};
        }
        self.held = held;
        self.events |= Event.render_hold;
    }

    fn clipboardWrite(handler: *Handler, write: vt.clipboard.Write) void {
        const self = fromHandler(handler);
        if (!self.clipboard_write_allowed) {
            write.reply(.denied);
            return;
        }
        for (write.contents) |content| {
            if (!vt.clipboard.isTextMime(content.mime)) continue;
            self.clipboard.clearRetainingCapacity();
            self.clipboard.appendSlice(self.alloc, content.data) catch {
                write.reply(.io_error);
                return;
            };
            self.events |= Event.clipboard;
            // With the "ask" policy the app still asks before touching the
            // system clipboard; the reply can't wait for that answer.
            write.reply(.{ .success = .{} });
            return;
        }
        write.reply(.unsupported);
    }
};
