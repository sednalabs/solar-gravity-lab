Optional user-supplied catalog assets for SolarLab.

If present, the app loads these before building the default scenario and merges them over the
bundled starter catalog by body id.

Supported files:
- planetary_moons_v1.tsv
- small_bodies_curated_v1.tsv

Both use the same tab-separated schema. See the *.template.tsv files in this directory.

Notes:
- category should be MOON for moons and ASTEROID/COMET for small bodies.
- role should be MASSIVE or TRACER.
- host_body_id must refer to an already-known body id (e.g. earth, jupiter, saturn, sun, pluto).
- epoch_jd_tdb is the reference epoch for the orbital elements.
- a_m is semi-major axis in meters.
- angular values are in degrees.
