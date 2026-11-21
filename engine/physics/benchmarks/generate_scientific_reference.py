#!/usr/bin/env python3
"""Independent offline trajectory oracle; never imports/calls SGL.

Generate the checked-in fixture with SciPy's DOP853 and verify convergence with
a second, tighter DOP853 integration. Rust tests consume the CSV without Python.
The two scenarios have a Sun/Earth/Moon-like source system and a massless craft.
These are synthetic inertial initial conditions, not fitted planetary ephemerides.
J2 is evaluated in a body-axis basis, independently of SGL's vector expression.
"""
from pathlib import Path
import csv
import hashlib
import json

import numpy as np
import scipy
from scipy.integrate import solve_ivp

G = 6.67430e-11
MU = np.array([1.32712440018e20, 3.986004418e14, 4.9048695e12, 0.0])
TIMES = np.array([0.0, 600.0, 1800.0, 3600.0, 7200.0, 14400.0])
# An inertial frame translated and boosted to Earth's initial state. It does not
# follow the accelerating Earth. Earth is still integrated as a massive body.
INITIAL = np.array([
    [-1.495978707e11, 0.0, 0.0, 0.0, -29780.0, 0.0],
    [0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
    [3.844e8, 0.0, 0.0, 0.0, 1022.0, 0.0],
    [8e6, 0.0, 0.0, 0.0, 6000.0, 4000.0],
])
MODELS = [
    (1, 1.08262668e-3, 6378137.0, [0.36, -0.48, 0.8]),
    (2, 2.032e-4, 1737400.0, [0.0, 0.8, 0.6]),
]


def basis(pole):
    z = np.array(pole, dtype=float)
    x = np.cross(np.array([1.0, 0.0, 0.0]), z)
    x /= np.linalg.norm(x)
    y = np.cross(z, x)
    return np.column_stack([x, y, z])


def rhs(models):
    compiled = [(i, j2, radius, basis(pole)) for i, j2, radius, pole in models]

    def derivative(_time, flattened):
        state = flattened.reshape((-1, 6))
        positions = state[:, :3]
        acceleration = np.zeros((4, 3))
        for target in range(4):
            for source in range(3):
                if source != target:
                    separation = positions[source] - positions[target]
                    acceleration[target] += MU[source] * separation / np.linalg.norm(separation) ** 3
        for source, j2, radius, rotation in compiled:
            for target in range(4):
                if target == source:
                    continue
                x, y, z = rotation.T @ (positions[target] - positions[source])
                r2 = x * x + y * y + z * z
                if r2 < radius * radius:
                    raise ValueError("reference left exterior harmonic domain")
                common = 1.5 * MU[source] * j2 * radius * radius / r2 ** 2.5
                latitude = 5.0 * z * z / r2
                field = rotation @ (common * np.array([
                    x * (latitude - 1.0), y * (latitude - 1.0), z * (latitude - 3.0),
                ]))
                acceleration[target] += field
                acceleration[source] -= field * MU[target] / MU[source]
        return np.column_stack([state[:, 3:], acceleration]).reshape(-1)

    return derivative


def integrate(models, rtol, position_atol, velocity_atol, max_step):
    solution = solve_ivp(rhs(models), [TIMES[0], TIMES[-1]], INITIAL.reshape(-1),
        method="DOP853", t_eval=TIMES, rtol=rtol,
        atol=np.tile([position_atol] * 3 + [velocity_atol] * 3, 4), max_step=max_step)
    if not solution.success or not np.isfinite(solution.y).all():
        raise RuntimeError(solution.message)
    return solution.y.T.reshape((-1, 4, 6)), solution.nfev


def main():
    root = Path(__file__).resolve().parents[1]
    output = root / "tests" / "fixtures" / "scientific-trajectories.csv"
    output.parent.mkdir(parents=True, exist_ok=True)
    rows, model_lines, evidence = [], [], {}
    for case, models in [("three-source", []), ("tilted-j2", MODELS)]:
        reference, evaluations = integrate(models, 3e-14, 1e-7, 1e-10, 60.0)
        comparison, check_evaluations = integrate(models, 1e-13, 1e-6, 1e-9, 120.0)
        difference = comparison - reference
        position_difference = float(np.max(np.linalg.norm(difference[:, :, :3], axis=2)))
        velocity_difference = float(np.max(np.linalg.norm(difference[:, :, 3:], axis=2)))
        if position_difference > 0.001 or velocity_difference > 1e-6:
            raise RuntimeError(f"independent reference did not converge: {case} {position_difference}m")
        evidence[case] = {"position_refinement_difference_m": position_difference,
            "velocity_refinement_difference_mps": velocity_difference,
            "reference_rhs_calls": evaluations, "check_rhs_calls": check_evaluations}
        for source, j2, radius, pole in models:
            model_lines.append(["# model", case, source, j2, radius, *pole])
        for time, state in zip(TIMES, reference):
            for body, values in enumerate(state):
                rows.append([case, time, body, MU[body] / G, *values])
    with output.open("w", newline="") as stream:
        stream.write("# SGL independent-reference-v1; synthetic states; SI units; inertial axes\n")
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerows(model_lines)
        writer.writerow(["case", "time_seconds", "body", "mass_kg", "x_m", "y_m", "z_m",
            "vx_mps", "vy_mps", "vz_mps"])
        for row in rows:
            writer.writerow([format(value, ".17g") if isinstance(value, (float, np.floating)) else value
                for value in row])
    metadata = {"oracle": "SciPy DOP853", "scipy_version": scipy.__version__,
        "numpy_version": np.__version__, "gravitational_constant": G,
        "reference": {"rtol": 3e-14, "position_atol_m": 1e-7, "velocity_atol_mps": 1e-10,
            "maximum_step_seconds": 60.0},
        "refinement_check": {"rtol": 1e-13, "position_atol_m": 1e-6, "velocity_atol_mps": 1e-9,
            "maximum_step_seconds": 120.0},
        "convergence": evidence, "fixture_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "generator_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "scope": "Independent integrator/coordinate-form comparison of synthetic Newtonian+J2 trajectories; not a Horizons ephemeris fit."}
    output.with_suffix(".json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
