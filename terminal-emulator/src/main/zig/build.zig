const std = @import("std");
const builtin = @import("builtin");

pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const enable_logging = b.option(bool, "ghostty-log", "Enable verbose Ghostty JNI logging") orelse (optimize == .Debug);

    const termux_options = b.addOptions();
    termux_options.addOption(bool, "enable_logging", enable_logging);

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/jni_exports.zig"),
        .target = target,
        .optimize = optimize,
        .strip = optimize != .Debug,
        .link_libc = true,
    });

    root_module.addOptions("termux_ghostty_build_options", termux_options);

    if (b.lazyDependency("ghostty", .{
        .target = target,
        .optimize = optimize,
        .simd = false,
    })) |ghostty_dep| {
        root_module.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));
    }

    const lib = b.addLibrary(.{
        .name = "termux-ghostty",
        .linkage = .dynamic,
        .root_module = root_module,
        .version = .{ .major = 0, .minor = 0, .patch = 0 },
    });
    lib.linkLibC();

    if (lib.rootModuleTarget().abi.isAndroid()) {
        lib.link_z_max_page_size = 16384;
        lib.linkSystemLibrary("log");
        try addAndroidNdkPaths(b, lib);
    }

    lib.installHeadersDirectory(
        b.path("include"),
        "",
        .{ .include_extensions = &.{ ".h" } },
    );

    b.installArtifact(lib);
}

fn addAndroidNdkPaths(b: *std.Build, step: *std.Build.Step.Compile) !void {
    const target = step.rootModuleTarget();
    const ndk_path = findAndroidNdkPath(b) orelse return error.AndroidNDKNotFound;
    const ndk_triple = ndkTriple(target) orelse return error.AndroidNDKUnsupportedTarget;
    const host = hostTag() orelse return error.AndroidNDKUnsupportedHost;

    const sysroot = b.pathJoin(&.{
        ndk_path,
        "toolchains",
        "llvm",
        "prebuilt",
        host,
        "sysroot",
    });
    const include_dir = b.pathJoin(&.{
        sysroot,
        "usr",
        "include",
    });
    const sys_include_dir = b.pathJoin(&.{
        sysroot,
        "usr",
        "include",
        ndk_triple,
    });
    const c_runtime_dir = b.pathJoin(&.{
        sysroot,
        "usr",
        "lib",
        ndk_triple,
        b.fmt("{d}", .{target.os.version_range.linux.android}),
    });
    const lib_dir = b.pathJoin(&.{
        sysroot,
        "usr",
        "lib",
        ndk_triple,
    });
    const cpp_include_dir = b.pathJoin(&.{
        sysroot,
        "usr",
        "include",
        "c++",
        "v1",
    });

    const libc_txt = b.fmt(
        \\include_dir={s}
        \\sys_include_dir={s}
        \\crt_dir={s}
        \\msvc_lib_dir=
        \\kernel32_lib_dir=
        \\gcc_dir=
    , .{ include_dir, sys_include_dir, c_runtime_dir });

    const write_files = b.addWriteFiles();
    const libc_path = write_files.add("libc.txt", libc_txt);

    step.setLibCFile(libc_path);
    step.root_module.addSystemIncludePath(.{ .cwd_relative = include_dir });
    step.root_module.addSystemIncludePath(.{ .cwd_relative = cpp_include_dir });
    step.root_module.addLibraryPath(.{ .cwd_relative = c_runtime_dir });
    step.root_module.addLibraryPath(.{ .cwd_relative = lib_dir });
}

fn findAndroidNdkPath(b: *std.Build) ?[]const u8 {
    if (std.process.getEnvVarOwned(b.allocator, "ANDROID_NDK_HOME") catch null) |value| {
        if (value.len == 0) {
            return null;
        }

        var dir = std.fs.openDirAbsolute(value, .{}) catch return null;
        defer dir.close();
        return value;
    }

    for ([_][]const u8{ "ANDROID_HOME", "ANDROID_SDK_ROOT" }) |env| {
        if (std.process.getEnvVarOwned(b.allocator, env) catch null) |sdk| {
            if (sdk.len == 0) {
                continue;
            }

            if (findLatestAndroidNdk(b, sdk)) |ndk| {
                return ndk;
            }
        }
    }

    const home = std.process.getEnvVarOwned(
        b.allocator,
        if (builtin.os.tag == .windows) "LOCALAPPDATA" else "HOME",
    ) catch return null;

    const default_sdk_path = b.pathJoin(&.{
        home,
        switch (builtin.os.tag) {
            .linux => "Android/sdk",
            .macos => "Library/Android/Sdk",
            .windows => "Android/Sdk",
            else => return null,
        },
    });

    return findLatestAndroidNdk(b, default_sdk_path);
}

fn findLatestAndroidNdk(b: *std.Build, sdk_path: []const u8) ?[]const u8 {
    const ndk_dir = b.pathJoin(&.{ sdk_path, "ndk" });
    var dir = std.fs.openDirAbsolute(ndk_dir, .{ .iterate = true }) catch return null;
    defer dir.close();

    var latest: ?struct {
        name: []const u8,
        version: std.SemanticVersion,
    } = null;
    var iterator = dir.iterate();

    while (iterator.next() catch null) |file| {
        if (file.kind != .directory) {
            continue;
        }

        const version = std.SemanticVersion.parse(file.name) catch continue;
        if (latest) |current| {
            if (version.order(current.version) != .gt) {
                continue;
            }
        }

        latest = .{
            .name = file.name,
            .version = version,
        };
    }

    const current_latest = latest orelse return null;
    return b.pathJoin(&.{ sdk_path, "ndk", current_latest.name });
}

fn hostTag() ?[]const u8 {
    return switch (builtin.os.tag) {
        .linux => "linux-x86_64",
        .macos => "darwin-x86_64",
        .windows => "windows-x86_64",
        else => null,
    };
}

fn ndkTriple(target: std.Target) ?[]const u8 {
    return switch (target.cpu.arch) {
        .arm => "arm-linux-androideabi",
        .aarch64 => "aarch64-linux-android",
        .x86 => "i686-linux-android",
        .x86_64 => "x86_64-linux-android",
        else => null,
    };
}
