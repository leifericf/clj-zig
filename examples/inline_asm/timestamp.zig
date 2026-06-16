// Reads the CPU's cycle or virtual timer counter straight from a control
// register: rdtsc on x86_64, cntvct_el0 on aarch64. There is no portable
// instruction and no JVM equivalent; this reaches the register directly.
pub fn timestamp() u64 {
    return switch (@import("builtin").cpu.arch) {
        .aarch64 => asm volatile ("mrs %[ret], cntvct_el0"
            : [ret] "=r" (-> u64),
        ),
        .x86_64 => blk: {
            var low: u32 = undefined;
            var high: u32 = undefined;
            asm volatile ("rdtsc"
                : [low] "={eax}" (low),
                  [high] "={edx}" (high),
            );
            break :blk (@as(u64, high) << 32) | @as(u64, low);
        },
        else => @compileError("timestamp: unsupported architecture"),
    };
}
