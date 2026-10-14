//! Points a compile step at the Android NDK sysroot for Bionic libc.
//!
//! Adapted from Ghostty's pkg/android-ndk (MIT). Zig cannot yet target
//! Bionic natively (ziglang/zig#23906), so we hand it a libc file.
//! The NDK is taken from ANDROID_NDK_HOME, which the Gradle build sets to
//! the pinned `ndkVersion`.
const std = @import("std");
const builtin = @import("builtin");

pub const Paths = struct {
    libc: std.Build.LazyPath,
    include: []const u8,
    arch_include: []const u8,
    lib: []const u8,
};

pub fn paths(b: *std.Build, target: std.Target) !Paths {
    const ndk = b.graph.environ_map.get("ANDROID_NDK_HOME") orelse
        return error.AndroidNdkHomeNotSet;
    const triple: []const u8 = switch (target.cpu.arch) {
        .aarch64 => "aarch64-linux-android",
        .x86_64 => "x86_64-linux-android",
        else => return error.AndroidNdkUnsupportedTarget,
    };
    const host: []const u8 = switch (builtin.os.tag) {
        .macos => "darwin-x86_64",
        .linux => "linux-x86_64",
        else => return error.AndroidNdkUnsupportedHost,
    };
    const api = target.os.version_range.linux.android;
    const sysroot = b.pathJoin(&.{ ndk, "toolchains", "llvm", "prebuilt", host, "sysroot" });
    const include = b.pathJoin(&.{ sysroot, "usr", "include" });
    const libc_txt = b.fmt(
        \\include_dir={s}
        \\sys_include_dir={s}
        \\crt_dir={s}
        \\msvc_lib_dir=
        \\kernel32_lib_dir=
        \\gcc_dir=
    , .{
        include,
        b.pathJoin(&.{ include, triple }),
        b.pathJoin(&.{ sysroot, "usr", "lib", triple, b.fmt("{d}", .{api}) }),
    });
    const wf = b.addWriteFiles();
    return .{
        .libc = wf.add("libc.txt", libc_txt),
        .include = include,
        .arch_include = b.pathJoin(&.{ include, triple }),
        .lib = b.pathJoin(&.{ sysroot, "usr", "lib", triple }),
    };
}

pub fn addPaths(b: *std.Build, step: *std.Build.Step.Compile) !void {
    const p = try paths(b, step.rootModuleTarget());
    step.setLibCFile(p.libc);
    step.root_module.addSystemIncludePath(.{ .cwd_relative = b.pathJoin(&.{ p.include, "c++", "v1" }) });
    step.root_module.addLibraryPath(.{ .cwd_relative = p.lib });
}
