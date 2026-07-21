#!/usr/bin/env python3
"""Regression coverage for the fenced Vulkan staging-upload ring contract."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / "render/android-vulkan/src/main/cpp/SolarLabVulkanRenderer.h"
SOURCE = ROOT / "render/android-vulkan/src/main/cpp/SolarLabVulkanRenderer.cpp"


def function_block(source: str, signature: str, next_signature: str) -> str:
    start = source.index(signature)
    end = source.index(next_signature, start)
    return source[start:end]


class VulkanFencedUploadRingTest(unittest.TestCase):
    def test_upload_slots_keep_staging_memory_alive_until_their_fences_signal(self) -> None:
        header = HEADER.read_text(encoding="utf-8")
        source = SOURCE.read_text(encoding="utf-8")

        self.assertIn("struct StagingUploadSlot", header)
        self.assertIn("GpuBuffer stagingBuffer", header)
        self.assertIn("VkCommandBuffer commandBuffer", header)
        self.assertIn("VkFence fence", header)
        self.assertIn("kStagingUploadRingSize = 8U", header)
        self.assertIn("std::array<StagingUploadSlot, kStagingUploadRingSize>", header)

        acquire = function_block(
            source,
            "SolarLabVulkanRenderer::StagingUploadSlot* SolarLabVulkanRenderer::AcquireStagingUploadSlot",
            "bool SolarLabVulkanRenderer::SubmitStagingCopy",
        )
        self.assertIn("vkGetFenceStatus", acquire)
        self.assertIn("vkWaitForFences", acquire)
        self.assertIn("slot.inFlight = false", acquire)

        submit = function_block(
            source,
            "bool SolarLabVulkanRenderer::SubmitStagingCopy",
            "void SolarLabVulkanRenderer::DestroyStagingUploadRing",
        )
        self.assertIn("vkResetFences", submit)
        self.assertIn("vkQueueSubmit(graphicsQueue_, 1, &submitInfo, slot.fence)", submit)
        self.assertIn("slot.inFlight = true", submit)
        self.assertNotIn("vkQueueWaitIdle(", submit)

        upload = function_block(
            source,
            "bool SolarLabVulkanRenderer::TryUploadDeviceLocalWithStaging",
            "bool SolarLabVulkanRenderer::UploadBytesInternal",
        )
        self.assertIn("AcquireStagingUploadSlot", upload)
        self.assertIn("slot->stagingBuffer", upload)
        self.assertNotIn("DestroyGpuBuffer(slot->stagingBuffer)", upload)

    def test_renderer_has_no_queue_wide_staged_upload_wait(self) -> None:
        source = SOURCE.read_text(encoding="utf-8")
        self.assertNotIn("vkQueueWaitIdle(", source)

        cleanup = function_block(
            source,
            "void SolarLabVulkanRenderer::Cleanup",
            "void SolarLabVulkanRenderer::SetError",
        )
        self.assertLess(
            cleanup.index("DestroyStagingUploadRing();"),
            cleanup.index("vkDestroyCommandPool"),
        )


if __name__ == "__main__":
    unittest.main()
