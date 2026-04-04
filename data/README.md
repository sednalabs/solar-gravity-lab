# Offline-first data architecture

Solar Gravity Lab v2 uses offline-first scientific content with signed live
updates.

Primary package types:

- `ScenarioPackage`
- `EphemerisBundle`
- `CatalogPack`
- `UpdateManifest`

Required properties:

- reproducible ingest
- provenance preserved into runtime diagnostics
- content-addressable storage
- signature verification
- background update capability without making network connectivity mandatory

The protobuf contracts for these packages live under `proto/solarlab/v2`.
