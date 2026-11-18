#include "BenchmarkUtil.h"
#include "QcomTileMemory.h"
#include "SolarLabVulkanCapabilities.h"
#include "bench_passive_fp32.h"
#include "bench_passive_fp64.h"
#include "bench_visual_fp32.h"
#include "bench_visual_fp16.h"
#include "bench_compact_atomic.h"
#include "bench_compact_subgroup.h"
#include "bench_compact_bda.h"
#include "bench_dispatch_args.h"
#ifdef SOLARLAB_EMBED_QCOM_SHADERS
#include "../../../probes/QcomOffscreenProbe.h"
#endif
#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <stdexcept>

namespace solarlab::benchmark {
namespace {
constexpr uint32_t kBodies=16384;
constexpr uint64_t kFenceTimeoutNs=5'000'000'000ULL;
void Check(VkResult result,const char* operation) {
    if(result!=VK_SUCCESS) throw std::runtime_error(std::string(operation)+":vk-result="+std::to_string(result));
}
struct Instance {
    VkInstance instance=VK_NULL_HANDLE;
    VkPhysicalDevice physical=VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties{};
    VkPhysicalDeviceFeatures features{};
    VkPhysicalDeviceMemoryProperties memory{};
    std::vector<VkExtensionProperties> extensions;
    std::vector<VkQueueFamilyProperties> queues;
    uint32_t queueFamily=UINT32_MAX,apiVersion=VK_API_VERSION_1_1;
    bool float16=false;
    solarlab::rendering::VulkanCapabilities advanced;
    solarlab::rendering::QcomTileMemory tile;
    std::string skip;
    Instance()=default;
    Instance(const Instance&)=delete;
    Instance& operator=(const Instance&)=delete;
    ~Instance(){if(instance!=VK_NULL_HANDLE)vkDestroyInstance(instance,nullptr);}
    bool Has(const char* name)const {
        return std::any_of(extensions.begin(),extensions.end(),[&](const auto& ext){return std::strcmp(ext.extensionName,name)==0;});
    }
    bool Initialize(){
        uint32_t loader=VK_API_VERSION_1_0;
        auto enumerate=reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vkGetInstanceProcAddr(VK_NULL_HANDLE,"vkEnumerateInstanceVersion"));
        if(enumerate)Check(enumerate(&loader),"enumerate-version");
        if(loader<VK_API_VERSION_1_1){skip="vulkan-1.1-unavailable";return false;}
        apiVersion=std::min(loader,VK_API_VERSION_1_3);
        VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        application.pApplicationName="SolarLab isolated hardware research";application.apiVersion=apiVersion;
        VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};info.pApplicationInfo=&application;
        auto result=vkCreateInstance(&info,nullptr,&instance);
        if(result==VK_ERROR_INCOMPATIBLE_DRIVER){skip="vulkan-driver-unavailable";return false;}
        Check(result,"create-instance");
        uint32_t count=0;
        Check(vkEnumeratePhysicalDevices(instance,&count,nullptr),"enumerate-physical-devices");
        std::vector<VkPhysicalDevice> devices(count);
        Check(vkEnumeratePhysicalDevices(instance,&count,devices.data()),"read-physical-devices");
        for(auto candidate:devices){
            VkPhysicalDeviceProperties props{};vkGetPhysicalDeviceProperties(candidate,&props);
            if(props.apiVersion<VK_API_VERSION_1_1)continue;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate,&count,nullptr);
            std::vector<VkQueueFamilyProperties> available(count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate,&count,available.data());
            for(uint32_t i=0;i<count;++i){
                if(available[i].queueCount==0||!(available[i].queueFlags&VK_QUEUE_COMPUTE_BIT))continue;
                physical=candidate;properties=props;queues=available;queueFamily=i;break;
            }
            if(physical!=VK_NULL_HANDLE)break;
        }
        if(physical==VK_NULL_HANDLE){skip="no-compute-queue";return false;}
        Check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&count,nullptr),"enumerate-device-extensions");
        extensions.resize(count);
        Check(vkEnumerateDeviceExtensionProperties(physical,nullptr,&count,extensions.data()),"read-device-extensions");
        vkGetPhysicalDeviceFeatures(physical,&features);vkGetPhysicalDeviceMemoryProperties(physical,&memory);
        advanced.Probe(instance,physical,apiVersion,queueFamily);tile.Probe(physical,extensions);
        if(std::min(apiVersion,properties.apiVersion)>=VK_API_VERSION_1_2||Has(VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME)){
            VkPhysicalDeviceShaderFloat16Int8Features half{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
            VkPhysicalDeviceFeatures2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,&half,{}};
            vkGetPhysicalDeviceFeatures2(physical,&query);float16=half.shaderFloat16==VK_TRUE;
        }
        return true;
    }
};
struct Buffer{VkBuffer buffer=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;void* mapped=nullptr;VkDeviceSize bytes=0;};
class Device{
public:
    Instance& host;
    VkDevice device=VK_NULL_HANDLE;
    VkQueue queue=VK_NULL_HANDLE;
    VkCommandPool commandPool=VK_NULL_HANDLE;
    VkCommandBuffer command=VK_NULL_HANDLE;
    VkFence fence=VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptors=VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool=VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet=VK_NULL_HANDLE;
    VkPipelineLayout layout=VK_NULL_HANDLE;
    VkPipeline pipeline=VK_NULL_HANDLE,argsPipeline=VK_NULL_HANDLE;
    VkPipelineCache cache=VK_NULL_HANDLE;
    VkShaderModule shader=VK_NULL_HANDLE,argsShader=VK_NULL_HANDLE;
    VkQueryPool queries=VK_NULL_HANDLE;
    VkSemaphore timeline=VK_NULL_HANDLE;
    std::vector<Buffer> buffers;
    bool addressMode=false,timelineMode=false,sync2Mode=false,tileMode=false,gpuTimestampValid=false;
    uint64_t gpuNs=0;
    PFN_vkGetBufferDeviceAddressKHR getAddress=nullptr;
    PFN_vkWaitSemaphoresKHR waitSemaphores=nullptr;
    PFN_vkQueueSubmit2KHR queueSubmit2=nullptr;
    PFN_vkCmdPipelineBarrier2KHR barrier2=nullptr;
    explicit Device(Instance& selected):host(selected){buffers.reserve(5);}
    ~Device(){
        if(device==VK_NULL_HANDLE)return;
        vkDeviceWaitIdle(device);
        for(auto& b:buffers){if(b.mapped)vkUnmapMemory(device,b.memory);if(b.buffer!=VK_NULL_HANDLE)vkDestroyBuffer(device,b.buffer,nullptr);if(b.memory!=VK_NULL_HANDLE)vkFreeMemory(device,b.memory,nullptr);}
        if(timeline!=VK_NULL_HANDLE)vkDestroySemaphore(device,timeline,nullptr);
        if(queries!=VK_NULL_HANDLE)vkDestroyQueryPool(device,queries,nullptr);
        if(pipeline!=VK_NULL_HANDLE)vkDestroyPipeline(device,pipeline,nullptr);
        if(argsPipeline!=VK_NULL_HANDLE)vkDestroyPipeline(device,argsPipeline,nullptr);
        if(shader!=VK_NULL_HANDLE)vkDestroyShaderModule(device,shader,nullptr);
        if(argsShader!=VK_NULL_HANDLE)vkDestroyShaderModule(device,argsShader,nullptr);
        if(cache!=VK_NULL_HANDLE)vkDestroyPipelineCache(device,cache,nullptr);
        if(layout!=VK_NULL_HANDLE)vkDestroyPipelineLayout(device,layout,nullptr);
        if(descriptorPool!=VK_NULL_HANDLE)vkDestroyDescriptorPool(device,descriptorPool,nullptr);
        if(descriptors!=VK_NULL_HANDLE)vkDestroyDescriptorSetLayout(device,descriptors,nullptr);
        if(fence!=VK_NULL_HANDLE)vkDestroyFence(device,fence,nullptr);
        if(commandPool!=VK_NULL_HANDLE)vkDestroyCommandPool(device,commandPool,nullptr);
        host.tile.ResetDevice();vkDestroyDevice(device,nullptr);
    }
    void Initialize(const std::string& id);
    size_t MakeBuffer(VkDeviceSize bytes,VkBufferUsageFlags usage,bool tile=false);
    void Pipeline(const unsigned char* code,size_t bytes);
    VkShaderModule MakeShader(const unsigned char* code,size_t bytes);
    VkPipeline MakePipeline(VkShaderModule module,VkPipelineCache selectedCache);
    void Barrier(VkPipelineStageFlags source,VkAccessFlags sourceAccess,VkPipelineStageFlags destination,VkAccessFlags destinationAccess);
    void Begin();
    uint64_t Submit();
};
void Device::Initialize(const std::string& id){
    addressMode=id=="gpu.compact-bda";timelineMode=id=="gpu.timeline-submit";sync2Mode=id=="gpu.sync2-submit";tileMode=id=="gpu.compact-tile-memory";
    const uint32_t effectiveApi=std::min(host.apiVersion,host.properties.apiVersion);
    void* chain=nullptr;std::vector<const char*> extensions;VkPhysicalDeviceFeatures base{};
    base.shaderFloat64=id=="gpu.passive-fp64"?VK_TRUE:VK_FALSE;
    VkPhysicalDeviceShaderFloat16Int8Features half{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
    VkPhysicalDeviceBufferDeviceAddressFeatures address{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES};
    VkPhysicalDeviceTimelineSemaphoreFeatures time{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
    VkPhysicalDeviceSynchronization2FeaturesKHR sync{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES_KHR};
    if(id=="gpu.visual-fp16"){
        half.shaderFloat16=VK_TRUE;half.pNext=chain;chain=&half;
        if(effectiveApi<VK_API_VERSION_1_2)extensions.push_back(VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);
    }
    if(addressMode){
        address.bufferDeviceAddress=VK_TRUE;address.pNext=chain;chain=&address;
        if(effectiveApi<VK_API_VERSION_1_2)extensions.push_back(VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
    }
    if(timelineMode){
        time.timelineSemaphore=VK_TRUE;time.pNext=chain;chain=&time;
        if(effectiveApi<VK_API_VERSION_1_2)extensions.push_back(VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);
    }
    if(sync2Mode){
        sync.synchronization2=VK_TRUE;sync.pNext=chain;chain=&sync;
        if(effectiveApi<VK_API_VERSION_1_3)extensions.push_back(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
    }
    if(tileMode&&!host.tile.AppendDeviceFeature(extensions,chain))throw std::runtime_error("tile-feature-not-enabled");
    float priority=1;VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex=host.queueFamily;queueInfo.queueCount=1;queueInfo.pQueuePriorities=&priority;
    VkDeviceCreateInfo create{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    create.pNext=chain;create.queueCreateInfoCount=1;create.pQueueCreateInfos=&queueInfo;
    create.enabledExtensionCount=static_cast<uint32_t>(extensions.size());create.ppEnabledExtensionNames=extensions.data();create.pEnabledFeatures=&base;
    Check(vkCreateDevice(host.physical,&create,nullptr,&device),"create-isolated-benchmark-device");vkGetDeviceQueue(device,host.queueFamily,0,&queue);
    if(addressMode){
        getAddress=reinterpret_cast<PFN_vkGetBufferDeviceAddressKHR>(vkGetDeviceProcAddr(device,effectiveApi>=VK_API_VERSION_1_2?"vkGetBufferDeviceAddress":"vkGetBufferDeviceAddressKHR"));
        if(!getAddress)throw std::runtime_error("bda-entrypoint-absent");
    }
    if(timelineMode){
        waitSemaphores=reinterpret_cast<PFN_vkWaitSemaphoresKHR>(vkGetDeviceProcAddr(device,effectiveApi>=VK_API_VERSION_1_2?"vkWaitSemaphores":"vkWaitSemaphoresKHR"));
        if(!waitSemaphores)throw std::runtime_error("timeline-entrypoint-absent");
        VkSemaphoreTypeCreateInfo type{VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO};type.semaphoreType=VK_SEMAPHORE_TYPE_TIMELINE;
        VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};info.pNext=&type;
        Check(vkCreateSemaphore(device,&info,nullptr,&timeline),"create-timeline");
    }
    if(sync2Mode){
        queueSubmit2=reinterpret_cast<PFN_vkQueueSubmit2KHR>(vkGetDeviceProcAddr(device,effectiveApi>=VK_API_VERSION_1_3?"vkQueueSubmit2":"vkQueueSubmit2KHR"));
        barrier2=reinterpret_cast<PFN_vkCmdPipelineBarrier2KHR>(vkGetDeviceProcAddr(device,effectiveApi>=VK_API_VERSION_1_3?"vkCmdPipelineBarrier2":"vkCmdPipelineBarrier2KHR"));
        if(!queueSubmit2||!barrier2)throw std::runtime_error("sync2-entrypoints-absent");
    }
    if(tileMode&&!host.tile.LoadDevice(device))throw std::runtime_error("tile-entrypoint-absent");
    VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pool.queueFamilyIndex=host.queueFamily;
    Check(vkCreateCommandPool(device,&pool,nullptr,&commandPool),"create-command-pool");
    VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocation.commandPool=commandPool;allocation.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;allocation.commandBufferCount=1;
    Check(vkAllocateCommandBuffers(device,&allocation,&command),"allocate-command");
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};Check(vkCreateFence(device,&fenceInfo,nullptr,&fence),"create-fence");
    if(host.advanced.timestampValidBits!=0&&host.properties.limits.timestampPeriod>0){
        VkQueryPoolCreateInfo query{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};query.queryType=VK_QUERY_TYPE_TIMESTAMP;query.queryCount=2;
        Check(vkCreateQueryPool(device,&query,nullptr,&queries),"create-timestamps");
    }
}
size_t Device::MakeBuffer(VkDeviceSize bytes,VkBufferUsageFlags usage,bool tile){
    const size_t index=buffers.size();buffers.emplace_back();auto& b=buffers.back();b.bytes=bytes;
    if(tile){
        solarlab::rendering::QcomTileBuffer allocation;
        if(!host.tile.TryCreateCounterBuffer(bytes,allocation))throw std::runtime_error("tile-allocation-failed:"+host.tile.Summary());
        b.buffer=allocation.buffer;b.memory=allocation.memory;return index;
    }
    if(addressMode)usage|=VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};info.size=bytes;info.usage=usage;info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    Check(vkCreateBuffer(device,&info,nullptr,&b.buffer),"create-buffer");
    VkMemoryRequirements requirements{};vkGetBufferMemoryRequirements(device,b.buffer,&requirements);
    uint32_t type=UINT32_MAX;
    for(uint32_t i=0;i<host.memory.memoryTypeCount;++i){
        const auto& t=host.memory.memoryTypes[i];
        constexpr VkMemoryPropertyFlags wanted=VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        if((requirements.memoryTypeBits&(1U<<i))&&(t.propertyFlags&wanted)==wanted&&
            !(host.memory.memoryHeaps[t.heapIndex].flags&VK_MEMORY_HEAP_TILE_MEMORY_BIT_QCOM)){type=i;break;}
    }
    if(type==UINT32_MAX)throw std::runtime_error("host-visible-coherent-memory-unavailable");
    VkMemoryAllocateFlagsInfo flags{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO};flags.flags=VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT;
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};alloc.allocationSize=requirements.size;alloc.memoryTypeIndex=type;alloc.pNext=addressMode?&flags:nullptr;
    Check(vkAllocateMemory(device,&alloc,nullptr,&b.memory),"allocate-memory");Check(vkBindBufferMemory(device,b.buffer,b.memory,0),"bind-memory");
    Check(vkMapMemory(device,b.memory,0,bytes,0,&b.mapped),"map-memory");return index;
}
void Device::Pipeline(const unsigned char* code,size_t bytes){
    std::array<VkDescriptorSetLayoutBinding,3> bindings{};
    for(uint32_t i=0;i<3;++i)bindings[i]={i,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr};
    VkDescriptorSetLayoutCreateInfo descriptorInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};descriptorInfo.bindingCount=3;descriptorInfo.pBindings=bindings.data();
    Check(vkCreateDescriptorSetLayout(device,&descriptorInfo,nullptr,&descriptors),"create-descriptor-layout");
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,3};VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool.maxSets=1;pool.poolSizeCount=1;pool.pPoolSizes=&size;Check(vkCreateDescriptorPool(device,&pool,nullptr,&descriptorPool),"create-descriptor-pool");
    VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};allocate.descriptorPool=descriptorPool;allocate.descriptorSetCount=1;allocate.pSetLayouts=&descriptors;
    Check(vkAllocateDescriptorSets(device,&allocate,&descriptorSet),"allocate-descriptor-set");
    std::array<VkDescriptorBufferInfo,3> bufferInfos{};std::array<VkWriteDescriptorSet,3>writes{};
    for(uint32_t i=0;i<3;++i){
        bufferInfos[i]={buffers[i].buffer,0,buffers[i].bytes};writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};writes[i].dstSet=descriptorSet;writes[i].dstBinding=i;
        writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&bufferInfos[i];
    }
    vkUpdateDescriptorSets(device,3,writes.data(),0,nullptr);
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,32};VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount=1;layoutInfo.pSetLayouts=&descriptors;layoutInfo.pushConstantRangeCount=1;layoutInfo.pPushConstantRanges=&push;
    Check(vkCreatePipelineLayout(device,&layoutInfo,nullptr,&layout),"create-pipeline-layout");shader=MakeShader(code,bytes);pipeline=MakePipeline(shader,VK_NULL_HANDLE);
}
VkShaderModule Device::MakeShader(const unsigned char* code,size_t bytes){
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};info.codeSize=bytes;info.pCode=reinterpret_cast<const uint32_t*>(code);
    VkShaderModule module=VK_NULL_HANDLE;Check(vkCreateShaderModule(device,&info,nullptr,&module),"create-shader-module");return module;
}
VkPipeline Device::MakePipeline(VkShaderModule module,VkPipelineCache selectedCache){
    VkComputePipelineCreateInfo info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};info.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    info.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;info.stage.module=module;info.stage.pName="main";info.layout=layout;
    VkPipeline result=VK_NULL_HANDLE;Check(vkCreateComputePipelines(device,selectedCache,1,&info,nullptr,&result),"create-compute-pipeline");return result;
}
void Device::Barrier(VkPipelineStageFlags source,VkAccessFlags sourceAccess,VkPipelineStageFlags destination,VkAccessFlags destinationAccess){
    if(sync2Mode){
        VkMemoryBarrier2KHR memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2_KHR};memory.srcStageMask=source;memory.srcAccessMask=sourceAccess;memory.dstStageMask=destination;memory.dstAccessMask=destinationAccess;
        VkDependencyInfoKHR dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO_KHR};dependency.memoryBarrierCount=1;dependency.pMemoryBarriers=&memory;barrier2(command,&dependency);
    }else{
        VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};memory.srcAccessMask=sourceAccess;memory.dstAccessMask=destinationAccess;
        vkCmdPipelineBarrier(command,source,destination,0,1,&memory,0,nullptr,0,nullptr);
    }
}
void Device::Begin(){
    VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};info.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;Check(vkBeginCommandBuffer(command,&info),"begin-command");
    if(queries!=VK_NULL_HANDLE){vkCmdResetQueryPool(command,queries,0,2);vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0);}
}
uint64_t Device::Submit(){
    if(queries!=VK_NULL_HANDLE)vkCmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);
    Check(vkEndCommandBuffer(command),"end-command");const auto start=Clock::now();
    if(sync2Mode){
        VkCommandBufferSubmitInfoKHR commandInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO_KHR};commandInfo.commandBuffer=command;
        VkSubmitInfo2KHR submit{VK_STRUCTURE_TYPE_SUBMIT_INFO_2_KHR};submit.commandBufferInfoCount=1;submit.pCommandBufferInfos=&commandInfo;Check(queueSubmit2(queue,1,&submit,fence),"queue-submit2");
    }else{
        const uint64_t signal=1;VkTimelineSemaphoreSubmitInfo time{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};time.signalSemaphoreValueCount=1;time.pSignalSemaphoreValues=&signal;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&command;
        if(timelineMode){submit.pNext=&time;submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&timeline;}
        Check(vkQueueSubmit(queue,1,&submit,timelineMode?VK_NULL_HANDLE:fence),"queue-submit");
    }
    if(timelineMode){
        const uint64_t value=1;VkSemaphoreWaitInfo wait{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};wait.semaphoreCount=1;wait.pSemaphores=&timeline;wait.pValues=&value;
        Check(waitSemaphores(device,&wait,kFenceTimeoutNs),"timeline-wait");
    }else Check(vkWaitForFences(device,1,&fence,VK_TRUE,kFenceTimeoutNs),"fence-wait");
    const uint64_t elapsed=Nanos(start);
    if(queries!=VK_NULL_HANDLE){
        std::array<uint64_t,4>values{};
        const auto result=vkGetQueryPoolResults(device,queries,0,2,sizeof(values),values.data(),2*sizeof(uint64_t),VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        if(result==VK_SUCCESS&&values[1]!=0&&values[3]!=0){
            const auto ticks=solarlab::rendering::TimestampDelta(values[0],values[2],host.advanced.timestampValidBits);
            gpuNs=static_cast<uint64_t>(static_cast<double>(ticks)*host.properties.limits.timestampPeriod);gpuTimestampValid=true;
        }
    }
    return elapsed;
}
std::string Unsupported(const Instance& instance,const std::string& id){
    if(id=="gpu.passive-fp64"&&!instance.features.shaderFloat64)return "shaderFloat64-feature-absent";
    if(id=="gpu.visual-fp16"&&!instance.float16)return "shaderFloat16-feature-absent";
    if(id=="gpu.compact-subgroup"&&!instance.advanced.SupportsSubgroupCompaction())return "compute-BASIC-and-BALLOT-subgroup-operations-absent";
    if(id=="gpu.compact-bda"&&!instance.advanced.bufferDeviceAddress)return "bufferDeviceAddress-feature-absent";
    if(id=="gpu.compact-tile-memory"&&!instance.tile.Eligible())return "tile-memory-counter-path-unavailable:"+instance.tile.Summary();
    if(id=="gpu.timeline-submit"&&!instance.advanced.timelineSemaphore)return "timelineSemaphore-feature-absent";
    if(id=="gpu.sync2-submit"&&!instance.advanced.synchronization2)return "synchronization2-feature-absent";
    return "";
}
Sample ComputeCase(Instance& instance,const std::string& id,int iterations){
    Sample result;result.iterations=iterations;const auto setup=Clock::now();
    Device gpu(instance);gpu.Initialize(id);result.enabled=true;
    const bool fp64=id=="gpu.passive-fp64";
    const bool visual=id=="gpu.visual-fp16"||id=="gpu.visual-fp32";
    const bool compact=id.rfind("gpu.compact-",0)==0;
    const bool indirect=id=="gpu.dispatch-indirect";
    const bool transfer=id=="gpu.transfer-persistent"||id=="gpu.transfer-remap";
    constexpr VkBufferUsageFlags general=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    gpu.MakeBuffer(kBodies*4U*sizeof(float),general);
    gpu.MakeBuffer(kBodies*4U*(fp64?sizeof(double):sizeof(float)),general);
    gpu.MakeBuffer(16,general|VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,gpu.tileMode);
    if(gpu.tileMode)gpu.MakeBuffer(16,general);
    auto* source=static_cast<float*>(gpu.buffers[0].mapped);
    for(uint32_t i=0;i<kBodies;++i){
        source[i*4]=static_cast<float>(i%251)/128.0f-1.0f;
        source[i*4+1]=static_cast<float>((i*17)%253)/128.0f-1.0f;
        source[i*4+2]=static_cast<float>((i*31)%257)/128.0f-1.0f;source[i*4+3]=0;
    }
    std::memset(gpu.buffers[1].mapped,0,static_cast<size_t>(gpu.buffers[1].bytes));
    if(!gpu.tileMode)std::memset(gpu.buffers[2].mapped,0,16);
    const unsigned char* shader=bench_passive_fp32;size_t shaderBytes=sizeof(bench_passive_fp32);
#define SELECT_SHADER(name) do{shader=name;shaderBytes=sizeof(name);}while(false)
    if(fp64)SELECT_SHADER(bench_passive_fp64);
    if(id=="gpu.visual-fp32")SELECT_SHADER(bench_visual_fp32);
    if(id=="gpu.visual-fp16")SELECT_SHADER(bench_visual_fp16);
    if(compact)SELECT_SHADER(bench_compact_atomic);
    if(id=="gpu.compact-subgroup")SELECT_SHADER(bench_compact_subgroup);
    if(id=="gpu.compact-bda")SELECT_SHADER(bench_compact_bda);
#undef SELECT_SHADER
    if(!transfer)gpu.Pipeline(shader,shaderBytes);
    if(id=="gpu.pipeline-no-cache"||id=="gpu.pipeline-cache"){
        result.iterations=std::min(iterations,16);size_t cacheBytes=0;
        if(id=="gpu.pipeline-cache"){
            VkPipelineCacheCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};Check(vkCreatePipelineCache(gpu.device,&info,nullptr,&gpu.cache),"create-pipeline-cache");
            auto warm=gpu.MakePipeline(gpu.shader,gpu.cache);vkDestroyPipeline(gpu.device,warm,nullptr);
            Check(vkGetPipelineCacheData(gpu.device,gpu.cache,&cacheBytes,nullptr),"pipeline-cache-size");
            if(cacheBytes>16U*1024U*1024U)throw std::runtime_error("pipeline-cache-exceeds-bounded-export");
            std::vector<uint8_t>data(cacheBytes);Check(vkGetPipelineCacheData(gpu.device,gpu.cache,&cacheBytes,data.data()),"serialize-pipeline-cache");
            vkDestroyPipelineCache(gpu.device,gpu.cache,nullptr);gpu.cache=VK_NULL_HANDLE;info.initialDataSize=cacheBytes;info.pInitialData=data.data();
            Check(vkCreatePipelineCache(gpu.device,&info,nullptr,&gpu.cache),"restore-pipeline-cache");
        }
        const auto start=Clock::now();
        for(int i=0;i<result.iterations;++i){auto created=gpu.MakePipeline(gpu.shader,gpu.cache);vkDestroyPipeline(gpu.device,created,nullptr);}
        result.elapsed=Nanos(start);result.executed=true;result.scope="pipeline-create-destroy-only-driver-internal-cache-not-cleared";
        result.reason="pipelines-created-cache-serialisation-round-trip-qualified";
        result.extra=",\"pipeline_cache_bytes\":"+std::to_string(cacheBytes)+",\"device\":"+Quote(instance.properties.deviceName);
        return result;
    }
    if(indirect){gpu.argsShader=gpu.MakeShader(bench_dispatch_args,sizeof(bench_dispatch_args));gpu.argsPipeline=gpu.MakePipeline(gpu.argsShader,VK_NULL_HANDLE);}
    result.extra=",\"setup_ns\":"+std::to_string(Nanos(setup));
    uint64_t mappingNs=0;
    if(transfer){
        const auto mappingStart=Clock::now();
        for(int i=0;i<iterations;++i){
            if(id=="gpu.transfer-remap"){
                vkUnmapMemory(gpu.device,gpu.buffers[0].memory);gpu.buffers[0].mapped=nullptr;
                Check(vkMapMemory(gpu.device,gpu.buffers[0].memory,0,gpu.buffers[0].bytes,0,&gpu.buffers[0].mapped),"remap-upload");
            }
            static_cast<float*>(gpu.buffers[0].mapped)[3]=static_cast<float>(i);std::atomic_signal_fence(std::memory_order_seq_cst);
        }
        mappingNs=Nanos(mappingStart);source=static_cast<float*>(gpu.buffers[0].mapped);
    }
    gpu.Begin();
    if(gpu.tileMode&&!instance.tile.BindForCommands(gpu.command,gpu.buffers[2].memory))throw std::runtime_error("tile-bind-failed");
    gpu.Barrier(VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_WRITE_BIT,transfer?VK_PIPELINE_STAGE_TRANSFER_BIT:VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,transfer?VK_ACCESS_TRANSFER_READ_BIT:VK_ACCESS_SHADER_READ_BIT);
    if(!transfer){
        vkCmdBindDescriptorSets(gpu.command,VK_PIPELINE_BIND_POINT_COMPUTE,gpu.layout,0,1,&gpu.descriptorSet,0,nullptr);
        if(gpu.addressMode){
            struct AddressPush{VkDeviceAddress output;VkDeviceAddress counter;uint32_t count;uint32_t padding;}push{};
            VkBufferDeviceAddressInfo address{VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO};address.buffer=gpu.buffers[1].buffer;push.output=gpu.getAddress(gpu.device,&address);
            address.buffer=gpu.buffers[2].buffer;push.counter=gpu.getAddress(gpu.device,&address);push.count=kBodies;
            if(push.output==0||push.counter==0)throw std::runtime_error("zero-buffer-device-address");
            vkCmdPushConstants(gpu.command,gpu.layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(push),&push);
        }else vkCmdPushConstants(gpu.command,gpu.layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(kBodies),&kBodies);
    }
    for(int i=0;i<iterations;++i){
        if(transfer){
            VkBufferCopy copy{0,0,gpu.buffers[0].bytes};vkCmdCopyBuffer(gpu.command,gpu.buffers[0].buffer,gpu.buffers[1].buffer,1,&copy);
            gpu.Barrier(VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT);continue;
        }
        if(compact){
            gpu.Barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdFillBuffer(gpu.command,gpu.buffers[2].buffer,0,16,0);
            gpu.Barrier(VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
        }
        if(indirect){
            vkCmdBindPipeline(gpu.command,VK_PIPELINE_BIND_POINT_COMPUTE,gpu.argsPipeline);vkCmdDispatch(gpu.command,1,1,1);
            gpu.Barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        }
        vkCmdBindPipeline(gpu.command,VK_PIPELINE_BIND_POINT_COMPUTE,gpu.pipeline);
        if(indirect)vkCmdDispatchIndirect(gpu.command,gpu.buffers[2].buffer,0);else vkCmdDispatch(gpu.command,(kBodies+63)/64,1,1);
        gpu.Barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT);
    }
    if(gpu.tileMode){
        gpu.Barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);
        VkBufferCopy copy{0,0,16};vkCmdCopyBuffer(gpu.command,gpu.buffers[2].buffer,gpu.buffers[3].buffer,1,&copy);
    }
    gpu.Barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT);
    result.elapsed=gpu.Submit()+mappingNs;result.executed=true;
    result.scope=transfer?"host-map-update-plus-queue-submit-completion-excluding-command-recording":"queue-submit-to-completion-excluding-device-pipeline-upload-and-command-recording";
    result.extra+=",\"device\":"+Quote(instance.properties.deviceName)+",\"driver_version\":"+std::to_string(instance.properties.driverVersion)
        +",\"body_count\":"+std::to_string(kBodies)+",\"source_count\":3,\"gpu_timestamp_valid\":"+(gpu.gpuTimestampValid?"true":"false")
        +",\"gpu_elapsed_ns\":"+(gpu.gpuTimestampValid?std::to_string(gpu.gpuNs):"null")+",\"mapping_update_ns\":"+std::to_string(mappingNs);
    if(transfer){
        if(std::memcmp(gpu.buffers[0].mapped,gpu.buffers[1].mapped,static_cast<size_t>(gpu.buffers[0].bytes))!=0)throw std::runtime_error("transfer-byte-oracle-mismatch");
        result.checksum=Hash(gpu.buffers[1].mapped,static_cast<size_t>(gpu.buffers[0].bytes));
    }else if(compact){
        const uint32_t count=*static_cast<const uint32_t*>(gpu.buffers[gpu.tileMode?3:2].mapped);
        if(count>kBodies)throw std::runtime_error("compaction-output-count-overflow");
        std::vector<uint32_t>ids(count),expected;std::memcpy(ids.data(),gpu.buffers[1].mapped,count*sizeof(uint32_t));std::sort(ids.begin(),ids.end());
        for(uint32_t i=0;i<kBodies;++i)if((i*17U+13U)%7U<3U)expected.push_back(i);
        if(ids!=expected)throw std::runtime_error("compaction-count-id-oracle-mismatch");
        result.checksum=Hash(ids.data(),ids.size()*sizeof(uint32_t));
        result.extra+=",\"accepted_count\":"+std::to_string(count)+",\"tile_heap_used\":"+(gpu.tileMode?"true":"false");
    }else{
        const auto* actual32=static_cast<const float*>(gpu.buffers[1].mapped);const auto* actual64=static_cast<const double*>(gpu.buffers[1].mapped);
        for(uint32_t i=0;i<kBodies;++i){
            std::array<double,4>expected{};
            if(visual){const double x=source[i*4]*0.5+0.5,y=source[i*4+1]*0.5+0.5;expected={x*x,y*y,(x+y)*0.25,1};}
            else{
                constexpr std::array<std::array<double,4>,3>sources{{{0.5,-0.25,0.125,1},{-0.75,0.5,-0.25,0.0625},{0.125,0.75,0.5,0.015625}}};
                for(const auto& s:sources){
                    const double x=s[0]-source[i*4],y=s[1]-source[i*4+1],z=s[2]-source[i*4+2];
                    const double inverse=1/std::sqrt(x*x+y*y+z*z+0.0009765625),scale=s[3]*inverse*inverse*inverse;
                    expected[0]+=x*scale;expected[1]+=y*scale;expected[2]+=z*scale;
                }
            }
            for(size_t c=0;c<4;++c){
                const double actual=fp64?actual64[i*4+c]:actual32[i*4+c];if(!std::isfinite(actual))throw std::runtime_error("nonfinite-gpu-output");
                result.error=std::max(result.error,std::abs(actual-expected[c])/std::max(1.0,std::abs(expected[c])));
            }
        }
        const double tolerance=fp64?1e-11:id=="gpu.visual-fp16"?0.002:0.0002;
        std::ostringstream toleranceJson;toleranceJson<<std::setprecision(17)<<tolerance;
        result.extra+=",\"max_allowed_scaled_error\":"+toleranceJson.str()+",\"authority\":\"isolated-forecast-or-visual-only\"";
        if(result.error>tolerance){result.status="FAIL";result.reason="gpu-fp64-cpu-oracle-mismatch";}
        result.checksum=Hash(gpu.buffers[1].mapped,static_cast<size_t>(gpu.buffers[1].bytes));
    }
    return result;
}
} // namespace
GpuInventory GpuCapabilities(){
    Instance instance;const bool available=instance.Initialize();
    GpuInventory result;
    result.cases={
        {"gpu.passive-fp32","Isolated FP32 passive acceleration","gpu","","",true,true},
        {"gpu.passive-fp64","Isolated FP64 passive acceleration","gpu","","gpu.passive-fp32",true,true},
        {"gpu.visual-fp32","FP32 visual colour transform","gpu","","",true,true},
        {"gpu.visual-fp16","FP16 visual colour transform","gpu","","gpu.visual-fp32",true,true},
        {"gpu.compact-atomic","Atomic visibility compaction","gpu","","",true,true},
        {"gpu.compact-subgroup","Subgroup visibility compaction","gpu","","gpu.compact-atomic",true,true},
        {"gpu.compact-bda","Buffer-address visibility compaction","gpu","","gpu.compact-atomic",true,true},
        {"gpu.compact-tile-memory","QCOM tile heap transient compaction counter","gpu","","gpu.compact-atomic",true,true},
        {"gpu.dispatch-indirect","GPU-generated indirect dispatch","gpu","compute-dispatch prototype; not an indirect drawing implementation","gpu.passive-fp32",true,true},
        {"gpu.pipeline-no-cache","Pipeline creation without explicit cache","gpu","driver internal caches are not cleared","",true,true},
        {"gpu.pipeline-cache","Pipeline cache serialisation and reuse","gpu","driver internal caches are not cleared","gpu.pipeline-no-cache",true,true},
        {"gpu.transfer-persistent","Persistent coherent upload mapping","gpu","","",true,true},
        {"gpu.transfer-remap","Repeated coherent upload mapping","gpu","","gpu.transfer-persistent",true,true},
        {"gpu.timeline-submit","Timeline completion on isolated compute","gpu","","gpu.passive-fp32",true,true},
        {"gpu.sync2-submit","Synchronisation2 on isolated compute","gpu","","gpu.passive-fp32",true,true},
        {"gpu.qcom-control","Portable inversion control","gpu","Vulkan 1.3 format qualification occurs on execution","",true,true},
        {"gpu.qcom-box-control","Portable 4x4 box filter control","gpu","Vulkan 1.3 format qualification occurs on execution","",true,true},
        {"gpu.qcom-tile-shading","QCOM per-tile inversion","gpu","whole-frame renderer performance remains unqualified","gpu.qcom-control",true,true},
        {"gpu.qcom-image-processing","QCOM texture box filtering","gpu","format and sampler qualification occurs on execution","gpu.qcom-box-control",true,true},
    };
    for(auto& item:result.cases){
        const auto reason=available?Unsupported(instance,item.id):instance.skip;
        if(!reason.empty()){item.supported=false;item.reason=reason;}
        if(item.id.rfind("gpu.qcom-",0)==0){
#ifndef SOLARLAB_EMBED_QCOM_SHADERS
            item.supported=false;item.implemented=false;item.reason="pinned-QCOM-shaders-not-packaged-in-this-build";
#else
            if(available&&std::min(instance.apiVersion,instance.properties.apiVersion)<VK_API_VERSION_1_3){item.supported=false;item.reason="probe-requires-vulkan-1.3";}
            if(item.id=="gpu.qcom-tile-shading"&&available){
                VkPhysicalDeviceTileShadingFeaturesQCOM tile{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TILE_SHADING_FEATURES_QCOM};
                if(instance.Has(VK_QCOM_TILE_SHADING_EXTENSION_NAME)&&instance.Has(VK_QCOM_TILE_PROPERTIES_EXTENSION_NAME)){
                    VkPhysicalDeviceFeatures2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,&tile,{}};vkGetPhysicalDeviceFeatures2(instance.physical,&query);
                }
                if(!tile.tileShading||!tile.tileShadingColorAttachments||!tile.tileShadingPerTileDispatch){item.supported=false;item.reason="QCOM-tile-features-unavailable";}
            }
            if(item.id=="gpu.qcom-image-processing"&&available){
                VkPhysicalDeviceImageProcessingFeaturesQCOM image{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_PROCESSING_FEATURES_QCOM};
                if(instance.Has(VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME)){VkPhysicalDeviceFeatures2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,&image,{}};vkGetPhysicalDeviceFeatures2(instance.physical,&query);}
                if(!image.textureBoxFilter){item.supported=false;item.reason="QCOM-box-filter-feature-unavailable";}
            }
#endif
        }
    }
    if(!available){result.json="{\"status\":\"SKIP\",\"reason\":"+Quote(instance.skip)+"}";return result;}
    std::ostringstream out;out<<std::setprecision(17)<<"{\"status\":\"PASS\",\"scope\":\"capability-query-only\",\"enabled\":false,\"executed\":false"
        <<",\"device\":"<<Quote(instance.properties.deviceName)<<",\"vendor_id\":"<<instance.properties.vendorID<<",\"device_id\":"<<instance.properties.deviceID
        <<",\"driver_version\":"<<instance.properties.driverVersion<<",\"device_api_version\":"<<instance.properties.apiVersion<<",\"instance_api_version\":"<<instance.apiVersion
        <<",\"shaderFloat64\":"<<(instance.features.shaderFloat64?"true":"false")<<",\"shaderFloat16\":"<<(instance.float16?"true":"false")
        <<",\"shaderInt64\":"<<(instance.features.shaderInt64?"true":"false")<<",\"shaderInt16\":"<<(instance.features.shaderInt16?"true":"false")
        <<",\"bufferDeviceAddress\":"<<(instance.advanced.bufferDeviceAddress?"true":"false")<<",\"timelineSemaphore\":"<<(instance.advanced.timelineSemaphore?"true":"false")
        <<",\"synchronization2\":"<<(instance.advanced.synchronization2?"true":"false")<<",\"subgroup_size\":"<<instance.advanced.subgroup.subgroupSize
        <<",\"subgroup_stages\":"<<instance.advanced.subgroup.supportedStages<<",\"subgroup_operations\":"<<instance.advanced.subgroup.supportedOperations
        <<",\"timestamp_period_ns\":"<<instance.properties.limits.timestampPeriod<<",\"timestamp_valid_bits\":"<<instance.advanced.timestampValidBits
        <<",\"min_storage_buffer_offset_alignment\":"<<instance.properties.limits.minStorageBufferOffsetAlignment
        <<",\"noncoherent_atom_bytes\":"<<instance.properties.limits.nonCoherentAtomSize<<",\"max_compute_shared_memory_bytes\":"<<instance.properties.limits.maxComputeSharedMemorySize
        <<",\"qcom_tile_heap\":"<<Quote(instance.tile.Summary())<<",\"pipeline_cache_uuid\":\"";
    for(uint8_t byte:instance.properties.pipelineCacheUUID)out<<std::hex<<std::setw(2)<<std::setfill('0')<<unsigned(byte);
    out<<std::dec<<"\",\"extensions\":[";
    for(size_t i=0;i<instance.extensions.size();++i){if(i)out<<',';out<<Quote(instance.extensions[i].extensionName);}
    out<<"],\"memory_heaps\":[";
    for(uint32_t i=0;i<instance.memory.memoryHeapCount;++i){if(i)out<<',';out<<"{\"bytes\":"<<instance.memory.memoryHeaps[i].size<<",\"flags\":"<<instance.memory.memoryHeaps[i].flags<<'}';}
    out<<"],\"memory_types\":[";
    for(uint32_t i=0;i<instance.memory.memoryTypeCount;++i){if(i)out<<',';out<<"{\"heap\":"<<instance.memory.memoryTypes[i].heapIndex<<",\"flags\":"<<instance.memory.memoryTypes[i].propertyFlags<<'}';}
    out<<"],\"queues\":[";
    for(size_t i=0;i<instance.queues.size();++i){if(i)out<<',';out<<"{\"family\":"<<i<<",\"count\":"<<instance.queues[i].queueCount<<",\"flags\":"<<instance.queues[i].queueFlags<<",\"timestamp_valid_bits\":"<<instance.queues[i].timestampValidBits<<'}';}
    out<<"]}";result.json=out.str();return result;
}
Sample RunGpu(const std::string& id,int iterations){
    Sample result;result.iterations=iterations;
    if(id.rfind("gpu.qcom-",0)==0){
#ifdef SOLARLAB_EMBED_QCOM_SHADERS
        const std::string mode=id.substr(std::string("gpu.qcom-").size());
        const auto probe=solarlab::probes::RunQcomProbe(mode,"embedded",false);
        result.status=probe.status;result.reason=probe.reason;result.iterations=1;
        result.elapsed=probe.submissionNs;result.error=probe.maximumError;
        result.enabled=result.executed=probe.submissionNs>0;
        result.scope="queue-submit-to-completion-qualification-workload-one-submission";
        result.extra=",\"qcom_probe\":"+probe.Json(mode)+",\"full_frame_performance_qualified\":false";
#else
        result.status="SKIP";result.reason="pinned-QCOM-shaders-not-packaged-in-this-build";result.implemented=false;
#endif
        return result;
    }
    static const std::array<const char*,15> known{{"gpu.passive-fp32","gpu.passive-fp64","gpu.visual-fp32","gpu.visual-fp16","gpu.compact-atomic","gpu.compact-subgroup","gpu.compact-bda","gpu.compact-tile-memory","gpu.dispatch-indirect","gpu.pipeline-no-cache","gpu.pipeline-cache","gpu.transfer-persistent","gpu.transfer-remap","gpu.timeline-submit","gpu.sync2-submit"}};
    if(std::none_of(known.begin(),known.end(),[&](const char* candidate){return id==candidate;})){result.status="SKIP";result.reason="unknown-gpu-case";result.implemented=false;return result;}
    Instance instance;if(!instance.Initialize()){result.status="SKIP";result.reason=instance.skip;return result;}
    const auto unavailable=Unsupported(instance,id);if(!unavailable.empty()){result.status="SKIP";result.reason=unavailable;return result;}
    return ComputeCase(instance,id,iterations);
}
} // namespace solarlab::benchmark
