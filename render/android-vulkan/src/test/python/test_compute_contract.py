"""Regression for the actual C++/GLSL compaction ABI and camera projection.

This deliberately reads production declarations. A successful dispatch is not enough:
the former vec2 compute output had a 16-byte stride while graphics read 20 bytes, and
the former uniform prefix interpreted the 3D camera basis as old 2D spans.
Native instrumentation separately checks submitted visible pixels and GPU readbacks.
"""

import ctypes
import math
from pathlib import Path
import re
import struct
import unittest


MAIN = Path(__file__).resolve().parents[2] / "main"
HEADER = (MAIN / "cpp" / "SolarLabVulkanRenderer.h").read_text()
CPP = (MAIN / "cpp" / "SolarLabVulkanRenderer.cpp").read_text()
SHADERS = MAIN / "shaders" / "solarlab"


def block(source, declaration):
    body = source.split(declaration, 1)[1].split("{", 1)[1]
    depth = 1
    for index, character in enumerate(body):
        depth += (character == "{") - (character == "}")
        if depth == 0:
            return body[:index]
    raise AssertionError("Unclosed declaration: " + declaration)


def cpp_record(name):
    fields = re.findall(r"\b(float|uint32_t)\s+(\w+)\s*=", block(HEADER, "struct " + name))
    kinds = {"float": ctypes.c_float, "uint32_t": ctypes.c_uint32}
    return type(name, (ctypes.Structure,), {"_fields_": [(field, kinds[kind]) for kind, field in fields]})


def std430_record(shader, name):
    # Only scalar/vector records are used here. Work out actual std430 array strides,
    # including the padding a future vec3 edit would introduce.
    fields = re.findall(r"\b(float|uint|vec[234]|uvec[234])\s+(\w+)\s*;", block(shader, "struct " + name))
    offsets = {}
    position, alignment = 0, 1
    for kind, field in fields:
        count = int(kind[-1]) if kind[-1].isdigit() else 1
        member_alignment = 4 if count == 1 else 8 if count == 2 else 16
        position = (position + member_alignment - 1) // member_alignment * member_alignment
        offsets[field] = position
        position += count * 4
        alignment = max(alignment, member_alignment)
    return offsets, (position + alignment - 1) // alignment * alignment


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def basis(yaw, pitch):
    return {
        "rightAndSpan": (math.cos(yaw), math.sin(yaw), 0.0),
        "upAndSpan": (-math.sin(yaw) * math.sin(pitch), math.cos(yaw) * math.sin(pitch), math.cos(pitch)),
        "forwardAndDepth": (-math.sin(yaw) * math.cos(pitch), math.cos(yaw) * math.cos(pitch), -math.sin(pitch)),
    }


class ComputeContractTest(unittest.TestCase):
    def test_binary_records_preserve_z_and_graphics_stride(self):
        for source_name, output_name, shader_name in (
            ("MediumTracerState", "CheapPointVertex", "compact_medium.comp"),
            ("MediumTracerState", "CheapPointVertex", "compact_medium_subgroup.comp"),
            ("MediumTracerState", "CheapPointVertex", "compact_medium_bda.comp"),
            ("FarTracerState", "DensityPointVertex", "compact_far.comp"),
            ("FarTracerState", "DensityPointVertex", "compact_far_subgroup.comp"),
        ):
            shader = (SHADERS / shader_name).read_text()
            for name, stride in ((source_name, 40), (output_name, 20)):
                with self.subTest(record=name):
                    native = cpp_record(name)
                    offsets, shader_stride = std430_record(shader, name)
                    self.assertEqual(ctypes.sizeof(native), stride)
                    self.assertEqual(shader_stride, ctypes.sizeof(native))
                    self.assertEqual(offsets, {field: getattr(native, field).offset for field, _ in native._fields_})
                    records = (native * 3)()
                    for index, record in enumerate(records):
                        record.x, record.y, record.z = 1.25 + index, -200.0 - index, 8192.0 + index
                        record.colorArgb = 0xA1B2C300 + index
                    packed = bytes(records)
                    for index in range(3):
                        self.assertEqual(struct.unpack_from("f", packed, index * shader_stride + offsets["z"])[0], 8192.0 + index)
                        self.assertEqual(struct.unpack_from("I", packed, index * shader_stride + offsets["colorArgb"])[0], 0xA1B2C300 + index)
            output_fields = [field for field, _ in cpp_record(output_name)._fields_]
            emitted = shader.split("= " + output_name + "(", 1)[1].split(");", 1)[0]
            self.assertEqual(re.findall(r"candidate\.(\w+)", emitted), output_fields)
            for component in ("z", "vz"):
                initializer = block(CPP, "push_back(" + source_name)
                self.assertRegex(initializer, rf"\.{component}\s*=.*\[base \+ 2U\]")

    def test_uniform_prefix_matches_native_and_graphics(self):
        native = re.findall(r"std::array<float, 4>\s+(\w+)", block(HEADER, "struct alignas(16) SceneUniformData"))
        for name in ("compact_medium.comp", "compact_far.comp", "compact_medium_subgroup.comp",
                     "compact_far_subgroup.comp", "compact_medium_bda.comp", "cheap_point.vert", "density_point.vert"):
            with self.subTest(shader=name):
                shader = (SHADERS / name).read_text()
                uniforms = re.findall(r"vec4\s+(\w+)\s*;", block(shader, "uniform SceneUniforms"))
                self.assertEqual(uniforms, native[:len(uniforms)])
                self.assertEqual(uniforms[:5], native[:5])

    def test_compute_visibility_and_far_tiles_use_the_displayed_3d_basis(self):
        far = (SHADERS / "compact_far.comp").read_text()
        window_y = re.search(r"vec2\(relative.x,\s*(-?)relative.y\) / span", far)
        self.assertIsNotNone(window_y)
        for name in ("compact_medium.comp", "compact_far.comp", "compact_medium_subgroup.comp", "compact_far_subgroup.comp", "compact_medium_bda.comp"):
            shader = (SHADERS / name).read_text()
            function = block(shader, "vec2 cameraPlanePosition")
            self.assertIn("positionM - uScene.centerRelativeAndMetrics.xyz", function)
            axes = re.findall(r"dot\(relative, uScene\.(\w+)\.xyz\)", function)
            self.assertEqual(len(axes), 2)
            self.assertIn("vec3(candidate.x, candidate.y, candidate.z)", shader)
            self.assertIn("uScene.centerRelativeAndMetrics.w /", shader)
            for width, height in ((1080, 2000), (2200, 900)):
                radius = 7.5e10
                mpp = radius * 2.0 / min(width, height)
                span = (mpp * width * 0.5, mpp * height * 0.5)
                for yaw, pitch in ((0.0, 0.21), (1.2, 0.7), (-2.1, math.pi / 2)):
                    camera = basis(yaw, pitch)
                    expected = (span[0] * 0.4, -span[1] * 0.3)
                    relative = tuple(camera["rightAndSpan"][i] * expected[0] +
                                     camera["upAndSpan"][i] * expected[1] +
                                     camera["forwardAndDepth"][i] * radius * 7.0 for i in range(3))
                    plane = [dot(relative, camera[axis]) for axis in axes]
                    for actual, wanted in zip(plane, expected):
                        self.assertAlmostEqual(actual / radius, wanted / radius, places=12)
                    # Check the actual compute Y convention against native screen projection.
                    y_sign = -1 if window_y.group(1) else 1
                    pixel = (int((plane[0] / span[0] * 0.5 + 0.5) * width),
                             int((y_sign * plane[1] / span[1] * 0.5 + 0.5) * height))
                    native_pixel = (int(width * 0.5 + expected[0] / mpp), int(height * 0.5 - expected[1] / mpp))
                    self.assertLessEqual(max(abs(a - b) for a, b in zip(pixel, native_pixel)), 1)
                    self.assertEqual(tuple(value // 16 for value in pixel), tuple(value // 16 for value in native_pixel))

    def test_far_clears_and_counter_copies_have_required_usage_and_dependencies(self):
        allocation = CPP.split("farOutputVertexCapacity, 1U)) * sizeof(DensityPointVertex)", 1)[1].split("sceneGpuStreams_.tracerFarCompute.label", 1)[0]
        self.assertIn("VK_BUFFER_USAGE_TRANSFER_DST_BIT", allocation)
        tile_allocation = CPP.split('"tracer-far-tile-counters"', 1)[0].rsplit("ensureDeviceLocalComputeBuffer(", 1)[1]
        self.assertIn("VK_BUFFER_USAGE_TRANSFER_SRC_BIT", tile_allocation)
        dependency = CPP.split("std::vector<VkBufferMemoryBarrier> computeToTransferBarriers;", 1)[1].split("std::vector<VkBufferMemoryBarrier> transferToHostBarriers;", 1)[0]
        self.assertIn(".buffer = sceneGpuStreams_.tracerFarCompute.tileCounterBuffer.buffer", dependency)
        self.assertIn("VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT", dependency)
        self.assertIn("VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT", dependency)


if __name__ == "__main__":
    unittest.main()
