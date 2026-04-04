# Optional service surfaces

Solar Gravity Lab v2 is offline-first, but not offline-only.

The service layer is intentionally narrow:

- publish signed update manifests
- host bundle and catalog payloads
- support incremental content refresh
- expose provenance and compatibility metadata

The runtime must remain functional without these services. Services enhance the
product; they do not define whether the simulation can operate.
