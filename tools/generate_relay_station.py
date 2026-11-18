#!/usr/bin/env python3
"""Author the original Relay orbital station; all rigid geometry fits a 12 m hull."""
import math
from pathlib import Path
from gltf_mesh_builder import MeshAsset, box, cylinder

ROOT = Path(__file__).resolve().parents[1]
asset = MeshAsset()
mat = asset.material
mesh = asset.add_mesh
pearl = mat('Pearl ceramic pressure modules', [.81, .87, .91], .15, .39)
silver = mat('Brushed aluminium truss', [.40, .51, .60], .78, .33)
graphite = mat('Graphite joints and seals', [.045, .065, .095], .60, .42)
copper = mat('Sedna copper identification bands', [.94, .29, .065], .40, .38)
blanket = mat('Gold thermal blankets', [.67, .39, .12], .72, .46)
solar = mat('Bifacial sapphire solar cells', [.025, .13, .38], .68, .25)
trace = mat('Silver cell interconnects', [.33, .52, .69], .80, .30)
window = mat('Observation windows', [.025, .24, .34], .45, .16, .10)
beacon = mat('Cyan port navigation lights', [.10, .72, .78], .10, .30, .55)

# The pressure vessel cluster is intentionally different from the tug silhouette.
core = []
core.append(mesh('Central laboratory', cylinder(1.9, 1.9, -2.7, 2.7, 24), pearl))
for sign in (-1, 1):
    if sign == 1:
        core.append(mesh('Laboratory end cap', cylinder(1.9, 1.12, 2.7, 3.45, 24), pearl))
    else:
        core.append(mesh('Laboratory aft cap', cylinder(1.12, 1.9, -3.45, -2.7, 24), pearl))
    core.append(mesh('Copper station band', cylinder(1.92, 1.92, sign*2.12-.12, sign*2.12+.12, 24), copper))
    core.append(mesh('Axial docking sleeve', cylinder(.77, .77, sign*4.25-.63, sign*4.25+.63, 20), graphite))
    core.append(mesh('Axial docking rim', cylinder(.89, .89, sign*4.98-.14, sign*4.98+.14, 20), silver))
    core.append(mesh('Axial port lights', cylinder(.82, .82, sign*5.16-.035, sign*5.16+.035, 20), beacon))
    module = []
    module.append(mesh('Habitat pressure vessel', cylinder(1.06, 1.06, -2.8, 2.8, 20), pearl))
    module.append(mesh('Habitat forward cap', cylinder(1.06, .55, 2.8, 3.45, 20), pearl))
    module.append(mesh('Habitat aft cap', cylinder(.55, 1.06, -3.45, -2.8, 20), blanket))
    for z in (-2.1, 2.1):
        module.append(mesh('Habitat copper band', cylinder(1.07, 1.07, z-.10, z+.10, 20), copper))
    for z in (-1.05, 0, 1.05):
        module.append(mesh('Habitat viewing window', box(.35, .025, .27), window, (0, -1.055, z)))
    core.append(asset.group('Habitat module', module, (sign*3.25, 0, 0)))
    core.append(mesh('Cross-module tunnel', box(.72, .58, .66), pearl, (sign*2.15, 0, 0)))
    core.append(mesh('Array structural spar', box(4.4, .085, .085), silver, (sign*5.25, 0, -.5)))
asset.group('Pressure modules and axial ports', core)

# A forward service port faces the mission's -Y stand-off direction. This is
# visual geometry only; the scenario stops outside the collision keep-out zone.
port = [mesh('Service port tunnel', cylinder(.8, .8, 0, 2.1, 20), pearl),
        mesh('Service port collar', cylinder(.99, .99, 1.7, 2.15, 20), graphite),
        mesh('Service port rim', cylinder(1.05, 1.05, 2.11, 2.28, 20), silver),
        mesh('Service port beacon', cylinder(.94, .94, 2.29, 2.34, 20), beacon)]
asset.group('Service port facing stand-off', port, (0, -1.7, 0),
            (math.sqrt(.5), 0, 0, math.sqrt(.5)))

# Four separate bifacial array wings have explicit cells on both visible faces,
# perimeter rails and thin conductors. No opaque backing hides the blue cells.
for sx in (-1, 1):
    for sy in (-1, 1):
        panel = []
        for row in range(5):
            for col in range(8):
                panel.append(mesh('Bifacial photovoltaic tile', box(.279, .303, .026), solar,
                                  (-2.1+col*.6, -1.28+row*.64, 0)))
        for x in (-2.43, 2.43):
            panel.append(mesh('Solar wing edge rail', box(.045, 1.68, .06), graphite, (x, 0, 0)))
        for y in (-1.66, 1.66):
            panel.append(mesh('Solar wing end rail', box(2.43, .045, .06), silver, (0, y, 0)))
        for col in range(9):
            panel.append(mesh('Cell conductor', box(.009, 1.60, .031), trace, (-2.4+col*.6, 0, 0)))
        asset.group('Bifacial solar wing', panel, (sx*7.3, sy*3.0, -.5))
    mesh('Wing hinge boom', box(.065, 3.0, .065), silver, (sx*7.3, 0, -.5))
    mesh('Solar wing hinge', cylinder(.24, .24, -.75, -.24, 12), copper, (sx*7.3, 0, 0))
    for z in (-1.3, 1.3):
        mesh('White thermal radiator', box(.85, .055, .48), pearl, (sx*2.3, 2.25, z))
        mesh('Radiator attachment', box(.045, .40, .045), graphite, (sx*2.3, 1.9, z))

# Shallow dish plus feed mast are triangle geometry, not texture markings.
dish = [mesh('Communications dish', cylinder(.13, .92, 0, .38, 24, False), pearl),
        mesh('Dish rim', cylinder(.94, .94, .36, .43, 24, False), silver),
        mesh('Dish feed mast', cylinder(.025, .025, .05, .83, 8), graphite),
        mesh('Dish feed', cylinder(.12, .12, .78, .90, 12), copper)]
asset.group('Communications antenna', dish, (0, 2.0, 1.3),
            (-math.sqrt(.5), 0, 0, math.sqrt(.5)))
mesh('Navigation mast', box(.045, .78, .045), silver, (0, 2.5, -1.7))
mesh('Navigation lamp', box(.12, .09, .12), beacon, (0, 3.33, -1.7))

asset.save(ROOT/'render/android-vulkan/assets-src/relay-station.gltf',
           'Relay orbital station', 'Relay station body frame (+Z axial ports)')
