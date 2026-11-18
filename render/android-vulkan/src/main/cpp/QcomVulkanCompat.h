#pragma once

#include <vulkan/vulkan.h>

// Declarations absent from pinned Android NDK 27 headers, copied from Khronos
// Vulkan-Headers vulkan_core.h, version 1.4.368, blob
// e595aee44360593ba06c01487840666fcde1f042. No implementation is vendored.
// Copyright 2015-2026 The Khronos Group Inc.
// SPDX-License-Identifier: Apache-2.0 OR MIT
// Source: https://github.com/KhronosGroup/Vulkan-Headers/blob/main/include/vulkan/vulkan_core.h
// Runtime extension/feature checks and vkGetDeviceProcAddr remain mandatory.

#ifndef VK_QCOM_tile_memory_heap
inline constexpr VkStructureType VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_MEMORY_HEAP_FEATURES_QCOM = static_cast<VkStructureType>(1000547000);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_MEMORY_HEAP_PROPERTIES_QCOM = static_cast<VkStructureType>(1000547001);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_TILE_MEMORY_REQUIREMENTS_QCOM = static_cast<VkStructureType>(1000547002);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_TILE_MEMORY_BIND_INFO_QCOM = static_cast<VkStructureType>(1000547003);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_TILE_MEMORY_SIZE_INFO_QCOM = static_cast<VkStructureType>(1000547004);
inline constexpr VkMemoryHeapFlagBits VK_MEMORY_HEAP_TILE_MEMORY_BIT_QCOM = static_cast<VkMemoryHeapFlagBits>(0x00000008);
inline constexpr VkBufferUsageFlagBits VK_BUFFER_USAGE_TILE_MEMORY_BIT_QCOM = static_cast<VkBufferUsageFlagBits>(0x08000000);
inline constexpr VkImageUsageFlagBits VK_IMAGE_USAGE_TILE_MEMORY_BIT_QCOM = static_cast<VkImageUsageFlagBits>(0x08000000);

#define VK_QCOM_tile_memory_heap 1

#define VK_QCOM_TILE_MEMORY_HEAP_SPEC_VERSION 1
#define VK_QCOM_TILE_MEMORY_HEAP_EXTENSION_NAME "VK_QCOM_tile_memory_heap"

typedef struct VkPhysicalDeviceTileMemoryHeapFeaturesQCOM {
    VkStructureType    sType;
    void*              pNext;
    VkBool32           tileMemoryHeap;
} VkPhysicalDeviceTileMemoryHeapFeaturesQCOM;

typedef struct VkPhysicalDeviceTileMemoryHeapPropertiesQCOM {
    VkStructureType    sType;
    void*              pNext;
    VkBool32           queueSubmitBoundary;
    VkBool32           tileBufferTransfers;
} VkPhysicalDeviceTileMemoryHeapPropertiesQCOM;

typedef struct VkTileMemoryRequirementsQCOM {
    VkStructureType    sType;
    void*              pNext;
    VkDeviceSize       size;
    VkDeviceSize       alignment;
} VkTileMemoryRequirementsQCOM;

typedef struct VkTileMemoryBindInfoQCOM {
    VkStructureType    sType;
    const void*        pNext;
    VkDeviceMemory     memory;
} VkTileMemoryBindInfoQCOM;

typedef struct VkTileMemorySizeInfoQCOM {
    VkStructureType    sType;
    const void*        pNext;
    VkDeviceSize       size;
} VkTileMemorySizeInfoQCOM;


typedef void (VKAPI_PTR *PFN_vkCmdBindTileMemoryQCOM)(VkCommandBuffer commandBuffer, const VkTileMemoryBindInfoQCOM* pTileMemoryBindInfo);
#endif

#ifndef VK_QCOM_tile_shading
inline constexpr VkStructureType VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_SHADING_FEATURES_QCOM = static_cast<VkStructureType>(1000309000);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_SHADING_PROPERTIES_QCOM = static_cast<VkStructureType>(1000309001);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_RENDER_PASS_TILE_SHADING_CREATE_INFO_QCOM = static_cast<VkStructureType>(1000309002);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_PER_TILE_BEGIN_INFO_QCOM = static_cast<VkStructureType>(1000309003);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_PER_TILE_END_INFO_QCOM = static_cast<VkStructureType>(1000309004);
inline constexpr VkStructureType VK_STRUCTURE_TYPE_DISPATCH_TILE_INFO_QCOM = static_cast<VkStructureType>(1000309005);

#define VK_QCOM_tile_shading 1

#define VK_QCOM_TILE_SHADING_SPEC_VERSION 2
#define VK_QCOM_TILE_SHADING_EXTENSION_NAME "VK_QCOM_tile_shading"

typedef enum VkTileShadingRenderPassFlagBitsQCOM {
    VK_TILE_SHADING_RENDER_PASS_ENABLE_BIT_QCOM = 0x00000001,
    VK_TILE_SHADING_RENDER_PASS_PER_TILE_EXECUTION_BIT_QCOM = 0x00000002,
    VK_TILE_SHADING_RENDER_PASS_FLAG_BITS_MAX_ENUM_QCOM = 0x7FFFFFFF
} VkTileShadingRenderPassFlagBitsQCOM;
typedef VkFlags VkTileShadingRenderPassFlagsQCOM;

typedef struct VkPhysicalDeviceTileShadingFeaturesQCOM {
    VkStructureType    sType;
    void*              pNext;
    VkBool32           tileShading;
    VkBool32           tileShadingFragmentStage;
    VkBool32           tileShadingColorAttachments;
    VkBool32           tileShadingDepthAttachments;
    VkBool32           tileShadingStencilAttachments;
    VkBool32           tileShadingInputAttachments;
    VkBool32           tileShadingSampledAttachments;
    VkBool32           tileShadingPerTileDraw;
    VkBool32           tileShadingPerTileDispatch;
    VkBool32           tileShadingDispatchTile;
    VkBool32           tileShadingApron;
    VkBool32           tileShadingAnisotropicApron;
    VkBool32           tileShadingAtomicOps;
    VkBool32           tileShadingImageProcessing;
} VkPhysicalDeviceTileShadingFeaturesQCOM;

typedef struct VkPhysicalDeviceTileShadingPropertiesQCOM {
    VkStructureType    sType;
    void*              pNext;
    uint32_t           maxApronSize;
    VkBool32           preferNonCoherent;
    VkExtent2D         tileGranularity;
    VkExtent2D         maxTileShadingRate;
} VkPhysicalDeviceTileShadingPropertiesQCOM;

typedef struct VkRenderPassTileShadingCreateInfoQCOM {
    VkStructureType                     sType;
    const void*                         pNext;
    VkTileShadingRenderPassFlagsQCOM    flags;
    VkExtent2D                          tileApronSize;
} VkRenderPassTileShadingCreateInfoQCOM;

typedef struct VkPerTileBeginInfoQCOM {
    VkStructureType    sType;
    const void*        pNext;
} VkPerTileBeginInfoQCOM;

typedef struct VkPerTileEndInfoQCOM {
    VkStructureType    sType;
    const void*        pNext;
} VkPerTileEndInfoQCOM;

typedef struct VkDispatchTileInfoQCOM {
    VkStructureType    sType;
    const void*        pNext;
} VkDispatchTileInfoQCOM;


typedef void (VKAPI_PTR *PFN_vkCmdDispatchTileQCOM)(VkCommandBuffer commandBuffer, const VkDispatchTileInfoQCOM* pDispatchTileInfo);
typedef void (VKAPI_PTR *PFN_vkCmdBeginPerTileExecutionQCOM)(VkCommandBuffer commandBuffer, const VkPerTileBeginInfoQCOM* pPerTileBeginInfo);
typedef void (VKAPI_PTR *PFN_vkCmdEndPerTileExecutionQCOM)(VkCommandBuffer commandBuffer, const VkPerTileEndInfoQCOM* pPerTileEndInfo);
#endif

