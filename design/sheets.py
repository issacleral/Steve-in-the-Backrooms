"""BackroomsCraft design sheets: preflight check and code generation.

    python design/sheets.py preflight   list every unfilled cell and unresolved reference
    python design/sheets.py gen         write the generated Java and resource files (runs preflight first)

The sheets in design/sheets/*.json are the source of truth. Change a sheet, then run gen.

A column is declared as "<type>; <what it is>". Types:
    key              the row's id
    str int float bool
    strs floats      lists
    ref:<sheet>      the id of a row of another sheet        refs:<sheet>   a list of them
    etb              a /Game/... object in Escape the Backrooms' files      etbs   a list of them
    file             a source file under mod/
    class            a class path under the decompiled Minecraft or Fabric API sources
A type ending in "?" may be left empty.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHEETS = os.path.join(ROOT, "design", "sheets")
MOD = os.path.join(ROOT, "mod")
TOOLS = [os.path.join(ROOT, "tools"), os.path.join(os.path.dirname(ROOT), "Bodycam + Minecraft", "tools")]
SOURCES = [os.path.join(t, s) for t in TOOLS for s in ("mc-src", "fabric-src")]
MODID = "backroomscraft"
PACKAGE = "gg.backroomscraft"


def load():
    sheets = {}
    for path in sorted(glob.glob(os.path.join(SHEETS, "*.json"))):
        with open(path, encoding="utf-8") as f:
            sheets[os.path.splitext(os.path.basename(path))[0]] = json.load(f)
    return sheets


def column_type(kind):
    return kind.split(";")[0].strip()


# ---------------------------------------------------------------------------------------------- preflight

def etb_files():
    """Lower-case paths of every file in the installed Escape the Backrooms pak, or None when it is not installed."""
    sys.path.insert(0, os.path.join(ROOT, "recon"))
    try:
        import etbpak
        if not os.path.isfile(etbpak.PAK):
            return None
        return {k.lower() for k in etbpak.index()}
    except Exception as e:
        print("note: could not read the Escape the Backrooms index (%s)" % e)
        return None


def etb_exists(files, game_path):
    if files is None:
        return True
    if not game_path.startswith("/Game/"):
        return False
    base = ("EscapeTheBackrooms/Content/" + game_path[6:]).lower()
    return (base + ".uasset") in files or (base + ".umap") in files


def preflight(sheets, quiet=False):
    problems, pending = [], []
    keys = {name: {row.get("id") for row in sheet["rows"]} for name, sheet in sheets.items()}
    files = etb_files()
    if files is None:
        print("note: Escape the Backrooms is not installed here, so its paths were not checked")

    for name, sheet in sheets.items():
        columns = sheet["_columns"]
        seen = set()
        for row in sheet["rows"]:
            where = "%s.%s" % (name, row.get("id", "?"))
            if row.get("id") in seen:
                problems.append("%s: duplicate id" % where)
            seen.add(row.get("id"))
            for column, kind in columns.items():
                typ = column_type(kind)
                optional = typ.endswith("?")
                typ = typ.rstrip("?")
                if column not in row:
                    problems.append("%s: no cell for column '%s'" % (where, column))
                    continue
                value = row[column]
                is_list = typ in ("strs", "floats", "etbs") or typ.startswith("refs:")
                if value is None or value == "TODO" or (value == "" and not optional) or (is_list and not isinstance(value, list)):
                    problems.append("%s.%s: unfilled" % (where, column))
                    continue
                values = value if isinstance(value, list) else [value]
                values = [v for v in values if not (optional and v == "")]
                if typ.startswith("ref:") or typ.startswith("refs:"):
                    target = typ.split(":")[1]
                    for v in values:
                        if v not in keys.get(target, ()):
                            problems.append("%s.%s: '%s' is not a row of %s" % (where, column, v, target))
                elif typ in ("etb", "etbs"):
                    for v in values:
                        if not etb_exists(files, v):
                            problems.append("%s.%s: not in Escape the Backrooms' files: %s" % (where, column, v))
                elif typ == "file":
                    for v in values:
                        if not os.path.isfile(os.path.join(MOD, v)):
                            problems.append("%s.%s: not written yet: %s" % (where, column, v))
                elif typ == "class":
                    for v in values:
                        if not any(os.path.isfile(os.path.join(s, v + ".java")) for s in SOURCES):
                            problems.append("%s.%s: no such class in the decompiled sources: %s" % (where, column, v))
                elif typ in ("int", "float") and (isinstance(value, bool) or not isinstance(value, (int, float))):
                    problems.append("%s.%s: not a number" % (where, column))
                elif typ == "bool" and not isinstance(value, bool):
                    problems.append("%s.%s: not true/false" % (where, column))
            for column in row:
                if column not in columns and column != "verified":
                    problems.append("%s: cell '%s' is not a column of the sheet" % (where, column))
            verified = row.get("verified", "")
            if not verified:
                problems.append("%s.verified: unfilled" % where)
            elif verified.startswith("pending"):
                pending.append("%s: %s" % (where, verified))

    for row in sheets.get("hooks", {"rows": []})["rows"]:
        where = "hooks.%s" % row["id"]
        source = next((os.path.join(s, row["target"] + ".java") for s in SOURCES if os.path.isfile(os.path.join(s, row["target"] + ".java"))), None)
        if source and row["member"] not in open(source, encoding="utf-8").read():
            problems.append("%s.member: '%s' is not in %s" % (where, row["member"], row["target"]))
        path = os.path.join(MOD, row["file"])
        if os.path.isfile(path) and ("hook: %s" % row["id"]) not in open(path, encoding="utf-8").read():
            problems.append("%s.file: %s does not mark 'hook: %s'" % (where, row["file"], row["id"]))

    if not quiet:
        cells = sum(len(s["_columns"]) * len(s["rows"]) for s in sheets.values())
        print("%d sheets, %d rows, %d cells" % (len(sheets), sum(len(s["rows"]) for s in sheets.values()), cells))
        for p in problems:
            print("  PROBLEM  " + p)
        for p in pending:
            print("  pending  " + p)
        print("preflight: %s (%d problems, %d rows not yet verified in game)" % ("CLEAN" if not problems else "NOT CLEAN", len(problems), len(pending)))
    return problems


# ---------------------------------------------------------------------------------------------- generation

def jstr(v):
    return json.dumps(v, ensure_ascii=True)


def camel(name, first_upper=False):
    parts = name.split("_")
    out = parts[0] + "".join(p.capitalize() for p in parts[1:])
    return out[0].upper() + out[1:] if first_upper else out


def java_type(typ):
    typ = typ.rstrip("?")
    if typ == "int": return "int"
    if typ == "float": return "float"
    if typ == "bool": return "boolean"
    if typ in ("strs", "etbs") or typ.startswith("refs:"): return "List<String>"
    if typ == "floats": return "List<Float>"
    return "String"


def java_value(typ, v):
    typ = typ.rstrip("?")
    if typ == "int": return str(int(v))
    if typ == "float": return "%sf" % float(v)
    if typ == "bool": return "true" if v else "false"
    if typ in ("strs", "etbs") or typ.startswith("refs:"): return "List.of(" + ", ".join(jstr(x) for x in v) + ")"
    if typ == "floats": return "List.of(" + ", ".join("%sf" % float(x) for x in v) + ")"
    return jstr(v)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def write_json(path, data):
    write(path, json.dumps(data, indent=2) + "\n")


def gen(sheets):
    out = ["// Generated by design/sheets.py from design/sheets/*.json. Do not edit: change the sheet and run gen.",
           "package %s.gen;" % PACKAGE, "", "import java.util.List;", "",
           "public final class Sheets {", "\tprivate Sheets() {", "\t}", ""]
    for name, sheet in sheets.items():
        kind = sheet.get("_kind", "rows")
        if kind == "doc":
            continue
        columns = sheet["_columns"]
        if kind == "constants":
            # One constant per row: <SHEET>.<ID> = value
            out.append("\t/** %s */" % sheet["_sheet"])
            out.append("\tpublic static final class %s {" % camel(name, True))
            for row in sheet["rows"]:
                v = row["value"]
                const = row["id"].upper()
                if isinstance(v, bool):
                    out.append("\t\tpublic static final boolean %s = %s;" % (const, "true" if v else "false"))
                elif isinstance(v, str):
                    out.append("\t\tpublic static final String %s = %s;" % (const, jstr(v)))
                elif isinstance(v, list):
                    out.append("\t\tpublic static final float[] %s = {%s};" % (const, ", ".join("%sf" % float(x) for x in v)))
                else:
                    out.append("\t\tpublic static final float %s = %sf;" % (const, float(v)))
            out += ["", "\t\tprivate %s() {" % camel(name, True), "\t\t}", "\t}", ""]
            continue
        record = sheet["_record"]
        fields = [(camel(c), column_type(k)) for c, k in columns.items()]
        out.append("\t/** %s */" % sheet["_sheet"])
        out.append("\tpublic record %s(%s) {" % (record, ", ".join("%s %s" % (java_type(t), f) for f, t in fields)))
        out += ["\t}", ""]
        const = name.upper()
        out.append("\tpublic static final List<%s> %s = List.of(" % (record, const))
        rows = []
        for row in sheet["rows"]:
            rows.append("\t\t\tnew %s(%s)" % (record, ", ".join(java_value(column_type(k), row[c]) for c, k in columns.items())))
        out.append(",\n".join(rows) + ");")
        out.append("")
        out += ["\tpublic static %s %s(String id) {" % (record, camel(record[0].lower() + record[1:])),
                "\t\tfor (%s row : %s) {" % (record, const), "\t\t\tif (row.id().equals(id)) {", "\t\t\t\treturn row;", "\t\t\t}", "\t\t}",
                "\t\treturn null;", "\t}", ""]
    out += ["}", ""]
    write(os.path.join(MOD, "src/main/java", PACKAGE.replace(".", "/"), "gen/Sheets.java"), "\n".join(out))

    assets = os.path.join(MOD, "src/main/resources/assets", MODID)
    lang = {}
    for row in sheets["text"]["rows"]:
        lang[row["key"]] = row["en"]
    for item in sheets["items"]["rows"]:
        lang["item.%s.%s" % (MODID, item["id"])] = item["name"]
        write_json(os.path.join(assets, "items", item["id"] + ".json"), {"model": {
            "type": "minecraft:special", "base": "%s:item/etb_base" % MODID,
            "model": {"type": "%s:etb_mesh" % MODID, "item": item["id"]}}})
    for entity in sheets["entities"]["rows"]:
        lang["entity.%s.%s" % (MODID, entity["id"])] = entity["name"]
    write_json(os.path.join(assets, "lang", "en_us.json"), lang)
    write_json(os.path.join(assets, "models", "item", "etb_base.json"), {"parent": "minecraft:item/generated", "gui_light": "front"})
    write_json(os.path.join(assets, "sounds.json"), {"etb": {"subtitle": "subtitles.%s.etb" % MODID,
                                                             "sounds": [{"name": "fabric-sound-api-v1:empty", "stream": True}]}})
    print("generated Sheets.java, %d item models, lang (%d keys), sounds.json" % (len(sheets["items"]["rows"]), len(lang)))


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "preflight"
    all_sheets = load()
    found = preflight(all_sheets)
    if command == "gen":
        gen(all_sheets)
    elif found:
        sys.exit(1)
