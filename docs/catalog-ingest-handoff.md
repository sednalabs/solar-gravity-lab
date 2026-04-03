# Catalog ingest handoff

This repo now supports three catalog layers for the default scenario:

1. Built-in approximate planet / dwarf-planet layer
2. Built-in starter moon + curated small-body layer
3. Optional imported TSV catalog rows loaded from app assets

## Runtime asset paths

- `app/src/main/assets/catalogs/planetary_moons_v1.tsv`
- `app/src/main/assets/catalogs/small_bodies_curated_v1.tsv`

If present, these are parsed by `OrbitingBodyCatalogParser` and merged over the bundled starter
catalog by `body_id`.

## TSV schema

```
body_id	name	category	role	host_body_id	mass_kg	radius_m	color_argb	epoch_jd_tdb	a_m	e	i_deg	node_deg	peri_deg	mean_deg	enabled_by_default	notes
```

- `category`: `MOON`, `ASTEROID`, or `COMET`
- `role`: `MASSIVE` or `TRACER`
- `host_body_id`: `sun`, `earth`, `jupiter`, `saturn`, `uranus`, `neptune`, `pluto`, etc.
- `epoch_jd_tdb`: reference epoch for the orbital elements
- `a_m`: semi-major axis in meters
- all angular fields are in degrees
- `color_argb`: `RRGGBB` or `AARRGGBB`

## Current limitations

The app currently propagates imported rows as simple Keplerian two-body orbits around their host.
That is good enough for a strong first implementation and local testing, but it is not a
replacement for full DE/SPICE/Horizons state packs.

## Recommended unrestricted-agent follow-up

1. Generate a full planetary-moon TSV from real sources.
2. Generate a curated asteroid/comet TSV from SBDB + Horizons vectors / element exports.
3. Replace the simple TSV path with a compact binary pack once the field set stabilises.
4. Add host-relative visual presets and moon-system filters in the app UI.
