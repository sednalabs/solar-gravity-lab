#!/usr/bin/env python3
"""An original small survey vehicle demonstrating the external asset catalog."""
from pathlib import Path
from gltf_mesh_builder import MeshAsset, annulus, box, cylinder
ROOT = Path(__file__).resolve().parents[1]
asset = MeshAsset()
white = asset.material('Ivory thermal shell', [.86,.89,.85], .12, .42)
black = asset.material('Carbon frame', [.04,.06,.08], .65, .34)
solar = asset.material('Blue violet cells', [.06,.13,.32], .72, .2)
copper = asset.material('Copper identification', [.9,.3,.08], .55, .3)
glass = asset.material('Survey aperture', [.08,.5,.55], .4, .15, .12)
plume = asset.material('Illustrative ion exhaust', [.12,.45,1], 0, .7, 1, True)
asset.add_mesh('Hexagonal instrument bus', cylinder(.95,.95,-.8,.8,6), white)
asset.add_mesh('Survey telescope', cylinder(.5,.32,.8,1.8,16), black)
asset.add_mesh('Telescope lens', cylinder(.3,.3,1.8,1.83,20), glass)
asset.add_mesh('Identification band', cylinder(.96,.96,-.2,.1,6), copper)
asset.add_mesh('Ion engine', cylinder(.38,.24,-1.25,-.8,16,False), black)
asset.add_mesh('Illustrative exhaust', cylinder(.28,.02,-1.25,-5,16), plume)
for sign in (-1,1):
    asset.add_mesh('Array boom', box(1.35,.045,.045), copper, (sign*1.8,0,-.3))
    asset.add_mesh('Array backing', box(.9,.065,1.2), black, (sign*3,0,-.3))
    for x in range(4):
        for z in range(6):
            asset.add_mesh('Solar cell', box(.2,.015,.18), solar, (sign*(2.35+x*.43),.08,-1.3+z*.39))
asset.add_mesh('Service mounting bracket', box(.65,.1,.45), white, (0,-1.05,-.1))
# A small passive service collar points +Z, with a physical definition supplied
# separately in pathfinder.scenario.json. The mesh never sets physical values.
asset.add_mesh('Passive service collar', annulus(.2,.35,.85,1.0,24), copper, (.6,0,0))
path = ROOT/'engine/data/authoring/assets/pathfinder.gltf'
path.parent.mkdir(parents=True, exist_ok=True)
asset.save(path, 'Pathfinder survey craft', 'Pathfinder body (+Z forward, metres)')
