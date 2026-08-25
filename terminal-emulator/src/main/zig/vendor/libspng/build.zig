const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const lib = b.addLibrary(.{
        .name = "spng",
        .linkage = .static,
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });

    lib.root_module.addCSourceFiles(.{
        .root = b.path("."),
        .files = &.{"spng.c"},
        .flags = &.{"-O2"},
    });

    lib.installHeader(b.path("spng.h"), "spng.h");
    b.installArtifact(lib);
}

