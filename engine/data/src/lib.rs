use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};

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

fn validate_semver(version: &SemVer, path: &str, errors: &mut Vec<ValidationError>) {
    for (label, value) in [
        ("prerelease", version.prerelease.as_deref()),
        ("build_metadata", version.build_metadata.as_deref()),
    ] {
        if let Some(identifier) = value {
            let invalid = !identifier.is_ascii()
                || identifier.split('.').any(|part| {
                    part.is_empty()
                        || !part
                            .chars()
                            .all(|character| character.is_ascii_alphanumeric() || character == '-')
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
}
