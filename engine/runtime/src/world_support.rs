//! Narrow adapters between persisted world bodies, solver state and package manifests.
//! New force models should extend the solver boundary, not add competing transforms.
use super::*;

pub(super) fn package_provenance_digest(mounted_packages: &[MountedPackageState]) -> Option<Digest> {
    match mounted_packages {
        [] => None,
        [only_package] => Some(only_package.digest.clone()),
        multiple_packages => {
            let mut value = Vec::new();
            for package in multiple_packages {
                value.extend_from_slice(package.package_id.as_bytes());
                value.push(0);
                value.extend_from_slice(package.digest.algorithm.as_bytes());
                value.push(0);
                value.extend_from_slice(package.digest.hex_value().as_bytes());
                value.push(b'\n');
            }
            Some(Digest {
                algorithm: "mounted-set/v1".to_owned(),
                value,
            })
        }
    }
}

pub(super) fn mounted_manifest_from_state(
    local_data_state: &LocalDataState,
    mounted_package_ids: &BTreeSet<String>,
) -> Result<Option<MountedManifestState>, RuntimeError> {
    let Some(installed_manifest) = &local_data_state.installed_manifest else {
        return Ok(None);
    };

    let mut mounted_packages = Vec::with_capacity(mounted_package_ids.len());
    for package_id in mounted_package_ids {
        if !installed_manifest
            .installed_package_ids
            .contains(package_id)
        {
            return Err(RuntimeError::PackageNotInstalled(package_id.clone()));
        }

        let stored = local_data_state
            .package_store
            .packages_by_id
            .get(package_id)
            .ok_or_else(|| RuntimeError::MountedPackageMissingFromStore(package_id.clone()))?;

        mounted_packages.push(MountedPackageState {
            package_id: stored.package_id.clone(),
            kind: stored.kind.clone(),
            package_version: stored.package_version.clone(),
            schema_version: stored.schema_version.clone(),
            digest: stored.digest.clone(),
            local_store_uri: stored.local_store_uri.clone(),
        });
    }

    Ok(Some(MountedManifestState {
        manifest_id: installed_manifest.manifest_id.clone(),
        manifest_version: installed_manifest.manifest_version.clone(),
        channel: installed_manifest.channel.clone(),
        manifest_digest: installed_manifest.manifest_digest.clone(),
        mounted_packages,
    }))
}

pub(super) fn world_bodies_to_solver_state(bodies: &[BodyState]) -> Vec<MassiveBodyState> {
    bodies
        .iter()
        .map(|body| MassiveBodyState {
            mass_kg: body.source_mass_kg(),
            position_m: body.position_m,
            velocity_mps: body.velocity_mps,
        })
        .collect()
}

pub(super) fn apply_solver_state_to_world_bodies(bodies: &mut [BodyState], solved: &[MassiveBodyState]) {
    for (body, solved_body) in bodies.iter_mut().zip(solved.iter()) {
        body.position_m = solved_body.position_m;
        body.velocity_mps = solved_body.velocity_mps;
    }
}

pub(super) fn compute_world_invariants(bodies: &[BodyState]) -> PhysicsInvariants {
    let solver_bodies = world_bodies_to_solver_state(bodies);
    compute_invariants(&solver_bodies)
}
