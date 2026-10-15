//! libpuppy_remap.so: an LD_PRELOAD library that lets unmodified Termux
//! packages run inside Puppy.
//!
//! Termux binaries have `/data/data/com.termux` compiled in. Android only
//! lets this app use `/data/data/<its package>`, so every libc call that
//! takes a path is wrapped and paths under PUPPY_REMAP_FROM are rewritten
//! to PUPPY_REMAP_TO. Two things bypass libc and are handled explicitly:
//! the kernel reading a script's `#!` line (execve), and the kernel
//! following symlinks (symlink targets are rewritten when created).
//!
//! The dynamic linker's own library search (RUNPATH) is not covered; the
//! shell environment sets LD_LIBRARY_PATH for that.
const std = @import("std");
const rp = @import("remap_path.zig");

const PATH_MAX = 4096;

// ------------------------------------------------------------- libc glue

extern "c" fn dlsym(handle: ?*anyopaque, name: [*:0]const u8) ?*anyopaque;
extern "c" fn getenv(name: [*:0]const u8) ?[*:0]const u8;
extern "c" fn __errno() *c_int;
extern "c" var environ: [*:null]?[*:0]const u8;
const DlInfo = extern struct {
    fname: ?[*:0]const u8,
    fbase: ?*anyopaque,
    sname: ?[*:0]const u8,
    saddr: ?*anyopaque,
};
extern "c" fn dladdr(addr: *const anyopaque, info: *DlInfo) c_int;

const RTLD_NEXT: ?*anyopaque = @ptrFromInt(std.math.maxInt(usize)); // (void*)-1 on LP64
const ENOENT = 2;
const ENOSYS = 38;
const E2BIG = 7;

fn Real(comptime name: [:0]const u8, comptime T: type) type {
    return struct {
        var ptr: ?T = null;
        fn get() ?T {
            if (ptr) |p| return p;
            const sym = dlsym(RTLD_NEXT, name.ptr) orelse return null;
            ptr = @ptrCast(@alignCast(sym));
            return ptr;
        }
    };
}

// ----------------------------------------------------------- the mapping

var from_buf: [256]u8 = undefined;
var to_buf: [256]u8 = undefined;
var from: []const u8 = "";
var to: []const u8 = "";
var ready = std.atomic.Value(bool).init(false);
// What children need to stay remapped (see withRemapEnv).
var self_buf: [PATH_MAX]u8 = undefined;
var self: []const u8 = "";
var lib_path_buf: [PATH_MAX]u8 = undefined;
var lib_path: []const u8 = "";

fn init() void {
    if (ready.load(.acquire)) return;
    // libc opens files during its own startup, before the environment is
    // set up: don't cache "no mapping" until the variables have been seen.
    if (getenv("PUPPY_REMAP_FROM") == null) return;
    from = copyEnv("PUPPY_REMAP_FROM", &from_buf);
    to = copyEnv("PUPPY_REMAP_TO", &to_buf);
    lib_path = copyEnv("LD_LIBRARY_PATH", &lib_path_buf);
    var info: DlInfo = undefined;
    if (dladdr(@ptrCast(&init), &info) != 0) {
        if (info.fname) |f| {
            const s = std.mem.span(f);
            if (s.len <= self_buf.len) {
                @memcpy(self_buf[0..s.len], s);
                self = self_buf[0..s.len];
            }
        }
    }
    debug = getenv("PUPPY_REMAP_DEBUG") != null;
    ready.store(true, .release);
}

fn copyEnv(name: [*:0]const u8, buf: []u8) []const u8 {
    const v = getenv(name) orelse return "";
    const s = std.mem.span(v);
    if (s.len > buf.len) return "";
    @memcpy(buf[0..s.len], s);
    return buf[0..s.len];
}

/// Per-call scratch for mapped paths; each wrapper needs at most two.
const Buf = [PATH_MAX]u8;

fn m(path: ?[*:0]const u8, buf: *Buf) ?[*:0]const u8 {
    return mAt(AT_FDCWD, path, buf);
}

/// Maps `path` as seen from directory `dirfd`. Relative paths can cross
/// into the mapped prefix too (`mkdir -p` creates `com.termux` relative to
/// an fd for `/data/data`), so those are resolved against their base
/// directory when they could, and passed through untouched otherwise.
fn mAt(dirfd: c_int, path: ?[*:0]const u8, buf: *Buf) ?[*:0]const u8 {
    const p = path orelse return null;
    init();
    const s = std.mem.span(p);
    var r: [*:0]const u8 = p;
    if (s.len > 0 and s[0] == '/') {
        r = rp.map(s, from, to, buf).ptr;
    } else if (s.len > 0 and from.len > 0 and rp.mayCross(s, from)) {
        var base_buf: Buf = undefined;
        var joined_buf: Buf = undefined;
        if (baseDir(dirfd, &base_buf)) |base| {
            if (rp.join(base, s, &joined_buf)) |joined| {
                const mapped = rp.map(joined, from, to, buf);
                if (mapped.ptr != joined.ptr) r = mapped.ptr;
            }
        }
    }
    if (debug) debugLog(p, r);
    return r;
}

/// Maps absolute paths only: for symlink targets, where a relative target is
/// relative to the link itself and must be stored as written.
fn mAbs(path: ?[*:0]const u8, buf: *Buf) ?[*:0]const u8 {
    const p = path orelse return null;
    init();
    return rp.map(std.mem.span(p), from, to, buf).ptr;
}

const AT_FDCWD: c_int = -100;

var cwd_buf: Buf = undefined;
var cwd_len: usize = 0;
var cwd_valid = std.atomic.Value(bool).init(false);

/// The absolute path a relative path is resolved against.
fn baseDir(dirfd: c_int, out: *Buf) ?[]const u8 {
    if (dirfd == AT_FDCWD) {
        if (!cwd_valid.load(.acquire)) {
            const g = Real("getcwd", *const fn ([*]u8, usize) callconv(.c) ?[*:0]u8).get() orelse return null;
            const res = g(&cwd_buf, cwd_buf.len) orelse return null;
            cwd_len = std.mem.len(res);
            cwd_valid.store(true, .release);
        }
        @memcpy(out[0..cwd_len], cwd_buf[0..cwd_len]);
        return out[0..cwd_len];
    }
    var fd_path: [32]u8 = undefined;
    const lp = std.fmt.bufPrintZ(&fd_path, "/proc/self/fd/{d}", .{dirfd}) catch return null;
    const rl = Real("readlink", *const fn ([*:0]const u8, [*]u8, usize) callconv(.c) isize).get() orelse return null;
    const n = rl(lp.ptr, out, out.len);
    if (n <= 0 or out[0] != '/') return null;
    return out[0..@intCast(n)];
}

var debug = false;

/// PUPPY_REMAP_DEBUG=1 reports each mapped path on stderr.
fn debugLog(p: [*:0]const u8, r: [*:0]const u8) void {
    var line: [PATH_MAX * 2 + 32]u8 = undefined;
    const msg = std.fmt.bufPrint(&line, "remap: {s} -> {s}\n", .{ std.mem.span(p), std.mem.span(r) }) catch return;
    _ = std.c.write(2, msg.ptr, msg.len);
}

fn fail(errno: c_int) c_int {
    __errno().* = errno;
    return -1;
}

// ------------------------------------------------------------------ open

const OpenFn = *const fn (?[*:0]const u8, c_int, c_uint) callconv(.c) c_int;
const OpenatFn = *const fn (c_int, ?[*:0]const u8, c_int, c_uint) callconv(.c) c_int;

// The mode argument is variadic in C; on AArch64 Linux variadic and fixed
// integer arguments travel in the same registers, so a fixed parameter is
// ABI compatible.
export fn open(path: ?[*:0]const u8, flags: c_int, mode: c_uint) c_int {
    var b: Buf = undefined;
    const f = Real("open", OpenFn).get() orelse return fail(ENOSYS);
    return f(m(path, &b), flags, mode);
}
export fn open64(path: ?[*:0]const u8, flags: c_int, mode: c_uint) c_int {
    return open(path, flags, mode);
}
export fn __open_2(path: ?[*:0]const u8, flags: c_int) c_int {
    var b: Buf = undefined;
    const f = Real("__open_2", *const fn (?[*:0]const u8, c_int) callconv(.c) c_int).get() orelse return fail(ENOSYS);
    return f(m(path, &b), flags);
}
export fn openat(dirfd: c_int, path: ?[*:0]const u8, flags: c_int, mode: c_uint) c_int {
    var b: Buf = undefined;
    const f = Real("openat", OpenatFn).get() orelse return fail(ENOSYS);
    return f(dirfd, mAt(dirfd, path, &b), flags, mode);
}
export fn openat64(dirfd: c_int, path: ?[*:0]const u8, flags: c_int, mode: c_uint) c_int {
    return openat(dirfd, path, flags, mode);
}
export fn __openat_2(dirfd: c_int, path: ?[*:0]const u8, flags: c_int) c_int {
    var b: Buf = undefined;
    const f = Real("__openat_2", *const fn (c_int, ?[*:0]const u8, c_int) callconv(.c) c_int).get() orelse return fail(ENOSYS);
    return f(dirfd, mAt(dirfd, path, &b), flags);
}
export fn creat(path: ?[*:0]const u8, mode: c_uint) c_int {
    var b: Buf = undefined;
    const f = Real("creat", *const fn (?[*:0]const u8, c_uint) callconv(.c) c_int).get() orelse return fail(ENOSYS);
    return f(m(path, &b), mode);
}

const FopenFn = *const fn (?[*:0]const u8, ?[*:0]const u8) callconv(.c) ?*anyopaque;
export fn fopen(path: ?[*:0]const u8, mode: ?[*:0]const u8) ?*anyopaque {
    var b: Buf = undefined;
    const f = Real("fopen", FopenFn).get() orelse return null;
    return f(m(path, &b), mode);
}
export fn fopen64(path: ?[*:0]const u8, mode: ?[*:0]const u8) ?*anyopaque {
    return fopen(path, mode);
}
export fn freopen(path: ?[*:0]const u8, mode: ?[*:0]const u8, stream: ?*anyopaque) ?*anyopaque {
    var b: Buf = undefined;
    const f = Real("freopen", *const fn (?[*:0]const u8, ?[*:0]const u8, ?*anyopaque) callconv(.c) ?*anyopaque).get() orelse return null;
    return f(m(path, &b), mode, stream);
}

// ------------------------------------------- one-path, pass-through calls

/// Calls the real `name` (C signature `F`) with argument `idx` mapped.
fn wrap(comptime name: [:0]const u8, comptime F: type, comptime idx: usize, args: std.meta.ArgsTuple(F)) Ret(F) {
    var b: Buf = undefined;
    var a = args;
    // fooat(dirfd, path, ...): resolve relative paths against dirfd.
    const at = comptime std.mem.endsWith(u8, name, "at") and idx == 1;
    a[idx] = if (at) mAt(a[0], a[idx], &b) else m(a[idx], &b);
    const f = Real(name, *const F).get() orelse return unavailable(Ret(F));
    return @call(.auto, f, a);
}

fn Ret(comptime F: type) type {
    return @typeInfo(F).@"fn".return_type.?;
}

fn unavailable(comptime R: type) R {
    __errno().* = ENOSYS;
    return switch (@typeInfo(R)) {
        .int => -1,
        .optional => null,
        else => @compileError("unsupported return type"),
    };
}

const P = ?[*:0]const u8;
const Opaque = ?*anyopaque;

export fn stat(p: P, st: Opaque) c_int {
    return wrap("stat", fn (P, Opaque) callconv(.c) c_int, 0, .{ p, st });
}
export fn stat64(p: P, st: Opaque) c_int {
    return stat(p, st);
}
export fn lstat(p: P, st: Opaque) c_int {
    return wrap("lstat", fn (P, Opaque) callconv(.c) c_int, 0, .{ p, st });
}
export fn lstat64(p: P, st: Opaque) c_int {
    return lstat(p, st);
}
export fn fstatat(fd: c_int, p: P, st: Opaque, flags: c_int) c_int {
    return wrap("fstatat", fn (c_int, P, Opaque, c_int) callconv(.c) c_int, 1, .{ fd, p, st, flags });
}
export fn fstatat64(fd: c_int, p: P, st: Opaque, flags: c_int) c_int {
    return fstatat(fd, p, st, flags);
}
export fn access(p: P, mode: c_int) c_int {
    return wrap("access", fn (P, c_int) callconv(.c) c_int, 0, .{ p, mode });
}
export fn faccessat(fd: c_int, p: P, mode: c_int, flags: c_int) c_int {
    return wrap("faccessat", fn (c_int, P, c_int, c_int) callconv(.c) c_int, 1, .{ fd, p, mode, flags });
}
export fn opendir(p: P) Opaque {
    return wrap("opendir", fn (P) callconv(.c) Opaque, 0, .{p});
}
export fn scandir(p: P, list: Opaque, filter: Opaque, compar: Opaque) c_int {
    return wrap("scandir", fn (P, Opaque, Opaque, Opaque) callconv(.c) c_int, 0, .{ p, list, filter, compar });
}
export fn mkdir(p: P, mode: c_uint) c_int {
    return wrap("mkdir", fn (P, c_uint) callconv(.c) c_int, 0, .{ p, mode });
}
export fn mkdirat(fd: c_int, p: P, mode: c_uint) c_int {
    return wrap("mkdirat", fn (c_int, P, c_uint) callconv(.c) c_int, 1, .{ fd, p, mode });
}
export fn rmdir(p: P) c_int {
    return wrap("rmdir", fn (P) callconv(.c) c_int, 0, .{p});
}
export fn unlink(p: P) c_int {
    return wrap("unlink", fn (P) callconv(.c) c_int, 0, .{p});
}
export fn unlinkat(fd: c_int, p: P, flags: c_int) c_int {
    return wrap("unlinkat", fn (c_int, P, c_int) callconv(.c) c_int, 1, .{ fd, p, flags });
}
export fn remove(p: P) c_int {
    return wrap("remove", fn (P) callconv(.c) c_int, 0, .{p});
}
export fn chdir(p: P) c_int {
    const r = wrap("chdir", fn (P) callconv(.c) c_int, 0, .{p});
    cwd_valid.store(false, .release);
    return r;
}
export fn fchdir(fd: c_int) c_int {
    const f = Real("fchdir", *const fn (c_int) callconv(.c) c_int).get() orelse return fail(ENOSYS);
    const r = f(fd);
    cwd_valid.store(false, .release);
    return r;
}
export fn chmod(p: P, mode: c_uint) c_int {
    return wrap("chmod", fn (P, c_uint) callconv(.c) c_int, 0, .{ p, mode });
}
export fn fchmodat(fd: c_int, p: P, mode: c_uint, flags: c_int) c_int {
    return wrap("fchmodat", fn (c_int, P, c_uint, c_int) callconv(.c) c_int, 1, .{ fd, p, mode, flags });
}
export fn chown(p: P, uid: c_uint, gid: c_uint) c_int {
    return wrap("chown", fn (P, c_uint, c_uint) callconv(.c) c_int, 0, .{ p, uid, gid });
}
export fn lchown(p: P, uid: c_uint, gid: c_uint) c_int {
    return wrap("lchown", fn (P, c_uint, c_uint) callconv(.c) c_int, 0, .{ p, uid, gid });
}
export fn fchownat(fd: c_int, p: P, uid: c_uint, gid: c_uint, flags: c_int) c_int {
    return wrap("fchownat", fn (c_int, P, c_uint, c_uint, c_int) callconv(.c) c_int, 1, .{ fd, p, uid, gid, flags });
}
export fn truncate(p: P, len: i64) c_int {
    return wrap("truncate", fn (P, i64) callconv(.c) c_int, 0, .{ p, len });
}
export fn truncate64(p: P, len: i64) c_int {
    return truncate(p, len);
}
export fn utimes(p: P, tv: Opaque) c_int {
    return wrap("utimes", fn (P, Opaque) callconv(.c) c_int, 0, .{ p, tv });
}
export fn utimensat(fd: c_int, p: P, ts: Opaque, flags: c_int) c_int {
    return wrap("utimensat", fn (c_int, P, Opaque, c_int) callconv(.c) c_int, 1, .{ fd, p, ts, flags });
}
export fn readlink(p: P, buf: Opaque, size: usize) isize {
    return wrap("readlink", fn (P, Opaque, usize) callconv(.c) isize, 0, .{ p, buf, size });
}
export fn readlinkat(fd: c_int, p: P, buf: Opaque, size: usize) isize {
    return wrap("readlinkat", fn (c_int, P, Opaque, usize) callconv(.c) isize, 1, .{ fd, p, buf, size });
}
export fn realpath(p: P, resolved: Opaque) Opaque {
    return wrap("realpath", fn (P, Opaque) callconv(.c) Opaque, 0, .{ p, resolved });
}
export fn statvfs(p: P, buf: Opaque) c_int {
    return wrap("statvfs", fn (P, Opaque) callconv(.c) c_int, 0, .{ p, buf });
}
export fn statvfs64(p: P, buf: Opaque) c_int {
    return statvfs(p, buf);
}
export fn statfs(p: P, buf: Opaque) c_int {
    return wrap("statfs", fn (P, Opaque) callconv(.c) c_int, 0, .{ p, buf });
}
export fn statfs64(p: P, buf: Opaque) c_int {
    return statfs(p, buf);
}
export fn mkfifo(p: P, mode: c_uint) c_int {
    return wrap("mkfifo", fn (P, c_uint) callconv(.c) c_int, 0, .{ p, mode });
}
export fn mknod(p: P, mode: c_uint, dev: u64) c_int {
    return wrap("mknod", fn (P, c_uint, u64) callconv(.c) c_int, 0, .{ p, mode, dev });
}
export fn dlopen(p: P, flags: c_int) Opaque {
    return wrap("dlopen", fn (P, c_int) callconv(.c) Opaque, 0, .{ p, flags });
}
export fn android_dlopen_ext(p: P, flags: c_int, info: Opaque) Opaque {
    return wrap("android_dlopen_ext", fn (P, c_int, Opaque) callconv(.c) Opaque, 0, .{ p, flags, info });
}

// ------------------------------------------------------ two-path calls

const PathMode = enum { cwd, abs_only, at0, at1, at2 };

/// Calls the real two-path function `name`, mapping argument `i` and `j`
/// as described by their modes (`at0`: relative to the fd in argument 0).
fn wrap2(comptime name: [:0]const u8, comptime F: type, comptime i: usize, comptime mi: PathMode, comptime j: usize, comptime mj: PathMode, args: std.meta.ArgsTuple(F)) c_int {
    var b1: Buf = undefined;
    var b2: Buf = undefined;
    var a = args;
    a[i] = mapMode(mi, a, a[i], &b1);
    a[j] = mapMode(mj, a, a[j], &b2);
    const f = Real(name, *const F).get() orelse return fail(ENOSYS);
    return @call(.auto, f, a);
}

fn mapMode(comptime mode: PathMode, args: anytype, path: P, buf: *Buf) P {
    return switch (mode) {
        .cwd => m(path, buf),
        .abs_only => mAbs(path, buf),
        .at0 => mAt(args[0], path, buf),
        .at1 => mAt(args[1], path, buf),
        .at2 => mAt(args[2], path, buf),
    };
}

export fn rename(a: P, b: P) c_int {
    return wrap2("rename", fn (P, P) callconv(.c) c_int, 0, .cwd, 1, .cwd, .{ a, b });
}
export fn renameat(fa: c_int, a: P, fb: c_int, b: P) c_int {
    return wrap2("renameat", fn (c_int, P, c_int, P) callconv(.c) c_int, 1, .at0, 3, .at2, .{ fa, a, fb, b });
}
export fn link(a: P, b: P) c_int {
    return wrap2("link", fn (P, P) callconv(.c) c_int, 0, .cwd, 1, .cwd, .{ a, b });
}
export fn linkat(fa: c_int, a: P, fb: c_int, b: P, flags: c_int) c_int {
    return wrap2("linkat", fn (c_int, P, c_int, P, c_int) callconv(.c) c_int, 1, .at0, 3, .at2, .{ fa, a, fb, b, flags });
}
/// The target is rewritten too (absolute ones only): the kernel, not libc,
/// follows symlinks, and relative targets are relative to the link.
export fn symlink(target: P, linkpath: P) c_int {
    return wrap2("symlink", fn (P, P) callconv(.c) c_int, 0, .abs_only, 1, .cwd, .{ target, linkpath });
}
export fn symlinkat(target: P, fd: c_int, linkpath: P) c_int {
    return wrap2("symlinkat", fn (P, c_int, P) callconv(.c) c_int, 0, .abs_only, 2, .at1, .{ target, fd, linkpath });
}

// ------------------------------------------------------------- mkstemp

/// mkstemp-style calls fill in the template; copy the result back.
fn template(comptime name: [:0]const u8, comptime F: type, args: std.meta.ArgsTuple(F)) Ret(F) {
    const f = Real(name, *const F).get() orelse return unavailable(Ret(F));
    const tmpl: [*:0]u8 = args[0] orelse return @call(.auto, f, args);
    init();
    const orig = std.mem.span(tmpl);
    var b: Buf = undefined;
    const mapped = rp.map(orig, from, to, &b);
    if (mapped.ptr == orig.ptr) return @call(.auto, f, args);
    var a = args;
    a[0] = @constCast(mapped.ptr);
    const r = @call(.auto, f, a);
    // orig = from ++ rest and mapped = to ++ rest', where only the XXXXXX in
    // rest was filled in: copy rest' back over rest.
    @memcpy(orig[from.len..], mapped[to.len..]);
    // mkdtemp returns its argument; hand back the caller's buffer, not ours.
    if (Ret(F) == MP) return if (r == null) null else tmpl;
    return r;
}

const MP = ?[*:0]u8;
export fn mkstemp(t: MP) c_int {
    return template("mkstemp", fn (MP) callconv(.c) c_int, .{t});
}
export fn mkstemp64(t: MP) c_int {
    return mkstemp(t);
}
export fn mkostemp(t: MP, flags: c_int) c_int {
    return template("mkostemp", fn (MP, c_int) callconv(.c) c_int, .{ t, flags });
}
export fn mkstemps(t: MP, suffix: c_int) c_int {
    return template("mkstemps", fn (MP, c_int) callconv(.c) c_int, .{ t, suffix });
}
export fn mkdtemp(t: MP) MP {
    return template("mkdtemp", fn (MP) callconv(.c) MP, .{t});
}

// --------------------------------------------------------------- sockets

const sockaddr_un = extern struct { family: u16, path: [108]u8 };
const AF_UNIX = 1;
const SockFn = *const fn (c_int, ?*const anyopaque, u32) callconv(.c) c_int;

fn sockCall(comptime name: [:0]const u8, fd: c_int, addr: ?*const anyopaque, len: u32) c_int {
    const f = Real(name, SockFn).get() orelse return fail(ENOSYS);
    const a = addr orelse return f(fd, addr, len);
    const un: *const sockaddr_un = @ptrCast(@alignCast(a));
    if (un.family != AF_UNIX or len <= 2 or un.path[0] == 0) return f(fd, addr, len);
    const end = std.mem.indexOfScalar(u8, &un.path, 0) orelse return f(fd, addr, len);
    const path: [:0]const u8 = un.path[0..end :0];
    var b: Buf = undefined;
    init();
    const mapped = rp.map(path, from, to, &b);
    if (mapped.ptr == path.ptr or mapped.len >= un.path.len) return f(fd, addr, len);
    var copy: sockaddr_un = .{ .family = AF_UNIX, .path = @splat(0) };
    @memcpy(copy.path[0..mapped.len], mapped);
    return f(fd, &copy, @intCast(2 + mapped.len + 1));
}
export fn connect(fd: c_int, addr: ?*const anyopaque, len: u32) c_int {
    return sockCall("connect", fd, addr, len);
}
export fn bind(fd: c_int, addr: ?*const anyopaque, len: u32) c_int {
    return sockCall("bind", fd, addr, len);
}

// ------------------------------------------------------------------ exec

const Argv = [*:null]const ?[*:0]const u8;
const ExecveFn = *const fn (?[*:0]const u8, ?Argv, ?Argv) callconv(.c) c_int;
const MAX_ARGS = 4096;

/// execve with path mapping and `#!` handling. Scripts whose interpreter
/// lives under the mapped prefix (or is `/usr/bin/x`, `/bin/x`, which
/// Android lacks) are run by exec'ing the interpreter directly, as the
/// kernel would have.
export fn execve(path: ?[*:0]const u8, argv: ?Argv, envp_in: ?Argv) c_int {
    const real = Real("execve", ExecveFn).get() orelse return fail(ENOSYS);
    var env_store: EnvStore = undefined;
    const envp = withRemapEnv(envp_in, &env_store);
    var b: Buf = undefined;
    const target = m(path, &b) orelse return real(path, argv, envp);

    var head: [512]u8 = undefined;
    const n = readHead(target, &head);
    const sb = rp.parseShebang(head[0..n]) orelse return real(target, argv, envp);

    var ib: Buf = undefined;
    const interp = shebangInterpreter(sb.interpreter, &ib) orelse return real(target, argv, envp);

    // argv: interpreter [arg] script argv[1..]
    var args: [MAX_ARGS:null]?[*:0]const u8 = undefined;
    var ab: [512]u8 = undefined;
    var i: usize = 0;
    args[i] = interp;
    i += 1;
    if (sb.arg) |arg| {
        if (arg.len >= ab.len) return fail(E2BIG);
        @memcpy(ab[0..arg.len], arg);
        ab[arg.len] = 0;
        args[i] = ab[0..arg.len :0];
        i += 1;
    }
    args[i] = target;
    i += 1;
    if (argv) |av| {
        var j: usize = 1;
        if (av[0] != null) while (av[j]) |a| : (j += 1) {
            if (i >= MAX_ARGS) return fail(E2BIG);
            args[i] = a;
            i += 1;
        };
    }
    args[i] = null;
    return real(interp, @ptrCast(&args), envp);
}

const MAX_ENV = 4096;
const EnvStore = struct {
    ptrs: [MAX_ENV:null]?[*:0]const u8,
    preload: [2 * PATH_MAX]u8,
    from: [300]u8,
    to: [300]u8,
    lib_path: [PATH_MAX + 32]u8,
};

/// Programs that run others with a cleaned environment (termux-exec's own
/// scripts `unset LD_PRELOAD`, `env -i`, sudo-like tools) would drop the
/// remap for their children; put the remap variables back.
fn withRemapEnv(envp: ?Argv, st: *EnvStore) ?Argv {
    init();
    if (!ready.load(.acquire) or self.len == 0) return envp;
    const ev = envp orelse return envp;
    var have_from = false;
    var have_to = false;
    var have_lib = false;
    var preload_i: ?usize = null;
    var n: usize = 0;
    while (ev[n]) |e| : (n += 1) {
        if (n + 5 >= MAX_ENV) return envp;
        const s = std.mem.span(e);
        if (std.mem.startsWith(u8, s, "PUPPY_REMAP_FROM=")) have_from = true;
        if (std.mem.startsWith(u8, s, "PUPPY_REMAP_TO=")) have_to = true;
        if (std.mem.startsWith(u8, s, "LD_LIBRARY_PATH=")) have_lib = true;
        if (std.mem.startsWith(u8, s, "LD_PRELOAD=")) preload_i = n;
        st.ptrs[n] = e;
    }
    if (preload_i) |i| {
        const old = std.mem.span(ev[i].?)["LD_PRELOAD=".len..];
        var it = std.mem.tokenizeAny(u8, old, ": ");
        const present = while (it.next()) |x| {
            if (std.mem.eql(u8, x, self)) break true;
        } else false;
        if (!present) {
            st.ptrs[i] = (std.fmt.bufPrintZ(&st.preload, "LD_PRELOAD={s}:{s}", .{ self, old }) catch return envp).ptr;
        }
    } else {
        st.ptrs[n] = (std.fmt.bufPrintZ(&st.preload, "LD_PRELOAD={s}", .{self}) catch return envp).ptr;
        n += 1;
    }
    if (!have_from) {
        st.ptrs[n] = (std.fmt.bufPrintZ(&st.from, "PUPPY_REMAP_FROM={s}", .{from}) catch return envp).ptr;
        n += 1;
    }
    if (!have_to) {
        st.ptrs[n] = (std.fmt.bufPrintZ(&st.to, "PUPPY_REMAP_TO={s}", .{to}) catch return envp).ptr;
        n += 1;
    }
    if (!have_lib and lib_path.len > 0) {
        st.ptrs[n] = (std.fmt.bufPrintZ(&st.lib_path, "LD_LIBRARY_PATH={s}", .{lib_path}) catch return envp).ptr;
        n += 1;
    }
    st.ptrs[n] = null;
    return @ptrCast(&st.ptrs);
}

/// The interpreter to exec for a `#!` line, or null to let the kernel do it.
fn shebangInterpreter(interp: []const u8, buf: *Buf) ?[*:0]const u8 {
    if (interp.len + 1 > buf.len) return null;
    var z: Buf = undefined;
    @memcpy(z[0..interp.len], interp);
    z[interp.len] = 0;
    init();
    const mapped = rp.map(z[0..interp.len :0], from, to, buf);
    if (mapped.ptr != @as([*]const u8, &z)) return mapped.ptr;
    // /usr/bin/env, /bin/sh... don't exist on Android: use $PREFIX/bin.
    for ([_][]const u8{ "/usr/bin/", "/bin/" }) |dir| {
        if (std.mem.startsWith(u8, interp, dir) and !exists(z[0..interp.len :0])) {
            const prefix = std.mem.span(getenv("PREFIX") orelse return null);
            const name = interp[dir.len..];
            if (prefix.len + 5 + name.len + 1 > buf.len) return null;
            const out = std.fmt.bufPrintZ(buf, "{s}/bin/{s}", .{ prefix, name }) catch return null;
            return out.ptr;
        }
    }
    return null;
}

fn exists(path: [*:0]const u8) bool {
    const f = Real("access", *const fn (?[*:0]const u8, c_int) callconv(.c) c_int).get() orelse return true;
    return f(path, 0) == 0;
}

fn readHead(path: [*:0]const u8, out: []u8) usize {
    const o = Real("open", OpenFn).get() orelse return 0;
    const fd = o(path, 0o2000000, 0); // O_RDONLY | O_CLOEXEC
    if (fd < 0) return 0;
    defer _ = std.c.close(fd);
    const r = std.c.read(fd, out.ptr, out.len);
    return if (r > 0) @intCast(r) else 0;
}

export fn execv(path: ?[*:0]const u8, argv: ?Argv) c_int {
    return execve(path, argv, @ptrCast(environ));
}

export fn execvpe(file: ?[*:0]const u8, argv: ?Argv, envp: ?Argv) c_int {
    const f = file orelse return fail(ENOENT);
    const name = std.mem.span(f);
    if (name.len == 0) return fail(ENOENT);
    if (std.mem.indexOfScalar(u8, name, '/') != null) return execve(f, argv, envp);
    const path_env = std.mem.span(getenv("PATH") orelse "/system/bin");
    var it = std.mem.splitScalar(u8, path_env, ':');
    var last_err: c_int = ENOENT;
    var b: Buf = undefined;
    while (it.next()) |dir| {
        const d = if (dir.len == 0) "." else dir;
        const full = std.fmt.bufPrintZ(&b, "{s}/{s}", .{ d, name }) catch continue;
        _ = execve(full.ptr, argv, envp);
        const e = __errno().*;
        if (e != ENOENT and e != 20) last_err = e; // keep EACCES etc. over ENOENT/ENOTDIR
    }
    return fail(last_err);
}

export fn execvp(file: ?[*:0]const u8, argv: ?Argv) c_int {
    return execvpe(file, argv, @ptrCast(environ));
}

// execl, execlp and execle are variadic, which Zig 0.16 cannot define on
// AArch64; remap_exec.c implements them on top of execve/execvpe above.

// ------------------------------------------------------- FORTIFY readlink

export fn __readlink_chk(p: P, buf: Opaque, size: usize, buf_size: usize) isize {
    return wrap("__readlink_chk", fn (P, Opaque, usize, usize) callconv(.c) isize, 0, .{ p, buf, size, buf_size });
}
export fn __readlinkat_chk(fd: c_int, p: P, buf: Opaque, size: usize, buf_size: usize) isize {
    var b: Buf = undefined;
    const f = Real("__readlinkat_chk", *const fn (c_int, P, Opaque, usize, usize) callconv(.c) isize).get() orelse return fail(ENOSYS);
    return f(fd, mAt(fd, p, &b), buf, size, buf_size);
}

// ------------------------------------------------------------- syscall()

/// gnulib (coreutils, findutils...) reaches statx, renameat2 and friends
/// through syscall() when bionic has no wrapper, bypassing everything above.
/// The arguments are variadic in C; on AArch64 they arrive in x1..x6 like
/// fixed ones, so they are taken as fixed and forwarded unchanged.
const SyscallFn = *const fn (c_long, usize, usize, usize, usize, usize, usize) callconv(.c) c_long;

// AArch64 (asm-generic) syscall numbers that take paths.
const SYS = struct {
    const getxattr = 8;
    const mkdirat = 34;
    const unlinkat = 35;
    const symlinkat = 36;
    const linkat = 37;
    const renameat = 38;
    const faccessat = 48;
    const chdir = 49;
    const fchmodat = 53;
    const fchownat = 54;
    const openat = 56;
    const readlinkat = 78;
    const newfstatat = 79;
    const utimensat = 88;
    const execve = 221;
    const renameat2 = 276;
    const statx = 291;
    const openat2 = 437;
    const faccessat2 = 439;
};

export fn syscall(n: c_long, a1: usize, a2: usize, a3: usize, a4: usize, a5: usize, a6: usize) c_long {
    const f = Real("syscall", SyscallFn).get() orelse return fail(ENOSYS);
    var b1: Buf = undefined;
    var b2: Buf = undefined;
    var x1 = a1;
    var x2 = a2;
    var x3 = a3;
    var x4 = a4;
    switch (n) {
        // (dirfd, path, ...)
        SYS.mkdirat, SYS.unlinkat, SYS.faccessat, SYS.faccessat2, SYS.fchmodat, SYS.fchownat, SYS.openat, SYS.openat2, SYS.readlinkat, SYS.newfstatat, SYS.utimensat, SYS.statx => {
            x2 = mapArg(@bitCast(@as(u32, @truncate(a1))), a2, &b1);
        },
        // (olddirfd, oldpath, newdirfd, newpath, ...)
        SYS.renameat, SYS.renameat2, SYS.linkat => {
            x2 = mapArg(@bitCast(@as(u32, @truncate(a1))), a2, &b1);
            x4 = mapArg(@bitCast(@as(u32, @truncate(a3))), a4, &b2);
        },
        // (target, newdirfd, linkpath): only absolute targets are rewritten.
        SYS.symlinkat => {
            x1 = if (a1 == 0) 0 else @intFromPtr(mAbs(@ptrFromInt(a1), &b1));
            x3 = mapArg(@bitCast(@as(u32, @truncate(a2))), a3, &b2);
        },
        SYS.chdir => {
            x1 = mapArg(AT_FDCWD, a1, &b1);
            const r = f(n, x1, x2, x3, x4, a5, a6);
            cwd_valid.store(false, .release);
            return r;
        },
        SYS.execve => return execve(@ptrFromInt(a1), @ptrFromInt(a2), @ptrFromInt(a3)),
        else => {},
    }
    return f(n, x1, x2, x3, x4, a5, a6);
}

fn mapArg(dirfd: c_int, path: usize, buf: *Buf) usize {
    if (path == 0) return 0;
    return @intFromPtr(mAt(dirfd, @ptrFromInt(path), buf));
}
