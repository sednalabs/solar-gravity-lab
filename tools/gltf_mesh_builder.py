"""Small deterministic glTF authoring helpers shared by original SGL assets."""
import base64
import json
import math
import struct


class MeshAsset:
    def __init__(self):
        self.materials = []
        self.meshes = []
        self.nodes = []
        self.binary = bytearray()
        self.views = []
        self.accessors = []
        self.roots = []

    def material(self, name, rgb, metal=0., rough=.55, emission=0., exhaust=False):
        self.materials.append({'name':name, 'pbrMetallicRoughness':{'baseColorFactor':[*rgb,1], 'metallicFactor':metal,'roughnessFactor':rough},
                          'emissiveFactor':[emission]*3, 'extras':{'solarlab_exhaust':exhaust}})
        return len(self.materials)-1

    def add_mesh(self, name, triangles, mat, translation=(0,0,0)):
        positions=[]; normals=[]
        for a,b,c in triangles:
            u=[b[i]-a[i] for i in range(3)];v=[c[i]-a[i] for i in range(3)]
            n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]]
            mag=math.sqrt(sum(x*x for x in n))
            if mag < 1e-9: continue
            for p in (a,b,c):positions.append(p);normals.append([x/mag for x in n])
        attrs={}
        for key, values in [('POSITION',positions),('NORMAL',normals)]:
            offset=len(self.binary)
            for v in values:self.binary.extend(struct.pack('<3f',*v))
            self.views.append({'buffer':0,'byteOffset':offset,'byteLength':len(values)*12,'target':34962})
            acc={'bufferView':len(self.views)-1,'componentType':5126,'count':len(values),'type':'VEC3'}
            if key=='POSITION':acc.update(min=[min(v[i] for v in values) for i in range(3)],max=[max(v[i] for v in values) for i in range(3)])
            self.accessors.append(acc);attrs[key]=len(self.accessors)-1
        self.meshes.append({'name':name,'primitives':[{'attributes':attrs,'material':mat,'mode':4}]})
        self.nodes.append({'name':name,'mesh':len(self.meshes)-1,'translation':list(translation)})
        self.roots.append(len(self.nodes)-1)
        return len(self.nodes)-1

    def group(self, name, children, translation=(0, 0, 0), rotation=None):
        node = {'name': name, 'children': children, 'translation': list(translation)}
        if rotation is not None:
            node['rotation'] = list(rotation)
        self.nodes.append(node)
        self.roots = [i for i in self.roots if i not in children]
        self.roots.append(len(self.nodes)-1)
        return len(self.nodes)-1

    def save(self, path, scene_name, root_name):
        self.nodes.append({'name': root_name, 'children': self.roots})
        doc={'asset':{'version':'2.0','generator':'SednaLabs original deterministic mesh authoring','copyright':'Copyright 2026 SednaLabs; AGPL-3.0-or-later'},
             'scene':0,'scenes':[{'name':scene_name,'nodes':[len(self.nodes)-1]}], 'nodes':self.nodes,'meshes':self.meshes,'materials':self.materials,
             'buffers':[{'uri':'data:application/octet-stream;base64,'+base64.b64encode(self.binary).decode(),'byteLength':len(self.binary)}],
             'bufferViews':self.views,'accessors':self.accessors}
        path.write_text(json.dumps(doc,separators=(',',':'))+'\n')
        print(f'Wrote {path.name}: {sum(a["count"] for a in self.accessors)//2} vertices')


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


def annulus(inner, outer, z0, z1, n=32):
    """Closed annular sleeve with outward normals and a real open centre."""
    if not 0 < inner < outer or not z0 < z1 or n < 3:
        raise ValueError('Invalid annular sleeve dimensions')
    out = []
    for i in range(n):
        a = 2*math.pi*i/n
        b = 2*math.pi*(i+1)/n
        def point(radius, angle, z):
            return (radius*math.cos(angle), radius*math.sin(angle), z)
        p, q = point(outer, a, z0), point(outer, b, z0)
        r, s = point(outer, b, z1), point(outer, a, z1)
        u, v = point(inner, a, z0), point(inner, b, z0)
        w, x = point(inner, b, z1), point(inner, a, z1)
        out.extend([(p,q,r), (p,r,s), (u,w,v), (u,x,w),
                    (u,q,p), (u,v,q), (x,s,r), (x,r,w)])
    return out
