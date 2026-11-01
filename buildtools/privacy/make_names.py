"""Writes the name lists the app's private-details finder uses (app/src/main/assets/privacy/).

    python3 make_names.py <names dir>

The names dir holds (downloaded from Hugging Face, see README.md):
  us_ssa.csv            US Social Security baby names 1880-, national (nkrishnaswami/us-ssa-baby-names-national, CC0)
  ibge_ranking.parquet  Brazil's 2022 census names and surnames (olob0/ibge-nomes-no-brasil-2022, CC BY 4.0; data by IBGE)
  insee.csv             French first names, INSEE (eltorio/french_first_names_insee_2024, MIT)

first_names.txt: the most common given names (lower case, without accents), one per line.
surnames.txt: the most common Brazilian surnames, the same way. Only names of at least three letters.
"""
import csv, collections, os, sys, unicodedata
import pandas as pd

src = sys.argv[1]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'assets', 'privacy')
os.makedirs(out, exist_ok=True)

def plain(s):
    s = unicodedata.normalize('NFKD', s.strip().lower())
    return ''.join(c for c in s if not unicodedata.combining(c))

def ok(n): return len(n) >= 3 and n.isalpha() and n.isascii()

def top(counts, n):
    return [k for k, _ in sorted(counts.items(), key=lambda kv: -kv[1]) if ok(k)][:n]

us = collections.Counter()
with open(os.path.join(src, 'us_ssa.csv'), newline='') as f:
    for r in csv.DictReader(f):
        if int(r['year']) >= 1930: us[plain(r['name'])] += int(r['count'])
fr = collections.Counter()
with open(os.path.join(src, 'insee.csv'), newline='') as f:
    for r in csv.DictReader(f):
        n = plain(r['firstname'])
        if not n.startswith('_'): fr[n] += int(r['occurrences'])
br = pd.read_parquet(os.path.join(src, 'ibge_ranking.parquet'))
given = collections.Counter({plain(n): int(c) for n, c in br[br.name_type == 'given'][['name_text', 'frequency']].itertuples(index=False)})
family = collections.Counter({plain(n): int(c) for n, c in br[br.name_type == 'surname'][['name_text', 'frequency']].itertuples(index=False)})

first = sorted(set(top(us, 4000)) | set(top(given, 3000)) | set(top(fr, 2000)))
last = sorted(top(family, 3000))
for name, rows in (('first_names.txt', first), ('surnames.txt', last)):
    with open(os.path.join(out, name), 'w') as f: f.write('\n'.join(rows) + '\n')
    print(name, len(rows))
