"""Numerical regression for the camera basis actually declared by the billboard shaders.

Reads their axis/sign contract, then projects known world-space surface normals through
the native orthographic camera. This catches a reflected direction basis even when a
sphere's outline and the independent projection helper are both correct.
"""

import math
from pathlib import Path
import re
import unittest


SHADERS = Path(__file__).resolve().parents[2] / "main" / "shaders" / "solarlab"
VERTEX = (SHADERS / "billboard.vert").read_text()
FRAGMENT = (SHADERS / "billboard.frag").read_text()


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def normalized(v):
    length = math.sqrt(dot(v, v))
    return tuple(x / length for x in v)


def camera_basis(yaw, pitch):
    # Native right/up/forward axes; forward points away from the viewer.
    right = (math.cos(yaw), math.sin(yaw), 0.0)
    forward = (-math.sin(yaw) * math.cos(pitch), math.cos(yaw) * math.cos(pitch),
               -math.sin(pitch))
    return {"rightAndSpan": right, "upAndSpan": cross(right, forward),
            "forwardAndDepth": forward}


def shader_direction(world, basis):
    function = VERTEX.split("vec3 directionToCamera(vec3 worldDirection)", 1)[1]
    declaration = function.split("vec3 transformed = vec3(", 1)[1].split(");", 1)[0]
    terms = re.findall(r"(-?)dot\(worldDirection,\s*uScene\.(\w+)\.xyz\)", declaration)
    if len(terms) != 3:
        raise AssertionError("Update the surface-frame regression for the new shader transform")
    return normalized(tuple((-1.0 if sign else 1.0) * dot(world, basis[axis])
                            for sign, axis in terms))


def fragment_normal(world, basis):
    match = re.search(r"vec2 cameraUv = vec2\(vBillboardUv.x,\s*(-?)vBillboardUv.y\)", FRAGMENT)
    if match is None:
        raise AssertionError("Update the regression for the new fragment UV convention")
    # Positive Vulkan clip Y runs down the window, while native camera up runs up it.
    screen_y = -dot(world, basis["upAndSpan"])
    return (dot(world, basis["rightAndSpan"]),
            (-1.0 if match.group(1) else 1.0) * screen_y,
            -dot(world, basis["forwardAndDepth"]))


class SurfaceFrameTest(unittest.TestCase):
    def test_world_features_keep_body_coordinates_as_camera_orbits(self):
        self.assertIn("directionToCamera(normalize(cross(surfaceReferenceWs, poleWs)))", VERTEX)
        self.assertIn("mapped ? normalize(vSurfaceFrame.xyz)", FRAGMENT)
        feature = normalized((0.3, 0.4, 0.866))
        for pole in [(0.0, 0.0, 1.0), normalized((0.2, -0.4, 0.8))]:
            reference = (0.0, 0.0, 1.0) if abs(pole[2]) < 0.92 else (1.0, 0.0, 0.0)
            east = normalized(cross(reference, pole))
            meridian = normalized(cross(pole, east))
            expected = (dot(feature, east), dot(feature, pole), dot(feature, meridian))
            for yaw_degrees in (0, 37, 90, 180, 270):
                for pitch_degrees in (44, 63, 80):
                    with self.subTest(pole=pole, yaw=yaw_degrees, pitch=pitch_degrees):
                        basis = camera_basis(math.radians(yaw_degrees), math.radians(pitch_degrees))
                        normal = fragment_normal(feature, basis)
                        self.assertGreater(normal[2], 0.0)  # The tested feature is actually visible.
                        pole_camera = shader_direction(pole, basis)
                        east_camera = shader_direction(east, basis)
                        meridian_camera = normalized(cross(pole_camera, east_camera))
                        actual = (dot(normal, east_camera), dot(normal, pole_camera),
                                  dot(normal, meridian_camera))
                        for actual_coordinate, expected_coordinate in zip(actual, expected):
                            self.assertAlmostEqual(actual_coordinate, expected_coordinate, places=10)

    def test_light_and_surface_normal_share_the_same_handed_frame(self):
        self.assertIn("vLightDirection = directionToCamera(toLightWorld);", VERTEX)
        feature = normalized((0.3, 0.4, 0.866))
        light = normalized((-0.7, 0.2, 0.4))
        for yaw_degrees in (0, 90, 180, 270):
            basis = camera_basis(math.radians(yaw_degrees), math.radians(63))
            normal = fragment_normal(feature, basis)
            self.assertAlmostEqual(dot(normal, shader_direction(light, basis)), dot(feature, light), places=10)
            self.assertAlmostEqual(dot(normal, shader_direction(feature, basis)), 1.0, places=10)


if __name__ == "__main__":
    unittest.main()
