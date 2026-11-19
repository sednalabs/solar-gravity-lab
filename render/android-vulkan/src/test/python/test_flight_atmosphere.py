"""Execute the production GLSL scattering function on the host.

Only GLSL vector syntax and its out parameter are adapted. Tests exercise actual
shader arithmetic against geometry/energy invariants, not a second implementation.
This is not SPIR-V compilation, image acceptance or a device performance claim.
"""

from pathlib import Path
import subprocess
import tempfile
import unittest


SHADER = Path(__file__).resolve().parents[2] / "main/shaders/solarlab/billboard.frag"

VECTOR_ADAPTER = r"""
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
using std::max; using std::min; using std::sqrt; using std::pow; using std::exp;
float clamp(float x, float a, float b) { return max(a, min(x, b)); }
struct vec2 {
    float x, y;
    explicit vec2(float a): x(a), y(a) {}
    vec2(float a, float b): x(a), y(b) {}
};
struct vec3 {
    float x, y, z;
    explicit vec3(float a): x(a), y(a), z(a) {}
    vec3(float a, float b, float c): x(a), y(b), z(c) {}
};
vec2 operator+(vec2 a, vec2 b) { return vec2(a.x+b.x, a.y+b.y); }
vec2 operator*(vec2 a, vec2 b) { return vec2(a.x*b.x, a.y*b.y); }
vec2 operator*(vec2 a, float b) { return vec2(a.x*b, a.y*b); }
vec2 operator/(float a, vec2 b) { return vec2(a/b.x, a/b.y); }
vec2& operator+=(vec2& a, vec2 b) { a=a+b; return a; }
vec2 max(vec2 a, vec2 b) { return vec2(max(a.x,b.x), max(a.y,b.y)); }
vec2 exp(vec2 a) { return vec2(exp(a.x), exp(a.y)); }
vec3 operator+(vec3 a, vec3 b) { return vec3(a.x+b.x, a.y+b.y, a.z+b.z); }
vec3 operator-(vec3 a, vec3 b) { return vec3(a.x-b.x, a.y-b.y, a.z-b.z); }
vec3 operator-(vec3 a) { return vec3(-a.x, -a.y, -a.z); }
vec3 operator*(vec3 a, vec3 b) { return vec3(a.x*b.x, a.y*b.y, a.z*b.z); }
vec3 operator*(vec3 a, float b) { return vec3(a.x*b, a.y*b, a.z*b); }
vec3 operator*(float a, vec3 b) { return b*a; }
vec3 operator/(vec3 a, float b) { return a*(1.0f/b); }
vec3& operator+=(vec3& a, vec3 b) { a=a+b; return a; }
vec3 max(vec3 a, vec3 b) { return vec3(max(a.x,b.x), max(a.y,b.y), max(a.z,b.z)); }
vec3 exp(vec3 a) { return vec3(exp(a.x), exp(a.y), exp(a.z)); }
float dot(vec3 a, vec3 b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
float length(vec3 a) { return sqrt(dot(a,a)); }
vec3 normalize(vec3 a) { return a/length(a); }
vec2 vAtmosphereRatioAndDensity(0.0f,1.0f);
vec3 vLightDirection(0.0f,0.0f,1.0f);
"""

INVARIANTS = r"""
void near(vec3 a, vec3 b, float tolerance=1e-4f) {
    assert(std::abs(a.x-b.x)<tolerance);
    assert(std::abs(a.y-b.y)<tolerance);
    assert(std::abs(a.z-b.z)<tolerance);
}
void bounded(vec3 a) {
    for(float x: {a.x,a.y,a.z}) assert(std::isfinite(x) && x>=0.0f && x<=1.0f);
}
vec3 turn(vec3 v, float angle) {
    return vec3(v.x*std::cos(angle)+v.z*std::sin(angle),v.y,
        -v.x*std::sin(angle)+v.z*std::cos(angle));
}
void setSun(vec3 towardsSun) {
    vLightDirection=vec3(towardsSun.x,towardsSun.y,-towardsSun.z);
}
int main() {
    const vec3 ray(0.0f,0.0f,1.0f), centre(0.0f,0.0f,1.125f);
    constexpr float outer=1.01569612f;
    vec3 transmission(0.0f);
    // Vacuum and a ray missing the shell must be exact identity operations.
    vAtmosphereRatioAndDensity.y=0.0f;
    near(flightAtmosphere(ray,centre,1.0f,outer,0.125f,transmission),vec3(0.0f));
    near(transmission,vec3(1.0f));
    vAtmosphereRatioAndDensity.y=1.0f;
    near(flightAtmosphere(vec3(1.0f,0.0f,0.0f),centre,1.0f,outer,10.0f,transmission),vec3(0.0f));
    near(transmission,vec3(1.0f));
    // Daylight molecular scattering is blue; rotating the Sun behind the solid
    // planet removes in-scattered light, but preserves view-path extinction.
    setSun(vec3(0.0f,0.0f,-1.0f));
    vec3 day=flightAtmosphere(ray,centre,1.0f,outer,0.125f,transmission);
    bounded(day); bounded(transmission);
    assert(day.z>day.y && day.y>day.x && day.x>0.0f);
    vec3 daylightTransmission=transmission;
    setSun(vec3(0.0f,0.0f,1.0f));
    near(flightAtmosphere(ray,centre,1.0f,outer,0.125f,transmission),vec3(0.0f));
    near(transmission,daylightTransmission);
    // The full visible atmospheric chord, including a ray that misses the
    // surface, must produce light. This catches a missing off-surface atmosphere.
    const vec3 limbCentre(1.001f,0.0f,1.125f);
    const vec3 sunlight=normalize(vec3(-1.0f,0.0f,-1.0f));
    setSun(sunlight);
    vec3 limb=flightAtmosphere(ray,limbCentre,1.0f,outer,10.0f,transmission);
    bounded(limb); bounded(transmission); assert(length(limb)>0.02f);
    const vec3 limbTransmission=transmission;
    // Coordinate rotations and metre-to-radius scaling cannot alter the image.
    for(float angle: {0.3f,1.0f,2.2f,3.14f}) {
        setSun(turn(sunlight,angle));
        near(flightAtmosphere(turn(ray,angle),turn(limbCentre,angle),1.0f,outer,10.0f,transmission),limb,3e-4f);
        near(transmission,limbTransmission,3e-4f);
    }
    setSun(sunlight);
    near(flightAtmosphere(ray,limbCentre*6371000.0f,6371000.0f,outer*6371000.0f,
        63710000.0f,transmission),limb,3e-4f);
    near(transmission,limbTransmission,3e-4f);
    // Cameras within the shell and rays near the tangent remain finite/bounded.
    for(float altitude: {0.00001f,0.001f,0.015f,0.1f,4.0f}) {
        for(float angle: {-0.8f,-0.3f,0.0f,0.3f,0.8f}) {
            vec3 sight=normalize(vec3(std::sin(angle),0.0f,std::cos(angle)));
            bounded(flightAtmosphere(sight,vec3(1.001f,0.0f,1.0f+altitude),1.0f,outer,10.0f,transmission));
            bounded(transmission);
        }
    }
    std::cout << "Production atmosphere: vacuum, miss, planet shadow, lit limb, rotation, scale and finite bounds passed.\n";
}
"""


class FlightAtmosphereTest(unittest.TestCase):
    def test_actual_shader_scattering_respects_geometry_and_energy_bounds(self):
        source = SHADER.read_text()
        begin = source.index("vec3 flightAtmosphere(")
        end = source.index("\nvoid perspectiveBody()", begin)
        function = source[begin:end].replace("out vec3 transmittance", "vec3& transmittance")
        # C++ has stricter float overloads than GLSL. Preserve values, add suffixes.
        import re
        function = re.sub(r"(?<![\w.])(\d+\.\d*(?:e[+-]?\d+)?|\d+e[+-]?\d+)(?![\w.])", r"\1f", function)
        with tempfile.TemporaryDirectory(prefix="sgl-atmosphere-") as temporary:
            cpp = Path(temporary) / "atmosphere.cpp"
            binary = Path(temporary) / "atmosphere-test"
            cpp.write_text(VECTOR_ADAPTER + function + INVARIANTS)
            subprocess.run(["g++", "-std=c++20", "-Wall", "-Wextra", "-Werror", str(cpp), "-o", str(binary)], check=True)
            result = subprocess.run([str(binary)], check=True, capture_output=True, text=True)
            self.assertIn("passed", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
