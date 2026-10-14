//! Kitty graphics protocol export: visible image placements and image
//! pixels for the Android renderer. Placement geometry follows Ghostty's
//! C API (src/terminal/c/kitty_graphics.zig, computeViewportPos).
const std = @import("std");
const vt = @import("ghostty-vt");
const wuffs = @import("wuffs");

const Terminal = vt.Terminal;

/// Ints per exported placement. Keep in sync with `KittyPlacement.kt`.
pub const placement_words = 14;

pub const P = struct {
    pub const image_id = 0;
    pub const generation_lo = 1;
    pub const generation_hi = 2;
    pub const col = 3; // viewport column, may be negative
    pub const row = 4; // viewport row, may be negative
    pub const x_offset = 5; // pixels within the first cell
    pub const y_offset = 6;
    pub const width = 7; // destination size in terminal pixels
    pub const height = 8;
    pub const source_x = 9;
    pub const source_y = 10;
    pub const source_width = 11;
    pub const source_height = 12;
    pub const z = 13;
};

/// Installs the PNG decoder. libghostty-vt leaves it unset; without it,
/// PNG transmissions (what most programs send) are rejected.
pub fn installDecoder() void {
    if (vt.sys.decode_png == null) vt.sys.decode_png = &decodePng;
}

fn decodePng(alloc: std.mem.Allocator, data: []const u8) vt.sys.DecodeError!vt.sys.Image {
    const result = wuffs.png.decode(alloc, data) catch |err| switch (err) {
        error.WuffsError, error.Overflow => return error.InvalidData,
        error.OutOfMemory => return error.OutOfMemory,
    };
    return .{ .width = result.width, .height = result.height, .data = result.data };
}

/// True if images or placements changed (or moved) since the last call.
pub fn takeDirty(t: *Terminal) bool {
    const storage = &t.screens.active.kitty_images;
    const dirty = storage.dirty;
    storage.dirty = false;
    return dirty;
}

/// Writes visible placements into `out` (placement_words each), lowest z
/// first. Returns how many were written.
pub fn placements(t: *Terminal, out: []i32) usize {
    const storage = &t.screens.active.kitty_images;
    const max = out.len / placement_words;
    var n: usize = 0;
    var it = storage.placements.iterator();
    while (it.next()) |entry| {
        if (n >= max) break;
        const p = entry.value_ptr;
        const image = storage.imageById(entry.key_ptr.image_id) orelse continue;
        if (image.data != .complete) continue;
        const pos = viewportPos(p, &image, t) orelse continue;
        const size = p.pixelSize(image, t);
        const src = p.sourceRect(image);
        const o = out[n * placement_words ..][0..placement_words];
        o[P.image_id] = @bitCast(image.id);
        o[P.generation_lo] = @bitCast(@as(u32, @truncate(image.generation)));
        o[P.generation_hi] = @bitCast(@as(u32, @truncate(image.generation >> 32)));
        o[P.col] = pos.col;
        o[P.row] = pos.row;
        o[P.x_offset] = @intCast(@min(p.x_offset, std.math.maxInt(i32)));
        o[P.y_offset] = @intCast(@min(p.y_offset, std.math.maxInt(i32)));
        o[P.width] = @intCast(@min(size.width, std.math.maxInt(i32)));
        o[P.height] = @intCast(@min(size.height, std.math.maxInt(i32)));
        o[P.source_x] = @intCast(@min(src.x, std.math.maxInt(i32)));
        o[P.source_y] = @intCast(@min(src.y, std.math.maxInt(i32)));
        o[P.source_width] = @intCast(@min(src.width, std.math.maxInt(i32)));
        o[P.source_height] = @intCast(@min(src.height, std.math.maxInt(i32)));
        o[P.z] = p.z;
        n += 1;
    }
    // Draw order: lower z first (insertion sort; there are few placements).
    var i: usize = 1;
    while (i < n) : (i += 1) {
        var j = i;
        while (j > 0 and out[(j - 1) * placement_words + P.z] > out[j * placement_words + P.z]) : (j -= 1) {
            const a = out[(j - 1) * placement_words ..][0..placement_words];
            const b = out[j * placement_words ..][0..placement_words];
            var tmp: [placement_words]i32 = undefined;
            @memcpy(&tmp, a);
            @memcpy(a, b);
            @memcpy(b, &tmp);
        }
    }
    return n;
}

const Pos = struct { col: i32, row: i32 };

/// Viewport-relative position of a placement, or null if it is virtual
/// (unicode placeholders), pruned or entirely off screen.
fn viewportPos(p: anytype, image: anytype, t: *Terminal) ?Pos {
    const storage = &t.screens.active.kitty_images;
    var col_offset: i32 = 0;
    var row_offset: i32 = 0;
    const pin = switch (p.location) {
        .pin => |pin| pin,
        .virtual => return null,
        .relative => |rel| blk: {
            const chain = storage.resolveChain(rel) orelse return null;
            col_offset = chain.horizontal_offset;
            row_offset = chain.vertical_offset;
            switch (chain.root.location) {
                .pin => |root| break :blk root,
                else => return null,
            }
        },
    };
    if (pin.garbage) return null;
    const pages = &t.screens.active.pages;
    const at = pages.pointFromPin(.screen, pin.*) orelse return null;
    const top = pages.pointFromPin(.screen, pages.getTopLeft(.viewport)) orelse return null;
    const row: i32 = (@as(i32, @intCast(at.screen.y)) - @as(i32, @intCast(top.screen.y))) +| row_offset;
    const col: i32 = @as(i32, @intCast(at.screen.x)) +| col_offset;
    const grid = p.gridSize(image.*, t);
    const visible = @as(i64, row) + grid.rows > 0 and row < @as(i32, t.rows) and
        @as(i64, col) + grid.cols > 0 and col < @as(i32, t.cols);
    return if (visible) .{ .col = col, .row = row } else null;
}

pub const Pixels = struct {
    width: u32,
    height: u32,
    /// Premultiplied RGBA, as Android's ARGB_8888 bitmaps expect.
    data: []u8,
};

/// Copies an image's pixels as premultiplied RGBA. The caller frees `data`.
pub fn imagePixels(alloc: std.mem.Allocator, t: *Terminal, id: u32) !?Pixels {
    const storage = &t.screens.active.kitty_images;
    const image = storage.imageById(id) orelse return null;
    const src = switch (image.data) {
        .complete => |d| d,
        else => return null,
    };
    const count = @as(usize, image.width) * image.height;
    const out = try alloc.alloc(u8, count * 4);
    errdefer alloc.free(out);
    const channels: usize = switch (image.format) {
        .gray => 1,
        .gray_alpha => 2,
        .rgb => 3,
        .rgba => 4,
        else => return error.UnsupportedFormat,
    };
    if (src.len < count * channels) return error.InvalidData;
    for (0..count) |i| {
        const s = src[i * channels ..][0..channels];
        var r: u32 = 0;
        var g: u32 = 0;
        var b: u32 = 0;
        var a: u32 = 255;
        switch (channels) {
            1 => { r = s[0]; g = s[0]; b = s[0]; },
            2 => { r = s[0]; g = s[0]; b = s[0]; a = s[1]; },
            3 => { r = s[0]; g = s[1]; b = s[2]; },
            else => { r = s[0]; g = s[1]; b = s[2]; a = s[3]; },
        }
        const d = out[i * 4 ..][0..4];
        d[0] = @intCast(r * a / 255);
        d[1] = @intCast(g * a / 255);
        d[2] = @intCast(b * a / 255);
        d[3] = @intCast(a);
    }
    return .{ .width = image.width, .height = image.height, .data = out };
}
