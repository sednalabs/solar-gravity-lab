# solarlab.v2 protobuf contracts

This package defines the first real v2 data-plane/runtime boundary for
offline-first scientific simulation.

## Contract goals

- Preserve deterministic runtime state and checkpoint identity.
- Keep package/update metadata verifiable and versionable.
- Keep payloads friendly to cross-language clients (Rust/Kotlin/Swift/Web).
- Carry provenance from package ingest to diagnostics and scene outputs.

## Message groups

- `data.proto`: signed package contracts (`EphemerisBundle`, `CatalogPack`,
  `UpdateManifest`) including chunk/digest/signature metadata.
- `runtime.proto`: typed runtime command envelope and checkpoint/snapshot
  surfaces.
- `scene.proto`: backend-neutral render scene and delta contracts with typed
  bodies/trails/tracers/camera/light state.
- `diagnostics.proto`: runtime+hardware metrics and package verification state.
- `scenario.proto`: scenario package requirements and runtime presets.
- `common.proto`: shared versioning, digest/signature, provenance, vectors,
  and core enums.

## Evolution rules (v2)

- Do not reuse or renumber existing fields.
- Prefer adding new optional/repeated fields over replacing existing ones.
- Use `Digest` + `Signature` metadata for every package/update artifact.

## Contract decisions (authoritative)

- `WorldCommandEnvelope` is typed-only. Legacy fallback fields
  (`command_kind`, `command_payload`) are removed and field numbers are
  reserved, so a command has one unambiguous representation: `typed_command`.
- `UpdateManifest` is package-locator driven. Legacy duplicate fields
  (`published_at`, `compatible_schema_versions`, `package_ids`) are removed and
  reserved. Authority lives in `published_at_utc`, `manifest_version`, and
  `packages`.
- Compatibility bounds use structured `SemVer` (`runtime_contract_min`,
  `runtime_contract_max`) rather than free-form strings.
- `RenderSceneDelta` supports light changes explicitly via
  `updated_light_count`, `light_upserts`, and `removed_light_ids`, matching the
  `RenderScene.lights` surface.
