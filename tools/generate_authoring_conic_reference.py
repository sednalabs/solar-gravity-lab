#!/usr/bin/env python3
"""Generate independent conic placement states with NASA NAIF CSPICE.

Offline fixture regeneration only: python -m pip install spiceypy==6.0.0
No SPICE kernels or SGL modules are loaded. CSPICE constructs states from mean
anomaly and recovers true anomaly independently; Rust consumes the checked-in
JSON and never requires this Python dependency at build/run time.
"""
from pathlib import Path
import hashlib
import json
import math

import spiceypy as spice


def main():
    root = Path(__file__).resolve().parents[1]
    host_radius = 6_371_000.0
    host_mass = 5.972e24
    mu_m = 6.67430e-11 * host_mass
    # id, periapsis altitude (m), e, i, node, arg periapsis, mean anomaly.
    cases = [
        ("equatorial-circle", 400_000, 0, 0, 0, 0, 0.9),
        ("polar-circle", 800_000, 0, math.pi / 2, 0.7, 1.2, -0.6),
        ("retrograde-circle", 1_000_000, 0, math.pi, 1.1, 0.3, 1.4),
        ("inclined-ellipse", 400_000, 0.65, 0.7, 1.2, 0.4, 0.3),
        ("elliptic-apoapsis", 600_000, 0.6, 0, 0, 0, math.pi),
        ("retrograde-ellipse", 500_000, 0.4, 2.2, 5.7, 2.5, -1.1),
        ("near-parabolic-ellipse", 400_000, 0.999, 1.2, 0.8, 2.4, 0.001),
        ("incoming-hyperbola", 150_000, 1.5, 0.8, 0.6, 1.9, -0.7),
        ("outgoing-hyperbola", 300_000, 2.1, 2.6, 4.4, 5.5, 1.2),
        ("incoming-parabola", 800_000, 1, 1.3, 1.9, 0.9, -0.6),
        ("outgoing-parabola", 800_000, 1, 0.4, 2.3, 1.2, 0.7),
        ("tilted-periapsis", 700_000, 0.3, math.pi / 2, math.pi / 2, math.pi / 2, 0),
    ]
    rows = []
    for name, altitude, eccentricity, inclination, node, periapsis, mean in cases:
        elements = [(host_radius + altitude) / 1000, eccentricity, inclination,
                    node, periapsis, mean, 0, mu_m * 1e-9]
        state_km = spice.conics(elements, 0)
        # At e=0 periapsis is arbitrary; preserve the supplied reference phase.
        anomaly = mean if eccentricity == 0 else float(spice.oscltx(state_km, 0, mu_m * 1e-9)[8])
        if anomaly > math.pi:
            anomaly -= math.tau
        if not all(math.isfinite(float(v)) for v in state_km):
            raise ValueError(f"Non-finite reference state: {name}")
        rows.append({
            "id": name,
            "reference_mean_anomaly_rad": mean,
            "placement": {
                "kind": "conic_orbit", "host_body_id": "reference.primary",
                "periapsis_altitude_m": altitude, "eccentricity": eccentricity,
                "true_anomaly_rad": anomaly, "inclination_rad": inclination,
                "ascending_node_longitude_rad": node,
                "argument_of_periapsis_rad": periapsis,
            },
            "position_m": [float(v * 1000) for v in state_km[:3]],
            "velocity_mps": [float(v * 1000) for v in state_km[3:]],
        })
    fixture = {
        "oracle": "NASA NAIF CSPICE conics + oscltx",
        "cspice_version": spice.tkvrsn("TOOLKIT"),
        "spiceypy_version": spice.__version__,
        "reference": "https://naif.jpl.nasa.gov/pub/naif/toolkit_docs/C/cspice/conics_c.html",
        "generator_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "scope": "Independent synthetic two-body element-to-state conversion; no ephemeris kernels or trajectory accuracy claim.",
        "host_radius_m": host_radius, "host_source_mass_kg": host_mass,
        "gravitational_parameter_m3ps2": mu_m,
        "cases": rows,
    }
    output = root / "engine/runtime/tests/fixtures/authoring-conics-cspice.json"
    output.write_text(json.dumps(fixture, indent=2, allow_nan=False) + "\n")
    print(f"Wrote {len(rows)} independent conic states to {output.relative_to(root)}")


if __name__ == "__main__":
    main()
