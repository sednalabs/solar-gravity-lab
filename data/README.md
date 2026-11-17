# Offline-first data architecture

Solar Gravity Lab uses **bundled offline** scientific data today.
Versioned, signed *live* updates are an accepted **architecture goal**, not
an implemented production distribution service. The engine and scenario
packages must remain useful without network access.

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
