import bpy, math, os
FBX = os.environ.get('FBX', os.path.expanduser('~/Downloads/airpod3d/Apple+AirPods+Pro.fbx'))
from mathutils import Vector
def setup(res=(600,600), samples=48):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.fbx(filepath=FBX)
    sc=bpy.context.scene
    for o in list(bpy.data.objects):
        if o.type in ('LIGHT','CAMERA') or o.name in ('地形_2','平面','圆柱体'): bpy.data.objects.remove(o)
    def mat(name, col, rough, metal=0.0, coat=0.0, emit=None):
        m=bpy.data.materials.new(name); m.use_nodes=True; b=m.node_tree.nodes['Principled BSDF']
        b.inputs['Base Color'].default_value=(*col,1); b.inputs['Roughness'].default_value=rough
        b.inputs['Metallic'].default_value=metal; b.inputs['Coat Weight'].default_value=coat
        b.inputs['Coat Roughness'].default_value=0.15
        if emit: b.inputs['Emission Color'].default_value=(*emit,1); b.inputs['Emission Strength'].default_value=0
        return m
    white=mat('white',(0.86,0.86,0.87),0.32,coat=0.5)
    tip=mat('tip',(0.80,0.80,0.81),0.6)
    black=mat('black',(0.02,0.02,0.022),0.35)
    mesh=mat('mesh',(0.05,0.05,0.05),0.5,metal=0.6)
    metal=mat('metal',(0.75,0.75,0.77),0.25,metal=1.0)
    led=mat('led',(0.05,0.05,0.05),0.4)
    for o in bpy.data.objects:
        if o.type!='MESH': continue
        names=[m.name for m in o.data.materials]
        n=names[0] if names else ''
        m = black if '黑色' in n else mesh if '金属网' in n else tip if '耳罩' in n else \
            metal if '金属' in n else led if 'Incandescent' in n else white
        o.data.materials.clear(); o.data.materials.append(m)
        for p in o.data.polygons: p.use_smooth=True
    # đèn studio
    w=bpy.data.worlds.new('w'); sc.world=w; w.use_nodes=True
    bg=w.node_tree.nodes['Background']; bg.inputs[0].default_value=(1,1,1,1); bg.inputs[1].default_value=0.55
    def area(name, loc, size, energy):
        l=bpy.data.lights.new(name,'AREA'); l.size=size; l.energy=energy
        o=bpy.data.objects.new(name,l); o.location=loc; sc.collection.objects.link(o)
        d=Vector((0,0,0.3))-Vector(loc); o.rotation_euler=d.to_track_quat('-Z','Y').to_euler()
    area('key',(-1.5,-2.0,3.0),2.5,60)
    area('fill',(2.2,-1.5,1.0),2.0,25)
    area('top',(0,0.5,3.5),3.0,40)
    area('rim',(0,3,1.5),2.0,20)
    # sàn bắt bóng
    bpy.ops.mesh.primitive_plane_add(size=20, location=(0,0,0))
    fl=bpy.context.active_object; fl.is_shadow_catcher=True; fl.name='floor'
    # camera trực giao, nghiêng 20°
    cam=bpy.data.cameras.new('cam'); cam.type='ORTHO'
    co=bpy.data.objects.new('cam',cam); sc.collection.objects.link(co); sc.camera=co
    E=math.radians(20); tgt=Vector((0,0,0.3)); dist=10
    co.location=tgt+Vector((0,-math.cos(E),math.sin(E)))*dist
    co.rotation_euler=(math.radians(90)-E,0,0)
    sc.render.engine='CYCLES'; sc.cycles.samples=samples; sc.cycles.use_denoising=True
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.film_transparent=True; sc.view_settings.view_transform='Standard'
    return sc, co, cam
def bbox(objs):
    pts=[o.matrix_world @ Vector(c) for o in objs if o.type=='MESH' for c in o.bound_box]
    return [min(p[i] for p in pts) for i in range(3)], [max(p[i] for p in pts) for i in range(3)]
def kids(name): 
    r=[]; st=[bpy.data.objects[name]]
    while st:
        o=st.pop(); r.append(o); st+=list(o.children)
    return r
