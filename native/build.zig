const std = @import("std");
const android_ndk = @import("android_ndk.zig");

pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{ .preferred_optimize_mode = .ReleaseFast });

    const mod = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = optimize != .Debug,
    });
    if (b.lazyDependency("ghostty", .{ .target = target, .optimize = optimize })) |dep| {
        mod.addImport("ghostty-vt", dep.module("ghostty-vt"));
    }

    if (target.result.abi.isAndroid()) {
        const ndk = try android_ndk.paths(b, target.result);
        const c = b.addTranslateC(.{
            .root_source_file = b.path("src/c.h"),
            .target = target,
            .optimize = optimize,
            .link_libc = false,
        });
        c.addSystemIncludePath(.{ .cwd_relative = ndk.include });
        c.addSystemIncludePath(.{ .cwd_relative = ndk.arch_include });
        mod.addImport("c", c.createModule());
    }

    const lib = b.addLibrary(.{
        .name = "puppy",
        .linkage = .dynamic,
        .root_module = mod,
    });
    if (target.result.abi.isAndroid()) {
        // Android 15+ devices may use 16 KiB pages.
        lib.link_z_max_page_size = 16384;
        try android_ndk.addPaths(b, lib);
    }
    b.installArtifact(lib);

    // Host tests cover the platform-independent parts (frame encoding,
    // input encoding) against the real Ghostty core.
    const test_mod = b.createModule(.{
        .root_source_file = b.path("src/tests.zig"),
        .target = b.graph.host,
        .optimize = .Debug,
        .link_libc = true,
    });
    if (b.lazyDependency("ghostty", .{ .target = b.graph.host })) |dep| {
        test_mod.addImport("ghostty-vt", dep.module("ghostty-vt"));
    }
    const tests = b.addTest(.{ .root_module = test_mod });
    b.step("test", "Run native unit tests").dependOn(&b.addRunArtifact(tests).step);
}
