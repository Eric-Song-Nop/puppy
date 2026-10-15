//! Pure path logic for the remap preload library (remap.zig), kept apart
//! so it can be unit tested on the host without interposing libc.

const std = @import("std");

/// Rewrites `path` if it starts with `from` (as a whole path component).
/// Returns `path` unchanged, or a NUL-terminated rewrite stored in `buf`.
/// Paths too long for `buf` are returned unchanged.
pub fn map(path: [:0]const u8, from: []const u8, to: []const u8, buf: []u8) [:0]const u8 {
    if (from.len == 0 or !std.mem.startsWith(u8, path, from)) return path;
    if (path.len > from.len and path[from.len] != '/') return path;
    const rest = path[from.len..];
    const n = to.len + rest.len;
    if (n + 1 > buf.len) return path;
    @memcpy(buf[0..to.len], to);
    @memcpy(buf[to.len..n], rest);
    buf[n] = 0;
    return buf[0..n :0];
}

pub const Shebang = struct {
    interpreter: []const u8,
    /// The single optional argument after the interpreter, as the kernel passes it.
    arg: ?[]const u8,
};

/// Parses a `#!` line the way Linux does: the interpreter is the first
/// word, everything after the following blanks (trailing blanks trimmed) is
/// one optional argument. Returns null if `head` is not a script header.
pub fn parseShebang(head: []const u8) ?Shebang {
    if (head.len < 3 or head[0] != '#' or head[1] != '!') return null;
    const eol = std.mem.indexOfScalar(u8, head, '\n') orelse head.len;
    const line = std.mem.trim(u8, head[2..eol], " \t\r");
    if (line.len == 0) return null;
    const sep = std.mem.indexOfAny(u8, line, " \t") orelse return .{ .interpreter = line, .arg = null };
    const arg = std.mem.trim(u8, line[sep..], " \t");
    return .{ .interpreter = line[0..sep], .arg = if (arg.len == 0) null else arg };
}

test "map rewrites whole-component prefixes only" {
    var buf: [256]u8 = undefined;
    const from = "/data/data/com.termux";
    const to = "/data/data/org.pocketlab.puppy";
    try std.testing.expectEqualStrings(
        "/data/data/org.pocketlab.puppy/files/usr/bin/sh",
        map("/data/data/com.termux/files/usr/bin/sh", from, to, &buf),
    );
    try std.testing.expectEqualStrings("/data/data/org.pocketlab.puppy", map("/data/data/com.termux", from, to, &buf));
    // Another package that merely shares the prefix is left alone.
    try std.testing.expectEqualStrings("/data/data/com.termux.api/x", map("/data/data/com.termux.api/x", from, to, &buf));
    try std.testing.expectEqualStrings("/system/bin/sh", map("/system/bin/sh", from, to, &buf));
    var tiny: [8]u8 = undefined;
    try std.testing.expectEqualStrings("/data/data/com.termux/x", map("/data/data/com.termux/x", from, to, &tiny));
}

test "parseShebang follows kernel rules" {
    const a = parseShebang("#!/data/data/com.termux/files/usr/bin/sh\necho hi").?;
    try std.testing.expectEqualStrings("/data/data/com.termux/files/usr/bin/sh", a.interpreter);
    try std.testing.expect(a.arg == null);
    const b = parseShebang("#! /usr/bin/env  python3 -u \r\n").?;
    try std.testing.expectEqualStrings("/usr/bin/env", b.interpreter);
    try std.testing.expectEqualStrings("python3 -u", b.arg.?);
    try std.testing.expect(parseShebang("\x7fELF") == null);
    try std.testing.expect(parseShebang("#!\n") == null);
}
