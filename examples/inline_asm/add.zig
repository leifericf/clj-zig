// A single hardware add instruction, written by hand rather than left to
// the compiler. The architecture is chosen at compile time, so the same
// function builds on x86_64 and aarch64.
pub fn add(a: i64, b: i64) i64 {
    return switch (@import("builtin").cpu.arch) {
        .aarch64 => asm ("add %[ret], %[a], %[b]"
            : [ret] "=r" (-> i64),
            : [a] "r" (a),
              [b] "r" (b),
        ),
        .x86_64 => asm ("addq %[b], %[ret]"
            : [ret] "=r" (-> i64),
            : [a] "0" (a),
              [b] "r" (b),
        ),
        else => @compileError("add: unsupported architecture"),
    };
}
