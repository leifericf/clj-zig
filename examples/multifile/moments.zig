pub fn mean(xs: []const f64) f64 {
    var total: f64 = 0;
    for (xs) |x| total += x;
    return total / @as(f64, @floatFromInt(xs.len));
}
