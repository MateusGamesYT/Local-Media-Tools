"""The app's English text and its translation catalogs (app/src/main/assets/i18n/<tag>.txt).

The English text is collected from the code: the literal arguments of tr(…), trc(note, …), trn(n, one,
other) and N_(…), plus the Gallery's category names, groups and search words (assets/gallery/
categories.tsv). See i18n/I18n.kt (Catalog) for the file format.

    python3 catalog.py keys                 every entry, in the catalog format without translations
    python3 catalog.py missing <tag>        entries the language's catalog lacks (to translate)
    python3 catalog.py merge <tag> <file>   adds the entries of <file> (catalog format) to the language's
                                            catalog, drops entries the code no longer has, sorts it
    python3 catalog.py check                every catalog complete, with the same placeholders as the English

I18nCatalogTest checks the same rules in the build.
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'kotlin')
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
LANGS = {  # tag → number of plural forms (Language.pluralForms)
    'pt-BR': 2, 'es': 2, 'fr': 2, 'de': 2, 'it': 2, 'ru': 3, 'zh-Hans': 1, 'ja': 1, 'ko': 1,
}
NAMES = {'pt-BR': 'Português (Brasil)', 'es': 'Español', 'fr': 'Français', 'de': 'Deutsch', 'it': 'Italiano',
         'ru': 'Русский', 'zh-Hans': '简体中文', 'ja': '日本語', 'ko': '한국어'}


def unescape_kotlin(s):
    out = []; i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            if n == 'n': out.append('\n')
            elif n == 't': out.append('\t')
            elif n == 'r': out.append('\r')
            elif n in '"\'\\$': out.append(n)
            elif n == 'u': out.append(chr(int(s[i + 2:i + 6], 16))); i += 4
            else: raise ValueError('escape \\' + n)
            i += 2; continue
        out.append(c); i += 1
    return ''.join(out)


def literal_at(src, i):
    """The string literal starting at src[i] (a quote): its text and the index after it; None when it has a template."""
    if src[i] != '"' or src.startswith('"""', i): return None
    j = i + 1
    while j < len(src):
        c = src[j]
        if c == '\\': j += 2; continue
        if c == '$' and j + 1 < len(src) and (src[j + 1] == '{' or src[j + 1].isalpha()): return None
        if c == '"': return unescape_kotlin(src[i + 1:j]), j + 1
        j += 1
    return None


def skip_ws(src, i):
    while i < len(src) and src[i] in ' \t\r\n': i += 1
    return i


def skip_expr(src, i):
    """Index of the comma ending the argument that starts at i (parentheses, brackets and strings balanced)."""
    depth = 0
    while i < len(src):
        c = src[i]
        if c == '"':
            j = i + 1
            while j < len(src) and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            i = j + 1; continue
        if c in '([{': depth += 1
        elif c in ')]}':
            if depth == 0: return i
            depth -= 1
        elif c == ',' and depth == 0: return i
        i += 1
    return i


CALL = re.compile(r'(?<![\w.])(trn|trc|tr|N_)\(')


def strip_comments(src):
    out = []; i = 0
    while i < len(src):
        if src.startswith('//', i):
            j = src.find('\n', i); i = len(src) if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); i = len(src) if j < 0 else j + 2; continue
        if src[i] == '"':
            j = i + 1
            if src.startswith('"""', i):
                j = src.find('"""', i + 3) + 3
            else:
                while j < len(src) and src[j] != '"':
                    j += 2 if src[j] == '\\' else 1
                j += 1
            out.append(src[i:j]); i = j; continue
        if src[i] == "'":
            j = i + 1
            while j < len(src) and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1; continue
        out.append(src[i]); i += 1
    return ''.join(out)


def code_entries():
    """(context, english forms) → first place seen, from the Kotlin sources; and the calls that aren't literals."""
    found = {}; dynamic = []
    for dirpath, _, files in os.walk(SRC):
        for f in sorted(files):
            if not f.endswith('.kt'): continue
            path = os.path.join(dirpath, f)
            rel = os.path.relpath(path, ROOT)
            if rel.endswith('i18n/I18n.kt'): continue
            src = strip_comments(open(path, encoding='utf-8').read())
            for m in CALL.finditer(src):
                fn = m.group(1); i = m.end()
                line = src.count('\n', 0, m.start()) + 1
                args = []
                if fn == 'trn':
                    i = skip_expr(src, i) + 1
                want = {'tr': 1, 'N_': 1, 'trc': 2, 'trn': 2}[fn]
                ok = True
                for k in range(want):
                    i = skip_ws(src, i)
                    lit = literal_at(src, i) if i < len(src) else None
                    if lit is None: ok = False; break
                    args.append(lit[0]); i = skip_ws(src, lit[1])
                    if k < want - 1:
                        if i >= len(src) or src[i] != ',': ok = False; break
                        i += 1
                if not ok:
                    # Text passed on from an N_-marked value ("tr(labelEn)") or from the category table is fine.
                    arg = src[m.end():].lstrip().split(')')[0]
                    if not ((fn == 'tr' and re.fullmatch(r'[A-Za-z_]\w*', arg.strip())) or
                            (fn == 'trc' and arg.startswith('"search words for “$englishName”, separated by commas"'))):
                        dynamic.append(f'{rel}:{line}: {fn}(' + src[m.end():m.end() + 50].split('\n')[0])
                    continue
                key = (args[0], (args[1],)) if fn == 'trc' else (None, tuple(args))
                found.setdefault(key, f'{rel}:{line}')
    return found, dynamic


def tsv_entries():
    out = {}
    path = os.path.join(ASSETS, 'gallery', 'categories.tsv')
    for line in open(path, encoding='utf-8'):
        if not line.strip() or line.startswith('#'): continue
        c = line.rstrip('\n').split('\t')
        name, group = c[1], c[2]
        words = ','.join(w.strip().lower() for w in c[3].split(',') if w.strip())
        out.setdefault((None, (name,)), 'categories.tsv')
        out.setdefault((None, (group,)), 'categories.tsv')
        out.setdefault((f'search words for “{name}”, separated by commas', (words,)), 'categories.tsv')
    return out


def all_entries():
    code, dynamic = code_entries()
    tsv = tsv_entries()
    for k, v in tsv.items(): code.setdefault(k, v)
    return code, dynamic


def esc(s): return s.replace('\\', '\\\\').replace('\n', '\\n')
def unesc(s):
    out = []; i = 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            out.append({'n': '\n', '\\': '\\'}.get(s[i + 1], s[i] + s[i + 1])); i += 2
        else: out.append(s[i]); i += 1
    return ''.join(out)


def parse_catalog(text):
    """[(context, english forms, translations)] in file order."""
    out = []; eng = []; tr = []; ctx = None
    def flush():
        nonlocal eng, tr, ctx
        if eng: out.append((ctx, tuple(eng), list(tr)))
        eng = []; tr = []; ctx = None
    for n, raw in enumerate(text.split('\n')):
        line = raw.rstrip('\r')
        if not line.strip(): flush()
        elif line.startswith('#'): pass
        elif line.startswith('@ '):
            if eng: flush()
            ctx = line[2:]
        elif line.startswith('< ') or line == '<':
            if tr: flush()
            eng.append(unesc(line[2:]))
        elif line.startswith('> ') or line == '>':
            tr.append(unesc(line[2:]))
        else: raise ValueError(f'line {n + 1}: {line}')
    flush()
    return out


def fmt_entry(ctx, eng, tr):
    lines = []
    if ctx is not None: lines.append('@ ' + ctx)
    lines += ['< ' + esc(e) for e in eng]
    lines += ['> ' + esc(t) for t in tr]
    return '\n'.join(lines)


PH = re.compile(r'(?<!\{)\{(\d+)\}(?!\})')


def placeholders(s): return set(PH.findall(s))


def problems(tag, entries):
    """What's wrong with a language's entries (placeholders, plural forms, whitespace)."""
    out = []
    forms = LANGS[tag]
    for ctx, eng, tr in entries:
        where = f'{tag}: {eng[0][:60]!r}'
        if len(eng) == 1:
            if len(tr) != 1: out.append(f'{where}: needs 1 translation, has {len(tr)}'); continue
            if placeholders(tr[0]) != placeholders(eng[0]): out.append(f'{where}: placeholders {sorted(placeholders(tr[0]))} instead of {sorted(placeholders(eng[0]))}')
            t, e = tr[0], eng[0]
            # Spaces around the text matter where it is joined to other text (a list separator may drop them: "、").
            if not (ctx and ctx.startswith('between')) and ((t[:1].isspace() != e[:1].isspace()) or (t[-1:].isspace() != e[-1:].isspace())):
                out.append(f'{where}: leading/trailing spaces differ')
        else:
            if len(tr) != forms: out.append(f'{where}: needs {forms} plural forms, has {len(tr)}'); continue
            union = placeholders(eng[0]) | placeholders(eng[1])
            need = placeholders(eng[1]) - {'0'}
            for t in tr:
                if not placeholders(t) <= union: out.append(f'{where}: unknown placeholder in {t!r}')
                if not need <= placeholders(t): out.append(f'{where}: {t!r} lacks {sorted(need - placeholders(t))}')
        if ctx and ctx.startswith('search words') or ctx and ctx.startswith('search:'):
            if any(not w.strip() for w in tr[0].split(',')): out.append(f'{where}: empty search word')
    return out


def catalog_path(tag): return os.path.join(ASSETS, 'i18n', f'{tag}.txt')


def load(tag):
    p = catalog_path(tag)
    return parse_catalog(open(p, encoding='utf-8').read()) if os.path.exists(p) else []


def write(tag, entries):
    keys, _ = all_entries()
    order = list(keys.keys())
    pos = {k: i for i, k in enumerate(order)}
    kept = [(c, e, t) for c, e, t in entries if (c, e) in pos]
    kept.sort(key=lambda x: pos[(x[0], x[1])])
    head = (f'# {NAMES[tag]} — translations of the app\'s English text (see buildtools/i18n/catalog.py).\n'
            '# "< " lines: the English (two lines: singular and plural); "> " lines: the translation (one per plural form);\n'
            '# "@ " lines: which meaning of the English is meant.\n')
    os.makedirs(os.path.dirname(catalog_path(tag)), exist_ok=True)
    open(catalog_path(tag), 'w', encoding='utf-8').write(head + '\n' + '\n\n'.join(fmt_entry(*x) for x in kept) + '\n')
    return len(entries) - len(kept)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'check'
    keys, dynamic = all_entries()
    if cmd == 'keys':
        for (c, e), where in keys.items(): print('# ' + where + '\n' + fmt_entry(c, e, []) + '\n')
        if dynamic: print('\n'.join('# not a literal: ' + d for d in dynamic), file=sys.stderr)
    elif cmd == 'missing':
        tag = sys.argv[2]
        have = {(c, e) for c, e, _ in load(tag)}
        for (c, e), where in keys.items():
            if (c, e) not in have: print(fmt_entry(c, e, []) + '\n')
    elif cmd == 'merge':
        tag, f = sys.argv[2], sys.argv[3]
        new = parse_catalog(open(f, encoding='utf-8').read())
        bad = problems(tag, new)
        unknown = [e for c, e, _ in new if (c, e) not in keys]
        if bad or unknown:
            print('\n'.join(bad + ['not in the code: ' + repr(u) for u in unknown])); sys.exit(1)
        cur = {(c, e): (c, e, t) for c, e, t in load(tag)}
        for c, e, t in new: cur[(c, e)] = (c, e, t)
        dropped = write(tag, list(cur.values()))
        missing = sum(1 for k in keys if k not in cur)
        print(f'{tag}: {len(cur) - dropped} entries, {missing} still missing, {dropped} dropped')
    elif cmd == 'check':
        bad = []
        for tag in LANGS:
            entries = load(tag)
            have = {(c, e) for c, e, _ in entries}
            bad += problems(tag, entries)
            bad += [f'{tag}: missing {e[0][:70]!r} ({w})' for (c, e), w in keys.items() if (c, e) not in have]
            bad += [f'{tag}: not in the code: {e[0][:70]!r}' for (c, e) in have if (c, e) not in keys]
        if dynamic: bad += ['not a literal: ' + d for d in dynamic]
        print('\n'.join(bad[:200]) if bad else f'OK: {len(keys)} entries in {len(LANGS)} languages')
        print(f'{len(bad)} problems' if bad else '', end='')
        sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
