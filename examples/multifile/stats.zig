// A body split across files: this one @imports a sibling and calls into
// it. The import resolves from this file's directory, the way Zig resolves
// it; clj-zig reproduces moments.zig beside the generated source.
const moments = @import("moments.zig");

pub fn variance(xs: []const f64) f64 {
    const m = moments.mean(xs);
    var total: f64 = 0;
    for (xs) |x| {
        const d = x - m;
        total += d * d;
    }
    return total / @as(f64, @floatFromInt(xs.len));
}
