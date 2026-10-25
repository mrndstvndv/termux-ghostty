const std = @import("std");
const builtin = @import("builtin");
const translate_c = @import("translate_c");

pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const enable_logging = b.option(bool, "ghostty-log", "Enable verbose Ghostty JNI logging") orelse (optimize == .debug);
    const test_filters = b.option(
        [][]const u8,
        "test-filter",
        "Only run matching Zig tests",
    ) orelse &[0][]const u8{};

    const termux_options = b.addOptions();
    termux_options.addOption(bool, "enable_logging", enable_logging);

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/jni_exports.zig"),
        .target = target,
        .optimize = optimize,
        .strip = optimize != .debug,
        .link_libc = true,
    });

    root_module.addOptions("termux_ghostty_build_options", termux_options);

    var test_module_opt: ?*std.Build.Module = null;
    const test_step = b.step("test", "Run terminal wrapper Zig tests");

    // Pure Zig, no libssh2 or JNI: these SSH seams run on the host. The stream pump and
    // pipe harness (ssh_stream_harness.zig) needs JDK and libssh2 headers, so it is not wired here.
    for ([_][]const u8{
        "src/ssh_stream_buffer.zig",
        "src/ssh_open_queue.zig",
        "src/ssh_shell_exit.zig",
    }) |root_source| {
        const seam_tests = b.addTest(.{
            .root_module = b.createModule(.{
                .root_source_file = b.path(root_source),
                .target = target,
                .optimize = optimize,
            }),
            .filters = test_filters,
        });
        test_step.dependOn(&b.addRunArtifact(seam_tests).step);
    }
    if (b.lazyDependency("ghostty", .{
        .target = target,
        .optimize = optimize,
        .simd = false,
    })) |ghostty_dep| {
        const ghostty_module = ghostty_dep.module("ghostty-vt");
        root_module.addImport("ghostty-vt", ghostty_module);

        const test_module = b.createModule(.{
            .root_source_file = b.path("src/termux_ghostty.zig"),
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        });
        test_module.addOptions("termux_ghostty_build_options", termux_options);
        test_module.addImport("ghostty-vt", ghostty_module);
        test_module_opt = test_module;
        const tests = b.addTest(.{
            .root_module = test_module,
            .filters = test_filters,
        });
        test_step.dependOn(&b.addRunArtifact(tests).step);
    }

    var mbedtls_lib: ?*std.Build.Step.Compile = null;
    if (b.lazyDependency("mbedtls", .{
        .target = target,
        .optimize = optimize,
    })) |mbedtls_dep| {
        const lib_dep = mbedtls_dep.artifact("mbedtls");
        if (lib_dep.rootModuleTarget().abi.isAndroid()) {
            try addAndroidNdkPaths(b, lib_dep);
        }
        mbedtls_lib = lib_dep;
        // zig 0.16 defaulted static-lib modules to PIC off; they're linked
        // into a shared lib so they must be position-independent.
        lib_dep.root_module.pic = true;
        root_module.linkLibrary(lib_dep);
        if (test_module_opt) |tm| tm.linkLibrary(lib_dep);
    }

    var zlib_lib: ?*std.Build.Step.Compile = null;
    if (b.lazyDependency("zlib", .{
        .target = target,
        .optimize = optimize,
    })) |zlib_dep| {
        const z_lib = zlib_dep.artifact("z");
        if (z_lib.rootModuleTarget().abi.isAndroid()) {
            try addAndroidNdkPaths(b, z_lib);
        }
        z_lib.root_module.pic = true;
        zlib_lib = z_lib;
        root_module.linkLibrary(z_lib);
        if (test_module_opt) |tm| tm.linkLibrary(z_lib);
    }

    // Kitty graphics PNG decoding via libspng backed by zlib.
    if (b.lazyDependency("libspng", .{
        .target = target,
        .optimize = optimize,
    })) |spng_dep| {
        const spng_lib = spng_dep.artifact("spng");
        if (spng_lib.rootModuleTarget().abi.isAndroid()) {
            try addAndroidNdkPaths(b, spng_lib);
        }
        spng_lib.root_module.pic = true;
        if (zlib_lib) |z| spng_lib.root_module.linkLibrary(z);
        root_module.linkLibrary(spng_lib);
        if (test_module_opt) |tm| tm.linkLibrary(spng_lib);
    }

    var libssh2_lib: ?*std.Build.Step.Compile = null;
    if (b.lazyDependency("libssh2", .{
        .target = target,
        .optimize = optimize,
        .@"crypto-backend" = .mbedtls,
        .@"link-system-crypto-backend" = false,
        .@"zlib" = true,
    })) |libssh2_dep| {
        const lib_dep = libssh2_dep.artifact("ssh2");
        libssh2_lib = lib_dep;
        if (lib_dep.rootModuleTarget().abi.isAndroid()) {
            try addAndroidNdkPaths(b, lib_dep);
        }
        if (mbedtls_lib) |m| {
            lib_dep.root_module.linkLibrary(m);
        }
        lib_dep.root_module.pic = true;
        root_module.linkLibrary(lib_dep);
    }

    const lib = b.addLibrary(.{
        .name = "termux-ghostty",
        .linkage = .dynamic,
        .root_module = root_module,
        .version = .{ .major = 0, .minor = 0, .patch = 0 },
    });

    if (lib.rootModuleTarget().abi.isAndroid()) {
        lib.link_z_max_page_size = 16384;
        lib.root_module.linkSystemLibrary("log", .{});
        try addAndroidNdkPaths(b, lib);
        try addAndroidCImport(b, lib, "android_log_c", "src/c/android_log.h", &.{});
        if (libssh2_lib) |ssh2| {
            try addAndroidCImport(b, lib, "ssh_c", "src/c/ssh.h", &.{ssh2.getEmittedIncludeTree()});
        }
    }

    lib.installHeadersDirectory(
        b.path("include"),
        "",
        .{ .include_extensions = &.{ ".h" } },
    );

    b.installArtifact(lib);
}

const AndroidNdkPaths = struct {
    include_dir: []const u8,
    sys_include_dir: []const u8,
    cpp_include_dir: []const u8,
    c_runtime_dir: []const u8,
    lib_dir: []const u8,
};

fn addAndroidNdkPaths(b: *std.Build, step: *std.Build.Step.Compile) !void {
    const paths = try androidNdkPaths(b, step.rootModuleTarget());

    step.setLibCFile(androidLibcFile(b, paths));
    step.root_module.addSystemIncludePath(.{ .cwd_relative = paths.include_dir });
    step.root_module.addSystemIncludePath(.{ .cwd_relative = paths.cpp_include_dir });
    step.root_module.addLibraryPath(.{ .cwd_relative = paths.c_runtime_dir });
    step.root_module.addLibraryPath(.{ .cwd_relative = paths.lib_dir });
}

fn androidLibcFile(b: *std.Build, paths: AndroidNdkPaths) std.Build.LazyPath {
    const libc_txt = b.fmt(
        \\include_dir={s}
        \\sys_include_dir={s}
        \\crt_dir={s}
        \\msvc_lib_dir=
        \\kernel32_lib_dir=
        \\gcc_dir={s}
        \\darwin_sdk_dir=
    , .{ paths.include_dir, paths.sys_include_dir, paths.c_runtime_dir, paths.c_runtime_dir });

    const write_files = b.addWriteFiles();
    return write_files.add("libc.txt", libc_txt);
}

/// Zig 0.17 removed @cImport. Translates one of the wrapper headers in src/c for the Android
/// target and makes it importable as `name` from the step's root module.
///
/// This mirrors the `Translator.init` path of Ghostty's pkg/translate-c wrapper (`initTranslator`
/// in the pinned Ghostty), using the same upstream translate_c 2.0.0 package. The NDK libc file is
/// passed directly, so translate-c uses the NDK headers and not the host SDK. Revisit this when
/// bumping the Ghostty pin.
fn addAndroidCImport(
    b: *std.Build,
    step: *std.Build.Step.Compile,
    name: []const u8,
    header: []const u8,
    include_paths: []const std.Build.LazyPath,
) !void {
    const paths = try androidNdkPaths(b, step.rootModuleTarget());
    const translated = translate_c.Translator.init(b.dependency("translate_c", .{}), .{
        .c_source_file = b.path(header),
        .target = step.root_module.resolved_target.?,
        .optimize = step.root_module.optimize.?,
        // translate-c only takes include dirs from the libc file when libc is linked.
        .link_libc = true,
        .libc_file = androidLibcFile(b, paths),
    });
    for (include_paths) |path| translated.addIncludePath(path);
    step.root_module.addImport(name, translated.mod);
}

fn androidNdkPaths(b: *std.Build, target: std.Target) !AndroidNdkPaths {
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

    return .{
        .include_dir = include_dir,
        .sys_include_dir = sys_include_dir,
        .cpp_include_dir = cpp_include_dir,
        .c_runtime_dir = c_runtime_dir,
        .lib_dir = lib_dir,
    };
}

fn findAndroidNdkPath(b: *std.Build) ?[]const u8 {
    if (b.graph.environ_map.get("ANDROID_NDK_HOME")) |value| {
        if (value.len == 0) {
            return null;
        }

        var dir = std.Io.Dir.openDirAbsolute(b.graph.io, value, .{}) catch return null;
        defer dir.close(b.graph.io);
        return value;
    }

    for ([_][]const u8{ "ANDROID_HOME", "ANDROID_SDK_ROOT" }) |env| {
        if (b.graph.environ_map.get(env)) |sdk| {
            if (sdk.len == 0) {
                continue;
            }

            if (findLatestAndroidNdk(b, sdk)) |ndk| {
                return ndk;
            }
        }
    }

    const home = b.graph.environ_map.get(
        if (builtin.os.tag == .windows) "LOCALAPPDATA" else "HOME",
    ) orelse return null;

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
    var dir = std.Io.Dir.cwd().openDir(b.graph.io, ndk_dir, .{ .iterate = true }) catch return null;
    defer dir.close(b.graph.io);
    var latest: ?struct {
        name: []const u8,
        version: std.SemanticVersion,
    } = null;
    var iterator = dir.iterate();

    while (iterator.next(b.graph.io) catch null) |file| {
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
        .x86_64 => "x86_64-linux-android",
        else => null,
    };
}
