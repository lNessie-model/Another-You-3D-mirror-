"""Build a reproducible, original morph-only guide GLB with Blender 4.5, offline.

Run: blender --background --factory-startup --python-exit-code 1 --python scripts/build_builtin_avatar.py
No downloaded meshes, textures, fonts, models, or external services are used.
The exported GLB is written directly from the same arrays used for the previews.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import itertools
import math
import struct
import sys
from collections import Counter
from pathlib import Path

import bpy
import numpy as np
from mathutils import Vector, Matrix

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/avatars/builtin-guide"
REPORT = ROOT / "app/build/builtin-guide-review"
SCALE = .13
HEAD_PIVOT = np.array([0., -.68, 0.])
JAW_PIVOT = np.array([0., -.05, .08])
EYE_RADIUS = .235
EYE_CENTERS = {"Left": np.array([.305, .32, .365]), "Right": np.array([-.305, .32, .365])}
SOURCE_NAMES = [
    "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
    "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight",
    "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft",
    "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
    "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft", "jawOpen", "jawRight", "mouthClose",
    "mouthDimpleLeft", "mouthDimpleRight", "mouthFrownLeft", "mouthFrownRight", "mouthFunnel",
    "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft", "mouthPressRight",
    "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower", "mouthShrugUpper",
    "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight", "mouthUpperUpLeft",
    "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight",
]
TARGETS = [name for name in SOURCE_NAMES[1:] if not name.startswith("eyeLook")]
BASE_TARGETS = tuple(TARGETS)
DERIVED = []
SKIN = np.array([.72, .405, .255, 1.])
LIP = np.array([.40, .105, .085, 1.])
HAIR = np.array([.025, .075, .080, 1.])
GOLD = np.array([.80, .48, .105, 1.])
TEAL = np.array([.025, .19, .205, 1.])
WHITE = np.array([.88, .86, .73, 1.])


def clamp(x, a=0., b=1.):
    return max(a, min(b, x))


def gauss(x, center, width):
    return math.exp(-((x-center)/width)**2)


def rotate_x(p, angle, pivot=JAW_PIVOT):
    q = np.array(p)-pivot
    c, s = math.cos(angle), math.sin(angle)
    return pivot + np.array([q[0], c*q[1]-s*q[2], s*q[1]+c*q[2]])


def face_z(x, y):
    v = (y-.14)/.96
    taper = 1-.17*clamp((-y-.12)/.60)
    u = x/(.735*taper)
    z = .65*math.sqrt(max(0., 1-u*u-v*v))
    z += .145*gauss(x, 0, .115)*gauss(y, .19, .30)
    z += .095*gauss(x, 0, .14)*gauss(y, .015, .095)
    z += .038*(gauss(x, .35, .19)+gauss(x, -.35, .19))*gauss(y, -.01, .18)
    z += .033*gauss(x, 0, .29)*gauss(y, -.29, .18)
    z += .045*gauss(x, 0, .27)*gauss(y, -.57, .17)
    return z


class Mesh:
    def __init__(self, name, origin, rough=.65, morph=False):
        self.name, self.origin, self.rough, self.morph = name, np.array(origin), rough, morph
        self.v, self.f, self.c, self.tags = [], [], [], []
        self.deltas = {}

    def vertex(self, p, color, tag=None):
        self.v.append(tuple(float(x) for x in p))
        self.c.append(tuple(float(x) for x in color))
        self.tags.append(tag or ("skin",))
        return len(self.v)-1

    def tri(self, a, b, c):
        self.f.append((a, b, c))

    def quad(self, a, b, c, d):
        self.tri(a, b, c)
        self.tri(a, c, d)

    def append(self, vertices, faces, color, tag=("skin",)):
        off = len(self.v)
        for p in vertices:
            self.vertex(p, color, tag)
        self.f.extend(tuple(off+i for i in tri) for tri in faces)

    def finish(self):
        self.v = np.asarray(self.v, dtype=np.float64)
        self.f = np.asarray(self.f, dtype=np.int32)
        self.c = np.asarray(self.c, dtype=np.float32)
        # No zero-area base faces; sphere poles are represented as single vertices.
        areas = np.linalg.norm(np.cross(self.v[self.f[:, 1]]-self.v[self.f[:, 0]], self.v[self.f[:, 2]]-self.v[self.f[:, 0]]), axis=1)
        self.f = self.f[areas > 1e-10]
        # Exact Boolean may emit a pair of opposite internal sliver faces. They cancel as a
        # surface; remove both by identical vertex identity, never by a spatial tolerance.
        duplicates = Counter(tuple(sorted(map(int, tri))) for tri in self.f)
        self.f = np.asarray([tri for tri in self.f if duplicates[tuple(sorted(map(int, tri)))] == 1],dtype=np.int32)
        if self.morph:
            for name in TARGETS:
                self.deltas[name] = np.array([deform(p, tag, name)-p for p, tag in zip(self.v, self.tags)], dtype=np.float32)


def ellipsoid(mesh, center, radii, color, tag=("fixed",), nu=24, nv=12):
    center, radii = np.asarray(center), np.asarray(radii)
    top = mesh.vertex(center+np.array([0, radii[1], 0]), color, tag)
    rings = []
    for j in range(1, nv):
        phi = math.pi*j/nv
        row = []
        for i in range(nu):
            theta = 2*math.pi*i/nu
            p = center+radii*np.array([math.sin(phi)*math.sin(theta), math.cos(phi), math.sin(phi)*math.cos(theta)])
            row.append(mesh.vertex(p, color, tag))
        rings.append(row)
    bottom = mesh.vertex(center-np.array([0, radii[1], 0]), color, tag)
    for i in range(nu):
        k = (i+1)%nu
        mesh.tri(top, rings[0][i], rings[0][k])
        for a, b in zip(rings, rings[1:]):
            mesh.quad(a[i], b[i], b[k], a[k])
        mesh.tri(rings[-1][i], bottom, rings[-1][k])


def tube(mesh, points, radii, color, tag=("fixed",), sides=8, flatten=1.):
    points = np.asarray(points)
    rows = []
    for i, p in enumerate(points):
        t = points[min(len(points)-1, i+1)]-points[max(0, i-1)]
        t /= np.linalg.norm(t)
        a = np.cross(t, [0, 0, 1.])
        if np.linalg.norm(a) < .01:
            a = np.cross(t, [0, 1., 0])
        a /= np.linalg.norm(a)
        b = np.cross(t, a)
        row = []
        for j in range(sides):
            q = p+radii[i]*(a*math.cos(2*math.pi*j/sides)+b*math.sin(2*math.pi*j/sides)*flatten)
            row.append(mesh.vertex(q, color, tag))
        rows.append(row)
    for a, b in zip(rows, rows[1:]):
        for j in range(sides):
            k = (j+1)%sides
            mesh.quad(a[j], a[k], b[k], b[j])
    start = mesh.vertex(points[0], color, tag)
    end = mesh.vertex(points[-1], color, tag)
    for j in range(sides):
        k = (j+1)%sides
        mesh.tri(start, rows[0][k], rows[0][j])
        mesh.tri(end, rows[-1][j], rows[-1][k])


def head_boolean():
    """A closed sculpted head with three true socket/cavity openings, not painted circles."""
    bpy.ops.mesh.primitive_uv_sphere_add(segments=60, ring_count=36)
    head = bpy.context.object
    for v in head.data.vertices:
        p = v.co.copy()
        y = .14+.96*p.z
        taper = 1-.17*clamp((-y-.12)/.60)
        x = .735*p.x*taper
        z = face_z(x, y) if p.y < 0 else -.59*math.sqrt(max(0., 1-p.x*p.x-p.z*p.z))
        v.co = (x, y, z)
    # Recalculate orientation after remapping Blender's sphere axes.
    import bmesh
    bm = bmesh.new(); bm.from_mesh(head.data)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces)); bm.to_mesh(head.data); bm.free()
    for x, y, rx, ry, back in [(.305, .32, .218, .127, .36), (-.305, .32, .218, .127, .36), (0, -.29, .287, .100, .02)]:
        bpy.ops.mesh.primitive_cylinder_add(vertices=80, radius=1, depth=1, location=(x, y, (1.4+back)/2))
        cutter = bpy.context.object
        cutter.scale = (rx, ry, 1.4-back)
        bpy.context.view_layer.objects.active = cutter
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
        bpy.context.view_layer.objects.active = head
        mod = head.modifiers.new("Actual facial opening", "BOOLEAN")
        mod.operation, mod.solver, mod.object = 'DIFFERENCE', 'EXACT', cutter
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(cutter, do_unlink=True)
    head.data.calc_loop_triangles()
    v = [tuple(v.co) for v in head.data.vertices]
    f = [tuple(t.vertices) for t in head.data.loop_triangles]
    bpy.data.objects.remove(head, do_unlink=True)
    return v, f


def add_lids(mesh, side):
    center = EYE_CENTERS[side]
    rows = []
    for j in range(6):
        t = j/5
        rx, ry = .186+(.247-.186)*t, .083+(.166-.083)*t
        row = []
        for i in range(64):
            theta = 2*math.pi*i/64
            dx, dy = rx*math.cos(theta), ry*math.sin(theta)
            x, y = center[0]+dx, center[1]+dy
            inner_z = center[2]+math.sqrt(max(.001, EYE_RADIUS**2-(.186*math.cos(theta))**2-(.083*math.sin(theta))**2))+.012
            z = (1-t)*inner_z+t*(face_z(x, y)+.007)
            if dx*dx+dy*dy<EYE_RADIUS**2:
                z=max(z,center[2]+math.sqrt(EYE_RADIUS**2-dx*dx-dy*dy)+.012)
            col = SKIN*(1-.10*(1-t))
            col[3] = 1
            row.append(mesh.vertex((x, y, z), col, ("lid", side, t, theta)))
        rows.append(row)
    for a, b in zip(rows, rows[1:]):
        for i in range(64):
            k = (i+1)%64
            # From inner contour to outer contour, front normal +Z.
            mesh.quad(a[i], b[i], b[k], a[k])
    # A fine upper crease gives the closed lid a readable edge without painting the eye.
    pts = []
    for i in range(25):
        theta = .10+(math.pi-.20)*i/24
        dx, dy = .197*math.cos(theta), .107*math.sin(theta)
        z = center[2]+math.sqrt(max(.001, (EYE_RADIUS+.004)**2-dx*dx-dy*dy))+.015
        pts.append((center[0]+dx, center[1]+dy, z))
    tube(mesh, pts, [.0045]*len(pts), SKIN*np.array([.60,.50,.48,1]), ("crease", side), sides=6)


def add_lips(mesh):
    rows = []
    for j in range(5):
        t = j/4
        row = []
        for i in range(80):
            theta = 2*math.pi*i/80
            x = (.242+.099*t)*math.cos(theta)
            y = -.29+(.045+.084*t)*math.sin(theta)
            if math.sin(theta)>0:
                y -= .010*(1-t)*gauss(x, 0, .085)
            z = (1-t)*.699+t*(face_z(x, y)+.008)+.020*math.sin(math.pi*t)
            color = LIP*(1-t)+SKIN*t
            color[3] = 1
            row.append(mesh.vertex((x,y,z),color,("lip",t,theta)))
        rows.append(row)
    for a,b in zip(rows,rows[1:]):
        for i in range(80):
            k=(i+1)%80
            mesh.quad(a[i],b[i],b[k],a[k])


def build_face():
    mesh = Mesh("Face", HEAD_PIVOT, morph=True)
    v,f = head_boolean(); mesh.append(v,f,SKIN)
    for i,(x,y,z) in enumerate(v):
        if (x/.291)**2+((y+.29)/.103)**2<1.05 and .01<z<.67:
            mesh.c[i]=(.030,.006,.007,1.)
    for side in ("Left","Right"):
        sign = 1 if side=="Left" else -1
        add_lids(mesh,side)
        # Thick arched brows with independent inner and outer regions.
        pts=[]
        for i in range(19):
            t=i/18
            x=sign*(.125+.39*t); y=.548+.054*math.sin(math.pi*t)-.048*t
            pts.append((x,y,face_z(x,y)+.037))
        tube(mesh,pts,[.017+.010*math.sin(math.pi*i/18) for i in range(19)],HAIR,("brow",side),sides=8,flatten=.58)
        ellipsoid(mesh,(sign*.716,.03,-.005),(.123,.227,.103),SKIN,("ear",),24,14)
        ellipsoid(mesh,(sign*.745,.042,.079),(.070,.148,.030),SKIN*np.array([.70,.59,.59,1]),("ear",),20,10)
        ellipsoid(mesh,(sign*.757,-.087,.083),(.053,.066,.035),SKIN,("ear",),16,8)
        # Tiny nostril geometry, separate from the nose's sculpted skin surface.
        ellipsoid(mesh,(sign*.083,-.013,.812),(.032,.016,.013),np.array([.12,.045,.025,1]),("nose",side),16,8)
    add_lips(mesh)
    ellipsoid(mesh,(0,-.75,-.035),(.275,.285,.265),SKIN,("neck",),32,14)
    mesh.finish(); return mesh


def build_hair():
    mesh=Mesh("Hair",HEAD_PIVOT,.70)
    top=mesh.vertex((0,1.155,-.055),HAIR,("fixed",))
    rows=[]
    for j in range(1,18):
        row=[]
        for i in range(64):
            theta=2*math.pi*i/64
            front=max(0,math.cos(theta))
            edge_y=-.20+.88*front**2
            phi=math.acos(clamp((edge_y-.16)/1.0,-1,1))*j/17
            p=(.776*math.sin(phi)*math.sin(theta),.16+1.0*math.cos(phi),-.035+.70*math.sin(phi)*math.cos(theta))
            color=HAIR*(.82+.18*math.sin(theta*4+.5)**2); color[3]=1
            row.append(mesh.vertex(p,color,("fixed",)))
        rows.append(row)
    for i in range(64):
        k=(i+1)%64
        mesh.tri(top,rows[0][i],rows[0][k])
        for a,b in zip(rows,rows[1:]):mesh.quad(a[i],b[i],b[k],a[k])
    # Deliberate swept locks, rather than a featureless cap.
    for lane in range(5):
        pts=[]
        for i in range(18):
            t=i/17
            x=-.56+.24*lane+.26*t
            y=.76+.20*math.sin(math.pi*t)-.10*t+.02*lane
            z=.54+.11*math.sin(math.pi*t)+.027*lane
            pts.append((x,y,z))
        radii=[.015+.057*math.sin(math.pi*i/17)**.6 for i in range(18)]
        col=HAIR*np.array([1.1,1.3,1.35,1])
        tube(mesh,pts,radii,col,sides=10,flatten=.53)
    # Small gold side pin anchors the guide's visual identity.
    for x,y,z in [(-.60,.58,.40),(-.58,.62,.41),(-.56,.66,.42)]:
        ellipsoid(mesh,(x,y,z),(.019,.060,.020),GOLD,("fixed",),12,8)
    mesh.finish();return mesh


def build_eye(side):
    center=EYE_CENTERS[side]
    mesh=Mesh("Eye"+side,center,.24)
    # Independent watertight globe; iris and pupil are curved geometric regions.
    angles=[.035,.09,.15,.19,.205,.25,.30,.35,.405,.43,.50,.63,.80,1.0,1.2,1.45,1.7,2.0,2.3,2.6,2.88,3.05]
    pupil=np.array([.006,.014,.017,1.]); iris=np.array([.028,.30,.29,1.])
    top=mesh.vertex(center+[0,0,EYE_RADIUS],pupil,("fixed",)); rows=[]
    for phi in angles:
        row=[]
        for i in range(48):
            th=2*math.pi*i/48
            p=center+EYE_RADIUS*np.array([math.sin(phi)*math.cos(th),math.sin(phi)*math.sin(th),math.cos(phi)])
            color=pupil.copy() if phi<.205 else iris.copy() if phi<.43 else WHITE.copy()
            if .205<=phi<.43:
                color[:3]*=.72+.28*math.cos(th*16+.4)**2
                if phi>.405:color[:3]*=.40
            row.append(mesh.vertex(p,color,("fixed",)))
        rows.append(row)
    back=mesh.vertex(center+[0,0,-EYE_RADIUS],WHITE,("fixed",))
    for i in range(48):
        k=(i+1)%48
        mesh.tri(top,rows[0][i],rows[0][k])
        for a,b in zip(rows,rows[1:]):mesh.quad(a[i],b[i],b[k],a[k])
        mesh.tri(rows[-1][i],back,rows[-1][k])
    ellipsoid(mesh,center+[-.027,.034,EYE_RADIUS-.004],(.012,.016,.005),np.array([1,1,1,1]),("fixed",),12,8)
    mesh.finish();return mesh


def build_mouth():
    mesh=Mesh("MouthInterior",HEAD_PIVOT,.84,True)
    # A deep closed back wall leaves actual room for teeth/tongue. A shallow bowl would
    # incorrectly occlude those parts when the rigid jaw swings down and backwards.
    ellipsoid(mesh,(0,-.36,.15),(.34,.27,.11),[.030,.006,.007,1],("fixed",),32,16)
    # Upper teeth remain on Head. Only lower teeth/tongue live on the rigid jaw node.
    for i in range(8):
        x=(i-3.5)*.048
        ellipsoid(mesh,(x,-.282,.610-.075*(x/.22)**2),(.025,.047,.027),WHITE,("fixed",),12,8)
    mesh.finish();return mesh


def build_jaw():
    mesh=Mesh("JawAttachments",JAW_PIVOT,.63)
    for i in range(8):
        x=(i-3.5)*.046
        ellipsoid(mesh,(x,-.369,.588-.066*(x/.22)**2),(.023,.033,.024),WHITE,("fixed",),12,8)
    ellipsoid(mesh,(0,-.401,.491),(.172,.045,.130),[.49,.115,.13,1],("fixed",),28,12)
    mesh.finish();return mesh


def build_shoulders():
    mesh=Mesh("Shoulders",[0,-2,0],.78)
    ellipsoid(mesh,(0,-1.48,-.09),(1.03,.65,.45),TEAL,("fixed",),40,18)
    # Collar and undershirt overlap the neck so head rotation never reveals a hollow torso.
    ellipsoid(mesh,(0,-.91,-.03),(.39,.22,.32),[.018,.055,.063,1],("fixed",),32,12)
    ellipsoid(mesh,(0,-.78,-.045),(.248,.22,.241),SKIN,("fixed",),24,12)
    for sign in (-1,1):
        pts=[]
        for i in range(22):
            t=i/21
            pts.append((sign*(.21+.67*t),-.96-.49*t,.32+.09*math.sin(math.pi*t)))
        tube(mesh,pts,[.029]*len(pts),GOLD,sides=8,flatten=.43)
    ellipsoid(mesh,(0,-1.21,.392),(.073,.095,.024),GOLD,("fixed",),20,12)
    ellipsoid(mesh,(0,-1.205,.417),(.036,.052,.014),[.025,.34,.30,1],("fixed",),16,10)
    mesh.finish();return mesh


def deform(p,tag,name):
    """Authored procedural deltas in character space. Gaze uses rigid nodes, never these deltas."""
    p=np.asarray(p); x,y,z=p; out=p.copy(); kind=tag[0]
    if kind in ("fixed","ear","neck"):
        return out
    front=clamp((z-.28)/.20)
    suffix="Left" if name.endswith("Left") else "Right" if name.endswith("Right") else None
    sign=1 if suffix=="Left" else -1
    side=clamp(.5+sign*x/.15) if suffix else 1.
    cheek=(gauss(x,.37,.24)+gauss(x,-.37,.24))*gauss(y,-.065,.22)*front
    mouth=gauss(x,0,.35)*gauss(y,-.29,.18)*front
    is_lip=kind=="lip"; is_oral=kind=="oral"
    upper=clamp((y+.29)/.045)
    lower=clamp((-.29-y)/.045)
    if is_lip or is_oral:
        theta=tag[2] if is_lip else tag[1]
        upper=max(0,math.sin(theta));lower=max(0,-math.sin(theta))
        mouth=1 if is_oral else 1-.40*tag[1]
    jaw_weight=clamp((-.16-y)/.32)*front
    if is_lip or is_oral:
        jaw_weight=.16+.84*lower-.14*upper
    if name=="jawOpen":
        out+=(rotate_x(p,math.radians(22))-p)*jaw_weight
    elif name in ("jawLeft","jawRight"):
        out[0]+=(.065 if name=="jawLeft" else -.065)*jaw_weight
    elif name=="jawForward":out[2]+=.060*jaw_weight
    elif name.startswith("eye") and kind in ("lid","crease") and tag[1]==suffix:
        center=EYE_CENTERS[suffix]
        t=tag[2] if kind=="lid" else .28
        w=(1-t)**1.5 if kind=="lid" else .64
        if name.startswith("eyeBlink"):
            out[1]-=(y-center[1])*.99*w
            # Closed lids follow the front of the globe and cover the iris; globe does not shrink.
            closed_z=center[2]+math.sqrt(max(.0001,EYE_RADIUS**2-(x-center[0])**2))+.015
            out[2]+=(closed_z-z)*w
        elif name.startswith("eyeWide"):
            out[1]+=(.032 if y>=center[1] else -.020)*w
        elif name.startswith("eyeSquint"):
            out[1]+=(center[1]-y)*(.28 if y>=center[1] else .64)*w
    elif name.startswith("brow"):
        w=gauss(abs(x),.32,.28)*gauss(y,.55,.15)*front
        if kind=="brow":w=1
        if suffix:w*=side
        if name.startswith("browDown"):out[1]-=.085*w
        elif name=="browInnerUp":out[1]+=.12*w*gauss(abs(x),.13,.20)
        else:out[1]+=.105*w*clamp((abs(x)-.12)/.35)
    elif name=="cheekPuff":
        out[2]+=.067*cheek;out[0]+=.025*cheek*(1 if x>0 else -1)
    elif name.startswith("cheekSquint"):
        out[1]+=.046*cheek*side;out[2]+=.016*cheek*side
    elif name.startswith("noseSneer"):
        w=gauss(x,sign*.13,.12)*gauss(y,-.015,.16)*front
        out[1]+=.062*w;out[2]+=.025*w
    elif name.startswith("mouth"):
        w=mouth*side
        if name.startswith("mouthSmile"):
            corner=clamp(abs(x)/.25)**1.25
            out[0]+=sign*.064*w*corner;out[1]+=.085*w*corner;out[2]+=.010*w
        elif name.startswith("mouthFrown"):
            out[1]-=.076*w*clamp(abs(x)/.26)
        elif name.startswith("mouthDimple"):
            out[0]+=sign*.028*w*clamp(abs(x)/.24);out[2]-=.024*w*clamp(abs(x)/.24)
        elif name.startswith("mouthStretch"):
            out[0]+=sign*.079*w*clamp(abs(x)/.22)
        elif name=="mouthFunnel":
            out[0]-=.25*x*w;out[1]+=.65*(y+.29)*w;out[2]+=.065*w
        elif name=="mouthPucker":
            out[0]-=.44*x*w;out[1]-=.30*(y+.29)*w;out[2]+=.089*w
        elif name in ("mouthLeft","mouthRight"):
            out[0]+=(.065 if name=="mouthLeft" else -.065)*mouth
        elif name.startswith("mouthLowerDown"):
            out[1]-=.082*lower*w;out[2]+=.013*lower*w
        elif name.startswith("mouthUpperUp"):
            out[1]+=.075*upper*w;out[2]+=.015*upper*w
        elif name.startswith("mouthPress"):
            out[1]-=.56*(y+.29)*w;out[2]-=.012*w
        elif name=="mouthClose":
            # Precursor delta; fix_lips below authors the final jaw×close corrective.
            out[1]-=.92*(y+.29)*mouth
        elif name=="mouthRollLower":out[2]-=.047*lower*mouth;out[1]+=.029*lower*mouth
        elif name=="mouthRollUpper":out[2]-=.047*upper*mouth;out[1]-=.026*upper*mouth
        elif name=="mouthShrugLower":out[1]+=.052*lower*mouth;out[2]+=.022*lower*mouth
        elif name=="mouthShrugUpper":out[1]+=.037*upper*mouth;out[2]+=.025*upper*mouth
    if name.startswith("eye") and kind in ("lid","crease") and tag[1]==suffix:
        center=EYE_CENTERS[suffix]
        projected=(out[0]-center[0])**2+(out[1]-center[1])**2
        if projected<EYE_RADIUS**2:
            out[2]=max(out[2],center[2]+math.sqrt(EYE_RADIUS**2-projected)+.012)
    return out


def normals(v,f):
    n=np.zeros_like(v,dtype=np.float64)
    cross=np.cross(v[f[:,1]]-v[f[:,0]],v[f[:,2]]-v[f[:,0]])
    for i in range(3):np.add.at(n,f[:,i],cross)
    length=np.linalg.norm(n,axis=1)
    n[length>1e-15]/=length[length>1e-15,None]
    n[length<=1e-15]=[0,0,1]
    return n.astype(np.float32)


class Glb:
    def __init__(self):
        self.data=bytearray();self.views=[];self.accessors=[]
    def accessor(self,values,type_name,component=5126,bounds=False,target=None):
        values=np.asarray(values,dtype='<f4' if component==5126 else '<u2')
        while len(self.data)%4:self.data.append(0)
        start=len(self.data);raw=values.tobytes();self.data.extend(raw)
        view={"buffer":0,"byteOffset":start,"byteLength":len(raw)}
        if target:view["target"]=target
        self.views.append(view)
        width={"SCALAR":1,"VEC3":3,"VEC4":4}[type_name]
        entry={"bufferView":len(self.views)-1,"componentType":component,"count":values.size//width,"type":type_name}
        if bounds:
            shaped=values.reshape(-1,width);entry["min"]=shaped.min(axis=0).tolist();entry["max"]=shaped.max(axis=0).tolist()
        self.accessors.append(entry);return len(self.accessors)-1
    def write(self,meshes,path):
        gm=[];mats=[]
        for i,m in enumerate(meshes):
            local=(m.v-m.origin)*SCALE
            pos=self.accessor(local,"VEC3",bounds=True,target=34962)
            nor=self.accessor(normals(m.v,m.f),"VEC3",target=34962)
            color=self.accessor(m.c,"VEC4",target=34962)
            ids=self.accessor(m.f.ravel(),"SCALAR",5123,target=34963)
            primitive={"attributes":{"POSITION":pos,"NORMAL":nor,"COLOR_0":color},"indices":ids,"material":i,"mode":4}
            mesh={"name":m.name,"primitives":[primitive]}
            if m.morph:
                primitive["targets"]=[{"POSITION":self.accessor(m.deltas[name]*SCALE,"VEC3",bounds=True)} for name in TARGETS]
                mesh["extras"]={"targetNames":TARGETS};mesh["weights"]=[0]*len(TARGETS)
                if m.name=="Face":
                    mesh["extras"]["validationGroups"]={side:[i for i,t in enumerate(m.tags) if t[0]=='lid' and t[1]==side and t[2]==0] for side in ('Left','Right')}
            gm.append(mesh)
            mats.append({"name":m.name+" factor and vertex color","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":m.rough},"alphaMode":"OPAQUE"})
        nodes=[{"name":"GuideRoot","children":[1,8]}, {"name":"Head","translation":[0,.1716,0],"children":[2,3,4,5,6,7]}]
        for i,m in enumerate(meshes):
            node={"name":m.name,"mesh":i}
            if m.name.startswith("Eye") or m.name=="JawAttachments":node["translation"]=((m.origin-HEAD_PIVOT)*SCALE).tolist()
            nodes.append(node)
        logical=len(self.data)
        doc={"asset":{"version":"2.0","generator":"Mirror offline builtin-guide builder v2"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":nodes,"meshes":gm,"materials":mats,"buffers":[{"byteLength":logical}],"bufferViews":self.views,"accessors":self.accessors}
        text=json.dumps(doc,separators=(',',':'),ensure_ascii=True).encode()
        text+=b' '*((-len(text))%4);self.data.extend(b'\0'*((-len(self.data))%4))
        payload=struct.pack('<III',0x46546c67,2,12+8+len(text)+8+len(self.data))+struct.pack('<II',len(text),0x4e4f534a)+text+struct.pack('<II',len(self.data),0x004e4942)+self.data
        path.write_bytes(payload);return doc


def derived(target,sources):
    DERIVED.append({'operation':'product','sources':sources,'mesh':'Face','target':target,'gain':1.})
    return target


def fix_lips(face):
    p=face.v
    close=np.zeros_like(p)
    jaw=face.deltas['jawOpen'].astype(np.float64)
    # The lip ribbon's outside contour must follow the surrounding skin, rather
    # than the inner vermilion's stronger jaw weighting. Otherwise even a sealed
    # inner rim floats away from the mouth cutout, exposing teeth below the lip.
    for i,tag in enumerate(face.tags):
        if tag[0]!='lip':continue
        skin=deform(p[i],('skin',),'jawOpen')-p[i]
        # A common lip-depth sample keeps nested ribbon rows ordered in XY.
        # Actual Z still follows skin; varying depth must not drag an inner row
        # past the next outside row at the corner while the seam closes.
        sample=np.array([p[i,0],p[i,1],.699])
        skin[1]=(deform(sample,('skin',),'jawOpen')-sample)[1]
        jaw[i]=skin
    face.deltas['jawOpen']=jaw.astype(np.float32)
    full=p+jaw
    for i,tag in enumerate(face.tags):
        if tag[0]!='lip':continue
        t,theta=tag[1:]
        w=(1-t)**1.35
        x,y,z=p[i]
        # Closed inner rims meet at the same seam without crossing.
        # Outer lip boundaries and all chin/cheek/mandible vertices retain the jaw-open pose.
        seam=np.array([x,-.29,.655-.030*(x/.242)**2])
        close[i]=w*(seam-p[i])
        open_p=p[i]+jaw[i]
        mid=deform(np.array([x,-.29,.699]),('skin',),'jawOpen')
        open_seam=np.array([open_p[0],mid[1],.660-.026*(x/.242)**2])
        full[i]=open_p+w*(open_seam-open_p)
    face.deltas['mouthClose']=close.astype(np.float32)
    key=derived('corrective_jawOpen_mouthClose',['jawOpen','mouthClose'])
    face.deltas[key]=(full-p-jaw-close).astype(np.float32)


def eye_surface_floor(points,indices,center,margin=.020):
    result=points.copy()
    for i in indices:
        d2=(result[i,0]-center[0])**2+(result[i,1]-center[1])**2
        if d2<EYE_RADIUS**2:
            result[i,2]=max(result[i,2],center[2]+math.sqrt(EYE_RADIUS**2-d2)+margin)
    return result


def fix_eyes(face):
    p=face.v
    for side in ('Left','Right'):
        b,w,s=('eyeBlink'+side,'eyeWide'+side,'eyeSquint'+side)
        selected=[i for i,t in enumerate(face.tags) if t[0] in ('lid','crease') and t[1]==side]
        center=EYE_CENTERS[side]
        for source in (b,w,s):
            pose=eye_surface_floor(p+face.deltas[source],selected,center)
            if source==b:
                # Coincident upper/lower inner rim closes the real mesh seam.
                # The previous 99% reduction left a thin visible white globe strip.
                for i in selected:
                    tag=face.tags[i]
                    if tag[0]=='lid':
                        pose[i,1]-=(p[i,1]-center[1])*.01*(1-tag[2])**1.5
            face.deltas[source]=(pose-p).astype(np.float32)
        B,W,S=[face.deltas[source].astype(np.float64) for source in (b,w,s)]
        ws=eye_surface_floor(p+W+S,selected,center)
        ws_delta=ws-p-W-S
        # Trilinear inclusion/exclusion: blink=1 selects the same fully closed lid,
        # regardless of simultaneous wide/squint. No eyeball size or position is changed.
        face.deltas[derived('corrective_'+b+'_'+w,[b,w])]=(-W).astype(np.float32)
        face.deltas[derived('corrective_'+b+'_'+s,[b,s])]=(-S).astype(np.float32)
        face.deltas[derived('corrective_'+w+'_'+s,[w,s])]=ws_delta.astype(np.float32)
        face.deltas[derived('corrective_'+b+'_'+w+'_'+s,[b,w,s])]=(-ws_delta).astype(np.float32)


def seal_lip_outer_edge(face):
    # This ribbon is deliberately separate from the Boolean head mesh. A short
    # inward-facing side wall closes its exposed outer thickness under jaw motion;
    # it is actual opaque skin geometry, not a hidden jaw/teeth suppression.
    outer=[i for i,t in enumerate(face.tags) if t[0]=='lip' and t[1]==1]
    start=len(face.v)
    inset=face.v[outer].copy();inset[:,2]-=.10
    face.v=np.vstack([face.v,inset])
    face.c=np.vstack([face.c,face.c[outer]])
    face.tags.extend([('lip_skirt',face.tags[i][2]) for i in outer])
    for name in face.deltas:
        face.deltas[name]=np.vstack([face.deltas[name],face.deltas[name][outer]])
    faces=[]
    for k,a in enumerate(outer):
        n=(k+1)%len(outer);b=outer[n]
        faces.extend([(a,start+k,start+n),(a,start+n,b)])
    face.f=np.vstack([face.f,np.array(faces,dtype=np.uint32)])


def weights(sources):
    out={name:min(1.,max(0.,float(value))) for name,value in sources.items()}
    for binding in DERIVED:
        out[binding['target']]=math.prod(out.get(name,0.) for name in binding['sources'])*binding['gain']
    return out


def deform_mesh(mesh,sources):
    p=mesh.v.copy()
    for name,value in weights(sources).items():
        if name in mesh.deltas:p+=mesh.deltas[name]*value
    return p


def areas_xy(p,triangles):
    a=p[triangles[:,1],:2]-p[triangles[:,0],:2]
    b=p[triangles[:,2],:2]-p[triangles[:,0],:2]
    return a[:,0]*b[:,1]-a[:,1]*b[:,0]


def validate_combinations(meshes):
    face=meshes[0];p=face.v
    lips=[i for i,t in enumerate(face.tags) if t[0]=='lip']
    inner=[i for i in lips if face.tags[i][1]==0]
    nonlip=[i for i,t in enumerate(face.tags) if t[0]!='lip']
    lipset=set(lips);liptri=np.array([t for t in face.f if set(t).issubset(lipset)])
    max_closed_gap=0.;min_lip_area=1.;mouth_cases=0
    for j,c in itertools.product(np.linspace(0,1,21),repeat=2):
        q=deform_mesh(face,{'jawOpen':j,'mouthClose':c})
        assert np.isfinite(q).all()
        expected=p[nonlip]+face.deltas['jawOpen'][nonlip]*j
        assert np.max(abs(q[nonlip]-expected))<1e-8,'closed lips changed mandible/cheek pose'
        # Pair upper/lower inner rim vertices at identical x (theta and 2pi-theta).
        for k in range(1,40):
            gap=q[inner[k],1]-q[inner[80-k],1]
            assert gap>=-1e-7,('lip crossing',j,c,k,gap)
            if c==1:max_closed_gap=max(max_closed_gap,gap)
        area=areas_xy(q,liptri);min_lip_area=min(min_lip_area,float(area.min()))
        assert area.min()>-1e-8,('lip folded through itself in front projection',j,c,float(area.min()),[face.tags[i] for i in liptri[area.argmin()]],q[liptri[area.argmin()]].tolist())
        mouth_cases+=1
    assert max_closed_gap<.0013
    eyes=[]
    for side in ('Left','Right'):
        b,w,s=('eyeBlink'+side,'eyeWide'+side,'eyeSquint'+side)
        ids=np.array([i for i,t in enumerate(face.tags) if t[0]=='lid' and t[1]==side])
        rim=[i for i,t in enumerate(face.tags) if t[0]=='lid' and t[1]==side and t[2]==0]
        other=[i for i,t in enumerate(face.tags) if t[0]=='lid' and t[1]!=side]
        lidset=set(ids.tolist());tris=np.array([t for t in face.f if set(t).issubset(lidset)])
        center=EYE_CENTERS[side];min_clearance=1.;minimum_gap=1.;n=0;min_area=1.
        for blink,wide,squint in itertools.product(np.linspace(0,1,11),repeat=3):
            q=deform_mesh(face,{b:blink,w:wide,s:squint})
            assert np.max(abs(q[other]-p[other]))==0,'eyelid source leaked to the opposite eye'
            for k in range(1,32):
                gap=q[rim[k],1]-q[rim[64-k],1]
                minimum_gap=min(minimum_gap,gap)
                assert gap>=-1e-7,('eyelid crossed',side,blink,wide,squint,k,gap)
            delta=q[ids,:2]-center[:2]
            r2=np.sum(delta*delta,axis=1);inside=r2<EYE_RADIUS**2
            z_sphere=center[2]+np.sqrt(EYE_RADIUS**2-r2[inside])
            clearance=float((q[ids[inside],2]-z_sphere).min());min_clearance=min(min_clearance,clearance)
            assert clearance>.001,('eyelid entered globe',side,blink,wide,squint,clearance)
            area=float(areas_xy(q,tris).min());min_area=min(min_area,area)
            assert area>0,('eyelid triangle inverted in projection',side,blink,wide,squint,area)
            if blink==1:
                closed=p+face.deltas[b]
                assert np.max(abs(q[ids]-closed[ids]))<1e-7,'wide/squint reopened closed lid'
            n+=1
        eyes.append({'side':side,'gridPoses':n,'minimumGlobeClearanceMeters':min_clearance*SCALE,
                     'minimumRimGapMeters':minimum_gap*SCALE,'minimumProjectedDoubleTriangleArea':min_area*SCALE**2})
    # Recompute the actual deformed normals for representative mixed facial states.
    normal_cases=0
    for j,c,b,w,s in itertools.product((0,1),repeat=5):
        q=deform_mesh(face,{'jawOpen':j,'mouthClose':c,'eyeBlinkLeft':b,'eyeWideLeft':w,'eyeSquintLeft':s,
                       'mouthSmileLeft':.6,'mouthSmileRight':.6})
        normal=normals(q,face.f)
        assert np.isfinite(normal).all() and np.max(abs(np.linalg.norm(normal,axis=1)-1))<2e-6
        normal_cases+=1
    return {'passed':True,'mouthGridPoses':mouth_cases,'maxFullyClosedLipGapMeters':max_closed_gap*SCALE,
            'minimumLipProjectedDoubleTriangleArea':min_lip_area*SCALE**2,'nonLipJawPreserved':True,
            'eyeGrids':eyes,'mixedNormalPoses':normal_cases,
            'scope':'jawOpen/mouthClose grid; each independent blink/wide/squint cube; representative smile/head/gaze visual cases. Not every simultaneous 51-action combination.'}




def write_manifest():
    manifest={"schemaVersion":2,"id":"builtin-guide","displayName":"镜中向导 · 组合修正版","model":"character.glb",
      "modelSha256":hashlib.sha256((DEST/'character.glb').read_bytes()).hexdigest(),"inputSchema":"mediapipe-face-blendshapes-v1",
      "coordinates":{"up":"+Y","forward":"+Z","subjectLeft":"+X","units":"meters","rootScale":1,"headPivot":[0,.1716,0]},
      "normalPolicy":"recompute-deformed","materialProfile":"mirror-lit-v1",
      "rig":{"headNode":"Head","jawMode":"morph","jawAttachmentNode":"JawAttachments","jawAxis":[1,0,0],"jawOpenDegrees":22,"jawLateralMeters":.00845,"jawForwardMeters":.0078,
       "leftEyeNode":"EyeLeft","rightEyeNode":"EyeRight","gazeMode":"joint","gazeYawDegrees":18,"gazePitchDegrees":15},
      "bindings":[{"source":name,"mesh":mesh,"target":name,"gain":1} for mesh in ("Face","MouthInterior") for name in BASE_TARGETS],
      "requiredRigFeatures":["product-correctives-v1"],"derivedBindings":DERIVED,
      "ignoredSources":["_neutral"],"missingSources":[],"incompleteSources":[],
      "license":{"name":"CC0-1.0","file":"LICENSE.txt"},
      "validation":{"stage":"procedural prototype; not final art acceptance","morphActionsAuthored":43,"derivedGeometryTargets":9,"rigidGazeActions":8,
       "corePreviewCases":["neutral","left-blink","jaw-open","smile","three-quarter","gaze-left"],
       "knownLimitations":["Jaw/close and blink/wide/squint combinations pass grid and offline visual checks; real GLES remains a separate acceptance step.",
          "Extreme simultaneous mouth/eyelid coefficients require rig constraints and further visual acceptance.",
          "Non-core action shapes are independently named procedural drafts; 52-input support is not a claim of 52 artist-approved expressions."]}}
    (DEST/'avatar.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    (DEST/'LICENSE.txt').write_text("Builtin Guide — original procedural character geometry and colors.\n\nCreated for this project by the checked-in offline build_builtin_avatar.py script.\nNo external meshes, scans, image textures, fonts, or generated third-party assets are incorporated.\n\nTo the extent copyright applies, this asset package is dedicated to the public domain under CC0 1.0 Universal.\nSPDX-License-Identifier: CC0-1.0\nLegal text: https://creativecommons.org/publicdomain/zero/1.0/legalcode\n\nBlender is a build/render tool only; no Blender sample assets are included.\nThe source script is editable and geometrically reproducible; Boolean vertex/face ordering may vary.\n",encoding='utf-8')


def validate(meshes,doc):
    report={"passed":True,"checks":[],"meshes":[],"warnings":[],"previewIs":"ordinary geometric rendering, not image generation"}
    totalv=sum(len(m.v) for m in meshes);totalt=sum(len(m.f) for m in meshes)
    assert totalv<=20_000 and totalt<=30_000 and len(meshes)<=8
    report.update(vertices=totalv,triangles=totalt,primitives=len(meshes),fileBytes=(DEST/'character.glb').stat().st_size)
    for m in meshes:
        assert np.isfinite(m.v).all() and np.isfinite(m.c).all() and m.f.min()>=0 and m.f.max()<len(m.v)
        n=normals(m.v,m.f);assert np.isfinite(n).all() and np.max(abs(np.linalg.norm(n,axis=1)-1))<1e-5
        for name,d in m.deltas.items():assert d.shape==m.v.shape and np.isfinite(d).all()
        edges=Counter(tuple(sorted((int(tri[a]),int(tri[b])))) for tri in m.f for a,b in ((0,1),(1,2),(2,0)))
        # Overlapping authored pieces and opening boundaries are deliberate. Nonmanifold sharing is not.
        assert max(edges.values())<=2,(m.name,"nonmanifold edge")
        report['meshes'].append({"name":m.name,"vertices":len(m.v),"triangles":len(m.f),"targets":len(m.deltas),"boundaryEdges":sum(v==1 for v in edges.values()),
          "minPosition":((m.v-m.origin)*SCALE).min(axis=0).tolist(),"maxPosition":((m.v-m.origin)*SCALE).max(axis=0).tolist()})
    face=meshes[0]
    for side in ('Left','Right'):
        ids=[i for i,t in enumerate(face.tags) if t[0]=='lid' and t[1]==side and t[2]==0]
        p=face.v[ids];q=p+face.deltas['eyeBlink'+side][ids]
        assert np.ptp(q[:,1])<.003
        other=[i for i,t in enumerate(face.tags) if t[0]=='lid' and t[1]!=side]
        assert np.max(abs(face.deltas['eyeBlink'+side][other]))==0
        report['checks'].append({"check":"independent lid closure "+side,"neutralOpening":float(np.ptp(p[:,1]))*SCALE,"closedOpening":float(np.ptp(q[:,1]))*SCALE})
    report['checks'].append({"check":"all GLB morph counts match their base POSITION; all indices finite/in range; all base normals unit; no edge shared by >2 faces","passed":True})
    report['warnings'].append("Independent numerical checks are not a Khronos Validator report. Project AvatarGlbLoader host validation must run against character.glb.")
    (REPORT/'geometry-check.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')


def preview(meshes):
    bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
    objects={}
    for m in meshes:
        data=bpy.data.meshes.new(m.name);data.from_pydata(m.v.tolist(),[],m.f.tolist());data.update()
        color=data.color_attributes.new(name='Color',type='FLOAT_COLOR',domain='POINT')
        color.data.foreach_set('color',m.c.ravel())
        obj=bpy.data.objects.new(m.name,data);bpy.context.collection.objects.link(obj)
        for p in data.polygons:p.use_smooth=True
        mat=bpy.data.materials.new(m.name);mat.use_nodes=True;mat.use_backface_culling=True
        bs=mat.node_tree.nodes.get('Principled BSDF');bs.inputs['Roughness'].default_value=m.rough
        attr=mat.node_tree.nodes.new('ShaderNodeVertexColor');attr.layer_name='Color'
        mat.node_tree.links.new(attr.outputs['Color'],bs.inputs['Base Color'])
        data.materials.append(mat);objects[m.name]=obj
    scene=bpy.context.scene;scene.render.engine='BLENDER_EEVEE_NEXT'
    scene.render.resolution_x=720;scene.render.resolution_y=900;scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG';scene.render.film_transparent=False
    scene.world.color=(.16,.18,.20)
    scene.view_settings.view_transform='AgX'
    bpy.ops.object.camera_add(location=(0,.15,6.8));camera=bpy.context.object
    camera.data.type='ORTHO';camera.data.ortho_scale=3.80;scene.camera=camera
    for loc,power,size in [((-3.5,4.5,4.5),650,4),((3,2,3),430,3),((0,3,-3),800,3)]:
        bpy.ops.object.light_add(type='AREA',location=loc);light=bpy.context.object;light.data.energy=power;light.data.shape='DISK';light.data.size=size
        light.rotation_euler=(Vector((0,-.1,0))-light.location).to_track_quat('-Z','Y').to_euler()
    cases=[('neutral',{},0),('left-blink',{'eyeBlinkLeft':1},0),('jaw-open',{'jawOpen':1},0),('smile',{'mouthSmileLeft':.9,'mouthSmileRight':.9,'cheekSquintLeft':.35,'cheekSquintRight':.35},0),('three-quarter',{},25),('gaze-left',{'eyeLookOutLeft':1,'eyeLookInRight':1},0)]
    for name,weights,yaw in cases:
        for m in meshes:
            v=m.v.copy()
            for key,w in weights.items():
                if key in m.deltas:v+=w*m.deltas[key]
            if m.name=='JawAttachments' and weights.get('jawOpen',0):v=np.array([rotate_x(p,math.radians(22)*weights['jawOpen']) for p in v])
            if m.name.startswith('Eye') and name=='gaze-left':
                angle=math.radians(18);c,s=math.cos(angle),math.sin(angle)
                v=(v-m.origin)@np.array([[c,0,-s],[0,1,0],[s,0,c]])+m.origin
            objects[m.name].data.vertices.foreach_set('co',v.ravel());objects[m.name].data.update()
        rad=math.radians(yaw);camera.location=(6.8*math.sin(rad),.12,6.8*math.cos(rad))
        back=(camera.location-Vector((0,-.35,0))).normalized()
        right=Vector((0,1,0)).cross(back).normalized()
        up=back.cross(right).normalized()
        camera.rotation_euler=Matrix((right,up,back)).transposed().to_euler()
        scene.render.filepath=str(REPORT/(name+'.png'));bpy.ops.render.render(write_still=True)
    # Save editable neutral geometry and actual shape keys, not the last rendered pose.
    head=bpy.data.objects.new('Head',None);bpy.context.collection.objects.link(head);head.location=HEAD_PIVOT
    for m in meshes:
        obj=objects[m.name];local=m.v-m.origin
        obj.data.vertices.foreach_set('co',local.ravel());obj.data.update()
        if m.name=='Shoulders':obj.location=m.origin
        else:obj.parent=head;obj.location=m.origin-HEAD_PIVOT
        if m.morph:
            obj.shape_key_add(name='Basis')
            for target,delta in m.deltas.items():
                key=obj.shape_key_add(name=target);key.data.foreach_set('co',(local+delta).ravel())
    # Save an editable source scene in the review folder, not in the APK assets.
    bpy.ops.wm.save_as_mainfile(filepath=str(REPORT/'builtin-guide-source.blend'))


def render_combinations(meshes):
    bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
    objects={}
    for m in meshes:
        data=bpy.data.meshes.new(m.name);data.from_pydata(m.v.tolist(),[],m.f.tolist());data.update()
        attr=data.color_attributes.new(name='Color',type='FLOAT_COLOR',domain='POINT');attr.data.foreach_set('color',m.c.ravel())
        obj=bpy.data.objects.new(m.name,data);bpy.context.collection.objects.link(obj)
        for polygon in data.polygons:polygon.use_smooth=True
        mat=bpy.data.materials.new(m.name);mat.use_nodes=True;mat.use_backface_culling=True
        bs=mat.node_tree.nodes.get('Principled BSDF');bs.inputs['Roughness'].default_value=m.rough
        color=mat.node_tree.nodes.new('ShaderNodeVertexColor');color.layer_name='Color'
        mat.node_tree.links.new(color.outputs['Color'],bs.inputs['Base Color']);data.materials.append(mat);objects[m.name]=obj
    scene=bpy.context.scene;scene.render.engine='BLENDER_EEVEE_NEXT'
    scene.render.resolution_x=640;scene.render.resolution_y=800;scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG';scene.world.color=(.16,.18,.20);scene.view_settings.view_transform='AgX'
    bpy.ops.object.camera_add(location=(0,.12,6.8));cam=bpy.context.object;cam.data.type='ORTHO';cam.data.ortho_scale=3.7;scene.camera=cam
    back=(cam.location-Vector((0,-.34,0))).normalized();right=Vector((0,1,0)).cross(back).normalized();up=back.cross(right)
    cam.rotation_euler=Matrix((right,up,back)).transposed().to_euler()
    for loc,power,size in [((-3.5,4.5,4.5),650,4),((3,2,3),430,3),((0,3,-3),800,3)]:
        bpy.ops.object.light_add(type='AREA',location=loc);light=bpy.context.object;light.data.energy=power;light.data.shape='DISK';light.data.size=size
        light.rotation_euler=(Vector((0,-.1,0))-light.location).to_track_quat('-Z','Y').to_euler()
    cases=[]
    for j,c in itertools.product((0,.5,1),repeat=2):
        cases.append((f'jaw{int(j*100):03d}-close{int(c*100):03d}',{'jawOpen':j,'mouthClose':c},0,0))
    for side in ('Left','Right'):
        cases.append(('extreme-'+side,{'eyeBlink'+side:1,'eyeWide'+side:1,'eyeSquint'+side:1},0,0))
    cases.extend([
        ('eyes-half-wide-squint',{'eyeBlinkLeft':.5,'eyeWideLeft':1,'eyeSquintLeft':1,'eyeBlinkRight':.5,'eyeWideRight':1,'eyeSquintRight':1},0,0),
        ('all-closed-yaw-left',{'jawOpen':1,'mouthClose':1,'eyeBlinkLeft':1,'eyeWideLeft':1,'eyeSquintLeft':1,'eyeBlinkRight':1,'eyeWideRight':1,'eyeSquintRight':1,'eyeLookOutLeft':1,'eyeLookInRight':1},25,12),
        ('all-closed-yaw-right',{'jawOpen':1,'mouthClose':1,'eyeBlinkLeft':1,'eyeWideLeft':1,'eyeSquintLeft':1,'eyeBlinkRight':1,'eyeWideRight':1,'eyeSquintRight':1,'eyeLookInLeft':1,'eyeLookOutRight':1},-25,-12),
        ('open-smile-quarter',{'jawOpen':1,'mouthClose':.5,'mouthSmileLeft':.6,'mouthSmileRight':.6,'eyeWideLeft':1,'eyeWideRight':1},25,-12),
        ('closed-smile-quarter',{'jawOpen':1,'mouthClose':1,'mouthSmileLeft':.6,'mouthSmileRight':.6},-25,12),
    ])
    for name,sources,head_yaw,head_pitch in cases:
        for m in meshes:
            v=deform_mesh(m,sources)
            if m.name=='JawAttachments':
                v=np.array([rotate_x(p,math.radians(22)*sources.get('jawOpen',0)) for p in v])
            if m.name.startswith('Eye'):
                side=m.name[3:]
                yaw=sources.get('eyeLookOut'+side,0)-sources.get('eyeLookIn'+side,0)
                if side=='Right':yaw=-yaw
                pitch=sources.get('eyeLookDown'+side,0)-sources.get('eyeLookUp'+side,0)
                r=np.array(Matrix.Rotation(math.radians(18)*yaw,3,'Y')@Matrix.Rotation(math.radians(15)*pitch,3,'X'))
                v=(v-m.origin)@r.T+m.origin
            if m.name!='Shoulders':
                r=np.array(Matrix.Rotation(math.radians(head_yaw),3,'Y')@Matrix.Rotation(math.radians(head_pitch),3,'X'))
                v=(v-HEAD_PIVOT)@r.T+HEAD_PIVOT
            objects[m.name].data.vertices.foreach_set('co',v.ravel());objects[m.name].data.update()
        scene.render.filepath=str(REPORT/(name+'.png'));bpy.ops.render.render(write_still=True)
    (REPORT/'visual-cases.json').write_text(json.dumps([{'image':n+'.png','sources':s,'headYaw':y,'headPitch':p} for n,s,y,p in cases],indent=2)+'\n')




def main():
    global DEST,REPORT
    args=sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else []
    ap=argparse.ArgumentParser();ap.add_argument('--no-render',action='store_true')
    ap.add_argument('--output',type=Path);ap.add_argument('--report-dir',type=Path);opts=ap.parse_args(args)
    if opts.output:DEST=opts.output.resolve()
    if opts.report_dir:REPORT=opts.report_dir.resolve()
    DEST.mkdir(parents=True,exist_ok=True);REPORT.mkdir(parents=True,exist_ok=True)
    bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
    meshes=[build_face(),build_hair(),build_eye('Left'),build_eye('Right'),build_mouth(),build_jaw(),build_shoulders()]
    # Same v2 corrective geometry verified in the independent staging candidate.
    tongue=(meshes[5].c[:,0]>.3)&(meshes[5].c[:,1]<.3)
    meshes[5].v[tongue,2]-=.10
    meshes[5].v[~tongue,2]-=.045
    fix_lips(meshes[0]);fix_eyes(meshes[0]);seal_lip_outer_edge(meshes[0])
    for entry in DERIVED:
        target=entry['target'];TARGETS.append(target)
        meshes[4].deltas[target]=np.zeros_like(meshes[4].v,dtype=np.float32)
    report=validate_combinations(meshes)
    doc=Glb().write(meshes,DEST/'character.glb');write_manifest();validate(meshes,doc)
    report['sha256']=hashlib.sha256((DEST/'character.glb').read_bytes()).hexdigest()
    report['derivedBindings']=DERIVED
    (REPORT/'combination-check.json').write_text(json.dumps(report,indent=2)+'\n')
    if not opts.no_render:
        preview(meshes);render_combinations(meshes)
    print('BUILTIN_AVATAR_READY',DEST,[(m.name,len(m.v),len(m.f)) for m in meshes])


if __name__=='__main__':main()
