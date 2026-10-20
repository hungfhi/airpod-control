"""Render hộp AirPods Pro từ model 3D (airpod3d/Apple+AirPods+Pro.fbx) -> case_*.png, pod_l/r.png, HeroMeta.kt
Chạy: FBX=~/Downloads/airpod3d/Apple+AirPods+Pro.fbx python3 gen/render_all.py x <thư mục output>   (cần: pip install bpy)"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scene import *
from bpy_extras.object_utils import world_to_camera_view as w2c
from PIL import Image
OUT = sys.argv[2] if len(sys.argv) > 2 else '/home/claude/b3d/out'
os.makedirs(OUT, exist_ok=True)
W, H, MAXDEG = 640, 760, 110.0
N, SAMPLES = int(os.environ.get('N', 16)), int(os.environ.get('SAMPLES', 64))
sc, co, cam = setup((W, H), SAMPLES)
bpy.data.objects['充电盒子'].rotation_euler.z = 0
bpy.context.view_layer.update()
mn, mx = bbox(kids('舱位'))
fl = bpy.data.objects['floor']; fl.scale = (0.07, 0.05, 1); fl.location.z = mn[2]
cam.ortho_scale = 0.94
bpy.context.view_layer.update()
def px(p):
    v = w2c(sc, co, Vector(p)); return v.x * W, (1 - v.y) * H
# đặt đáy hộp cách mép dưới 40px
_, by = px((0, mn[1], mn[2])); 
UP = co.matrix_world.to_3x3().col[1]; RT = co.matrix_world.to_3x3().col[0]
co.location += UP * ((H - 40) - by) / H * cam.ortho_scale
bpy.context.view_layer.update()
_, by = px((0, mn[1], mn[2])); print('bottom px', by)
# toạ độ cho app
w1 = bbox([bpy.data.objects['object_122']]); w2 = bbox([bpy.data.objects['object_123']])
wl = px(((w1[0][0] + w1[1][0]) / 2, (w1[0][1] + w1[1][1]) / 2, mx[2]))
wr = px(((w2[0][0] + w2[1][0]) / 2, (w2[0][1] + w2[1][1]) / 2, mx[2]))
rim = px((0, w1[0][1], mx[2]))[1]
lb = bbox([bpy.data.objects['object_106']])
led = px(tuple((lb[0][i] + lb[1][i]) / 2 for i in range(3)))
ledr = (lb[1][0] - lb[0][0]) / 2 / cam.ortho_scale * H
pods = kids('盒外_L') + kids('盒外_R')
lid = bpy.data.objects['盖子']
# bóng: chỉ giữ vùng bóng mềm sát đáy hộp (shadow catcher bắt cả nền rộng)
import numpy as np
fl_l, _ = px((mn[0], mn[1], mn[2])); fl_r, _ = px((mx[0], mn[1], mn[2])); _, fl_y = px((0, (mn[1] + mx[1]) / 2, mn[2]))
def fix_shadow(path):
    a = np.asarray(Image.open(path).convert('RGBA')).astype(np.float32)
    lum = a[..., :3].mean(-1); al = a[..., 3]
    yy, xx = np.mgrid[0:H, 0:W]
    cx, rx = (fl_l + fl_r) / 2, (fl_r - fl_l) / 2 * 1.04
    d = np.sqrt(((xx - cx) / rx) ** 2 + ((yy - fl_y) / 34.0) ** 2)
    mask = np.clip(1.15 - d, 0, 1) ** 1.6
    sh = (al < 252) & (lum < 90)
    a[..., 3] = np.where(sh, al * mask, al); a[..., :3] = np.where(sh[..., None], 0, a[..., :3])
    Image.fromarray(a.astype(np.uint8)).save(path, optimize=True)
# --- frames hộp ---
for o in pods: o.hide_render = True
for i in range(N):
    lid.rotation_euler.x = math.radians(-MAXDEG * i / (N - 1))
    sc.render.filepath = f'{OUT}/case_{i}.png'
    bpy.ops.render.render(write_still=True)
    fix_shadow(f'{OUT}/case_{i}.png')
# --- tai nghe (render riêng, cùng tỉ lệ, crop) ---
for o in kids('舱位') + kids('盖子') + [fl]: o.hide_render = True
podsz = {}
for side, root in (('l', '盒外_L'), ('r', '盒外_R')):
    for o in pods: o.hide_render = True
    for o in kids(root): o.hide_render = False
    home = co.location.copy()
    pb = bbox(kids(root)); c = tuple((pb[0][i] + pb[1][i]) / 2 for i in range(3)); cx, cy = px(c)
    co.location += RT * (cx - W / 2) / H * cam.ortho_scale - UP * (cy - H / 2) / H * cam.ortho_scale
    bpy.context.view_layer.update()
    sc.render.filepath = f'{OUT}/_pod.png'; bpy.ops.render.render(write_still=True)
    im = Image.open(f'{OUT}/_pod.png'); b = im.getchannel('A').point(lambda a: 255 if a > 3 else 0).getbbox()
    b = (b[0] - 2, b[1] - 2, b[2] + 2, b[3] + 2)
    im.crop(b).save(f'{OUT}/pod_{side}.png', optimize=True); podsz[side] = (b[2] - b[0], b[3] - b[1])
    co.location = home; bpy.context.view_layer.update()
os.remove(f'{OUT}/_pod.png')
LW, LH = podsz['l']; RW, RH = podsz['r']
meta = dict(N=N, W=W, H=H, RIM=rim, LX=wl[0] - LW / 2, RX=wr[0] - RW / 2, LW=LW, LH=LH, RW=RW, RH=RH,
            UPL=16.0, UPR=16.0 + max(0, LH - RH), REST=rim + 2, LEDX=led[0], LEDY=led[1], LEDR=ledr)
with open(f'{OUT}/HeroMeta.kt', 'w') as f:
    f.write('package dev.me.podpopup\n\n/** Sinh tự động từ gen/render_all.py (model airpod3d) – toạ độ pixel trong ảnh case_*.png */\nobject HeroMeta {\n')
    for k, v in meta.items():
        f.write(f'    const val {k} = {v}\n' if k == 'N' else f'    const val {k} = {float(v):.1f}f\n')
    f.write('    val FRAMES = intArrayOf(' + ', '.join(f'R.drawable.case_{i}' for i in range(N)) + ')\n')
    f.write('}\n')
print(meta)
