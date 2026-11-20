#!/usr/bin/env python3
"""Independent DOP853 event-time oracle. Does not import or invoke SGL.

Integrates Cartesian point-mass motion and locates radial-velocity zeroes.
The fixture is consumed by Rust without Python/SciPy at test time. These are
synthetic conics, not planetary ephemerides or a full-gravity forecast.
"""
from pathlib import Path
import hashlib
import json

import numpy as np
import scipy
from scipy.integrate import solve_ivp

CASES = [
    ("earth-departing-tilted", 3.986004418e14,
     [8e6, -2e6, 3e6, 1800, 6500, 900]),
    ("earth-incoming-retrograde", 3.986004418e14,
     [8e6, -2e6, 3e6, -1800, -6500, -900]),
    ("earth-high-eccentricity-before-apoapsis", 3.986004418e14,
     [78e6, 1e6, 0, 30, 400, 0]),
    ("earth-high-eccentricity-after-apoapsis", 3.986004418e14,
     [78e6, 1e6, 0, -30, -400, 0]),
    ("lunar-hyperbolic-approach", 4.9048695e12,
     [-8e6, 2.1e6, 0.5e6, 2200, -100, 50]),
    ("lunar-hyperbolic-departure", 4.9048695e12,
     [-8e6, 2.1e6, 0.5e6, -2200, 100, -50]),
    ("earth-near-circular-distinct-apsides", 3.986004418e14,
     [7e6, 0, 0, 0.02, 7546.1, 1]),
]


# Initial state construction is separate from the event-time oracle: the oracle
# integrates Cartesian acceleration and locates r dot v = 0, never Kepler time.
for eccentricity in [1.0 - 2e-8, 1.0 + 2e-8]:
    for anomaly in [-0.005, -0.9]:
        semilatus = 7e6 * (1.0 + eccentricity)
        radius = semilatus / (1.0 + eccentricity * np.cos(anomaly))
        speed_scale = np.sqrt(3.986004418e14 / semilatus)
        CASES.append((f"near-escape-{eccentricity:.8f}-{anomaly}", 3.986004418e14,
                      [radius * np.cos(anomaly), radius * np.sin(anomaly), 0.0,
                       -speed_scale * np.sin(anomaly), speed_scale * (eccentricity + np.cos(anomaly)), 0.0]))


def integrate(mu, state, refinement, bounded_horizon=False):
    position, velocity = state[:3], state[3:]
    energy = np.dot(velocity, velocity) / 2 - mu / np.linalg.norm(position)
    horizon = 30000.0 if bounded_horizon or energy >= 0 else 1.05 * 2 * np.pi * (-mu / (2 * energy)) ** 1.5 / np.sqrt(mu)

    def rhs(_time, y):
        radius = np.linalg.norm(y[:3])
        return np.concatenate((y[3:], -mu * y[:3] / radius ** 3))

    def periapsis(_time, y):
        return np.dot(y[:3], y[3:])

    def apoapsis(_time, y):
        return np.dot(y[:3], y[3:])

    periapsis.direction = 1
    apoapsis.direction = -1
    # Maximum steps cap interpolation error at event roots, separately from the
    # integrator's local tolerance. The second run halves it and tightens rtol.
    maximum_step = min(30.0, horizon / 2048) / (2 if refinement else 1)
    result = solve_ivp(rhs, [0, horizon], state, method="DOP853",
                       rtol=3e-14 if refinement else 2e-13,
                       atol=[1e-7] * 3 + [1e-10] * 3,
                       max_step=maximum_step, events=[periapsis, apoapsis])
    if not result.success:
        raise RuntimeError(result.message)
    return [(float(times[0]), values[0, :3].tolist()) if len(times) else None
            for times, values in zip(result.t_events, result.y_events)]


def main():
    rows = []
    largest_time_difference = 0.0
    largest_position_difference = 0.0
    for name, mu, initial in CASES:
        state = np.asarray(initial, dtype=float)
        regular = integrate(mu, state, False, name.startswith("near-escape"))
        refined = integrate(mu, state, True, name.startswith("near-escape"))
        events = []
        for first, second in zip(regular, refined):
            if first is None or second is None:
                if first != second:
                    raise ValueError(f"event disagreement: {name}")
                events.append(None)
                continue
            time_difference = abs(first[0] - second[0])
            position_difference = float(np.linalg.norm(np.array(first[1]) - second[1]))
            largest_time_difference = max(largest_time_difference, time_difference)
            largest_position_difference = max(largest_position_difference, position_difference)
            if time_difference > 0.001 or position_difference > 0.02:
                raise ValueError(f"reference failed refinement: {name} {time_difference} {position_difference}")
            events.append({"seconds": second[0], "position_m": second[1]})
        rows.append({"name": name, "mu": mu, "position_m": initial[:3],
                     "velocity_mps": initial[3:], "periapsis": events[0], "apoapsis": events[1],
                     "checked_events": ["periapsis"] if name.startswith("near-escape") else ["periapsis", "apoapsis"]})
    document = {
        "schema_version": 1,
        "method": "independent Cartesian DOP853 with radial-velocity event roots",
        "numpy_version": np.__version__, "scipy_version": scipy.__version__,
        "generator_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "refinement_max_time_difference_seconds": largest_time_difference,
        "refinement_max_position_difference_m": largest_position_difference,
        "cases": rows,
    }
    output = Path(__file__).resolve().parents[1] / "tests/fixtures/navigation-reference.json"
    output.write_text(json.dumps(document, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps({"cases": len(rows), "max_time_refinement_seconds": largest_time_difference,
                      "max_position_refinement_m": largest_position_difference}, indent=2))


if __name__ == "__main__":
    main()
