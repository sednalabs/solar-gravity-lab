#!/usr/bin/env python3
"""Author Sedna's original Asteria orbital tug as a reproducible glTF2 asset."""
import math
from pathlib import Path
from gltf_mesh_builder import MeshAsset, box, cylinder

ROOT = Path(__file__).resolve().parents[1]
asset = MeshAsset()
material = asset.material
add_mesh = asset.add_mesh

pearl = material('Ceramic pearl hull', [.78,.84,.89], .18,.36)
graphite = material('Graphite structure',[.055,.073,.10], .7,.34)
gold = material('Gold thermal blanket',[.72,.39,.12], .75,.48)
blue = material('Sapphire photovoltaic cells',[.025,.105,.29], .65,.23)
trim = material('Silver panel traces',[.30,.49,.65], .8,.28)
window = material('Blue navigation windows',[.035,.28,.40], .5,.12,.12)
orange = material('Sedna copper stripe',[.9,.24,.045], .25,.4)
plume = material('Illustrative main exhaust',[.20,.60,1.0],0,.7,1.,True)


add_mesh('Pressurised cabin',cylinder(1.15,1.15,-.6,2.15),pearl)
add_mesh('Forward tapered nose',cylinder(1.15,.52,2.15,3.1),pearl)
add_mesh('Docking collar',cylinder(.52,.52,3.1,3.5),graphite)
add_mesh('Docking rim',cylinder(.57,.57,3.38,3.52),trim)
add_mesh('Copper identification band',cylinder(1.16,1.16,1.25,1.48),orange)
add_mesh('Service module',cylinder(1.27,1.27,-2.75,-.75),gold)
add_mesh('Module separation ring',cylinder(1.32,1.32,-.85,-.62),graphite)
add_mesh('Engine support',cylinder(.66,.45,-3.0,-2.7),graphite)
add_mesh('Main engine bell',cylinder(.86,.36,-4.0,-3.0,20,False),graphite)
add_mesh('Engine rim',cylinder(.91,.91,-4.03,-3.92,20,False),trim)
add_mesh('Main engine exhaust',cylinder(.75,.04,-4.03,-10.5,20),plume)
for sign in (-1,1):
    add_mesh('Solar array boom',box(2.8,.055,.07),trim,(sign*3.8,0,-1.7))
    add_mesh('Solar array frame',box(2.05,.07,1.25),graphite,(sign*4.7,0,-1.7))
    for row in range(4):
        for col in range(8):
            add_mesh('Photovoltaic tile',box(.238,.012,.283),blue,
                     (sign*(2.95+col*.50),.082,-2.60+row*.60))
    for col in range(9):
        add_mesh('Panel conductor',box(.008,.016,1.2),trim,(sign*(2.70+col*.50),.087,-1.7))
    add_mesh('Cabin viewport',box(.025,.24,.38),window,(sign*1.146,.16,1.82))
for angle in (0,math.pi/2,math.pi,3*math.pi/2):
    x,y=1.30*math.cos(angle),1.30*math.sin(angle)
    add_mesh('Attitude thruster pod',box(.18,.18,.32),pearl,(x,y,-.36))
    add_mesh('RCS nozzle',cylinder(.11,.06,-.74,-.65,8),graphite,(x,y,0))
add_mesh('Communications mast',box(.025,.53,.025),trim,(0,1.65,.05))
add_mesh('Navigation lamp',box(.10,.08,.10),window,(0,2.20,.05))
asset.save(ROOT/'render/android-vulkan/assets-src/asteria.gltf',
           'Asteria orbital tug', 'Asteria body frame (+Z forward)')
