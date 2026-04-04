#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CpuBackend {
    ReferenceScalar,
    SimdArm64,
    SimdX64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GpuBackend {
    None,
    Vulkan,
    Metal,
    WebGpuClass,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HardwareProfile {
    pub cpu_backend: CpuBackend,
    pub gpu_backend: GpuBackend,
    pub cpu_features: Vec<String>,
    pub gpu_features: Vec<String>,
    pub acceleration_modes: Vec<String>,
}

impl HardwareProfile {
    #[must_use]
    pub fn offline_reference() -> Self {
        Self {
            cpu_backend: CpuBackend::ReferenceScalar,
            gpu_backend: GpuBackend::None,
            cpu_features: Vec::new(),
            gpu_features: Vec::new(),
            acceleration_modes: Vec::new(),
        }
    }
}
