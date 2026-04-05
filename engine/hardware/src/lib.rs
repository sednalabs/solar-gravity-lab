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
    OpenCl,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GpuBackendStateFamily {
    Simulation,
    Rendering,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BackendFamilyAssignment {
    pub state_family: GpuBackendStateFamily,
    pub backend: GpuBackend,
}

#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct GpuBackendReport {
    pub active: Vec<BackendFamilyAssignment>,
    pub available: Vec<BackendFamilyAssignment>,
}

impl GpuBackendReport {
    #[must_use]
    pub fn has_one_owner_per_state_family(&self) -> bool {
        let mut families = std::collections::HashSet::new();

        for assignment in &self.active {
            if !families.insert(assignment.state_family) {
                return false;
            }
        }

        true
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HardwareProfile {
    pub cpu_backend: CpuBackend,
    pub gpu_backend: GpuBackend,
    pub gpu_backend_report: GpuBackendReport,
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
            gpu_backend_report: GpuBackendReport::default(),
            cpu_features: Vec::new(),
            gpu_features: Vec::new(),
            acceleration_modes: Vec::new(),
        }
    }

    #[must_use]
    pub fn active_gpu_backends(&self) -> &[BackendFamilyAssignment] {
        &self.gpu_backend_report.active
    }

    #[must_use]
    pub fn available_gpu_backends(&self) -> &[BackendFamilyAssignment] {
        &self.gpu_backend_report.available
    }

    #[must_use]
    pub fn has_one_owner_per_state_family(&self) -> bool {
        self.gpu_backend_report.has_one_owner_per_state_family()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opencl_backend_support_is_encoded_in_hardware_profile() {
        let profile = HardwareProfile {
            cpu_backend: CpuBackend::ReferenceScalar,
            gpu_backend: GpuBackend::OpenCl,
            gpu_backend_report: GpuBackendReport {
                active: vec![BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::OpenCl,
                }],
                available: Vec::new(),
            },
            cpu_features: vec!["sse4.2".to_string()],
            gpu_features: Vec::new(),
            acceleration_modes: vec!["dual-gpu".to_string()],
        };

        assert_eq!(profile.gpu_backend, GpuBackend::OpenCl);
        assert_eq!(profile.active_gpu_backends()[0].backend, GpuBackend::OpenCl);
        assert!(profile.has_one_owner_per_state_family());
    }

    #[test]
    fn gpu_backend_report_enforces_one_owner_per_state_family() {
        let report = GpuBackendReport {
            active: vec![
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::Vulkan,
                },
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Rendering,
                    backend: GpuBackend::Metal,
                },
            ],
            available: vec![],
        };

        let active_duplicate_report = GpuBackendReport {
            active: vec![
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::Vulkan,
                },
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::Metal,
                },
            ],
            available: vec![],
        };

        let available_duplicate_report = GpuBackendReport {
            active: vec![BackendFamilyAssignment {
                state_family: GpuBackendStateFamily::Simulation,
                backend: GpuBackend::Vulkan,
            }],
            available: vec![
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::Vulkan,
                },
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::OpenCl,
                },
            ],
        };

        assert!(report.has_one_owner_per_state_family());
        assert!(!active_duplicate_report.has_one_owner_per_state_family());
        assert!(available_duplicate_report.has_one_owner_per_state_family());
    }
}
