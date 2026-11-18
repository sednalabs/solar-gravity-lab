#!/usr/bin/env python3
"""Author Sedna's original Asteria orbital tug as a reproducible glTF2 asset."""
import base64
import json
import math
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT/'render/android-vulkan/assets-src/asteria.gltf'
materials = []
meshes = []
nodes = []
binary = bytearray()
views = []
accessors = []


def material(name, rgb, metal=0., rough=.55, emission=0., exhaust=False):
    materials.append({'name':name, 'pbrMetallicRoughness':{'baseColorFactor':[*rgb,1], 'metallicFactor':metal,'roughnessFactor':rough},
                      'emissiveFactor':[emission]*3, 'extras':{'solarlab_exhaust':exhaust}})
    return len(materials)-1


pearl = material('Ceramic pearl hull', [.78,.84,.89], .18,.36)
graphite = material('Graphite structure',[.055,.073,.10], .7,.34)
gold = material('Gold thermal blanket',[.72,.39,.12], .75,.48)
blue = material('Sapphire photovoltaic cells',[.025,.105,.29], .65,.23)
trim = material('Silver panel traces',[.30,.49,.65], .8,.28)
window = material('Blue navigation windows',[.035,.28,.40], .5,.12,.12)
orange = material('Sedna copper stripe',[.9,.24,.045], .25,.4)
plume = material('Illustrative main exhaust',[.20,.60,1.0],0,.7,1.,True)


def add_mesh(name, triangles, mat, translation=(0,0,0)):
    positions=[]; normals=[]
    for a,b,c in triangles:
        u=[b[i]-a[i] for i in range(3)];v=[c[i]-a[i] for i in range(3)]
        n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
        mag=math.sqrt(sum(x*x for x in n))
        if mag < 1e-9: continue
        for p in (a,b,c):positions.append(p);normals.append([x/mag for x in n])
    attrs={}
    for key, values in [('POSITION',positions),('NORMAL',normals)]:
        offset=len(binary)
        for v in values:binary.extend(struct.pack('<3f',*v))
        views.append({'buffer':0,'byteOffset':offset,'byteLength':len(values)*12,'target':34962})
        acc={'bufferView':len(views)-1,'componentType':5126,'count':len(values),'type':'VEC3'}
        if key=='POSITION':acc.update(min=[min(v[i] for v in values) for i in range(3)],max=[max(v[i] for v in values) for i in range(3)])
        accessors.append(acc);attrs[key]=len(accessors)-1
    meshes.append({'name':name,'primitives':[{'attributes':attrs,'material':mat,'mode':4}]})
    nodes.append({'name':name,'mesh':len(meshes)-1,'translation':list(translation)})
    return len(nodes)-1


def box(x,y,z):
    v=[(-x,-y,-z),(x,-y,-z),(x,y,-z),(-x,y,-z),(-x,-y,z),(x,-y,z),(x,y,z),(-x,y,z)]
    faces=[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]
    return [(v[a],v[b],v[c]) for a,b,c,d in faces]+[(v[a],v[c],v[d]) for a,b,c,d in faces]


def cylinder(r0,r1,z0,z1,n=16,cap=True):
    out=[]
    for i in range(n):
        a=2*math.pi*i/n;b=2*math.pi*(i+1)/n
        p=(r0*math.cos(a),r0*math.sin(a),z0);q=(r0*math.cos(b),r0*math.sin(b),z0)
        r=(r1*math.cos(b),r1*math.sin(b),z1);s=(r1*math.cos(a),r1*math.sin(a),z1)
        out.extend([(p,q,r),(p,r,s)])
        if cap:out.extend([((0,0,z0),q,p),((0,0,z1),s,r)])
    return out


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
# Named root demonstrates scene hierarchy and owns the body-frame convention.
children=list(range(len(nodes)))
nodes.append({'name':'Asteria body frame (+Z forward)', 'children':children})
doc={'asset':{'version':'2.0','generator':'SednaLabs original deterministic mesh authoring','copyright':'Copyright 2026 SednaLabs; AGPL-3.0-or-later'},
     'scene':0,'scenes':[{'name':'Asteria orbital tug','nodes':[len(nodes)-1]}], 'nodes':nodes,'meshes':meshes,'materials':materials,
     'buffers':[{'uri':'data:application/octet-stream;base64,'+base64.b64encode(binary).decode(),'byteLength':len(binary)}],
     'bufferViews':views,'accessors':accessors}
DEST.write_text(json.dumps(doc,separators=(',',':'))+'\n')
print(f'Wrote {DEST.name}: {sum(a["count"] for a in accessors)//2} vertices')
