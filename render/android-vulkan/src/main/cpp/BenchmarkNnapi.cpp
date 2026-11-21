// Public NDK accelerator qualification. NNAPI is deprecated on newer Android;
// this optional probe measures an exposed driver, not a new production backend.
// No vendor library, model download, permissions or CPU fallback is used.
#include "BenchmarkNnapi.h"
#include <android/NeuralNetworks.h>
#include <algorithm>
#include <array>
#include <limits>
#include <memory>

#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
#endif

namespace solarlab::benchmark {
namespace {
constexpr uint32_t Rows=256, Inputs=32, Outputs=16;
using ModelPtr=std::unique_ptr<ANeuralNetworksModel, decltype(&ANeuralNetworksModel_free)>;
using CompilationPtr=std::unique_ptr<ANeuralNetworksCompilation, decltype(&ANeuralNetworksCompilation_free)>;
using ExecutionPtr=std::unique_ptr<ANeuralNetworksExecution, decltype(&ANeuralNetworksExecution_free)>;

struct Fixture {
    std::vector<float> input, weights, bias, output;
    std::vector<double> oracle;
    Fixture():input(Rows*Inputs),weights(Outputs*Inputs),bias(Outputs),output(Rows*Outputs),oracle(Rows*Outputs) {
        for(size_t i=0;i<input.size();++i) input[i]=static_cast<float>(static_cast<int>((i*17+3)%113)-56)/64.0F;
        for(size_t i=0;i<weights.size();++i) weights[i]=static_cast<float>(static_cast<int>((i*23+7)%67)-33)/128.0F;
        for(size_t i=0;i<bias.size();++i) bias[i]=static_cast<float>(i)/32.0F;
        for(uint32_t row=0;row<Rows;++row) for(uint32_t out=0;out<Outputs;++out) {
            double value=bias[out];
            for(uint32_t in=0;in<Inputs;++in) value+=double(input[row*Inputs+in])*double(weights[out*Inputs+in]);
            oracle[row*Outputs+out]=value;
        }
    }
    void Cpu() {
        for(uint32_t row=0;row<Rows;++row) for(uint32_t out=0;out<Outputs;++out) {
            float value=bias[out];
            for(uint32_t in=0;in<Inputs;++in) value+=input[row*Inputs+in]*weights[out*Inputs+in];
            output[row*Outputs+out]=value;
        }
    }
    double Error() const {
        double maximum=0;
        for(size_t i=0;i<output.size();++i) {
            if(!std::isfinite(output[i])) return std::numeric_limits<double>::infinity();
            maximum=std::max(maximum,std::abs(double(output[i])-oracle[i])/(1.0+std::abs(oracle[i])));
        }
        return maximum;
    }
};

std::vector<const ANeuralNetworksDevice*> Devices() {
    uint32_t count=0;
    std::vector<const ANeuralNetworksDevice*> devices;
    if(ANeuralNetworks_getDeviceCount(&count)!=ANEURALNETWORKS_NO_ERROR) return devices;
    for(uint32_t index=0;index<std::min(count,64U);++index) {
        ANeuralNetworksDevice* device=nullptr;
        if(ANeuralNetworks_getDevice(index,&device)==ANEURALNETWORKS_NO_ERROR && device) devices.push_back(device);
    }
    return devices;
}

std::string DeviceJson(const ANeuralNetworksDevice* device) {
    const char* name=nullptr; const char* version=nullptr; int32_t type=0; int64_t level=0;
    ANeuralNetworksDevice_getName(device,&name);
    ANeuralNetworksDevice_getVersion(device,&version);
    ANeuralNetworksDevice_getType(device,&type);
    ANeuralNetworksDevice_getFeatureLevel(device,&level);
    return "{\"name\":"+Quote(name?name:"unavailable")+",\"version\":"+Quote(version?version:"unavailable")
        +",\"type\":"+std::to_string(type)+",\"feature_level\":"+std::to_string(level)+"}";
}

ModelPtr MakeModel(Fixture& fixture, int& status) {
    ANeuralNetworksModel* raw=nullptr;
    status=ANeuralNetworksModel_create(&raw);
    ModelPtr model(raw,&ANeuralNetworksModel_free);
    if(status!=ANEURALNETWORKS_NO_ERROR) return model;
    const std::array<uint32_t,2> inputShape={Rows,Inputs},weightShape={Outputs,Inputs},outputShape={Rows,Outputs};
    const uint32_t biasShape=Outputs;
    const ANeuralNetworksOperandType operands[]={
        {ANEURALNETWORKS_TENSOR_FLOAT32,2,inputShape.data(),0.0F,0},
        {ANEURALNETWORKS_TENSOR_FLOAT32,2,weightShape.data(),0.0F,0},
        {ANEURALNETWORKS_TENSOR_FLOAT32,1,&biasShape,0.0F,0},
        {ANEURALNETWORKS_INT32,0,nullptr,0.0F,0},
        {ANEURALNETWORKS_TENSOR_FLOAT32,2,outputShape.data(),0.0F,0},
    };
    for(const auto& operand:operands) {
        status=ANeuralNetworksModel_addOperand(raw,&operand);
        if(status!=ANEURALNETWORKS_NO_ERROR) return model;
    }
    const int32_t activation=ANEURALNETWORKS_FUSED_NONE;
    const uint32_t operationInputs[]={0,1,2,3},operationOutputs[]={4};
    const uint32_t modelInputs[]={0};
    auto accept=[&](int value){status=value; return value==ANEURALNETWORKS_NO_ERROR;};
    if(!accept(ANeuralNetworksModel_setOperandValue(raw,1,fixture.weights.data(),fixture.weights.size()*sizeof(float))) ||
       !accept(ANeuralNetworksModel_setOperandValue(raw,2,fixture.bias.data(),fixture.bias.size()*sizeof(float))) ||
       !accept(ANeuralNetworksModel_setOperandValue(raw,3,&activation,sizeof(activation))) ||
       !accept(ANeuralNetworksModel_addOperation(raw,ANEURALNETWORKS_FULLY_CONNECTED,4,operationInputs,1,operationOutputs)) ||
       !accept(ANeuralNetworksModel_identifyInputsAndOutputs(raw,1,modelInputs,1,operationOutputs)) ||
       !accept(ANeuralNetworksModel_relaxComputationFloat32toFloat16(raw,false)) ||
       !accept(ANeuralNetworksModel_finish(raw))) return model;
    return model;
}
}

GpuInventory NnapiCapabilities() {
    GpuInventory inventory;
    bool accelerator=false;
    std::string json="{\"api\":\"public-NDK-NNAPI\",\"deprecated_since_android_15\":true,\"devices\":[";
    bool first=true;
    for(const auto* device:Devices()) {
        if(!first) json+=',';
        first=false;
        json+=DeviceJson(device);
        int32_t type=0;
        if(ANeuralNetworksDevice_getType(device,&type)==ANEURALNETWORKS_NO_ERROR && type==ANEURALNETWORKS_DEVICE_ACCELERATOR) accelerator=true;
    }
    inventory.json=json+"],\"scientific_use\":\"dense-surrogate-primitive-only; no-trained-orbit-model\"}";
    inventory.cases.push_back({"nnapi-dense-cpu","FP32 dense layer CPU control","npu","FP64 oracle; fixed 256x32x16 dense layer","",true,true});
    inventory.cases.push_back({"nnapi-dense-accelerator","NNAPI explicit accelerator dense layer","npu",
        accelerator?"Operation support checked before execution; no CPU fallback":"No public NNAPI accelerator driver exposed",
        "nnapi-dense-cpu",accelerator,true});
    return inventory;
}

Sample RunNnapi(const std::string& id,int iterations) {
    Sample result;
    iterations=std::clamp(iterations,1,32);
    result.iterations=iterations;
    result.scope="total-of-iterations; CPU-arithmetic-or-accelerator-create-bind-compute-wait; excludes-model-compilation-fixture-oracle-checksum";
    Fixture fixture;
    if(id=="nnapi-dense-cpu") {
        result.enabled=true;
        for(int i=0;i<iterations;++i) {
            auto start=Clock::now(); fixture.Cpu(); result.elapsed+=Nanos(start);
            result.checksum=(result.checksum^Hash(fixture.output.data(),fixture.output.size()*sizeof(float)))*1099511628211ULL;
        }
        result.executed=true;
    } else if(id=="nnapi-dense-accelerator") {
        int status=0;
        ModelPtr model=MakeModel(fixture,status);
        if(status!=ANEURALNETWORKS_NO_ERROR || !model) {
            result.status="FAIL"; result.reason="NNAPI model construction failed: "+std::to_string(status); return result;
        }
        const ANeuralNetworksDevice* selected=nullptr;
        for(const auto* device:Devices()) {
            int32_t type=0; bool supported=false;
            if(ANeuralNetworksDevice_getType(device,&type)!=ANEURALNETWORKS_NO_ERROR || type!=ANEURALNETWORKS_DEVICE_ACCELERATOR) continue;
            const ANeuralNetworksDevice* selection[]={device};
            if(ANeuralNetworksModel_getSupportedOperationsForDevices(model.get(),selection,1,&supported)==ANEURALNETWORKS_NO_ERROR && supported) { selected=device; break; }
        }
        if(!selected) {
            result.status="SKIP"; result.reason="No exposed accelerator supports the complete FP32 dense operation; no CPU fallback"; return result;
        }
        result.enabled=true;
        result.extra=",\"selected_device\":"+DeviceJson(selected);
        const ANeuralNetworksDevice* selection[]={selected};
        ANeuralNetworksCompilation* rawCompilation=nullptr;
        auto compileStart=Clock::now();
        status=ANeuralNetworksCompilation_createForDevices(model.get(),selection,1,&rawCompilation);
        CompilationPtr compilation(rawCompilation,&ANeuralNetworksCompilation_free);
        if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksCompilation_setPreference(compilation.get(),ANEURALNETWORKS_PREFER_SUSTAINED_SPEED);
        if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksCompilation_setTimeout(compilation.get(),2'000'000'000ULL);
        if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksCompilation_finish(compilation.get());
        result.extra+=",\"compilation_ns\":"+std::to_string(Nanos(compileStart));
        if(status!=ANEURALNETWORKS_NO_ERROR) {
            result.status="FAIL"; result.reason="Eligible accelerator compilation failed: "+std::to_string(status); return result;
        }
        uint64_t hardwareNs=0; bool hardwareAvailable=true;
        for(int i=0;i<iterations;++i) {
            ANeuralNetworksExecution* rawExecution=nullptr;
            auto start=Clock::now();
            status=ANeuralNetworksExecution_create(compilation.get(),&rawExecution);
            ExecutionPtr execution(rawExecution,&ANeuralNetworksExecution_free);
            if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksExecution_setInput(execution.get(),0,nullptr,fixture.input.data(),fixture.input.size()*sizeof(float));
            if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksExecution_setOutput(execution.get(),0,nullptr,fixture.output.data(),fixture.output.size()*sizeof(float));
            if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksExecution_setTimeout(execution.get(),1'000'000'000ULL);
            if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksExecution_setMeasureTiming(execution.get(),true);
            if(status==ANEURALNETWORKS_NO_ERROR) status=ANeuralNetworksExecution_compute(execution.get());
            result.elapsed+=Nanos(start);
            if(status!=ANEURALNETWORKS_NO_ERROR) {
                result.status="FAIL"; result.reason="Eligible accelerator execution failed: "+std::to_string(status); return result;
            }
            result.executed=true;
            uint64_t duration=std::numeric_limits<uint64_t>::max();
            if(ANeuralNetworksExecution_getDuration(execution.get(),ANEURALNETWORKS_DURATION_ON_HARDWARE,&duration)==ANEURALNETWORKS_NO_ERROR && duration!=std::numeric_limits<uint64_t>::max()) hardwareNs+=duration;
            else hardwareAvailable=false;
            result.checksum=(result.checksum^Hash(fixture.output.data(),fixture.output.size()*sizeof(float)))*1099511628211ULL;
        }
        result.extra+=",\"accelerator_hardware_total_ns\":"+(hardwareAvailable?std::to_string(hardwareNs):"null");
    } else { result.status="FAIL"; result.reason="Unknown NNAPI experiment"; return result; }
    result.error=fixture.Error();
    result.extra+=",\"workload\":\"dense-256x32x16\",\"numerical_policy\":\"non-authoritative-FP32-surrogate-primitive\",\"tolerance_scaled\":0.0001,\"cpu_fallback_allowed\":false,\"learned_orbit_model\":false";
    if(!(result.error<=1e-4)) { result.status="FAIL"; result.reason="Dense output differs from FP64 oracle"; }
    return result;
}
}
#if defined(__clang__)
#pragma clang diagnostic pop
#endif
