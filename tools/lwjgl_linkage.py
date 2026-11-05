"""Checks that every LWJGL class, method and field a jar links against exists in the LWJGL the game ships.

Overhaul's runtime modules compile against a newer LWJGL than Minecraft loads, so an overload added later
(MemoryUtil.memFree(ByteBuffer) in 3.3.4, say) compiles cleanly and fails at run time with NoSuchMethodError.

Usage: python -I tools/lwjgl_linkage.py <jar to check> <lwjgl jar>... ; exits 1 when a reference is missing.
"""
import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gl_audit import ClassFile  # noqa: E402

PKG = "org/lwjgl/"
JDK = ("java/", "javax/", "jdk/", "sun/")
OBJECT = {"equals", "hashCode", "toString", "getClass", "clone", "finalize", "notify", "notifyAll", "wait"}


def classes(data, sink):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for info in z.infolist():
            n = info.filename
            if n.endswith(".class") and not n.startswith("META-INF/versions/") and not n.endswith("module-info.class"):
                sink(ClassFile(z.read(info)))
            elif n.endswith(".jar"):
                classes(z.read(info), sink)


def library(jars):
    table = {}
    for jar in jars:
        with open(jar, "rb") as f:
            classes(f.read(), lambda cf: table.__setitem__(cf.name, cf))
    return table


def found(table, owner, name, desc, field):
    """Member lookup along superclasses and interfaces, as the JVM resolves it. Members LWJGL inherits from the JDK
    are not resolved here, so only Object's own methods are taken on trust."""
    if not field and name in OBJECT:
        return True
    pending, seen = [owner], set()
    while pending:
        current = pending.pop()
        if current in seen or current.startswith(JDK):
            continue
        seen.add(current)
        cf = table.get(current)
        if cf is None:
            continue
        members = cf.fields if field else [(m[0], m[1]) for m in cf.methods]
        if (name, desc) in members:
            return True
        if cf.super:
            pending.append(cf.super)
        pending.extend(cf.interfaces)
    return False


def check(target, table):
    missing = set()

    def sink(cf):
        if cf.name.startswith(PKG):
            return
        for i, e in enumerate(cf.cp):
            if not e:
                continue
            if e[0] == 7:
                ref = cf.utf(e[1]).lstrip("[")
                if ref.startswith("L"):
                    ref = ref[1:-1]
                if ref.startswith(PKG) and ref not in table:
                    missing.add((cf.name, ref, "", "class"))
            elif e[0] in (9, 10, 11):
                owner, name, desc = cf.member(i)
                if owner.startswith(PKG) and owner in table and not found(table, owner, name, desc, e[0] == 9):
                    missing.add((cf.name, owner, name + desc, "field" if e[0] == 9 else "method"))

    with open(target, "rb") as f:
        classes(f.read(), sink)
    return sorted(missing)


def main(target, jars):
    missing = check(target, library(jars))
    for user, owner, member, kind in missing:
        print("%s %s.%s  (used by %s)" % (kind, owner, member, user))
    print("%d missing LWJGL reference(s)" % len(missing))
    return 1 if missing else 0


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2:]))
