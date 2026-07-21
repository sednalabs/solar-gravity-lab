#!/usr/bin/env python3
"""Structural regression coverage for canonical Android renderer ownership."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / "clients/android/settings.gradle.kts"
APP_BUILD = ROOT / "clients/android/app/build.gradle.kts"
RENDERER = ROOT / "render/android-vulkan"
LEGACY_RENDERER = ROOT / "feature-lab/src/main/java/com/graciousgazelles/solarlab/feature/lab/render"


class CanonicalAndroidRendererModuleTest(unittest.TestCase):
    def test_forward_android_shell_depends_only_on_the_canonical_renderer_module(self) -> None:
        settings = SETTINGS.read_text(encoding="utf-8")
        app_build = APP_BUILD.read_text(encoding="utf-8")

        self.assertIn('include(":android-vulkan-renderer")', settings)
        self.assertIn(
            'project(":android-vulkan-renderer").projectDir = file("../../render/android-vulkan")',
            settings,
        )
        self.assertNotIn('include(":feature-lab")', settings)
        self.assertIn('implementation(project(":android-vulkan-renderer"))', app_build)
        self.assertNotIn('implementation(project(":feature-lab"))', app_build)

        app_sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted((ROOT / "clients/android/app/src/main").rglob("*.kt"))
        )
        self.assertNotIn("com.graciousgazelles.solarlab.feature.lab", app_sources)

    def test_renderer_sources_have_one_canonical_owner(self) -> None:
        expected_sources = {
            "src/main/cpp/CMakeLists.txt",
            "src/main/cpp/SolarLabStageController.cpp",
            "src/main/cpp/SolarLabStageController.h",
            "src/main/cpp/SolarLabVulkanBridge.cpp",
            "src/main/cpp/SolarLabVulkanRenderer.cpp",
            "src/main/cpp/SolarLabVulkanRenderer.h",
            "src/main/kotlin/com/sednalabs/solarlab/render/vulkan/RenderDeviceCapabilities.kt",
            "src/main/kotlin/com/sednalabs/solarlab/render/vulkan/RenderInteractionListener.kt",
            "src/main/kotlin/com/sednalabs/solarlab/render/vulkan/RenderProcessingMode.kt",
            "src/main/kotlin/com/sednalabs/solarlab/render/vulkan/SolarLabVulkanBridge.kt",
            "src/main/kotlin/com/sednalabs/solarlab/render/vulkan/SolarRenderSurface.kt",
            "src/main/kotlin/com/sednalabs/solarlab/render/vulkan/SolarSystemRenderHostView.kt",
            "src/main/kotlin/com/sednalabs/solarlab/render/vulkan/SolarSystemVulkanSurfaceView.kt",
        }
        actual_sources = {
            path.relative_to(RENDERER).as_posix()
            for path in RENDERER.rglob("*")
            if path.is_file()
        }

        self.assertTrue(expected_sources.issubset(actual_sources))
        self.assertFalse(any(path.is_file() for path in LEGACY_RENDERER.rglob("*") if LEGACY_RENDERER.exists()))
        self.assertFalse(any((ROOT / "feature-lab/src/main/cpp").glob("*")))
        self.assertFalse(any((ROOT / "feature-lab/src/main/shaders").rglob("*")))

    def test_kotlin_package_and_jni_exports_are_canonical(self) -> None:
        kotlin_root = RENDERER / "src/main/kotlin"
        kotlin_sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(kotlin_root.rglob("*.kt"))
        )
        bridge = (RENDERER / "src/main/cpp/SolarLabVulkanBridge.cpp").read_text(encoding="utf-8")
        consumer_rules = (RENDERER / "consumer-rules.pro").read_text(encoding="utf-8")

        self.assertIn("package com.sednalabs.solarlab.render.vulkan", kotlin_sources)
        self.assertNotIn("com.graciousgazelles.solarlab.feature.lab.render", kotlin_sources)
        self.assertIn(
            "Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_nativeCreateRenderer",
            bridge,
        )
        self.assertNotIn("Java_com_graciousgazelles_solarlab_feature_lab_render", bridge)
        self.assertIn("class com.sednalabs.solarlab.render.vulkan.**", consumer_rules)

        kotlin_bridge = (
            kotlin_root / "com/sednalabs/solarlab/render/vulkan/SolarLabVulkanBridge.kt"
        ).read_text(encoding="utf-8")
        declared_methods = set(re.findall(r"private external fun (native[A-Za-z0-9_]+)", kotlin_bridge))
        exported_methods = set(
            re.findall(
                r"Java_com_sednalabs_solarlab_render_vulkan_SolarLabVulkanBridge_(native[A-Za-z0-9_]+)\(",
                bridge,
            )
        )
        self.assertEqual(declared_methods, exported_methods)

    def test_packaged_shader_contract_is_complete(self) -> None:
        shader_root = RENDERER / "src/main/shaders/solarlab"
        actual_shaders = {path.name for path in shader_root.iterdir() if path.is_file()}
        expected_shaders = {
            "billboard.frag",
            "billboard.vert",
            "cheap_point.frag",
            "cheap_point.vert",
            "compact_far.comp",
            "compact_medium.comp",
            "density_point.frag",
            "density_point.vert",
            "trail.frag",
            "trail.vert",
        }

        self.assertEqual(actual_shaders, expected_shaders)

    def test_renderer_module_does_not_depend_on_managed_simulation(self) -> None:
        build = (RENDERER / "build.gradle.kts").read_text(encoding="utf-8")

        self.assertNotIn('project(":feature-lab")', build)
        self.assertNotIn('project(":core-simulation")', build)


if __name__ == "__main__":
    unittest.main()
