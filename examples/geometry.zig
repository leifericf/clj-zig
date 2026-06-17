//! clj-zig: geometry
// Bodies for the geometry namespace. Each Clojure defnz is declared
// bodyless; the pub fn of the same name here is its body. The shared
// @cImport and the square helper are written once and used by both.
const c = @cImport({
    @cInclude("math.h");
});

const pi: f64 = 3.141592653589793;

fn square(x: f64) f64 {
    return x * x;
}

pub fn hypotenuse(a: f64, b: f64) f64 {
    return c.sqrt(square(a) + square(b));
}

pub fn circle_area(r: f64) f64 {
    return pi * square(r);
}
