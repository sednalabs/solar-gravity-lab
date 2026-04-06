use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};

mod canonical_startup;

pub use canonical_startup::{
    canonical_startup_seed, CanonicalBodySpec, CanonicalStartupSeed,
    CANONICAL_STARTUP_CURATED_SMALL_BODY_COUNT, CANONICAL_STARTUP_SYNTHETIC_ASTEROID_BELT_COUNT,
    CANONICAL_STARTUP_SYNTHETIC_OORT_CLOUD_COUNT,
};

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum PackageKind {
    Scenario,
    EphemerisBundle,
    CatalogPack,
}

impl PackageKind {
    #[must_use]
    pub const fn as_str(&self) -> &'static str {
        match self {
            Self::Scenario => "scenario",
            Self::EphemerisBundle => "ephemeris-bundle",
            Self::CatalogPack => "catalog-pack",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Digest {
    pub algorithm: String,
    pub value: Vec<u8>,
}

impl Digest {
    #[must_use]
    pub fn hex_value(&self) -> String {
        let mut out = String::with_capacity(self.value.len() * 2);
        for byte in &self.value {
            use std::fmt::Write as _;
            let _ = write!(&mut out, "{byte:02x}");
        }
        out
    }

    #[must_use]
    pub fn content_id(&self, kind: &PackageKind) -> String {
        format!("{}:{}:{}", kind.as_str(), self.algorithm, self.hex_value())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct SemVer {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
    pub prerelease: Option<String>,
    pub build_metadata: Option<String>,
}

impl SemVer {
    #[must_use]
    pub const fn new(major: u32, minor: u32, patch: u32) -> Self {
        Self {
            major,
            minor,
            patch,
            prerelease: None,
            build_metadata: None,
        }
    }
}

impl Ord for SemVer {
    fn cmp(&self, other: &Self) -> Ordering {
        let core =
            (self.major, self.minor, self.patch).cmp(&(other.major, other.minor, other.patch));
        if core != Ordering::Equal {
            return core;
        }

        match (&self.prerelease, &other.prerelease) {
            (None, None) => Ordering::Equal,
            (None, Some(_)) => Ordering::Greater,
            (Some(_), None) => Ordering::Less,
            (Some(left), Some(right)) => compare_prerelease(left, right),
        }
    }
}

impl PartialOrd for SemVer {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

fn compare_prerelease(left: &str, right: &str) -> Ordering {
    let left_parts: Vec<&str> = left.split('.').collect();
    let right_parts: Vec<&str> = right.split('.').collect();

    for (left_part, right_part) in left_parts.iter().zip(right_parts.iter()) {
        let ord = compare_prerelease_identifier(left_part, right_part);
        if ord != Ordering::Equal {
            return ord;
        }
    }

    left_parts.len().cmp(&right_parts.len())
}

fn compare_prerelease_identifier(left: &str, right: &str) -> Ordering {
    let left_numeric = left.parse::<u64>();
    let right_numeric = right.parse::<u64>();

    match (left_numeric, right_numeric) {
        (Ok(left_num), Ok(right_num)) => left_num.cmp(&right_num),
        (Ok(_), Err(_)) => Ordering::Less,
        (Err(_), Ok(_)) => Ordering::Greater,
        (Err(_), Err(_)) => left.cmp(right),
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PackageCompatibility {
    pub runtime_contract_min: SemVer,
    pub runtime_contract_max: SemVer,
    pub required_capabilities: BTreeSet<String>,
    pub supported_platforms: BTreeSet<String>,
}

impl PackageCompatibility {
    #[must_use]
    pub fn supports(&self, target: &CompatibilityTarget) -> bool {
        self.runtime_contract_min <= target.runtime_contract
            && target.runtime_contract <= self.runtime_contract_max
            && self.required_capabilities.is_subset(&target.capabilities)
            && self.supported_platforms.contains(&target.platform)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PackageLocator {
    pub package_id: String,
    pub kind: PackageKind,
    pub package_version: SemVer,
    pub schema_version: String,
    pub digest: Digest,
    pub relative_uri: String,
    pub uncompressed_size_bytes: u64,
    pub required: bool,
    pub compatibility: PackageCompatibility,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UpdateManifest {
    pub manifest_id: String,
    pub manifest_version: SemVer,
    pub channel: String,
    pub packages: Vec<PackageLocator>,
    pub manifest_digest: Option<Digest>,
    pub full_snapshot: bool,
    pub supersedes_manifest_ids: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CompatibilityTarget {
    pub runtime_contract: SemVer,
    pub schema_version: String,
    pub capabilities: BTreeSet<String>,
    pub platform: String,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ValidationCode {
    DuplicateManifestSupersedes,
    DuplicatePackageDigest,
    DuplicatePackageId,
    EmptyManifestChannel,
    EmptyManifestId,
    EmptyPackageId,
    EmptyPackages,
    EmptyRelativeUri,
    InvalidDigestAlgorithm,
    InvalidManifestSupersedes,
    InvalidPackageIdentity,
    InvalidRelativeUri,
    InvalidRuntimeWindow,
    InvalidSchemaVersion,
    InvalidSemVerIdentifier,
    MissingDigestValue,
    UnsupportedPackagePlatform,
    ZeroPackageSize,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ValidationError {
    pub code: ValidationCode,
    pub path: String,
    pub message: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ValidationReport {
    pub errors: Vec<ValidationError>,
}

impl ValidationReport {
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.errors.is_empty()
    }
}

#[must_use]
pub fn validate_manifest(manifest: &UpdateManifest) -> ValidationReport {
    let mut errors = Vec::new();

    if manifest.manifest_id.trim().is_empty() {
        errors.push(err(
            ValidationCode::EmptyManifestId,
            "manifest.manifest_id",
            "manifest_id must not be empty",
        ));
    }
    if manifest.channel.trim().is_empty() {
        errors.push(err(
            ValidationCode::EmptyManifestChannel,
            "manifest.channel",
            "channel must not be empty",
        ));
    }
    if manifest.packages.is_empty() {
        errors.push(err(
            ValidationCode::EmptyPackages,
            "manifest.packages",
            "manifest must include at least one package",
        ));
    }

    validate_semver(
        &manifest.manifest_version,
        "manifest.manifest_version",
        &mut errors,
    );

    if let Some(digest) = &manifest.manifest_digest {
        validate_digest(digest, "manifest.manifest_digest", &mut errors);
    }

    let mut supersedes_seen = BTreeSet::new();
    for (index, superseded_id) in manifest.supersedes_manifest_ids.iter().enumerate() {
        let path = format!("manifest.supersedes_manifest_ids[{index}]");
        if superseded_id.trim().is_empty() || superseded_id == &manifest.manifest_id {
            errors.push(err(
                ValidationCode::InvalidManifestSupersedes,
                &path,
                "supersedes_manifest_ids must be non-empty and cannot include manifest_id",
            ));
        }
        if !supersedes_seen.insert(superseded_id) {
            errors.push(err(
                ValidationCode::DuplicateManifestSupersedes,
                &path,
                "duplicate superseded manifest id",
            ));
        }
    }

    let mut package_ids = BTreeSet::new();
    let mut package_digests = BTreeSet::new();

    for (index, package) in manifest.packages.iter().enumerate() {
        let package_path = format!("manifest.packages[{index}]");
        validate_package(package, &package_path, &mut errors);

        if !package_ids.insert(package.package_id.clone()) {
            errors.push(err(
                ValidationCode::DuplicatePackageId,
                &format!("{package_path}.package_id"),
                "duplicate package_id",
            ));
        }

        let digest_key = format!(
            "{}:{}",
            package.digest.algorithm,
            package.digest.hex_value()
        );
        if !package_digests.insert(digest_key) {
            errors.push(err(
                ValidationCode::DuplicatePackageDigest,
                &format!("{package_path}.digest"),
                "duplicate package digest",
            ));
        }
    }

    errors.sort();
    ValidationReport { errors }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SelectionError {
    pub required_package_failures: Vec<RequiredPackageFailure>,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct RequiredPackageFailure {
    pub package_id: String,
    pub reason: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredPackage {
    pub package_id: String,
    pub kind: PackageKind,
    pub digest: Digest,
    pub package_version: SemVer,
    pub schema_version: String,
    pub uncompressed_size_bytes: u64,
    pub local_store_uri: String,
}

impl StoredPackage {
    #[must_use]
    pub fn matches_locator(&self, locator: &PackageLocator) -> bool {
        self.package_id == locator.package_id
            && self.kind == locator.kind
            && self.digest == locator.digest
            && self.package_version == locator.package_version
            && self.schema_version == locator.schema_version
            && self.uncompressed_size_bytes == locator.uncompressed_size_bytes
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PackageStoreState {
    pub packages_by_id: BTreeMap<String, StoredPackage>,
}

impl PackageStoreState {
    #[must_use]
    pub fn empty() -> Self {
        Self {
            packages_by_id: BTreeMap::new(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InstalledManifestState {
    pub manifest_id: String,
    pub manifest_version: SemVer,
    pub channel: String,
    pub manifest_digest: Option<Digest>,
    pub installed_package_ids: BTreeSet<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LocalDataState {
    pub package_store: PackageStoreState,
    pub installed_manifest: Option<InstalledManifestState>,
}

impl LocalDataState {
    #[must_use]
    pub fn empty() -> Self {
        Self {
            package_store: PackageStoreState::empty(),
            installed_manifest: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum UpdatePlanStoreAction {
    FetchPackage {
        package_id: String,
        package_kind: PackageKind,
        package_version: SemVer,
        source_relative_uri: String,
        digest: Digest,
        uncompressed_size_bytes: u64,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum UpdatePlanActivationAction {
    InstallPackage { package_id: String },
    RemovePackage { package_id: String },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UpdatePlan {
    pub manifest_id: String,
    pub manifest_version: SemVer,
    pub channel: String,
    pub manifest_digest: Option<Digest>,
    pub selected_package_ids: Vec<String>,
    pub store_actions: Vec<UpdatePlanStoreAction>,
    pub activation_actions: Vec<UpdatePlanActivationAction>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum UpdatePlanError {
    InvalidManifest(ValidationReport),
    IncompatibleSelection(SelectionError),
    MissingBaselineForDelta {
        manifest_id: String,
    },
    DeltaDoesNotSupersedeInstalled {
        manifest_id: String,
        installed_manifest_id: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApplyPackageInputs {
    pub fetched_packages_by_id: BTreeMap<String, StoredPackage>,
}

impl ApplyPackageInputs {
    #[must_use]
    pub fn empty() -> Self {
        Self {
            fetched_packages_by_id: BTreeMap::new(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppliedFetchProvenance {
    pub package_id: String,
    pub source_relative_uri: String,
    pub local_store_uri: String,
    pub digest: Digest,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApplyProvenance {
    pub fetched_packages: Vec<AppliedFetchProvenance>,
    pub reused_package_ids: Vec<String>,
    pub installed_package_ids: Vec<String>,
    pub removed_package_ids: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AppliedUpdate {
    pub committed_state: LocalDataState,
    pub provenance: ApplyProvenance,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ApplyUpdateError {
    DuplicateSelectedPackageId {
        package_id: String,
    },
    DuplicateFetchAction {
        package_id: String,
    },
    DuplicateInstallAction {
        package_id: String,
    },
    DuplicateRemoveAction {
        package_id: String,
    },
    StoreActionReferencesUnselectedPackage {
        package_id: String,
    },
    MissingFetchedPackage {
        package_id: String,
    },
    FetchedPackageMismatch {
        package_id: String,
        reason: String,
    },
    MissingSelectedPackageInStore {
        package_id: String,
    },
    ActivationReferencesUnknownPackage {
        package_id: String,
    },
    ConflictingActivationAction {
        package_id: String,
    },
    InstallActionAlreadyInstalled {
        package_id: String,
    },
    RemoveActionNotInstalled {
        package_id: String,
    },
    InstalledSetDoesNotMatchSelection {
        expected_selected: Vec<String>,
        actual_installed: Vec<String>,
    },
}

pub fn select_compatible_packages<'a>(
    manifest: &'a UpdateManifest,
    target: &CompatibilityTarget,
) -> Result<Vec<&'a PackageLocator>, SelectionError> {
    let mut required_failures = Vec::new();
    let mut selected: Vec<&PackageLocator> = Vec::new();
    let mut best_optional_by_kind: BTreeMap<PackageKind, &PackageLocator> = BTreeMap::new();

    for package in &manifest.packages {
        let compatible = package_is_compatible(package, target);

        if package.required {
            if compatible {
                selected.push(package);
            } else {
                required_failures.push(required_failure(package, target));
            }
            continue;
        }

        if compatible {
            best_optional_by_kind
                .entry(package.kind.clone())
                .and_modify(|best| {
                    if package.package_version > best.package_version {
                        *best = package;
                    }
                })
                .or_insert(package);
        }
    }

    if !required_failures.is_empty() {
        required_failures.sort();
        return Err(SelectionError {
            required_package_failures: required_failures,
        });
    }

    selected.extend(best_optional_by_kind.into_values());
    selected.sort_by(|left, right| left.package_id.cmp(&right.package_id));
    Ok(selected)
}

pub fn plan_manifest_update(
    manifest: &UpdateManifest,
    target: &CompatibilityTarget,
    local: &LocalDataState,
) -> Result<UpdatePlan, UpdatePlanError> {
    let report = validate_manifest(manifest);
    if !report.is_valid() {
        return Err(UpdatePlanError::InvalidManifest(report));
    }

    if !manifest.full_snapshot {
        let installed_manifest = local.installed_manifest.as_ref().ok_or_else(|| {
            UpdatePlanError::MissingBaselineForDelta {
                manifest_id: manifest.manifest_id.clone(),
            }
        })?;
        if installed_manifest.manifest_id != manifest.manifest_id
            && !manifest
                .supersedes_manifest_ids
                .contains(&installed_manifest.manifest_id)
        {
            return Err(UpdatePlanError::DeltaDoesNotSupersedeInstalled {
                manifest_id: manifest.manifest_id.clone(),
                installed_manifest_id: installed_manifest.manifest_id.clone(),
            });
        }
    }

    let selected = select_compatible_packages(manifest, target)
        .map_err(UpdatePlanError::IncompatibleSelection)?;

    let selected_package_ids: Vec<String> = selected
        .iter()
        .map(|package| package.package_id.clone())
        .collect();
    let selected_package_id_set: BTreeSet<String> = selected_package_ids.iter().cloned().collect();

    let mut store_actions = Vec::new();
    for package in &selected {
        let should_fetch = local
            .package_store
            .packages_by_id
            .get(&package.package_id)
            .is_none_or(|stored| !stored.matches_locator(package));
        if should_fetch {
            store_actions.push(UpdatePlanStoreAction::FetchPackage {
                package_id: package.package_id.clone(),
                package_kind: package.kind.clone(),
                package_version: package.package_version.clone(),
                source_relative_uri: package.relative_uri.clone(),
                digest: package.digest.clone(),
                uncompressed_size_bytes: package.uncompressed_size_bytes,
            });
        }
    }
    store_actions.sort_by(|left, right| {
        let left_id = match left {
            UpdatePlanStoreAction::FetchPackage { package_id, .. } => package_id,
        };
        let right_id = match right {
            UpdatePlanStoreAction::FetchPackage { package_id, .. } => package_id,
        };
        left_id.cmp(right_id)
    });

    let installed_ids = local
        .installed_manifest
        .as_ref()
        .map_or_else(BTreeSet::new, |installed| {
            installed.installed_package_ids.clone()
        });

    let mut activation_actions = Vec::new();
    for package_id in selected_package_id_set.difference(&installed_ids) {
        activation_actions.push(UpdatePlanActivationAction::InstallPackage {
            package_id: package_id.clone(),
        });
    }
    for package_id in installed_ids.difference(&selected_package_id_set) {
        activation_actions.push(UpdatePlanActivationAction::RemovePackage {
            package_id: package_id.clone(),
        });
    }

    Ok(UpdatePlan {
        manifest_id: manifest.manifest_id.clone(),
        manifest_version: manifest.manifest_version.clone(),
        channel: manifest.channel.clone(),
        manifest_digest: manifest.manifest_digest.clone(),
        selected_package_ids,
        store_actions,
        activation_actions,
    })
}

pub fn apply_update_plan(
    plan: &UpdatePlan,
    local: &LocalDataState,
    apply_inputs: &ApplyPackageInputs,
) -> Result<AppliedUpdate, ApplyUpdateError> {
    let mut selected_set = BTreeSet::new();
    for package_id in &plan.selected_package_ids {
        if !selected_set.insert(package_id.clone()) {
            return Err(ApplyUpdateError::DuplicateSelectedPackageId {
                package_id: package_id.clone(),
            });
        }
    }

    let mut seen_install_actions = BTreeSet::new();
    let mut seen_remove_actions = BTreeSet::new();
    let mut seen_fetch_actions = BTreeSet::new();
    for action in &plan.store_actions {
        match action {
            UpdatePlanStoreAction::FetchPackage { package_id, .. } => {
                if !selected_set.contains(package_id) {
                    return Err(ApplyUpdateError::StoreActionReferencesUnselectedPackage {
                        package_id: package_id.clone(),
                    });
                }
                if !seen_fetch_actions.insert(package_id.clone()) {
                    return Err(ApplyUpdateError::DuplicateFetchAction {
                        package_id: package_id.clone(),
                    });
                }
            }
        }
    }

    for action in &plan.activation_actions {
        match action {
            UpdatePlanActivationAction::InstallPackage { package_id } => {
                if !seen_install_actions.insert(package_id.clone()) {
                    return Err(ApplyUpdateError::DuplicateInstallAction {
                        package_id: package_id.clone(),
                    });
                }
            }
            UpdatePlanActivationAction::RemovePackage { package_id } => {
                if !seen_remove_actions.insert(package_id.clone()) {
                    return Err(ApplyUpdateError::DuplicateRemoveAction {
                        package_id: package_id.clone(),
                    });
                }
            }
        }
    }
    if let Some(package_id) = seen_install_actions
        .intersection(&seen_remove_actions)
        .next()
        .cloned()
    {
        return Err(ApplyUpdateError::ConflictingActivationAction { package_id });
    }

    let mut next_store = local.package_store.packages_by_id.clone();
    let mut fetched_packages = Vec::new();

    for action in &plan.store_actions {
        match action {
            UpdatePlanStoreAction::FetchPackage {
                package_id,
                package_kind,
                package_version,
                source_relative_uri,
                digest,
                uncompressed_size_bytes,
            } => {
                let fetched = apply_inputs
                    .fetched_packages_by_id
                    .get(package_id)
                    .ok_or_else(|| ApplyUpdateError::MissingFetchedPackage {
                        package_id: package_id.clone(),
                    })?;

                let mismatch_reason = if fetched.package_id != *package_id {
                    Some(format!(
                        "package_id mismatch: expected {package_id} got {}",
                        fetched.package_id
                    ))
                } else if fetched.kind != *package_kind {
                    Some(format!(
                        "kind mismatch: expected {} got {}",
                        package_kind.as_str(),
                        fetched.kind.as_str()
                    ))
                } else if fetched.package_version != *package_version {
                    Some(format!(
                        "version mismatch: expected {}.{}.{} got {}.{}.{}",
                        package_version.major,
                        package_version.minor,
                        package_version.patch,
                        fetched.package_version.major,
                        fetched.package_version.minor,
                        fetched.package_version.patch
                    ))
                } else if fetched.digest != *digest {
                    Some("digest mismatch".to_string())
                } else if fetched.uncompressed_size_bytes != *uncompressed_size_bytes {
                    Some(format!(
                        "size mismatch: expected {uncompressed_size_bytes} got {}",
                        fetched.uncompressed_size_bytes
                    ))
                } else if fetched.local_store_uri.trim().is_empty() {
                    Some("local_store_uri must not be empty".to_string())
                } else {
                    None
                };

                if let Some(reason) = mismatch_reason {
                    return Err(ApplyUpdateError::FetchedPackageMismatch {
                        package_id: package_id.clone(),
                        reason,
                    });
                }

                next_store.insert(package_id.clone(), fetched.clone());
                fetched_packages.push(AppliedFetchProvenance {
                    package_id: package_id.clone(),
                    source_relative_uri: source_relative_uri.clone(),
                    local_store_uri: fetched.local_store_uri.clone(),
                    digest: digest.clone(),
                });
            }
        }
    }

    for package_id in &plan.selected_package_ids {
        if !next_store.contains_key(package_id) {
            return Err(ApplyUpdateError::MissingSelectedPackageInStore {
                package_id: package_id.clone(),
            });
        }
    }

    let mut installed_package_ids = local
        .installed_manifest
        .as_ref()
        .map_or_else(BTreeSet::new, |installed| {
            installed.installed_package_ids.clone()
        });
    let preexisting_installed_package_ids = installed_package_ids.clone();
    let mut newly_installed_package_ids = BTreeSet::new();
    let mut removed_package_ids = BTreeSet::new();

    for action in &plan.activation_actions {
        match action {
            UpdatePlanActivationAction::InstallPackage { package_id } => {
                if !selected_set.contains(package_id) {
                    return Err(ApplyUpdateError::ActivationReferencesUnknownPackage {
                        package_id: package_id.clone(),
                    });
                }
                if preexisting_installed_package_ids.contains(package_id) {
                    return Err(ApplyUpdateError::InstallActionAlreadyInstalled {
                        package_id: package_id.clone(),
                    });
                }
                installed_package_ids.insert(package_id.clone());
                newly_installed_package_ids.insert(package_id.clone());
            }
            UpdatePlanActivationAction::RemovePackage { package_id } => {
                if !preexisting_installed_package_ids.contains(package_id) {
                    return Err(ApplyUpdateError::RemoveActionNotInstalled {
                        package_id: package_id.clone(),
                    });
                }
                installed_package_ids.remove(package_id);
                removed_package_ids.insert(package_id.clone());
            }
        }
    }

    if installed_package_ids != selected_set {
        return Err(ApplyUpdateError::InstalledSetDoesNotMatchSelection {
            expected_selected: selected_set.iter().cloned().collect(),
            actual_installed: installed_package_ids.iter().cloned().collect(),
        });
    }

    let fetched_id_set: BTreeSet<String> = plan
        .store_actions
        .iter()
        .map(|action| match action {
            UpdatePlanStoreAction::FetchPackage { package_id, .. } => package_id.clone(),
        })
        .collect();
    let reused_package_ids: Vec<String> = plan
        .selected_package_ids
        .iter()
        .filter(|package_id| !fetched_id_set.contains(*package_id))
        .cloned()
        .collect();

    Ok(AppliedUpdate {
        committed_state: LocalDataState {
            package_store: PackageStoreState {
                packages_by_id: next_store,
            },
            installed_manifest: Some(InstalledManifestState {
                manifest_id: plan.manifest_id.clone(),
                manifest_version: plan.manifest_version.clone(),
                channel: plan.channel.clone(),
                manifest_digest: plan.manifest_digest.clone(),
                installed_package_ids,
            }),
        },
        provenance: ApplyProvenance {
            fetched_packages,
            reused_package_ids,
            installed_package_ids: newly_installed_package_ids.into_iter().collect(),
            removed_package_ids: removed_package_ids.into_iter().collect(),
        },
    })
}

fn package_is_compatible(package: &PackageLocator, target: &CompatibilityTarget) -> bool {
    package.schema_version == target.schema_version && package.compatibility.supports(target)
}

fn required_failure(
    package: &PackageLocator,
    target: &CompatibilityTarget,
) -> RequiredPackageFailure {
    let reason = if package.schema_version != target.schema_version {
        format!(
            "schema mismatch: package={} target={}",
            package.schema_version, target.schema_version
        )
    } else if target.runtime_contract < package.compatibility.runtime_contract_min
        || target.runtime_contract > package.compatibility.runtime_contract_max
    {
        format!(
            "runtime {}.{}.{} outside [{}.{},{}.{},{}]",
            target.runtime_contract.major,
            target.runtime_contract.minor,
            target.runtime_contract.patch,
            package.compatibility.runtime_contract_min.major,
            package.compatibility.runtime_contract_min.minor,
            package.compatibility.runtime_contract_max.major,
            package.compatibility.runtime_contract_max.minor,
            package.compatibility.runtime_contract_max.patch
        )
    } else if !package
        .compatibility
        .required_capabilities
        .is_subset(&target.capabilities)
    {
        let missing: Vec<&str> = package
            .compatibility
            .required_capabilities
            .difference(&target.capabilities)
            .map(String::as_str)
            .collect();
        format!("missing capabilities: {}", missing.join(","))
    } else {
        format!("unsupported platform: {}", target.platform)
    };

    RequiredPackageFailure {
        package_id: package.package_id.clone(),
        reason,
    }
}

fn validate_package(
    package: &PackageLocator,
    package_path: &str,
    errors: &mut Vec<ValidationError>,
) {
    if package.package_id.trim().is_empty() {
        errors.push(err(
            ValidationCode::EmptyPackageId,
            &format!("{package_path}.package_id"),
            "package_id must not be empty",
        ));
    }

    validate_semver(
        &package.package_version,
        &format!("{package_path}.package_version"),
        errors,
    );

    if package.schema_version.trim().is_empty() {
        errors.push(err(
            ValidationCode::InvalidSchemaVersion,
            &format!("{package_path}.schema_version"),
            "schema_version must not be empty",
        ));
    }

    validate_digest(&package.digest, &format!("{package_path}.digest"), errors);

    let expected_package_id = package.digest.content_id(&package.kind);
    if package.package_id != expected_package_id {
        errors.push(err(
            ValidationCode::InvalidPackageIdentity,
            &format!("{package_path}.package_id"),
            &format!(
                "package_id must match content id {expected_package_id} for deterministic content-addressed identity"
            ),
        ));
    }

    if package.relative_uri.trim().is_empty() {
        errors.push(err(
            ValidationCode::EmptyRelativeUri,
            &format!("{package_path}.relative_uri"),
            "relative_uri must not be empty",
        ));
    } else if package.relative_uri.starts_with('/') || package.relative_uri.contains("://") {
        errors.push(err(
            ValidationCode::InvalidRelativeUri,
            &format!("{package_path}.relative_uri"),
            "relative_uri must be a relative, offline-safe locator",
        ));
    }

    if package.uncompressed_size_bytes == 0 {
        errors.push(err(
            ValidationCode::ZeroPackageSize,
            &format!("{package_path}.uncompressed_size_bytes"),
            "uncompressed_size_bytes must be greater than zero",
        ));
    }

    if package.compatibility.runtime_contract_min > package.compatibility.runtime_contract_max {
        errors.push(err(
            ValidationCode::InvalidRuntimeWindow,
            &format!("{package_path}.compatibility"),
            "runtime_contract_min must be <= runtime_contract_max",
        ));
    }

    if package.compatibility.supported_platforms.is_empty() {
        errors.push(err(
            ValidationCode::UnsupportedPackagePlatform,
            &format!("{package_path}.compatibility.supported_platforms"),
            "supported_platforms must include at least one platform",
        ));
    }

    validate_semver(
        &package.compatibility.runtime_contract_min,
        &format!("{package_path}.compatibility.runtime_contract_min"),
        errors,
    );
    validate_semver(
        &package.compatibility.runtime_contract_max,
        &format!("{package_path}.compatibility.runtime_contract_max"),
        errors,
    );
}

fn validate_digest(digest: &Digest, path: &str, errors: &mut Vec<ValidationError>) {
    if digest.algorithm.trim().is_empty()
        || !digest.algorithm.chars().all(|character| {
            character.is_ascii_lowercase()
                || character.is_ascii_digit()
                || matches!(character, '_' | '+' | '-' | '.')
        })
    {
        errors.push(err(
            ValidationCode::InvalidDigestAlgorithm,
            &format!("{path}.algorithm"),
            "digest.algorithm must use lowercase [a-z0-9_+.-]",
        ));
    }

    if digest.value.is_empty() {
        errors.push(err(
            ValidationCode::MissingDigestValue,
            &format!("{path}.value"),
            "digest.value must not be empty",
        ));
    }
}

const MAX_NUMERIC_SEMVER_IDENTIFIER: &str = "18446744073709551615";
const MAX_NUMERIC_SEMVER_IDENTIFIER_DIGITS: usize = MAX_NUMERIC_SEMVER_IDENTIFIER.len();

fn validate_semver(version: &SemVer, path: &str, errors: &mut Vec<ValidationError>) {
    for (label, value) in [
        ("prerelease", version.prerelease.as_deref()),
        ("build_metadata", version.build_metadata.as_deref()),
    ] {
        if let Some(identifier) = value {
            let invalid = !identifier.is_ascii()
                || identifier.split('.').any(|part| {
                    let is_numeric = part.chars().all(|character| character.is_ascii_digit());
                    let invalid_numeric = matches!(label, "prerelease")
                        && is_numeric
                        && ((part.len() > 1 && part.starts_with('0'))
                            || part.len() > MAX_NUMERIC_SEMVER_IDENTIFIER_DIGITS
                            || (part.len() == MAX_NUMERIC_SEMVER_IDENTIFIER_DIGITS
                                && part > MAX_NUMERIC_SEMVER_IDENTIFIER));

                    part.is_empty()
                        || !part
                            .chars()
                            .all(|character| character.is_ascii_alphanumeric() || character == '-')
                        || invalid_numeric
                });
            if invalid {
                errors.push(err(
                    ValidationCode::InvalidSemVerIdentifier,
                    &format!("{path}.{label}"),
                    "semver identifiers must be dot-separated ASCII alphanumeric/hyphen segments",
                ));
            }
        }
    }
}

fn err(code: ValidationCode, path: &str, message: &str) -> ValidationError {
    ValidationError {
        code,
        path: path.to_string(),
        message: message.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn digest(seed: u8) -> Digest {
        Digest {
            algorithm: "sha256".to_string(),
            value: vec![seed; 32],
        }
    }

    fn semver(major: u32, minor: u32, patch: u32) -> SemVer {
        SemVer {
            major,
            minor,
            patch,
            prerelease: None,
            build_metadata: None,
        }
    }

    fn compat(
        min: SemVer,
        max: SemVer,
        required_capabilities: &[&str],
        platforms: &[&str],
    ) -> PackageCompatibility {
        PackageCompatibility {
            runtime_contract_min: min,
            runtime_contract_max: max,
            required_capabilities: required_capabilities
                .iter()
                .map(ToString::to_string)
                .collect(),
            supported_platforms: platforms.iter().map(ToString::to_string).collect(),
        }
    }

    fn package(
        kind: PackageKind,
        version: SemVer,
        schema_version: &str,
        digest: Digest,
        required: bool,
        compatibility: PackageCompatibility,
    ) -> PackageLocator {
        PackageLocator {
            package_id: digest.content_id(&kind),
            kind,
            package_version: version,
            schema_version: schema_version.to_string(),
            digest,
            relative_uri: "packages/example.bin".to_string(),
            uncompressed_size_bytes: 4096,
            required,
            compatibility,
        }
    }

    fn manifest(packages: Vec<PackageLocator>) -> UpdateManifest {
        UpdateManifest {
            manifest_id: "manifest/v2/stable".to_string(),
            manifest_version: semver(2, 0, 1),
            channel: "stable".to_string(),
            packages,
            manifest_digest: Some(digest(99)),
            full_snapshot: true,
            supersedes_manifest_ids: vec!["manifest/v2/previous".to_string()],
        }
    }

    fn target() -> CompatibilityTarget {
        CompatibilityTarget {
            runtime_contract: semver(2, 1, 0),
            schema_version: "v2.schema.1".to_string(),
            capabilities: ["fft".to_string(), "simd".to_string()]
                .into_iter()
                .collect(),
            platform: "desktop-linux".to_string(),
        }
    }

    fn local_store_entry(package: &PackageLocator) -> StoredPackage {
        StoredPackage {
            package_id: package.package_id.clone(),
            kind: package.kind.clone(),
            digest: package.digest.clone(),
            package_version: package.package_version.clone(),
            schema_version: package.schema_version.clone(),
            uncompressed_size_bytes: package.uncompressed_size_bytes,
            local_store_uri: format!("cas/{}", package.package_id),
        }
    }

    #[test]
    fn validate_manifest_accepts_valid_content_addressed_manifest() {
        let package = package(
            PackageKind::Scenario,
            semver(1, 0, 0),
            "v2.schema.1",
            digest(1),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 2, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let report = validate_manifest(&manifest(vec![package]));

        assert!(report.is_valid(), "report had errors: {:?}", report.errors);
    }

    #[test]
    fn validate_manifest_returns_sorted_deterministic_errors() {
        let mut package = package(
            PackageKind::CatalogPack,
            semver(1, 2, 0),
            "",
            Digest {
                algorithm: "SHA-256".to_string(),
                value: Vec::new(),
            },
            true,
            compat(semver(2, 2, 0), semver(2, 1, 0), &[], &[]),
        );
        package.package_id = "wrong-id".to_string();
        package.relative_uri = "https://cdn.example.com/pack.bin".to_string();
        package.uncompressed_size_bytes = 0;

        let bad = UpdateManifest {
            manifest_id: "".to_string(),
            manifest_version: SemVer {
                major: 2,
                minor: 0,
                patch: 0,
                prerelease: Some("alpha..1".to_string()),
                build_metadata: None,
            },
            channel: "".to_string(),
            packages: vec![package],
            manifest_digest: None,
            full_snapshot: true,
            supersedes_manifest_ids: vec!["".to_string(), "".to_string()],
        };

        let report = validate_manifest(&bad);
        let codes: Vec<ValidationCode> = report
            .errors
            .iter()
            .map(|error| error.code.clone())
            .collect();

        assert_eq!(
            codes,
            vec![
                ValidationCode::DuplicateManifestSupersedes,
                ValidationCode::EmptyManifestChannel,
                ValidationCode::EmptyManifestId,
                ValidationCode::InvalidDigestAlgorithm,
                ValidationCode::InvalidManifestSupersedes,
                ValidationCode::InvalidManifestSupersedes,
                ValidationCode::InvalidPackageIdentity,
                ValidationCode::InvalidRelativeUri,
                ValidationCode::InvalidRuntimeWindow,
                ValidationCode::InvalidSchemaVersion,
                ValidationCode::InvalidSemVerIdentifier,
                ValidationCode::MissingDigestValue,
                ValidationCode::UnsupportedPackagePlatform,
                ValidationCode::ZeroPackageSize,
            ]
        );
    }

    #[test]
    fn validate_semver_rejects_prerelease_numeric_with_leading_zero() {
        let mut version = semver(1, 2, 3);
        version.prerelease = Some("alpha.01".to_string());

        let mut errors = Vec::new();
        validate_semver(&version, "version", &mut errors);

        assert!(errors
            .iter()
            .any(|error| error.code == ValidationCode::InvalidSemVerIdentifier));
    }

    #[test]
    fn validate_semver_rejects_overlarge_numeric_prerelease_identifier() {
        let mut version = semver(1, 2, 3);
        version.prerelease = Some("18446744073709551616".to_string());

        let mut errors = Vec::new();
        validate_semver(&version, "version", &mut errors);

        assert!(errors
            .iter()
            .any(|error| error.code == ValidationCode::InvalidSemVerIdentifier));
    }

    #[test]
    fn select_compatible_packages_picks_latest_optional_per_kind() {
        let compatibility = compat(
            semver(2, 0, 0),
            semver(2, 9, 0),
            &["fft"],
            &["desktop-linux"],
        );

        let older_optional = package(
            PackageKind::CatalogPack,
            semver(1, 0, 0),
            "v2.schema.1",
            digest(3),
            false,
            compatibility.clone(),
        );
        let newer_optional = package(
            PackageKind::CatalogPack,
            semver(1, 2, 0),
            "v2.schema.1",
            digest(4),
            false,
            compatibility.clone(),
        );
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(5),
            true,
            compatibility,
        );

        let target = CompatibilityTarget {
            runtime_contract: semver(2, 1, 0),
            schema_version: "v2.schema.1".to_string(),
            capabilities: ["fft".to_string()].into_iter().collect(),
            platform: "desktop-linux".to_string(),
        };

        let resolved_manifest = manifest(vec![older_optional, newer_optional.clone(), required]);
        let selected = select_compatible_packages(&resolved_manifest, &target)
            .expect("selection should succeed");

        assert_eq!(selected.len(), 2);
        assert!(selected
            .iter()
            .any(|locator| locator.package_id == newer_optional.package_id));
    }

    #[test]
    fn select_compatible_packages_fails_when_required_package_is_incompatible() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.2",
            digest(6),
            true,
            compat(
                semver(3, 0, 0),
                semver(3, 1, 0),
                &["gpu"],
                &["desktop-linux"],
            ),
        );

        let target = CompatibilityTarget {
            runtime_contract: semver(2, 1, 0),
            schema_version: "v2.schema.1".to_string(),
            capabilities: BTreeSet::new(),
            platform: "desktop-linux".to_string(),
        };

        let err = select_compatible_packages(&manifest(vec![required.clone()]), &target)
            .expect_err("required package should fail");

        assert_eq!(err.required_package_failures.len(), 1);
        assert_eq!(
            err.required_package_failures[0].package_id,
            required.package_id
        );
        assert!(err.required_package_failures[0]
            .reason
            .contains("schema mismatch"));
    }

    #[test]
    fn semver_prerelease_ordering_matches_expected_precedence() {
        let stable = semver(1, 0, 0);
        let mut alpha = semver(1, 0, 0);
        alpha.prerelease = Some("alpha.1".to_string());

        let mut alpha_two = semver(1, 0, 0);
        alpha_two.prerelease = Some("alpha.2".to_string());

        assert!(alpha < alpha_two);
        assert!(alpha_two < stable);
    }

    #[test]
    fn compatibility_requires_all_capabilities_and_exact_platform() {
        let package = package(
            PackageKind::EphemerisBundle,
            semver(1, 0, 0),
            "v2.schema.1",
            digest(7),
            false,
            compat(
                semver(2, 0, 0),
                semver(2, 3, 0),
                &["fft", "simd"],
                &["desktop-linux", "desktop-macos"],
            ),
        );

        let insufficient = CompatibilityTarget {
            runtime_contract: semver(2, 1, 0),
            schema_version: "v2.schema.1".to_string(),
            capabilities: ["fft".to_string()].into_iter().collect(),
            platform: "desktop-linux".to_string(),
        };
        let wrong_platform = CompatibilityTarget {
            runtime_contract: semver(2, 1, 0),
            schema_version: "v2.schema.1".to_string(),
            capabilities: ["fft".to_string(), "simd".to_string()]
                .into_iter()
                .collect(),
            platform: "android-arm64".to_string(),
        };

        let insufficient_manifest = manifest(vec![package.clone()]);
        assert!(select_compatible_packages(&insufficient_manifest, &insufficient).is_ok());

        let wrong_platform_manifest = manifest(vec![package]);
        let selected = select_compatible_packages(&wrong_platform_manifest, &wrong_platform)
            .expect("optional incompatible package should just be skipped");
        assert!(selected.is_empty());
    }

    #[test]
    fn plan_manifest_update_snapshot_fetches_missing_and_installs_selected_packages() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(8),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let optional = package(
            PackageKind::CatalogPack,
            semver(1, 1, 0),
            "v2.schema.1",
            digest(9),
            false,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["simd"],
                &["desktop-linux"],
            ),
        );

        let update_plan = plan_manifest_update(
            &manifest(vec![required.clone(), optional.clone()]),
            &target(),
            &LocalDataState::empty(),
        )
        .expect("snapshot planning should succeed");

        assert_eq!(
            update_plan.selected_package_ids,
            vec![optional.package_id.clone(), required.package_id.clone()]
        );
        assert_eq!(update_plan.store_actions.len(), 2);
        assert!(update_plan
            .store_actions
            .contains(&UpdatePlanStoreAction::FetchPackage {
                package_id: required.package_id.clone(),
                package_kind: required.kind.clone(),
                package_version: required.package_version.clone(),
                source_relative_uri: required.relative_uri.clone(),
                digest: required.digest.clone(),
                uncompressed_size_bytes: required.uncompressed_size_bytes,
            }));
        assert!(update_plan
            .store_actions
            .contains(&UpdatePlanStoreAction::FetchPackage {
                package_id: optional.package_id.clone(),
                package_kind: optional.kind.clone(),
                package_version: optional.package_version.clone(),
                source_relative_uri: optional.relative_uri.clone(),
                digest: optional.digest.clone(),
                uncompressed_size_bytes: optional.uncompressed_size_bytes,
            }));
        assert_eq!(
            update_plan.activation_actions,
            vec![
                UpdatePlanActivationAction::InstallPackage {
                    package_id: optional.package_id,
                },
                UpdatePlanActivationAction::InstallPackage {
                    package_id: required.package_id,
                },
            ]
        );
    }

    #[test]
    fn plan_manifest_update_reuses_existing_store_entries_and_plans_removals() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(10),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let old_installed = package(
            PackageKind::CatalogPack,
            semver(1, 0, 0),
            "v2.schema.1",
            digest(11),
            false,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["simd"],
                &["desktop-linux"],
            ),
        );
        let mut store = BTreeMap::new();
        store.insert(required.package_id.clone(), local_store_entry(&required));
        store.insert(
            old_installed.package_id.clone(),
            local_store_entry(&old_installed),
        );

        let local = LocalDataState {
            package_store: PackageStoreState {
                packages_by_id: store,
            },
            installed_manifest: Some(InstalledManifestState {
                manifest_id: "manifest/v2/old".to_string(),
                manifest_version: semver(1, 9, 0),
                channel: "stable".to_string(),
                manifest_digest: None,
                installed_package_ids: [
                    required.package_id.clone(),
                    old_installed.package_id.clone(),
                ]
                .into_iter()
                .collect(),
            }),
        };

        let update_plan =
            plan_manifest_update(&manifest(vec![required.clone()]), &target(), &local)
                .expect("planning should succeed");

        assert!(update_plan.store_actions.is_empty());
        assert_eq!(
            update_plan.activation_actions,
            vec![UpdatePlanActivationAction::RemovePackage {
                package_id: old_installed.package_id
            }]
        );
    }

    #[test]
    fn plan_manifest_update_delta_requires_baseline_and_supersede_match() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(12),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );

        let mut delta_manifest = manifest(vec![required]);
        delta_manifest.full_snapshot = false;
        delta_manifest.supersedes_manifest_ids = vec!["manifest/v2/old".to_string()];

        let missing_baseline =
            plan_manifest_update(&delta_manifest, &target(), &LocalDataState::empty())
                .expect_err("delta planning requires an installed baseline");
        assert_eq!(
            missing_baseline,
            UpdatePlanError::MissingBaselineForDelta {
                manifest_id: delta_manifest.manifest_id.clone(),
            }
        );

        let local = LocalDataState {
            package_store: PackageStoreState::empty(),
            installed_manifest: Some(InstalledManifestState {
                manifest_id: "manifest/v2/unrelated".to_string(),
                manifest_version: semver(1, 0, 0),
                channel: "stable".to_string(),
                manifest_digest: None,
                installed_package_ids: BTreeSet::new(),
            }),
        };
        let supersede_error = plan_manifest_update(&delta_manifest, &target(), &local)
            .expect_err("delta planning should reject unrelated baseline");
        assert_eq!(
            supersede_error,
            UpdatePlanError::DeltaDoesNotSupersedeInstalled {
                manifest_id: delta_manifest.manifest_id,
                installed_manifest_id: "manifest/v2/unrelated".to_string(),
            }
        );
    }

    #[test]
    fn plan_manifest_update_is_deterministic_for_unsorted_manifest_entries() {
        let third = package(
            PackageKind::CatalogPack,
            semver(1, 3, 0),
            "v2.schema.1",
            digest(13),
            true,
            compat(semver(2, 0, 0), semver(2, 5, 0), &[], &["desktop-linux"]),
        );
        let first = package(
            PackageKind::Scenario,
            semver(1, 1, 0),
            "v2.schema.1",
            digest(14),
            true,
            compat(semver(2, 0, 0), semver(2, 5, 0), &[], &["desktop-linux"]),
        );
        let second = package(
            PackageKind::EphemerisBundle,
            semver(1, 2, 0),
            "v2.schema.1",
            digest(15),
            true,
            compat(semver(2, 0, 0), semver(2, 5, 0), &[], &["desktop-linux"]),
        );

        let update_plan = plan_manifest_update(
            &manifest(vec![third.clone(), first.clone(), second.clone()]),
            &target(),
            &LocalDataState::empty(),
        )
        .expect("planning should succeed");

        assert_eq!(
            update_plan.selected_package_ids,
            vec![
                third.package_id.clone(),
                second.package_id.clone(),
                first.package_id.clone()
            ]
        );
        let fetch_ids: Vec<String> = update_plan
            .store_actions
            .iter()
            .map(|action| match action {
                UpdatePlanStoreAction::FetchPackage { package_id, .. } => package_id.clone(),
            })
            .collect();
        assert_eq!(
            fetch_ids,
            vec![third.package_id, second.package_id, first.package_id]
        );
    }

    #[test]
    fn apply_update_plan_commits_state_and_provenance_for_fetch_and_reuse() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(16),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let optional = package(
            PackageKind::CatalogPack,
            semver(1, 1, 0),
            "v2.schema.1",
            digest(17),
            false,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["simd"],
                &["desktop-linux"],
            ),
        );
        let legacy = package(
            PackageKind::EphemerisBundle,
            semver(1, 0, 0),
            "v2.schema.1",
            digest(18),
            false,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["simd"],
                &["desktop-linux"],
            ),
        );

        let mut store = BTreeMap::new();
        store.insert(required.package_id.clone(), local_store_entry(&required));
        store.insert(legacy.package_id.clone(), local_store_entry(&legacy));
        let local = LocalDataState {
            package_store: PackageStoreState {
                packages_by_id: store,
            },
            installed_manifest: Some(InstalledManifestState {
                manifest_id: "manifest/v2/old".to_string(),
                manifest_version: semver(1, 9, 0),
                channel: "stable".to_string(),
                manifest_digest: None,
                installed_package_ids: [required.package_id.clone(), legacy.package_id.clone()]
                    .into_iter()
                    .collect(),
            }),
        };

        let plan = plan_manifest_update(
            &manifest(vec![required.clone(), optional.clone()]),
            &target(),
            &local,
        )
        .expect("planning should succeed");

        let mut fetched = BTreeMap::new();
        fetched.insert(optional.package_id.clone(), local_store_entry(&optional));
        let result = apply_update_plan(
            &plan,
            &local,
            &ApplyPackageInputs {
                fetched_packages_by_id: fetched,
            },
        )
        .expect("apply should succeed");

        assert_eq!(
            result
                .committed_state
                .installed_manifest
                .as_ref()
                .expect("installed manifest should exist")
                .installed_package_ids,
            [required.package_id.clone(), optional.package_id.clone()]
                .into_iter()
                .collect()
        );
        assert!(result
            .committed_state
            .package_store
            .packages_by_id
            .contains_key(&required.package_id));
        assert!(result
            .committed_state
            .package_store
            .packages_by_id
            .contains_key(&optional.package_id));
        assert!(result
            .committed_state
            .package_store
            .packages_by_id
            .contains_key(&legacy.package_id));

        assert_eq!(result.provenance.fetched_packages.len(), 1);
        assert_eq!(
            result.provenance.fetched_packages[0].package_id,
            optional.package_id
        );
        assert_eq!(
            result.provenance.reused_package_ids,
            vec![required.package_id]
        );
        assert_eq!(
            result.provenance.installed_package_ids,
            vec![optional.package_id.clone()]
        );
        assert_eq!(
            result.provenance.removed_package_ids,
            vec![legacy.package_id]
        );
    }

    #[test]
    fn apply_update_plan_fails_when_expected_fetch_is_missing() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(19),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let local = LocalDataState::empty();
        let plan = plan_manifest_update(&manifest(vec![required]), &target(), &local)
            .expect("planning should succeed");

        let err = apply_update_plan(&plan, &local, &ApplyPackageInputs::empty())
            .expect_err("apply should fail without fetched package content");
        assert_eq!(
            err,
            ApplyUpdateError::MissingFetchedPackage {
                package_id: plan.selected_package_ids[0].clone(),
            }
        );
    }

    #[test]
    fn apply_update_plan_rejects_fetched_metadata_mismatch() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(20),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let local = LocalDataState::empty();
        let plan = plan_manifest_update(&manifest(vec![required.clone()]), &target(), &local)
            .expect("planning should succeed");

        let mut mismatched = local_store_entry(&required);
        mismatched.uncompressed_size_bytes = required.uncompressed_size_bytes + 1;
        let mut fetched = BTreeMap::new();
        fetched.insert(required.package_id.clone(), mismatched);

        let err = apply_update_plan(
            &plan,
            &local,
            &ApplyPackageInputs {
                fetched_packages_by_id: fetched,
            },
        )
        .expect_err("apply should fail on mismatched fetched package metadata");
        assert_eq!(
            err,
            ApplyUpdateError::FetchedPackageMismatch {
                package_id: required.package_id,
                reason: format!(
                    "size mismatch: expected {} got {}",
                    required.uncompressed_size_bytes,
                    required.uncompressed_size_bytes + 1
                ),
            }
        );
    }

    #[test]
    fn apply_update_plan_rejects_activation_when_selected_set_not_reached() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(21),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let local = LocalDataState::empty();
        let planned = plan_manifest_update(&manifest(vec![required.clone()]), &target(), &local)
            .expect("planning should succeed");
        let plan = UpdatePlan {
            manifest_id: planned.manifest_id.clone(),
            manifest_version: planned.manifest_version.clone(),
            channel: planned.channel.clone(),
            manifest_digest: planned.manifest_digest.clone(),
            selected_package_ids: planned.selected_package_ids.clone(),
            store_actions: planned.store_actions.clone(),
            activation_actions: Vec::new(),
        };

        let mut fetched = BTreeMap::new();
        fetched.insert(
            planned.selected_package_ids[0].clone(),
            local_store_entry(&required),
        );

        let err = apply_update_plan(
            &plan,
            &local,
            &ApplyPackageInputs {
                fetched_packages_by_id: fetched,
            },
        )
        .expect_err("apply should fail if activation does not produce selected set");

        assert_eq!(
            err,
            ApplyUpdateError::InstalledSetDoesNotMatchSelection {
                expected_selected: planned.selected_package_ids.clone(),
                actual_installed: Vec::new(),
            }
        );
    }

    #[test]
    fn apply_update_plan_rejects_store_action_for_unselected_package() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(22),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );
        let stray = package(
            PackageKind::CatalogPack,
            semver(1, 0, 0),
            "v2.schema.1",
            digest(23),
            false,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["simd"],
                &["desktop-linux"],
            ),
        );

        let local = LocalDataState::empty();
        let planned = plan_manifest_update(&manifest(vec![required.clone()]), &target(), &local)
            .expect("planning should succeed");
        let mut plan = planned.clone();
        plan.store_actions
            .push(UpdatePlanStoreAction::FetchPackage {
                package_id: stray.package_id.clone(),
                package_kind: stray.kind.clone(),
                package_version: stray.package_version.clone(),
                source_relative_uri: stray.relative_uri.clone(),
                digest: stray.digest.clone(),
                uncompressed_size_bytes: stray.uncompressed_size_bytes,
            });

        let err = apply_update_plan(&plan, &local, &ApplyPackageInputs::empty())
            .expect_err("apply should reject stray fetch actions");

        assert_eq!(
            err,
            ApplyUpdateError::StoreActionReferencesUnselectedPackage {
                package_id: stray.package_id,
            }
        );
    }

    #[test]
    fn apply_update_plan_rejects_conflicting_activation_actions() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(24),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );

        let local = LocalDataState {
            package_store: PackageStoreState::empty(),
            installed_manifest: Some(InstalledManifestState {
                manifest_id: "manifest/v2/current".to_string(),
                manifest_version: semver(2, 0, 0),
                channel: "stable".to_string(),
                manifest_digest: None,
                installed_package_ids: [required.package_id.clone()].into_iter().collect(),
            }),
        };
        let plan = UpdatePlan {
            manifest_id: "manifest/v2/current".to_string(),
            manifest_version: semver(2, 0, 1),
            channel: "stable".to_string(),
            manifest_digest: None,
            selected_package_ids: [required.package_id.clone()].into_iter().collect(),
            store_actions: Vec::new(),
            activation_actions: vec![
                UpdatePlanActivationAction::InstallPackage {
                    package_id: required.package_id.clone(),
                },
                UpdatePlanActivationAction::RemovePackage {
                    package_id: required.package_id.clone(),
                },
            ],
        };

        let err = apply_update_plan(&plan, &local, &ApplyPackageInputs::empty())
            .expect_err("apply should reject conflicting install/remove actions");

        assert_eq!(
            err,
            ApplyUpdateError::ConflictingActivationAction {
                package_id: required.package_id,
            }
        );
    }

    #[test]
    fn apply_update_plan_rejects_remove_action_for_uninstalled_package() {
        let required = package(
            PackageKind::Scenario,
            semver(2, 0, 0),
            "v2.schema.1",
            digest(25),
            true,
            compat(
                semver(2, 0, 0),
                semver(2, 5, 0),
                &["fft"],
                &["desktop-linux"],
            ),
        );

        let local = LocalDataState::empty();
        let plan = UpdatePlan {
            manifest_id: "manifest/v2/current".to_string(),
            manifest_version: semver(2, 0, 1),
            channel: "stable".to_string(),
            manifest_digest: None,
            selected_package_ids: Vec::new(),
            store_actions: Vec::new(),
            activation_actions: vec![UpdatePlanActivationAction::RemovePackage {
                package_id: required.package_id.clone(),
            }],
        };

        let err = apply_update_plan(&plan, &local, &ApplyPackageInputs::empty())
            .expect_err("apply should reject removing an uninstalled package");

        assert_eq!(
            err,
            ApplyUpdateError::RemoveActionNotInstalled {
                package_id: required.package_id,
            }
        );
    }
}
