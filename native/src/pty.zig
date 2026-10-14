//! Pseudo-terminal and child process creation for local sessions.
//!
//! Behaviour follows Termux's create_subprocess (termux-app
//! terminal-emulator/src/main/jni/termux.c, GPLv3): IUTF8 on, flow control
//! off, new session with the pty as controlling terminal, inherited
//! descriptors closed.
const std = @import("std");
const linux = std.os.linux;

/// Bionic libc entry points, declared directly: Zig's translate-c cannot
/// parse Bionic's nullability-annotated headers.
const c = struct {
    const DIR = opaque {};
    const dirent = extern struct {
        d_ino: u64,
        d_off: i64,
        d_reclen: u16,
        d_type: u8,
        d_name: [256]u8,
    };
    const sigset_t = extern struct { bits: [2]u64 };

    const O_RDWR: c_int = 0o2;
    const O_CLOEXEC: c_int = 0o2000000;
    const TCSANOW: c_int = 0;
    const SIG_UNBLOCK: c_int = 1;
    const SIG_DFL: usize = 0;
    const SIGKILL: c_int = 9;
    const SIGSTOP: c_int = 19;
    const EINTR: c_int = 4;
    const TIOCSWINSZ: c_int = @intCast(linux.T.IOCSWINSZ);
    const TIOCSCTTY: c_int = @intCast(linux.T.IOCSCTTY);

    const struct_winsize = extern struct {
        ws_row: u16,
        ws_col: u16,
        ws_xpixel: u16,
        ws_ypixel: u16,
    };

    extern "c" fn open(path: [*:0]const u8, flags: c_int, ...) c_int;
    extern "c" fn close(fd: c_int) c_int;
    extern "c" fn grantpt(fd: c_int) c_int;
    extern "c" fn unlockpt(fd: c_int) c_int;
    extern "c" fn ptsname_r(fd: c_int, buf: [*]u8, len: usize) c_int;
    extern "c" fn tcgetattr(fd: c_int, t: *linux.termios) c_int;
    extern "c" fn tcsetattr(fd: c_int, action: c_int, t: *const linux.termios) c_int;
    extern "c" fn ioctl(fd: c_int, request: c_int, ...) c_int;
    extern "c" fn fork() c_int;
    extern "c" fn setsid() c_int;
    extern "c" fn dup2(old: c_int, new: c_int) c_int;
    extern "c" fn chdir(path: [*:0]const u8) c_int;
    extern "c" fn execve(path: [*:0]const u8, argv: [*:null]const ?[*:0]const u8, envp: [*:null]const ?[*:0]const u8) c_int;
    extern "c" fn write(fd: c_int, buf: [*]const u8, len: usize) isize;
    extern "c" fn _exit(code: c_int) noreturn;
    extern "c" fn sigfillset(set: *sigset_t) c_int;
    extern "c" fn sigprocmask(how: c_int, set: *const sigset_t, old: ?*sigset_t) c_int;
    extern "c" fn signal(sig: c_int, handler: usize) usize;
    extern "c" fn opendir(path: [*:0]const u8) ?*DIR;
    extern "c" fn readdir(dir: *DIR) ?*dirent;
    extern "c" fn closedir(dir: *DIR) c_int;
    extern "c" fn dirfd(dir: *DIR) c_int;
    extern "c" fn waitpid(pid: c_int, status: *c_int, options: c_int) c_int;
    extern "c" fn kill(pid: c_int, sig: c_int) c_int;
    extern "c" fn __errno() *c_int;
};

pub const Spawn = struct {
    fd: c_int,
    pid: c_int,
};

pub const Error = error{ OpenPty, GrantPty, PtsName, Fork };

/// Starts `path` on a new pty. All arguments must be NUL terminated and
/// remain valid until this returns; nothing after `fork` allocates.
pub fn spawn(
    path: [*:0]const u8,
    argv: [*:null]const ?[*:0]const u8,
    envp: [*:null]const ?[*:0]const u8,
    cwd: ?[*:0]const u8,
    rows: u16,
    cols: u16,
    width_px: u16,
    height_px: u16,
) Error!Spawn {
    const ptm = c.open("/dev/ptmx", c.O_RDWR | c.O_CLOEXEC);
    if (ptm < 0) return error.OpenPty;
    errdefer _ = c.close(ptm);

    if (c.grantpt(ptm) != 0 or c.unlockpt(ptm) != 0) return error.GrantPty;

    var devname: [64]u8 = undefined;
    if (c.ptsname_r(ptm, &devname, devname.len) != 0) return error.PtsName;

    // UTF-8 line editing and no XON/XOFF, which would steal Ctrl+S/Ctrl+Q.
    var tios: linux.termios = undefined;
    if (c.tcgetattr(ptm, &tios) == 0) {
        tios.iflag.IUTF8 = true;
        tios.iflag.IXON = false;
        tios.iflag.IXOFF = false;
        _ = c.tcsetattr(ptm, c.TCSANOW, &tios);
    }
    setWindowSize(ptm, rows, cols, width_px, height_px);

    const pid = c.fork();
    if (pid < 0) return error.Fork;
    if (pid > 0) return .{ .fd = ptm, .pid = pid };

    // Child: only async-signal-safe calls from here on.
    var all: c.sigset_t = undefined;
    _ = c.sigfillset(&all);
    _ = c.sigprocmask(c.SIG_UNBLOCK, &all, null);
    var sig: c_int = 1;
    while (sig < 32) : (sig += 1) {
        if (sig == c.SIGKILL or sig == c.SIGSTOP) continue;
        _ = c.signal(sig, c.SIG_DFL);
    }

    _ = c.close(ptm);
    _ = c.setsid();
    const pts = c.open(@ptrCast(&devname), c.O_RDWR);
    if (pts < 0) c._exit(127);
    _ = c.ioctl(pts, c.TIOCSCTTY, @as(c_int, 0));
    _ = c.dup2(pts, 0);
    _ = c.dup2(pts, 1);
    _ = c.dup2(pts, 2);

    if (c.opendir("/proc/self/fd")) |dir| {
        const self_fd = c.dirfd(dir);
        while (c.readdir(dir)) |entry| {
            const fd = parseFd(&entry.d_name) orelse continue;
            if (fd > 2 and fd != self_fd) _ = c.close(fd);
        }
        _ = c.closedir(dir);
    }

    if (cwd) |dir| {
        if (c.chdir(dir) != 0) _ = c.chdir("/");
    }
    _ = c.execve(path, @ptrCast(argv), @ptrCast(envp));

    const msg = "puppy: failed to start shell\r\n";
    _ = c.write(1, msg, msg.len);
    c._exit(127);
}

fn parseFd(name: *const [256]u8) ?c_int {
    var v: c_int = 0;
    var i: usize = 0;
    while (i < name.len and name[i] != 0) : (i += 1) {
        const ch = name[i];
        if (ch < '0' or ch > '9') return null;
        v = v * 10 + (ch - '0');
    }
    return if (i == 0) null else v;
}

pub fn setWindowSize(fd: c_int, rows: u16, cols: u16, width_px: u16, height_px: u16) void {
    var ws: c.struct_winsize = .{
        .ws_row = rows,
        .ws_col = cols,
        .ws_xpixel = width_px,
        .ws_ypixel = height_px,
    };
    _ = c.ioctl(fd, c.TIOCSWINSZ, &ws);
}

/// Blocks until `pid` exits. Returns the exit code, or -signal when the
/// process was killed by a signal.
pub fn waitFor(pid: c_int) c_int {
    var status: c_int = 0;
    while (c.waitpid(pid, &status, 0) < 0) {
        if (c.__errno().* != c.EINTR) return -1;
    }
    const sig = status & 0x7f;
    if (sig == 0) return (status >> 8) & 0xff; // exited
    if (sig != 0x7f) return -sig; // killed by a signal
    return 0;
}

/// Sends `sig` to the child's process group (it leads its own session).
pub fn signalGroup(pid: c_int, sig: c_int) void {
    if (pid <= 0) return;
    _ = c.kill(-pid, sig);
}

pub fn close(fd: c_int) void {
    if (fd >= 0) _ = c.close(fd);
}
