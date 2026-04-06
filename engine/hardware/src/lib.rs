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

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GpuWorkloadClass {
    RealtimeRendering,
    InFrameCompaction,
    LongHorizonTracerIntegration,
    ForecastPathSampling,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GpuWorkloadAssignment {
    pub workload: GpuWorkloadClass,
    pub backend: GpuBackend,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GpuInteropSyncBoundary {
    CheckpointPublication,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GpuInteropErrorBudget {
    /// Maximum tolerated publish-time position delta in millimeters.
    pub max_position_error_mm: u32,
    /// Maximum tolerated publish-time velocity delta in micrometers/second.
    pub max_velocity_error_um_per_s: u32,
    /// Maximum tolerated relative total-energy drift in parts per million.
    pub max_relative_energy_drift_ppm: u32,
    /// Maximum tolerated relative angular-momentum drift in parts per million.
    pub max_relative_angular_momentum_drift_ppm: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GpuInteropPolicy {
    pub sync_boundary: GpuInteropSyncBoundary,
    pub error_budget: GpuInteropErrorBudget,
}

#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct GpuBackendRoleSummary {
    pub rendering: Option<GpuBackend>,
    pub simulation_assist: Option<GpuBackend>,
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

    #[must_use]
    pub fn rendering_backend(&self) -> Option<GpuBackend> {
        self.active
            .iter()
            .find(|assignment| assignment.state_family == GpuBackendStateFamily::Rendering)
            .map(|assignment| assignment.backend.clone())
    }

    #[must_use]
    pub fn simulation_assist_backend(&self) -> Option<GpuBackend> {
        self.active
            .iter()
            .find(|assignment| assignment.state_family == GpuBackendStateFamily::Simulation)
            .map(|assignment| assignment.backend.clone())
    }

    #[must_use]
    pub fn role_summary(&self) -> GpuBackendRoleSummary {
        GpuBackendRoleSummary {
            rendering: self.rendering_backend(),
            simulation_assist: self.simulation_assist_backend(),
        }
    }

    #[must_use]
    pub fn supports_vulkan_render_opencl_simulation_assist(&self) -> bool {
        matches!(self.rendering_backend(), Some(GpuBackend::Vulkan))
            && matches!(self.simulation_assist_backend(), Some(GpuBackend::OpenCl))
    }

    #[must_use]
    pub fn workload_assignments(&self) -> Vec<GpuWorkloadAssignment> {
        let mut assignments = Vec::new();

        if matches!(self.rendering_backend(), Some(GpuBackend::Vulkan)) {
            assignments.push(GpuWorkloadAssignment {
                workload: GpuWorkloadClass::RealtimeRendering,
                backend: GpuBackend::Vulkan,
            });
            assignments.push(GpuWorkloadAssignment {
                workload: GpuWorkloadClass::InFrameCompaction,
                backend: GpuBackend::Vulkan,
            });
        }

        if matches!(self.simulation_assist_backend(), Some(GpuBackend::OpenCl)) {
            assignments.push(GpuWorkloadAssignment {
                workload: GpuWorkloadClass::LongHorizonTracerIntegration,
                backend: GpuBackend::OpenCl,
            });
            assignments.push(GpuWorkloadAssignment {
                workload: GpuWorkloadClass::ForecastPathSampling,
                backend: GpuBackend::OpenCl,
            });
        }

        assignments
    }

    #[must_use]
    pub fn interop_policy(&self) -> Option<GpuInteropPolicy> {
        if self.supports_vulkan_render_opencl_simulation_assist() {
            Some(vulkan_opencl_interop_policy())
        } else {
            None
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HardwareProfile {
    pub cpu_backend: CpuBackend,
    pub gpu_backend: GpuBackend,
    pub gpu_backend_report: GpuBackendReport,
    pub gpu_workload_assignments: Vec<GpuWorkloadAssignment>,
    pub gpu_interop_policy: Option<GpuInteropPolicy>,
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
            gpu_workload_assignments: Vec::new(),
            gpu_interop_policy: None,
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

    #[must_use]
    pub fn gpu_role_summary(&self) -> GpuBackendRoleSummary {
        self.gpu_backend_report.role_summary()
    }

    #[must_use]
    pub fn supports_vulkan_render_opencl_simulation_assist(&self) -> bool {
        self.gpu_backend_report
            .supports_vulkan_render_opencl_simulation_assist()
    }

    #[must_use]
    pub fn has_explicit_opencl_workload_surface(&self) -> bool {
        self.gpu_workload_assignments.iter().any(|assignment| {
            assignment.backend == GpuBackend::OpenCl
                && matches!(
                    assignment.workload,
                    GpuWorkloadClass::LongHorizonTracerIntegration
                        | GpuWorkloadClass::ForecastPathSampling
                )
        })
    }

    #[must_use]
    pub fn interop_error_budget_policy(&self) -> Option<GpuInteropPolicy> {
        self.gpu_interop_policy
    }
}

#[must_use]
pub fn vulkan_opencl_interop_policy() -> GpuInteropPolicy {
    GpuInteropPolicy {
        sync_boundary: GpuInteropSyncBoundary::CheckpointPublication,
        error_budget: GpuInteropErrorBudget {
            max_position_error_mm: 5_000,
            max_velocity_error_um_per_s: 1_000,
            max_relative_energy_drift_ppm: 10,
            max_relative_angular_momentum_drift_ppm: 10,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vulkan_render_and_opencl_simulation_assist_split_is_encoded_in_hardware_profile() {
        let profile = HardwareProfile {
            cpu_backend: CpuBackend::ReferenceScalar,
            gpu_backend: GpuBackend::Vulkan,
            gpu_backend_report: GpuBackendReport {
                active: vec![
                    BackendFamilyAssignment {
                        state_family: GpuBackendStateFamily::Rendering,
                        backend: GpuBackend::Vulkan,
                    },
                    BackendFamilyAssignment {
                        state_family: GpuBackendStateFamily::Simulation,
                        backend: GpuBackend::OpenCl,
                    },
                ],
                available: Vec::new(),
            },
            gpu_workload_assignments: vec![
                GpuWorkloadAssignment {
                    workload: GpuWorkloadClass::RealtimeRendering,
                    backend: GpuBackend::Vulkan,
                },
                GpuWorkloadAssignment {
                    workload: GpuWorkloadClass::InFrameCompaction,
                    backend: GpuBackend::Vulkan,
                },
                GpuWorkloadAssignment {
                    workload: GpuWorkloadClass::LongHorizonTracerIntegration,
                    backend: GpuBackend::OpenCl,
                },
            ],
            gpu_interop_policy: Some(vulkan_opencl_interop_policy()),
            cpu_features: vec!["sse4.2".to_string()],
            gpu_features: vec!["vk-render".to_string(), "opencl-sim".to_string()],
            acceleration_modes: vec!["dual-gpu".to_string(), "vulkan-render".to_string()],
        };

        assert_eq!(profile.gpu_backend, GpuBackend::Vulkan);
        assert_eq!(
            profile.gpu_role_summary(),
            GpuBackendRoleSummary {
                rendering: Some(GpuBackend::Vulkan),
                simulation_assist: Some(GpuBackend::OpenCl),
            }
        );
        assert!(profile.supports_vulkan_render_opencl_simulation_assist());
        assert!(profile.has_one_owner_per_state_family());
        assert!(profile.has_explicit_opencl_workload_surface());
        assert_eq!(
            profile.interop_error_budget_policy(),
            Some(vulkan_opencl_interop_policy())
        );
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

    #[test]
    fn dual_gpu_report_surfaces_opencl_workloads_and_interop_budget() {
        let report = GpuBackendReport {
            active: vec![
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Rendering,
                    backend: GpuBackend::Vulkan,
                },
                BackendFamilyAssignment {
                    state_family: GpuBackendStateFamily::Simulation,
                    backend: GpuBackend::OpenCl,
                },
            ],
            available: vec![],
        };

        let workloads = report.workload_assignments();
        assert!(workloads.iter().any(|assignment| {
            assignment.backend == GpuBackend::OpenCl
                && assignment.workload == GpuWorkloadClass::LongHorizonTracerIntegration
        }));
        assert!(workloads.iter().any(|assignment| {
            assignment.backend == GpuBackend::OpenCl
                && assignment.workload == GpuWorkloadClass::ForecastPathSampling
        }));
        assert_eq!(
            report.interop_policy(),
            Some(vulkan_opencl_interop_policy())
        );
    }
}
