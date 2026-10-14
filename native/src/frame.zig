//! Frame export: copies a RenderState into a flat, little-endian buffer
//! the Kotlin renderer decodes. Native pointers never cross to Kotlin.
//!
//! Layout (all u32):
//!   header[header_words]
//!   cells[cols * rows][cell_words]
//!   extra[extra_count]         grapheme continuation codepoints
//!
//! Keep in sync with `TerminalFrame.kt`.
const std = @import("std");
const vt = @import("ghostty-vt");

pub const version: u32 = 1;
pub const header_words = 16;
pub const cell_words = 4;

pub const H = struct {
    pub const version = 0;
    pub const cols = 1;
    pub const rows = 2;
    pub const cursor_x = 3; // -1 when outside the viewport
    pub const cursor_y = 4;
    pub const cursor = 5; // bit0 visible, bit1 blinking, bit2 wide tail, bits 8.. visual style
    pub const background = 6;
    pub const foreground = 7;
    pub const cursor_color = 8; // 0 = unset
    pub const scroll_total = 9;
    pub const scroll_offset = 10;
    pub const scroll_len = 11;
    pub const flags = 12;
    pub const extra_count = 13;
};

pub const Flag = struct {
    pub const alt_screen: u32 = 1 << 0;
    pub const mouse_tracking: u32 = 1 << 1;
    pub const bracketed_paste: u32 = 1 << 2;
    pub const focus_events: u32 = 1 << 3;
    pub const cursor_keys: u32 = 1 << 4;
    pub const render_hold: u32 = 1 << 5;
    pub const scrolled_back: u32 = 1 << 6;
    /// Kitty graphics placements exist; fetch them with kittyPlacements.
    pub const images: u32 = 1 << 7;
};

/// Cell attribute bits (cell word 3).
pub const Attr = struct {
    pub const bold: u32 = 1 << 0;
    pub const italic: u32 = 1 << 1;
    pub const faint: u32 = 1 << 2;
    pub const strikethrough: u32 = 1 << 3;
    pub const overline: u32 = 1 << 4;
    pub const invisible: u32 = 1 << 5;
    pub const blink: u32 = 1 << 6;
    pub const selected: u32 = 1 << 7;
    pub const underline_shift = 8; // 3 bits: sgr underline kind
    pub const wide_shift = 12; // 2 bits: 0 narrow, 1 wide, 2 spacer tail, 3 spacer head
    pub const hyperlink: u32 = 1 << 14;
    pub const extra_len_shift = 16; // 8 bits: grapheme codepoints in `extra`
    pub const extra_index_shift = 0; // when extra_len > 0, word 0 high bit set
};

/// Set on the codepoint word when the cell has grapheme continuation
/// codepoints; the low 31 bits then hold the base codepoint and the
/// continuation starts at `extra[extra_cursor]` in reading order.
pub const grapheme_flag: u32 = 1 << 31;

pub fn capacityBytes(cols: usize, rows: usize) usize {
    // Leave room for one continuation codepoint per cell on average.
    return 4 * (header_words + cols * rows * (cell_words + 1));
}

fn argb(c: vt.color.RGB) u32 {
    return 0xFF000000 | (@as(u32, c.r) << 16) | (@as(u32, c.g) << 8) | @as(u32, c.b);
}

pub fn write(out: []u8, state: *const vt.RenderState, t: *vt.Terminal, flags: u32) !usize {
    const cols: usize = state.cols;
    const rows: usize = state.rows;
    const cell_end = header_words + cols * rows * cell_words;
    if (out.len < cell_end * 4) return error.BufferTooSmall;

    const words: []align(1) u32 = std.mem.bytesAsSlice(u32, out[0 .. out.len - out.len % 4]);
    @memset(words[0..header_words], 0);

    const palette = &state.colors.palette;
    const default_fg = state.colors.foreground;
    const default_bg = state.colors.background;

    var extra: usize = 0;
    const extra_cap = words.len - cell_end;

    const range = state.rowDataRange();
    const row_cells = state.row_data.items(.cells);
    const row_sel = state.row_data.items(.selection);
    var y: usize = 0;
    while (y < rows) : (y += 1) {
        const ri = range.start + y;
        const base = header_words + y * cols * cell_words;
        if (ri >= range.end) {
            @memset(words[base .. base + cols * cell_words], 0);
            continue;
        }
        const cells = row_cells[ri].slice();
        const raws = cells.items(.raw);
        const styles = cells.items(.style);
        const graphemes = cells.items(.grapheme);
        const sel = row_sel[ri];

        var x: usize = 0;
        while (x < cols) : (x += 1) {
            const o = base + x * cell_words;
            if (x >= raws.len) {
                words[o] = 0;
                words[o + 1] = argb(default_fg);
                words[o + 2] = argb(default_bg);
                words[o + 3] = 0;
                continue;
            }
            const raw = raws[x];
            const style: vt.Style = if (raw.style_id != 0) styles[x] else .{};

            var fg = style.fg(.{ .default = default_fg, .palette = palette });
            var bg = style.bg(&raw, palette) orelse default_bg;
            if (style.flags.inverse) std.mem.swap(vt.color.RGB, &fg, &bg);

            var attrs: u32 = 0;
            if (style.flags.bold) attrs |= Attr.bold;
            if (style.flags.italic) attrs |= Attr.italic;
            if (style.flags.faint) attrs |= Attr.faint;
            if (style.flags.strikethrough) attrs |= Attr.strikethrough;
            if (style.flags.overline) attrs |= Attr.overline;
            if (style.flags.invisible) attrs |= Attr.invisible;
            if (style.flags.blink) attrs |= Attr.blink;
            if (raw.hyperlink) attrs |= Attr.hyperlink;
            attrs |= (@as(u32, @intFromEnum(style.flags.underline)) & 0x7) << Attr.underline_shift;
            attrs |= (@as(u32, @intFromEnum(raw.wide)) & 0x3) << Attr.wide_shift;
            if (sel) |s| {
                if (x >= s[0] and x <= s[1]) attrs |= Attr.selected;
            }

            var cp: u32 = switch (raw.content_tag) {
                .codepoint, .codepoint_grapheme => raw.content.codepoint.data,
                .bg_color_palette, .bg_color_rgb => 0,
            };
            if (raw.content_tag == .codepoint_grapheme) {
                const g = graphemes[x];
                const n = @min(g.len, 255, extra_cap - extra);
                if (n > 0) {
                    for (g[0..n], 0..) |c, i| words[cell_end + extra + i] = c;
                    extra += n;
                    cp |= grapheme_flag;
                    attrs |= @as(u32, @intCast(n)) << Attr.extra_len_shift;
                }
            }

            words[o] = cp;
            words[o + 1] = argb(fg);
            words[o + 2] = argb(bg);
            words[o + 3] = attrs;
        }
    }

    const sb = t.screens.active.pages.scrollbar();
    words[H.version] = version;
    words[H.cols] = @intCast(cols);
    words[H.rows] = @intCast(rows);
    if (state.cursor.viewport) |vp| {
        words[H.cursor_x] = vp.x;
        words[H.cursor_y] = vp.y;
    } else {
        words[H.cursor_x] = std.math.maxInt(u32);
        words[H.cursor_y] = std.math.maxInt(u32);
    }
    var cursor: u32 = 0;
    if (state.cursor.visible) cursor |= 1;
    if (state.cursor.blinking) cursor |= 2;
    if (state.cursor.viewport) |vp| {
        if (vp.wide_tail) cursor |= 4;
    }
    cursor |= @as(u32, @intFromEnum(state.cursor.visual_style)) << 8;
    words[H.cursor] = cursor;
    words[H.background] = argb(default_bg);
    words[H.foreground] = argb(default_fg);
    words[H.cursor_color] = if (state.colors.cursor) |c| argb(c) else 0;
    words[H.scroll_total] = @intCast(@min(sb.total, std.math.maxInt(u32)));
    words[H.scroll_offset] = @intCast(@min(sb.offset, std.math.maxInt(u32)));
    words[H.scroll_len] = @intCast(@min(sb.len, std.math.maxInt(u32)));
    words[H.flags] = flags;
    words[H.extra_count] = @intCast(extra);

    return (cell_end + extra) * 4;
}
