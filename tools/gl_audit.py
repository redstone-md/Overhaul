"""Audit every OpenGL entry point a mod pack reaches against what Overhaul's Vulkan frontend answers.

Usage: python -I gl_audit.py <pack mods dir> <minecraft mojmap jar> <overhaul src dir> <report out> <lwjgl-opengl jar>

Sources of use:
  * raw LWJGL GL calls (org/lwjgl/opengl/*.gl*) in every mod jar, nested jar-in-jar included;
  * calls into com/mojang/blaze3d/** (GlStateManager, RenderSystem, VertexBuffer, ...), resolved through the
    vanilla bytecode down to the GL natives they reach.
Coverage:
  * a GL native is "routed" when BlazeGlBoundary/Routes/Buffers answer it by name;
  * draws (glDraw*, glMultiDraw*, ...) unrouted are "draw-noop", everything else unrouted is "inert" (returns 0);
  * a path through a method an Overhaul mixin hooks counts as handled by that hook.
"""
import io
import json
import os
import re
import struct
import sys
import zipfile
from collections import defaultdict

GL_PKG = "org/lwjgl/opengl/"
BLAZE = "com/mojang/blaze3d/"
DRAW_PREFIXES = ("glDraw", "glMultiDraw", "glDispatch", "glBlitFramebuffer", "glBlitNamedFramebuffer")


# ---------------------------------------------------------------- class file reader
def _op_lengths():
    t = [0] * 256
    for o in (0x10, 0x12, 0xbc, 0xa9) + tuple(range(0x15, 0x1a)) + tuple(range(0x36, 0x3b)):
        t[o] = 1
    for o in (0x11, 0x13, 0x14, 0x84, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7) + tuple(range(0x99, 0xa9)) + tuple(range(0xb2, 0xb9)):
        t[o] = 2
    t[0xc5] = 3
    for o in (0xb9, 0xba, 0xc8, 0xc9):
        t[o] = 4
    return t


OPLEN = _op_lengths()


class ClassFile:
    def __init__(self, data):
        self.d = data
        self.p = 8
        n = self.u2()
        self.cp = [None] * n
        i = 1
        while i < n:
            tag = self.u1()
            if tag == 1:
                ln = self.u2()
                self.cp[i] = (1, self.d[self.p:self.p + ln].decode("utf-8", "replace"))
                self.p += ln
            elif tag in (3, 4):
                self.p += 4
            elif tag in (5, 6):
                self.p += 8
                i += 1
            elif tag in (7, 8, 16, 19, 20):
                self.cp[i] = (tag, self.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                self.cp[i] = (tag, self.u2(), self.u2())
            elif tag == 15:
                self.cp[i] = (tag, self.u1(), self.u2())
            else:
                raise ValueError("bad constant tag %d" % tag)
            i += 1
        self.u2()
        self.name = self.cls(self.u2())
        sup = self.u2()
        self.super = self.cls(sup) if sup else None
        self.interfaces = [self.cls(self.u2()) for _ in range(self.u2())]
        self.fields = []
        for _ in range(self.u2()):
            self.u2()
            self.fields.append((self.utf(self.u2()), self.utf(self.u2())))
            self.skip_attrs()
        self.methods = []
        for _ in range(self.u2()):
            self.u2()
            name, desc = self.utf(self.u2()), self.utf(self.u2())
            code = None
            for _ in range(self.u2()):
                an, ln = self.utf(self.u2()), self.u4()
                if an == "Code":
                    cl = struct.unpack_from(">I", self.d, self.p + 4)[0]
                    code = self.d[self.p + 8:self.p + 8 + cl]
                self.p += ln
            self.methods.append((name, desc, code))
        self.bootstraps = []
        for _ in range(self.u2()):
            an, ln = self.utf(self.u2()), self.u4()
            end = self.p + ln
            if an == "BootstrapMethods":
                for _ in range(self.u2()):
                    self.u2()
                    self.bootstraps.append([self.u2() for _ in range(self.u2())])
            self.p = end

    def u1(self):
        v = self.d[self.p]; self.p += 1; return v

    def u2(self):
        v = struct.unpack_from(">H", self.d, self.p)[0]; self.p += 2; return v

    def u4(self):
        v = struct.unpack_from(">I", self.d, self.p)[0]; self.p += 4; return v

    def skip_attrs(self):
        for _ in range(self.u2()):
            self.p += 2
            length = self.u4()
            self.p += length

    def utf(self, i):
        return self.cp[i][1]

    def cls(self, i):
        return self.utf(self.cp[i][1])

    def member(self, i):
        e = self.cp[i]
        nt = self.cp[e[2]]
        return self.cls(e[1]), self.utf(nt[1]), self.utf(nt[2])

    def method_refs(self):
        for e_i, e in enumerate(self.cp):
            if e and e[0] in (10, 11):
                yield self.member(e_i)

    def calls(self, code):
        """Invoked members of one method body; invokedynamic yields the method handles in its bootstrap arguments."""
        p, n = 0, len(code)
        while p < n:
            op = code[p]
            if op in (0xb6, 0xb7, 0xb8, 0xb9):
                yield self.member(struct.unpack_from(">H", code, p + 1)[0])
            elif op == 0xba:
                dyn = self.cp[struct.unpack_from(">H", code, p + 1)[0]]
                for arg in self.bootstraps[dyn[1]] if dyn[1] < len(self.bootstraps) else ():
                    e = self.cp[arg]
                    if e and e[0] == 15 and self.cp[e[2]][0] in (10, 11):
                        yield self.member(e[2])
            if op == 0xaa:
                q = (p + 4) & ~3
                lo, hi = struct.unpack_from(">ii", code, q + 4)
                p = q + 12 + 4 * (hi - lo + 1)
            elif op == 0xab:
                q = (p + 4) & ~3
                p = q + 8 + 8 * struct.unpack_from(">i", code, q + 4)[0]
            elif op == 0xc4:
                p += 6 if code[p + 1] == 0x84 else 4
            else:
                p += 1 + OPLEN[op]


CAPS = set()


def load_caps(lwjgl_jar):
    """Native GL names are the long fields of GLCapabilities; LWJGL's Java names drop the 'v' of single-value getters."""
    with zipfile.ZipFile(lwjgl_jar) as z:
        CAPS.update(n for n, d in ClassFile(z.read("org/lwjgl/opengl/GLCapabilities.class")).fields if d == "J" and n.startswith("gl"))


def gl_native(owner, name):
    if not owner.startswith(GL_PKG) or owner.count("/") != 3:
        return None
    if name.startswith("ngl"):
        name = name[1:]
    if not (name.startswith("gl") and len(name) > 2 and name[2].isupper()):
        return None
    for candidate in (name, name + "v", name + "_v"):
        if candidate in CAPS:
            return candidate
    return name if not CAPS else None


# ---------------------------------------------------------------- mod scan
def walk_jar(data, label, sink):
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return
    for info in z.infolist():
        n = info.filename
        if n.endswith(".class") and not n.startswith("META-INF/versions/"):
            try:
                sink(label, ClassFile(z.read(info)))
            except Exception as failure:  # one broken class must not stop the audit
                print("skip %s!%s: %s" % (label, n, failure), file=sys.stderr)
        elif n.endswith(".jar"):
            walk_jar(z.read(info), label + "!" + os.path.basename(n), sink)


def scan_mods(folder):
    gl = defaultdict(lambda: defaultdict(set))     # native -> mod -> {class}
    blaze = defaultdict(lambda: defaultdict(set))  # (owner,name,desc) -> mod -> {class}

    def sink(label, cf):
        mod = label.split("!")[0]
        for owner, name, desc in cf.method_refs():
            native = gl_native(owner, name)
            if native:
                gl[native][mod].add(cf.name)
            elif owner.startswith(BLAZE):
                blaze[(owner, name, desc)][mod].add(cf.name)

    jars = sorted(f for f in os.listdir(folder) if f.endswith(".jar") and not f.lower().startswith("overhaul"))
    for jar in jars:
        with open(os.path.join(folder, jar), "rb") as h:
            walk_jar(h.read(), jar, sink)
    return gl, blaze, len(jars)


# ---------------------------------------------------------------- vanilla call graph
class Vanilla:
    def __init__(self, jar):
        self.classes = {}
        self.outside_gl = defaultdict(set)     # GL natives referenced by non-blaze3d vanilla classes
        self.outside_blaze = defaultdict(set)  # blaze3d members called from non-blaze3d vanilla classes
        with zipfile.ZipFile(jar) as z:
            for info in z.infolist():
                n = info.filename
                if not n.endswith(".class"):
                    continue
                if n.startswith(BLAZE):
                    cf = ClassFile(z.read(info))
                    self.classes[cf.name] = cf
                elif n.startswith(("net/minecraft/", "net/neoforged/")):
                    cf = ClassFile(z.read(info))
                    for owner, name, desc in cf.method_refs():
                        native = gl_native(owner, name)
                        if native:
                            self.outside_gl[native].add(cf.name)
                        elif owner.startswith(BLAZE):
                            self.outside_blaze[(owner, name, desc)].add(cf.name)
        self.bodies = {}
        for cf in self.classes.values():
            for name, desc, code in cf.methods:
                if code is not None:
                    self.bodies[(cf.name, name, desc)] = (cf, code)

    def resolve(self, owner, name, desc):
        seen = owner
        while seen in self.classes:
            if (seen, name, desc) in self.bodies:
                return (seen, name, desc)
            seen = self.classes[seen].super
        return None

    def reach(self, start, hooks, wrapped):
        """GL natives a blaze3d member reaches: native -> (handled_by_hook, via) with hook coverage inherited down the path."""
        out = {}
        stack = [(start, None)]
        seen = set()
        while stack:
            member, hook = stack.pop()
            key = self.resolve(*member)
            if key is None or (key, hook) in seen:
                continue
            seen.add((key, hook))
            owner, name, _ = key
            here = hook or (owner + "." + name if (owner, name) in hooks else None)
            cf, code = self.bodies[key]
            for t_owner, t_name, t_desc in cf.calls(code):
                native = gl_native(t_owner, t_name)
                if native:
                    via = here or ((owner + "." + name + " wraps") if (owner, name, native) in wrapped else None)
                    prev = out.get(native)
                    if prev is None or (prev[0] is None and via):
                        out[native] = (via, owner + "." + name)
                elif t_owner.startswith(BLAZE):
                    stack.append(((t_owner, t_name, t_desc), here))
        return out


# ---------------------------------------------------------------- Overhaul sources
HOOK_ANN = r"@(Inject|Redirect|WrapOperation|WrapWithCondition|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant|ModifyExpressionValue|ModifyReturnValue|WrapMethod)\s*\("


def overhaul_sources(root):
    routed, hooks, wrapped = set(), set(), set()
    route_files = ("BlazeGlBoundary.java", "BlazeGlRoutes.java", "BlazeGlBuffers.java")
    for base, _, files in os.walk(root):
        for f in files:
            if not f.endswith(".java"):
                continue
            text = open(os.path.join(base, f), encoding="utf-8").read()
            if f in route_files:
                routed.update(re.findall(r'"(gl[A-Z]\w*)"', text))
                for head, tail in re.findall(r'"(gl\w*)"\s*\+\s*buffer(?:\s*\+\s*"(\w*)")?', text):
                    routed.update(head + b + tail for b in ("Buffer", "NamedBuffer"))
            m = re.search(r"@Mixin\s*\(\s*(?:value\s*=\s*)?\{?([^)]*?)\}?\s*(?:,\s*\w+\s*=[^)]*)?\)", text)
            if not m:
                continue
            imports = dict((i.split(".")[-1], i.replace(".", "/")) for i in re.findall(r"import\s+([\w.]+);", text))
            targets = []
            for simple in re.findall(r"(\w+)\.class", m.group(1)):
                targets.append(imports.get(simple, simple))
            targets += [t.replace(".", "/") for t in re.findall(r'targets\s*=\s*"([^"]+)"', m.group(0))]
            for a in re.finditer(HOOK_ANN, text):
                chunk = text[a.end():a.end() + 900]
                mm = re.search(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', chunk)
                names = [re.split(r"[(]", s)[0] for s in re.findall(r'"([^"]+)"', mm.group(1))] if mm else []
                target = re.search(r'target\s*=\s*"L(org/lwjgl/opengl/\w+);(\w+)\(', chunk)
                for owner in targets:
                    for name in names:
                        if target:
                            wrapped.add((owner, name, gl_native(target.group(1), target.group(2))))
                        else:
                            hooks.add((owner, name))
            for o in re.finditer(r"@Overwrite[^;{]*?\s(\w+)\s*\(", text):
                for owner in targets:
                    hooks.add((owner, o.group(1)))
    return routed, hooks, wrapped


# ---------------------------------------------------------------- report
CATEGORIES = (
    ("draw", r"^gl(Multi)?Draw(Arrays|Elements|Range|Transform|Texture)|^glDispatch"),
    ("debug", r"Debug|ObjectLabel|GetError|^glGetString|^glHint|^glFinish|^glFlush$"),
    ("sync/query", r"Sync|Query|Queries|Fence|MemoryBarrier"),
    ("shader", r"Shader|Program|Uniform|AttribLocation|FragData|TransformFeedback|Patch"),
    ("vertex", r"VertexAttrib|VertexArray|VertexBuffer|VertexBinding|BindVertex"),
    ("framebuffer", r"Framebuffer|Renderbuffer|ReadBuffer|DrawBuffers|^glClear(?!Buffer|Tex)|CopyTex|ReadPixels"),
    ("texture", r"Tex|Texture|Sampler|Image|Mipmap"),
    ("buffer", r"Buffer"),
    ("state", r"."),
)


def category(native):
    return next(name for name, pattern in CATEGORIES if re.search(pattern, native))


def classify(native, routed):
    if native in routed:
        return "routed"
    return "draw-noop" if native.startswith(DRAW_PREFIXES) else "inert"


def main(mods_dir, mc_jar, src_dir, out_path, lwjgl_jar):
    load_caps(lwjgl_jar)
    routed, hooks, wrapped = overhaul_sources(src_dir)
    gl, blaze, jar_count = scan_mods(mods_dir)
    vanilla = Vanilla(mc_jar)

    raw = []
    for native, mods in gl.items():
        raw.append({"native": native, "status": classify(native, routed), "mods": sorted(mods),
                    "classes": sorted({c for s in mods.values() for c in s})[:6]})
    raw.sort(key=lambda r: (r["status"] == "routed", -len(r["mods"]), r["native"]))

    def blaze_rows(calls):
        rows = []
        for (owner, name, desc), users in calls.items():
            if vanilla.resolve(owner, name, desc) is None:
                continue
            for native, (via, at) in vanilla.reach((owner, name, desc), hooks, wrapped).items():
                status = "hooked" if via else classify(native, routed)
                rows.append({"member": owner.split("/")[-1] + "." + name, "native": native, "status": status,
                             "via": via or at, "users": sorted(users)})
        return rows

    through = defaultdict(lambda: {"members": set(), "mods": set(), "statuses": set(), "at": set()})
    for row in blaze_rows(blaze) + [dict(r, users=["minecraft/neoforge"]) for r in blaze_rows(vanilla.outside_blaze)]:
        e = through[row["native"]]
        e["members"].add(row["member"])
        e["mods"].update(row["users"])
        e["statuses"].add(row["status"])
        e["at"].add(row["via"])
    for native, classes in vanilla.outside_gl.items():
        e = through[native]
        e["mods"].add("minecraft/neoforge")
        e["statuses"].add(classify(native, routed))
        e["at"].update(sorted(classes)[:3])

    gaps = []
    for native, e in through.items():
        unhandled = sorted(e["statuses"] - {"routed", "hooked"})
        gaps.append({"native": native, "unhandled": unhandled, "statuses": sorted(e["statuses"]),
                     "members": sorted(e["members"])[:8], "mods": sorted(e["mods"]), "at": sorted(e["at"])[:6]})
    gaps.sort(key=lambda g: (not g["unhandled"], -len(g["mods"]), g["native"]))

    report = {"jars": jar_count, "routed_names": len(routed), "mixin_hooks": len(hooks) + len(wrapped),
              "raw_gl": raw, "through_blaze3d": gaps}
    with open(out_path, "w", encoding="utf-8") as h:
        json.dump(report, h, indent=1)

    def line(native, status, mods):
        shown = ", ".join(m.replace(".jar", "") for m in mods[:5]) + (" +%d" % (len(mods) - 5) if len(mods) > 5 else "")
        return "  %-40s %-10s %3d  %s" % (native, status, len(mods), shown)

    txt = [ "Jars scanned: %d, routed GL names: %d, mixin hooks: %d" % (jar_count, len(routed), len(hooks) + len(wrapped)), "",
            "== Raw GL calls from mods (not routed first, by mod count)"]
    for r in raw:
        txt.append(line(r["native"], r["status"], r["mods"]))
    per_mod = defaultdict(lambda: defaultdict(list))
    for r in raw:
        if r["status"] != "routed":
            for mod in r["mods"]:
                per_mod[mod][category(r["native"])].append(r["native"])
    txt += ["", "== Unrouted raw GL per mod (most first)"]
    for mod, groups in sorted(per_mod.items(), key=lambda m: -sum(len(v) for v in m[1].values())):
        txt.append("  %s  (%d)" % (mod.replace(".jar", ""), sum(len(v) for v in groups.values())))
        for name, _ in CATEGORIES:
            if groups.get(name):
                txt.append("      %-12s %s" % (name, " ".join(sorted(groups[name]))))
    txt += ["", "== GL natives reached through com.mojang.blaze3d (gaps first)"]
    for g in gaps:
        txt.append(line(g["native"], "/".join(g["unhandled"]) or "ok", g["mods"]))
        if g["unhandled"]:
            txt.append("      via " + "; ".join(g["members"][:5]) + " | at " + "; ".join(g["at"][:3]))
    with open(os.path.splitext(out_path)[0] + ".txt", "w", encoding="utf-8") as h:
        h.write("\n".join(txt) + "\n")
    s = defaultdict(int)
    for r in raw:
        s[r["status"]] += 1
    print("raw natives:", dict(s), "| blaze3d natives with gaps:", sum(1 for g in gaps if g["unhandled"]), "of", len(gaps))


if __name__ == "__main__":
    main(*sys.argv[1:6])
