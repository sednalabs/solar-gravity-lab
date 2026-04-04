use solarlab_hardware::{CpuBackend, GpuBackend};

pub const SOLARLAB_V2_ABI_VERSION: u32 = 1;

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlRuntimeHandle {
    pub raw: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlStatusCode {
    Ok = 0,
    InvalidArgument = 1,
    NotReady = 2,
    InternalError = 3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlResult {
    pub code: SlStatusCode,
    pub detail_length: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlCpuBackend {
    ReferenceScalar = 0,
    SimdArm64 = 1,
    SimdX64 = 2,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlGpuBackend {
    None = 0,
    Vulkan = 1,
    Metal = 2,
    WebGpuClass = 3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlRuntimeInfo {
    pub abi_version: u32,
    pub cpu_backend: SlCpuBackend,
    pub gpu_backend: SlGpuBackend,
}

#[must_use]
pub fn abi_version() -> u32 {
    SOLARLAB_V2_ABI_VERSION
}

#[must_use]
pub fn runtime_info(cpu_backend: CpuBackend, gpu_backend: GpuBackend) -> SlRuntimeInfo {
    SlRuntimeInfo {
        abi_version: SOLARLAB_V2_ABI_VERSION,
        cpu_backend: match cpu_backend {
            CpuBackend::ReferenceScalar => SlCpuBackend::ReferenceScalar,
            CpuBackend::SimdArm64 => SlCpuBackend::SimdArm64,
            CpuBackend::SimdX64 => SlCpuBackend::SimdX64,
        },
        gpu_backend: match gpu_backend {
            GpuBackend::None => SlGpuBackend::None,
            GpuBackend::Vulkan => SlGpuBackend::Vulkan,
            GpuBackend::Metal => SlGpuBackend::Metal,
            GpuBackend::WebGpuClass => SlGpuBackend::WebGpuClass,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::{abi_version, SOLARLAB_V2_ABI_VERSION};

    #[test]
    fn abi_version_matches_constant() {
        assert_eq!(abi_version(), SOLARLAB_V2_ABI_VERSION);
    }
}
