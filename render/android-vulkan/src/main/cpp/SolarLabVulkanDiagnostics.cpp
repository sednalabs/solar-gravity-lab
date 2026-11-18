#include "SolarLabVulkanRenderer.h"

#include <android/log.h>

#include <algorithm>
#include <array>
#include <sstream>

namespace {
VkPipelineStageFlags StagesForAccess(VkPipelineStageFlags stages, VkAccessFlags access) {
    VkPipelineStageFlags relevant = 0;
    constexpr VkPipelineStageFlags shaders = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT |
        VK_PIPELINE_STAGE_TESSELLATION_CONTROL_SHADER_BIT | VK_PIPELINE_STAGE_TESSELLATION_EVALUATION_SHADER_BIT |
        VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    if ((access & (VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)) != 0U) relevant |= shaders;
    if ((access & VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT) != 0U) relevant |= VK_PIPELINE_STAGE_VERTEX_INPUT_BIT;
    if ((access & VK_ACCESS_INDIRECT_COMMAND_READ_BIT) != 0U) relevant |= VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
    if ((access & (VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)) != 0U) relevant |= VK_PIPELINE_STAGE_TRANSFER_BIT;
    if ((access & (VK_ACCESS_HOST_READ_BIT | VK_ACCESS_HOST_WRITE_BIT)) != 0U) relevant |= VK_PIPELINE_STAGE_HOST_BIT;
    constexpr VkAccessFlags known = VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
        VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT |
        VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_READ_BIT | VK_ACCESS_HOST_WRITE_BIT;
    return (access & ~known) == 0U && (relevant & stages) != 0U ? (relevant & stages) : stages;
}
}

void SolarLabVulkanRenderer::CmdPipelineBarrier(VkCommandBuffer commandBuffer,
    VkPipelineStageFlags sourceStages, VkPipelineStageFlags destinationStages, VkDependencyFlags flags,
    uint32_t memoryCount, const VkMemoryBarrier* memory, uint32_t bufferCount,
    const VkBufferMemoryBarrier* buffers, uint32_t imageCount, const VkImageMemoryBarrier* images) {
    if (cmdPipelineBarrier2_ == nullptr || imageCount != 0U) {
        vkCmdPipelineBarrier(commandBuffer, sourceStages, destinationStages, flags,
            memoryCount, memory, bufferCount, buffers, imageCount, images);
        return;
    }
    // Per-resource stages avoid delaying vertex reads for an indirect-only
    // dependency. The portable path retains the original equivalent scopes.
    std::vector<VkMemoryBarrier2KHR> memory2(memoryCount);
    for (uint32_t index = 0; index < memoryCount; ++index) {
        memory2[index] = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER_2_KHR,
            .srcStageMask = StagesForAccess(sourceStages, memory[index].srcAccessMask),
            .srcAccessMask = memory[index].srcAccessMask,
            .dstStageMask = StagesForAccess(destinationStages, memory[index].dstAccessMask),
            .dstAccessMask = memory[index].dstAccessMask,
        };
    }
    std::vector<VkBufferMemoryBarrier2KHR> buffers2(bufferCount);
    for (uint32_t index = 0; index < bufferCount; ++index) {
        buffers2[index] = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2_KHR,
            .srcStageMask = StagesForAccess(sourceStages, buffers[index].srcAccessMask),
            .srcAccessMask = buffers[index].srcAccessMask,
            .dstStageMask = StagesForAccess(destinationStages, buffers[index].dstAccessMask),
            .dstAccessMask = buffers[index].dstAccessMask,
            .srcQueueFamilyIndex = buffers[index].srcQueueFamilyIndex,
            .dstQueueFamilyIndex = buffers[index].dstQueueFamilyIndex,
            .buffer = buffers[index].buffer,
            .offset = buffers[index].offset,
            .size = buffers[index].size,
        };
    }
    const VkDependencyInfoKHR dependency{
        .sType = VK_STRUCTURE_TYPE_DEPENDENCY_INFO_KHR,
        .dependencyFlags = flags,
        .memoryBarrierCount = memoryCount,
        .pMemoryBarriers = memory2.data(),
        .bufferMemoryBarrierCount = bufferCount,
        .pBufferMemoryBarriers = buffers2.data(),
    };
    cmdPipelineBarrier2_(commandBuffer, &dependency);
}

VkResult SolarLabVulkanRenderer::SubmitWithSynchronization2(VkCommandBuffer commandBuffer,
    VkFence fence, VkSemaphore waitSemaphore, VkSemaphore signalSemaphore) {
    const VkSemaphoreSubmitInfoKHR wait{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO_KHR,
        .semaphore = waitSemaphore,
        .stageMask = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT_KHR,
    };
    const VkSemaphoreSubmitInfoKHR signal{
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO_KHR,
        .semaphore = signalSemaphore,
        .stageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,
    };
    const VkCommandBufferSubmitInfoKHR command{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO_KHR,
        .commandBuffer = commandBuffer,
        .deviceMask = 1U,
    };
    const VkSubmitInfo2KHR submit{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2_KHR,
        .waitSemaphoreInfoCount = waitSemaphore != VK_NULL_HANDLE ? 1U : 0U,
        .pWaitSemaphoreInfos = &wait,
        .commandBufferInfoCount = 1,
        .pCommandBufferInfos = &command,
        .signalSemaphoreInfoCount = signalSemaphore != VK_NULL_HANDLE ? 1U : 0U,
        .pSignalSemaphoreInfos = &signal,
    };
    const VkResult result = queueSubmit2_(graphicsQueue_, 1, &submit, fence);
    if (result == VK_SUCCESS) ++synchronization2Submissions_;
    return result;
}

void SolarLabVulkanRenderer::InitialiseGpuProfiling() {
    if (capabilities_.timestampValidBits > 0U && capabilities_.timestampPeriod > 0.0f &&
        capabilities_.timestampComputeAndGraphics) {
        const VkQueryPoolCreateInfo info{
            .sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
            .queryType = VK_QUERY_TYPE_TIMESTAMP,
            .queryCount = 3,
        };
        if (vkCreateQueryPool(device_, &info, nullptr, &timestampQueryPool_) != VK_SUCCESS) timestampQueryPool_ = VK_NULL_HANDLE;
    }
    if (optionalFeatures_.calibratedTimestamps) {
        getCalibratedTimestamps_ = reinterpret_cast<PFN_vkGetCalibratedTimestampsEXT>(vkGetDeviceProcAddr(device_,
            optionalFeatures_.calibratedTimestampsKhr ? "vkGetCalibratedTimestampsKHR" : "vkGetCalibratedTimestampsEXT"));
    }
    if (optionalFeatures_.executable.pipelineExecutableInfo) {
        getPipelineExecutableProperties_ = reinterpret_cast<PFN_vkGetPipelineExecutablePropertiesKHR>(vkGetDeviceProcAddr(device_, "vkGetPipelineExecutablePropertiesKHR"));
        getPipelineExecutableStatistics_ = reinterpret_cast<PFN_vkGetPipelineExecutableStatisticsKHR>(vkGetDeviceProcAddr(device_, "vkGetPipelineExecutableStatisticsKHR"));
    }
}

void SolarLabVulkanRenderer::RefreshGpuTimingsLocked() {
    // Called only after the frame fence, so these count retired GPU submissions,
    // independent of timestamp-query availability or driver compiler telemetry.
    if ((pendingAccelerationPaths_ & 1U) != 0U) ++subgroupCompletedFrames_;
    if ((pendingAccelerationPaths_ & 2U) != 0U) ++bufferAddressCompletedFrames_;
    if ((pendingAccelerationPaths_ & 4U) != 0U) ++tileMemoryCompletedFrames_;
    pendingAccelerationPaths_ = 0;
    if (!gpuTimingPending_ || timestampQueryPool_ == VK_NULL_HANDLE) return;
    gpuTimingPending_ = false;
    struct QueryValue { uint64_t ticks; uint64_t available; };
    std::array<QueryValue, 3> queries{};
    // The frame fence already retired this submission. Never add a query WAIT
    // to the CPU frame path; a driver returning unavailable simply loses a sample.
    const auto result = vkGetQueryPoolResults(device_, timestampQueryPool_, 0, 3,
        sizeof(queries), queries.data(), sizeof(QueryValue),
        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
    if (result != VK_SUCCESS || !queries[0].available || !queries[1].available || !queries[2].available) return;
    gpuFrameNs_ = static_cast<double>(solarlab::rendering::TimestampDelta(queries[0].ticks, queries[2].ticks,
        capabilities_.timestampValidBits)) * capabilities_.timestampPeriod;
    gpuPreGraphicsNs_ = static_cast<double>(solarlab::rendering::TimestampDelta(queries[0].ticks, queries[1].ticks,
        capabilities_.timestampValidBits)) * capabilities_.timestampPeriod;
    ++gpuTimingSamples_;
    if (getCalibratedTimestamps_ != nullptr && (gpuTimingSamples_ == 1U || gpuTimingSamples_ % 120U == 0U)) {
        const std::array<VkCalibratedTimestampInfoEXT, 2> info = {{
            {.sType = VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT, .timeDomain = VK_TIME_DOMAIN_DEVICE_EXT},
            {.sType = VK_STRUCTURE_TYPE_CALIBRATED_TIMESTAMP_INFO_EXT, .timeDomain = VK_TIME_DOMAIN_CLOCK_MONOTONIC_EXT},
        }};
        std::array<uint64_t, 2> timestamps{};
        uint64_t deviation = 0;
        if (getCalibratedTimestamps_(device_, static_cast<uint32_t>(info.size()), info.data(), timestamps.data(), &deviation) == VK_SUCCESS) {
            calibratedDeviceTicks_ = timestamps[0];
            calibratedHostNs_ = timestamps[1];
            calibratedMaxDeviationNs_ = deviation;
        }
    }
}

void SolarLabVulkanRenderer::CapturePipelineStatistics(VkPipeline pipeline, const char* label) {
    if (getPipelineExecutableProperties_ == nullptr || getPipelineExecutableStatistics_ == nullptr) return;
    const VkPipelineInfoKHR pipelineInfo{.sType = VK_STRUCTURE_TYPE_PIPELINE_INFO_KHR, .pipeline = pipeline};
    uint32_t count = 0;
    if (getPipelineExecutableProperties_(device_, &pipelineInfo, &count, nullptr) != VK_SUCCESS) return;
    count = std::min(count, 64U);
    std::vector<VkPipelineExecutablePropertiesKHR> executables(count, {.sType = VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_PROPERTIES_KHR});
    const auto result = getPipelineExecutableProperties_(device_, &pipelineInfo, &count, executables.data());
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) return;
    for (uint32_t index = 0; index < count; ++index) {
        const VkPipelineExecutableInfoKHR info{.sType = VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_INFO_KHR, .pipeline = pipeline, .executableIndex = index};
        uint32_t statisticsCount = 0;
        if (getPipelineExecutableStatistics_(device_, &info, &statisticsCount, nullptr) != VK_SUCCESS) continue;
        statisticsCount = std::min(statisticsCount, 128U);
        std::vector<VkPipelineExecutableStatisticKHR> statistics(statisticsCount, {.sType = VK_STRUCTURE_TYPE_PIPELINE_EXECUTABLE_STATISTIC_KHR});
        const auto statisticsResult = getPipelineExecutableStatistics_(device_, &info, &statisticsCount, statistics.data());
        if (statisticsResult != VK_SUCCESS && statisticsResult != VK_INCOMPLETE) continue;
        for (uint32_t statIndex = 0; statIndex < statisticsCount; ++statIndex) {
            const auto& stat = statistics[statIndex];
            std::ostringstream out;
            out << "pipeline-stat pipeline=" << label << " executable=" << index
                << " name=" << executables[index].name << " subgroup=" << executables[index].subgroupSize
                << " statistic=" << stat.name << " value=";
            switch (stat.format) {
                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_BOOL32_KHR: out << (stat.value.b32 != VK_FALSE); break;
                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR: out << stat.value.i64; break;
                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR: out << stat.value.u64; break;
                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_FLOAT64_KHR: out << stat.value.f64; break;
                default: continue;
            }
            __android_log_print(ANDROID_LOG_INFO, "SolarLabVulkan", "%s", out.str().c_str());
            ++pipelineStatisticCount_;
        }
    }
}
