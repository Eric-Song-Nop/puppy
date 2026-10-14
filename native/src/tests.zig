const std = @import("std");
const testing = std.testing;
const term = @import("term.zig");
const frame = @import("frame.zig");

const Session = term.Session;

fn cellWord(buf: []const u8, cols: usize, x: usize, y: usize, word: usize) u32 {
    const i = (frame.header_words + (y * cols + x) * frame.cell_words + word) * 4;
    return std.mem.readInt(u32, buf[i..][0..4], .little);
}

fn headerWord(buf: []const u8, word: usize) u32 {
    return std.mem.readInt(u32, buf[word * 4 ..][0..4], .little);
}

test "plain text lands in the frame" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 3 });
    defer s.destroy();
    _ = s.feed("hi\r\n\x1b[31mR");

    var buf: [frame.capacityBytes(10, 3)]u8 = undefined;
    const n = try s.snapshot(&buf, false);
    try testing.expect(n > 0);
    try testing.expectEqual(@as(u32, 10), headerWord(&buf, frame.H.cols));
    try testing.expectEqual(@as(u32, 'h'), cellWord(&buf, 10, 0, 0, 0));
    try testing.expectEqual(@as(u32, 'i'), cellWord(&buf, 10, 1, 0, 0));
    try testing.expectEqual(@as(u32, 'R'), cellWord(&buf, 10, 0, 1, 0));
    // Red foreground differs from the default foreground.
    try testing.expect(cellWord(&buf, 10, 0, 1, 1) != headerWord(&buf, frame.H.foreground));
    try testing.expectEqual(@as(u32, 1), headerWord(&buf, frame.H.cursor_x));
    try testing.expectEqual(@as(u32, 1), headerWord(&buf, frame.H.cursor_y));

    // Nothing changed: no new frame.
    try testing.expectEqual(@as(usize, 0), try s.snapshot(&buf, false));
}

test "escape sequences split across writes" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    _ = s.feed("\x1b[");
    _ = s.feed("1mB");
    var buf: [frame.capacityBytes(10, 2)]u8 = undefined;
    _ = try s.snapshot(&buf, true);
    try testing.expectEqual(@as(u32, 'B'), cellWord(&buf, 10, 0, 0, 0));
    try testing.expect(cellWord(&buf, 10, 0, 0, 3) & frame.Attr.bold != 0);
}

test "utf8 split across writes and wide characters" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    const zh = "中";
    _ = s.feed(zh[0..1]);
    _ = s.feed(zh[1..]);
    var buf: [frame.capacityBytes(10, 2)]u8 = undefined;
    _ = try s.snapshot(&buf, true);
    try testing.expectEqual(@as(u32, 0x4E2D), cellWord(&buf, 10, 0, 0, 0));
    const wide = (cellWord(&buf, 10, 0, 0, 3) >> frame.Attr.wide_shift) & 3;
    try testing.expectEqual(@as(u32, 1), wide);
}

test "device attribute query produces a reply" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    const ev = s.feed("\x1b[c");
    try testing.expect(ev & term.Event.replies != 0);
    try testing.expect(std.mem.startsWith(u8, s.replies.items, "\x1b[?"));
}

test "title change raises an event" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    const ev = s.feed("\x1b]2;hello\x07");
    try testing.expect(ev & term.Event.title != 0);
    try testing.expectEqualStrings("hello", s.title().?);
}

test "resize changes the frame size" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    try s.resize(20, 5, 8, 16);
    var buf: [frame.capacityBytes(20, 5)]u8 = undefined;
    _ = try s.snapshot(&buf, false);
    try testing.expectEqual(@as(u32, 20), headerWord(&buf, frame.H.cols));
    try testing.expectEqual(@as(u32, 5), headerWord(&buf, frame.H.rows));
}

test "key encoding follows cursor key mode" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    var aw: std.Io.Writer.Allocating = .init(testing.allocator);
    defer aw.deinit();

    try s.encodeKey(&aw.writer, "arrow_up", "", 0, 0, 1, 0);
    try testing.expectEqualStrings("\x1b[A", aw.written());

    aw.clearRetainingCapacity();
    _ = s.feed("\x1b[?1h");
    try s.encodeKey(&aw.writer, "arrow_up", "", 0, 0, 1, 0);
    try testing.expectEqualStrings("\x1bOA", aw.written());

    aw.clearRetainingCapacity();
    // ctrl+c
    try s.encodeKey(&aw.writer, "key_c", "c", 0b10, 0, 1, 'c');
    try testing.expectEqualStrings("\x03", aw.written());
}

test "bracketed paste wraps data" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    var aw: std.Io.Writer.Allocating = .init(testing.allocator);
    defer aw.deinit();
    try s.encodePaste(&aw.writer, "ls");
    try testing.expectEqualStrings("ls", aw.written());
    aw.clearRetainingCapacity();
    _ = s.feed("\x1b[?2004h");
    try s.encodePaste(&aw.writer, "ls");
    try testing.expectEqualStrings("\x1b[200~ls\x1b[201~", aw.written());
}

test "theme colors keep program overrides" {
    const s = try Session.create(testing.allocator, .{ .cols = 4, .rows = 1 });
    defer s.destroy();
    try s.setDefaultColors(.{ .r = 1, .g = 2, .b = 3 }, .{ .r = 200, .g = 200, .b = 200 }, null, null);
    var buf: [frame.capacityBytes(4, 1)]u8 = undefined;
    _ = try s.snapshot(&buf, true);
    try testing.expectEqual(@as(u32, 0xFF010203), headerWord(&buf, frame.H.background));

    // OSC 11 overrides the background; a new theme default must not undo it.
    _ = s.feed("\x1b]11;rgb:10/20/30\x1b\\");
    try s.setDefaultColors(.{ .r = 9, .g = 9, .b = 9 }, .{ .r = 200, .g = 200, .b = 200 }, null, null);
    _ = try s.snapshot(&buf, true);
    try testing.expectEqual(@as(u32, 0xFF102030), headerWord(&buf, frame.H.background));
}

test "scrollback and viewport scrolling" {
    const s = try Session.create(testing.allocator, .{ .cols = 4, .rows = 2 });
    defer s.destroy();
    _ = s.feed("1\r\n2\r\n3\r\n4");
    var buf: [frame.capacityBytes(4, 2)]u8 = undefined;
    _ = try s.snapshot(&buf, true);
    try testing.expectEqual(@as(u32, '3'), cellWord(&buf, 4, 0, 0, 0));
    s.scroll(.{ .delta = -2 });
    _ = try s.snapshot(&buf, false);
    try testing.expectEqual(@as(u32, '1'), cellWord(&buf, 4, 0, 0, 0));
    try testing.expect(headerWord(&buf, frame.H.flags) & frame.Flag.scrolled_back != 0);
    s.scroll(.bottom);
    _ = try s.snapshot(&buf, false);
    try testing.expectEqual(@as(u32, '3'), cellWord(&buf, 4, 0, 0, 0));
}

test "denied clipboard writes are not collected" {
    const s = try Session.create(testing.allocator, .{ .cols = 10, .rows = 2 });
    defer s.destroy();
    s.clipboard_write_allowed = false;
    const ev = s.feed("\x1b]52;c;aGk=\x07");
    try testing.expect(ev & term.Event.clipboard == 0);
    s.clipboard_write_allowed = true;
    const ev2 = s.feed("\x1b]52;c;aGk=\x07");
    try testing.expect(ev2 & term.Event.clipboard != 0);
    try testing.expectEqualStrings("hi", s.clipboard.items);
}

test "kitty graphics: PNG transmit and place produces a placement" {
    const graphics = @import("graphics.zig");
    const s = try Session.create(testing.allocator, .{ .cols = 20, .rows = 5 });
    defer s.destroy();
    try s.resize(20, 5, 10, 20);
    // 1x1 red PNG, transmitted and placed (a=T, f=100), quietly (q=2).
    const png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==";
    _ = s.feed("\x1b_Ga=T,f=100,i=7,q=2;" ++ png ++ "\x1b\\");
    var out: [4 * graphics.placement_words]i32 = undefined;
    const n = graphics.placements(s.terminal, &out);
    try testing.expectEqual(@as(usize, 1), n);
    try testing.expectEqual(@as(i32, 7), out[graphics.P.image_id]);
    const px = (try graphics.imagePixels(testing.allocator, s.terminal, 7)).?;
    defer testing.allocator.free(px.data);
    try testing.expectEqual(@as(u32, 1), px.width);
    try testing.expectEqual(@as(u8, 0xFF), px.data[0]);
    try testing.expectEqual(@as(u8, 0x00), px.data[1]);
}

test "kitty graphics: support query is answered" {
    const s = try Session.create(testing.allocator, .{ .cols = 20, .rows = 5 });
    defer s.destroy();
    const ev = s.feed("\x1b_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\x1b\\");
    try testing.expect(ev & term.Event.replies != 0);
    try testing.expect(std.mem.indexOf(u8, s.replies.items, "OK") != null);
}
