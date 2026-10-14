//! JNI entry points for libpuppy.so.
//!
//! Text crosses the boundary as UTF-8 byte arrays, never `jstring`: JNI's
//! "modified UTF-8" encodes supplementary characters differently.
const std = @import("std");
const c = @import("c");
const term = @import("term.zig");
const frame = @import("frame.zig");
const pty = @import("pty.zig");

const alloc = std.heap.c_allocator;

pub const std_options: std.Options = .{ .log_level = .err };

const Env = [*c]c.JNIEnv;

// ---------------------------------------------------------------- helpers

fn session(handle: c.jlong) ?*term.Session {
    if (handle == 0) return null;
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

fn newBytes(env: Env, data: []const u8) c.jbyteArray {
    const arr = env.*.*.NewByteArray.?(env, @intCast(data.len));
    if (arr == null) return null;
    if (data.len > 0) env.*.*.SetByteArrayRegion.?(env, arr, 0, @intCast(data.len), @ptrCast(data.ptr));
    return arr;
}

/// Copies a Java byte[] into a native buffer; the caller frees it.
fn getBytes(env: Env, arr: c.jbyteArray) ?[]u8 {
    if (arr == null) return null;
    const len: usize = @intCast(env.*.*.GetArrayLength.?(env, arr));
    const buf = alloc.alloc(u8, len) catch return null;
    if (len > 0) env.*.*.GetByteArrayRegion.?(env, arr, 0, @intCast(len), @ptrCast(buf.ptr));
    return buf;
}

/// Copies a Java byte[] into a NUL terminated native string.
fn getCString(env: Env, arr: c.jbyteArray) ?[:0]u8 {
    if (arr == null) return null;
    const len: usize = @intCast(env.*.*.GetArrayLength.?(env, arr));
    const buf = alloc.allocSentinel(u8, len, 0) catch return null;
    if (len > 0) env.*.*.GetByteArrayRegion.?(env, arr, 0, @intCast(len), @ptrCast(buf.ptr));
    return buf;
}

/// Converts a Java byte[][] into a NULL terminated array of C strings.
fn getCStringArray(env: Env, arr: c.jobjectArray) ?[:null]?[*:0]const u8 {
    const len: usize = if (arr == null) 0 else @intCast(env.*.*.GetArrayLength.?(env, arr));
    const out = alloc.allocSentinel(?[*:0]const u8, len, null) catch return null;
    for (0..len) |i| {
        const item: c.jbyteArray = env.*.*.GetObjectArrayElement.?(env, arr, @intCast(i));
        const s = getCString(env, item) orelse {
            freeCStringArray(out[0..i :null]);
            return null;
        };
        out[i] = s.ptr;
        env.*.*.DeleteLocalRef.?(env, item);
    }
    return out;
}

fn freeCStringArray(arr: [:null]?[*:0]const u8) void {
    for (arr) |s| if (s) |p| alloc.free(std.mem.span(p));
    alloc.free(arr);
}

/// Runs an encoder into a temporary buffer and returns its bytes, or null
/// when the encoder produced nothing.
fn encoded(env: Env, aw: *std.Io.Writer.Allocating) c.jbyteArray {
    const out = aw.written();
    if (out.len == 0) return null;
    return newBytes(env, out);
}

fn rgb(argb: c.jint) @import("ghostty-vt").color.RGB {
    const v: u32 = @bitCast(argb);
    return .{ .r = @truncate(v >> 16), .g = @truncate(v >> 8), .b = @truncate(v) };
}

// --------------------------------------------------------- NativeTerminal

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_create(
    _: Env,
    _: c.jclass,
    cols: c.jint,
    rows: c.jint,
    scrollback_lines: c.jint,
) c.jlong {
    const s = term.Session.create(alloc, .{
        .cols = @intCast(std.math.clamp(cols, 1, std.math.maxInt(u16))),
        .rows = @intCast(std.math.clamp(rows, 1, std.math.maxInt(u16))),
        .scrollback_lines = @intCast(@max(scrollback_lines, 0)),
    }) catch return 0;
    return @intCast(@intFromPtr(s));
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_destroy(_: Env, _: c.jclass, h: c.jlong) void {
    if (session(h)) |s| s.destroy();
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_feed(
    env: Env,
    _: c.jclass,
    h: c.jlong,
    data: c.jbyteArray,
    offset: c.jint,
    len: c.jint,
) c.jint {
    const s = session(h) orelse return 0;
    if (len <= 0) return 0;
    const ptr = env.*.*.GetPrimitiveArrayCritical.?(env, data, null) orelse return 0;
    const bytes: [*]const u8 = @ptrCast(ptr);
    // Parsing never calls back into Java, so the critical region is safe.
    const ev = s.feed(bytes[@intCast(offset)..][0..@intCast(len)]);
    env.*.*.ReleasePrimitiveArrayCritical.?(env, data, ptr, c.JNI_ABORT);
    return @bitCast(ev);
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_takeReplies(env: Env, _: c.jclass, h: c.jlong) c.jbyteArray {
    const s = session(h) orelse return null;
    if (s.replies.items.len == 0) return null;
    const arr = newBytes(env, s.replies.items);
    s.replies.clearRetainingCapacity();
    return arr;
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_takeClipboard(env: Env, _: c.jclass, h: c.jlong) c.jbyteArray {
    const s = session(h) orelse return null;
    const arr = newBytes(env, s.clipboard.items);
    s.clipboard.clearRetainingCapacity();
    return arr;
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_resize(
    _: Env,
    _: c.jclass,
    h: c.jlong,
    cols: c.jint,
    rows: c.jint,
    cell_w: c.jint,
    cell_h: c.jint,
) c.jboolean {
    const s = session(h) orelse return c.JNI_FALSE;
    if (cols <= 0 or rows <= 0 or cols > std.math.maxInt(u16) or rows > std.math.maxInt(u16)) return c.JNI_FALSE;
    s.resize(@intCast(cols), @intCast(rows), @intCast(@max(cell_w, 1)), @intCast(@max(cell_h, 1))) catch return c.JNI_FALSE;
    return c.JNI_TRUE;
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_setColors(
    env: Env,
    _: c.jclass,
    h: c.jlong,
    bg: c.jint,
    fg: c.jint,
    cursor: c.jint,
    has_cursor: c.jboolean,
    palette: c.jintArray,
) void {
    const s = session(h) orelse return;
    const vt = @import("ghostty-vt");
    var pal: vt.color.Palette = vt.color.default;
    var have_palette = false;
    if (palette != null) {
        const n: usize = @intCast(env.*.*.GetArrayLength.?(env, palette));
        var tmp: [256]c.jint = undefined;
        const take = @min(n, 256);
        env.*.*.GetIntArrayRegion.?(env, palette, 0, @intCast(take), &tmp);
        for (0..take) |i| pal[i] = rgb(tmp[i]);
        have_palette = true;
    }
    s.setDefaultColors(
        rgb(bg),
        rgb(fg),
        if (has_cursor != 0) rgb(cursor) else null,
        if (have_palette) &pal else null,
    ) catch {};
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_scroll(
    _: Env,
    _: c.jclass,
    h: c.jlong,
    kind: c.jint,
    value: c.jint,
) void {
    const s = session(h) orelse return;
    s.scroll(switch (kind) {
        0 => .{ .delta = value },
        1 => .top,
        2 => .bottom,
        else => .{ .row = @intCast(@max(value, 0)) },
    });
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_frameCapacity(_: Env, _: c.jclass, cols: c.jint, rows: c.jint) c.jint {
    return @intCast(frame.capacityBytes(@intCast(@max(cols, 1)), @intCast(@max(rows, 1))));
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_snapshot(
    env: Env,
    _: c.jclass,
    h: c.jlong,
    buffer: c.jobject,
    force: c.jboolean,
) c.jint {
    const s = session(h) orelse return 0;
    const ptr = env.*.*.GetDirectBufferAddress.?(env, buffer) orelse return -1;
    const cap = env.*.*.GetDirectBufferCapacity.?(env, buffer);
    if (cap <= 0) return -1;
    const out = @as([*]u8, @ptrCast(ptr))[0..@intCast(cap)];
    const n = s.snapshot(out, force != 0) catch return -1;
    return @intCast(n);
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_setClipboardWriteAllowed(_: Env, _: c.jclass, h: c.jlong, allowed: c.jboolean) void {
    if (session(h)) |s| s.clipboard_write_allowed = allowed != 0;
}

/// The terminal's current grid as (cols << 16) | rows. Programs can change it
/// themselves (DECCOLM), so frame buffers are sized from this.
export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_gridSize(_: Env, _: c.jclass, h: c.jlong) c.jint {
    const s = session(h) orelse return 0;
    return @bitCast((@as(u32, s.terminal.cols) << 16) | @as(u32, s.terminal.rows));
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_invalidate(_: Env, _: c.jclass, h: c.jlong) void {
    if (session(h)) |s| s.invalidate();
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_title(env: Env, _: c.jclass, h: c.jlong) c.jbyteArray {
    const s = session(h) orelse return null;
    return newBytes(env, s.title() orelse return null);
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_pwd(env: Env, _: c.jclass, h: c.jlong) c.jbyteArray {
    const s = session(h) orelse return null;
    return newBytes(env, s.pwd() orelse return null);
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_modes(_: Env, _: c.jclass, h: c.jlong) c.jint {
    const s = session(h) orelse return 0;
    return @bitCast(s.modeFlags());
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_encodeKey(
    env: Env,
    _: c.jclass,
    h: c.jlong,
    key: c.jbyteArray,
    text: c.jbyteArray,
    mods: c.jint,
    consumed: c.jint,
    action: c.jint,
    unshifted: c.jint,
) c.jbyteArray {
    const s = session(h) orelse return null;
    const key_name = getBytes(env, key) orelse return null;
    defer alloc.free(key_name);
    const utf8 = getBytes(env, text) orelse &.{};
    defer if (utf8.len > 0) alloc.free(utf8);

    var aw: std.Io.Writer.Allocating = .init(alloc);
    defer aw.deinit();
    s.encodeKey(
        &aw.writer,
        key_name,
        utf8,
        @truncate(@as(u32, @bitCast(mods))),
        @truncate(@as(u32, @bitCast(consumed))),
        @intCast(std.math.clamp(action, 0, 2)),
        @intCast(std.math.clamp(unshifted, 0, 0x10FFFF)),
    ) catch return null;
    return encoded(env, &aw);
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_encodeMouse(
    env: Env,
    _: c.jclass,
    h: c.jlong,
    action: c.jint,
    button: c.jint,
    mods: c.jint,
    x: c.jfloat,
    y: c.jfloat,
    width: c.jint,
    height: c.jint,
    any_pressed: c.jboolean,
) c.jbyteArray {
    const s = session(h) orelse return null;
    var aw: std.Io.Writer.Allocating = .init(alloc);
    defer aw.deinit();
    s.encodeMouse(
        &aw.writer,
        @intCast(std.math.clamp(action, 0, 2)),
        @intCast(std.math.clamp(button, 0, 11)),
        @truncate(@as(u32, @bitCast(mods))),
        x,
        y,
        @intCast(@max(width, 1)),
        @intCast(@max(height, 1)),
        any_pressed != 0,
    ) catch return null;
    return encoded(env, &aw);
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_encodePaste(env: Env, _: c.jclass, h: c.jlong, data: c.jbyteArray) c.jbyteArray {
    const s = session(h) orelse return null;
    const bytes = getBytes(env, data) orelse return null;
    defer alloc.free(bytes);
    var aw: std.Io.Writer.Allocating = .init(alloc);
    defer aw.deinit();
    s.encodePaste(&aw.writer, bytes) catch return null;
    return encoded(env, &aw);
}

export fn Java_org_pocketlab_puppy_terminal_NativeTerminal_encodeFocus(env: Env, _: c.jclass, h: c.jlong, gained: c.jboolean) c.jbyteArray {
    const s = session(h) orelse return null;
    var aw: std.Io.Writer.Allocating = .init(alloc);
    defer aw.deinit();
    s.encodeFocus(&aw.writer, gained != 0) catch return null;
    return encoded(env, &aw);
}

// -------------------------------------------------------------------- Pty

export fn Java_org_pocketlab_puppy_session_local_Pty_spawn(
    env: Env,
    _: c.jclass,
    path: c.jbyteArray,
    argv: c.jobjectArray,
    envp: c.jobjectArray,
    cwd: c.jbyteArray,
    rows: c.jint,
    cols: c.jint,
    width_px: c.jint,
    height_px: c.jint,
    out_pid: c.jintArray,
) c.jint {
    const p = getCString(env, path) orelse return -1;
    defer alloc.free(p);
    const a = getCStringArray(env, argv) orelse return -1;
    defer freeCStringArray(a);
    const e = getCStringArray(env, envp) orelse return -1;
    defer freeCStringArray(e);
    const dir = getCString(env, cwd);
    defer if (dir) |d| alloc.free(d);

    const result = pty.spawn(
        p.ptr,
        a.ptr,
        e.ptr,
        if (dir) |d| d.ptr else null,
        clampU16(rows),
        clampU16(cols),
        clampU16(width_px),
        clampU16(height_px),
    ) catch return -1;
    var pid: c.jint = result.pid;
    env.*.*.SetIntArrayRegion.?(env, out_pid, 0, 1, &pid);
    return result.fd;
}

fn clampU16(v: c.jint) u16 {
    return @intCast(std.math.clamp(v, 0, std.math.maxInt(u16)));
}

export fn Java_org_pocketlab_puppy_session_local_Pty_setWindowSize(
    _: Env,
    _: c.jclass,
    fd: c.jint,
    rows: c.jint,
    cols: c.jint,
    width_px: c.jint,
    height_px: c.jint,
) void {
    pty.setWindowSize(fd, clampU16(rows), clampU16(cols), clampU16(width_px), clampU16(height_px));
}

export fn Java_org_pocketlab_puppy_session_local_Pty_waitFor(_: Env, _: c.jclass, pid: c.jint) c.jint {
    return pty.waitFor(pid);
}

export fn Java_org_pocketlab_puppy_session_local_Pty_signalGroup(_: Env, _: c.jclass, pid: c.jint, sig: c.jint) void {
    pty.signalGroup(pid, sig);
}

export fn Java_org_pocketlab_puppy_session_local_Pty_close(_: Env, _: c.jclass, fd: c.jint) void {
    pty.close(fd);
}
