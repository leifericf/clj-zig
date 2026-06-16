// A C header imported straight into Zig: `@cImport` pulls in math.h, and
// the function calls C's `sqrt` directly. The compiler needs libm linked,
// which the Clojure side requests with `:zig/link ["m"]`.
const c = @cImport({
    @cInclude("math.h");
});

pub fn hypotenuse(a: f64, b: f64) f64 {
    return c.sqrt(a * a + b * b);
}
